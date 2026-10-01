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
    private final @CheckForNull Integer vmCpus;
    private final @CheckForNull Long vmMemoryMB;

    public HostSelectionOptions(boolean requireCores, boolean requireMemory) {
        this(requireCores, requireMemory, null, null);
    }

    private HostSelectionOptions(
            boolean requireCores, boolean requireMemory, @CheckForNull Integer vmCpus, @CheckForNull Long vmMemoryMB) {
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
        return new HostSelectionOptions(requireCores, requireMemory, vmCpus, vmMemoryMB);
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

    /** Drop-down for a tri-state call-site setting: inherit the cloud's default, or force yes/no. */
    public static ListBoxModel triStateItems() {
        ListBoxModel items = new ListBoxModel();
        items.add("(inherit the cloud's default)", "");
        items.add("Yes", "true");
        items.add("No", "false");
        return items;
    }
}
