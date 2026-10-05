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

import com.vmware.vim25.ManagedObjectReference;
import com.vmware.vim25.VirtualMachineCloneSpec;
import com.vmware.vim25.VirtualMachineConfigSpec;
import com.vmware.vim25.VirtualMachineMovePriority;
import com.vmware.vim25.VirtualMachinePowerState;
import com.vmware.vim25.VirtualMachineRelocateSpec;
import com.vmware.vim25.mo.Datastore;
import com.vmware.vim25.mo.Folder;
import com.vmware.vim25.mo.HostSystem;
import com.vmware.vim25.mo.ResourcePool;
import com.vmware.vim25.mo.Task;
import com.vmware.vim25.mo.VirtualMachine;

/**
 * A VM on a standalone ESXi host, as the rest of the plugin sees VMs: the things that are looked up in it are
 * answered from what the host says ({@code vim-cmd} and the {@code .vmx} file), and the things that are done to
 * it are done by {@code vim-cmd} over SSH, instead of by calls to the vSphere API.
 *
 * <p>All the properties of a VM go through {@link #getCurrentProperty(String)}, so only the ones that something
 * looks at are provided; asking for another one is an error that says so, not a silent null.
 */
public final class EsxiVirtualMachine extends VirtualMachine {

    private final VSphereEsxiSsh host;
    private final VmEntry entry;

    EsxiVirtualMachine(VSphereEsxiSsh host, VmEntry entry) {
        super(null, reference(entry));
        this.host = host;
        this.entry = entry;
    }

    private static ManagedObjectReference reference(VmEntry entry) {
        final ManagedObjectReference mor = new ManagedObjectReference();
        mor.setType("VirtualMachine");
        mor.setVal(Integer.toString(entry.getId()));
        return mor;
    }

    /** What the host calls this VM by. */
    public int getVmId() {
        return entry.getId();
    }

    VmEntry getEntry() {
        return entry;
    }

    @Override
    protected Object getCurrentProperty(String propertyName) {
        try {
            switch (propertyName) {
                case "name":
                    return entry.getName();
                case "config":
                    return host.readConfigInfo(entry);
                case "runtime":
                    return host.readRuntime(entry);
                case "summary":
                    return host.readSummary(entry);
                case "guest":
                    return host.readGuest(entry);
                case "snapshot":
                    return host.readSnapshotInfo(entry);
                default:
                    throw new UnsupportedOperationException("The \"" + propertyName
                            + "\" property of a VM is not available from an ESXi host over SSH (yet)");
            }
        } catch (org.jenkinsci.plugins.vsphere.tools.VSphereException e) {
            throw new IllegalStateException(e.getMessage(), e);
        }
    }

    @Override
    public Task powerOnVM_Task(HostSystem host) {
        return this.host.vmTask("powerOn", entry, "power.on");
    }

    @Override
    public Task powerOffVM_Task() {
        return host.vmTask("powerOff", entry, "power.off");
    }

    @Override
    public Task suspendVM_Task() {
        return host.vmTask("suspend", entry, "power.suspend");
    }

    @Override
    public Task resetVM_Task() {
        return host.vmTask("reset", entry, "power.reset");
    }

    @Override
    public void shutdownGuest() {
        host.vmCommandOrThrow("shutting down the guest of", entry, "power.shutdown");
    }

    @Override
    public Task destroy_Task() {
        return host.vmTask("destroy", entry, "destroy");
    }

    @Override
    public Task createSnapshot_Task(String name, String description, boolean memory, boolean quiesce) {
        return host.createSnapshotTask(entry, name, description, memory, quiesce);
    }

    @Override
    public Task removeAllSnapshots_Task() {
        return host.vmTask("removeAllSnapshots", entry, "snapshot.removeall");
    }

    @Override
    public Task reconfigVM_Task(VirtualMachineConfigSpec spec) {
        return host.reconfigureTask("reconfigure", entry, spec);
    }

    @Override
    public Task rename_Task(String newName) {
        final VirtualMachineConfigSpec spec = new VirtualMachineConfigSpec();
        spec.setName(newName);
        return host.reconfigureTask("rename", entry, spec);
    }

    /** The datastores that hold the files of the VM. */
    @Override
    public Datastore[] getDatastores() {
        try {
            return host.datastoresOf(entry).toArray(new Datastore[0]);
        } catch (org.jenkinsci.plugins.vsphere.tools.VSphereException e) {
            throw new IllegalStateException(e.getMessage(), e);
        }
    }

    private static EsxiConstraintUnsupportedOperationException migration(String what) {
        return new EsxiConstraintUnsupportedOperationException(what
                + " is not applicable to a standalone ESXi host: there is no other host to move a VM to or to"
                + " clone it on (that takes vCenter)");
    }

    @Override
    public Task migrateVM_Task(
            ResourcePool pool, HostSystem host, VirtualMachineMovePriority priority, VirtualMachinePowerState state) {
        throw migration("Migrating a VM");
    }

    @Override
    public Task relocateVM_Task(VirtualMachineRelocateSpec spec) {
        throw migration("Relocating a VM");
    }

    @Override
    public Task relocateVM_Task(VirtualMachineRelocateSpec spec, VirtualMachineMovePriority priority) {
        throw migration("Relocating a VM");
    }

    @Override
    public Task cloneVM_Task(Folder folder, String name, VirtualMachineCloneSpec spec) {
        throw new EsxiConstraintUnsupportedOperationException(
                "Cloning through the API is not applicable to a standalone ESXi host: cloning is done on its files"
                        + " (use the clone and deploy operations of the plugin)");
    }

    @Override
    public String toString() {
        return entry.toString();
    }
}
