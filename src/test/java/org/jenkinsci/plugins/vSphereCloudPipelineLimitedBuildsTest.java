package org.jenkinsci.plugins;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.is;

import hudson.Launcher;
import hudson.model.AbstractBuild;
import hudson.model.BuildListener;
import hudson.model.Computer;
import hudson.model.Executor;
import hudson.model.FreeStyleBuild;
import hudson.model.FreeStyleProject;
import hudson.model.Node.Mode;
import hudson.model.Result;
import hudson.model.TaskListener;
import hudson.slaves.ComputerLauncher;
import hudson.slaves.ComputerListener;
import hudson.slaves.OfflineCause;
import hudson.slaves.RetentionStrategy;
import hudson.slaves.SlaveComputer;
import hudson.util.OneShotEvent;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;
import org.jenkinsci.plugins.vsphere.AgentRecycler;
import org.jenkinsci.plugins.vsphere.AgentUsageRegistry;
import org.jenkinsci.plugins.vsphere.AgentUsageRegistry.RecycleOutcome;
import org.jenkinsci.plugins.vsphere.LimitedBuildsOfflineCause;
import org.jenkinsci.plugins.vsphere.VSphereConnectionConfig;
import org.jenkinsci.plugins.workflow.cps.CpsFlowDefinition;
import org.jenkinsci.plugins.workflow.job.WorkflowJob;
import org.jenkinsci.plugins.workflow.job.WorkflowRun;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.jvnet.hudson.test.JenkinsRule;
import org.jvnet.hudson.test.SleepBuilder;
import org.jvnet.hudson.test.TestBuilder;
import org.jvnet.hudson.test.TestExtension;
import org.jvnet.hudson.test.junit.jupiter.WithJenkins;

/**
 * "Disconnect after Limited Builds" with real Pipeline and freestyle builds on a vSphere agent. The agent is a real, connected one,
 * except that its launcher does not talk to a vSphere system (there is none to talk to in a test): it starts the
 * agent in a separate process the way {@link JenkinsRule#createSlave} does.
 */
@WithJenkins
class vSphereCloudPipelineLimitedBuildsTest {

    private static final String CLOUD = "some-cloud";

    /**
     * Connects and disconnects like {@link JenkinsRule#createSlave}, with none of the vSphere work. Delegating
     * {@code afterDisconnect} matters: that is what stops the agent process.
     */
    private static final class InProcessLauncher extends vSphereCloudLauncher {
        /** Not persisted with the agent's configuration. */
        final transient AtomicInteger launches = new AtomicInteger();

        InProcessLauncher(ComputerLauncher delegate, String vmName, int limit) {
            super(delegate, CLOUD, vmName, false, false, "", "60", "Nothing", Integer.toString(limit));
        }

        @Override
        public void launch(SlaveComputer computer, TaskListener listener) throws IOException, InterruptedException {
            launches.incrementAndGet();
            getDelegate().launch(computer, listener);
        }

        /** When set, a disconnect is not done until it is opened. */
        static volatile CountDownLatch holdDisconnect;

        /** Whether a disconnect says it went wrong. */
        static volatile boolean failDisconnect;

        static final AtomicInteger DISCONNECTS = new AtomicInteger();

