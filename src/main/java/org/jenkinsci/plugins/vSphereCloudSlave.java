package org.jenkinsci.plugins;

import static org.jenkinsci.plugins.vsphere.tools.PermissionUtils.throwUnlessUserHasPermissionToConfigureSlave;

import com.vmware.vim25.mo.VirtualMachine;
import com.vmware.vim25.mo.VirtualMachineSnapshot;
import edu.umd.cs.findbugs.annotations.NonNull;
import hudson.AbortException;
import hudson.Extension;
import hudson.Util;
import hudson.model.Computer;
import hudson.model.Descriptor.FormException;
import hudson.model.Executor;
import hudson.model.ItemGroup;
import hudson.model.Node;
import hudson.model.Queue;
import hudson.model.Queue.BuildableItem;
import hudson.model.Result;
import hudson.model.Run;
import hudson.model.TaskListener;
import hudson.model.queue.CauseOfBlockage;
import hudson.slaves.*;
import hudson.util.FormValidation;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Date;
import java.util.Iterator;
import java.util.List;
import java.util.Map.Entry;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;
import jenkins.model.Jenkins;
import jenkins.model.NodeListener;
import org.jenkinsci.plugins.vsphere.AgentUsageRegistry;
import org.jenkinsci.plugins.vsphere.VSphereOfflineCause;
import org.jenkinsci.plugins.vsphere.tools.VSphere;
import org.kohsuke.accmod.Restricted;
import org.kohsuke.accmod.restrictions.NoExternalUse;
import org.kohsuke.stapler.AncestorInPath;
import org.kohsuke.stapler.DataBoundConstructor;
import org.kohsuke.stapler.QueryParameter;
import org.kohsuke.stapler.interceptor.RequirePOST;

/**
 *
 * @author Admin
 */
public class vSphereCloudSlave extends AbstractCloudSlave {

    private static final long serialVersionUID = 1L;

    private final String vsDescription;
    private final String vmName;
    private final String snapName;
    private final Boolean waitForVMTools;
    private final String launchDelay;
    private final String idleOption;
    /** If more than zero then this is the number of build-jobs we limit the agent to. */
    private Integer LimitedTestRunCount = 0;

    public transient Boolean doingLastInLimitedTestRun = Boolean.FALSE;

    // The list of agents that MIGHT be launched.
    private static ConcurrentHashMap<vSphereCloudSlave, ProbableLaunchData> ProbableLaunch;
    private static final Object ProbableLaunchLock = new Object();

    public transient Boolean slaveIsStarting = Boolean.FALSE;
    public transient Boolean slaveIsDisconnecting = Boolean.FALSE;

    @DataBoundConstructor
    public vSphereCloudSlave(
            String name,
            String nodeDescription,
            String remoteFS,
            String numExecutors,
            Mode mode,
            String labelString,
            ComputerLauncher delegateLauncher,
            RetentionStrategy retentionStrategy,
            List<? extends NodeProperty<?>> nodeProperties,
            String vsDescription,
            String vmName,
            boolean launchSupportForced,
            boolean waitForVMTools,
            String snapName,
            String launchDelay,
            String idleOption,
            String LimitedTestRunCount)
            throws FormException, IOException {
        super(
                name,
                nodeDescription,
                remoteFS,
                numExecutors,
                mode,
                labelString,
                new vSphereCloudLauncher(
                        delegateLauncher,
                        vsDescription,
                        vmName,
                        launchSupportForced,
                        waitForVMTools,
                        snapName,
                        launchDelay,
                        idleOption,
                        LimitedTestRunCount),
                retentionStrategy,
                nodeProperties);
        this.vsDescription = vsDescription;
        this.vmName = vmName;
        this.snapName = snapName;
        this.waitForVMTools = waitForVMTools;
        this.launchDelay = launchDelay;
        this.idleOption = idleOption;
        final Number parsedLimitedTestRunCount = Util.tryParseNumber(LimitedTestRunCount, 0);
        this.LimitedTestRunCount = parsedLimitedTestRunCount != null ? parsedLimitedTestRunCount.intValue() : 0;
        readResolve();
    }

