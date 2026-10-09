package org.jenkinsci.plugins;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;

import hudson.Launcher;
import hudson.model.AbstractBuild;
import hudson.model.BuildListener;
import hudson.model.FreeStyleBuild;
import hudson.model.FreeStyleProject;
import hudson.model.Node.Mode;
import hudson.model.Result;
import hudson.model.TaskListener;
import hudson.slaves.ComputerLauncher;
import hudson.slaves.RetentionStrategy;
import hudson.slaves.SlaveComputer;
import hudson.util.OneShotEvent;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.util.Collections;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;
import org.jenkinsci.plugins.vsphere.VSphereConnectionConfig;
import org.jenkinsci.plugins.workflow.cps.CpsFlowDefinition;
import org.jenkinsci.plugins.workflow.job.WorkflowJob;
import org.jenkinsci.plugins.workflow.job.WorkflowRun;
import org.junit.jupiter.api.Test;
import org.jvnet.hudson.test.JenkinsRule;
import org.jvnet.hudson.test.TestBuilder;
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

        @Override
        public void afterDisconnect(SlaveComputer computer, TaskListener listener) {
            getDelegate().afterDisconnect(computer, listener);
        }
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
        r.jenkins.addNode(agent);
        final SlaveComputer computer = (SlaveComputer) agent.toComputer();
        computer.connect(false).get();
        waitFor(name + " to be online", computer::isOnline);
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

        // Both runs are inside their node blocks at the same time, until the gate is opened.
        waitFor("both runs to be using the agent", () -> agent.toComputer().countBusy() == 2);
        assertThat(agent.getNumberOfLimitedTestRuns(), is(2));
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
        assertThat(agent.getNumberOfLimitedTestRuns(), is(0));
        assertThat(vSphereCloudSlave.hasLimitedTestRun(run), is(false));
    }

    @Test
    void aFailedRunStillRecyclesTheAgent(JenkinsRule r) throws Exception {
        final vSphereCloudSlave agent = connectedAgent(r, "agent", "lbl", 1, 1);
        final WorkflowJob job = pipeline(r, "failing", "node('lbl') { error 'on purpose' }\n");

        final WorkflowRun run = r.assertBuildStatus(Result.FAILURE, job.scheduleBuild2(0));

        waitFor("the agent to be recycled", () -> isDisconnected(agent));
        assertThat(agent.getNumberOfLimitedTestRuns(), is(0));
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
        assertThat(agent.getNumberOfLimitedTestRuns(), is(0));
        assertThat(vSphereCloudSlave.hasLimitedTestRun(run), is(false));
    }

    @Test
    void aFreestyleBuildReachingTheLimitRecyclesTheAgent(JenkinsRule r) throws Exception {
        final vSphereCloudSlave agent = connectedAgent(r, "agent", "lbl", 1, 1);
        final FreeStyleProject project = r.createFreeStyleProject();
        project.setAssignedLabel(r.jenkins.getLabel("lbl"));

        final FreeStyleBuild build = r.buildAndAssertSuccess(project);

        waitFor("the agent to be recycled", () -> isDisconnected(agent));
        assertThat(agent.getNumberOfLimitedTestRuns(), is(0));
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

    @Test
    void freestyleBuildsAreStillCounted(JenkinsRule r) throws Exception {
        final vSphereCloudSlave agent = connectedAgent(r, "agent", "lbl", 1, 5);
        final FreeStyleProject project = r.createFreeStyleProject();
        project.setAssignedLabel(r.jenkins.getLabel("lbl"));

        r.buildAndAssertSuccess(project);

        assertThat(agent.getNumberOfLimitedTestRuns(), is(1));
    }
}