        @Override
        public void afterDisconnect(SlaveComputer computer, TaskListener listener) {
            getDelegate().afterDisconnect(computer, listener);
            DISCONNECTS.incrementAndGet();
            final CountDownLatch hold = holdDisconnect;
            if (hold != null) {
                try {
                    hold.await(60, TimeUnit.SECONDS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
            AgentRecycler.idleActionDone(
                    computer.getName(), failDisconnect ? RecycleOutcome.FAILED : RecycleOutcome.COMPLETED);
        }
    }

    @AfterEach
    void resetTheLauncher() {
        InProcessLauncher.holdDisconnect = null;
        InProcessLauncher.failDisconnect = false;
        InProcessLauncher.DISCONNECTS.set(0);
        AgentRecycler.get().setClock(System::currentTimeMillis);
    }

    /** Waits for the condition, as the recycling of an agent is asynchronous. */
    private static void waitFor(String what, BooleanSupplier condition) throws InterruptedException {
        final long deadline = System.nanoTime() + 60_000_000_000L;
        while (!condition.getAsBoolean()) {
            if (System.nanoTime() > deadline) {
                throw new AssertionError("Timed out waiting for " + what);
            }
            Thread.sleep(100);
        }
    }

    /** The cloud the agents belong to; there is no vSphere system behind it. */
    private static void ensureCloud(JenkinsRule r) {
        // An agent is looked at soon after a run on it ends, not only after the five seconds it takes in production.
        AgentRecycler.get().setCheckDelayMs(500);
        if (vSphereCloud.findAllVsphereClouds(null).isEmpty()) {
            r.jenkins.clouds.add(new vSphereCloud(
                    new VSphereConnectionConfig("vsHost", false, "credentialsId"),
                    CLOUD,
                    100,
                    0,
                    false,
                    Collections.emptyList()));
        }
    }

    private static InProcessLauncher launcherOf(vSphereCloudSlave agent) {
        return (InProcessLauncher) agent.getLauncher();
    }

    /** The agent is recycled once its connection is closed (not merely marked offline). */
    private static boolean isDisconnected(vSphereCloudSlave agent) {
        final SlaveComputer computer = (SlaveComputer) agent.toComputer();
        return computer == null || computer.getChannel() == null;
    }

    private static vSphereCloudSlave connectedAgent(JenkinsRule r, String name, String label, int executors, int limit)
            throws Exception {
        final vSphereCloudSlave agent = newAgent(r, name, label, executors, limit);
        r.jenkins.addNode(agent);
        final SlaveComputer computer = (SlaveComputer) agent.toComputer();
        computer.connect(false).get();
        waitFor(name + " to be online", computer::isOnline);
        return agent;
    }

    /** An agent that is not part of Jenkins yet. */
    private static vSphereCloudSlave newAgent(JenkinsRule r, String name, String label, int executors, int limit)
            throws Exception {
        ensureCloud(r);
        final vSphereCloudSlave agent = new vSphereCloudSlave(
                name,
                "",
                new File(r.jenkins.getRootDir(), "agent-" + name).getPath(),
                Integer.toString(executors),
                Mode.NORMAL,
                label,
                new ComputerLauncher() {},
                RetentionStrategy.NOOP,
                Collections.emptyList(),
                CLOUD,
                "vm-" + name,
                false,
                false,
                "",
                "60",
                "Nothing",
                Integer.toString(limit));
        agent.setLauncher(new InProcessLauncher(r.createComputerLauncher(null), "vm-" + name, limit));
        return agent;
    }

    private static WorkflowJob pipeline(JenkinsRule r, String name, String script) throws Exception {
        final WorkflowJob job = r.createProject(WorkflowJob.class, name);
        job.setDefinition(new CpsFlowDefinition(script, true));
        return job;
    }

    @Test
    void severalNodeBlocksOfOnePipelineRunAreOneUse(JenkinsRule r) throws Exception {
        final vSphereCloudSlave agent = connectedAgent(r, "agent", "lbl", 1, 5);
        final WorkflowJob job = pipeline(r, "seq", "node('lbl') { echo 'first' }\nnode('lbl') { echo 'second' }\n");

        final WorkflowRun run = r.buildAndAssertSuccess(job);

        r.assertLogContains("second", run);
        assertThat(agent.getNumberOfLimitedTestRuns(), is(1));
        assertThat(vSphereCloudSlave.hasLimitedTestRun(run), is(false));
    }

    @Test
    void aRunThatMovesToAnotherAgentIsCountedOnTheFirstOneOnly(JenkinsRule r) throws Exception {
        final vSphereCloudSlave first = connectedAgent(r, "first", "a", 1, 5);
        final vSphereCloudSlave second = connectedAgent(r, "second", "b", 1, 5);
        final WorkflowJob job = pipeline(r, "two", "node('a') { echo 'on a' }\nnode('b') { echo 'on b' }\n");

        r.buildAndAssertSuccess(job);

        assertThat(first.getNumberOfLimitedTestRuns(), is(1));
        assertThat(second.getNumberOfLimitedTestRuns(), is(0));
    }

    @Test
    void parallelPipelineRunsAreCountedEach(JenkinsRule r) throws Exception {
        final vSphereCloudSlave agent = connectedAgent(r, "agent", "lbl", 2, 5);
        final File gate = new File(r.jenkins.getRootDir(), "gate");
        final String script = "node('lbl') { waitUntil { fileExists('"
                + gate.getAbsolutePath().replace('\\', '/') + "') } }\n";
        final WorkflowRun run1 = pipeline(r, "one", script).scheduleBuild2(0).waitForStart();
        final WorkflowRun run2 = pipeline(r, "two", script).scheduleBuild2(0).waitForStart();

        // Both runs are inside their node blocks at the same time, until the gate is opened. An executor is busy
        // slightly before the block it was given is counted, so wait for both.
        waitFor(
                "both runs to be using the agent, and to be counted",
                () -> agent.toComputer().countBusy() == 2 && agent.getNumberOfLimitedTestRuns() == 2);
        assertThat(agent.toComputer().countBusy(), is(2));
        assertThat(agent.getNumberOfLimitedTestRuns(), is(2));
        assertThat(vSphereCloudSlave.hasLimitedTestRun(run1), is(true));
        assertThat(vSphereCloudSlave.hasLimitedTestRun(run2), is(true));
        Files.createFile(gate.toPath());
        r.assertBuildStatusSuccess(r.waitForCompletion(run1));
        r.assertBuildStatusSuccess(r.waitForCompletion(run2));

        assertThat(agent.getNumberOfLimitedTestRuns(), is(2));
    }

    @Test
    void parallelBranchesOfOneRunAreOneUse(JenkinsRule r) throws Exception {
        final vSphereCloudSlave agent = connectedAgent(r, "agent", "lbl", 2, 5);
        final WorkflowJob job = pipeline(
                r, "branches", "parallel a: { node('lbl') { echo 'in a' } }, b: { node('lbl') { echo 'in b' } }\n");

        r.buildAndAssertSuccess(job);

        assertThat(agent.getNumberOfLimitedTestRuns(), is(1));
    }

    @Test
    void theAgentIsRecycledWhenTheRunReachingTheLimitIsOverNotBetweenItsBlocks(JenkinsRule r) throws Exception {
        final vSphereCloudSlave agent = connectedAgent(r, "agent", "lbl", 1, 1);
        final WorkflowJob job = pipeline(r, "limited", "node('lbl') { echo 'first' }\nnode('lbl') { echo 'second' }\n");

        final WorkflowRun run = r.buildAndAssertSuccess(job);

        // Not recycled (and so not launched again) in between the two blocks...
        assertThat(launcherOf(agent).launches.get(), is(1));
        r.assertLogContains("second", run);
        // ...but once the run is over.
        waitFor("the agent to be recycled", () -> isDisconnected(agent));
        waitFor("the count to start again", () -> agent.getNumberOfLimitedTestRuns() == 0);
        assertThat(vSphereCloudSlave.hasLimitedTestRun(run), is(false));
    }

    /** What the agents are set temporarily offline with, as text. */
    private static final List<String> OFFLINE_REASONS = new CopyOnWriteArrayList<>();

    @TestExtension("theAgentIsSetOfflineForTheRightReasonWhileItIsRecycled")
    public static class RecordOfflineReasons extends ComputerListener {
        @Override
        public void onTemporarilyOffline(Computer computer, OfflineCause cause) {
            OFFLINE_REASONS.add(String.valueOf(cause));
        }
    }

    @Test
    void theAgentIsSetOfflineForTheRightReasonWhileItIsRecycled(JenkinsRule r) throws Exception {
        OFFLINE_REASONS.clear();
        final vSphereCloudSlave agent = connectedAgent(r, "agent", "lbl", 1, 1);
        final FreeStyleProject project = r.createFreeStyleProject();
        project.setAssignedLabel(r.jenkins.getLabel("lbl"));

        r.buildAndAssertSuccess(project);

        waitFor("the agent to be recycled", () -> isDisconnected(agent));
        // It is set offline once, and the reason says so (not that it is back online).
        assertThat(OFFLINE_REASONS.size(), is(1));
        assertThat(OFFLINE_REASONS.get(0), containsString("Marking the agent as offline"));
    }

    @Test
    void aFailedRunStillRecyclesTheAgent(JenkinsRule r) throws Exception {
        final vSphereCloudSlave agent = connectedAgent(r, "agent", "lbl", 1, 1);
        final WorkflowJob job = pipeline(r, "failing", "node('lbl') { error 'on purpose' }\n");

        final WorkflowRun run = r.assertBuildStatus(Result.FAILURE, job.scheduleBuild2(0));

        waitFor("the agent to be recycled", () -> isDisconnected(agent));
        waitFor("the count to start again", () -> agent.getNumberOfLimitedTestRuns() == 0);
        assertThat(vSphereCloudSlave.hasLimitedTestRun(run), is(false));
    }

    @Test
    void anAbortedRunStillRecyclesTheAgent(JenkinsRule r) throws Exception {
        final vSphereCloudSlave agent = connectedAgent(r, "agent", "lbl", 1, 1);
        final WorkflowJob job = pipeline(r, "aborted", "node('lbl') { echo 'inside'; sleep 600 }\n");
        final WorkflowRun run = job.scheduleBuild2(0).waitForStart();
        r.waitForMessage("inside", run);

        run.doStop();

        r.assertBuildStatus(Result.ABORTED, r.waitForCompletion(run));
        waitFor("the agent to be recycled", () -> isDisconnected(agent));
        waitFor("the count to start again", () -> agent.getNumberOfLimitedTestRuns() == 0);
        assertThat(vSphereCloudSlave.hasLimitedTestRun(run), is(false));
    }

    @Test
    void aFreestyleBuildReachingTheLimitRecyclesTheAgent(JenkinsRule r) throws Exception {
        final vSphereCloudSlave agent = connectedAgent(r, "agent", "lbl", 1, 1);
        final FreeStyleProject project = r.createFreeStyleProject();
        project.setAssignedLabel(r.jenkins.getLabel("lbl"));

        final FreeStyleBuild build = r.buildAndAssertSuccess(project);

        waitFor("the agent to be recycled", () -> isDisconnected(agent));
        waitFor("the count to start again", () -> agent.getNumberOfLimitedTestRuns() == 0);
        assertThat(vSphereCloudSlave.hasLimitedTestRun(build), is(false));
        // It is only recycled, not left out of service.
        waitFor("the agent to be back in service", () -> !agent.toComputer().isTemporarilyOffline());
    }

    @Test
    void aFreestyleBuildBeyondTheLimitIsAborted(JenkinsRule r) throws Exception {
        connectedAgent(r, "agent", "lbl", 2, 1);
        final OneShotEvent insideFirst = new OneShotEvent();
        final OneShotEvent releaseFirst = new OneShotEvent();
        final FreeStyleProject first = r.createFreeStyleProject();
        first.setAssignedLabel(r.jenkins.getLabel("lbl"));
        first.getBuildersList().add(new TestBuilder() {
            @Override
            public boolean perform(AbstractBuild<?, ?> build, Launcher launcher, BuildListener listener)
                    throws InterruptedException {
                insideFirst.signal();
                releaseFirst.block();
                return true;
            }
        });
        final FreeStyleBuild firstBuild = first.scheduleBuild2(0).waitForStart();
        try {
            insideFirst.block(60_000);
            assertThat(insideFirst.isSignaled(), is(true));
            final FreeStyleProject second = r.createFreeStyleProject();
            second.setAssignedLabel(r.jenkins.getLabel("lbl"));

            // The first build used the one allowed run of this agent, which still has a free executor.
            final FreeStyleBuild secondBuild = r.assertBuildStatus(Result.ABORTED, second.scheduleBuild2(0));

            r.assertLogContains("Terminating build due to limited build count", secondBuild);
        } finally {
            releaseFirst.signal();
        }
        r.assertBuildStatusSuccess(r.waitForCompletion(firstBuild));
    }

    private final List<FreeStyleBuild> runningBuilds = new ArrayList<>();

    /** A build that is going on and does not use any agent. */
    private FreeStyleBuild runningBuild(JenkinsRule r) throws Exception {
        final FreeStyleProject project = r.createFreeStyleProject();
        project.getBuildersList().add(new SleepBuilder(600_000));
        final FreeStyleBuild build = project.scheduleBuild2(0).waitForStart();
        runningBuilds.add(build);
        return build;
    }

    @AfterEach
    void stopRunningBuilds() {
        for (FreeStyleBuild build : runningBuilds) {
            final Executor executor = build.getExecutor();
            if (executor != null) {
                executor.interrupt();
            }
        }
    }

    private static String gateScript(JenkinsRule r, File gate) {
        return "node('lbl') { waitUntil { fileExists('" + gate.getAbsolutePath().replace('\\', '/') + "') } }\n";
    }

    @Test
    void anAbortedOverLimitBuildDoesNotStopTheAgentFromBeingRecycled(JenkinsRule r) throws Exception {
        final vSphereCloudSlave agent = connectedAgent(r, "agent", "lbl", 2, 1);
        final OneShotEvent insideFirst = new OneShotEvent();
        final OneShotEvent releaseFirst = new OneShotEvent();
        final FreeStyleProject first = r.createFreeStyleProject();
        first.setAssignedLabel(r.jenkins.getLabel("lbl"));
        first.getBuildersList().add(new TestBuilder() {
            @Override
            public boolean perform(AbstractBuild<?, ?> build, Launcher launcher, BuildListener listener)
                    throws InterruptedException {
                insideFirst.signal();
                releaseFirst.block();
                return true;
            }
        });
        final FreeStyleBuild firstBuild = first.scheduleBuild2(0).waitForStart();
        try {
            insideFirst.block(60_000);
            final FreeStyleProject second = r.createFreeStyleProject();
            second.setAssignedLabel(r.jenkins.getLabel("lbl"));
            r.assertBuildStatus(Result.ABORTED, second.scheduleBuild2(0));
            // The aborted build was not one of the uses of the agent, so it did not use any of them up.
            assertThat(agent.getNumberOfLimitedTestRuns(), is(1));
        } finally {
            releaseFirst.signal();
        }
        r.assertBuildStatusSuccess(r.waitForCompletion(firstBuild));

        waitFor("the agent to be recycled", () -> isDisconnected(agent));
        waitFor("the count to start again", () -> agent.getNumberOfLimitedTestRuns() == 0);
    }

    @Test
    void theCountSurvivesTheAgentBeingSaved(JenkinsRule r) throws Exception {
        final vSphereCloudSlave agent = connectedAgent(r, "agent", "lbl", 1, 5);
        final File gate = new File(r.jenkins.getRootDir(), "gate");
        final WorkflowRun run =
                pipeline(r, "held", gateScript(r, gate)).scheduleBuild2(0).waitForStart();
        waitFor("the run to be counted", () -> agent.getNumberOfLimitedTestRuns() == 1);

        // What saving the configuration of an agent does: its node is replaced by a new one.
        final vSphereCloudSlave saved = newAgent(r, "agent", "lbl", 1, 5);
        r.jenkins.getNodesObject().replaceNode(agent, saved);

        assertThat(saved.getNumberOfLimitedTestRuns(), is(1));
        Files.createFile(gate.toPath());
        r.assertBuildStatusSuccess(r.waitForCompletion(run));
        assertThat(saved.getNumberOfLimitedTestRuns(), is(1));
    }

    @Test
    void theAgentIsStillRecycledAfterBeingSaved(JenkinsRule r) throws Exception {
        final vSphereCloudSlave agent = connectedAgent(r, "agent", "lbl", 1, 1);
        final File gate = new File(r.jenkins.getRootDir(), "gate");
        final WorkflowRun run =
                pipeline(r, "held", gateScript(r, gate)).scheduleBuild2(0).waitForStart();
        waitFor("the run to be counted", () -> agent.getNumberOfLimitedTestRuns() == 1);
        final vSphereCloudSlave saved = newAgent(r, "agent", "lbl", 1, 1);
        r.jenkins.getNodesObject().replaceNode(agent, saved);

        Files.createFile(gate.toPath());
        r.assertBuildStatusSuccess(r.waitForCompletion(run));

        waitFor("the agent to be recycled", () -> isDisconnected(saved));
        waitFor("the count to start again", () -> saved.getNumberOfLimitedTestRuns() == 0);
    }

    @Test
    void removingTheLimitWhileARunIsUsingTheAgentNeverRecyclesIt(JenkinsRule r) throws Exception {
        final vSphereCloudSlave agent = connectedAgent(r, "agent", "lbl", 1, 1);
        final File gate = new File(r.jenkins.getRootDir(), "gate");
        final WorkflowRun run =
                pipeline(r, "held", gateScript(r, gate)).scheduleBuild2(0).waitForStart();
        waitFor("the run to be counted", () -> agent.getNumberOfLimitedTestRuns() == 1);
        final vSphereCloudSlave saved = newAgent(r, "agent", "lbl", 1, 0);
        r.jenkins.getNodesObject().replaceNode(agent, saved);

        Files.createFile(gate.toPath());
        r.assertBuildStatusSuccess(r.waitForCompletion(run));

        // The run was counted and has been released, and the agent was not asked to recycle.
        assertThat(vSphereCloudSlave.hasLimitedTestRun(run), is(false));
        Thread.sleep(2000);
        assertThat(isDisconnected(saved), is(false));
        assertThat(saved.getNumberOfLimitedTestRuns(), is(0));
    }

    @Test
    void deletingAnAgentForgetsItsUsage(JenkinsRule r) throws Exception {
        final vSphereCloudSlave agent = newAgent(r, "agent", "lbl", 1, 5);
        r.jenkins.addNode(agent);
        AgentUsageRegistry.get().acquire("agent", 5, runningBuild(r));
        assertThat(agent.getNumberOfLimitedTestRuns(), is(1));

        r.jenkins.removeNode(agent);

        assertThat(AgentUsageRegistry.get().count("agent"), is(0));
    }

    @Test
    void anAgentCreatedWithAUsedNameStartsFromNothing(JenkinsRule r) throws Exception {
        AgentUsageRegistry.get().acquire("agent", 5, runningBuild(r));

        final vSphereCloudSlave agent = newAgent(r, "agent", "lbl", 1, 5);
        r.jenkins.addNode(agent);

        assertThat(agent.getNumberOfLimitedTestRuns(), is(0));
    }

    @Test
    void renamingAnAgentKeepsItsUsage(JenkinsRule r) throws Exception {
        final vSphereCloudSlave agent = newAgent(r, "old", "lbl", 1, 5);
        r.jenkins.addNode(agent);
        AgentUsageRegistry.get().acquire("old", 5, runningBuild(r));

        r.jenkins.getNodesObject().replaceNode(agent, newAgent(r, "new", "lbl", 1, 5));

        assertThat(AgentUsageRegistry.get().count("new"), is(1));
        assertThat(AgentUsageRegistry.get().count("old"), is(0));
    }

    @Test
    void anAgentRenamedWhileARunEndsIsStillFound(JenkinsRule r) throws Exception {
        final AgentUsageRegistry registry = AgentUsageRegistry.get();
        final vSphereCloudSlave renamed = newAgent(r, "new", "lbl", 1, 5);
        final FreeStyleBuild run = runningBuild(r);
        registry.acquire("old", 5, run);

        // The agent is renamed after asking where the run was counted and before looking that agent up.
        final vSphereCloudSlave found = vSphereCloudSlave.findCountedAgent(run, name -> {
            if (name.equals("old")) {
                registry.rename("old", "new");
                return null;
            }
            return name.equals("new") ? renamed : null;
        });

        assertThat(found, is(renamed));
    }

    @Test
    void loweringTheLimitToTheUsageOfAnIdleAgentRetiresAndRecyclesIt(JenkinsRule r) throws Exception {
        final vSphereCloudSlave agent = connectedAgent(r, "agent", "lbl", 1, 5);
        final FreeStyleBuild one = runningBuild(r);
        final FreeStyleBuild two = runningBuild(r);
        AgentUsageRegistry.get().acquire("agent", 5, one);
        AgentUsageRegistry.get().acquire("agent", 5, two);
        AgentUsageRegistry.get().release(one);
        AgentUsageRegistry.get().release(two);
        assertThat(AgentUsageRegistry.get().count("agent"), is(2));

        final vSphereCloudSlave saved = newAgent(r, "agent", "lbl", 1, 2);
        r.jenkins.getNodesObject().replaceNode(agent, saved);

        // Saving the agent kept its usage; the agent is at its limit and nothing is using it.
        assertThat(AgentUsageRegistry.get().isRetiring("agent", 2), is(true));
        AgentRecycler.get().sweepOnce();
        waitFor("the agent to be recycled", () -> isDisconnected(saved));
        waitFor("the count to start again", () -> AgentUsageRegistry.get().count("agent") == 0);
    }

    /** A freestyle build on the label that stays until it is told to go. */
    private FreeStyleBuild gatedBuild(JenkinsRule r, OneShotEvent inside, OneShotEvent release) throws Exception {
        final FreeStyleProject project = r.createFreeStyleProject();
        project.setAssignedLabel(r.jenkins.getLabel("lbl"));
        project.getBuildersList().add(new TestBuilder() {
            @Override
            public boolean perform(AbstractBuild<?, ?> build, Launcher launcher, BuildListener listener)
                    throws InterruptedException {
                inside.signal();
                release.block();
                return true;
            }
        });
        return project.scheduleBuild2(0).waitForStart();
    }

    @Test
    void aRecycleDoesNotKillAnotherCountedRun(JenkinsRule r) throws Exception {
        final vSphereCloudSlave agent = connectedAgent(r, "agent", "lbl", 2, 2);
        final OneShotEvent insideA = new OneShotEvent();
        final OneShotEvent releaseA = new OneShotEvent();
        final OneShotEvent insideB = new OneShotEvent();
        final OneShotEvent releaseB = new OneShotEvent();
        final FreeStyleBuild a = gatedBuild(r, insideA, releaseA);
        final FreeStyleBuild b = gatedBuild(r, insideB, releaseB);
        try {
            waitFor("both runs to be counted", () -> agent.getNumberOfLimitedTestRuns() == 2);
            releaseA.signal();
            r.assertBuildStatusSuccess(r.waitForCompletion(a));
            waitFor(
                    "the first run to be released",
                    () -> !AgentUsageRegistry.get().isCounted(a));

            // The agent has used all its builds, but the second is still using it.
            assertThat(AgentRecycler.get().check("agent"), is(false));
            assertThat(isDisconnected(agent), is(false));
            assertThat(b.isBuilding(), is(true));
        } finally {
            releaseA.signal();
            releaseB.signal();
        }
        r.assertBuildStatusSuccess(r.waitForCompletion(b));

        waitFor("the agent to be recycled", () -> isDisconnected(agent));
        waitFor("the count to start again", () -> agent.getNumberOfLimitedTestRuns() == 0);
    }

    @Test
    void theCountIsKeptUntilTheRecycleCompletes(JenkinsRule r) throws Exception {
        final vSphereCloudSlave agent = connectedAgent(r, "agent", "lbl", 1, 1);
        final CountDownLatch hold = new CountDownLatch(1);
        InProcessLauncher.holdDisconnect = hold;
        final FreeStyleProject project = r.createFreeStyleProject();
        project.setAssignedLabel(r.jenkins.getLabel("lbl"));

        r.buildAndAssertSuccess(project);

        try {
            waitFor("the recycle to begin", () -> AgentUsageRegistry.get().isRecycling("agent"));
            waitFor("the machine to be dealt with", () -> InProcessLauncher.DISCONNECTS.get() >= 1);
            // Disconnected, but not yet done with: still a used agent.
            assertThat(agent.getNumberOfLimitedTestRuns(), is(1));
            assertThat(AgentUsageRegistry.get().isRecycling("agent"), is(true));
        } finally {
            hold.countDown();
        }
        waitFor("the count to start again", () -> agent.getNumberOfLimitedTestRuns() == 0);
        assertThat(AgentUsageRegistry.get().isRecycling("agent"), is(false));
    }

    @Test
    void aFailedRecycleLeavesTheAgentAtItsLimitAndIsRetried(JenkinsRule r) throws Exception {
        final vSphereCloudSlave agent = connectedAgent(r, "agent", "lbl", 1, 1);
        InProcessLauncher.failDisconnect = true;
        final FreeStyleProject project = r.createFreeStyleProject();
        project.setAssignedLabel(r.jenkins.getLabel("lbl"));

        r.buildAndAssertSuccess(project);

        waitFor("the recycle to be tried", () -> InProcessLauncher.DISCONNECTS.get() >= 1);
        waitFor("the recycle to be over", () -> !AgentUsageRegistry.get().isRecycling("agent"));
        assertThat(agent.getNumberOfLimitedTestRuns(), is(1));
        waitFor("the agent not to be held offline", () -> !agent.toComputer().isTemporarilyOffline());

        // Connected again, it is not tried again at once, but after a while.
        InProcessLauncher.failDisconnect = false;
        agent.toComputer().connect(false).get();
        waitFor("the agent to be online", () -> agent.toComputer().isOnline());
        assertThat(AgentRecycler.get().sweepOnce().isEmpty(), is(true));
        final long later = System.currentTimeMillis() + TimeUnit.MINUTES.toMillis(31);
        AgentRecycler.get().setClock(() -> later);
        AgentRecycler.get().sweepOnce();
        waitFor("the count to start again", () -> agent.getNumberOfLimitedTestRuns() == 0);
    }

    @Test
    void afterDisconnectTwiceResetsOnce(JenkinsRule r) throws Exception {
        final vSphereCloudSlave agent = connectedAgent(r, "agent", "lbl", 1, 1);
        final CountDownLatch hold = new CountDownLatch(1);
        InProcessLauncher.holdDisconnect = hold;
        final FreeStyleProject project = r.createFreeStyleProject();
        project.setAssignedLabel(r.jenkins.getLabel("lbl"));
        r.buildAndAssertSuccess(project);
        waitFor("the recycle to begin", () -> AgentUsageRegistry.get().isRecycling("agent"));
        waitFor("the machine to be dealt with", () -> InProcessLauncher.DISCONNECTS.get() >= 1);

        // The launcher of a real agent says it once more when Jenkins disconnects it a second time: the first counts.
        AgentRecycler.idleActionDone("agent", RecycleOutcome.COMPLETED);
        AgentRecycler.idleActionDone("agent", RecycleOutcome.FAILED);
        hold.countDown();

        waitFor("the count to start again", () -> agent.getNumberOfLimitedTestRuns() == 0);
        waitFor("the recycle to end", () -> !AgentUsageRegistry.get().isRecycling("agent"));
        assertThat(agent.getNumberOfLimitedTestRuns(), is(0));
    }

    @Test
    void aRecycleLeftBehindByAStoppedJenkinsIsCleared(JenkinsRule r) throws Exception {
        final vSphereCloudSlave agent = connectedAgent(r, "agent", "lbl", 1, 5);
        agent.toComputer().setTemporarilyOffline(true, new LimitedBuildsOfflineCause());

        // The first look after Jenkins starts, which is a new recycler for the Jenkins of this test.
        new AgentRecycler().sweepOnce();

        assertThat(agent.toComputer().isTemporarilyOffline(), is(false));
    }

    @Test
    void anAgentOfflineForAnotherReasonIsNotRecycled(JenkinsRule r) throws Exception {
        final vSphereCloudSlave agent = connectedAgent(r, "agent", "lbl", 1, 1);
        final FreeStyleBuild one = runningBuild(r);
        AgentUsageRegistry.get().acquire("agent", 1, one);
        AgentUsageRegistry.get().release(one);
        final OfflineCause theirs = new OfflineCause.UserCause(null, "being looked at");
        agent.toComputer().setTemporarilyOffline(true, theirs);

        assertThat(AgentRecycler.get().sweepOnce().isEmpty(), is(true));

        assertThat(isDisconnected(agent), is(false));
        assertThat(agent.toComputer().getOfflineCause(), is(theirs));
        assertThat(agent.getNumberOfLimitedTestRuns(), is(1));
    }

    @Test
    void freestyleBuildsAreStillCounted(JenkinsRule r) throws Exception {
        final vSphereCloudSlave agent = connectedAgent(r, "agent", "lbl", 1, 5);
        final FreeStyleProject project = r.createFreeStyleProject();
        project.setAssignedLabel(r.jenkins.getLabel("lbl"));

        r.buildAndAssertSuccess(project);

        assertThat(agent.getNumberOfLimitedTestRuns(), is(1));
    }
}