    @Override
    protected Object readResolve() {
        super.readResolve();
        if (LimitedTestRunCount == null) {
            LimitedTestRunCount = 0;
        }
        return this;
    }

    public String getVmName() {
        return vmName;
    }

    public String getVsDescription() {
        return vsDescription;
    }

    public String getSnapName() {
        return snapName;
    }

    public Boolean getWaitForVMTools() {
        return waitForVMTools;
    }

    public String getLaunchDelay() {
        return launchDelay;
    }

    public String getIdleOption() {
        return idleOption;
    }

    public Integer getLimitedTestRunCount() {
        return LimitedTestRunCount;
    }

    public boolean isLaunchSupportForced() {
        return Boolean.TRUE.equals(((vSphereCloudLauncher) getLauncher()).getOverrideLaunchSupported());
    }

    @Override
    protected void _terminate(final TaskListener listener) throws IOException, InterruptedException {
        try {
            Computer computer = toComputer();
            if (computer != null) {
                final VSphereOfflineCause cause =
                        new VSphereOfflineCause(Messages._vSphereCloudSlave_OfflineReason_ShuttingDown());
                computer.disconnect(cause);
                vSphereCloud.Log(this, listener, "Disconnected computer %s", vmName);
            } else {
                vSphereCloud.Log(
                        this,
                        listener,
                        "Can't disconnect computer for %s as there was no Computer node for it.",
                        vmName);
            }
        } catch (Exception e) {
            vSphereCloud.Log(this, listener, e, "Can't disconnect %s", vmName);
        }
    }

    @Restricted(NoExternalUse.class)
    vSphereCloud findOurVsInstance() {
        final ComputerLauncher l = getLauncher();
        return findOurVsInstance(l);
    }

    @Restricted(NoExternalUse.class)
    protected vSphereCloud findOurVsInstance(final ComputerLauncher l) {
        if (l instanceof vSphereCloudLauncher) {
            final vSphereCloudLauncher launcher = (vSphereCloudLauncher) l;
            final vSphereCloud cloud = launcher.findOurVsInstance();
            return cloud;
        }
        return null;
    }

    private static class ProbableLaunchData {

        public vSphereCloudSlave slave;
        public Date expiration;

        public ProbableLaunchData(vSphereCloudSlave slave, Date expiration) {
            this.slave = slave;
            this.expiration = expiration;
        }
    }

    private static void InitProbableLaunch() {
        synchronized (ProbableLaunchLock) {
            if (ProbableLaunch == null) {
                ProbableLaunch = new ConcurrentHashMap<vSphereCloudSlave, ProbableLaunchData>();
            }
        }
    }

    public static void AddProbableLaunch(vSphereCloudSlave slave, Date target) {
        synchronized (ProbableLaunchLock) {
            InitProbableLaunch();
            ProbableLaunch.put(slave, new ProbableLaunchData(slave, target));
        }
    }

    public static void RemoveProbableLaunch(vSphereCloudSlave slave) {
        synchronized (ProbableLaunchLock) {
            if (ProbableLaunch != null) {
                ProbableLaunch.remove(slave);
            }
        }
    }

    public static void ProbableLaunchCleanup() {
        synchronized (ProbableLaunchLock) {
            InitProbableLaunch();
            // Clean out any probable launches that have elapsed.
            Date now = new Date();
            Iterator<Entry<vSphereCloudSlave, ProbableLaunchData>> it =
                    ProbableLaunch.entrySet().iterator();
            while (it.hasNext()) {
                Entry<vSphereCloudSlave, ProbableLaunchData> entry = it.next();
                if (entry.getValue().expiration.before(now)) {
                    it.remove();
                }
            }
        }
    }

    public static int ProbableLaunchCount() {
        synchronized (ProbableLaunchLock) {
            if (ProbableLaunch != null) {
                return ProbableLaunch.size();
            }
            return 0;
        }
    }

