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
import jenkins.model.Jenkins;
import jenkins.model.NodeListener;
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
    /** A count of the number of build-jobs this agent has done. */
    private transient Integer NumberOfLimitedTestRuns = 0;

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
        this.NumberOfLimitedTestRuns = 0;
        readResolve();
    }

    @Override
    protected Object readResolve() {
        super.readResolve();
        if (NumberOfLimitedTestRuns == null) {
            NumberOfLimitedTestRuns = 0;
        }
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

    private static final ConcurrentHashMap<Run, Computer> RunToSlaveMapper = new ConcurrentHashMap<Run, Computer>();

    public boolean StartLimitedTestRun(Run r, TaskListener listener) {
        boolean ret = false;
        boolean DoUpdates = false;

        if (LimitedTestRunCount > 0) {
            DoUpdates = true;
            if (NumberOfLimitedTestRuns < LimitedTestRunCount) {
                ret = true;
            }
        } else {
            ret = true;
        }

        Executor executor = r.getExecutor();
        if (executor != null && DoUpdates) {
            if (ret) {
                NumberOfLimitedTestRuns++;
                vSphereCloud.Log(
                        this,
                        listener,
                        "Starting limited count build: %d of %d",
                        NumberOfLimitedTestRuns,
                        LimitedTestRunCount);
                Computer slave = executor.getOwner();
                RunToSlaveMapper.put(r, slave);
            } else {
                vSphereCloud.Log(
                        this,
                        listener,
                        "Terminating build due to limited build count: %d of %d",
                        NumberOfLimitedTestRuns,
                        LimitedTestRunCount);
                executor.interrupt(Result.ABORTED);
            }
        }

        return ret;
    }

    public boolean EndLimitedTestRun(Run r) {
        boolean ret = true;

        // See if the run maps to an existing computer; remove if found.
        Computer slave = RunToSlaveMapper.get(r);
        if (slave != null) {
            RunToSlaveMapper.remove(r);
        }

        if (LimitedTestRunCount > 0) {
            if (NumberOfLimitedTestRuns >= LimitedTestRunCount) {
                ret = false;
                NumberOfLimitedTestRuns = 0;
                try {
                    if (slave != null) {
                        vSphereCloud.Log(
                                this,
                                "Disconnecting the slave agent on %s due to limited build threshold",
                                slave.getName());

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
                    vSphereCloud.Log(
                            this, ex, "InterruptedException thrown while marking the slave as online or offline");
                }
            }
        } else {
            ret = true;
        }
        return ret;
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
        protected void onUpdated(@NonNull Node oldOne, @NonNull Node newOne) {
            if (!(oldOne instanceof vSphereCloudSlave) || !(newOne instanceof vSphereCloudSlave)) {
                return;
            }
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
