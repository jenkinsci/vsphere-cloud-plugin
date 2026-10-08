package org.jenkinsci.plugins;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.instanceOf;
import static org.hamcrest.Matchers.is;

import hudson.ExtensionList;
import hudson.model.Executor;
import hudson.model.ExecutorListener;
import hudson.model.FreeStyleBuild;
import hudson.model.FreeStyleProject;
import hudson.model.Node.Mode;
import hudson.model.Queue;
import hudson.model.ResourceList;
import hudson.model.Run;
import hudson.model.queue.AbstractQueueTask;
import hudson.slaves.ComputerLauncher;
import hudson.slaves.RetentionStrategy;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.jvnet.hudson.test.JenkinsRule;
import org.jvnet.hudson.test.SleepBuilder;
import org.jvnet.hudson.test.junit.jupiter.WithJenkins;

/**
 * A Pipeline {@code node {}} block runs as a {@link Queue.Task} that is not a {@code Run}, so the agent's
 * "limited builds" counter cannot rely on {@link vSphereCloudRunListener#onStarted} alone: it counts the Pipeline
 * run once, when its first block starts, and ends it when that run is finalized.
 */
@WithJenkins
class vSphereCloudExecutorListenerTest {

    /** Stands in for the {@code PlaceholderTask} that Pipeline's {@code node {}} step queues. */
    private static final class NodeBlockTask extends AbstractQueueTask {
        private final FreeStyleBuild pipelineRun;

        NodeBlockTask(FreeStyleBuild pipelineRun) {
            this.pipelineRun = pipelineRun;
        }

        @Override
        public Queue.Executable getOwnerExecutable() {
            return pipelineRun;
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
            return "node-block";
        }

        @Override
        public String getFullDisplayName() {
            return "node-block";
        }

        @Override
        public void checkAbortPermission() {}

        @Override
        public boolean hasAbortPermission() {
            return true;
        }

        @Override
        public String getUrl() {
            return "node-block/";
        }

        @Override
        public ResourceList getResourceList() {
            return new ResourceList();
        }

        @Override
        public String getDisplayName() {
            return "node-block";
        }

        @Override
        public Queue.Executable createExecutable() {
            throw new UnsupportedOperationException();
        }
    }

    private final vSphereCloudExecutorListener executorListener = new vSphereCloudExecutorListener();
    private final vSphereCloudRunListener runListener = new vSphereCloudRunListener();

    private static vSphereCloudSlave agent(JenkinsRule r, String name, int limit) throws Exception {
        final vSphereCloudSlave agent = new vSphereCloudSlave(
                name,
                "",
                "/home/jenkins",
                "1",
                Mode.NORMAL,
                "",
                new ComputerLauncher() {},
                RetentionStrategy.NOOP,
                Collections.emptyList(),
                "some-cloud",
                "some-vm",
                false,
                false,
                "",
                "60",
                "Shutdown",
                Integer.toString(limit));
        r.jenkins.addNode(agent);
        return agent;
    }

    private static Executor executorOf(vSphereCloudSlave agent) {
        return new Executor(agent.toComputer(), 0);
    }

    private final List<FreeStyleBuild> startedRuns = new ArrayList<>();

    /** Any running {@link Run} will do as the owner of the node blocks; it does not run on a vSphere agent. */
    private FreeStyleBuild pipelineRun(JenkinsRule r) throws Exception {
        final FreeStyleProject project = r.createFreeStyleProject();
        project.getBuildersList().add(new SleepBuilder(600_000));
        final FreeStyleBuild build = project.scheduleBuild2(0).waitForStart();
        startedRuns.add(build);
        return build;
    }

    @AfterEach
    void stopRuns() {
        for (FreeStyleBuild build : startedRuns) {
            final Executor executor = build.getExecutor();
            if (executor != null) {
                executor.interrupt();
            }
        }
    }

    @Test
    void listenerIsRegisteredAsAnExtension(JenkinsRule r) {
        assertThat(
                ExtensionList.lookup(ExecutorListener.class), hasItem(instanceOf(vSphereCloudExecutorListener.class)));
    }

    @Test
    void firstNodeBlockOfAPipelineRunIsCounted(JenkinsRule r) throws Exception {
        final vSphereCloudSlave agent = agent(r, "agent", 5);
        final FreeStyleBuild run = pipelineRun(r);

        executorListener.taskStarted(executorOf(agent), new NodeBlockTask(run));

        assertThat(agent.getNumberOfLimitedTestRuns(), is(1));
        runListener.onFinalized(run);
    }