    public static vSphereCloudSlave ProbablyLaunchCanHandle(BuildableItem item) {
        synchronized (ProbableLaunchLock) {
            InitProbableLaunch();
            Iterator<Entry<vSphereCloudSlave, ProbableLaunchData>> it =
                    ProbableLaunch.entrySet().iterator();
            while (it.hasNext()) {
                ProbableLaunchData data = it.next().getValue();
                if (data.slave.canTake(item) == null) {
                    return data.slave;
                }
            }
        }
        return null;
    }

    @Override
    public AbstractCloudComputer createComputer() {
        return new vSphereCloudSlaveComputer(this);
    }

    @Override
    public CauseOfBlockage canTake(BuildableItem buildItem) {
        // https://issues.jenkins-ci.org/browse/JENKINS-30203
        if (buildItem.task instanceof Queue.FlyweightTask) {
            return CauseOfBlockage.fromMessage(Messages._vSphereCloudSlave_BlockageReason_NoFlyweightTasks());
        }

        if (Boolean.TRUE.equals(slaveIsStarting)) {
            return new CauseOfBlockage.BecauseNodeIsBusy(this);
        }

        if (Boolean.TRUE.equals(slaveIsDisconnecting)) {
            return new CauseOfBlockage.BecauseNodeIsOffline(this);
        }

        return super.canTake(buildItem);
    }

    public boolean StartLimitedTestRun(Run r, TaskListener listener) {
        return startLimitedTest(r, r.getExecutor(), listener, true);
    }

    /**
     * Counts a Pipeline {@link Run} the first time one of its {@code node {}} blocks starts on an agent: the
     * {@link Run} itself is owned by a flyweight executor on the controller, so {@link #StartLimitedTestRun} never
     * sees it. Further blocks of the same {@link Run}, on this or another agent, are not counted again.
     */
    void StartLimitedPipelineRun(Executor executor, Run run) {
        startLimitedTest(run, executor, null, false);
    }

    private boolean startLimitedTest(Run run, Executor executor, TaskListener listener, boolean abortIfOverLimit) {
        if (executor == null) {
            // Nothing to count it on. As always, say whether there would have been room.
            return LimitedTestRunCount <= 0 || getNumberOfLimitedTestRuns() < LimitedTestRunCount;
        }
        final AgentUsageRegistry.Acquired acquired =
                AgentUsageRegistry.get().acquire(getNodeName(), LimitedTestRunCount, run);
        switch (acquired.outcome()) {
            case COUNTED:
                vSphereCloud.Log(
                        this,
                        listener,
                        "Starting limited count build: %d of %d",
                        acquired.count(),
                        LimitedTestRunCount);
                return true;
            case OVER_LIMIT:
                vSphereCloud.Log(
                        this,
                        listener,
                        abortIfOverLimit
                                ? "Terminating build due to limited build count: %d of %d"
                                : "Not counting work started beyond the limited build count: %d of %d",
                        acquired.count(),
                        LimitedTestRunCount);
                if (abortIfOverLimit) {
                    executor.interrupt(Result.ABORTED);
                }
                return false;
            default:
                // No limit, or the run is counted already, or it is over.
                return true;
        }
    }

    public boolean EndLimitedTestRun(Run r) {
        return endLimitedTest(r);
    }

    /**
     * The {@link Run} is over, whether it is a freestyle build or a Pipeline (so none of its {@code node {}} blocks is
     * still using the agent). Does nothing for a {@link Run} that was not counted.
     */
    static void EndLimitedRun(Run run) {
        final vSphereCloudSlave agent = findCountedAgent(run, Jenkins.get()::getNode);
        if (agent != null) {
            agent.endLimitedTest(run);
        } else {
            // The run was not counted, or its agent is gone (e.g. removed by a run-once retention strategy).
            AgentUsageRegistry.get().release(run, 0);
        }
    }

