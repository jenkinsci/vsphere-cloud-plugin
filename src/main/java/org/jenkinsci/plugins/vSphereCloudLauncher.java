/*
 * To change this template, choose Tools | Templates
 * and open the template in the editor.
 */
package org.jenkinsci.plugins;

import hudson.Util;
import hudson.model.TaskListener;
import hudson.model.Descriptor;
import hudson.slaves.ComputerLauncher;
import hudson.slaves.DelegatingComputerLauncher;
import hudson.slaves.SlaveComputer;

import java.io.IOException;
import java.io.ObjectStreamException;
import java.util.Calendar;

import org.jenkinsci.plugins.vsphere.tools.VSphere;
import org.kohsuke.stapler.DataBoundConstructor;

import com.vmware.vim25.VirtualMachinePowerState;
import com.vmware.vim25.VirtualMachineToolsStatus;
import com.vmware.vim25.mo.Task;
import com.vmware.vim25.mo.VirtualMachine;
import com.vmware.vim25.mo.VirtualMachineSnapshot;
import java.rmi.RemoteException;
import org.jenkinsci.plugins.vsphere.tools.VSphereException;

/**
 *
 * @author Admin
 */
public class vSphereCloudLauncher extends DelegatingComputerLauncher {

    @Deprecated
    private transient ComputerLauncher delegate;
    private final Boolean overrideLaunchSupported;
    private final String vsDescription;
    private final String vmName;
    private final Boolean waitForVMTools;
    private final String snapName;
    private final int launchDelay;
    private final MACHINE_ACTION idleAction;
    private final int LimitedTestRunCount;

    public enum MACHINE_ACTION {
        SHUTDOWN,
        REVERT,
        REVERT_AND_RESET,
        REVERT_AND_RESTART,
        SUSPEND,
        RESET,
        NOTHING
    }

    @DataBoundConstructor
    public vSphereCloudLauncher(ComputerLauncher launcher,
            String vsDescription, String vmName,
            Boolean overrideLaunchSupported, Boolean waitForVMTools,
            String snapName, String launchDelay, String idleOption,
            String LimitedTestRunCount) {
        super(launcher);
        this.overrideLaunchSupported = overrideLaunchSupported;
        this.vsDescription = vsDescription;
        this.vmName = vmName;
        this.waitForVMTools = waitForVMTools;
        this.snapName = snapName;
        this.launchDelay = Util.tryParseNumber(launchDelay, 60).intValue();
        if ("Shutdown".equals(idleOption)) {
            idleAction = MACHINE_ACTION.SHUTDOWN;
        } else if ("Shutdown and Revert".equals(idleOption)) {
            idleAction = MACHINE_ACTION.REVERT;
        } else if ("Revert and Restart".equals(idleOption)) {
            idleAction = MACHINE_ACTION.REVERT_AND_RESTART;
        } else if ("Revert and Reset".equals(idleOption)) {
            idleAction = MACHINE_ACTION.REVERT_AND_RESET;
        } else if ("Reset".equals(idleOption)) {
            idleAction = MACHINE_ACTION.RESET;
        } else if ("Suspend".equals(idleOption)) {
            idleAction = MACHINE_ACTION.SUSPEND;
        } else {
            idleAction = MACHINE_ACTION.NOTHING;
        }
        this.LimitedTestRunCount = Util.tryParseNumber(LimitedTestRunCount, 0).intValue();
    }

    private vSphereCloudLauncher(ComputerLauncher launcher, Boolean overrideLaunchSupported, String vsDescription,
                                String vmName, Boolean waitForVMTools, String snapName, int launchDelay,
                                MACHINE_ACTION idleAction, int limitedTestRunCount) {
        super(launcher);
        this.overrideLaunchSupported = overrideLaunchSupported;
        this.vsDescription = vsDescription;
        this.vmName = vmName;
        this.waitForVMTools = waitForVMTools;
        this.snapName = snapName;
        this.launchDelay = launchDelay;
        this.idleAction = idleAction;
        LimitedTestRunCount = limitedTestRunCount;
    }

