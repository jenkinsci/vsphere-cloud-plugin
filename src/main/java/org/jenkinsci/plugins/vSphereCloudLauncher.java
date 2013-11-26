/*
 * To change this template, choose Tools | Templates
 * and open the template in the editor.
 */
package org.jenkinsci.plugins;

import hudson.Util;
import hudson.model.TaskListener;
import hudson.model.Descriptor;
import hudson.model.Hudson;
import hudson.slaves.Cloud;
import hudson.slaves.ComputerLauncher;
import hudson.slaves.SlaveComputer;

import java.io.IOException;
import java.util.Calendar;

import org.jenkinsci.plugins.vsphere.tools.VSphere;
import org.kohsuke.stapler.DataBoundConstructor;

import com.vmware.vim25.VirtualMachinePowerState;
import com.vmware.vim25.VirtualMachineToolsStatus;
import com.vmware.vim25.mo.Task;
import com.vmware.vim25.mo.VirtualMachine;
import com.vmware.vim25.mo.VirtualMachineSnapshot;

/**
 *
 * @author Admin
 */
public class vSphereCloudLauncher extends ComputerLauncher {

    private ComputerLauncher delegate;
    private Boolean overrideLaunchSupported;
    private String vsDescription;
    private String vmName;
    private Boolean waitForVMTools;
    private String snapName;
    private Boolean isStarting = Boolean.FALSE;
    private Boolean isDisconnecting;
    private int launchDelay;
    private MACHINE_ACTION idleAction;
    private int LimitedTestRunCount = 0;

    public enum MACHINE_ACTION {

        SHUTDOWN,
        REVERT,
        SUSPEND,
        RESET,
        NOTHING
    }

    @DataBoundConstructor
    public vSphereCloudLauncher(ComputerLauncher delegate,
            String vsDescription, String vmName,
            Boolean overrideLaunchSupported, Boolean waitForVMTools,
            String snapName, String launchDelay, String idleOption,
            String LimitedTestRunCount) {
        super();
        this.delegate = delegate;
        this.overrideLaunchSupported = overrideLaunchSupported;
        this.vsDescription = vsDescription;
        this.vmName = vmName;
        this.waitForVMTools = waitForVMTools;
        this.snapName = snapName;
        this.isStarting = Boolean.FALSE;
        this.launchDelay = Util.tryParseNumber(launchDelay, 60).intValue();
        if ("Shutdown".equals(idleOption)) {
            idleAction = MACHINE_ACTION.SHUTDOWN;
        } else if ("Shutdown and Revert".equals(idleOption)) {
            idleAction = MACHINE_ACTION.REVERT;
        } else if ("Reset".equals(idleOption)) {
            idleAction = MACHINE_ACTION.RESET;            
        } else if ("Suspend".equals(idleOption)) {
            idleAction = MACHINE_ACTION.SUSPEND;
        } else {
            idleAction = MACHINE_ACTION.NOTHING;
        }
        this.LimitedTestRunCount = Util.tryParseNumber(LimitedTestRunCount, 0).intValue();
    }

    public vSphereCloud findOurVsInstance() throws RuntimeException {
        if (vsDescription != null && vmName != null) {
            vSphereCloud vs = null;
            for (Cloud cloud : Hudson.getInstance().clouds) {
                if (cloud instanceof vSphereCloud && ((vSphereCloud) cloud).getVsDescription().equals(vsDescription)) {
                    vs = (vSphereCloud) cloud;
                    return vs;
                }
            }
        }
        vSphereCloud.Log("Could not find our vSphere Cloud instance!");
        throw new RuntimeException("Could not find our vSphere Cloud instance!");
    }

