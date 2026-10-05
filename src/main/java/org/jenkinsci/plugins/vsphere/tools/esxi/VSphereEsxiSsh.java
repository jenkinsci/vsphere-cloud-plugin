/*   Copyright 2026, Jim Klimov
 *
 *   Licensed under the Apache License, Version 2.0 (the "License");
 *   you may not use this file except in compliance with the License.
 *   You may obtain a copy of the License at
 *
 *       http://www.apache.org/licenses/LICENSE-2.0
 *
 *   Unless required by applicable law or agreed to in writing, software
 *   distributed under the License is distributed on an "AS IS" BASIS,
 *   WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *   See the License for the specific language governing permissions and
 *   limitations under the License.
 */
package org.jenkinsci.plugins.vsphere.tools.esxi;

import com.vmware.vim25.CustomizationSpecItem;
import com.vmware.vim25.GuestInfo;
import com.vmware.vim25.ManagedObjectReference;
import com.vmware.vim25.VirtualDevice;
import com.vmware.vim25.VirtualDeviceFileBackingInfo;
import com.vmware.vim25.VirtualDisk;
import com.vmware.vim25.VirtualMachineConfigInfo;
import com.vmware.vim25.VirtualMachineConfigSpec;
import com.vmware.vim25.VirtualMachineConfigSummary;
import com.vmware.vim25.VirtualMachineConnectionState;
import com.vmware.vim25.VirtualMachinePowerState;
import com.vmware.vim25.VirtualMachineRuntimeInfo;
import com.vmware.vim25.VirtualMachineSnapshotInfo;
import com.vmware.vim25.VirtualMachineSnapshotTree;
import com.vmware.vim25.VirtualMachineSummary;
import com.vmware.vim25.VirtualMachineToolsStatus;
import com.vmware.vim25.mo.DistributedVirtualPortgroup;
import com.vmware.vim25.mo.DistributedVirtualSwitch;
import com.vmware.vim25.mo.Folder;
import com.vmware.vim25.mo.ManagedEntity;
import com.vmware.vim25.mo.Network;
import com.vmware.vim25.mo.VirtualMachine;
import com.vmware.vim25.mo.VirtualMachineSnapshot;
import java.io.PrintStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.logging.Level;
import org.jenkinsci.plugins.vsphere.tools.AbstractVSphere;
import org.jenkinsci.plugins.vsphere.tools.HostSelectionOptions;
import org.jenkinsci.plugins.vsphere.tools.VSphereException;
import org.jenkinsci.plugins.vsphere.tools.VSphereLogger;
import org.jenkinsci.plugins.vsphere.tools.VmSize;

/**
 * A connection to a standalone ESXi host, which runs its {@code vim-cmd} through an {@link EsxiShell} (normally
 * SSH) instead of calling the vSphere API: for hosts whose API is read-only (free licenses) or that are too old,
 * and which have no vCenter that could do the cloning.
 *
 * <p>The VMs it hands out are {@link EsxiVirtualMachine}s, which answer what is asked of them from what the host
 * says and do what is asked of them by {@code vim-cmd}. As the operations that work purely through such VM
 * objects (power off with a grace period, IP lookup, destroy, ...) are in {@link AbstractVSphere}, they are the
 * very ones that are used with vCenter; this class has to provide only what is specific to the host.
 *
 * <p>What only makes sense with vCenter (folders, clusters, templates, customization specs, distributed
 * switches, ...) is not supported, and says so. Cloning is not supported yet.
 *
 * <p>Nothing that comes from outside is put in a command without being quoted ({@link ShellQuote}): VMs are
 * found by comparing names in Java, and are then addressed by the numeric id the host gave them.
 */
public class VSphereEsxiSsh extends AbstractVSphere {

    private static final String VIM_CMD = "/bin/vim-cmd";

    private final EsxiShell shell;

    public VSphereEsxiSsh(EsxiShell shell) {
        this.shell = shell;
    }

    @Override
    protected void closeSession() {
        shell.close();
    }

