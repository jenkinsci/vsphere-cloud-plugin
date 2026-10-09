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
    private final HostLimits limits;
    private final long waitSeconds;
    private final @CheckForNull Listener waitListener;
    private final boolean ignoreWaitListenerErrors;
    private final double scoreDeviation;
    private final boolean folderFollowsHost;

    public HostSelectionOptions(boolean requireCores, boolean requireMemory) {
        this(requireCores, requireMemory, false);
    }

    public HostSelectionOptions(boolean requireCores, boolean requireMemory, boolean requireAvailableMemory) {
        this(
                requireCores,
                requireMemory,
                requireAvailableMemory,
                null,
                null,
                HostWeights.DEFAULT,
                HostLimits.NONE,
                0,
                null,
                false,
                0,
                false);
    }

    private HostSelectionOptions(
            boolean requireCores,
            boolean requireMemory,
            boolean requireAvailableMemory,
            @CheckForNull Integer vmCpus,
            @CheckForNull Long vmMemoryMB,
            HostWeights weights,
            HostLimits limits,
            long waitSeconds,
            @CheckForNull Listener waitListener,
            boolean ignoreWaitListenerErrors,
            double scoreDeviation,
            boolean folderFollowsHost) {
        this.scoreDeviation = scoreDeviation;
        this.folderFollowsHost = folderFollowsHost;
        this.requireAvailableMemory = requireAvailableMemory;
        this.weights = weights == null ? HostWeights.DEFAULT : weights;
        this.limits = limits == null ? HostLimits.NONE : limits;
        this.waitListener = waitListener;
        this.ignoreWaitListenerErrors = ignoreWaitListenerErrors;
        this.waitSeconds = waitSeconds < 0 ? VSphereHostSelection.WAIT_FOREVER : waitSeconds;
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
                requireCores,
                requireMemory,
                requireAvailableMemory,
                vmCpus,
                vmMemoryMB,
                weights,
                limits,
                waitSeconds,
                waitListener,
                ignoreWaitListenerErrors,
                scoreDeviation,
                folderFollowsHost);
    }

    /** Same options, ranking the candidate hosts with these weights. */
    public HostSelectionOptions withWeights(@CheckForNull HostWeights weights) {
        return new HostSelectionOptions(
                requireCores,
                requireMemory,
                requireAvailableMemory,
                vmCpus,
                vmMemoryMB,
                weights,
                limits,
                waitSeconds,
                waitListener,
                ignoreWaitListenerErrors,
                scoreDeviation,
                folderFollowsHost);
    }

    /** Same options, keeping hosts with less free resources than these limits off the candidate list. */
    public HostSelectionOptions withLimits(@CheckForNull HostLimits limits) {
        return new HostSelectionOptions(
                requireCores,
                requireMemory,
                requireAvailableMemory,
                vmCpus,
                vmMemoryMB,
                weights,
                limits,
                waitSeconds,
                waitListener,
                ignoreWaitListenerErrors,
                scoreDeviation,
                folderFollowsHost);
    }

    /**
     * Same options, but if the limits (or nothing else) leave no host, wait up to this many seconds for
     * one to free up: 0 does not wait, a negative number ({@link VSphereHostSelection#WAIT_FOREVER}) waits
     * for as long as it takes.
     */
    public HostSelectionOptions withWaitSeconds(long waitSeconds) {
        return new HostSelectionOptions(
                requireCores,
                requireMemory,
                requireAvailableMemory,
                vmCpus,
                vmMemoryMB,
                weights,
                limits,
                waitSeconds,
                waitListener,
                ignoreWaitListenerErrors,
                scoreDeviation,
                folderFollowsHost);
    }

    /**
     * Same options, telling this listener whenever no host is available at the moment, right after that is
     * logged. For a pipeline's {@code hostSelectionWaitNotification}; it only lives as long as the call. If
     * the listener throws, the clone/deploy fails, unless {@code ignoreErrors}, when that is only logged.
     */
    public HostSelectionOptions withWaitListener(@CheckForNull Listener waitListener, boolean ignoreErrors) {
        return new HostSelectionOptions(
                requireCores,
                requireMemory,
                requireAvailableMemory,
                vmCpus,
                vmMemoryMB,
                weights,
                limits,
                waitSeconds,
                waitListener,
                ignoreErrors,
                scoreDeviation,
                folderFollowsHost);
    }

    /**
     * Same options, choosing at random among the hosts scoring within this fraction (0..1) of the best
     * one; a negative number always takes the single top host.
     */
    public HostSelectionOptions withScoreDeviation(double scoreDeviation) {
        return new HostSelectionOptions(
                requireCores,
                requireMemory,
                requireAvailableMemory,
                vmCpus,
                vmMemoryMB,
                weights,
                limits,
                waitSeconds,
                waitListener,
                ignoreWaitListenerErrors,
                scoreDeviation,
                folderFollowsHost);
    }

    /**
     * Same options, but if no folder is given for the clone, put it in the folder of its source with the
     * source's host name replaced by the name of the host chosen for the clone (see {@link HostFolderPath}),
     * if there is such a folder.
     */
    public HostSelectionOptions withFolderFollowsHost(boolean folderFollowsHost) {
        return new HostSelectionOptions(
                requireCores,
                requireMemory,
                requireAvailableMemory,
                vmCpus,
                vmMemoryMB,
                weights,
                limits,
                waitSeconds,
                waitListener,
                ignoreWaitListenerErrors,
                scoreDeviation,
                folderFollowsHost);
    }

    /** Whether the clone's default folder follows the host chosen for it. */
    public boolean isFolderFollowsHost() {
        return folderFollowsHost;
    }

    /** As {@link #withScoreDeviation}, but a null (not set at the call site) keeps the current value. */
    public HostSelectionOptions withScoreDeviationOverride(@CheckForNull Double scoreDeviation) {
        return scoreDeviation == null ? this : withScoreDeviation(scoreDeviation.doubleValue());
    }

    /**
     * How far below the best score a host may be and still be a candidate for the final random pick:
     * 0 (the default) only equally scored ones, up to 1 for all that have a score; above 1: any available host
     * at random, including those with no usage statistics; negative: always the single top host.
     */
    public double getScoreDeviation() {
        return scoreDeviation;
    }

    /** True if the deviation is above 1: any available host is chosen at random, whatever its load. */
    public boolean isPickAnyHostAtRandom() {
        return scoreDeviation > 1;
    }

    /**
     * Parses a call site's own deviation, already variable-expanded: blank means not set (null, inherit
     * the cloud's); else a number, where anything below 0 means always the top host and anything above 1
     * any available host at random.
     *
     * @throws VSphereException if it is not a number
     */
    public static @CheckForNull Double parseScoreDeviation(@CheckForNull String value) throws VSphereException {
        if (value == null || value.trim().isEmpty()) {
            return null;
        }
        try {
            final double parsed = Double.parseDouble(value.trim());
            if (Double.isNaN(parsed) || Double.isInfinite(parsed)) {
                throw new NumberFormatException(value);
            }
            return Double.valueOf(parsed < 0 ? -1d : parsed);
        } catch (NumberFormatException e) {
            throw new VSphereException("hostSelectionScoreDeviation must be a number between 0 and 1 (or negative"
                    + " to always take the top host, or above 1 to take any host at random), but is \"" + value + "\"");
        }
    }

    /** Told that host selection found no host to use at the moment; see {@link #withWaitListener}. */
    @FunctionalInterface
    public interface Listener {
        /**
         * @param message what was written to the build log about it, including what happens next
         * @param reason which situation this is
         */
        void hostSelectionWaiting(String message, HostSelectionWaitReason reason);
    }

    /** True if a failing {@link #getWaitListener() listener} is only logged, rather than failing the call. */
    public boolean isIgnoreWaitListenerErrors() {
        return ignoreWaitListenerErrors;
    }

    /** Who to tell when no host is available at the moment, or null. */
    public @CheckForNull Listener getWaitListener() {
        return waitListener;
    }

    /** Minimal free resources of a candidate host; {@link HostLimits#NONE} for no limits. */
    public HostLimits getLimits() {
        return limits;
    }

    /** How long to wait for a host within the limits: 0 not at all, negative forever. */
    public long getWaitSeconds() {
        return waitSeconds;
    }

    /**
     * Parses a call site's own wait time, already variable-expanded: blank means not set (null, inherit
     * the cloud's); a whole number of seconds, where a negative one or {@code infinite}/{@code forever}
     * means to wait for as long as it takes.
     *
     * @throws VSphereException if it is none of these
     */
    public static @CheckForNull Long parseWaitSeconds(@CheckForNull String value) throws VSphereException {
        if (value == null || value.trim().isEmpty()) {
            return null;
        }
        final String trimmed = value.trim();
        if ("infinite".equalsIgnoreCase(trimmed) || "forever".equalsIgnoreCase(trimmed)) {
            return Long.valueOf(VSphereHostSelection.WAIT_FOREVER);
        }
        try {
            final long parsed = Long.parseLong(trimmed);
            return Long.valueOf(parsed < 0 ? VSphereHostSelection.WAIT_FOREVER : parsed);
        } catch (NumberFormatException e) {
            throw new VSphereException("hostSelectionWaitSeconds must be a whole number of seconds (0 for none, -1 or"
                    + " \"infinite\" for no limit), but is \"" + value + "\"");
        }
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
