package org.jenkinsci.plugins.vsphere.tools;

import edu.umd.cs.findbugs.annotations.CheckForNull;

/**
 * How much each of four measures of a host's <em>free</em> resources counts towards its score when
 * automatically choosing the "most available" host (see {@link VSphereHostSelection#rank}):
 *
 * <ul>
 *   <li>free CPU in MHz (absolute: a big host with the same load percentage has more to give),
 *   <li>free CPU as a share of the host's CPU capacity (relative: how idle the host is),
 *   <li>free memory in MB (absolute),
 *   <li>free memory as a share of the host's memory (relative).
 * </ul>
 *
 * The weights are relative to each other and only need to be non-negative. With all of them zero
 * (the default) the original ranking applies: the host whose busier resource - CPU or memory - is
 * the least used, by percentage.
 */
public final class HostWeights {

    /** All zero: the original ranking. */
    public static final HostWeights DEFAULT = new HostWeights(0, 0, 0, 0);

    private final double freeCpuMhz;
    private final double freeCpuPercent;
    private final double freeMemoryMB;
    private final double freeMemoryPercent;

    public HostWeights(double freeCpuMhz, double freeCpuPercent, double freeMemoryMB, double freeMemoryPercent) {
        this.freeCpuMhz = sanitize(freeCpuMhz);
        this.freeCpuPercent = sanitize(freeCpuPercent);
        this.freeMemoryMB = sanitize(freeMemoryMB);
        this.freeMemoryPercent = sanitize(freeMemoryPercent);
    }

    /**
     * Builds the weights a single call (build step or template) sets for itself from the text of
     * its four settings, already variable-expanded. If none is set (all null or blank) there is no
     * override and null is returned, meaning: use the cloud's weights. If any is set, these four
     * values replace the cloud's weights entirely, those left blank counting as zero. Setting them
     * all to {@code 0} is therefore a way to get the original ranking despite weights on the cloud.
     *
     * @throws VSphereException if a value is set but is not a non-negative whole number
     */
    public static @CheckForNull HostWeights parseOverride(
            @CheckForNull String freeCpuMhz,
            @CheckForNull String freeCpuPercent,
            @CheckForNull String freeMemoryMB,
            @CheckForNull String freeMemoryPercent)
            throws VSphereException {
        final Integer cpuMhz = parseOptionalWeight("hostWeightFreeCpuMhz", freeCpuMhz);
        final Integer cpuPercent = parseOptionalWeight("hostWeightFreeCpuPercent", freeCpuPercent);
        final Integer memoryMB = parseOptionalWeight("hostWeightFreeMemoryMB", freeMemoryMB);
        final Integer memoryPercent = parseOptionalWeight("hostWeightFreeMemoryPercent", freeMemoryPercent);
        if (cpuMhz == null && cpuPercent == null && memoryMB == null && memoryPercent == null) {
            return null;
        }
        return new HostWeights(
                cpuMhz == null ? 0 : cpuMhz,
                cpuPercent == null ? 0 : cpuPercent,
                memoryMB == null ? 0 : memoryMB,
                memoryPercent == null ? 0 : memoryPercent);
    }

    private static @CheckForNull Integer parseOptionalWeight(String what, @CheckForNull String value)
            throws VSphereException {
        if (value == null || value.trim().isEmpty()) {
            return null;
        }
        try {
            int parsed = Integer.parseInt(value.trim());
            if (parsed >= 0) {
                return parsed;
            }
        } catch (NumberFormatException e) {
            // reported below
        }
        throw new VSphereException(what + " must be a whole number of zero or more, but is \"" + value + "\"");
    }

    private static double sanitize(double weight) {
        return Double.isNaN(weight) || Double.isInfinite(weight) || weight < 0 ? 0 : weight;
    }

    public double getFreeCpuMhz() {
        return freeCpuMhz;
    }

    public double getFreeCpuPercent() {
        return freeCpuPercent;
    }

    public double getFreeMemoryMB() {
        return freeMemoryMB;
    }

    public double getFreeMemoryPercent() {
        return freeMemoryPercent;
    }

    public double total() {
        return freeCpuMhz + freeCpuPercent + freeMemoryMB + freeMemoryPercent;
    }

    /** True if no weight is set, meaning the original ranking applies. */
    public boolean isDefault() {
        return total() <= 0;
    }

    @Override
    public String toString() {
        return "weights[free CPU MHz=" + freeCpuMhz + ", free CPU %=" + freeCpuPercent + ", free RAM MB=" + freeMemoryMB
                + ", free RAM %=" + freeMemoryPercent + "]";
    }
}