    @Override
    public boolean isSessionAlive() {
        try {
            return shell.run("true").succeeded();
        } catch (Exception e) {
            LOGGER.log(Level.FINE, "ESXi shell alive-check failed", e);
            return false;
        }
    }

    /**
     * Runs a {@code vim-cmd} command. When the host reports a fault, which it does by printing it (as
     * {@code (vim.fault.NotFound) { ... msg = "..." }}), the command is taken to have failed whatever its exit
     * code was, with the fault's message as the explanation.
     */
    ShellResult vim(String command) throws VSphereException {
        final ShellResult result = shell.run(command);
        final String fault = VimCmdParsers.parseFault(result.getStdout() + "\n" + result.getStderr());
        if (fault != null && result.succeeded()) {
            return new ShellResult(1, result.getStdout(), fault);
        }
        if (fault != null && result.getStderr().trim().isEmpty()) {
            return new ShellResult(result.getExitCode(), result.getStdout(), fault);
        }
        return result;
    }

    // -- looking up VMs --

    List<VmEntry> listVms() throws VSphereException {
        return VimCmdParsers.parseGetAllVms(
                vim(VIM_CMD + " vmsvc/getallvms").stdoutOrThrow("Listing the registered VMs"));
    }

    @Override
    public VirtualMachine getVmByName(String vmName) throws VSphereException {
        for (VmEntry entry : listVms()) {
            if (entry.getName().equals(vmName)) {
                return new EsxiVirtualMachine(this, entry);
            }
        }
        return null;
    }

    @Override
    public int countVms() throws VSphereException {
        return listVms().size();
    }

    @Override
    public int countVmsByPrefix(final String prefix) throws VSphereException {
        int count = 0;
        for (VmEntry entry : listVms()) {
            if (entry.getName().startsWith(prefix)) {
                count++;
            }
        }
        return count;
    }

    @Override
    protected VirtualMachineSnapshot newSnapshotProxy(VirtualMachine vm, ManagedObjectReference mor) {
        return new EsxiVirtualMachineSnapshot(this, ((EsxiVirtualMachine) vm).getEntry(), mor);
    }

    // -- what the VM objects are made of --

    private String vmCommand(VmEntry vm, String subcommand, String... quotedArguments) throws VSphereException {
        final StringBuilder command = new StringBuilder(VIM_CMD)
                .append(" vmsvc/")
                .append(subcommand)
                .append(' ')
                .append(ShellQuote.id(vm.getId()));
        for (String argument : quotedArguments) {
            command.append(' ').append(argument);
        }
        return command.toString();
    }

    VirtualMachineConfigInfo readConfigInfo(VmEntry vm) throws VSphereException {
        final String vmx = shell.run("cat " + ShellQuote.quote(vm.getVmxFileSystemPath()))
                .stdoutOrThrow("Reading the .vmx of " + vm);
        final VirtualMachineConfigInfo config = EsxiConfigInfo.build(vm, VmxFile.parse(vmx));
        // the size of a disk is in its descriptor, not in the .vmx
        final EsxiDatastoreFiles files = new EsxiDatastoreFiles(shell);
        final String folder =
                vm.getVmxFileSystemPath().substring(0, vm.getVmxFileSystemPath().lastIndexOf('/'));
        for (VirtualDevice device : config.getHardware().getDevice()) {
            if (device instanceof VirtualDisk && device.getBacking() instanceof VirtualDeviceFileBackingInfo) {
                final String file = ((VirtualDeviceFileBackingInfo) device.getBacking()).getFileName();
                try {
                    ((VirtualDisk) device)
                            .setCapacityInKB(files.readDescriptor(EsxiConfigInfo.pathOf(file, folder))
                                    .getCapacityKb());
                } catch (VSphereException e) {
                    LOGGER.log(Level.FINE, "Could not read the size of the disk " + file, e);
                }
            }
        }
        return config;
    }

