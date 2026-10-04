package org.jenkinsci.plugins.vsphere.tools.esxi;

import com.vmware.vim25.ManagedObjectReference;
import com.vmware.vim25.mo.HostSystem;
import com.vmware.vim25.mo.Task;
import com.vmware.vim25.mo.VirtualMachineSnapshot;

/**
 * A snapshot of a VM on a standalone ESXi host. Taking snapshots is supported by {@link EsxiVirtualMachine};
 * acting on an existing one (revert, remove, rename) is not yet, and says so.
 */
public final class EsxiVirtualMachineSnapshot extends VirtualMachineSnapshot {

    private static final String NOT_YET = " of a snapshot is not supported by the ESXi SSH backend (yet)";

    EsxiVirtualMachineSnapshot(ManagedObjectReference mor) {
        super(null, mor);
    }

    @Override
    public Task revertToSnapshot_Task(HostSystem host) {
        return new EsxiTask("revertToSnapshot", "Reverting" + NOT_YET);
    }

    @Override
    public Task revertToSnapshot_Task(HostSystem host, Boolean suppressPowerOn) {
        return new EsxiTask("revertToSnapshot", "Reverting" + NOT_YET);
    }

    @Override
    public Task removeSnapshot_Task(boolean removeChildren) {
        return new EsxiTask("removeSnapshot", "Removing" + NOT_YET);
    }

    @Override
    public Task removeSnapshot_Task(boolean removeChildren, Boolean consolidate) {
        return new EsxiTask("removeSnapshot", "Removing" + NOT_YET);
    }

    /** The default one asks the (missing) connection for its URL. */
    @Override
    public String toString() {
        return "snapshot " + getMOR().getVal();
    }

    @Override
    public void rename(String name, String description) {
        throw new UnsupportedOperationException("Renaming" + NOT_YET);
    }
}
