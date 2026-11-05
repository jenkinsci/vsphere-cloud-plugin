package org.jenkinsci.plugins.vsphere.tools;

import edu.umd.cs.findbugs.annotations.CheckForNull;
import org.jenkinsci.plugins.vsphere.tools.VSphereHostSelection.HostCandidate;

/**
 * Minimal amounts of <em>free</em> resources a host must have right now to be a candidate for a new VM
 * at all (see {@link VSphereHostSelection#limitShortfall}), as opposed to {@link HostWeights}, which
 * only decide which of the candidates is preferred. A host with less than the limit is kept off the
 * list, however attractive it would otherwise be.
 *
 * <p>Each limit is a floor; zero (the default) switches it off, since no host has less than none free.
 * The absolute ones (MHz, MB) suit farms of similar hosts, the relative ones (percent of the host's own
 * capacity) suit mixed farms; all four may be combined, and a host must then satisfy every one set.
 */
public final class HostLimits {

    /** All zero: no limits. */
    public static final HostLimits NONE = new HostLimits(0, 0, 0, 0);

    private final long minFreeCpuMhz;
    private final int minFreeCpuPercent;
    private final long minFreeMemoryMB;
    private final int minFreeMemoryPercent;

    public HostLimits(long minFreeCpuMhz, int minFreeCpuPercent, long minFreeMemoryMB, int minFreeMemoryPercent) {
        this.minFreeCpuMhz = Math.max(0, minFreeCpuMhz);
        this.minFreeCpuPercent = clampPercent(minFreeCpuPercent);
        this.minFreeMemoryMB = Math.max(0, minFreeMemoryMB);
        this.minFreeMemoryPercent = clampPercent(minFreeMemoryPercent);
    }

    /**
     * Builds the limits a single call (build step or template) sets for itself from the text of its
     * four settings, already variable-expanded. If none is set (all null or blank) there is no override
     * and null is returned, meaning: use the cloud's limits. If any is set, these four values replace
     * the cloud's limits entirely, those left blank counting as zero. Setting them all to {@code 0} is
     * therefore a way to have no limits despite the cloud's.
     *
     * @throws VSphereException if a value is set but is not a non-negative whole number (or, for a
     *     percentage, is above 100)
     */
    public static @CheckForNull HostLimits parseOverride(
            @CheckForNull String minFreeCpuMhz,
            @CheckForNull String minFreeCpuPercent,
            @CheckForNull String minFreeMemoryMB,
            @CheckForNull String minFreeMemoryPercent)
            throws VSphereException {
        final Long cpuMhz = parseOptional("hostMinFreeCpuMhz", minFreeCpuMhz, Long.MAX_VALUE);
        final Long cpuPercent = parseOptional("hostMinFreeCpuPercent", minFreeCpuPercent, 100);
        final Long memoryMB = parseOptional("hostMinFreeMemoryMB", minFreeMemoryMB, Long.MAX_VALUE);
        final Long memoryPercent = parseOptional("hostMinFreeMemoryPercent", minFreeMemoryPercent, 100);
        if (cpuMhz == null && cpuPercent == null && memoryMB == null && memoryPercent == null) {
            return null;
        }
        return new HostLimits(
                cpuMhz == null ? 0 : cpuMhz,
                cpuPercent == null ? 0 : cpuPercent.intValue(),
                memoryMB == null ? 0 : memoryMB,
                memoryPercent == null ? 0 : memoryPercent.intValue());
    }

    private static @CheckForNull Long parseOptional(String what, @CheckForNull String value, long max)
            throws VSphereException {
        if (value == null || value.trim().isEmpty()) {
            return null;
        }
        try {
            final long parsed = Long.parseLong(value.trim());
            if (parsed >= 0 && parsed <= max) {
                return parsed;
            }
        } catch (NumberFormatException e) {
            // reported below
        }
        throw new VSphereException(what + " must be a whole number of zero or more"
                + (max == 100 ? " and at most 100" : "") + ", but is \"" + value + "\"");
    }

    private static int clampPercent(int percent) {
        return Math.max(0, Math.min(100, percent));
    }

    public long getMinFreeCpuMhz() {
        return minFreeCpuMhz;
    }

    public int getMinFreeCpuPercent() {
        return minFreeCpuPercent;
    }

    public long getMinFreeMemoryMB() {
        return minFreeMemoryMB;
    }

    public int getMinFreeMemoryPercent() {
        return minFreeMemoryPercent;
    }

    /** True if at least one limit is set. */
    public boolean isActive() {
        return minFreeCpuMhz > 0 || minFreeCpuPercent > 0 || minFreeMemoryMB > 0 || minFreeMemoryPercent > 0;
    }

    /**
     * Why this host is below the limits, or null if it satisfies all of them. A host whose usage is not
     * known cannot be shown to satisfy a limit that is set, so it is below it.
     */
    public @CheckForNull String shortfall(HostCandidate candidate) {
        if (!isActive()) {
            return null;
        }
        if (candidate.getCpuUsageMhz() == null || candidate.getMemUsageMB() == null) {
            return "its CPU/memory usage is unknown, so it cannot be told whether it is within the free resource limits";
        }
        if (candidate.freeCpuMhz() < minFreeCpuMhz) {
            return "has only " + (long) candidate.freeCpuMhz() + " MHz of free CPU, less than the limit of "
                    + minFreeCpuMhz + " MHz";
        }
        if (belowShare(candidate.freeCpuMhz(), candidate.getCpuCapacityMhz(), minFreeCpuPercent)) {
            return "has only " + percent(candidate.freeCpuFraction()) + "% of its CPU free, less than the limit of "
                    + minFreeCpuPercent + "%";
        }
        if (candidate.freeMemMB() < minFreeMemoryMB) {
            return "has only " + (long) candidate.freeMemMB() + " MB of free RAM, less than the limit of "
                    + minFreeMemoryMB + " MB";
        }
        if (belowShare(candidate.freeMemMB(), candidate.getMemCapacityMB(), minFreeMemoryPercent)) {
            return "has only " + percent(candidate.freeMemFraction()) + "% of its RAM free, less than the limit of "
                    + minFreeMemoryPercent + "%";
        }
        return null;
    }

    /**
     * Whether {@code free} is less than {@code minPercent} percent of {@code capacity}. Multiplies
     * rather than dividing, so a host exactly at the limit is not lost to rounding. With a limit set,
     * a host of unknown (zero) capacity is below it.
     */
    private static boolean belowShare(double free, double capacity, int minPercent) {
        if (minPercent <= 0) {
            return false;
        }
        return capacity <= 0 || free * 100 < minPercent * capacity;
    }

    private static String percent(double fraction) {
        return String.format(java.util.Locale.ROOT, "%.1f", fraction * 100);
    }

    @Override
    public String toString() {
        return "limits[free CPU MHz>=" + minFreeCpuMhz + ", free CPU %>=" + minFreeCpuPercent + ", free RAM MB>="
                + minFreeMemoryMB + ", free RAM %>=" + minFreeMemoryPercent + "]";
    }
}
