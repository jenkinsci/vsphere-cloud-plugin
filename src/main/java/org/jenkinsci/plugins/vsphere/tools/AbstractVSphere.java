package org.jenkinsci.plugins.vsphere.tools;

import com.vmware.vim25.GuestInfo;
import com.vmware.vim25.ManagedObjectReference;
import com.vmware.vim25.OptionValue;
import com.vmware.vim25.TaskInfo;
import com.vmware.vim25.TaskInfoState;
import com.vmware.vim25.VirtualMachineConfigSpec;
import com.vmware.vim25.VirtualMachinePowerState;
import com.vmware.vim25.VirtualMachineQuestionInfo;
import com.vmware.vim25.VirtualMachineSnapshotInfo;
import com.vmware.vim25.VirtualMachineSnapshotTree;
import com.vmware.vim25.VirtualMachineToolsStatus;
import com.vmware.vim25.mo.Task;
import com.vmware.vim25.mo.VirtualMachine;
import com.vmware.vim25.mo.VirtualMachineSnapshot;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * What is common to all the ways of talking to a hypervisor: the operations that are carried out purely through the
 * {@link VirtualMachine} (and snapshot and task) objects that the connection hands out, and the bookkeeping of
 * connections that are owned by a {@link VSphereConnectionPool}.
 *
 * <p>Subclasses provide the connection itself and the lookup of virtual machines, and whatever else is specific
 * to what they talk to.
 */
public abstract class AbstractVSphere implements VSphere {

    protected static final Logger LOGGER = Logger.getLogger(VSphere.class.getName());

    /**
     * When non-null, this instance is managed by a {@link VSphereConnectionPool}:
     * {@link #disconnect()} calls back into {@link VSphereConnectionPool#release()}
     * instead of logging out, so the pool can defer the real disconnect until every
     * borrower has released it.
     */
    private volatile VSphereConnectionPool owningPool = null;

    /** Ends the session with the hypervisor; failures are logged by the caller. */
    protected abstract void closeSession() throws Exception;

    /**
     * Disconnect from the server.
     * <p>
     * When this instance is managed by a {@link VSphereConnectionPool}, this instead
     * signals the pool that this caller is done with it (via
     * {@link VSphereConnectionPool#release()}); the pool decides when the underlying
     * session actually gets closed.
     * </p>
     * <p>
     * Note: This logs any {@link Exception} it encounters - it does not pass
     * them to get to the calling method.
     * </p>
     */
    @Override
    public void disconnect() {
        final VSphereConnectionPool pool = owningPool;
        if (pool != null) {
            pool.release();
            return;
        }
        try {
            closeSession();
        } catch (Exception e) {
            LOGGER.log(Level.WARNING, "Caught exception when trying to disconnect vSphere.", e);
        }
    }

    /**
     * Marks this instance as owned by {@code pool}, so that {@link #disconnect()}
     * releases it back to the pool instead of logging out directly.
     * Internal: only {@link VSphereConnectionPool} should call this.
     */
    @Override
    public void markAsPooled(VSphereConnectionPool pool) {
        owningPool = pool;
    }

    /**
     * Disconnects the underlying session regardless of pooled status.
     * Called by {@link VSphereConnectionPool} when it actually wants to tear down
     * the session (restart, idle timeout, shutdown).
     * Internal: only {@link VSphereConnectionPool} should call this.
     */
    @Override
    public void forceDisconnect() {
        owningPool = null;
        disconnect();
    }

    @Override
    public void reconfigureVm(String name, VirtualMachineConfigSpec spec) throws VSphereException {
        VirtualMachine vm = getVmByName(name);
        if (vm == null) {
            throw new VSphereNotFoundException("VM or template", name);
        }
        LOGGER.log(Level.FINER, "Reconfiguring VM. Please wait ...");
        try {
            Task task = vm.reconfigVM_Task(spec);
            String status = task.waitForTask();
            if (status.equals(TaskInfoState.success.toString())) {
                return;
            }
            throw newVSphereException(task.getTaskInfo(), "Couldn't reconfigure \"" + name + "\"!");
        } catch (RuntimeException | VSphereException e) {
            throw e;
        } catch (Exception e) {
            throw new VSphereException("VM cannot be reconfigured:" + e.getMessage(), e);
        }
    }

