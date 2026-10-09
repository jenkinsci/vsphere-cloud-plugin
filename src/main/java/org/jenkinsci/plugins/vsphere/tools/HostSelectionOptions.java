package org.jenkinsci.plugins.vsphere.tools;

import edu.umd.cs.findbugs.annotations.CheckForNull;
import hudson.util.ListBoxModel;

/**
 * Optional refinements for automatic host selection (see {@link VSphereHostSelection}), already
 * resolved from cloud-level defaults and call-site overrides, so {@link VSphere} only has to apply
 * them.
 */
public final class HostSelectionOptions {

    /** No refinements: the original behaviour. */
    public static final HostSelectionOptions NONE = new HostSelectionOptions(false, false);

    private final boolean requireCores;
    private final boolean requireMemory;
    private final boolean requireAvailableMemory;
    private final @CheckForNull Integer vmCpus;
    private final @CheckForNull Long vmMemoryMB;
    private final HostWeights weights;
    private final boolean folderFollowsHost;

    public HostSelectionOptions(boolean requireCores, boolean requireMemory) {
        this(requireCores, requireMemory, false);
    }

    public HostSelectionOptions(boolean requireCores, boolean requireMemory, boolean requireAvailableMemory) {
        this(requireCores, requireMemory, requireAvailableMemory, null, null, HostWeights.DEFAULT, false);
    }

    private HostSelectionOptions(
            boolean requireCores,
            boolean requireMemory,
            boolean requireAvailableMemory,
            @CheckForNull Integer vmCpus,
            @CheckForNull Long vmMemoryMB,
            HostWeights weights,
            boolean folderFollowsHost) {
        this.requireAvailableMemory = requireAvailableMemory;
        this.folderFollowsHost = folderFollowsHost;
        this.weights = weights == null ? HostWeights.DEFAULT : weights;
        this.requireCores = requireCores;
        this.requireMemory = requireMemory;
        this.vmCpus = vmCpus;
        this.vmMemoryMB = vmMemoryMB;
    }

    /**
     * Same options, but telling the host size checks to compare against this vCPU count and
     * memory size ({@code null}: unknown, use the source VM's) instead of the source VM's own
     * - for VMs that are known to be resized right after being cloned.
     */
    public HostSelectionOptions withVmSize(@CheckForNull Integer vmCpus, @CheckForNull Long vmMemoryMB) {
        return new HostSelectionOptions(
                requireCores, requireMemory, requireAvailableMemory, vmCpus, vmMemoryMB, weights, folderFollowsHost);
    }

    /** Same options, ranking the candidate hosts with these weights. */
    public HostSelectionOptions withWeights(@CheckForNull HostWeights weights) {
        return new HostSelectionOptions(
                requireCores, requireMemory, requireAvailableMemory, vmCpus, vmMemoryMB, weights, folderFollowsHost);
    }

    /**
     * Same options, but if no folder is given for the clone, put it in the folder of its source with the
     * source's host name replaced by the name of the host chosen for the clone (see {@link HostFolderPath}),
     * if there is such a folder.
     */
    public HostSelectionOptions withFolderFollowsHost(boolean folderFollowsHost) {
        return new HostSelectionOptions(
                requireCores, requireMemory, requireAvailableMemory, vmCpus, vmMemoryMB, weights, folderFollowsHost);
    }

    /** Whether the clone's default folder follows the host chosen for it. */
    public boolean isFolderFollowsHost() {
        return folderFollowsHost;
    }

    /** What "most available host" means; {@link HostWeights#DEFAULT} for the original ranking. */
    public HostWeights getWeights() {
        return weights;
    }

    /** vCPU count the VM will end up with, if known ahead of cloning; else null. */
    public @CheckForNull Integer getVmCpus() {
        return vmCpus;
    }

    /** Memory size in MB the VM will end up with, if known ahead of cloning; else null. */
    public @CheckForNull Long getVmMemoryMB() {
        return vmMemoryMB;
    }

    /** Only consider hosts with at least as many physical cores as the VM has vCPUs. */
    public boolean isRequireCores() {
        return requireCores;
    }

    /**
     * Only consider hosts that currently have at least as much memory <em>free</em> as the VM is
     * configured with, so the new VM does not push the host into swapping.
     */
    public boolean isRequireAvailableMemory() {
        return requireAvailableMemory;
    }

    /** Only consider hosts with at least as much physical RAM as the VM is configured with. */
    public boolean isRequireMemory() {
        return requireMemory;
    }

    /**
     * Resolves a tri-state call-site setting against the cloud-wide default: {@code null}
     * (never set at the call site) inherits {@code cloudDefault}; an explicit {@code TRUE} or
     * {@code FALSE} wins outright.
     */
    public static boolean resolve(boolean cloudDefault, @CheckForNull Boolean override) {
        return override != null ? override : cloudDefault;
    }

    /**
     * The form representation of a tri-state setting: "" for unset (inherit), else "true"/"false".
     * Needed because Stapler binds an empty form value to an explicit {@code false} for a {@code
     * Boolean} property, which would silently override the cloud's default instead of inheriting it.
     */
    public static String triStateToString(@CheckForNull Boolean value) {
        return value == null ? "" : value.toString();
    }

    /** Inverse of {@link #triStateToString}: blank or anything unrecognised means unset (inherit). */
    public static @CheckForNull Boolean triStateFromString(@CheckForNull String value) {
        if (value == null) {
            return null;
        }
        switch (value.trim().toLowerCase(java.util.Locale.ROOT)) {
            case "true":
                return Boolean.TRUE;
            case "false":
                return Boolean.FALSE;
            default:
                return null;
        }
    }

    /** Drop-down for a tri-state call-site setting: inherit the cloud's default, or force yes/no. */
    public static ListBoxModel triStateItems() {
        ListBoxModel items = new ListBoxModel();
        items.add("(inherit the cloud's default)", "");
        items.add("Yes", "true");
        items.add("No", "false");
        return items;
    }
}
