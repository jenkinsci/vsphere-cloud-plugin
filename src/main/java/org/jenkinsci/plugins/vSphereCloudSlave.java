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
import hudson.model.Hudson;
import hudson.model.Run;
import hudson.model.Slave;
import hudson.model.queue.CauseOfBlockage;
import hudson.slaves.Cloud;
import hudson.slaves.ComputerListener;
import hudson.slaves.NodeProperty;
import hudson.slaves.ComputerLauncher;
import hudson.slaves.RetentionStrategy;
import hudson.slaves.SlaveComputer;
import hudson.util.FormValidation;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Date;
import java.util.Hashtable;
import java.util.Iterator;
import java.util.List;
import java.util.Map.Entry;

import org.kohsuke.stapler.DataBoundConstructor;
import org.kohsuke.stapler.QueryParameter;

import com.vmware.vim25.mo.VirtualMachine;
import com.vmware.vim25.mo.VirtualMachineSnapshot;

/**
 *
 * @author Admin
 */
public class vSphereCloudSlave extends Slave {

    private final String vsDescription;
    private final String vmName;
    private final String snapName;
    private final Boolean waitForVMTools;
    private final String launchDelay;
    private final String idleOption;
    private Integer LimitedTestRunCount = 0; // If limited test runs enabled, the number of tests to limit the slave too.
    private transient Integer NumberOfLimitedTestRuns = 0;
    
    // The list of slaves that MIGHT be launched.
    private static Hashtable<vSphereCloudSlave, ProbableLaunchData> ProbableLaunch;
    private static final Boolean ProbableLaunchLock = true;

    public Boolean isStarting = Boolean.FALSE;
    public Boolean isDisconnecting = Boolean.FALSE;

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

    public void setLaunchSupportForced(boolean slaveLaunchesOnBootup) {
        ((vSphereCloudLauncher) getLauncher()).setOverrideLaunchSupported(slaveLaunchesOnBootup ? Boolean.TRUE : null);
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
        if (ProbableLaunch == null)
            ProbableLaunch = new Hashtable<vSphereCloudSlave, ProbableLaunchData>();
    }
    public static void AddProbableLaunch(vSphereCloudSlave slave, Date target) {
        synchronized (ProbableLaunchLock) {
            InitProbableLaunch();
            ProbableLaunch.put(slave, new ProbableLaunchData(slave, target));
        }
    }
    public static void RemoveProbableLaunch(vSphereCloudSlave slave) {
        synchronized (ProbableLaunchLock) {
            if (ProbableLaunch != null)
                ProbableLaunch.remove(slave);
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
              if (entry.getValue().expiration.before(now))
                  it.remove();
            }                    
        }
    }
    public static int ProbableLaunchCount() {
        synchronized (ProbableLaunchLock) {
            if (ProbableLaunch != null)
                return ProbableLaunch.size();
            return 0;
        }
    }
    public static vSphereCloudSlave ProbablyLaunchCanHandle(BuildableItem item) {
        synchronized (ProbableLaunchLock) {
            InitProbableLaunch();
            Iterator<Entry<vSphereCloudSlave, ProbableLaunchData>> it = ProbableLaunch.entrySet().iterator();
            while (it.hasNext()) {
                ProbableLaunchData data = it.next().getValue();
                if (data.slave.canTake(item) == null)
                    return data.slave;
            }
        }
        return null;
    }

    @Override
    public Computer createComputer() {
        return new vSphereCloudSlaveComputer(this);
    }
    
    @Override
    public CauseOfBlockage canTake(BuildableItem item) {
        CauseOfBlockage b = super.canTake(item);
        if (b == null) {
            return b;
            
            // Normal slave handling says that this item can be handled.
            // Now see if it can be handled in regards to a VM.
            //SlaveComputer sc = getComputer();
            
            // If the VM is offline, check to see if the VM should be 
            // launched.  The problem is that we don't want one build
            // to cause a more than one slave to be launched.
            //if (sc.isOffline() && sc.isLaunchSupported() && !sc.isConnecting()) {
                // Get the build name - for diags.
                //String buildName = "NA";
                //if ((item != null) && (item.task != null)) {
                //    buildName = item.getDisplayName();
                //}
                
                // Clean up any possible out-of-date probable launches
                //ProbableLaunchCleanup();
                
                // See if another probable launch is in play. 
                //vSphereCloudSlave launchingSlave = ProbablyLaunchCanHandle(item);
                
                //if (launchingSlave == this) {
                    // The possibly launching slave is ourself.
                    //return null;
                //} else if (launchingSlave != null) {
                    // A slave is launching that COULD handle this job. Defer
                    // to that slave.
                    //return CauseOfBlockage.fromMessage(Messages._Slave_UnableToLaunch(getNodeName(), 
                    //        String.format("Another potential slave (%s) is launching that should handle %s", launchingSlave.getNodeName(), buildName)) );
                //} else {
                    // Guess this slave can handle it. 
                    //return null;
                //}
            //} 
        }            
        return b;
    }
    
    
    
    private void CheckLimitedTestRunValues() {
        if (NumberOfLimitedTestRuns == null)
            NumberOfLimitedTestRuns = 0;
        if (LimitedTestRunCount == null)
            LimitedTestRunCount = 0;
    }          
    public boolean StartLimitedTestRun(Run r, TaskListener listener) {
        boolean ret = false;
        boolean DoUpdates = false;
        CheckLimitedTestRunValues();
        if (LimitedTestRunCount > 0) {
            DoUpdates = true;
            if (NumberOfLimitedTestRuns < LimitedTestRunCount) {
                ret = true;
            }
        }
        else
            ret = true;
        
        if (DoUpdates) {
            if (ret) {
                NumberOfLimitedTestRuns++;
                vSphereCloud.Log(listener, "Starting limited count build: %d", NumberOfLimitedTestRuns);
            }
            else {
                vSphereCloud.Log(listener, "Terminating build due to limited build count: %d", LimitedTestRunCount);
                r.getExecutor().interrupt(Result.ABORTED);
            }
        }
        
        return ret;
    }

    public boolean EndLimitedTestRun(Run r) {
        boolean ret = true;
        CheckLimitedTestRunValues();
        if (LimitedTestRunCount > 0) {
            if (NumberOfLimitedTestRuns >= LimitedTestRunCount) {
                ret = false;
                NumberOfLimitedTestRuns = 0;   
                r.getExecutor().getOwner().disconnect();
                String Node = "NA";
                if ((r.getExecutor() != null) && (r.getExecutor().getOwner() != null)) {
                    Node = r.getExecutor().getOwner().getName();
                }
                vSphereCloud.Log("Disconnecting the slave agent on %s due to limited build threshold", Node);
            }            
        }
        else
            ret = true;
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
            if (!vsC.markVMOnline(c.getDisplayName(), vsL.getVmName()))
                throw new AbortException("The vSphere cloud will not allow this slave to start at this time.");
        }               
    }

    @Extension
    public static final class DescriptorImpl extends SlaveDescriptor {

        public DescriptorImpl() {
            load();
        }

        public String getDisplayName() {
            return "Slave virtual computer running under vSphere Cloud";
        }

        @Override
        public boolean isInstantiable() {
            return true;
        }

        public List<vSphereCloud> getvSphereClouds() {
            List<vSphereCloud> result = new ArrayList<vSphereCloud>();
            for (Cloud cloud : Hudson.getInstance().clouds) {
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
                    if (snap == null)
                        return FormValidation.error("Virtual Machine snapshot was not found");
                }

                return FormValidation.ok("Virtual Machine found successfully");
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        }
    }
}
