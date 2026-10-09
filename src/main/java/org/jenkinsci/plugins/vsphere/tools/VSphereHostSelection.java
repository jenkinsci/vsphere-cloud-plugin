package org.jenkinsci.plugins.vsphere.tools;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Pure, yavijava-free logic for picking a "best" ESXi host out of a
 * cluster's members, given per-host stats and an optional admin-supplied
 * allow-list. Kept free of vSphere API types so it can be unit-tested
 * without a live vCenter connection.
 */
public final class VSphereHostSelection {

    /**
     * Explicit "no host selection" override for a template/build-step's {@code
     * hostSelectionMode}, distinct from leaving it blank (which means "inherit the
     * cloud-level default" - see {@link #resolveMode}).
     */
    public static final String HOST_SELECTION_MODE_NONE = "NONE";

    private VSphereHostSelection() {}

    /**
     * Describes one candidate host's name, availability and current load,
     * as needed to decide whether/how favourably it can be used for a new
     * clone.
     */
    public static final class HostCandidate {
        private final String name;
        private final boolean connected;
        private final boolean inMaintenanceMode;
        private final Integer cpuUsageMhz;
        private final int cpuCapacityMhz;
        private final Integer memUsageMB;
        private final long memCapacityMB;
        private final int cpuCores;

        public HostCandidate(
                String name,
                boolean connected,
                boolean inMaintenanceMode,
                Integer cpuUsageMhz,
                int cpuCapacityMhz,
                Integer memUsageMB,
                long memCapacityMB) {
            this(name, connected, inMaintenanceMode, cpuUsageMhz, cpuCapacityMhz, memUsageMB, memCapacityMB, 0);
        }

        public HostCandidate(
                String name,
                boolean connected,
                boolean inMaintenanceMode,
                Integer cpuUsageMhz,
                int cpuCapacityMhz,
                Integer memUsageMB,
                long memCapacityMB,
                int cpuCores) {
            this.cpuCores = cpuCores;
            this.name = name;
            this.connected = connected;
            this.inMaintenanceMode = inMaintenanceMode;
            this.cpuUsageMhz = cpuUsageMhz;
            this.cpuCapacityMhz = cpuCapacityMhz;
            this.memUsageMB = memUsageMB;
            this.memCapacityMB = memCapacityMB;
        }

        public String getName() {
            return name;
        }

        public boolean isConnected() {
            return connected;
        }

        public boolean isInMaintenanceMode() {
            return inMaintenanceMode;
        }

        public Integer getCpuUsageMhz() {
            return cpuUsageMhz;
        }

        public int getCpuCapacityMhz() {
            return cpuCapacityMhz;
        }

        public Integer getMemUsageMB() {
            return memUsageMB;
        }

        public long getMemCapacityMB() {
            return memCapacityMB;
        }

        /** Number of physical CPU cores on the host; 0 if unknown. */
        public int getCpuCores() {
            return cpuCores;
        }

        /**
         * Fraction of capacity currently in use, taking the higher (more
         * constrained) of CPU and memory. Returns null if usage stats are
         * missing (stale/unavailable), so the host can be excluded rather
         * than mis-ranked.
         */
        public Double loadFraction() {
            if (cpuUsageMhz == null || memUsageMB == null) {
                return null;
            }
            double cpuFraction = cpuCapacityMhz > 0 ? (double) cpuUsageMhz / cpuCapacityMhz : 0d;
            double memFraction = memCapacityMB > 0 ? (double) memUsageMB / memCapacityMB : 0d;
            return Math.max(cpuFraction, memFraction);
        }

        /** Unused CPU in MHz (0 if usage is unknown). */
        public double freeCpuMhz() {
            return cpuUsageMhz == null ? 0d : Math.max(0, cpuCapacityMhz - cpuUsageMhz);
        }

        /** Unused memory in MB (0 if usage is unknown). */
        public double freeMemMB() {
            return memUsageMB == null ? 0d : Math.max(0L, memCapacityMB - memUsageMB);
        }

        /** Unused CPU as a share (0..1) of the host's CPU capacity. */
        public double freeCpuFraction() {
            return cpuCapacityMhz > 0 ? freeCpuMhz() / cpuCapacityMhz : 0d;
        }

