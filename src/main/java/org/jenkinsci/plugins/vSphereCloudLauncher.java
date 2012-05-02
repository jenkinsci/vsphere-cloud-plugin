/*
 * To change this template, choose Tools | Templates
 * and open the template in the editor.
 */
package org.jenkinsci.plugins;

import com.vmware.vim25.VirtualMachinePowerState;
import com.vmware.vim25.VirtualMachineToolsStatus;
import com.vmware.vim25.mo.Folder;
import com.vmware.vim25.mo.InventoryNavigator;
import com.vmware.vim25.mo.ServiceInstance;
import com.vmware.vim25.mo.Task;
import com.vmware.vim25.mo.VirtualMachine;
import com.vmware.vim25.mo.VirtualMachineSnapshot;
import hudson.Util;
import hudson.slaves.ComputerLauncher;
import hudson.slaves.SlaveComputer;
import hudson.model.TaskListener;
import hudson.model.Descriptor;
import hudson.model.Hudson;
import hudson.slaves.Cloud;

import hudson.slaves.NodeProperty;
import java.io.IOException;
import java.util.Calendar;
import java.util.List;
import java.util.logging.Level;
import java.util.logging.Logger;

import org.kohsuke.stapler.DataBoundConstructor;

/**
 *
 * @author Admin
 */
public class vSphereCloudLauncher extends ComputerLauncher {