    VirtualMachineRuntimeInfo readRuntime(VmEntry vm) throws VSphereException {
        final String output = vim(vmCommand(vm, "power.getstate")).stdoutOrThrow("Getting the power state of " + vm);
        final VirtualMachinePowerState state = VimCmdParsers.parsePowerState(output);
        if (state == null) {
            throw new VSphereException("Could not tell the power state of " + vm + " from: " + output.trim());
        }
        final VirtualMachineRuntimeInfo runtime = new VirtualMachineRuntimeInfo();
        runtime.setPowerState(state);
        runtime.setConnectionState(VirtualMachineConnectionState.connected);
        return runtime;
    }

    VirtualMachineSummary readSummary(VmEntry vm) throws VSphereException {
        final VirtualMachineSummary summary = new VirtualMachineSummary();
        summary.setRuntime(readRuntime(vm));
        final VirtualMachineConfigSummary config = new VirtualMachineConfigSummary();
        config.setName(vm.getName());
        config.setVmPathName(vm.getVmxPath());
        config.setTemplate(false);
        summary.setConfig(config);
        return summary;
    }

    GuestInfo readGuest(VmEntry vm) throws VSphereException {
        // A VM that is not running has little to tell (fields are "<unset>"); that is not an error
        final String output = vim(vmCommand(vm, "get.guest")).stdoutOrThrow("Getting the guest information of " + vm);
        final GuestInfo guest = new GuestInfo();
        guest.setIpAddress(VimCmdParsers.parseGuestIp(output));
        final VirtualMachineToolsStatus tools = VimCmdParsers.parseToolsStatus(output);
        guest.setToolsStatus(tools == null ? VirtualMachineToolsStatus.toolsNotInstalled : tools);
        return guest;
    }

    /** The snapshots of the VM, or null if it has none (as vCenter says). */
    VirtualMachineSnapshotInfo readSnapshotInfo(VmEntry vm) throws VSphereException {
        final List<VirtualMachineSnapshotTree> roots = VimCmdParsers.parseSnapshotTree(
                vim(vmCommand(vm, "snapshot.get")).stdoutOrThrow("Getting the snapshots of " + vm));
        if (roots.isEmpty()) {
            return null;
        }
        final VirtualMachineSnapshotInfo info = new VirtualMachineSnapshotInfo();
        info.setRootSnapshotList(roots.toArray(new VirtualMachineSnapshotTree[0]));
        return info;
    }

    // -- what is done to the VMs --

    /** Has the host do something to a VM, and reports how that went as vCenter would, as a finished task. */
    EsxiTask vmTask(String description, VmEntry vm, String subcommand, String... quotedArguments) {
        try {
            return taskOf(description, vim(vmCommand(vm, subcommand, quotedArguments)));
        } catch (VSphereException e) {
            return new EsxiTask(description, e.getMessage());
        }
    }

    /** Reverts to or removes a snapshot, which the host knows by its number. */
    EsxiTask snapshotTask(String description, VmEntry vm, String subcommand, String snapshotId, String flag) {
        if (!snapshotId.matches("[0-9]{1,9}")) {
            return new EsxiTask(description, "\"" + snapshotId + "\" is not the number of a snapshot");
        }
        // both are plain words by now, so this is not about what they hold
        return vmTask(description, vm, subcommand, snapshotId, flag);
    }

    EsxiTask createSnapshotTask(VmEntry vm, String name, String description, boolean memory, boolean quiesce) {
        try {
            return taskOf(
                    "createSnapshot",
                    vim(vmCommand(
                            vm,
                            "snapshot.create",
                            ShellQuote.quote(name),
                            ShellQuote.quote(description == null ? "" : description),
                            memory ? "1" : "0",
                            quiesce ? "1" : "0")));
        } catch (VSphereException e) {
            return new EsxiTask("createSnapshot", e.getMessage());
        }
    }

