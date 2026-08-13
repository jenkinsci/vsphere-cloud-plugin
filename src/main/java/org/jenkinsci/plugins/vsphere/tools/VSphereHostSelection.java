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

    private VSphereHostSelection() {
    }

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

        public HostCandidate(String name, boolean connected, boolean inMaintenanceMode,
                Integer cpuUsageMhz, int cpuCapacityMhz, Integer memUsageMB, long memCapacityMB) {
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
        if (hostSelectionCandidatesCsv == null || hostSelectionCandidatesCsv.trim().isEmpty()) {
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
        if (hostSelectionCandidatesCsv == null || hostSelectionCandidatesCsv.trim().isEmpty()) {
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
            if (!candidate.isUsable()) {
                continue;
            }
            if (allowList != null && !allowList.isEmpty() && !allowList.contains(candidate.getName())) {
                continue;
            }
            result.add(candidate);
        }
        return result;
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
