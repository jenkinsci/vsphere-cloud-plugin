package org.jenkinsci.plugins.vsphere.tools;

import java.util.ArrayList;
import java.util.Arrays;
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
    public static Set<String> parseAllowList(String candidateHostsCsv) {
        Set<String> result = new LinkedHashSet<>();
        if (candidateHostsCsv == null || candidateHostsCsv.trim().isEmpty()) {
            return result;
        }
        for (String name : candidateHostsCsv.split(",")) {
            String trimmed = name.trim();
            if (!trimmed.isEmpty()) {
                result.add(trimmed);
            }
        }
        return result;
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
            if (!allowList.isEmpty() && !allowList.contains(candidate.getName())) {
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
