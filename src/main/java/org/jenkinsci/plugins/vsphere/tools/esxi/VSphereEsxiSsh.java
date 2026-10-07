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
import edu.umd.cs.findbugs.annotations.CheckForNull;
import java.io.PrintStream;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.logging.Level;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.jenkinsci.plugins.vsphere.tools.AbstractVSphere;
import org.jenkinsci.plugins.vsphere.tools.HostSelectionOptions;
import org.jenkinsci.plugins.vsphere.tools.VSphereException;
import org.jenkinsci.plugins.vsphere.tools.VSphereHostSelection;
import org.jenkinsci.plugins.vsphere.tools.VSphereLogger;
import org.jenkinsci.plugins.vsphere.tools.VSphereNotFoundException;
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

    /** The cluster this host is a member of, and what it is called there, if it is one. */
    private @CheckForNull VSphereEsxiCluster cluster;

    private @CheckForNull String label;

    public VSphereEsxiSsh(EsxiShell shell) {
        this.shell = shell;
    }

    void joinCluster(VSphereEsxiCluster cluster, String label) {
        this.cluster = cluster;
        this.label = label;
    }

    /** What this host is called in messages: how it is configured if it is part of a cluster. */
    String getLabel() {
        return label == null ? "the ESXi host" : label;
    }

    /** The hosts that are asked about VMs and datastores: this one, and those of its cluster that can be reached. */
    List<VSphereEsxiSsh> clusterHosts() {
        return cluster == null ? List.of(this) : cluster.availableMembers();
    }

    EsxiDatastoreFiles files() {
        return new EsxiDatastoreFiles(shell);
    }

    /** True if the path exists on this host (a shared datastore is there for each of the hosts that have it). */
    boolean fileExists(String path) throws VSphereException {
        return files().exists(path);
    }

    /**
     * How busy the host is, simply: the number of VMs that are on, then the number that are registered. What
     * decides where a clone is made when no host is asked for.
     */
    long[] load() throws VSphereException {
        long on = 0;
        final List<VmEntry> vms = listVms();
        for (VmEntry entry : vms) {
            if (VimCmdParsers.parsePowerState(
                            vim(vmCommand(entry, "power.getstate")).getStdout())
                    == VirtualMachinePowerState.poweredOn) {
                on++;
            }
        }
        return new long[] {on, vms.size()};
    }

    private @CheckForNull Integer maxHardwareVersion;
    private boolean maxHardwareVersionKnown;

    /**
     * Gives a VM that is to be registered with this host the newest virtual hardware version that the host has, if the
     * VM has a newer one (the host would take it for invalid). Returns what was done, to say, or null if nothing was.
     */
    synchronized @CheckForNull String adaptHardwareVersion(VmxFile vmx) {
        if (!maxHardwareVersionKnown) {
            try {
                final ShellResult version = shell.run("vmware -v");
                maxHardwareVersion = EsxiHardwareVersion.maxFor(version.succeeded() ? version.getStdout() : null);
            } catch (VSphereException e) {
                LOGGER.log(Level.FINE, "Asking " + getLabel() + " for its version", e);
            }
            maxHardwareVersionKnown = true;
        }
        return EsxiHardwareVersion.lowerTo(vmx, maxHardwareVersion);
    }

    /**
     * The host as a candidate for a placement: its size, and what is used of it now, as the host itself says (see
     * {@link EsxiHostStats}). A host that does not say what is used is not ranked.
     */
    VSphereHostSelection.HostCandidate candidate() throws VSphereException {
        return EsxiHostStats.parse(
                        vim(VIM_CMD + " hostsvc/hostsummary").stdoutOrThrow("Asking the host how busy it is"))
                .asCandidate(getLabel());
    }

    /**
     * Does what is asked of a VM unless that would break linked clones of it, see {@link EsxiCloneGuard}: then the
     * task has failed, with the reason.
     */
    EsxiTask guardedTask(String action, VmEntry vm, java.util.function.Supplier<EsxiTask> task) {
        try {
            final String refusal = EsxiCloneGuard.check(this, vm, action + " " + vm.getName());
            if (refusal != null) {
                return new EsxiTask("guard", refusal);
            }
        } catch (VSphereException e) {
            return new EsxiTask(
                    "guard", "Could not check whether linked clones depend on " + vm.getName() + ": " + e.getMessage());
        }
        return task.get();
    }

    @Override
    protected void closeSession() {
        shell.close();
    }

    @Override
    public String hostNameOf(VirtualMachine vm) {
        return vm == null ? null : getLabel();
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

    @Override
    public boolean shouldBeCheckedWhenAcquired() {
        return true;
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
        config.setTemplate(readConfigInfo(vm).isTemplate());
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

    /** The number the host knows the snapshot of the VM with this name by, or null if there is none. */
    String snapshotIdByName(VmEntry vm, String name) throws VSphereException {
        final VirtualMachineSnapshotInfo info = readSnapshotInfo(vm);
        if (info == null || info.getRootSnapshotList() == null) {
            return null;
        }
        final ManagedObjectReference mor = findSnapshotInTree(info.getRootSnapshotList(), name);
        return mor == null ? null : mor.getVal();
    }

    /**
     * Gives a snapshot another name and description, in the .vmsd file of the VM (a powered off one, as for any
     * change of its files). The host is told to read it again, and asked whether it now has the new name: if not, the
     * file is put back as it was.
     */
    void renameSnapshot(VmEntry vm, String snapshotId, String name, String description) throws VSphereException {
        if (name == null || name.isEmpty() || name.chars().anyMatch(c -> c < 0x20 && c != '\n' && c != '\t')) {
            throw new VSphereException("The new name of the snapshot is empty or has control characters in it");
        }
        final VirtualMachinePowerState state = readRuntime(vm).getPowerState();
        if (state != VirtualMachinePowerState.poweredOff) {
            throw new VSphereException("The VM has to be powered off for a snapshot of it to be renamed over SSH to an"
                    + " ESXi host, but it is " + state);
        }
        final EsxiDatastoreFiles files = new EsxiDatastoreFiles(shell);
        final String path = EsxiSnapshotMetadata.pathFor(vm.getVmxFileSystemPath());
        final String before = files.read(path);
        final EsxiSnapshotMetadata metadata = EsxiSnapshotMetadata.parse(before);
        final int index = metadata.indexOfUid(snapshotId);
        if (index < 0) {
            throw new VSphereException("The snapshot " + snapshotId + " is not in " + path);
        }
        metadata.rename(index, name, description);
        files.replace(path, metadata.toString());
        try {
            vim(vmCommand(vm, "reload")).stdoutOrThrow("Having the host read the snapshots of " + vm + " again");
            if (!name.equals(snapshotNameById(vm, snapshotId))) {
                throw new VSphereException("The host did not take the new name of the snapshot");
            }
        } catch (VSphereException e) {
            files.replace(path, before);
            try {
                vim(vmCommand(vm, "reload"));
            } catch (VSphereException again) {
                e.addSuppressed(again);
            }
            throw e;
        }
    }

    private String snapshotNameById(VmEntry vm, String id) throws VSphereException {
        final VirtualMachineSnapshotInfo info = readSnapshotInfo(vm);
        return info == null ? null : nameInTree(info.getRootSnapshotList(), id);
    }

    private static String nameInTree(VirtualMachineSnapshotTree[] trees, String id) {
        if (trees == null) {
            return null;
        }
        for (VirtualMachineSnapshotTree tree : trees) {
            if (tree.getSnapshot() != null && id.equals(tree.getSnapshot().getVal())) {
                return tree.getName();
            }
            final String found = nameInTree(tree.getChildSnapshotList(), id);
            if (found != null) {
                return found;
            }
        }
        return null;
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
            if (disks.after().size() > 0
                    || disks.before().stream().anyMatch(step -> step.kind == EsxiDiskChanges.Step.Kind.EXTEND)) {
                final String refusal = EsxiCloneGuard.check(this, vm, "change the disks of " + vm.getName());
                if (refusal != null) {
                    throw new VSphereException(refusal);
                }
            }
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
        final java.util.Set<String> readOnly = readOnlyShares();
        final List<EsxiDatastoreEntry> usable = new ArrayList<>();
        for (EsxiDatastoreEntry entry : all) {
            if (entry.isForVms() && entry.isMounted()) {
                usable.add(readOnly.contains(entry.getName()) ? entry.asReadOnly() : entry);
            }
        }
        return usable;
    }

    /** The NFS shares that are mounted read-only: nothing can be put on them. Those that cannot be told are not. */
    private java.util.Set<String> readOnlyShares() {
        final java.util.Set<String> names = new java.util.HashSet<>();
        for (String command : new String[] {"esxcli storage nfs list", "esxcli storage nfs41 list"}) {
            try {
                final ShellResult listing = shell.run(command);
                if (listing.succeeded()) {
                    names.addAll(EsxiNfsTable.readOnlyVolumes(listing.getStdout()));
                }
            } catch (VSphereException e) {
                LOGGER.log(Level.FINE, "Asking the host for its NFS shares", e);
            }
        }
        return names;
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

    // -- resource pools --

    /** The resource pools of the host, name to id, the top one ("Resources") included. */
    public Map<String, String> listResourcePools() throws VSphereException {
        final Map<String, String> pools = new LinkedHashMap<>();
        pools.put(EsxiResourcePool.ROOT_NAME, EsxiResourcePool.ROOT_ID);
        final ShellResult xml = shell.run("cat /etc/vmware/hostd/pools.xml");
        if (xml.succeeded()) {
            for (Map.Entry<String, String> pool :
                    EsxiResourcePool.parse(xml.getStdout()).entrySet()) {
                pools.putIfAbsent(pool.getKey(), pool.getValue());
            }
        }
        return pools;
    }

    /** The pool with the name, or null if the host has none like that. */
    public EsxiResourcePool getResourcePoolByName(String name) throws VSphereException {
        final String id = listResourcePools().get(EsxiResourcePool.isRoot(name) ? EsxiResourcePool.ROOT_NAME : name);
        return id == null
                ? null
                : new EsxiResourcePool(EsxiResourcePool.isRoot(name) ? EsxiResourcePool.ROOT_NAME : name, id);
    }

    /**
     * Makes a resource pool (below the top one, with expandable reservations and normal shares), unless there is
     * one by this name; returns it either way.
     */
    public EsxiResourcePool createResourcePool(String name) throws VSphereException {
        final EsxiResourcePool known = getResourcePoolByName(name);
        if (known != null) {
            return known;
        }
        EsxiDatastoreFiles.checkName("The name of the resource pool", name);
        final String output = vim(VIM_CMD
                        + " hostsvc/rsrc/create --cpu-min-expandable=true --cpu-shares=normal"
                        + " --mem-min-expandable=true --mem-shares=normal " + EsxiResourcePool.ROOT_ID + " "
                        + ShellQuote.quote(name))
                .stdoutOrThrow("Making the resource pool " + name);
        final Matcher id =
                Pattern.compile("vim\\.ResourcePool:([A-Za-z0-9._-]+)").matcher(output);
        if (!id.find()) {
            throw new VSphereException(
                    "The host did not say what the id of the resource pool " + name + " is: " + output.trim());
        }
        return new EsxiResourcePool(name, id.group(1));
    }

    /** Removes the resource pool with the name (not the top one); VMs in it go to the top one. */
    public void deleteResourcePool(String name) throws VSphereException {
        if (EsxiResourcePool.isRoot(name)) {
            throw new EsxiConstraintException("The top resource pool of a host cannot be deleted");
        }
        final EsxiResourcePool pool = getResourcePoolByName(name);
        if (pool == null) {
            throw new VSphereNotFoundException("Resource pool", name);
        }
        vim(VIM_CMD + " hostsvc/rsrc/destroy " + ShellQuote.quote(pool.getId()))
                .stdoutOrThrow("Removing the resource pool " + name);
    }

    /** The pool a clone is to be put in: made, if the host has none by this name (the scripts did that too). */
    private EsxiResourcePool poolForClone(String name, PrintStream log) throws VSphereException {
        EsxiResourcePool pool = getResourcePoolByName(name);
        if (pool == null) {
            if (log != null) {
                VSphereLogger.vsLogger(log, "Making the resource pool \"" + name + "\", which the host does not have");
            }
            pool = createResourcePool(name);
        }
        return pool;
    }

    /**
     * The resource pool a VM is in: {@code pools.xml} has an entry for each VM saying so. A VM it has none for is
     * taken to be in the top pool.
     */
    EsxiResourcePool resourcePoolOf(VmEntry vm) throws VSphereException {
        final ShellResult xml = shell.run("cat /etc/vmware/hostd/pools.xml");
        final String id = xml.succeeded() ? EsxiResourcePool.poolIdOfVm(xml.getStdout(), vm.getId()) : null;
        final String wanted = id == null ? EsxiResourcePool.ROOT_ID : id;
        for (Map.Entry<String, String> pool : listResourcePools().entrySet()) {
            if (pool.getValue().equals(wanted)) {
                return new EsxiResourcePool(pool.getKey(), wanted);
            }
        }
        return new EsxiResourcePool(wanted, wanted);
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
        refuse("choosing a host", hostName);
        refuse("choosing a host", hostSelectionMode);
        if (hostSelectionCandidates != null && !hostSelectionCandidates.isEmpty()) {
            refuse("choosing a host", hostSelectionCandidates.toString());
        }
        cloneFrom(
                null,
                null,
                cloneName,
                sourceName,
                linkedClone,
                resourcePoolName,
                cluster,
                datastoreName,
                folderName,
                useCurrentSnapshot,
                namedSnapshot,
                powerOn,
                extraConfigParameters,
                customizationSpec,
                vmSize,
                jLogger);
    }

    /**
     * Makes the clone on this host. The master is registered here, or, if a cluster has this host make the clone
     * and the master is on another host, there ({@code sourceHost}, with {@code master} as it knows it): the files
     * that are copied and linked to are those of a datastore that the hosts share.
     */
    void cloneFrom(
            @CheckForNull VSphereEsxiSsh sourceHost,
            @CheckForNull VmEntry master,
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
            VmSize vmSize,
            PrintStream jLogger)
            throws VSphereException {
        refuse("a customization specification", customizationSpec);
        ignored(jLogger, "cluster", cluster);
        ignored(jLogger, "folder", folderName);
        final String resourcePoolId = EsxiResourcePool.isRoot(resourcePoolName)
                ? null
                : poolForClone(resourcePoolName.trim(), jLogger).getId();
        final EsxiVmCloner cloner = new EsxiVmCloner(this, new EsxiDatastoreFiles(shell), jLogger);
        if (master != null) {
            cloner.fromOtherHost(sourceHost, master);
        }
        cloner.clone(
                cloneName,
                sourceName,
                linkedClone,
                datastoreName,
                powerOn,
                extraConfigParameters,
                vmSize,
                namedSnapshot,
                resourcePoolId,
                useCurrentSnapshot);
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

    /**
     * Runs a command that can take as long as it takes, such as the copy of a large disk: there is no limit to its
     * time (as there is for the other commands), it is given up on when it has printed nothing for the idle time.
     */
    void runUntilIdle(String command, String what, int idleSeconds) throws VSphereException {
        final ShellResult result = shell.stream(command, null, null, idleSeconds);
        if (!result.succeeded()) {
            final String why = result.getStderr().trim();
            throw new VSphereException(
                    what + " failed (exit code " + result.getExitCode() + ")" + (why.isEmpty() ? "" : ": " + why));
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

    /**
     * "Template" is a mark in the configuration of the VM here ({@code template = "TRUE"} in the .vmx), as a
     * standalone host has no kind of VM of that name: the plugin does not start a VM that has the mark (it says it
     * represents a template), and deploying from it makes a VM that has not. Like vCenter, this needs the VM to be
     * powered off (or powers it off, if forced).
     */
    @Override
    public void markAsTemplate(String vmName, String snapName, boolean force) throws VSphereException {
        final EsxiVirtualMachine vm = (EsxiVirtualMachine) getVmByName(vmName);
        if (vm == null) {
            throw new VSphereNotFoundException("VM", vmName);
        }
        if (vm.getConfig().template) {
            return;
        }
        if (!isPoweredOff(vm)) {
            if (!force) {
                throw new VSphereException("Could not mark as Template. Check its power state or select \"force\".");
            }
            powerOffVm(vm, true, 0);
        }
        setTemplateMark(vm.getEntry(), true);
    }

    /**
     * The VM stops being a template. A standalone host has no cluster, and a resource pool is not used to put it
     * in one (the VM stays in the pool it is in).
     */
    @Override
    public void markAsVm(String name, String resourcePool, String cluster) throws VSphereException {
        final EsxiVirtualMachine vm = (EsxiVirtualMachine) getVmByName(name);
        if (vm == null) {
            throw new VSphereNotFoundException("VM", name);
        }
        if (vm.getConfig().template) {
            setTemplateMark(vm.getEntry(), false);
        }
    }

    private void setTemplateMark(VmEntry vm, boolean template) throws VSphereException {
        final EsxiDatastoreFiles files = new EsxiDatastoreFiles(shell);
        final VmxFile vmx = VmxFile.parse(files.read(vm.getVmxFileSystemPath()));
        if (template) {
            vmx.put("template", "TRUE");
        } else {
            vmx.remove("template");
        }
        files.replace(vm.getVmxFileSystemPath(), vmx.toString());
        vim(vmCommand(vm, "reload")).stdoutOrThrow("Having the host read the configuration of " + vm + " again");
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
