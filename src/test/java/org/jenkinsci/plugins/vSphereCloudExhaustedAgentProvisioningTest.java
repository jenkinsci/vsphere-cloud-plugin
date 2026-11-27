package org.jenkinsci.plugins;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.everyItem;
import static org.hamcrest.Matchers.greaterThan;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.nullValue;

import hudson.Launcher;
import hudson.model.AbstractBuild;
import hudson.model.BuildListener;
import hudson.model.Computer;
import hudson.model.FreeStyleProject;
import hudson.model.Label;
import hudson.model.LoadStatistics;
import hudson.model.Node.Mode;
import hudson.model.TaskListener;
import hudson.slaves.ComputerLauncher;
import hudson.slaves.NodeProvisioner;
import hudson.slaves.OfflineCause;
import hudson.slaves.RetentionStrategy;
import hudson.slaves.SlaveComputer;
import hudson.util.OneShotEvent;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;
import java.util.stream.Collectors;
import org.jenkinsci.plugins.vsphere.AgentRecycler;
import org.jenkinsci.plugins.vsphere.AgentUsageRegistry;
import org.jenkinsci.plugins.vsphere.DrainingCheck;
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
import org.jvnet.hudson.test.TestBuilder;
import org.jvnet.hudson.test.junit.jupiter.WithJenkins;

/**
 * An agent that has used all its builds but has free executors: builds that wait only because of that must make Jenkins
 * ask the cloud for another agent, while the work that the agent still has to take goes on being taken.
 */