        /** Unused memory as a share (0..1) of the host's memory. */
        public double freeMemFraction() {
            return memCapacityMB > 0 ? freeMemMB() / memCapacityMB : 0d;
        }

        /**
         * True if the host is currently usable at all (connected and not in
         * maintenance mode), regardless of load.
         */
        public boolean isUsable() {
            return connected && !inMaintenanceMode;
        }
    }

    /**
     * Parses a comma-separated allow-list of host names, trimming whitespace
     * and discarding empty entries. Returns an empty (not null) set when the
     * input is null/blank, meaning "no restriction".
     */
    public static Set<String> parseAllowList(String hostSelectionCandidatesCsv) {
        Set<String> result = new LinkedHashSet<>();
        if (hostSelectionCandidatesCsv == null
                || hostSelectionCandidatesCsv.trim().isEmpty()) {
            return result;
        }
        for (String name : hostSelectionCandidatesCsv.split(",")) {
            String trimmed = name.trim();
            if (!trimmed.isEmpty()) {
                result.add(trimmed);
            }
        }
        return result;
    }

    /**
     * Joins a collection of host names back into the comma-separated form used by the
     * classic config UI textbox. Returns "" (not null) for a null/empty input, so this
     * is safe to use directly as a form field's current value.
     */
    public static String toCsv(Collection<String> hosts) {
        if (hosts == null || hosts.isEmpty()) {
            return "";
        }
        return String.join(", ", hosts);
    }

    /**
     * Like {@link #parseAllowList}, but preserves the "not set at all" case as null
     * instead of collapsing it to an empty set - needed so a blank {@code
     * hostSelectionCandidatesAsString} can mean "inherit the cloud-level default" (see
     * {@link #resolveCandidates}) while a deliberately non-blank-but-hostless value
     * (e.g. a lone comma) can still mean "explicitly override to no restriction".
     * Blank/whitespace-only input (including a single space) is treated as "not set";
     * anything else is parsed normally, which may still yield an empty (non-null) set.
     */
    public static Set<String> parseAllowListOrNull(String hostSelectionCandidatesCsv) {
        if (hostSelectionCandidatesCsv == null
                || hostSelectionCandidatesCsv.trim().isEmpty()) {
            return null;
        }
        return parseAllowList(hostSelectionCandidatesCsv);
    }

    /**
     * The inverse of {@link #parseAllowListOrNull}: renders null as "" (inherit), an
     * empty set as a single comma (a visible, non-blank marker for "explicitly no
     * restriction" that round-trips back through {@link #parseAllowListOrNull} to an
     * empty - not null - set), and anything else as the plain comma-separated form.
     */
    public static String toAllowListString(Set<String> hosts) {
        if (hosts == null) {
            return "";
        }
        if (hosts.isEmpty()) {
            return ",";
        }
        return toCsv(hosts);
    }

    /**
     * Resolves a template/build-step's {@code hostSelectionMode} against its cloud's
     * default: blank/null defers to {@code cloudDefault}; {@link #HOST_SELECTION_MODE_NONE}
     * explicitly disables host selection regardless of the cloud default; any other
     * value (a real mode) wins outright.
     */
    public static String resolveMode(String cloudDefault, String override) {
        if (override == null || override.isEmpty()) {
            return cloudDefault;
        }
        if (HOST_SELECTION_MODE_NONE.equals(override)) {
            return "";
        }
        return override;
    }

    /**
     * Resolves a template/build-step's {@code hostSelectionCandidates} against its
     * cloud's default: null (never set at this level) defers to {@code cloudDefault};
     * any explicitly-set value - including an empty set, meaning "explicitly no
     * restriction" - wins outright.
     */
    public static Set<String> resolveCandidates(Set<String> cloudDefault, Set<String> override) {
        return override != null ? override : cloudDefault;
    }

    /**
     * Filters candidates down to ones that are usable (connected, not in
     * maintenance mode) and, if the allow-list is non-empty, whose name is
     * in it. An empty/null allow-list means "consider every usable host".
     */
    public static List<HostCandidate> filterCandidates(List<HostCandidate> candidates, Set<String> allowList) {
        List<HostCandidate> result = new ArrayList<>();
        for (HostCandidate candidate : candidates) {
            if (excludedBecause(candidate, allowList) == null) {
                result.add(candidate);
            }
        }
        return result;
    }