    /**
     * @param name - Name of VM to start
     * @param timeoutInSeconds How long to wait for the VM to be running.
     * @throws VSphereException If an error occurred.
     */
    @Override
    public void startVm(String name, int timeoutInSeconds) throws VSphereException {
        try {
            VirtualMachine vm = getVmByName(name);
            if (vm == null) {
                throw new VSphereNotFoundException("VM", name);
            }
            if (isPoweredOn(vm)) return;

            if (vm.getConfig().template) throw new VSphereException("VM represents a template!");

            Task task = vm.powerOnVM_Task(null);

            int timesToCheck = timeoutInSeconds / 5;
            // add one extra time for remainder
            timesToCheck++;
            LOGGER.log(Level.FINER, "Checking " + timesToCheck + " times for vm to be powered on");

            for (int i = 0; i < timesToCheck; i++) {
                if (task.getTaskInfo().getState() == TaskInfoState.success) {
                    LOGGER.log(Level.FINER, "VM was powered up successfully.");
                    return;
                }
                if (task.getTaskInfo().getState() == TaskInfoState.running
                        || task.getTaskInfo().getState() == TaskInfoState.queued) {
                    Thread.sleep(5000);
                }
                // Check for copied/moved question
                VirtualMachineQuestionInfo q = vm.getRuntime().getQuestion();
                if (q != null && q.getId().equals("_vmx1")) {
                    vm.answerVM(q.getId(), q.getChoice().getDefaultIndex().toString());
                    return;
                }
            }
        } catch (InterruptedException e) { // build aborted
            Thread.currentThread().interrupt(); // pass interrupt upwards
            throw new VSphereException("VM cannot be started: " + e.getMessage(), e);
        } catch (Exception e) {
            throw new VSphereException("VM cannot be started: " + e.getMessage(), e);
        }

        throw new VSphereException("VM cannot be started");
    }

    protected ManagedObjectReference findSnapshotInTree(VirtualMachineSnapshotTree[] snapTree, String snapName) {
        LOGGER.log(Level.FINER, "Looking for snapshot " + snapName);
        for (VirtualMachineSnapshotTree node : snapTree) {
            if (snapName.equals(node.getName())) {
                return node.getSnapshot();
            } else {
                VirtualMachineSnapshotTree[] childTree = node.getChildSnapshotList();
                if (childTree != null) {
                    ManagedObjectReference mor = findSnapshotInTree(childTree, snapName);
                    if (mor != null) {
                        return mor;
                    }
                }
            }
        }
        return null;
    }

    @Override
    public VirtualMachineSnapshot getSnapshotInTree(VirtualMachine vm, String snapName) {
        if (vm == null || snapName == null) {
            return null;
        }

        LOGGER.log(Level.FINER, "Looking for snapshot " + snapName + " in " + vm.getName());
        VirtualMachineSnapshotInfo info = vm.getSnapshot();
        if (info != null) {
            VirtualMachineSnapshotTree[] snapTree = info.getRootSnapshotList();
            if (snapTree != null) {
                ManagedObjectReference mor = findSnapshotInTree(snapTree, snapName);
                if (mor != null) {
                    return new VirtualMachineSnapshot(vm.getServerConnection(), mor);
                }
            }
        }

        return null;
    }

    @Override
    public void revertToSnapshot(String vmName, String snapName) throws VSphereException {
        revertToSnapshot(vmName, snapName, false);
    }