    /**
     * Changes the settings of a VM that is powered off: the {@code .vmx} file is changed (see
     * {@link EsxiReconfigure}) and the host is told to read it again. A running VM keeps what it had, as the host
     * writes the file again from it, so it is not changed; it has to be powered off first.
     */
    EsxiTask reconfigureTask(String description, VmEntry vm, VirtualMachineConfigSpec spec) {
        try {
            final VirtualMachinePowerState state = readRuntime(vm).getPowerState();
            if (state != VirtualMachinePowerState.poweredOff) {
                throw new VSphereException("The VM has to be powered off to be reconfigured over SSH to an ESXi host,"
                        + " but it is " + state);
            }
            final EsxiDatastoreFiles files = new EsxiDatastoreFiles(shell);
            final String vmxPath = vm.getVmxFileSystemPath();
            final VmxFile vmx = VmxFile.parse(files.read(vmxPath));
            final EsxiDiskChanges disks = EsxiReconfigure.apply(
                    vmx, spec, vmxPath.substring(0, vmxPath.lastIndexOf('/')), new EsxiDiskChanges.Inspector() {
                        @Override
                        public VmdkDescriptor descriptor(String path) throws VSphereException {
                            return files.readDescriptor(path);
                        }

                        @Override
                        public boolean exists(String path) throws VSphereException {
                            return files.exists(path);
                        }
                    });
            final List<String> made = new ArrayList<>();
            try {
                for (EsxiDiskChanges.Step step : disks.before()) {
                    if (step.kind == EsxiDiskChanges.Step.Kind.CREATE) {
                        files.createDisk(step.path, step.sizeKb, step.thin);
                        made.add(step.path);
                    } else {
                        files.extendDisk(step.path, step.sizeKb);
                    }
                }
                files.replace(vmxPath, vmx.toString());
                vim(vmCommand(vm, "reload"))
                        .stdoutOrThrow("Having the host read the configuration of " + vm + " again");
            } catch (VSphereException e) {
                for (String path : made) {
                    try {
                        files.deleteDisk(path);
                    } catch (VSphereException again) {
                        e.addSuppressed(again);
                    }
                }
                throw e;
            }
            for (EsxiDiskChanges.Step step : disks.after()) {
                files.deleteDisk(step.path);
            }
            return new EsxiTask(description, null);
        } catch (VSphereException e) {
            return new EsxiTask(description, e.getMessage());
        }
    }

    /** Whether the host has a port group of a standard switch with the name; one that cannot be asked is assumed. */
    @Override
    public Network getNetworkPortGroupByName(VirtualMachine virtualMachine, String name) throws VSphereException {
        final ShellResult result = shell.run("esxcli network vswitch standard portgroup list");
        if (result.succeeded()) {
            final Boolean known = EsxiNetwork.isListed(result.getStdout(), name);
            if (known != null) {
                return known ? new EsxiNetwork(name) : null;
            }
        }
        LOGGER.log(Level.FINE, "Could not look up port groups on the ESXi host, taking \"{0}\" to exist", name);
        return new EsxiNetwork(name);
    }

    private static EsxiTask taskOf(String description, ShellResult result) {
        if (result.succeeded()) {
            return new EsxiTask(description, null);
        }
        final String explanation = !result.getStderr().trim().isEmpty()
                ? result.getStderr().trim()
                : result.getStdout().trim();
        return new EsxiTask(
                description, "exit code " + result.getExitCode() + (explanation.isEmpty() ? "" : ": " + explanation));
    }

    /** For the operations that are not tasks and that report failure by an exception. */
    void vmCommandOrThrow(String what, VmEntry vm, String subcommand) {
        try {
            vim(vmCommand(vm, subcommand)).stdoutOrThrow("Asking the host to carry out " + what + " " + vm);
        } catch (VSphereException e) {
            throw new IllegalStateException(e.getMessage(), e);
        }
    }

    /** For what is not done yet. */
    private static VSphereException unsupported(String operation) {
        return new VSphereException(operation + " is not supported by the ESXi SSH backend (yet)");
    }

    /** For what a standalone ESXi host does not have, which is what {@link EsxiPlatformConstraint} is for. */
    static EsxiConstraintException notApplicable(String operation, String reason) {
        return new EsxiConstraintException(operation + " is not applicable to a standalone ESXi host: " + reason);
    }

    // -- datastores --

