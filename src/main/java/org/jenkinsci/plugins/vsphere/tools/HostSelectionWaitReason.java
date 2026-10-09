package org.jenkinsci.plugins.vsphere.tools;

/**
 * Why automatic host selection found no host to place a new VM on at the moment, which is when {@code
 * hostSelectionWaitSeconds} comes into play. Passed to a pipeline's {@code hostSelectionWaitNotification}
 * so that it can tell a situation that is likely to pass by itself (the hosts are merely busy) from one
 * that needs somebody's attention (see {@link #isTransient()}).
 */
public enum HostSelectionWaitReason {

    /** Every host is disconnected, in maintenance mode, or not among the candidate hosts. */
    NO_USABLE_HOSTS(false, false),

    /** No usable host has enough physical cores or RAM for the VM, as the sizing requirements ask. */
    NO_HOST_FITS_VM_SIZE(false, false),

    /** No candidate host reports CPU/memory usage, so they cannot be told apart by load. */
    NO_USAGE_STATISTICS(false, false),

    /** Hosts big enough for the VM exist, but none has the VM's memory size free right now. */
    NO_HOST_WITH_FREE_RAM_FOR_VM(true, false),

    /** Hosts are usable, but every one has less free CPU/RAM than the configured limits demand. */
    BELOW_FREE_RESOURCE_LIMITS(true, true);

    private final boolean transientReason;
    private final boolean failsWhenGivingUp;

    HostSelectionWaitReason(boolean transientReason, boolean failsWhenGivingUp) {
        this.transientReason = transientReason;
        this.failsWhenGivingUp = failsWhenGivingUp;
    }

    /**
     * True if the situation is assumed to pass by itself as the running workloads come and go (hosts
     * are busy at the moment); false if it is assumed to persist until somebody changes something
     * (hosts in maintenance, hosts that do not report usage, hosts too small for the VM).
     */
    public boolean isTransient() {
        return transientReason;
    }

    /** The opposite of {@link #isTransient()}. */
    public boolean isPersistent() {
        return !transientReason;
    }

    /**
     * What happens if the wait is not enabled or runs out: true if the clone/deploy then fails (the free
     * resource limits were set to be respected, so vCenter must not place the VM regardless), false if
     * it is left to vCenter to place the VM, as it always was in the other situations.
     */
    public boolean failsWhenGivingUp() {
        return failsWhenGivingUp;
    }
}
