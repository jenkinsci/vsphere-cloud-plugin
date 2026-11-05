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
import com.vmware.vim25.mo.HostSystem;
import com.vmware.vim25.mo.Task;
import com.vmware.vim25.mo.VirtualMachineSnapshot;

/**
 * A snapshot of a VM on a standalone ESXi host, which {@code vim-cmd} knows by a number. Reverting to it and
 * removing it are done by {@code vim-cmd}; renaming it is done on the .vmsd file.
 */
public final class EsxiVirtualMachineSnapshot extends VirtualMachineSnapshot {

    private final VSphereEsxiSsh host;
    private final VmEntry vm;

    EsxiVirtualMachineSnapshot(VSphereEsxiSsh host, VmEntry vm, ManagedObjectReference mor) {
        super(null, mor);
        this.host = host;
        this.vm = vm;
    }

    private static String yesNo(boolean value) {
        return value ? "yes" : "no";
    }

    @Override
    public Task revertToSnapshot_Task(HostSystem host) {
        return revertToSnapshot_Task(host, Boolean.FALSE);
    }

    /** The VM is powered on afterwards if the snapshot was taken while it ran (with its memory), unless suppressed. */
    @Override
    public Task revertToSnapshot_Task(HostSystem unused, Boolean suppressPowerOn) {
        return host.snapshotTask(
                "revertToSnapshot",
                vm,
                "snapshot.revert",
                getMOR().getVal(),
                yesNo(Boolean.TRUE.equals(suppressPowerOn)));
    }

    @Override
    public Task removeSnapshot_Task(boolean removeChildren) {
        return host.guardedTask(
                "remove the snapshot of the VM",
                vm,
                () -> host.snapshotTask(
                        "removeSnapshot", vm, "snapshot.remove", getMOR().getVal(), yesNo(removeChildren)));
    }

    /** The host always consolidates the disks of what it removes, so there is nothing to ask for. */
    @Override
    public Task removeSnapshot_Task(boolean removeChildren, Boolean consolidate) {
        return removeSnapshot_Task(removeChildren);
    }

    /** The default one asks the (missing) connection for its URL. */
    @Override
    public String toString() {
        return "snapshot " + getMOR().getVal();
    }

    /**
     * vim-cmd has no command for this, so the name is changed in the .vmsd file of the VM, which the host is then
     * told to read again.
     */
    @Override
    public void rename(String name, String description) {
        try {
            host.renameSnapshot(vm, getMOR().getVal(), name, description);
        } catch (org.jenkinsci.plugins.vsphere.tools.VSphereException e) {
            throw new IllegalStateException(e.getMessage(), e);
        }
    }
}