    /**
     * Why {@link #filterCandidates} drops this host, or null if it keeps it. For logging the
     * reasoning behind a placement.
     */
    public static String excludedBecause(HostCandidate candidate, Set<String> allowList) {
        if (!candidate.isConnected()) {
            return "not connected";
        }
        if (candidate.isInMaintenanceMode()) {
            return "in maintenance mode";
        }
        if (allowList != null && !allowList.isEmpty() && !allowList.contains(candidate.getName())) {
            return "not in the list of candidate hosts";
        }
        return null;
    }

    /**
     * Optionally drops candidates that are physically too small for the VM about to be
     * created: with {@code requireCores}, hosts with fewer physical CPU cores than {@code
     * vmCpus}; with {@code requireMemory}, hosts with less total physical RAM than {@code
     * vmMemoryMB}. Compares absolute capacity, not current free resources, so sites that
     * oversubscribe (swap, hyperthreads, ...) can simply leave both off. A host whose
     * relevant capacity is unknown (0) does not satisfy a requirement that is switched
     * on, and a VM size that is unknown (null) disables the corresponding check.
     */
    public static List<HostCandidate> filterByVmSize(
            List<HostCandidate> candidates,
            boolean requireCores,
            Integer vmCpus,
            boolean requireMemory,
            Integer vmMemoryMB) {
        final boolean checkCores = requireCores && vmCpus != null;
        final boolean checkMemory = requireMemory && vmMemoryMB != null;
        if (!checkCores && !checkMemory) {
            return candidates;
        }
        List<HostCandidate> result = new ArrayList<>();
        for (HostCandidate candidate : candidates) {
            if (sizeShortfall(candidate, requireCores, vmCpus, requireMemory, vmMemoryMB) == null) {
                result.add(candidate);
            }
        }
        return result;
    }

    /**
     * Why {@link #filterByVmSize} drops this host, or null if it keeps it. For logging the
     * reasoning behind a placement.
     */
    public static String sizeShortfall(
            HostCandidate candidate, boolean requireCores, Integer vmCpus, boolean requireMemory, Integer vmMemoryMB) {
        return sizeShortfall(candidate, requireCores, vmCpus, requireMemory, false, vmMemoryMB);
    }

    /**
     * As above, and with {@code requireAvailableMemory} also drops hosts that do not have at least
     * {@code vmMemoryMB} of memory free right now (so the new VM would not be swapped by the
     * hypervisor), or whose memory usage is unknown. Unlike {@code requireMemory}, which compares
     * the RAM installed, this looks at the host's current usage, which changes by the minute.
     */
    public static String sizeShortfall(
            HostCandidate candidate,
            boolean requireCores,
            Integer vmCpus,
            boolean requireMemory,
            boolean requireAvailableMemory,
            Integer vmMemoryMB) {
        if (requireCores && vmCpus != null && candidate.getCpuCores() < vmCpus) {
            return "has " + candidate.getCpuCores() + " physical core(s), fewer than the " + vmCpus
                    + " vCPU(s) of the VM";
        }
        if (requireMemory && vmMemoryMB != null && candidate.getMemCapacityMB() < vmMemoryMB) {
            return "has " + candidate.getMemCapacityMB() + " MB of RAM, less than the " + vmMemoryMB + " MB of the VM";
        }
        if (requireAvailableMemory && vmMemoryMB != null) {
            if (candidate.getMemUsageMB() == null) {
                return "its memory usage is unknown, so it cannot be told whether " + vmMemoryMB + " MB are free";
            }
            if (candidate.freeMemMB() < vmMemoryMB) {
                return "has only " + (long) candidate.freeMemMB() + " MB of free RAM right now, less than the "
                        + vmMemoryMB + " MB of the VM";
            }
        }
        return null;
    }

    /**
     * Why {@link HostLimits} rule this host out, or null if it is within them (or there are none).
     * For logging the reasoning behind a placement.
     */
    public static String limitShortfall(HostCandidate candidate, HostLimits limits) {
        return limits == null ? null : limits.shortfall(candidate);
    }