    private static final Logger LOGGER = Logger.getLogger(vSphereCloudLauncher.class.getName());
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
        LOGGER.log(Level.SEVERE, "Could not find our vSphere Cloud instance!");
        throw new RuntimeException("Could not find our vSphere Cloud instance!");
    }

    @Override
    public void launch(SlaveComputer slaveComputer, TaskListener taskListener)
            throws IOException, InterruptedException {

        try {
            if (slaveComputer.isTemporarilyOffline()) {
               taskListener.getLogger().println("Not launching VM because it's not accepting tasks"); 
               return;
            }
            
            // Slaves that take a while to start up make get multiple launch
            // requests from Jenkins.  
            if (isStarting == Boolean.TRUE) {
                return;
            }
            
            vSphereCloud vsC = findOurVsInstance();
            SlaveComputer slaveBeingLaunched = vsC.getSlaveBeingLaunched();
            if ((slaveBeingLaunched != null) && (slaveBeingLaunched != slaveComputer)) {
                taskListener.getLogger().printf("Another vSphereCloud slave (%s) is being launched", slaveBeingLaunched.getName());
                return;
            }
                
            taskListener.getLogger().println("Starting Virtual Machine...");
            isStarting = Boolean.TRUE;
            try {
                vsC.setSlaveBeingLaunched(slaveComputer);
                ServiceInstance si = vsC.getSI();
                Folder rootFolder = si.getRootFolder();
                VirtualMachine vm = (VirtualMachine) new InventoryNavigator(
                        rootFolder).searchManagedEntity("VirtualMachine", vmName);
                if (vm == null) {
                    throw new IOException("Virtual Machine could not be found");
                }

                // Revert to a snapshot - always - if one is specified.
                if (!snapName.isEmpty()) {
                    VirtualMachineSnapshot snap = vsC.getSnapshotInTree(vm, snapName);
                    if (snap == null) {
                        throw new IOException("Virtual Machine snapshot cannot be found");
                    }

                    taskListener.getLogger().println("Reverting to snapshot:" + snapName);
                    Task task = snap.revertToSnapshot_Task(null);
                    if (!task.waitForTask().equals(Task.SUCCESS)) {
                        throw new IOException("Error while reverting to virtual machine snapshot");
                    }
                }

                switch (vm.getRuntime().powerState) {
                    case poweredOn:
                        // Nothing to do.
                        taskListener.getLogger().println("VM already powered on");
                        break;
                    case poweredOff:
                    case suspended:
                        // Power the VM up.
                        taskListener.getLogger().println("Powering on VM");
                        Task task = vm.powerOnVM_Task(null);
                        if (!task.waitForTask().equals(Task.SUCCESS)) {
                            throw new IOException("Unable to power on VM");
                        }
                        break;
                }

                if (waitForVMTools) {
                    taskListener.getLogger().println("Waiting for VMTools");

                    Calendar target = Calendar.getInstance();
                    target.add(Calendar.SECOND, 120);
                    while (Calendar.getInstance().before(target)) {
                        VirtualMachineToolsStatus status = vm.getGuest().toolsStatus;
                        if ((status == VirtualMachineToolsStatus.toolsOk) || (status == VirtualMachineToolsStatus.toolsOld)) {
                            taskListener.getLogger().println("VM Tools are running");
                            break;
                        }
                        Thread.sleep(5000);
                    }
                    taskListener.getLogger().println("Finished wait for VMTools");
                }

                /* At this point we have told vSphere to get the VM going.
                 * Now we wait our launch delay amount before trying to connect. */
                Thread.sleep(launchDelay * 1000);
                delegate.launch(slaveComputer, taskListener);
            } catch (Exception e) {
                vsC.markVMOffline(slaveComputer.getDisplayName(), vmName);
                throw new RuntimeException(e);
            } finally {
                vsC.setSlaveBeingLaunched(null);
                isStarting = Boolean.FALSE;
            }
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    @Override
    public synchronized void afterDisconnect(SlaveComputer slaveComputer,
            TaskListener taskListener) {
        if (isDisconnecting == Boolean.TRUE) {
            taskListener.getLogger().println("Already disconnecting on a separate thread");
            return;
        }
        
        if (slaveComputer.isTemporarilyOffline()) {
           taskListener.getLogger().println("Not disconnecting VM because it's not accepting tasks"); 
           return;
        }
            
        try {
            isDisconnecting = Boolean.TRUE;
            taskListener.getLogger().println("Running disconnect procedure...");
            delegate.afterDisconnect(slaveComputer, taskListener);
            taskListener.getLogger().println("Shutting down Virtual Machine...");
            
            MACHINE_ACTION localIdle = idleAction;
            if (localIdle == null)
                localIdle = MACHINE_ACTION.SHUTDOWN;

            vSphereCloud vsC = findOurVsInstance();
            vsC.markVMOffline(slaveComputer.getDisplayName(), vmName);

            ServiceInstance si = vsC.getSI();
            Folder rootFolder = si.getRootFolder();
            VirtualMachine vm = (VirtualMachine) new InventoryNavigator(
                    rootFolder).searchManagedEntity("VirtualMachine", vmName);
            
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
                                taskListener.getLogger().println("Attempting a graceful shutdown");
                                vm.shutdownGuest();
                                Calendar target = Calendar.getInstance();
                                target.add(Calendar.MINUTE, 3);
                                while (Calendar.getInstance().before(target)) {
                                    if (vm.getRuntime().powerState == VirtualMachinePowerState.poweredOff) {
                                        taskListener.getLogger().println("Guest shutdown succeeded");
                                        break;
                                    }
                                    Thread.sleep(5000);
                                }
                            }
                            
                            // Still powered on or no tools?  Hard power down time.
                            if (vm.getRuntime().powerState == VirtualMachinePowerState.poweredOn) {
                                taskListener.getLogger().println("Powering down hard");
                                Task task = vm.powerOffVM_Task();
                                if (!task.waitForTask().equals(Task.SUCCESS)) {
                                    taskListener.getLogger().println("Unable to power down the VM");
                                }
                            }
                            break;
                        case SUSPEND:
                            taskListener.getLogger().println("Suspending the VM");
                            Task task = vm.suspendVM_Task();
                            if (!task.waitForTask().equals(Task.SUCCESS)) {
                                taskListener.getLogger().println("Unable to susped the VM");
                            }
                            break;                            
                        case RESET:
                                taskListener.getLogger().print("Resetting the VM");
                                Task taskReset = vm.resetVM_Task();
                                if (!taskReset.waitForTask().equals(Task.SUCCESS)) {
                                    taskListener.getLogger().print("Unable to reset the VM");
                                }
                            break;
                    }

                    if (localIdle == MACHINE_ACTION.REVERT) {
                        if (!snapName.isEmpty()) {
                            VirtualMachineSnapshot snap = vsC.getSnapshotInTree(vm, snapName);
                            if (snap == null) {
                                throw new IOException("Virtual Machine snapshot cannot be found");
                            }

                            taskListener.getLogger().println("Reverting to snapshot:" + snapName);
                            Task task = snap.revertToSnapshot_Task(null);
                            if (!task.waitForTask().equals(Task.SUCCESS)) {
                                throw new IOException("Error while reverting to virtual machine snapshot");
                            }
                        } else {
                            taskListener.getLogger().println("Reverting to current snapshot");
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
            taskListener.getLogger().println("Got an exception");
            taskListener.getLogger().print(t.toString());
            taskListener.getLogger().println("Printed exception");
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
            LOGGER.log(Level.FINE, "Launch support is overridden to always return: " + overrideLaunchSupported);
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