    @Override
    public void launch(SlaveComputer slaveComputer, TaskListener taskListener)
            throws IOException, InterruptedException {
        
        vSphereCloudSlave vsSlave = (vSphereCloudSlave)slaveComputer.getNode();
        //synchronized(vSphereCloud.class)
        {
            try {
                if (slaveComputer.isTemporarilyOffline()) {
                    vSphereCloud.Log(slaveComputer, taskListener, "Not launching VM because it's not accepting tasks; temporarily offline"); 
                   return;
                }

                // Slaves that take a while to start up make get multiple launch
                // requests from Jenkins.  
                if (isStarting == Boolean.TRUE) {
                    return;
                }

                // Not the most efficient way, but only allow one VM
                // to startup at at time. Prevents multiple launches for
                // the same job.
                vSphereCloudSlave.ProbableLaunchCleanup();
                //if (vSphereCloudSlave.ProbableLaunchCount() > 0) {
                //    vSphereCloud.Log(slaveComputer, taskListener, "Aborting this slave start since another slave is being started");
                //    return;
                //}
                    
                vSphereCloud vsC = findOurVsInstance();
                isStarting = Boolean.TRUE;
                try {
                    vSphereCloud.Log(slaveComputer, taskListener, "Starting Virtual Machine...");
                    
                    Calendar cal = Calendar.getInstance();
                    cal.add(Calendar.MINUTE, 5);
                    vSphereCloudSlave.AddProbableLaunch(vsSlave, cal.getTime());
                    
                    VSphere v = vsC.vSphereInstance();
                    VirtualMachine vm = v.getVmByName(vmName);
                    if (vm == null) {
                        throw new IOException("Virtual Machine could not be found");
                    }

                    // Revert to a snapshot - always - if one is specified.
                    if (!snapName.isEmpty()) {
                        VirtualMachineSnapshot snap = vsC.vSphereInstance().getSnapshotInTree(vm, snapName);
                        if (snap == null) {
                            throw new IOException("Virtual Machine snapshot cannot be found");
                        }

                        vSphereCloud.Log(slaveComputer, taskListener, "Reverting to snapshot:" + snapName);
                        Task task = snap.revertToSnapshot_Task(null);
                        if (!task.waitForTask().equals(Task.SUCCESS)) {
                            throw new IOException("Error while reverting to virtual machine snapshot");
                        }
                    }

                    switch (vm.getRuntime().powerState) {
                        case poweredOn:
                            // Nothing to do.
                            vSphereCloud.Log(slaveComputer, taskListener, "VM already powered on");
                            break;
                        case poweredOff:
                        case suspended:
                            // Power the VM up.
                            vSphereCloud.Log(slaveComputer, taskListener, "Powering on VM");
                            v.startVm(vmName);
                            break;
                    }

                    if (waitForVMTools) {
                        vSphereCloud.Log(slaveComputer, taskListener, "Waiting for VMTools");

                        Calendar target = Calendar.getInstance();
                        target.add(Calendar.SECOND, 120);
                        while (Calendar.getInstance().before(target)) {
                            VirtualMachineToolsStatus status = vm.getGuest().toolsStatus;
                            if ((status == VirtualMachineToolsStatus.toolsOk) || (status == VirtualMachineToolsStatus.toolsOld)) {
                                vSphereCloud.Log(slaveComputer, taskListener, "VM Tools are running");
                                break;
                            }
                            Thread.sleep(5000);
                        }
                        vSphereCloud.Log(slaveComputer, taskListener, "Finished wait for VMTools");
                    }

                    /* At this point we have told vSphere to get the VM going.
                     * Now we wait our launch delay amount before trying to connect.
                     */
                    
                    if (delegate.isLaunchSupported()) {
                        // Delegate is going to do launch.
                        Thread.sleep(launchDelay * 1000);
                        delegate.launch(slaveComputer, taskListener);
                    }
                    else {
                        for (int i = 0; i <= launchDelay; i++) {
                            Thread.sleep(1000);
                            if (slaveComputer.isOnline()) {
                                break;
                            }
                        }
                        if (!slaveComputer.isOnline()) {
                            vSphereCloud.Log(slaveComputer, taskListener, "Slave did not come online in allowed time");
                            throw new IOException("Slave did not come online in allowed time");
                        }
                    }
                } catch (Exception e) {
                    vsC.markVMOffline(slaveComputer.getDisplayName(), vmName);
                    throw new RuntimeException(e);
                } finally {
                    vSphereCloudSlave.RemoveProbableLaunch(vsSlave);
                    isStarting = Boolean.FALSE;                    
                }
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        }
    }

    @Override
    public synchronized void afterDisconnect(SlaveComputer slaveComputer,
            TaskListener taskListener) {
        if (isStarting == Boolean.TRUE) {
            vSphereCloud.Log(slaveComputer, taskListener, "Ignoring disconnect attempt because a connect attempt is in progress.");
            return;
        }
        
        if (isDisconnecting == Boolean.TRUE) {
            vSphereCloud.Log(slaveComputer, taskListener, "Already disconnecting on a separate thread");
            return;
        }
        
        if (slaveComputer.isTemporarilyOffline()) {
           vSphereCloud.Log(slaveComputer, taskListener, "Not disconnecting VM because it's not accepting tasks"); 
           return;
        }
            
        try {
            isDisconnecting = Boolean.TRUE;
            vSphereCloud.Log(slaveComputer, taskListener, "Running disconnect procedure...");
            delegate.afterDisconnect(slaveComputer, taskListener);
            vSphereCloud.Log(slaveComputer, taskListener, "Shutting down Virtual Machine...");
            
            MACHINE_ACTION localIdle = idleAction;
            if (localIdle == null)
                localIdle = MACHINE_ACTION.SHUTDOWN;

            vSphereCloud vsC = findOurVsInstance();
            vsC.markVMOffline(slaveComputer.getDisplayName(), vmName);

            VirtualMachine vm = vsC.vSphereInstance().getVmByName(vmName);
            
            if ((vm != null) && (localIdle != MACHINE_ACTION.NOTHING)) {
                //VirtualMachinePowerState power = vm.getRuntime().getPowerState();
                VirtualMachinePowerState power = vm.getSummary().getRuntime().powerState;
                if (power == VirtualMachinePowerState.poweredOn) {
                    switch (localIdle) {
                        case SHUTDOWN:
                        case REVERT:
                            // If reverting to shutting down, attempt to shutdown
                            // gracefully first, then hard.
                            VirtualMachineToolsStatus status = vm.getGuest().toolsStatus;
                            if ((status == VirtualMachineToolsStatus.toolsOk) || (status == VirtualMachineToolsStatus.toolsOld)) {
                                vSphereCloud.Log(slaveComputer, taskListener, "Attempting a graceful shutdown");
                                vm.shutdownGuest();
                                Calendar target = Calendar.getInstance();
                                target.add(Calendar.MINUTE, 3);
                                while (Calendar.getInstance().before(target)) {
                                    if (vm.getRuntime().powerState == VirtualMachinePowerState.poweredOff) {
                                        vSphereCloud.Log(slaveComputer, taskListener, "Guest shutdown succeeded");
                                        break;
                                    }
                                    Thread.sleep(5000);
                                }
                            }
                            
                            // Still powered on or no tools?  Hard power down time.
                            if (vm.getRuntime().powerState == VirtualMachinePowerState.poweredOn) {
                                vSphereCloud.Log(slaveComputer, taskListener, "Powering down hard");
                                Task task = vm.powerOffVM_Task();
                                if (!task.waitForTask().equals(Task.SUCCESS)) {
                                    vSphereCloud.Log(slaveComputer, taskListener, "Unable to power down the VM");
                                }
                            }
                            break;
                        case SUSPEND:
                            vSphereCloud.Log(slaveComputer, taskListener, "Suspending the VM");
                            Task task = vm.suspendVM_Task();
                            if (!task.waitForTask().equals(Task.SUCCESS)) {
                                vSphereCloud.Log(slaveComputer, taskListener, "Unable to susped the VM");
                            }
                            break;                            
                        case RESET:
                                vSphereCloud.Log(slaveComputer, taskListener, "Resetting the VM");
                                Task taskReset = vm.resetVM_Task();
                                if (!taskReset.waitForTask().equals(Task.SUCCESS)) {
                                    vSphereCloud.Log(slaveComputer, taskListener, "Unable to reset the VM");
                                }
                            break;
                    }

                    if (localIdle == MACHINE_ACTION.REVERT) {
                        if (!snapName.isEmpty()) {
                            VirtualMachineSnapshot snap = vsC.vSphereInstance().getSnapshotInTree(vm, snapName);
                            if (snap == null) {
                                throw new IOException("Virtual Machine snapshot cannot be found");
                            }

                            vSphereCloud.Log(slaveComputer, taskListener, "Reverting to snapshot:" + snapName);
                            Task task = snap.revertToSnapshot_Task(null);
                            if (!task.waitForTask().equals(Task.SUCCESS)) {
                                throw new IOException("Error while reverting to virtual machine snapshot");
                            }
                        } else {
                            vSphereCloud.Log(slaveComputer, taskListener, "Reverting to current snapshot");
                            Task task = vm.revertToCurrentSnapshot_Task(null);
                            if (!task.waitForTask().equals(Task.SUCCESS)) {
                                throw new IOException("Error while reverting to virtual machine snapshot");
                            }
                        }
                    }
                }
                else
                {
                    // VM is already powered down.
                }
            }
        } catch (Throwable t) {
            vSphereCloud.Log(slaveComputer, taskListener, "Got an exception");
            vSphereCloud.Log(slaveComputer, taskListener, t.toString());
            vSphereCloud.Log(slaveComputer, taskListener, "Printed exception");
            taskListener.fatalError(t.getMessage(), t);
        } finally {
            isDisconnecting = Boolean.FALSE;
        }
    }

    public ComputerLauncher getDelegate() {
        return delegate;
    }

    public String getVmName() {
        return vmName;
    }

    public String getVsDescription() {
        return vsDescription;
    }

    public MACHINE_ACTION getIdleAction() {
        return idleAction;
    }

    public void setIdleAction(MACHINE_ACTION idleAction) {
        this.idleAction = idleAction;
    }

    public Boolean getOverrideLaunchSupported() {
        return overrideLaunchSupported;
    }

    public void setOverrideLaunchSupported(Boolean overrideLaunchSupported) {
        this.overrideLaunchSupported = overrideLaunchSupported;
    }

    public Boolean getWaitForVMTools() {
        return waitForVMTools;
    }
    
    public Integer getLimitedTestRunCount() {
        return LimitedTestRunCount;
    }
    
    @Override
    public boolean isLaunchSupported() {
        if (this.overrideLaunchSupported == null) {
            return delegate.isLaunchSupported();
        } else {            
            return overrideLaunchSupported;
        }
    }

    @Override
    public void beforeDisconnect(SlaveComputer slaveComputer, TaskListener taskListener) {
        delegate.beforeDisconnect(slaveComputer, taskListener);
    }

    @Override
    public Descriptor<ComputerLauncher> getDescriptor() {
        // Don't allow creation of launcher from UI
        throw new UnsupportedOperationException();
    }
}