    @Override
    public void revertToSnapshot(String vmName, String snapName, boolean suppressPowerOn) throws VSphereException {

        VirtualMachine vm = getVmByName(vmName);
        VirtualMachineSnapshot snap = getSnapshotInTree(vm, snapName);

        if (snap == null) {
            LOGGER.log(
                    Level.SEVERE,
                    "Cannot find snapshot: '" + snapName + "' for virtual machine: '" + vm.getName() + "'");
            throw new VSphereNotFoundException("Snapshot", snapName);
        }

        try {
            Task task = snap.revertToSnapshot_Task(null, Boolean.valueOf(suppressPowerOn));
            if (!task.waitForTask().equals(Task.SUCCESS)) {
                final String msg = "Could not revert to snapshot '" + snap.toString() + "' for virtual machine:'"
                        + vm.getName() + "'";
                LOGGER.log(Level.SEVERE, msg);
                throw newVSphereException(task.getTaskInfo(), msg);
            }
        } catch (RuntimeException | VSphereException e) {
            throw e;
        } catch (Exception e) {
            throw new VSphereException(e);
        }
    }

    @Override
    public void deleteSnapshot(String vmName, String snapName, boolean consolidate, boolean failOnNoExist)
            throws VSphereException {

        VirtualMachine vm = getVmByName(vmName);
        VirtualMachineSnapshot snap = getSnapshotInTree(vm, snapName);

        if (snap == null && failOnNoExist) {
            throw new VSphereNotFoundException("Snapshot", snapName);
        }

        try {
            Task task;
            if (snap != null) {
                // Does not delete subtree; consolidates the disk, which the API did implicitly while the flag was
                // omitted - it has to be explicit now, as the single-argument overload delegates a null Boolean
                // that the SDK unboxes
                task = snap.removeSnapshot_Task(false, Boolean.TRUE);
                if (!task.waitForTask().equals(Task.SUCCESS)) {
                    throw newVSphereException(task.getTaskInfo(), "Could not delete snapshot");
                }
            }

            if (!consolidate) return;

            // This might be redundant, but I think it consolidates all disks,
            // where as the removeSnapshot only consolidates the individual disk
            task = vm.consolidateVMDisks_Task();
            if (!task.waitForTask().equals(Task.SUCCESS)) {
                throw newVSphereException(task.getTaskInfo(), "Could not consolidate VM disks");
            }
        } catch (RuntimeException | VSphereException e) {
            throw e;
        } catch (Exception e) {
            throw new VSphereException(e);
        }
    }

    @Override
    public void takeSnapshot(String vmName, String snapshot, String description, boolean snapMemory)
            throws VSphereException {

        final String message = "Could not take snapshot";
        VirtualMachine vmToSnapshot = getVmByName(vmName);
        if (vmToSnapshot == null) {
            throw new VSphereNotFoundException("VM", vmName);
        }
        try {
            Task task = vmToSnapshot.createSnapshot_Task(snapshot, description, snapMemory, !snapMemory);
            if (task.waitForTask().equals(Task.SUCCESS)) {
                return;
            }
            throw newVSphereException(task.getTaskInfo(), message);
        } catch (RuntimeException | VSphereException e) {
            throw e;
        } catch (Exception e) {
            throw new VSphereException(message, e);
        }
    }

    /**
     * Asks vSphere for the IP address used by a VM.
     *
     * @param vm VirtualMachine name whose IP is to be returned.
     * @param timeout How long to wait (in seconds) for the IP address to known to vSphere.
     * @return String containing IP address.
     * @throws VSphereException If an error occurred.
     */
    @Override
    public String getIp(VirtualMachine vm, int timeout) throws VSphereException {

        if (vm == null) throw new VSphereException("VM is null");

        // Determine how many attempts will be made to fetch the IP address
        final int waitSeconds = 5;
        final int maxTries;
        if (timeout <= waitSeconds) maxTries = 1;
        else maxTries = (int) Math.round((double) timeout / waitSeconds);

        for (int count = 0; count < maxTries; count++) {

            GuestInfo guestInfo = vm.getGuest();

            // guest info can be null sometimes
            if (guestInfo != null && guestInfo.getIpAddress() != null) {
                return guestInfo.getIpAddress();
            }

            try {
                // wait
                Thread.sleep(waitSeconds * 1000);
            } catch (InterruptedException e) { // build aborted
                Thread.currentThread().interrupt(); // pass interrupt upwards
                break; // and abort our activities now.
            }
        }
        return null;
    }

