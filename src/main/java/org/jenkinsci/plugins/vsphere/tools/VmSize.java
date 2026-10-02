package org.jenkinsci.plugins.vsphere.tools;

import com.vmware.vim25.ResourceAllocationInfo;
import com.vmware.vim25.VirtualMachineConfigSpec;
import edu.umd.cs.findbugs.annotations.CheckForNull;

/**
 * The optional CPU and memory settings a VM is to be created with, in the same operation that
 * creates it, instead of being reconfigured afterwards. The properties mean the same as those of
 * the {@code ReconfigureCpu} and {@code ReconfigureMemory} steps; a null one keeps the source's.
 */
public final class VmSize {

    /** Nothing set: the VM keeps the source's CPU and memory configuration. */
    public static final VmSize NONE = new VmSize(null, null, null, null);

    private final @CheckForNull Integer cpuCores;
    private final @CheckForNull Integer coresPerSocket;
    private final @CheckForNull Integer cpuLimitMHz;
    private final @CheckForNull Integer memorySize;

    private VmSize(
            @CheckForNull Integer cpuCores,
            @CheckForNull Integer coresPerSocket,
            @CheckForNull Integer cpuLimitMHz,
            @CheckForNull Integer memorySize) {
        this.cpuCores = cpuCores;
        this.coresPerSocket = coresPerSocket;
        this.cpuLimitMHz = cpuLimitMHz;
        this.memorySize = memorySize;
    }

    /**
     * Builds a size from the (already variable-expanded) text of the four settings; each may be
     * null or blank, meaning "keep the source's".
     *
     * @throws VSphereException if a setting is given but is not a positive whole number
     */
    public static VmSize of(
            @CheckForNull String cpuCores,
            @CheckForNull String coresPerSocket,
            @CheckForNull String cpuLimitMHz,
            @CheckForNull String memorySize)
            throws VSphereException {
        return new VmSize(
                parseOptionalPositive("cpuCores", cpuCores),
                parseOptionalPositive("coresPerSocket", coresPerSocket),
                parseOptionalPositive("cpuLimitMHz", cpuLimitMHz),
                parseOptionalPositive("memorySize", memorySize));
    }

    /**
     * Parses an optional, already variable-expanded positive whole number.
     *
     * @param what name of the setting for the error message
     * @return the number, or null if {@code value} is null or blank (meaning: keep the source's)
     * @throws VSphereException if it is set but is not a positive whole number
     */
    public static @CheckForNull Integer parseOptionalPositive(String what, @CheckForNull String value)
            throws VSphereException {
        if (value == null || value.trim().isEmpty()) {
            return null;
        }
        try {
            int parsed = Integer.parseInt(value.trim());
            if (parsed > 0) {
                return parsed;
            }
        } catch (NumberFormatException e) {
            // reported below
        }
        throw new VSphereException(what + " must be a positive whole number, but is \"" + value + "\"");
    }

    /** Number of vCPUs (as {@code ReconfigureCpu}'s {@code cpuCores}), or null to keep the source's. */
    public @CheckForNull Integer getCpuCores() {
        return cpuCores;
    }

    /** Cores per socket, or null to keep the source's. */
    public @CheckForNull Integer getCoresPerSocket() {
        return coresPerSocket;
    }

    /** CPU reservation in MHz (as {@code ReconfigureCpu}'s {@code cpuLimitMHz}), or null for none. */
    public @CheckForNull Integer getCpuLimitMHz() {
        return cpuLimitMHz;
    }

    /** Memory size in MB (as {@code ReconfigureMemory}'s {@code memorySize}), or null to keep the source's. */
    public @CheckForNull Integer getMemorySize() {
        return memorySize;
    }

    public boolean isEmpty() {
        return cpuCores == null && coresPerSocket == null && cpuLimitMHz == null && memorySize == null;
    }

    /**
     * vCenter refuses a vCPU count that is not a multiple of the cores per socket. Checks that
     * with what the VM will end up with: the settings here, else the source's.
     *
     * @throws VSphereException if they do not fit together
     */
    public void validateAgainstSource(@CheckForNull Integer sourceCpus, @CheckForNull Integer sourceCoresPerSocket)
            throws VSphereException {
        if (cpuCores == null && coresPerSocket == null) {
            return;
        }
        final Integer cpus = cpuCores != null ? cpuCores : sourceCpus;
        final Integer perSocket = coresPerSocket != null ? coresPerSocket : sourceCoresPerSocket;
        if (cpus != null && perSocket != null && perSocket > 0 && cpus % perSocket != 0) {
            throw new VSphereException("The number of vCPUs (" + cpus
                    + (cpuCores == null ? ", kept from the source" : "")
                    + ") must be a multiple of the cores per socket (" + perSocket
                    + (coresPerSocket == null ? ", kept from the source" : "") + ")");
        }
    }

    /** Puts the settings that are set into a configuration spec, leaving the others untouched. */
    public void applyTo(VirtualMachineConfigSpec spec) {
        if (cpuCores != null) {
            spec.setNumCPUs(cpuCores);
        }
        if (coresPerSocket != null) {
            spec.setNumCoresPerSocket(coresPerSocket);
        }
        if (memorySize != null) {
            spec.setMemoryMB(Long.valueOf(memorySize));
        }
        if (cpuLimitMHz != null) {
            ResourceAllocationInfo allocation = new ResourceAllocationInfo();
            allocation.setReservation(Long.valueOf(cpuLimitMHz));
            spec.setCpuAllocation(allocation);
        }
    }

    /** For logging: what the VM will be created with. */
    public String describe() {
        StringBuilder sb = new StringBuilder();
        if (cpuCores != null) {
            sb.append(cpuCores).append(" vCPU(s)");
        }
        if (coresPerSocket != null) {
            sb.append(sb.length() > 0 ? ", " : "").append(coresPerSocket).append(" core(s) per socket");
        }
        if (memorySize != null) {
            sb.append(sb.length() > 0 ? ", " : "").append(memorySize).append(" MB of memory");
        }
        if (cpuLimitMHz != null) {
            sb.append(sb.length() > 0 ? ", " : "")
                    .append("a CPU reservation of ")
                    .append(cpuLimitMHz)
                    .append(" MHz");
        }
        return sb.toString();
    }
}