    /** Keeps only the candidates that satisfy all of the (possibly null) limits. */
    public static List<HostCandidate> filterByLimits(List<HostCandidate> candidates, HostLimits limits) {
        if (limits == null || !limits.isActive()) {
            return candidates;
        }
        List<HostCandidate> result = new ArrayList<>();
        for (HostCandidate candidate : candidates) {
            if (limits.shortfall(candidate) == null) {
                result.add(candidate);
            }
        }
        return result;
    }

    /** Waits for a while; {@link Thread#sleep} in real life. */
    @FunctionalInterface
    public interface Sleeper {
        void sleep(long millis) throws InterruptedException;
    }

    /** {@code waitSeconds} value meaning: keep waiting for as long as it takes. */
    public static final long WAIT_FOREVER = -1;

    /**
     * What one look at the cluster's hosts found: either the hosts that may be given the new VM, or, if
     * there are none, the {@link HostSelectionWaitReason reason} why not and a description for the log.
     */
    public static final class Evaluation {
        private final List<HostCandidate> eligible;
        private final HostSelectionWaitReason reason;
        private final String detail;

        private Evaluation(List<HostCandidate> eligible, HostSelectionWaitReason reason, String detail) {
            this.eligible = eligible;
            this.reason = reason;
            this.detail = detail;
        }

        public static Evaluation eligible(List<HostCandidate> hosts) {
            return new Evaluation(hosts, null, "");
        }

        public static Evaluation none(HostSelectionWaitReason reason, String detail) {
            return new Evaluation(new ArrayList<>(), reason, detail);
        }

        /** True if at least one host may be given the VM. */
        public boolean isEligible() {
            return reason == null;
        }

        public List<HostCandidate> getEligible() {
            return eligible;
        }

        /** Why no host is eligible; null if some are. */
        public HostSelectionWaitReason getReason() {
            return reason;
        }

        public String getDetail() {
            return detail;
        }
    }

    /**
     * The line for the build log when no host is eligible at the moment, which says what will be done
     * about it: whether (and for how long) to wait, or to carry on right away, and how.
     */
    public static String waitAnnouncement(Evaluation none, long waitSeconds, long pollMillis) {
        final HostSelectionWaitReason reason = none.getReason();
        final String givingUp = reason.failsWhenGivingUp() ? "fail the operation" : "let vSphere decide the placement";
        final StringBuilder sb = new StringBuilder("No host is available for the new VM at the moment (")
                .append(reason)
                .append(", ")
                .append(reason.isTransient() ? "transient" : "persistent")
                .append("): ")
                .append(none.getDetail())
                .append(' ');
        if (waitSeconds == 0) {
            sb.append("hostSelectionWaitSeconds is 0, so not waiting: will ")
                    .append(givingUp)
                    .append(" now.");
        } else if (waitSeconds < 0) {
            sb.append("hostSelectionWaitSeconds is unlimited: will check again every ")
                    .append(pollMillis / 1000)
                    .append(" second(s) for as long as it takes.");
        } else {
            sb.append("hostSelectionWaitSeconds is ")
                    .append(waitSeconds)
                    .append(": will check again every ")
                    .append(pollMillis / 1000)
                    .append(" second(s) for up to that long, then ")
                    .append(givingUp)
                    .append('.');
        }
        return sb.toString();
    }

    /**
     * Looks at the hosts again via {@code evaluateNow} until one is eligible, as long as {@code
     * waitSeconds} allows (0: not at all; negative, see {@link #WAIT_FOREVER}: until one is), and returns
     * the latest {@link Evaluation}, which is not eligible if the time ran out. It is for the caller to
     * decide what that means, as it depends on the reason.
     *
     * @throws VSphereException if the waiting thread is interrupted (e.g. the build aborted), in which case
     *     the interrupt flag is kept set
     */
    public static Evaluation waitForEligible(
            Evaluation initial,
            java.util.function.Supplier<Evaluation> evaluateNow,
            long waitSeconds,
            long pollMillis,
            java.util.function.LongSupplier clockMillis,
            Sleeper sleeper,
            java.util.function.Consumer<String> log)
            throws VSphereException {
        if (initial.isEligible() || waitSeconds == 0) {
            return initial;
        }
        final boolean forever = waitSeconds < 0;
        final long started = clockMillis.getAsLong();
        final long deadline = forever ? Long.MAX_VALUE : started + waitSeconds * 1000L;
        Evaluation latest = initial;
        while (true) {
            final long remaining = deadline - clockMillis.getAsLong();
            if (remaining <= 0) {
                log.accept("Gave up after waiting " + waitSeconds + " second(s); still: " + latest.getDetail());
                return latest;
            }
            try {
                sleeper.sleep(Math.min(pollMillis, remaining));
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new VSphereException("Interrupted while waiting for a host to become available.");
            }
            latest = evaluateNow.get();
            if (latest.isEligible()) {
                log.accept("After " + ((clockMillis.getAsLong() - started) / 1000) + " second(s), "
                        + latest.getEligible().size() + " host(s) are available.");
                return latest;
            }
        }
    }