    /** The places on the host where VMs can be kept, with their size and free space. */
    List<EsxiDatastoreEntry> listDatastores() throws VSphereException {
        final String output =
                shell.run("esxcli storage filesystem list").stdoutOrThrow("Listing the datastores of the host");
        final List<EsxiDatastoreEntry> all = EsxiDatastoreEntry.parse(output);
        if (all == null) {
            throw new VSphereException("Could not make out the datastores from: " + output.trim());
        }
        final List<EsxiDatastoreEntry> usable = new ArrayList<>();
        for (EsxiDatastoreEntry entry : all) {
            if (entry.isForVms() && entry.isMounted()) {
                usable.add(entry);
            }
        }
        return usable;
    }

    @Override
    public ManagedEntity[] getDatastores() throws VSphereException {
        final List<ManagedEntity> datastores = new ArrayList<>();
        for (EsxiDatastoreEntry entry : listDatastores()) {
            datastores.add(new EsxiDatastore(entry));
        }
        return datastores.toArray(new ManagedEntity[0]);
    }

    /** The datastore with the name, or null if the host has none like that. */
    public EsxiDatastore getDatastoreByName(String name) throws VSphereException {
        for (EsxiDatastoreEntry entry : listDatastores()) {
            if (entry.getName().equals(name)) {
                return new EsxiDatastore(entry);
            }
        }
        return null;
    }

    /** The datastores that hold the files of the VM (its configuration and its disks). */
    List<EsxiDatastore> datastoresOf(VmEntry vm) throws VSphereException {
        final java.util.Set<String> names = new java.util.LinkedHashSet<>();
        names.add(vm.getDatastore());
        final VirtualMachineConfigInfo config = readConfigInfo(vm);
        for (VirtualDevice device : config.getHardware().getDevice()) {
            if (device instanceof VirtualDisk && device.getBacking() instanceof VirtualDeviceFileBackingInfo) {
                final String file = ((VirtualDeviceFileBackingInfo) device.getBacking()).getFileName();
                if (file.startsWith("[") && file.indexOf(']') > 0) {
                    names.add(file.substring(1, file.indexOf(']')));
                }
            }
        }
        final List<EsxiDatastore> datastores = new ArrayList<>();
        for (String name : names) {
            final EsxiDatastore known = getDatastoreByName(name);
            datastores.add(known != null ? known : new EsxiDatastore(name));
        }
        return datastores;
    }

    // -- cloning, done on the files of the host --

    /**
     * Makes a copy of a VM, a linked one (sharing the data of the snapshot of its master) or a full one, by
     * working on the files of the datastore. What does not apply to a standalone host (clusters, folders,
     * customization specs, choosing a host) is not accepted where it would change the outcome, and is said to
     * be ignored where it would not.
     */
    @Override
    public void cloneOrDeployVm(
            String cloneName,
            String sourceName,
            boolean linkedClone,
            String resourcePoolName,
            String cluster,
            String datastoreName,
            String folderName,
            boolean useCurrentSnapshot,
            final String namedSnapshot,
            boolean powerOn,
            Map<String, String> extraConfigParameters,
            String customizationSpec,
            String hostName,
            String hostSelectionMode,
            Set<String> hostSelectionCandidates,
            HostSelectionOptions hostSelectionOptions,
            VmSize vmSize,
            PrintStream jLogger)
            throws VSphereException {
        refuse("a customization specification", customizationSpec);
        refuse("choosing a host", hostName);
        refuse("choosing a host", hostSelectionMode);
        if (hostSelectionCandidates != null && !hostSelectionCandidates.isEmpty()) {
            refuse("choosing a host", hostSelectionCandidates.toString());
        }
        if (namedSnapshot != null && !namedSnapshot.trim().isEmpty()) {
            throw unsupported("Cloning from the named snapshot \"" + namedSnapshot + "\"");
        }
        ignored(jLogger, "cluster", cluster);
        ignored(jLogger, "folder", folderName);
        if (resourcePoolName != null && !resourcePoolName.trim().isEmpty() && !"Resources".equals(resourcePoolName)) {
            ignored(jLogger, "resource pool", resourcePoolName);
        }
        new EsxiVmCloner(this, new EsxiDatastoreFiles(shell), jLogger)
                .clone(cloneName, sourceName, linkedClone, datastoreName, powerOn, extraConfigParameters, vmSize);
    }

