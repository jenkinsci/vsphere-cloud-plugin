package org.jenkinsci.plugins;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.vmware.vim25.VirtualMachinePowerState;
import com.vmware.vim25.VirtualMachineRuntimeInfo;
import com.vmware.vim25.VirtualMachineSummary;
import com.vmware.vim25.mo.Task;
import com.vmware.vim25.mo.VirtualMachine;
import com.vmware.vim25.mo.VirtualMachineSnapshot;
import hudson.model.Computer;
import hudson.model.FreeStyleProject;
import hudson.model.Node.Mode;
import hudson.model.TaskListener;
import hudson.slaves.ComputerLauncher;
import hudson.slaves.ComputerListener;
import hudson.slaves.RetentionStrategy;
import hudson.slaves.SlaveComputer;
import java.io.File;
import java.util.Collections;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;
import org.jenkinsci.plugins.vsphere.AgentRecycler;
import org.jenkinsci.plugins.vsphere.AgentUsageRegistry;
import org.jenkinsci.plugins.vsphere.VSphereConnectionConfig;
import org.jenkinsci.plugins.vsphere.tools.VSphere;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.jvnet.hudson.test.JenkinsRule;
import org.jvnet.hudson.test.TestExtension;
import org.jvnet.hudson.test.junit.jupiter.WithJenkins;

/**
 * What an agent that is recycled does when it is disconnected, with the real {@link vSphereCloudLauncher}: only the
 * vSphere system is made up (there is none in a test), so the launcher launches and disconnects as it does in
 * production, and says how the disconnect went to the recycling that waits for it.
 */
