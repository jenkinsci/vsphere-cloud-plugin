package org.jenkinsci.plugins.vsphere.tools;

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