    private static void refuse(String what, String value) throws VSphereException {
        if (value != null && !value.trim().isEmpty()) {
            throw new EsxiConstraintException("Over SSH to an ESXi host, " + what
                    + " cannot be used (it was given as \"" + value + "\"): leave it empty");
        }
    }

    private static void ignored(PrintStream log, String what, String value) {
        if (value != null && !value.trim().isEmpty() && log != null) {
            VSphereLogger.vsLogger(
                    log, "The " + what + " \"" + value + "\" is ignored: a standalone ESXi host has none to choose");
        }
    }

    /** Runs a command that takes a while, like the copy of a disk, within the time limit of commands. */
    void runLong(String command, String what) throws VSphereException {
        vim(command).stdoutOrThrow(what);
    }

    // -- what needs vCenter, or is not there yet --

    /**
     * Whether the name is that of this host: its host name, with or without the domain, as the host itself says
     * (a standalone host knows no others). Names are compared without regard to case.
     */
    @Override
    public boolean hostExists(final String hostName) throws VSphereException {
        if (hostName == null || hostName.trim().isEmpty()) {
            return false;
        }
        final String wanted = hostName.trim();
        for (String name : ownNames()) {
            if (name.equalsIgnoreCase(wanted)) {
                return true;
            }
        }
        return false;
    }

    /** The names this host calls itself: host name, domain name, and both together. */
    List<String> ownNames() throws VSphereException {
        final List<String> names = new ArrayList<>();
        final ShellResult esxcli = shell.run("esxcli system hostname get");
        if (esxcli.succeeded()) {
            for (String line : esxcli.getStdout().split("\\R")) {
                final int colon = line.indexOf(':');
                if (colon > 0 && !line.substring(colon + 1).trim().isEmpty()) {
                    final String label = line.substring(0, colon).trim();
                    if (label.equals("Host Name") || label.equals("Fully Qualified Domain Name")) {
                        names.add(line.substring(colon + 1).trim());
                    }
                }
            }
        }
        if (names.isEmpty()) {
            names.add(shell.run("hostname")
                    .stdoutOrThrow("Asking the host for its name")
                    .trim());
        }
        final String first = names.get(0);
        if (first.contains(".")) {
            names.add(first.substring(0, first.indexOf('.')));
        }
        return names;
    }

    @Override
    public void markAsTemplate(String vmName, String snapName, boolean force) throws VSphereException {
        throw unsupported("markAsTemplate");
    }

    @Override
    public void markAsVm(String name, String resourcePool, String cluster) throws VSphereException {
        throw unsupported("markAsVm");
    }

    @Override
    public Boolean folderExists(String folderPath) throws VSphereException {
        throw notApplicable("folderExists", "there are no folders in a standalone host's inventory");
    }

    @Override
    public Folder getFolder(String folderPath) throws VSphereException {
        throw notApplicable("getFolder", "there are no folders in a standalone host's inventory");
    }

    @Override
    public CustomizationSpecItem getCustomizationSpecByName(final String customizationSpecName)
            throws VSphereException {
        throw notApplicable("getCustomizationSpecByName", "customization specifications are kept by vCenter");
    }

    @Override
    public DistributedVirtualPortgroup getDistributedVirtualPortGroupByName(VirtualMachine virtualMachine, String name)
            throws VSphereException {
        throw notApplicable("getDistributedVirtualPortGroupByName", "distributed switches are managed by vCenter");
    }

    @Override
    public DistributedVirtualSwitch getDistributedVirtualSwitchByPortGroup(
            DistributedVirtualPortgroup distributedVirtualPortgroup) throws VSphereException {
        throw notApplicable("getDistributedVirtualSwitchByPortGroup", "distributed switches are managed by vCenter");
    }
}