@Timeout(value = 4, unit = TimeUnit.MINUTES, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
@WithJenkins
class vSphereCloudLauncherRecycleTest {

    private static final String SWEEP_PERIOD = AgentRecycler.class.getName() + ".sweepPeriodMs";

    private static final String CLOUD = "some-cloud";

    private static final String AGENT = "agent";

    @BeforeAll
    static void noSweepOfItsOwn() {
        System.setProperty(SWEEP_PERIOD, Long.toString(TimeUnit.DAYS.toMillis(1)));
    }

    @AfterAll
    static void sweepOfItsOwnAgain() {
        System.clearProperty(SWEEP_PERIOD);
    }

    /** The cloud, whose vSphere system is the one of the test. */
    private static final class FakeCloud extends vSphereCloud {
        static volatile VSphere vSphere;

        FakeCloud() {
            super(
                    new VSphereConnectionConfig("vsHost", false, "credentialsId"),
                    CLOUD,
                    100,
                    0,
                    false,
                    Collections.emptyList());
        }

        @Override
        public VSphere vSphereInstance() {
            return vSphere;
        }
    }

    /** Makes a launch that fails take a while to be over, as it does when something listens to it. */
    @TestExtension
    public static final class SlowLaunchFailure extends ComputerListener {
        static volatile long delayMs;

        @Override
        public void onLaunchFailure(Computer c, TaskListener taskListener) throws InterruptedException {
            if (delayMs > 0) {
                Thread.sleep(delayMs);
            }
        }
    }

    private VSphere vSphere;
    private VirtualMachineRuntimeInfo runtime;

    @AfterEach
    void forgetTheTest() {
        SlowLaunchFailure.delayMs = 0;
        FakeCloud.vSphere = null;
    }

    /** A virtual machine that is on, which the agents of a test are launched on. */
    private void aVirtualMachine(JenkinsRule r) throws Exception {
        runtime = new VirtualMachineRuntimeInfo();
        runtime.powerState = VirtualMachinePowerState.poweredOn;
        final VirtualMachineSummary summary = new VirtualMachineSummary();
        summary.setRuntime(runtime);
        final VirtualMachine vm = mock(VirtualMachine.class);
        when(vm.getRuntime()).thenReturn(runtime);
        when(vm.getSummary()).thenReturn(summary);
        vSphere = mock(VSphere.class);
        when(vSphere.getVmByName(anyString())).thenReturn(vm);
        final Task revert = mock(Task.class);
        when(revert.waitForTask()).thenReturn(Task.SUCCESS);
        final VirtualMachineSnapshot snapshot = mock(VirtualMachineSnapshot.class);
        when(snapshot.revertToSnapshot_Task(null, Boolean.FALSE)).thenReturn(revert);
        when(vSphere.getSnapshotInTree(vm, "snap")).thenReturn(snapshot);
        FakeCloud.vSphere = vSphere;
        // An agent is looked at soon after a run on it ends, not only after the five seconds it takes in production.
        AgentRecycler.get().setCheckDelayMs(500);
        r.jenkins.clouds.add(new FakeCloud());
    }

    private static vSphereCloudSlave connectedAgent(JenkinsRule r, String idleOption) throws Exception {
        return connectedAgent(r, idleOption, "");
    }

    private static vSphereCloudSlave connectedAgent(JenkinsRule r, String idleOption, String snapshot)
            throws Exception {
        final vSphereCloudSlave agent = new vSphereCloudSlave(
                AGENT,
                "",
                new File(r.jenkins.getRootDir(), "agent-" + AGENT).getPath(),
                "1",
                Mode.NORMAL,
                "lbl",
                new ComputerLauncher() {},
                RetentionStrategy.NOOP,
                Collections.emptyList(),
                CLOUD,
                "vm-" + AGENT,
                false,
                false,
                snapshot,
                "0",
                idleOption,
                "1");
        agent.setLauncher(new vSphereCloudLauncher(
                r.createComputerLauncher(null), CLOUD, "vm-" + AGENT, false, false, snapshot, "0", idleOption, "1"));
        r.jenkins.addNode(agent);
        final SlaveComputer computer = (SlaveComputer) agent.toComputer();
        computer.connect(false).get();
        waitFor("the agent to be online", computer::isOnline);
        return agent;
    }

    /** A build on the agent, which is all the builds it is allowed. */
    private static void useUpTheAgent(JenkinsRule r) throws Exception {
        final FreeStyleProject project = r.createFreeStyleProject();
        project.setAssignedLabel(r.jenkins.getLabel("lbl"));
        r.buildAndAssertSuccess(project);
    }

    /** The agent was disconnected, and what recycled it has no more to say. */
    private static void waitForTheRecycleToBeOver(vSphereCloudSlave agent) throws InterruptedException {
        waitFor("the agent to be disconnected", () -> {
            final SlaveComputer computer = (SlaveComputer) agent.toComputer();
            return computer == null || computer.getChannel() == null;
        });
        waitFor("the recycle to be over", () -> !AgentUsageRegistry.get().isRecycling(AGENT));
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

    @Test
    void anAgentThatConnectsAgainAfterItsRecycleIsOnlineWhicheverOfTheTwoThatConnectItComesLast(JenkinsRule r)
            throws Exception {
        aVirtualMachine(r);
        // A launch that fails is what a connect that comes while it lasts is given instead of a launch of its own, so
        // that the agent is not connected again if the recycle (or the second disconnect that Jenkins does) makes one
        // fail and another connect comes at once: this makes a failed launch take a while.
        SlowLaunchFailure.delayMs = 1500;
        final vSphereCloudSlave agent = connectedAgent(r, "Reconnect and Revert");

        for (int cycle = 1; cycle <= 4; cycle++) {
            useUpTheAgent(r);
            final int thisCycle = cycle;
            waitFor("the agent to be recycled and online again in cycle " + thisCycle, () -> {
                final Computer computer = agent.toComputer();
                return agent.getNumberOfLimitedTestRuns() == 0 && computer != null && computer.isOnline();
            });
        }
    }

    @Test
    void aRevertOfAMachineThatIsOffIsNotARecycle(JenkinsRule r) throws Exception {
        aVirtualMachine(r);
        final vSphereCloudSlave agent = connectedAgent(r, "Shutdown and Revert");
        runtime.powerState = VirtualMachinePowerState.poweredOff;

        useUpTheAgent(r);
        waitForTheRecycleToBeOver(agent);

        // Nothing was reverted, so the machine is not clean and the agent stays at its limit.
        assertThat(agent.getNumberOfLimitedTestRuns(), is(1));
    }

    @Test
    void aReconnectAndRevertOfAMachineThatIsOffIsARecycle(JenkinsRule r) throws Exception {
        aVirtualMachine(r);
        final vSphereCloudSlave agent = connectedAgent(r, "Reconnect and Revert");
        runtime.powerState = VirtualMachinePowerState.poweredOff;

        useUpTheAgent(r);
        // Reconnect and Revert does not depend on the state of the machine: the agent is launched again at once (and
        // reverted then, if it has a snapshot; this one has none, so only the count is at issue).
        waitFor("the agent to be recycled", () -> agent.getNumberOfLimitedTestRuns() == 0);
        waitFor("the agent to be online again", () -> agent.toComputer().isOnline());
    }

    @Test
    void aRevertOfAMachineThatIsOffIsARecycleIfTheAgentIsLaunchedFromASnapshot(JenkinsRule r) throws Exception {
        aVirtualMachine(r);
        final vSphereCloudSlave agent = connectedAgent(r, "Shutdown and Revert", "snap");
        runtime.powerState = VirtualMachinePowerState.poweredOff;

        useUpTheAgent(r);
        // It is reverted to its snapshot when it is launched again, whatever the state of the machine.
        waitFor("the agent to be recycled", () -> agent.getNumberOfLimitedTestRuns() == 0);
    }

    @Test
    void aMachineThatIsNotThereIsNotARecycle(JenkinsRule r) throws Exception {
        aVirtualMachine(r);
        final vSphereCloudSlave agent = connectedAgent(r, "Shutdown");
        when(vSphere.getVmByName(anyString())).thenReturn(null);

        useUpTheAgent(r);
        waitForTheRecycleToBeOver(agent);

        assertThat(agent.getNumberOfLimitedTestRuns(), is(1));
    }

    @Test
    void anAgentThatIsBeingConnectedWhenItIsDisconnectedIsNotARecycle(JenkinsRule r) throws Exception {
        aVirtualMachine(r);
        final vSphereCloudSlave agent = connectedAgent(r, "Shutdown");
        // The agent is not given builds while it is being connected, so it is after the one that it is allowed, and
        // before the recycle that comes soon after it.
        AgentRecycler.get().setCheckDelayMs(10_000);

        useUpTheAgent(r);
        agent.slaveIsStarting = Boolean.TRUE;
        waitForTheRecycleToBeOver(agent);

        assertThat(agent.getNumberOfLimitedTestRuns(), is(1));
        agent.slaveIsStarting = Boolean.FALSE;
    }
}