    /**
     * Destroys the VM in vSphere
     * @param name - VM object to destroy
     * @param failOnNoExist If true and the VM does not exist then a {@link VSphereNotFoundException} will be thrown.
     * @throws VSphereException If an error occurred.
     */
    @Override
    public void destroyVm(String name, boolean failOnNoExist) throws VSphereException {
        try {
            VirtualMachine vm = getVmByName(name);
            if (vm == null) {
                if (failOnNoExist) throw new VSphereNotFoundException("VM", name);

                LOGGER.log(Level.FINER, "VM \"" + name + "\" does not exist, or already deleted!");
                return;
            }

            if (!vm.getConfig().template) {
                powerOffVm(vm, true, 0);
            }

            final Task task = vm.destroy_Task();
            String status = task.waitForTask();
            if (status.equals(Task.SUCCESS)) {
                LOGGER.log(Level.FINER, "VM \"" + name + "\" was deleted successfully.");
                return;
            }
            throw newVSphereException(task.getTaskInfo(), "Could not delete VM \"" + name + "\"!");

        } catch (RuntimeException | VSphereException e) {
            throw e;
        } catch (Exception e) {
            throw new VSphereException(e.getMessage(), e);
        }
    }

    /**
     * Renames a VM Snapshot
     * @param vmName the name of the VM whose snapshot is being renamed.
     * @param oldName the current name of the VM's snapshot.
     * @param newName the new name of the VM's snapshot.
     * @param newDescription the new description of the VM's snapshot.
     * @throws VSphereException If an error occurred.
     */
    @Override
    public void renameVmSnapshot(String vmName, String oldName, String newName, String newDescription)
            throws VSphereException {
        renameVmSnapshot(vmName, oldName, newName, newDescription, true);
    }

    /**
     * Renames a VM Snapshot
     * @param vmName the name of the VM whose snapshot is being renamed.
     * @param oldName the current name of the VM's snapshot.
     * @param newName the new name of the VM's snapshot.
     * @param newDescription the new description of the VM's snapshot.
     * @param failOnNoExist If true and the snapshot does not exist then a {@link VSphereNotFoundException} will be
     *                      thrown; otherwise nothing is renamed.
     * @return true if the snapshot was renamed, false if it did not exist (and that was tolerated).
     * @throws VSphereException If an error occurred (the VM not existing is always an error).
     */
    @Override
    public boolean renameVmSnapshot(
            String vmName, String oldName, String newName, String newDescription, boolean failOnNoExist)
            throws VSphereException {
        try {
            VirtualMachine vm = getVmByName(vmName);
            if (vm == null) {
                throw new VSphereNotFoundException("VM", vmName);
            }

            VirtualMachineSnapshot snapshot = getSnapshotInTree(vm, oldName);
            if (snapshot == null) {
                if (failOnNoExist) {
                    throw new VSphereNotFoundException("Snapshot", oldName);
                }
                LOGGER.log(Level.FINER, "VM Snapshot does not exist, so was not renamed.");
                return false;
            }

            snapshot.rename(newName, newDescription);

            LOGGER.log(Level.FINER, "VM Snapshot was renamed successfully.");
            return true;

        } catch (RuntimeException | VSphereException e) {
            throw e;
        } catch (Exception e) {
            throw new VSphereException(e.getMessage(), e);
        }
    }

