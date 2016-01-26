/*
 * To change this template, choose Tools | Templates
 * and open the template in the editor.
 */
package org.jenkinsci.plugins;
 
import hudson.AbortException;
import hudson.Extension;
import hudson.Functions;
import hudson.Util;
import hudson.model.Queue.BuildableItem;
import hudson.model.Result;
import hudson.model.TaskListener;
import hudson.model.Computer;
import hudson.model.Descriptor;
import hudson.model.Descriptor.FormException;
import hudson.model.Run;
import hudson.model.queue.CauseOfBlockage;
import hudson.slaves.*;
import hudson.util.FormValidation;

import java.io.IOException;

import org.kohsuke.stapler.DataBoundConstructor;
import org.kohsuke.stapler.QueryParameter;

import com.vmware.vim25.mo.VirtualMachine;
import com.vmware.vim25.mo.VirtualMachineSnapshot;
import hudson.model.Executor;
import hudson.model.Queue;
import java.util.ArrayList;
import java.util.Date;
import java.util.Iterator;
import java.util.List;
import java.util.Map.Entry;
import java.util.concurrent.ConcurrentHashMap;
import jenkins.model.Jenkins;

/**
 *
 * @author Admin
 */
public class vSphereCloudSlave extends AbstractCloudSlave {

    private final String vsDescription;
    private final String vmName;
    private final String snapName;
    private final Boolean waitForVMTools;
    private final String launchDelay;
    private final String idleOption;
    private Integer LimitedTestRunCount = 0; // If limited test runs enabled, the number of tests to limit the slave too.
    private transient Integer NumberOfLimitedTestRuns = 0;
    public transient Boolean doingLastInLimitedTestRun = Boolean.FALSE;

    // The list of slaves that MIGHT be launched.
    private static ConcurrentHashMap<vSphereCloudSlave, ProbableLaunchData> ProbableLaunch;
    private static final Boolean ProbableLaunchLock = true;

    public transient Boolean slaveIsStarting = Boolean.FALSE;
    public transient Boolean slaveIsDisconnecting = Boolean.FALSE;

    @DataBoundConstructor
    public vSphereCloudSlave(String name, String nodeDescription,
            String remoteFS, String numExecutors, Mode mode,
            String labelString, ComputerLauncher delegateLauncher,
            RetentionStrategy retentionStrategy,
            List<? extends NodeProperty<?>> nodeProperties,
            String vsDescription, String vmName,
            boolean launchSupportForced, boolean waitForVMTools,
            String snapName, String launchDelay, String idleOption,
            String LimitedTestRunCount)
            throws FormException, IOException {
        super(name, nodeDescription, remoteFS, numExecutors, mode, labelString,
              new vSphereCloudLauncher(delegateLauncher, vsDescription, vmName,
                  launchSupportForced, waitForVMTools, snapName, launchDelay,
                  idleOption, LimitedTestRunCount),
              retentionStrategy, nodeProperties);
        this.vsDescription = vsDescription;
        this.vmName = vmName;
        this.snapName = snapName;
        this.waitForVMTools = waitForVMTools;
        this.launchDelay = launchDelay;
        this.idleOption = idleOption;
        this.LimitedTestRunCount = Util.tryParseNumber(LimitedTestRunCount, 0).intValue();
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
        return ((vSphereCloudLauncher) getLauncher()).getOverrideLaunchSupported() == Boolean.TRUE;
    }

