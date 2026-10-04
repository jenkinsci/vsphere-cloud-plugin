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
import com.vmware.vim25.VirtualMachineConfigInfo;
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
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.logging.Level;
import org.jenkinsci.plugins.vsphere.tools.AbstractVSphere;
import org.jenkinsci.plugins.vsphere.tools.HostSelectionOptions;
import org.jenkinsci.plugins.vsphere.tools.VSphereException;
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
    private ShellResult vim(String command) throws VSphereException {
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
        return new EsxiVirtualMachineSnapshot(mor);
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
        return EsxiConfigInfo.build(vm, VmxFile.parse(vmx));
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

    private static VSphereException unsupported(String operation) {
        return new VSphereException(
                operation + " is not supported by the ESXi SSH backend (it needs vCenter, or is not implemented yet)");
    }

    // -- what needs vCenter, or is not there yet --

    @Override
    public void deployVm(
            String cloneName,
            String sourceName,
            boolean linkedClone,
            String resourcePoolName,
            String cluster,
            String datastoreName,
            String folderName,
            boolean powerOn,
            String customizationSpec,
            PrintStream jLogger)
            throws VSphereException {
        throw unsupported("deployVm");
    }

    @Override
    public void deployVm(
            String cloneName,
            String sourceName,
            boolean linkedClone,
            String resourcePoolName,
            String cluster,
            String datastoreName,
            String folderName,
            boolean powerOn,
            String customizationSpec,
            String host,
            String hostSelectionMode,
            Set<String> hostSelectionCandidates,
            PrintStream jLogger)
            throws VSphereException {
        throw unsupported("deployVm");
    }

    @Override
    public void deployVm(
            String cloneName,
            String sourceName,
            boolean linkedClone,
            String resourcePoolName,
            String cluster,
            String datastoreName,
            String folderName,
            boolean powerOn,
            String customizationSpec,
            String host,
            String hostSelectionMode,
            Set<String> hostSelectionCandidates,
            HostSelectionOptions hostSelectionOptions,
            PrintStream jLogger)
            throws VSphereException {
        throw unsupported("deployVm");
    }

    @Override
    public void deployVm(
            String cloneName,
            String sourceName,
            boolean linkedClone,
            String resourcePoolName,
            String cluster,
            String datastoreName,
            String folderName,
            boolean powerOn,
            String customizationSpec,
            String host,
            String hostSelectionMode,
            Set<String> hostSelectionCandidates,
            HostSelectionOptions hostSelectionOptions,
            VmSize vmSize,
            PrintStream jLogger)
            throws VSphereException {
        throw unsupported("deployVm");
    }

    @Override
    public void cloneVm(
            String cloneName,
            String sourceName,
            boolean linkedClone,
            String resourcePoolName,
            String cluster,
            String datastoreName,
            String folderName,
            boolean powerOn,
            String customizationSpec,
            PrintStream jLogger)
            throws VSphereException {
        throw unsupported("cloneVm");
    }

    @Override
    public void cloneVm(
            String cloneName,
            String sourceName,
            boolean linkedClone,
            String resourcePoolName,
            String cluster,
            String datastoreName,
            String folderName,
            boolean powerOn,
            String customizationSpec,
            String host,
            String hostSelectionMode,
            Set<String> hostSelectionCandidates,
            PrintStream jLogger)
            throws VSphereException {
        throw unsupported("cloneVm");
    }

    @Override
    public void cloneVm(
            String cloneName,
            String sourceName,
            boolean linkedClone,
            String resourcePoolName,
            String cluster,
            String datastoreName,
            String folderName,
            boolean powerOn,
            String customizationSpec,
            String host,
            String hostSelectionMode,
            Set<String> hostSelectionCandidates,
            HostSelectionOptions hostSelectionOptions,
            PrintStream jLogger)
            throws VSphereException {
        throw unsupported("cloneVm");
    }

    @Override
    public void cloneVm(
            String cloneName,
            String sourceName,
            boolean linkedClone,
            String resourcePoolName,
            String cluster,
            String datastoreName,
            String folderName,
            boolean powerOn,
            String customizationSpec,
            String host,
            String hostSelectionMode,
            Set<String> hostSelectionCandidates,
            HostSelectionOptions hostSelectionOptions,
            VmSize vmSize,
            PrintStream jLogger)
            throws VSphereException {
        throw unsupported("cloneVm");
    }

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
            PrintStream jLogger)
            throws VSphereException {
        throw unsupported("cloneOrDeployVm");
    }

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
            String host,
            String hostSelectionMode,
            Set<String> hostSelectionCandidates,
            PrintStream jLogger)
            throws VSphereException {
        throw unsupported("cloneOrDeployVm");
    }

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
            String host,
            String hostSelectionMode,
            Set<String> hostSelectionCandidates,
            HostSelectionOptions hostSelectionOptions,
            PrintStream jLogger)
            throws VSphereException {
        throw unsupported("cloneOrDeployVm");
    }

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
            String host,
            String hostSelectionMode,
            Set<String> hostSelectionCandidates,
            HostSelectionOptions hostSelectionOptions,
            VmSize vmSize,
            PrintStream jLogger)
            throws VSphereException {
        throw unsupported("cloneOrDeployVm");
    }

    @Override
    public boolean hostExists(final String hostName) throws VSphereException {
        throw unsupported("hostExists");
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
        throw unsupported("folderExists");
    }

    @Override
    public Folder getFolder(String folderPath) throws VSphereException {
        throw unsupported("getFolder");
    }

    @Override
    public CustomizationSpecItem getCustomizationSpecByName(final String customizationSpecName)
            throws VSphereException {
        throw unsupported("getCustomizationSpecByName");
    }

    @Override
    public ManagedEntity[] getDatastores() throws VSphereException {
        throw unsupported("getDatastores");
    }

    @Override
    public Network getNetworkPortGroupByName(VirtualMachine virtualMachine, String name) throws VSphereException {
        throw unsupported("getNetworkPortGroupByName");
    }

    @Override
    public DistributedVirtualPortgroup getDistributedVirtualPortGroupByName(VirtualMachine virtualMachine, String name)
            throws VSphereException {
        throw unsupported("getDistributedVirtualPortGroupByName");
    }

    @Override
    public DistributedVirtualSwitch getDistributedVirtualSwitchByPortGroup(
            DistributedVirtualPortgroup distributedVirtualPortgroup) throws VSphereException {
        throw unsupported("getDistributedVirtualSwitchByPortGroup");
    }
}