    /**
     * The agent the run is counted on, or null if there is none. The agent may be renamed between asking where the run
     * was counted and looking the agent up, in which case it is asked again. (Jenkins renames the agent in its own list
     * a moment before the listener that renames it here is called; a run that ends in that moment is not found, and
     * the agent starts again from nothing when that listener runs.)
     */
    static vSphereCloudSlave findCountedAgent(Run run, Function<String, Node> lookup) {
        final AgentUsageRegistry registry = AgentUsageRegistry.get();
        String name = registry.nodeOf(run);
        for (int attempt = 0; name != null && attempt < 2; attempt++) {
            final Node node = lookup.apply(name);
            if (node instanceof vSphereCloudSlave) {
                return (vSphereCloudSlave) node;
            }
            final String current = registry.nodeOf(run);
            if (name.equals(current)) {
                break;
            }
            name = current;
        }
        return null;
    }

    static boolean hasLimitedTestRun(Run run) {
        return AgentUsageRegistry.get().isCounted(run);
    }

    int getNumberOfLimitedTestRuns() {
        return AgentUsageRegistry.get().count(getNodeName());
    }

    private boolean endLimitedTest(Run run) {
        if (AgentUsageRegistry.get().release(run, LimitedTestRunCount) != AgentUsageRegistry.Released.RECYCLE) {
            return true;
        }
        final Computer slave = toComputer();
        try {
            if (slave != null) {
                vSphereCloud.Log(
                        this, "Disconnecting the slave agent on %s due to limited build threshold", slave.getName());

                final VSphereOfflineCause tempOffline =
                        new VSphereOfflineCause(Messages._vSphereCloudSlave_LimitedBuild_TemporarilyOnline());
                slave.setTemporarilyOffline(true, tempOffline);
                slave.waitUntilOffline();
                final VSphereOfflineCause disconnect =
                        new VSphereOfflineCause(Messages._vSphereCloudSlave_LimitedBuild_Disconnect());
                slave.disconnect(disconnect);
                final VSphereOfflineCause tempOnline =
                        new VSphereOfflineCause(Messages._vSphereCloudSlave_LimitedBuild_TemporarilyOnline());
                slave.setTemporarilyOffline(false, tempOnline);
            } else {
                vSphereCloud.Log(
                        this,
                        "Attempting to shutdown slave due to limited build threshold, but cannot determine slave");
            }
        } catch (NullPointerException ex) {
            vSphereCloud.Log(this, ex, "NullPointerException thrown while retrieving the slave agent");
        } catch (InterruptedException ex) {
            vSphereCloud.Log(this, ex, "InterruptedException thrown while marking the slave as online or offline");
        }
        return false;
    }

    /**
     * For UI.
     *
     * @return original launcher
     */
    public ComputerLauncher getDelegateLauncher() {
        return ((vSphereCloudLauncher) getLauncher()).getLauncher();
    }

    @Extension
    public static class vSphereCloudComputerListener extends ComputerListener {

        @Override
        public void preLaunch(Computer c, TaskListener taskListener) throws IOException, InterruptedException {
            /* We may be called on any agent type so check that we should
             * be in here. */
            if (!(c.getNode() instanceof vSphereCloudSlave)) {
                return;
            }

            vSphereCloudLauncher vsL = (vSphereCloudLauncher) ((SlaveComputer) c).getLauncher();
            vSphereCloud vsC = vsL.findOurVsInstance();
            if (!vsC.markVMOnline(c.getDisplayName(), vsL.getVmName())) {
                throw new AbortException("The vSphere cloud will not allow this slave to start at this time.");
            }
        }
    }

    /**
     * Saving the configuration of an agent replaces its {@link Node} (and thereby its launchers) while a
     * connected {@link SlaveComputer} carries on. Make sure the launcher that made the connection still gets
     * torn down when that computer disconnects (JENKINS-62570).
     */
    @Extension
    public static class vSphereCloudNodeListener extends NodeListener {

        @Override
        protected void onCreated(@NonNull Node node) {
            // A new agent that takes the name of one that is gone starts from nothing.
            if (node instanceof vSphereCloudSlave) {
                AgentUsageRegistry.get().forget(node.getNodeName());
            }
        }

        @Override
        protected void onDeleted(@NonNull Node node) {
            if (node instanceof vSphereCloudSlave) {
                AgentUsageRegistry.get().forget(node.getNodeName());
            }
        }