    @Override
    protected void _terminate(final TaskListener listener) throws IOException, InterruptedException {
        try {
            Computer computer = toComputer();
            if(computer != null) {
                computer.disconnect(new OfflineCause(){
                    @Override
                    public String toString() {
                        return "Shutting down VSphere Cloud Slave";
                    }
                });
                vSphereCloud.Log("Disconnected computer");                    
            }
        } catch(Exception e) {
            vSphereCloud.Log("Can't disconnect: \n" + e.getMessage());
        }
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
        synchronized(ProbableLaunchLock) {
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
            Iterator<Entry<vSphereCloudSlave, ProbableLaunchData>> it = ProbableLaunch.entrySet().iterator();
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
            Iterator<Entry<vSphereCloudSlave, ProbableLaunchData>> it = ProbableLaunch.entrySet().iterator();
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
        if(buildItem.task instanceof Queue.FlyweightTask) {
            return new CauseOfBlockage() {
                @Override
                public String getShortDescription() {
                    return "Don't run FlyweightTask on vSphere node.";
                }
            };
        }
        
        if(slaveIsStarting == Boolean.TRUE) {
            return new CauseOfBlockage.BecauseNodeIsBusy(this);
        }
        
        if (slaveIsDisconnecting == Boolean.TRUE) {
            return new CauseOfBlockage.BecauseNodeIsOffline(this);
        }
        
        return super.canTake(buildItem);
    }

    static final private ConcurrentHashMap<Run, Computer> RunToSlaveMapper = new ConcurrentHashMap<Run, Computer>();

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
                vSphereCloud.Log(listener, "Starting limited count build: %d", NumberOfLimitedTestRuns);
                Computer slave = executor.getOwner();
                RunToSlaveMapper.put(r, slave);
            } else {
                vSphereCloud.Log(listener, "Terminating build due to limited build count: %d", LimitedTestRunCount);
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
                        vSphereCloud.Log("Disconnecting the slave agent on %s due to limited build threshold", slave.getName());
                        
                        slave.setTemporarilyOffline(true, new OfflineCause.ByCLI("vSphere Plugin marking the slave as offline due to reaching limited build threshold"));
                        slave.waitUntilOffline();
                        slave.disconnect(new OfflineCause.ByCLI("vSphere Plugin disconnecting the slave as offline due to reaching limited build threshold"));
                        slave.setTemporarilyOffline(false, new OfflineCause.ByCLI("vSphere Plugin marking the slave as online after completing post-disconnect actions."));
                    }
                    else {
                        vSphereCloud.Log("Attempting to shutdown slave due to limited build threshold, but cannot determine slave");
                    }
                } catch (NullPointerException ex) {
                    vSphereCloud.Log("NullPointerException thrown while retrieving the slave agent: %s", ex.getMessage());
                } catch (InterruptedException ex) {
                    vSphereCloud.Log("InterruptedException thrown while marking the slave as online or offline: %s", ex.getMessage());
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
        return ((vSphereCloudLauncher) getLauncher()).getDelegate();
    }

    @Extension
    public static class vSphereCloudComputerListener extends ComputerListener {

        @Override
        public void preLaunch(Computer c, TaskListener taskListener) throws IOException, InterruptedException {
            /* We may be called on any slave type so check that we should
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

    @Extension
    public static final class DescriptorImpl extends SlaveDescriptor {

        public DescriptorImpl() {
            load();
        }

        @Override
        public String getDisplayName() {
            return "Slave virtual computer running under vSphere Cloud";
        }

        @Override
        public boolean isInstantiable() {
            return true;
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

        public vSphereCloud getSpecificvSphereCloud(String vsDescription)
                throws Exception {
            for (vSphereCloud vs : getvSphereClouds()) {
                if (vs.getVsDescription().equals(vsDescription)) {
                    return vs;
                }
            }
            throw new Exception("The vSphere Cloud doesn't exist");
        }

        public List<Descriptor<ComputerLauncher>> getComputerLauncherDescriptors() {
            List<Descriptor<ComputerLauncher>> result = new ArrayList<Descriptor<ComputerLauncher>>();
            for (Descriptor<ComputerLauncher> launcher : Functions.getComputerLauncherDescriptors()) {
                if (!vSphereCloudLauncher.class.isAssignableFrom(launcher.clazz)) {
                    result.add(launcher);
                }
            }
            return result;
        }

        public List<String> getIdleOptions() {
            List<String> options = new ArrayList<String>();
            options.add("Shutdown");
            options.add("Shutdown and Revert");
            options.add("Revert and Restart");
            options.add("Revert and Reset");
            options.add("Suspend");
            options.add("Reset");
            options.add("Nothing");
            return options;
        }

        public FormValidation doCheckLaunchDelay(@QueryParameter String value) {
            return FormValidation.validatePositiveInteger(value);
        }

        public FormValidation doTestConnection(@QueryParameter String vsDescription,
                @QueryParameter String vmName,
                @QueryParameter String snapName) {
            try {
                vSphereCloud vsC = getSpecificvSphereCloud(vsDescription);
                VirtualMachine vm = vsC.vSphereInstance().getVmByName(vmName);
                if (vm == null) {
                    return FormValidation.error("Virtual Machine was not found");
                }

                if (!snapName.isEmpty()) {
                    VirtualMachineSnapshot snap = vsC.vSphereInstance().getSnapshotInTree(vm, snapName);
                    if (snap == null) {
                        return FormValidation.error("Virtual Machine snapshot was not found");
                    }
                }

                return FormValidation.ok("Virtual Machine found successfully");
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        }
    }
}