    @Test
    void furtherNodeBlocksOfTheSameRunAreNotCounted(JenkinsRule r) throws Exception {
        final vSphereCloudSlave first = agent(r, "first", 5);
        final vSphereCloudSlave other = agent(r, "other", 5);
        final FreeStyleBuild run = pipelineRun(r);

        executorListener.taskStarted(executorOf(first), new NodeBlockTask(run));
        executorListener.taskStarted(executorOf(first), new NodeBlockTask(run));
        executorListener.taskStarted(executorOf(other), new NodeBlockTask(run));

        assertThat(first.getNumberOfLimitedTestRuns(), is(1));
        assertThat(other.getNumberOfLimitedTestRuns(), is(0));
        runListener.onFinalized(run);
    }

    @Test
    void eachParallelPipelineRunIsCounted(JenkinsRule r) throws Exception {
        final vSphereCloudSlave agent = agent(r, "agent", 5);
        final FreeStyleBuild run1 = pipelineRun(r);
        final FreeStyleBuild run2 = pipelineRun(r);

        executorListener.taskStarted(executorOf(agent), new NodeBlockTask(run1));
        executorListener.taskStarted(executorOf(agent), new NodeBlockTask(run2));

        assertThat(agent.getNumberOfLimitedTestRuns(), is(2));
        runListener.onFinalized(run1);
        runListener.onFinalized(run2);
    }

    @Test
    void reachingTheLimitRecyclesTheAgentOnlyOnceTheRunIsOver(JenkinsRule r) throws Exception {
        final vSphereCloudSlave agent = agent(r, "agent", 1);
        final FreeStyleBuild run = pipelineRun(r);
        executorListener.taskStarted(executorOf(agent), new NodeBlockTask(run));
        executorListener.taskStarted(executorOf(agent), new NodeBlockTask(run));
        assertThat(agent.getNumberOfLimitedTestRuns(), is(1));

        runListener.onFinalized(run);

        assertThat(agent.getNumberOfLimitedTestRuns(), is(0));
        assertThat(vSphereCloudSlave.hasLimitedTestRun(run), is(false));
    }

    @Test
    void anAgentRemovedBeforeTheRunCompletesLeavesNothingBehind(JenkinsRule r) throws Exception {
        final vSphereCloudSlave agent = agent(r, "agent", 5);
        final FreeStyleBuild run = pipelineRun(r);
        executorListener.taskStarted(executorOf(agent), new NodeBlockTask(run));
        assertThat(vSphereCloudSlave.hasLimitedTestRun(run), is(true));

        r.jenkins.removeNode(agent);
        runListener.onFinalized(run);

        assertThat(vSphereCloudSlave.hasLimitedTestRun(run), is(false));
    }

    @Test
    void aRunThatWasNotCountedDoesNotResetTheCount(JenkinsRule r) throws Exception {
        final vSphereCloudSlave agent = agent(r, "agent", 5);
        final FreeStyleBuild counted = pipelineRun(r);
        executorListener.taskStarted(executorOf(agent), new NodeBlockTask(counted));

        runListener.onFinalized(pipelineRun(r));

        assertThat(agent.getNumberOfLimitedTestRuns(), is(1));
        runListener.onFinalized(counted);
    }

    @Test
    void aNodeBlockStartedAfterTheRunIsOverIsNotCounted(JenkinsRule r) throws Exception {
        final vSphereCloudSlave agent = agent(r, "agent", 5);
        final FreeStyleBuild finished = r.buildAndAssertSuccess(r.createFreeStyleProject());

        executorListener.taskStarted(executorOf(agent), new NodeBlockTask(finished));

        assertThat(agent.getNumberOfLimitedTestRuns(), is(0));
        assertThat(vSphereCloudSlave.hasLimitedTestRun(finished), is(false));
    }

    @Test
    void jobsAreLeftToTheRunListener(JenkinsRule r) throws Exception {
        final vSphereCloudSlave agent = agent(r, "agent", 5);
        final FreeStyleProject project = r.createFreeStyleProject();

        executorListener.taskStarted(executorOf(agent), project);

        assertThat(agent.getNumberOfLimitedTestRuns(), is(0));
    }

    @Test
    void agentsWithoutALimitAreNotTracked(JenkinsRule r) throws Exception {
        final vSphereCloudSlave agent = agent(r, "agent", 0);
        final FreeStyleBuild run = pipelineRun(r);

        executorListener.taskStarted(executorOf(agent), new NodeBlockTask(run));

        assertThat(agent.getNumberOfLimitedTestRuns(), is(0));
        assertThat(vSphereCloudSlave.hasLimitedTestRun(run), is(false));
    }
}