    /** A candidate host with its availability score (0 worst .. 1 best); see {@link #rank}. */
    public static final class ScoredHost {
        private final HostCandidate host;
        private final double score;

        ScoredHost(HostCandidate host, double score) {
            this.host = host;
            this.score = score;
        }

        public HostCandidate getHost() {
            return host;
        }

        public double getScore() {
            return score;
        }
    }

    /**
     * Ranks the candidates that have usage statistics, most available first (equally scored ones
     * keep their incoming order). Hosts without statistics are left out.
     *
     * <p>With {@link HostWeights#isDefault default} weights, a host scores 1 minus its {@link
     * HostCandidate#loadFraction() load fraction}, which reproduces {@link #pickLeastLoaded}.
     * Otherwise the score is the weighted average of four measures, each between 0 and 1: free CPU
     * and free memory as a share of the host's own capacity, and free CPU (MHz) and free memory (MB)
     * relative to the best of the candidates being compared.
     */
    public static List<ScoredHost> rank(List<HostCandidate> candidates, HostWeights weights) {
        final HostWeights w = weights == null ? HostWeights.DEFAULT : weights;
        List<HostCandidate> withStats = new ArrayList<>();
        double maxFreeCpuMhz = 0;
        double maxFreeMemMB = 0;
        for (HostCandidate candidate : candidates) {
            if (candidate.loadFraction() == null) {
                continue;
            }
            withStats.add(candidate);
            maxFreeCpuMhz = Math.max(maxFreeCpuMhz, candidate.freeCpuMhz());
            maxFreeMemMB = Math.max(maxFreeMemMB, candidate.freeMemMB());
        }
        List<ScoredHost> ranked = new ArrayList<>();
        for (HostCandidate candidate : withStats) {
            double score;
            if (w.isDefault()) {
                score = 1d - candidate.loadFraction();
            } else {
                double sum = w.getFreeCpuMhz() * (maxFreeCpuMhz > 0 ? candidate.freeCpuMhz() / maxFreeCpuMhz : 0d)
                        + w.getFreeCpuPercent() * candidate.freeCpuFraction()
                        + w.getFreeMemoryMB() * (maxFreeMemMB > 0 ? candidate.freeMemMB() / maxFreeMemMB : 0d)
                        + w.getFreeMemoryPercent() * candidate.freeMemFraction();
                score = sum / w.total();
            }
            ranked.add(new ScoredHost(candidate, score));
        }
        // List.sort is stable, so ties keep their incoming order.
        ranked.sort((x, y) -> Double.compare(y.getScore(), x.getScore()));
        return ranked;
    }

    /** The most available candidate according to {@link #rank}, or null if none has statistics. */
    public static HostCandidate pickBest(List<HostCandidate> candidates, HostWeights weights) {
        List<ScoredHost> ranked = rank(candidates, weights);
        return ranked.isEmpty() ? null : ranked.get(0).getHost();
    }

    /**
     * Picks the candidate with the lowest load fraction, excluding any
     * candidate whose stats are unavailable. Returns null if no candidate
     * has usable stats.
     */
    public static HostCandidate pickLeastLoaded(List<HostCandidate> candidates) {
        HostCandidate best = null;
        double bestLoad = Double.MAX_VALUE;
        for (HostCandidate candidate : candidates) {
            Double load = candidate.loadFraction();
            if (load == null) {
                continue;
            }
            if (best == null || load < bestLoad) {
                best = candidate;
                bestLoad = load;
            }
        }
        return best;
    }

    /** Convenience: same as calling {@link Arrays#asList} for tests. */
    public static List<HostCandidate> listOf(HostCandidate... candidates) {
        return Arrays.asList(candidates);
    }
}