    /**
     * Migrates instances from the old parent class to the new parent class.
     * @return the deserialized instance.
     * @throws ObjectStreamException if something went wrong.
     */
    private Object readResolve() throws ObjectStreamException {
        if (delegate != null) {
            return new vSphereCloudLauncher(delegate, overrideLaunchSupported, vsDescription, vmName, waitForVMTools,
                    snapName, launchDelay, idleAction, LimitedTestRunCount);
        }
        return this;
    }

    /**
     * Find the {@link vSphereCloud} for this {@link vSphereCloudLauncher}, or
     * dies trying.
     * 
     * @return The {@link vSphereCloud}. It will not return null.
     * @throws RuntimeException
     *             if it cannot find the {@link vSphereCloud} - e.g. if it's
     *             been deleted or the description has changed.
     */
    public vSphereCloud findOurVsInstance() throws RuntimeException {
        if (vsDescription != null && vmName != null) {
            for (vSphereCloud cloud : vSphereCloud.findAllVsphereClouds(null)) {
                if (cloud.getVsDescription().equals(vsDescription)) {
                    return cloud;
                }
            }
        }
        vSphereCloud.Log("Could not find our vSphere Cloud instance!");
        throw new RuntimeException("Could not find our vSphere Cloud instance!");
    }

    @Override
    public void launch(SlaveComputer slaveComputer, TaskListener taskListener)
            throws IOException, InterruptedException {

        vSphereCloudSlave vsSlave = (vSphereCloudSlave) slaveComputer.getNode();

        //synchronized(vSphereCloud.class)
        {
            try {

                if (slaveComputer.isTemporarilyOffline()) {
                    vSphereCloud.Log(slaveComputer, taskListener, "Not launching VM because it's not accepting tasks; temporarily offline");
                    return;
                }

                // Slaves that take a while to start up make get multiple launch
                // requests from Jenkins.
                if (vsSlave.slaveIsStarting == Boolean.TRUE) {
                    vSphereCloud.Log(slaveComputer, taskListener, "Ignoring additional attempt to start the slave; it's already being started");
                    return;
                }

                // If a slave is disconnecting, don't try to start it up
                if (vsSlave.slaveIsDisconnecting == Boolean.TRUE) {
                    vSphereCloud.Log(slaveComputer, taskListener, "Ignoring connect attempt to start the slave; it's being shutdown");
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
                vsSlave.slaveIsStarting = Boolean.TRUE;
                VSphere v = null;
                try {
                    vSphereCloud.Log(slaveComputer, taskListener, "Starting Virtual Machine...");

                    Calendar cal = Calendar.getInstance();
                    cal.add(Calendar.MINUTE, 5);
                    vSphereCloudSlave.AddProbableLaunch(vsSlave, cal.getTime());

                    v = vsC.vSphereInstance();
                    VirtualMachine vm = v.getVmByName(vmName);
                    if (vm == null) {
                        throw new IOException("Virtual Machine '" + vmName + "' could not be found");
                    }

                    // Revert to a snapshot - always - if one is specified.
                    if (!snapName.isEmpty()) {
                        VirtualMachineSnapshot snap = v.getSnapshotInTree(vm, snapName);
                        if (snap == null) {
                            throw new IOException("Virtual Machine '" + vmName + "' snapshot '" + snapName + "' cannot be found");
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
                            v.startVm(vmName, 60);
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
                    if (launcher.isLaunchSupported()) {
                        if (launchDelay > 0) {
                            vSphereCloud.Log(slaveComputer, taskListener, "Waiting for " + launchDelay
                                    + " seconds before asking " + launcher + " to launch slave.");
                            // Delegate is going to do launch.
                            Thread.sleep(launchDelay * 1000);
                        }
                        vSphereCloud.Log(slaveComputer, taskListener, "Asking " + launcher.getClass().getSimpleName() + " to launch slave.");
                        super.launch(slaveComputer, taskListener);
                    } else {
                        vSphereCloud.Log(slaveComputer, taskListener, "Waiting for up to " + launchDelay
                                + " seconds for slave to come online.");
                        for (int i = 0; i <= launchDelay; i++) {
                            Thread.sleep(1000);
                            if (slaveComputer.isOnline()) {
                                vSphereCloud.Log(slaveComputer, taskListener, "Slave has come online.");
                                break;
                            }
                        }
                        if (!slaveComputer.isOnline()) {
                            vSphereCloud.Log(slaveComputer, taskListener, "Slave did not come online in allowed time");
                            throw new IOException("Slave did not come online in allowed time");
                        }
                    }
                    vSphereCloud.Log(slaveComputer, taskListener, "Slave online");
                } catch (final Exception e) {
                    vSphereCloud.Log(slaveComputer, taskListener, e, "EXCEPTION while starting VM");
                    vsC.markVMOffline(slaveComputer.getDisplayName(), vmName);
                    throw new RuntimeException(e);
                } finally {
                    vSphereCloudSlave.RemoveProbableLaunch(vsSlave);
                    vsSlave.slaveIsStarting = Boolean.FALSE;
                    if (v != null)
                        v.disconnect();
                }
            } catch (final RuntimeException e) {
                throw e;
            } catch (final Exception e) {
                throw new RuntimeException(e);
            }
        }
    }

    @Override
    public synchronized void afterDisconnect(SlaveComputer slaveComputer, TaskListener taskListener) {
        final vSphereCloudSlave vsSlave = (vSphereCloudSlave) slaveComputer.getNode();

        if(vsSlave == null) {
            vSphereCloud.Log(slaveComputer, taskListener, "Slave is null.");
            return;
        }
        if (vsSlave.slaveIsStarting == Boolean.TRUE) {
            vSphereCloud.Log(slaveComputer, taskListener, "Ignoring disconnect attempt because a connect attempt is in progress.");
            return;
        }
        if (vsSlave.slaveIsDisconnecting == Boolean.TRUE) {
            vSphereCloud.Log(slaveComputer, taskListener, "Already disconnecting on a separate thread");
            return;
        }
        if (slaveComputer.isTemporarilyOffline()) {
            if (!slaveComputer.getOfflineCauseReason().contains("vSphere Plugin")) {
                vSphereCloud.Log(slaveComputer, taskListener, "Not disconnecting VM because it's not accepting tasks");
                return;
            }
        }

        vsSlave.slaveIsDisconnecting = Boolean.TRUE;
        VSphere v = null;
        try {
            vSphereCloud.Log(slaveComputer, taskListener, "Running disconnect procedure...");
            super.afterDisconnect(slaveComputer, taskListener);
            vSphereCloud.Log(slaveComputer, taskListener, "Shutting down Virtual Machine...");
            MACHINE_ACTION localIdle = idleAction;
            if (localIdle == null) {
                localIdle = MACHINE_ACTION.SHUTDOWN;
            }
            vSphereCloud vsC = findOurVsInstance();
            vsC.markVMOffline(slaveComputer.getDisplayName(), vmName);
            v = vsC.vSphereInstance();
            VirtualMachine vm = v.getVmByName(vmName);
            if (vm != null && !MACHINE_ACTION.NOTHING.equals(localIdle)) {
                //VirtualMachinePowerState power = vm.getRuntime().getPowerState();
                VirtualMachinePowerState power = vm.getSummary().getRuntime().powerState;
                if (power == VirtualMachinePowerState.poweredOn) {
                    switch (localIdle) {
                        case SHUTDOWN:
                        case REVERT:
                        case REVERT_AND_RESET:
                        case REVERT_AND_RESTART:
                            shutdownVM(vm, slaveComputer, taskListener);
                            break;
                        case SUSPEND:
                            suspendVM(vm, slaveComputer, taskListener);
                            break;
                        case RESET:
                            resetVM(vm, slaveComputer, taskListener);
                            break;
                        case NOTHING:
                            break;
                    }
                    if (localIdle == MACHINE_ACTION.REVERT) {
                        revertVM(vm, vsC, slaveComputer, taskListener);
                    } else if (localIdle == MACHINE_ACTION.REVERT_AND_RESTART) {
                        revertVM(vm, vsC, slaveComputer, taskListener);
                        if (power == VirtualMachinePowerState.poweredOn) {
                             // Some time is needed for the VMWare Tools to reactivate
                            Thread.sleep(60000);
                            shutdownVM(vm, slaveComputer, taskListener);
                        }
                        powerOnVM(vm, slaveComputer, taskListener);
                    } else if (localIdle == MACHINE_ACTION.REVERT_AND_RESET) {
                        revertVM(vm, vsC, slaveComputer, taskListener);
                        resetVM(vm, slaveComputer, taskListener);
                    }
                } else {
                        // VM is already powered down.
                }
            }
            if (v != null) {
                v.disconnect();
            }
        } catch (Throwable t) {
            vSphereCloud.Log(slaveComputer, taskListener, t, "Got an exception");
            taskListener.fatalError(t.getMessage(), t);
        } finally {
            vsSlave.slaveIsDisconnecting = Boolean.FALSE;
            vsSlave.slaveIsStarting = Boolean.FALSE;
        }
    }

    /**
     * @return actual launcher
     * @deprecated use {@link #getLauncher()}
     */
    @Deprecated
    public ComputerLauncher getDelegate() {
        return launcher;
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

    public Boolean getOverrideLaunchSupported() {
        return overrideLaunchSupported;
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
            return launcher.isLaunchSupported();
        } else {
            return overrideLaunchSupported;
        }
    }

    @Override
    public Descriptor<ComputerLauncher> getDescriptor() {
        // Don't allow creation of launcher from UI
        throw new UnsupportedOperationException();
    }

    private void powerOnVM(VirtualMachine vm, SlaveComputer slaveComputer, TaskListener taskListener)
            throws RemoteException, InterruptedException {
        vSphereCloud.Log(slaveComputer, taskListener, "Powering on the VM");
        Task taskPowerOn = vm.powerOnVM_Task(null);
        if (!taskPowerOn.waitForTask().equals(Task.SUCCESS)) {
            vSphereCloud.Log(slaveComputer, taskListener, "Unable to power on the VM");
        }
    }

    private void shutdownVM(VirtualMachine vm, SlaveComputer slaveComputer, TaskListener taskListener)
            throws RemoteException, InterruptedException {

        // If reverting to shutting down, attempt to shutdown
        // gracefully first, then hard.
        VirtualMachineToolsStatus status = vm.getGuest().toolsStatus;
        if ((status == VirtualMachineToolsStatus.toolsOk) || (status == VirtualMachineToolsStatus.toolsOld)) {
            try {
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
            } catch (Throwable t) {
                vSphereCloud.Log(slaveComputer, taskListener, t,
                        "Got an exception while attempting a graceful shutdown");
                vSphereCloud.Log(slaveComputer, taskListener, "Will now attempt a hard power down");
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
    }

    private void revertVM(VirtualMachine vm, vSphereCloud vsC, SlaveComputer slaveComputer,
                          TaskListener taskListener)
            throws IOException, InterruptedException, VSphereException {
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

    private void resetVM(VirtualMachine vm, SlaveComputer slaveComputer, TaskListener taskListener) throws RemoteException, InterruptedException {
        vSphereCloud.Log(slaveComputer, taskListener, "Resetting the VM");
        Task taskReset = vm.resetVM_Task();
        if (!taskReset.waitForTask().equals(Task.SUCCESS)) {
            vSphereCloud.Log(slaveComputer, taskListener, "Unable to reset the VM");
        }
    }

    private void suspendVM(VirtualMachine vm, SlaveComputer slaveComputer, TaskListener taskListener) throws RemoteException, InterruptedException {
        vSphereCloud.Log(slaveComputer, taskListener, "Suspending the VM");
        Task task = vm.suspendVM_Task();
        if (!task.waitForTask().equals(Task.SUCCESS)) {
            vSphereCloud.Log(slaveComputer, taskListener, "Unable to suspend the VM");
        }
    }
}