        @Override
        protected void onUpdated(@NonNull Node oldOne, @NonNull Node newOne) {
            if (!(oldOne instanceof vSphereCloudSlave) || !(newOne instanceof vSphereCloudSlave)) {
                return;
            }
            // Saving an agent replaces its node, but not its usage: keep it under the name it has now.
            final AgentUsageRegistry registry = AgentUsageRegistry.get();
            if (!oldOne.getNodeName().equals(newOne.getNodeName())) {
                registry.rename(oldOne.getNodeName(), newOne.getNodeName());
            }
            registry.reconcile(newOne.getNodeName(), ((vSphereCloudSlave) newOne).getLimitedTestRunCount());
            final Computer computer = newOne.toComputer();
            final boolean connected = computer instanceof SlaveComputer && ((SlaveComputer) computer).isOnline();
            carryOverConnectedLauncher((vSphereCloudSlave) oldOne, (vSphereCloudSlave) newOne, connected);
        }
    }

    /**
     * If the agent was connected when it was replaced, hand the old delegate launcher (which owns the live
     * connection) over to the new launcher, to be torn down at disconnect.
     */
    static void carryOverConnectedLauncher(vSphereCloudSlave oldOne, vSphereCloudSlave newOne, boolean connected) {
        if (!connected || oldOne == newOne) {
            return;
        }
        if (oldOne.getLauncher() instanceof vSphereCloudLauncher
                && newOne.getLauncher() instanceof vSphereCloudLauncher) {
            ((vSphereCloudLauncher) newOne.getLauncher()).takeOverFrom((vSphereCloudLauncher) oldOne.getLauncher());
        }
    }

    @Extension
    public static class DescriptorImpl extends SlaveDescriptor {
        public DescriptorImpl() {
            load();
        }

        @Override
        public String getDisplayName() {
            return "Agent running within a vSphere hypervisor";
        }

        public List<vSphereCloud> getvSphereClouds() {
            List<vSphereCloud> result = new ArrayList<vSphereCloud>();
            for (Cloud cloud : Jenkins.getInstance().clouds) {
                if (cloud instanceof vSphereCloud) {
                    result.add((vSphereCloud) cloud);
                }
            }
            return result;
        }

        public vSphereCloud getSpecificvSphereCloud(String vsDescription) throws Exception {
            for (vSphereCloud vs : getvSphereClouds()) {
                if (vs.getVsDescription().equals(vsDescription)) {
                    return vs;
                }
            }
            throw new Exception("The vSphere Cloud doesn't exist");
        }

        public List<String> getIdleOptions() {
            List<String> options = new ArrayList<String>();
            options.add("Shutdown");
            options.add("Shutdown and Revert");
            options.add("Revert and Restart");
            options.add("Revert and Reset");
            options.add("Suspend");
            options.add("Reset");
            options.add("Reconnect and Revert");
            options.add("Nothing");
            return options;
        }

        public FormValidation doCheckLaunchDelay(@QueryParameter String value) {
            return FormValidation.validatePositiveInteger(value);
        }

        @RequirePOST
        public FormValidation doTestConnection(
                @AncestorInPath ItemGroup<?> context,
                @QueryParameter String vsDescription,
                @QueryParameter String vmName,
                @QueryParameter String snapName) {
            throwUnlessUserHasPermissionToConfigureSlave(context);
            try {
                vSphereCloud vsC = getSpecificvSphereCloud(vsDescription);
                VSphere vs = vsC.vSphereInstance();
                try {
                    VirtualMachine vm = vs.getVmByName(vmName);
                    if (vm == null) {
                        return FormValidation.error("Virtual Machine was not found");
                    }
                    if (!snapName.isEmpty()) {
                        VirtualMachineSnapshot snap = vs.getSnapshotInTree(vm, snapName);
                        if (snap == null) {
                            return FormValidation.error("Virtual Machine snapshot was not found");
                        }
                    }
                    return FormValidation.ok("Virtual Machine found successfully");
                } finally {
                    vs.disconnect();
                }
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        }
    }
}