@Timeout(value = 4, unit = TimeUnit.MINUTES, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
@WithJenkins
class vSphereCloudExhaustedAgentProvisioningTest {

    private static final String CLOUD = "some-cloud";
    private static final String LABEL = "worker";
    private static final String AGENT = "node-1";

    /** Many more than the builds the agent is allowed here, so that some are free once it has used them. */
    private static final int EXECUTORS = 12;

    private static final int QUEUED = 4;

    /** How long Jenkins is given to ask the cloud, with the load statistics running a hundred times faster. */
    private static final long PROVISION_WAIT_MS = 20_000;

    private static int clock;

    @BeforeAll
    static void fastLoadStatistics() {
        clock = LoadStatistics.CLOCK;
        LoadStatistics.CLOCK = 100;
    }

    @AfterAll
    static void usualLoadStatistics() {
        LoadStatistics.CLOCK = clock;
    }

    private final List<OneShotEvent> gates = new ArrayList<>();

    @AfterEach
    void letTheBuildsEnd() {
        gates.forEach(OneShotEvent::signal);
        UsageGate.setIdleHeldMs(-1);
        DrainingCheck.setKeptMs(DrainingCheck.DEFAULT_KEPT_MS);
    }

    /** A cloud that only writes down what it is asked for. */
    private static final class RecordingCloud extends vSphereCloud {
        final transient List<Integer> asked = new CopyOnWriteArrayList<>();

        RecordingCloud(boolean noDelay) {
            super(
                    new VSphereConnectionConfig("vsHost", false, "credentialsId"),
                    CLOUD,
                    100,
                    0,
                    noDelay,
                    Collections.emptyList());
        }

        @Override
        public boolean canProvision(Label label) {
            return label != null && LABEL.equals(label.getName());
        }

        @Override
        public Collection<NodeProvisioner.PlannedNode> provision(Label label, int excessWorkload) {
            asked.add(excessWorkload);
            return Collections.emptyList();
        }
    }

    /** Connects and disconnects without a vSphere system. */
    private static final class InProcessLauncher extends vSphereCloudLauncher {
        InProcessLauncher(ComputerLauncher delegate, int limit) {
            super(delegate, CLOUD, "vm", false, false, "", "60", "Nothing", Integer.toString(limit));
        }

        @Override
        public void launch(SlaveComputer computer, TaskListener listener) throws IOException, InterruptedException {
            getDelegate().launch(computer, listener);
        }

        @Override
        public void afterDisconnect(SlaveComputer computer, TaskListener listener) {
            getDelegate().afterDisconnect(computer, listener);
        }
    }

    private static RecordingCloud cloud(JenkinsRule r, boolean noDelay) {
        AgentRecycler.get().setCheckDelayMs(500);
        final RecordingCloud cloud = new RecordingCloud(noDelay);
        r.jenkins.clouds.add(cloud);
        return cloud;
    }

    private static vSphereCloudSlave connectedAgent(JenkinsRule r, int limit) throws Exception {
        final vSphereCloudSlave agent = newAgent(r, limit);
        r.jenkins.addNode(agent);
        final SlaveComputer computer = (SlaveComputer) agent.toComputer();
        computer.connect(false).get();
        waitFor("the agent to be online", computer::isOnline);
        return agent;
    }

    private static vSphereCloudSlave newAgent(JenkinsRule r, int limit) throws Exception {
        final vSphereCloudSlave agent = new vSphereCloudSlave(
                AGENT,
                "",
                new File(r.jenkins.getRootDir(), "agent-" + AGENT).getPath(),
                Integer.toString(EXECUTORS),
                Mode.NORMAL,
                LABEL,
                new ComputerLauncher() {},
                RetentionStrategy.NOOP,
                Collections.emptyList(),
                CLOUD,
                "vm",
                false,
                false,
                "",
                "60",
                "Nothing",
                Integer.toString(limit));
        agent.setLauncher(new InProcessLauncher(r.createComputerLauncher(null), limit));
        return agent;
    }

    private static void waitFor(String what, BooleanSupplier condition) throws InterruptedException {
        final long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(60);
        while (!condition.getAsBoolean()) {
            if (System.nanoTime() > deadline) {
                throw new AssertionError("Timed out waiting for " + what);
            }
            Thread.sleep(100);
        }
    }

    private static FreeStyleProject project(JenkinsRule r) throws Exception {
        final FreeStyleProject project = r.createFreeStyleProject();
        project.setAssignedLabel(r.jenkins.getLabel(LABEL));
        return project;
    }

    /** A build that stays on its executor until the end of the test. */
    private void startLongBuild(JenkinsRule r) throws Exception {
        final OneShotEvent gate = new OneShotEvent();
        gates.add(gate);
        final FreeStyleProject project = project(r);
        project.getBuildersList().add(new TestBuilder() {
            @Override
            public boolean perform(AbstractBuild<?, ?> build, Launcher launcher, BuildListener listener)
                    throws InterruptedException {
                gate.block();
                return true;
            }
        });
        project.scheduleBuild2(0).waitForStart();
    }

    /** Queues builds of different jobs, and waits until all of them are ready to run and none has an executor. */
    private static List<FreeStyleProject> queueBuilds(JenkinsRule r, int count) throws Exception {
        final List<FreeStyleProject> projects = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            final FreeStyleProject project = project(r);
            project.scheduleBuild2(0);
            projects.add(project);
        }
        waitFor(
                "the builds to be waiting",
                () -> r.jenkins.getQueue().getBuildableItems().size() == count);
        return projects;
    }

    /** Uses all the builds of the agent: all but one are over, the last one stays on an executor. */
    private vSphereCloudSlave busyAgentAtItsLimit(JenkinsRule r) throws Exception {
        final int limit = 3;
        final vSphereCloudSlave agent = connectedAgent(r, limit);
        for (int i = 1; i < limit; i++) {
            r.buildAndAssertSuccess(project(r));
        }
        startLongBuild(r);
        assertAtItsLimitWithFreeExecutors(agent, limit, EXECUTORS - 1);
        return agent;
    }

    /** What every scenario here is about. */
    private static void assertAtItsLimitWithFreeExecutors(vSphereCloudSlave agent, int limit, int free)
            throws InterruptedException {
        final Computer computer = agent.toComputer();
        // A run is counted a moment after its executor is busy, so the count is waited for as well.
        waitFor(
                limit + " uses and " + free + " free executors",
                () -> agent.getNumberOfLimitedTestRuns() == limit && computer.countIdle() == free);
        assertThat("uses of the agent", agent.getNumberOfLimitedTestRuns(), is(limit));
        assertThat("online", computer.isOnline(), is(true));
        assertThat("free executors", computer.countIdle(), is(free));
        assertThat("free executors", free, greaterThan(0));
        assertThat("recycled", AgentRecycler.get().check(AGENT), is(false));
    }

    private static String state(JenkinsRule r, vSphereCloudSlave agent) {
        final LoadStatistics.LoadStatisticsSnapshot s =
                r.jenkins.getLabel(LABEL).loadStatistics.computeSnapshot();
        return "uses=" + agent.getNumberOfLimitedTestRuns() + "/" + agent.getLimitedTestRunCount()
                + " freeExecutors=" + agent.toComputer().countIdle() + " queued=" + s.getQueueLength()
                + " countedAsAvailable=" + s.getAvailableExecutors();
    }

    /** Fails unless the cloud is asked for an agent in the time given, with the provisioner looking all along. */
    private static void assertTheCloudIsAsked(JenkinsRule r, vSphereCloudSlave agent, RecordingCloud cloud)
            throws InterruptedException {
        final long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(PROVISION_WAIT_MS);
        while (cloud.asked.isEmpty() && System.nanoTime() < deadline) {
            r.jenkins.getLabel(LABEL).nodeProvisioner.suggestReviewNow();
            Thread.sleep(200);
        }
        final String seen = state(r, agent) + " asked=" + cloud.asked;
        assertThat("the cloud was never asked for another agent; " + seen, cloud.asked.isEmpty(), is(false));
    }

    @Test
    void aBusyAgentAtItsLimitWithFreeExecutorsMakesTheCloudBeAsked(JenkinsRule r) throws Exception {
        final RecordingCloud cloud = cloud(r, false);
        final vSphereCloudSlave agent = busyAgentAtItsLimit(r);
        queueBuilds(r, QUEUED);

        assertTheCloudIsAsked(r, agent, cloud);
    }

    @Test
    void aBusyAgentAtItsLimitWithFreeExecutorsMakesTheNoDelayProvisionerAsk(JenkinsRule r) throws Exception {
        final RecordingCloud cloud = cloud(r, true);
        final vSphereCloudSlave agent = busyAgentAtItsLimit(r);
        queueBuilds(r, QUEUED);

        assertTheCloudIsAsked(r, agent, cloud);
    }

    @Test
    void anIdleAgentAtItsLimitHeldByARunMakesTheCloudBeAsked(JenkinsRule r) throws Exception {
        final RecordingCloud cloud = cloud(r, false);
        final vSphereCloudSlave agent = connectedAgent(r, 1);
        // A Pipeline that goes on outside of any node block keeps the agent from being recycled, with nothing on it.
        final WorkflowJob job = r.createProject(WorkflowJob.class, "held");
        job.setDefinition(new CpsFlowDefinition("node('" + LABEL + "') { echo 'in' }\nsleep 600\n", true));
        final WorkflowRun held = job.scheduleBuild2(0).waitForStart();
        try {
            waitFor("the run to be counted", () -> agent.getNumberOfLimitedTestRuns() == 1);
            assertAtItsLimitWithFreeExecutors(agent, 1, EXECUTORS);
            queueBuilds(r, QUEUED);

            assertTheCloudIsAsked(r, agent, cloud);
        } finally {
            held.doKill();
            r.waitForCompletion(held);
        }
    }

    /** What must not be lost: the agent goes on taking what it has to, and only that. */
    @Test
    void theNextBlockOfACountedPipelineRunsWhileOtherBuildsWait(JenkinsRule r) throws Exception {
        cloud(r, false);
        final vSphereCloudSlave agent = connectedAgent(r, 1);
        final File gate = new File(r.jenkins.getRootDir(), "gate");
        final String path = gate.getAbsolutePath().replace('\\', '/');
        final WorkflowJob job = r.createProject(WorkflowJob.class, "two-blocks");
        job.setDefinition(new CpsFlowDefinition(
                "node('" + LABEL + "') { echo 'first' }\n"
                        + "waitUntil { new File('" + path + "').exists() }\n"
                        + "node('" + LABEL + "') { echo 'second' }\n",
                false));
        final WorkflowRun run = job.scheduleBuild2(0).waitForStart();
        waitFor("the run to be counted", () -> agent.getNumberOfLimitedTestRuns() == 1);
        assertAtItsLimitWithFreeExecutors(agent, 1, EXECUTORS);
        final List<FreeStyleProject> others = queueBuilds(r, QUEUED);

        Files.createFile(gate.toPath());

        r.assertBuildStatusSuccess(r.waitForCompletion(run));
        r.assertLogContains("second", run);
        assertThat(
                others.stream().map(FreeStyleProject::getLastBuild).collect(Collectors.toList()),
                everyItem(is(nullValue())));
    }

    /** The queue cannot say it any more once the agent stops accepting tasks, so the page of the agent does. */
    @Test
    void thePageOfTheAgentSaysThatItHasReachedItsLimit(JenkinsRule r) throws Exception {
        cloud(r, false);
        busyAgentAtItsLimit(r);

        assertThat(
                r.createWebClient().goTo("computer/" + AGENT + "/").asNormalizedText(),
                containsString("has reached its limit of 3 builds"));
    }

    @Test
    void anAgentAtItsLimitWithNothingWaitingForItDoesNotAcceptTasks(JenkinsRule r) throws Exception {
        cloud(r, false);
        final vSphereCloudSlave agent = busyAgentAtItsLimit(r);

        assertThat(agent.toComputer().isAcceptingTasks(), is(false));
    }

    @Test
    void anAgentBelowItsLimitAcceptsTasks(JenkinsRule r) throws Exception {
        cloud(r, false);
        final vSphereCloudSlave agent = connectedAgent(r, 100);
        startLongBuild(r);

        assertThat(agent.toComputer().isAcceptingTasks(), is(true));
    }

    /** Nothing may keep an agent that is offline from being launched when it is needed. */
    @Test
    void anOfflineAgentAtItsLimitAcceptsTasks(JenkinsRule r) throws Exception {
        cloud(r, false);
        final vSphereCloudSlave agent = busyAgentAtItsLimit(r);

        agent.toComputer().setTemporaryOfflineCause(new OfflineCause.ByCLI("by hand"));

        assertThat(agent.toComputer().isAcceptingTasks(), is(true));
    }

    @Test
    void anAgentWhoseUsageIsResetAcceptsTasksAgain(JenkinsRule r) throws Exception {
        cloud(r, false);
        final vSphereCloudSlave agent = busyAgentAtItsLimit(r);

        AgentUsageRegistry.get().reset(AGENT);

        assertThat(agent.toComputer().isAcceptingTasks(), is(true));
    }

    /** Saving an agent replaces its node, while its computer carries on. */
    @Test
    void theLimitThatAnAgentIsSavedWithIsTheOneThatCounts(JenkinsRule r) throws Exception {
        cloud(r, false);
        connectedAgent(r, 100);
        startLongBuild(r);
        waitFor("the build to be counted", () -> AgentUsageRegistry.get().count(AGENT) == 1);

        r.jenkins.addNode(newAgent(r, 1));

        assertThat(r.jenkins.getComputer(AGENT).isAcceptingTasks(), is(false));
    }

    /**
     * Looking through the queue is not done for every question, but a build that has just become ready to run is seen
     * at once, as the agent may have to take it.
     */
    @Test
    void theAnswerIsKeptUntilAnotherBuildIsReadyToRun(JenkinsRule r) throws Exception {
        cloud(r, false);
        DrainingCheck.setKeptMs(TimeUnit.HOURS.toMillis(1));
        final vSphereCloudSlave agent = connectedAgent(r, 1);
        final WorkflowJob job = r.createProject(WorkflowJob.class, "held");
        job.setDefinition(new CpsFlowDefinition("node('" + LABEL + "') { echo 'in' }\nsleep 600\n", true));
        final WorkflowRun held = job.scheduleBuild2(0).waitForStart();
        try {
            waitFor("the run to be counted", () -> agent.getNumberOfLimitedTestRuns() == 1);
            assertAtItsLimitWithFreeExecutors(agent, 1, EXECUTORS);
            queueBuilds(r, 1);
            final Computer computer = agent.toComputer();
            assertThat(computer.isAcceptingTasks(), is(false));

            // Now the agent takes work although it is at its limit, which nothing in the queue tells.
            UsageGate.setIdleHeldMs(0);
            assertThat("the answer that was kept", computer.isAcceptingTasks(), is(false));

            final FreeStyleProject another = project(r);
            another.scheduleBuild2(0);
            waitFor("a build to run on the agent", () -> computer.countBusy() == 1);
        } finally {
            held.doKill();
            r.waitForCompletion(held);
        }
    }

    /** Control: the same load, with the agent taken offline as an administrator would. */
    @Test
    void controlTakingTheAgentOfflineMakesTheCloudBeAsked(JenkinsRule r) throws Exception {
        final RecordingCloud cloud = cloud(r, false);
        final vSphereCloudSlave agent = busyAgentAtItsLimit(r);
        queueBuilds(r, QUEUED);

        agent.toComputer().setTemporaryOfflineCause(new OfflineCause.ByCLI("by hand"));

        assertTheCloudIsAsked(r, agent, cloud);
    }

    /** Control: with every executor busy and no limit reached, the harness does see the cloud being asked. */
    @Test
    void controlAFullAgentMakesTheCloudBeAsked(JenkinsRule r) throws Exception {
        final RecordingCloud cloud = cloud(r, false);
        final vSphereCloudSlave agent = connectedAgent(r, 100);
        for (int i = 0; i < EXECUTORS; i++) {
            startLongBuild(r);
        }
        queueBuilds(r, QUEUED);

        assertTheCloudIsAsked(r, agent, cloud);
    }
}
