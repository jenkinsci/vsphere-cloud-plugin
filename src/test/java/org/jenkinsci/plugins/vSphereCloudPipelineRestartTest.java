package org.jenkinsci.plugins;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;

import hudson.model.Node.Mode;
import hudson.model.Result;
import hudson.model.TaskListener;
import hudson.slaves.ComputerLauncher;
import hudson.slaves.RetentionStrategy;
import hudson.slaves.SlaveComputer;
import java.io.File;
import java.io.IOException;
import java.util.Collections;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;
import org.jenkinsci.plugins.vsphere.AgentRecycler;
import org.jenkinsci.plugins.vsphere.AgentUsageRegistry;
import org.jenkinsci.plugins.vsphere.AgentUsageRegistry.RecycleOutcome;
import org.jenkinsci.plugins.vsphere.VSphereConnectionConfig;
import org.jenkinsci.plugins.workflow.cps.CpsFlowDefinition;
import org.jenkinsci.plugins.workflow.job.WorkflowJob;
import org.jenkinsci.plugins.workflow.job.WorkflowRun;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.jvnet.hudson.test.JenkinsRule;
import org.jvnet.hudson.test.junit.jupiter.JenkinsSessionExtension;

/** A Pipeline that goes on after Jenkins was restarted, on an agent that counts its builds. */
// A test that waits for ever for something that never happens fails, with where it was, instead of stopping the build.
@Timeout(value = 6, unit = TimeUnit.MINUTES, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
class vSphereCloudPipelineRestartTest {

    private static final String CLOUD = "some-cloud";

    @RegisterExtension
    private final JenkinsSessionExtension sessions = new JenkinsSessionExtension();

    /** Connects like {@link JenkinsRule#createSlave}, with none of the vSphere work. */
    private static final class InProcessLauncher extends vSphereCloudLauncher {
        InProcessLauncher(ComputerLauncher delegate, String vmName, int limit) {
            super(delegate, CLOUD, vmName, false, false, "", "60", "Nothing", Integer.toString(limit));
        }

        @Override
        public void launch(SlaveComputer computer, TaskListener listener) throws IOException, InterruptedException {
            getDelegate().launch(computer, listener);
        }

        @Override
        public void afterDisconnect(SlaveComputer computer, TaskListener listener) {
            getDelegate().afterDisconnect(computer, listener);
            AgentRecycler.idleActionDone(computer.getName(), RecycleOutcome.COMPLETED);
        }
    }

    private static void waitFor(String what, BooleanSupplier condition) throws InterruptedException {
        final long deadline = System.nanoTime() + 60_000_000_000L;
        while (!condition.getAsBoolean()) {
            if (System.nanoTime() > deadline) {
                throw new AssertionError("Timed out waiting for " + what);
            }
            Thread.sleep(100);
        }
    }

    private static int count() {
        return AgentUsageRegistry.get().count("agent");
    }

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

    private static void addAgent(JenkinsRule r, int limit) throws Exception {
        ensureCloud(r);
        final vSphereCloudSlave agent = new vSphereCloudSlave(
                "agent",
                "",
                new File(r.jenkins.getRootDir(), "agent-agent").getPath(),
                "1",
                Mode.NORMAL,
                "lbl",
                new ComputerLauncher() {},
                RetentionStrategy.NOOP,
                Collections.emptyList(),
                CLOUD,
                "vm-agent",
                false,
                false,
                "",
                "60",
                "Nothing",
                Integer.toString(limit));
        agent.setLauncher(new InProcessLauncher(r.createComputerLauncher(null), "vm-agent", limit));
        r.jenkins.addNode(agent);
        connect(r);
    }

    private static SlaveComputer connect(JenkinsRule r) throws Exception {
        final SlaveComputer computer =
                (SlaveComputer) r.jenkins.getNode("agent").toComputer();
        if (!computer.isOnline()) {
            computer.connect(false).get();
        }
        waitFor("the agent to be online", computer::isOnline);
        return computer;
    }

    /** A run that holds the agent for a while, counted and in the middle of its block. */
    private static WorkflowRun startRun(JenkinsRule r) throws Exception {
        final WorkflowJob job = r.createProject(WorkflowJob.class, "held");
        job.setDefinition(new CpsFlowDefinition("node('lbl') { echo 'inside'; sleep 30 }\n", true));
        final WorkflowRun run = job.scheduleBuild2(0).waitForStart();
        r.waitForMessage("inside", run);
        waitFor("the run to be counted", () -> count() == 1);
        r.waitForMessage("Sleeping for", run);
        return run;
    }

    private static WorkflowRun resumedRun(JenkinsRule r) {
        return r.jenkins.getItemByFullName("held", WorkflowJob.class).getBuildByNumber(1);
    }

    @Test
    void aResumedRunIsCountedOnce() throws Throwable {
        sessions.then(r -> {
            addAgent(r, 5);
            startRun(r);
        });
        sessions.then(r -> {
            final WorkflowRun run = resumedRun(r);
            final String id = run.getExternalizableId();
            assertThat(count(), is(1));
            assertThat(AgentUsageRegistry.get().isCounted(id), is(true));
            connect(r);
            r.assertBuildStatusSuccess(r.waitForCompletion(run));
            assertThat(count(), is(1));
            assertThat(AgentUsageRegistry.get().isCounted(id), is(false));
        });
    }

    @Test
    void aRunResumedOnAnAgentAtItsLimitStillRecyclesItOnce() throws Throwable {
        sessions.then(r -> {
            addAgent(r, 1);
            startRun(r);
        });
        sessions.then(r -> {
            final WorkflowRun run = resumedRun(r);
            // Kept because of the run that was using it, not recycled under it.
            assertThat(count(), is(1));
            final SlaveComputer computer = connect(r);
            assertThat(r.waitForCompletion(run).getResult(), is(Result.SUCCESS));
            waitFor("the agent to be recycled", () -> computer.getChannel() == null);
            waitFor("the count to be reset", () -> count() == 0);
            assertThat(AgentUsageRegistry.get().isCounted(run.getExternalizableId()), is(false));
        });
    }
}