    /**
     * Renames the VM vSphere
     * @param oldName the current name of the vm
     * @param newName the new name of the vm
     * @throws VSphereException If an error occurred.
     */
    @Override
    public void renameVm(String oldName, String newName) throws VSphereException {
        try {
            VirtualMachine vm = getVmByName(oldName);
            if (vm == null) {
                throw new VSphereNotFoundException("VM", oldName);
            }

            final Task task = vm.rename_Task(newName);
            final String status = task.waitForTask();
            if (status.equals(Task.SUCCESS)) {
                LOGGER.log(Level.FINER, "VM was renamed successfully.");
                return;
            }
            throw newVSphereException(task.getTaskInfo(), "Could not rename VM \"" + oldName + "\"!");

        } catch (RuntimeException | VSphereException e) {
            throw e;
        } catch (Exception e) {
            throw new VSphereException(e.getMessage(), e);
        }
    }

    protected boolean isSuspended(VirtualMachine vm) {
        return (vm.getRuntime().getPowerState() == VirtualMachinePowerState.suspended);
    }

    protected boolean isPoweredOn(VirtualMachine vm) {
        return (vm.getRuntime().getPowerState() == VirtualMachinePowerState.poweredOn);
    }

    protected boolean isPoweredOff(VirtualMachine vm) {
        return (vm.getRuntime() != null && vm.getRuntime().getPowerState() == VirtualMachinePowerState.poweredOff);
    }

    @Override
    public boolean vmToolIsEnabled(VirtualMachine vm) {
        VirtualMachineToolsStatus status = vm.getGuest().toolsStatus;
        return ((status == VirtualMachineToolsStatus.toolsOk) || (status == VirtualMachineToolsStatus.toolsOld));
    }

    /**
     * Power off the given virtual machine, optionally waiting 180 seconds for its operating system to shut down.
     * @param vm The virtual machine to power off.
     * @param evenIfSuspended If false, a suspended VM is left as it was. If true, a suspended VM gets fully powered off.
     * @param shutdownGracefully If false, the VM is powered off immediately. If true (and VMware tools is installed), the guest operating system is given a grace period of 180 seconds to shut down.
     * @deprecated This method has been superseded by {@link #powerOffVm(VirtualMachine, boolean, int)}, which allows setting an arbitrary grace period.
     */
    @Override
    @Deprecated
    public void powerOffVm(VirtualMachine vm, boolean evenIfSuspended, boolean shutdownGracefully)
            throws VSphereException {
        powerOffVm(vm, evenIfSuspended, shutdownGracefully ? 180 : 0);
    }

    /**
     * Power off the given virtual machine, optionally waiting a while for its operating system to shut down.
     * @param vm The virtual machine to power off.
     * @param evenIfSuspended If false, a suspended VM is left as it was. If true, a suspended VM gets fully powered off.
     * @param gracefulShutdownSeconds The number of seconds to wait for the guest operating system to shut down. If the passed value is zero or less (or if VMware tools is not installed on the VM), the VM is powered off immediately.
     */
    @Override
    public void powerOffVm(VirtualMachine vm, boolean evenIfSuspended, int gracefulShutdownSeconds)
            throws VSphereException {

        if (vm.getConfig().template) throw new VSphereException("VM represents a template!");

        if (isPoweredOn(vm) || (evenIfSuspended && isSuspended(vm))) {
            boolean doHardShutdown = true;

            String status;
            try {
                if (!isSuspended(vm) && gracefulShutdownSeconds > 0 && vmToolIsEnabled(vm)) {
                    LOGGER.log(Level.FINER, "Requesting guest shutdown");
                    vm.shutdownGuest();

                    // Wait for a short while for a shutdown - then power off hard.
                    for (int i = 0; i <= gracefulShutdownSeconds; i++) {
                        try {
                            Thread.sleep(1000);
                        } catch (InterruptedException e) { // build aborted
                            Thread.currentThread().interrupt(); // pass interrupt upwards
                            throw new VSphereException("VM power-down interrupted", e);
                        }
                        if (isPoweredOff(vm)) {
                            doHardShutdown = false;
                            LOGGER.log(Level.FINER, "VM gracefully powered down successfully.");
                            return;
                        }
                    }
                }

                if (doHardShutdown) {
                    LOGGER.log(Level.FINER, "Powering off the VM");
                    final Task task = vm.powerOffVM_Task();
                    status = task.waitForTask();

                    if (status.equals(Task.SUCCESS)) {
                        LOGGER.log(Level.FINER, "VM was powered down successfully.");
                        return;
                    }
                    throw newVSphereException(task.getTaskInfo(), "Machine could not be powered down!");
                }
            } catch (RuntimeException | VSphereException e) {
                throw e;
            } catch (Exception e) {
                throw new VSphereException(e);
            }
        } else if (isPoweredOff(vm)) {
            LOGGER.log(Level.FINER, "Machine is already off.");
            return;
        }

        throw new VSphereException("Machine could not be powered down!");
    }

