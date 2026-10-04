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
import com.vmware.vim25.VirtualMachineConfigSpec;
import com.vmware.vim25.mo.HostSystem;
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
        return new EsxiTask("reconfigure", "Reconfiguring a VM is not supported by the ESXi SSH backend (yet)");
    }

    @Override
    public Task rename_Task(String newName) {
        return new EsxiTask("rename", "Renaming a VM is not supported by the ESXi SSH backend (yet)");
    }

    @Override
    public String toString() {
        return entry.toString();
    }
}
