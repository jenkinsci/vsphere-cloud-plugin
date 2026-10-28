package org.jenkinsci.plugins;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.notNullValue;
import static org.hamcrest.Matchers.nullValue;

import hudson.Launcher;
import hudson.model.AbstractBuild;
import hudson.model.Action;
import hudson.model.BuildListener;
import hudson.model.Computer;
import hudson.model.Executor;
import hudson.model.FreeStyleBuild;
import hudson.model.FreeStyleProject;
import hudson.model.Node.Mode;
import hudson.model.Queue;
import hudson.model.ResourceList;
import hudson.model.Result;
import hudson.model.Run;
import hudson.model.TaskListener;
import hudson.model.listeners.RunListener;
import hudson.model.queue.AbstractQueueTask;
import hudson.model.queue.QueueTaskFuture;
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
import java.util.Calendar;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;
import org.jenkinsci.plugins.durabletask.executors.ContinuedTask;
import org.jenkinsci.plugins.vsphere.AgentRecycler;
import org.jenkinsci.plugins.vsphere.AgentUsageRegistry;
import org.jenkinsci.plugins.vsphere.AgentUsageRegistry.RecycleOutcome;
import org.jenkinsci.plugins.vsphere.LimitedBuildsOfflineCause;
import org.jenkinsci.plugins.vsphere.UsageGate;
import org.jenkinsci.plugins.vsphere.VSphereConnectionConfig;
import org.jenkinsci.plugins.workflow.cps.CpsFlowDefinition;
import org.jenkinsci.plugins.workflow.job.WorkflowJob;
import org.jenkinsci.plugins.workflow.job.WorkflowRun;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
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
// A test that waits for ever for something that never happens fails, with where it was, instead of stopping the build.
@Timeout(value = 4, unit = TimeUnit.MINUTES, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
@WithJenkins
class vSphereCloudPipelineLimitedBuildsTest {

    private static final String SWEEP_PERIOD = AgentRecycler.class.getName() + ".sweepPeriodMs";

    @BeforeAll
    static void noSweepOfItsOwn() {
        // The sweep that Jenkins runs by itself, at a time of its own, is not what these tests are about.
        System.setProperty(SWEEP_PERIOD, Long.toString(TimeUnit.DAYS.toMillis(1)));
    }

    @AfterAll
    static void sweepOfItsOwnAgain() {
        System.clearProperty(SWEEP_PERIOD);
    }

    private static final String CLOUD = "some-cloud";

    /**
     * Connects and disconnects like {@link JenkinsRule#createSlave}, with none of the vSphere work. Delegating
     * {@code afterDisconnect} matters: that is what stops the agent process.
     */
    private static final class InProcessLauncher extends vSphereCloudLauncher {
        /** Not persisted with the agent's configuration. */
        final transient AtomicInteger launches = new AtomicInteger();

        InProcessLauncher(ComputerLauncher delegate, String vmName, int limit) {
            this(delegate, vmName, limit, "Nothing");
        }

        InProcessLauncher(ComputerLauncher delegate, String vmName, int limit, String idleOption) {
            super(delegate, CLOUD, vmName, false, false, "", "60", idleOption, Integer.toString(limit));
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
        UsageGate.setIdleHeldMs(-1);
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
        return connectedAgent(r, name, label, executors, limit, "Nothing");
    }

    private static vSphereCloudSlave connectedAgent(
            JenkinsRule r, String name, String label, int executors, int limit, String idleOption) throws Exception {
        final vSphereCloudSlave agent = newAgent(r, name, label, executors, limit, idleOption);
        r.jenkins.addNode(agent);
        final SlaveComputer computer = (SlaveComputer) agent.toComputer();
        computer.connect(false).get();
        waitFor(name + " to be online", computer::isOnline);
        return agent;
    }

    /** An agent that is not part of Jenkins yet. */
    private static vSphereCloudSlave newAgent(JenkinsRule r, String name, String label, int executors, int limit)
            throws Exception {
        return newAgent(r, name, label, executors, limit, "Nothing");
    }

    private static vSphereCloudSlave newAgent(
            JenkinsRule r, String name, String label, int executors, int limit, String idleOption) throws Exception {
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
                idleOption,
                Integer.toString(limit));
        agent.setLauncher(new InProcessLauncher(r.createComputerLauncher(null), "vm-" + name, limit, idleOption));
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

    private final List<FreeStyleBuild> runningBuilds = new ArrayList<>();

    /** A build that is going on and does not use any agent. */
    private FreeStyleBuild runningBuild(JenkinsRule r) throws Exception {
        final FreeStyleProject project = r.createFreeStyleProject();
        // Without a label the job could be scheduled on the vSphere test agent; keep it on the built-in node.
        project.setAssignedLabel(r.jenkins.getSelfLabel());
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
        assertThat(AgentRecycler.get().check("agent"), is(false));
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
        final int disconnectsBefore = InProcessLauncher.DISCONNECTS.get();
        final CountDownLatch hold = new CountDownLatch(1);
        InProcessLauncher.holdDisconnect = hold;
        final FreeStyleProject project = r.createFreeStyleProject();
        project.setAssignedLabel(r.jenkins.getLabel("lbl"));

        r.buildAndAssertSuccess(project);

        try {
            waitFor("the recycle to begin", () -> AgentUsageRegistry.get().isRecycling("agent"));
            waitFor("the machine to be dealt with", () -> InProcessLauncher.DISCONNECTS.get() > disconnectsBefore);
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
        final int disconnectsBefore = InProcessLauncher.DISCONNECTS.get();
        InProcessLauncher.failDisconnect = true;
        final FreeStyleProject project = r.createFreeStyleProject();
        project.setAssignedLabel(r.jenkins.getLabel("lbl"));

        r.buildAndAssertSuccess(project);

        waitFor("the recycle to be tried", () -> InProcessLauncher.DISCONNECTS.get() > disconnectsBefore);
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
        final int disconnectsBefore = InProcessLauncher.DISCONNECTS.get();
        final CountDownLatch hold = new CountDownLatch(1);
        InProcessLauncher.holdDisconnect = hold;
        final FreeStyleProject project = r.createFreeStyleProject();
        project.setAssignedLabel(r.jenkins.getLabel("lbl"));
        r.buildAndAssertSuccess(project);
        waitFor("the recycle to begin", () -> AgentUsageRegistry.get().isRecycling("agent"));
        waitFor("the machine to be dealt with", () -> InProcessLauncher.DISCONNECTS.get() > disconnectsBefore);

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

    /** Whether the gate refuses an item of the queue (the only one there is) for being beyond the limit. */
    private static boolean refusesTheQueuedItem(JenkinsRule r, vSphereCloudSlave agent) {
        for (Queue.BuildableItem item : r.jenkins.getQueue().getBuildableItems()) {
            if (agent.canTake(item) != null) {
                return true;
            }
        }
        return false;
    }

    @Test
    void aRunWaitingOutsideItsBlockForAnotherBuildDoesNotHoldTheAgentForEver(JenkinsRule r) throws Exception {
        final vSphereCloudSlave agent = connectedAgent(r, "agent", "lbl", 1, 1);
        final String path =
                new File(r.jenkins.getRootDir(), "gate").getAbsolutePath().replace('\\', '/');
        // It waits for the other build, outside of any block, so that the agent is idle but cannot be recycled.
        final WorkflowJob waiting = r.createProject(WorkflowJob.class, "waiting");
        waiting.setDefinition(new CpsFlowDefinition(
                "node('lbl') { echo 'in' }\nwaitUntil { new File('" + path + "').exists() }\n", false));
        final WorkflowRun waitingRun = waiting.scheduleBuild2(0).waitForStart();
        waitFor(
                "the run to be counted, and the agent idle",
                () -> agent.getNumberOfLimitedTestRuns() == 1
                        && agent.toComputer().isIdle());
        final QueueTaskFuture<WorkflowRun> other = pipeline(
                        r, "other", "node('lbl') { writeFile file: '" + path + "', text: 'x' }\n")
                .scheduleBuild2(0);

        // As it is by default, the agent does not take work again, however long it has had nothing to do.
        waitFor("the other build to be refused", () -> refusesTheQueuedItem(r, agent));
        UsageGate.setIdleHeldMs(0);
        r.jenkins.getQueue().scheduleMaintenance();

        final WorkflowRun otherRun = r.assertBuildStatusSuccess(other);
        // It ran beyond the limit, and is not one of the builds of the agent.
        assertThat(AgentUsageRegistry.get().isCounted(otherRun), is(false));
        r.assertBuildStatusSuccess(r.waitForCompletion(waitingRun));
        waitFor("the agent to be recycled", () -> isDisconnected(agent));
        waitFor("the count to start again", () -> agent.getNumberOfLimitedTestRuns() == 0);
    }

    @Test
    void aRunWaitingInsideItsBlockStillKeepsNewBuildsWaiting(JenkinsRule r) throws Exception {
        final vSphereCloudSlave agent = connectedAgent(r, "agent", "lbl", 2, 1);
        UsageGate.setIdleHeldMs(0);
        final File gate = new File(r.jenkins.getRootDir(), "gate");
        final WorkflowRun run =
                pipeline(r, "held", gateScript(r, gate)).scheduleBuild2(0).waitForStart();
        waitFor("the run to be counted", () -> agent.getNumberOfLimitedTestRuns() == 1);
        waitFor("its block to be on the agent", () -> agent.toComputer().countBusy() == 1);
        final FreeStyleProject other = r.createFreeStyleProject();
        other.setAssignedLabel(r.jenkins.getLabel("lbl"));
        final QueueTaskFuture<FreeStyleBuild> otherBuild = other.scheduleBuild2(0);

        // The agent is not idle, so this is the known limit of the way out of waiting for ever.
        waitFor("the other build to be refused", () -> refusesTheQueuedItem(r, agent));
        for (int pass = 0; pass < 3; pass++) {
            r.jenkins.getQueue().maintain();
        }
        assertThat(other.getLastBuild(), is(nullValue()));

        Files.createFile(gate.toPath());
        r.assertBuildStatusSuccess(r.waitForCompletion(run));
        waitFor("the agent to be recycled", () -> isDisconnected(agent));
        waitFor("the count to start again", () -> agent.getNumberOfLimitedTestRuns() == 0);
        reconnect(agent);
        r.assertBuildStatusSuccess(otherBuild);
    }

    @Test
    void anIdleAgentAtItsLimitWithNoRunCountedOnItStillRefusesNewWork(JenkinsRule r) throws Exception {
        final vSphereCloudSlave agent = connectedAgent(r, "agent", "lbl", 1, 1);
        final int disconnectsBefore = InProcessLauncher.DISCONNECTS.get();
        InProcessLauncher.failDisconnect = true;
        final FreeStyleProject project = r.createFreeStyleProject();
        project.setAssignedLabel(r.jenkins.getLabel("lbl"));
        r.buildAndAssertSuccess(project);
        waitFor("the recycle to be tried", () -> InProcessLauncher.DISCONNECTS.get() > disconnectsBefore);
        waitFor("the recycle to be over", () -> !AgentUsageRegistry.get().isRecycling("agent"));
        InProcessLauncher.failDisconnect = false;
        agent.toComputer().connect(false).get();
        waitFor("the agent to be online", () -> agent.toComputer().isOnline());
        UsageGate.setIdleHeldMs(0);

        // It is waiting to be recycled again, which nothing is keeping it from: it is not for it to take work now.
        assertThat(AgentUsageRegistry.get().holdsCountedRuns("agent"), is(false));
        assertThat(
                UsageGate.check(agent, itemOf(new FakeTask(false, false))).getShortDescription(),
                containsString("reached its limit"));
    }

    @Test
    void anOfflineMarkOfSomeoneElseIsKeptAtTheFirstLook(JenkinsRule r) throws Exception {
        final vSphereCloudSlave agent = connectedAgent(r, "agent", "lbl", 1, 5);
        final OfflineCause theirs = new OfflineCause.UserCause(null, "being looked at");
        agent.toComputer().setTemporarilyOffline(true, theirs);

        new AgentRecycler().sweepOnce();

        assertThat(agent.toComputer().getOfflineCause(), is(theirs));
    }

    @Test
    void anAgentThatReconnectsAfterItsRecycleComesBackOnceItIsOver(JenkinsRule r) throws Exception {
        final vSphereCloudSlave agent = connectedAgent(r, "agent", "lbl", 1, 1, "Reconnect and Revert");
        final FreeStyleProject project = r.createFreeStyleProject();
        project.setAssignedLabel(r.jenkins.getLabel("lbl"));

        r.buildAndAssertSuccess(project);

        // It is not allowed to connect while it is marked offline for the recycle, which may not be over when the
        // launcher asks, so the recycler does it when the mark is gone.
        waitFor("the count to start again", () -> agent.getNumberOfLimitedTestRuns() == 0);
        waitFor("the agent to be back online", () -> agent.toComputer().isOnline());
        assertThat(agent.toComputer().isTemporarilyOffline(), is(false));
    }

    @Test
    void anAgentThatLostItsConnectionWithADurableStepRunningStillTakesWhatItIsLaunchedFor(JenkinsRule r)
            throws Exception {
        final vSphereCloudSlave agent = connectedAgent(r, "agent", "lbl", 1, 1);
        // A step that goes on in the agent when the connection is lost, as a shell does: the block waits for it.
        final WorkflowRun run = pipeline(
                        r,
                        "durable",
                        "node('lbl') { if (isUnix()) { sh 'sleep 120' } else { bat 'ping -n 120 127.0.0.1 > nul' } }\n")
                .scheduleBuild2(0)
                .waitForStart();
        waitFor("the run to be counted", () -> agent.getNumberOfLimitedTestRuns() == 1);
        waitFor("its block to be on the agent", () -> agent.toComputer().countBusy() == 1);
        r.waitForMessage(hudson.Functions.isWindows() ? "ping" : "sleep 120", run);

        ((SlaveComputer) agent.toComputer()).getChannel().close();
        waitFor("the agent to be offline", () -> agent.toComputer().isOffline());

        // The block stays on the agent, waiting for it to come back. Anything that comes is what gets a retention
        // strategy that launches agents when they are needed to launch it, and refusing it would leave the run, and the
        // agent that cannot be recycled with it, waiting for ever.
        assertThat(agent.toComputer().countBusy(), is(1));
        assertThat(run.isBuilding(), is(true));
        assertThat(UsageGate.check(agent, itemOf(new FakeTask(false, false))), is(nullValue()));

        reconnect(agent);
        run.doStop();
        r.waitForCompletion(run);
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

    /** The agent is connected again, as the launcher of a test (like the default one) is not launched by Jenkins. */
    private static void reconnect(vSphereCloudSlave agent) throws Exception {
        agent.toComputer().connect(false).get();
        waitFor("the agent to be online", () -> agent.toComputer().isOnline());
    }

    /** A job on the label that, once it runs, stays until it is told to go. */
    private FreeStyleProject gatedProject(JenkinsRule r, OneShotEvent inside, OneShotEvent release) throws Exception {
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
        return project;
    }

    @Test
    void aFreestyleBuildBeyondTheLimitWaitsAndRunsAfterTheRecycle(JenkinsRule r) throws Exception {
        final vSphereCloudSlave agent = connectedAgent(r, "agent", "lbl", 2, 1);
        final OneShotEvent releaseFirst = new OneShotEvent();
        final FreeStyleBuild first = gatedBuild(r, new OneShotEvent(), releaseFirst);
        final FreeStyleProject second = r.createFreeStyleProject();
        second.setAssignedLabel(r.jenkins.getLabel("lbl"));
        final QueueTaskFuture<FreeStyleBuild> secondBuild = second.scheduleBuild2(0);
        try {
            // The agent has a free executor, but the first build used the one build that it is allowed.
            waitFor("the second build to be refused", () -> {
                final Queue.Item item = r.jenkins.getQueue().getItem(second);
                return item instanceof Queue.BuildableItem && agent.canTake((Queue.BuildableItem) item) != null;
            });
            final Queue.BuildableItem item =
                    (Queue.BuildableItem) r.jenkins.getQueue().getItem(second);
            assertThat(agent.canTake(item).getShortDescription(), containsString("reached its limit"));
            assertThat(second.getLastBuild(), is(nullValue()));
        } finally {
            releaseFirst.signal();
        }
        r.assertBuildStatusSuccess(r.waitForCompletion(first));

        waitFor("the agent to be recycled", () -> isDisconnected(agent));
        waitFor("the count to start again", () -> agent.getNumberOfLimitedTestRuns() == 0);
        reconnect(agent);
        r.assertBuildStatusSuccess(secondBuild);
    }

    @Test
    void twoBuildsDispatchedTogetherOnlyOneIsCounted(JenkinsRule r) throws Exception {
        final vSphereCloudSlave agent = connectedAgent(r, "agent", "lbl", 2, 1);
        final OneShotEvent release = new OneShotEvent();
        final FreeStyleProject one = gatedProject(r, new OneShotEvent(), release);
        final FreeStyleProject two = gatedProject(r, new OneShotEvent(), release);
        try {
            // Both are handed out in one pass of the queue, before either has started.
            Queue.withLock(() -> {
                one.scheduleBuild2(0);
                two.scheduleBuild2(0);
            });
            r.jenkins.getQueue().maintain();

            waitFor(
                    "one build to run and the other to wait",
                    () -> agent.getNumberOfLimitedTestRuns() == 1
                            && r.jenkins.getQueue().getBuildableItems().size() == 1);
            assertThat(agent.toComputer().countBusy(), is(1));
        } finally {
            release.signal();
        }

        // Whichever of them it was, nothing was aborted, and the other one runs when the agent is back.
        waitFor("the agent to be recycled", () -> isDisconnected(agent));
        waitFor("the count to start again", () -> agent.getNumberOfLimitedTestRuns() == 0);
        reconnect(agent);
        for (FreeStyleProject project : List.of(one, two)) {
            waitFor(
                    "the build of " + project.getName(),
                    () -> project.getLastBuild() != null
                            && !project.getLastBuild().isBuilding());
            assertThat(project.getLastBuild().getResult(), is(Result.SUCCESS));
        }
    }

    /** Held in {@code onStarted} until opened, for the build of the job called "uncounted". */
    private static volatile CountDownLatch startHeld;

    /** Runs before the listener that counts the builds of the agents, which is of ordinal 0. */
    @TestExtension("aStartedButUncountedRunHoldsItsPlace")
    public static class HoldStart extends RunListener<Run<?, ?>> {
        @Override
        public void onStarted(Run<?, ?> run, TaskListener listener) {
            final CountDownLatch held = startHeld;
            if (held != null && "uncounted".equals(run.getParent().getName())) {
                try {
                    held.await(60, TimeUnit.SECONDS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
        }
    }

    @Test
    void aStartedButUncountedRunHoldsItsPlace(JenkinsRule r) throws Exception {
        final vSphereCloudSlave agent = connectedAgent(r, "agent", "lbl", 2, 1);
        final CountDownLatch held = new CountDownLatch(1);
        startHeld = held;
        final OneShotEvent release = new OneShotEvent();
        final FreeStyleProject uncounted = gatedProject(r, new OneShotEvent(), release);
        uncounted.renameTo("uncounted");
        final FreeStyleProject other = gatedProject(r, new OneShotEvent(), release);
        try {
            uncounted.scheduleBuild2(0);
            waitFor(
                    "the first build to be on the agent",
                    () -> agent.toComputer().countBusy() == 1);
            assertThat(agent.getNumberOfLimitedTestRuns(), is(0));

            other.scheduleBuild2(0);
            r.jenkins.getQueue().maintain();

            // It is not counted yet, but it has taken the one place that the agent has.
            waitFor("the second build to be refused", () -> {
                final Queue.Item item = r.jenkins.getQueue().getItem(other);
                return item instanceof Queue.BuildableItem && agent.canTake((Queue.BuildableItem) item) != null;
            });
            assertThat(other.getLastBuild(), is(nullValue()));
        } finally {
            startHeld = null;
            held.countDown();
            release.signal();
        }
        waitFor("the agent to be recycled", () -> isDisconnected(agent));
        waitFor("the count to start again", () -> agent.getNumberOfLimitedTestRuns() == 0);
        reconnect(agent);
        waitFor(
                "the second build",
                () -> other.getLastBuild() != null && !other.getLastBuild().isBuilding());
    }

    @Test
    void aLoopOfNodeBlocksOnAnAgentAtItsLimitFinishes(JenkinsRule r) throws Exception {
        final vSphereCloudSlave agent = connectedAgent(r, "agent", "lbl", 2, 1);
        final WorkflowJob job = pipeline(
                r,
                "loop",
                "node('lbl') { echo 'b0'; sleep 4 }\nnode('lbl') { echo 'b1' }\nnode('lbl') { echo 'b2' }\n");
        final WorkflowRun run = job.scheduleBuild2(0).waitForStart();
        r.waitForMessage("b0", run);
        final FreeStyleProject other = r.createFreeStyleProject();
        other.setAssignedLabel(r.jenkins.getLabel("lbl"));
        final QueueTaskFuture<FreeStyleBuild> otherBuild = other.scheduleBuild2(0);

        // The run goes on to its next blocks although the agent has used all it is allowed: they are not new.
        r.assertBuildStatusSuccess(r.waitForCompletion(run));

        // Whereas the other build, which is new, had to wait for it to be over.
        assertThat(other.getLastBuild(), is(nullValue()));
        waitFor("the agent to be recycled", () -> isDisconnected(agent));
        waitFor("the count to start again", () -> agent.getNumberOfLimitedTestRuns() == 0);
        reconnect(agent);
        r.assertBuildStatusSuccess(otherBuild);
    }

    @Test
    void runsCountedOnDifferentAgentsDoNotDeadlock(JenkinsRule r) throws Exception {
        connectedAgent(r, "first", "x", 1, 1);
        connectedAgent(r, "second", "y", 1, 1);
        final File gateA = new File(r.jenkins.getRootDir(), "gateA");
        final File gateB = new File(r.jenkins.getRootDir(), "gateB");
        final String wait = "{ waitUntil { fileExists('%s') } }";
        final WorkflowJob a = pipeline(
                r,
                "a",
                "node('x') " + String.format(wait, gateA.getAbsolutePath().replace('\\', '/'))
                        + "\nnode('y') { echo 'a-on-y' }\n");
        final WorkflowJob b = pipeline(
                r,
                "b",
                "node('y') " + String.format(wait, gateB.getAbsolutePath().replace('\\', '/'))
                        + "\nnode('x') { echo 'b-on-x' }\n");
        final WorkflowRun runA = a.scheduleBuild2(0).waitForStart();
        final WorkflowRun runB = b.scheduleBuild2(0).waitForStart();
        waitFor(
                "both runs to be counted",
                () -> AgentUsageRegistry.get().count("first") == 1
                        && AgentUsageRegistry.get().count("second") == 1);

        // Each of them moves on to the agent that the other one is using, and that has used all it is allowed.
        Files.createFile(gateA.toPath());
        Files.createFile(gateB.toPath());

        r.assertBuildStatusSuccess(r.waitForCompletion(runA));
        r.assertBuildStatusSuccess(r.waitForCompletion(runB));
    }

    /** A task that is not a build of a job nor a block of a Pipeline, and says what the test wants of it. */
    private static final class FakeTask extends AbstractQueueTask implements ContinuedTask {
        private final boolean continued;
        private final boolean failing;

        FakeTask(boolean continued, boolean failing) {
            this.continued = continued;
            this.failing = failing;
        }

        @Override
        public boolean isContinued() {
            return continued;
        }

        @Override
        public Queue.Executable getOwnerExecutable() {
            if (failing) {
                throw new IllegalStateException("on purpose");
            }
            return null;
        }

        @Override
        public boolean isBuildBlocked() {
            return false;
        }

        @Override
        public String getWhyBlocked() {
            return null;
        }

        @Override
        public String getName() {
            return "fake";
        }

        @Override
        public String getFullDisplayName() {
            return "fake";
        }

        @Override
        public void checkAbortPermission() {}

        @Override
        public boolean hasAbortPermission() {
            return true;
        }

        @Override
        public String getUrl() {
            return "fake/";
        }

        @Override
        public ResourceList getResourceList() {
            return new ResourceList();
        }

        @Override
        public String getDisplayName() {
            return "fake";
        }

        @Override
        public Queue.Executable createExecutable() {
            throw new UnsupportedOperationException();
        }
    }

    private static Queue.BuildableItem itemOf(Queue.Task task) {
        return new Queue.BuildableItem(new Queue.WaitingItem(Calendar.getInstance(), task, new ArrayList<Action>()));
    }

    @Test
    void canTakeNeverThrows(JenkinsRule r) throws Exception {
        final vSphereCloudSlave agent = connectedAgent(r, "agent", "lbl", 1, 1);

        // Whatever goes wrong in finding out what the item is must not stop the queue.
        assertThat(UsageGate.check(agent, itemOf(new FakeTask(false, true))), is(nullValue()));
    }

    @Test
    void aContinuedTaskIsAcceptedByAnAgentAtItsLimit(JenkinsRule r) throws Exception {
        final vSphereCloudSlave agent = connectedAgent(r, "agent", "lbl", 1, 1);
        final FreeStyleBuild run = runningBuild(r);
        AgentUsageRegistry.get().acquire("agent", 1, run);

        // Work that goes on from before a restart is not new, whereas anything else that comes now is.
        assertThat(UsageGate.check(agent, itemOf(new FakeTask(true, false))), is(nullValue()));
        assertThat(
                UsageGate.check(agent, itemOf(new FakeTask(false, false))).getShortDescription(),
                containsString("reached its limit"));
    }

    @Test
    void aPipelineBlockDoesNotTakeThePlaceOfASecondBuild(JenkinsRule r) throws Exception {
        final vSphereCloudSlave agent = connectedAgent(r, "agent", "lbl", 2, 2);
        final File gate = new File(r.jenkins.getRootDir(), "gate");
        final WorkflowRun run =
                pipeline(r, "held", gateScript(r, gate)).scheduleBuild2(0).waitForStart();
        waitFor("the run to be counted", () -> agent.getNumberOfLimitedTestRuns() == 1);
        waitFor("its block to be on the agent", () -> agent.toComputer().countBusy() == 1);
        final FreeStyleProject other = r.createFreeStyleProject();
        other.setAssignedLabel(r.jenkins.getLabel("lbl"));

        // One of the two builds that the agent is allowed is being used, and a block is not a build of its own.
        final QueueTaskFuture<FreeStyleBuild> otherBuild = other.scheduleBuild2(0);
        try {
            waitFor("the second build to start", () -> other.getLastBuild() != null);
        } catch (AssertionError e) {
            final Queue.Item item = r.jenkins.getQueue().getItem(other);
            throw new AssertionError(
                    e.getMessage() + " (queue: " + (item == null ? null : item.getWhy()) + ", count: "
                            + agent.getNumberOfLimitedTestRuns() + ", busy: "
                            + agent.toComputer().countBusy()
                            + ", online: " + agent.toComputer().isOnline() + ")",
                    e);
        }
        r.assertBuildStatusSuccess(otherBuild);

        Files.createFile(gate.toPath());
        r.assertBuildStatusSuccess(r.waitForCompletion(run));
    }

    @Test
    void parallelBlocksOfANewRunAreNotOneBuildEach(JenkinsRule r) throws Exception {
        connectedAgent(r, "agent", "lbl", 2, 1);
        final String a = new File(r.jenkins.getRootDir(), "a").getAbsolutePath().replace('\\', '/');
        final String b = new File(r.jenkins.getRootDir(), "b").getAbsolutePath().replace('\\', '/');
        // Each waits for the other, so they have to be on the agent together.
        final WorkflowJob job = pipeline(
                r,
                "both",
                "parallel(\n"
                        + "  one: { node('lbl') { writeFile file: '" + a + "', text: 'x'; waitUntil { fileExists('" + b
                        + "') } } },\n"
                        + "  two: { node('lbl') { writeFile file: '" + b + "', text: 'x'; waitUntil { fileExists('" + a
                        + "') } } }\n"
                        + ")\n");

        r.assertBuildStatusSuccess(job.scheduleBuild2(0));
    }

    @Test
    void aPipelineThatARunOnTheAgentStartedIsNotLeftWaitingForIt(JenkinsRule r) throws Exception {
        connectedAgent(r, "agent", "lbl", 2, 1);
        pipeline(r, "downstream", "node('lbl') { echo 'in the downstream' }\n");
        final WorkflowJob upstream = pipeline(r, "upstream", "node('lbl') { build 'downstream' }\n");

        // The blocks of the downstream Pipeline are queued with no cause, but the run that they are blocks of has one.
        final WorkflowRun run = r.buildAndAssertSuccess(upstream);

        r.assertLogContains(
                "in the downstream",
                r.jenkins.getItemByFullName("downstream", WorkflowJob.class).getLastBuild());
        r.assertLogContains("Finished: SUCCESS", run);
    }

    @Test
    void aBuildThatARunOnTheAgentStartedIsNotLeftWaitingForIt(JenkinsRule r) throws Exception {
        connectedAgent(r, "agent", "lbl", 2, 1);
        final FreeStyleProject downstream = r.createFreeStyleProject("downstream");
        downstream.setAssignedLabel(r.jenkins.getLabel("lbl"));
        final WorkflowJob upstream = pipeline(r, "upstream", "node('lbl') { build 'downstream' }\n");

        // The run holds the one build that the agent is allowed and waits for the other one, which is beyond it.
        final WorkflowRun run = r.buildAndAssertSuccess(upstream);

        r.assertLogContains("Finished: SUCCESS", run);
        assertThat(downstream.getLastBuild().getResult(), is(Result.SUCCESS));
    }

    @Test
    void anAgentBeingRecycledRefusesEverything(JenkinsRule r) throws Exception {
        final vSphereCloudSlave agent = newAgent(r, "agent", "lbl", 1, 1);
        r.jenkins.addNode(agent);
        final FreeStyleBuild run = runningBuild(r);
        AgentUsageRegistry.get().acquire("agent", 1, run);
        AgentUsageRegistry.get().release(run);
        assertThat(AgentUsageRegistry.get().tryBeginRecycle("agent", 1), is(notNullValue()));

        assertThat(
                UsageGate.check(agent, itemOf(new FakeTask(false, false))).getShortDescription(),
                containsString("being recycled"));
    }

    @Test
    void anOfflineAgentAtItsLimitAcceptsWork(JenkinsRule r) throws Exception {
        // So that an agent that is launched when it is needed can be launched.
        final vSphereCloudSlave agent = newAgent(r, "agent", "lbl", 1, 1);
        r.jenkins.addNode(agent);
        final FreeStyleBuild run = runningBuild(r);
        AgentUsageRegistry.get().acquire("agent", 1, run);
        AgentUsageRegistry.get().release(run);
        assertThat(agent.toComputer().isOffline(), is(true));

        assertThat(UsageGate.check(agent, itemOf(new FakeTask(false, false))), is(nullValue()));
    }

    @Test
    void anOfflineAgentKeptAtItsLimitByACountedRunRefusesWork(JenkinsRule r) throws Exception {
        // Launched, it would refuse the work, and be disconnected for being idle by a retention strategy that launches
        // agents when they are needed, over and over, with the virtual machine reverted each time.
        final vSphereCloudSlave agent = newAgent(r, "agent", "lbl", 1, 1);
        r.jenkins.addNode(agent);
        final FreeStyleBuild run = runningBuild(r);
        AgentUsageRegistry.get().acquire("agent", 1, run);
        assertThat(agent.toComputer().isOffline(), is(true));

        assertThat(
                UsageGate.check(agent, itemOf(new FakeTask(false, false))).getShortDescription(),
                containsString("reached its limit"));
        // Work that goes on from before a restart is not new: it is what the agent is launched for.
        assertThat(UsageGate.check(agent, itemOf(new FakeTask(true, false))), is(nullValue()));
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