    @Override
    public void suspendVm(VirtualMachine vm) throws VSphereException {
        if (isPoweredOn(vm)) {
            try {
                // TODO is this better?
                // vm.shutdownGuest()
                final Task task = vm.suspendVM_Task();
                final String status = task.waitForTask();
                if (Task.SUCCESS.equals(status)) {
                    LOGGER.log(Level.FINER, "VM was suspended successfully.");
                    return;
                }
                throw newVSphereException(task.getTaskInfo(), "Machine could not be suspended!");
            } catch (RuntimeException | VSphereException e) {
                throw e;
            } catch (Exception e) {
                throw new VSphereException(e);
            }
        } else {
            LOGGER.log(Level.FINER, "Machine not powered on.");
            return;
        }
    }

    /**
     * Passes data to a VM's "extra config" object. This data can then be read
     * back at a later stage.
     * In the case of parameters whose name starts "guestinfo.", the parameter
     * can be read by the VMware Tools on the client OS.
     * <p>
     * e.g. a variable named "guestinfo.Foo" with value "Bar" could be read on
     * the guest using the command-line
     * {@code vmtoolsd --cmd "info-get guestinfo.Foo"}.
     * </p>
     *
     * @param vmName
     *            The name of the VM.
     * @param parameters
     *            A {@link Map} of variable name to variable value.
     * @throws VSphereException
     *             If an error occurred.
     */
    @Override
    public void setExtraConfigParameters(String vmName, Map<String, String> parameters) throws VSphereException {
        VirtualMachineConfigSpec cs = createVMConfigSpecFromExtraConfigParameters(parameters);
        reconfigureVm(vmName, cs);
    }

    protected static VirtualMachineConfigSpec createVMConfigSpecFromExtraConfigParameters(
            Map<String, String> parameters) {
        VirtualMachineConfigSpec cs = new VirtualMachineConfigSpec();
        OptionValue[] ourOptionValues = new OptionValue[parameters.size()];
        List<OptionValue> optionValues = new ArrayList<>();
        for (Map.Entry<String, String> eachVariable : parameters.entrySet()) {
            OptionValue ov = new OptionValue();
            ov.setKey(eachVariable.getKey());
            ov.setValue(eachVariable.getValue());
            optionValues.add(ov);
        }
        for (int i = 0; i < optionValues.size(); i++) {
            ourOptionValues[i] = optionValues.get(i);
        }
        cs.setExtraConfig(ourOptionValues);
        return cs;
    }

    /**
     * Creates a {@link VSphereException} whose cause is the {@link TaskInfo}'s
     * exception. This provides an exception that is much more informative than
     * what is said by the <code>message</code> alone.
     *
     * @param taskInfo
     *            The vSphere task that failed.
     * @param message
     *            A line of text that says what the task was trying to achieve.
     * @return An exception that includes the cause of the failure.
     */
    protected static VSphereException newVSphereException(TaskInfo taskInfo, final String message) {
        final com.vmware.vim25.LocalizedMethodFault error = taskInfo == null ? null : taskInfo.getError();
        final String faultMsg = error == null ? null : error.getLocalizedMessage();
        final Exception fault = error == null ? null : error.getFault();
        final String combinedMsg = message + (faultMsg == null ? "" : ("\n" + faultMsg));
        if (fault != null) {
            return new VSphereException(combinedMsg, fault);
        } else {
            return new VSphereException(combinedMsg);
        }
    }
}
