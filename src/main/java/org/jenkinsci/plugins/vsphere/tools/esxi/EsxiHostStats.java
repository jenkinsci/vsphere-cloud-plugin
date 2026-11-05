/*   Copyright 2026, Jim Klimov
 *
 *   Licensed under the Apache License, Version 2.0 (the "License");
 *   you may not use this file except in compliance with the License.
 *   You may obtain a copy of the License at
 *
 *       http://www.apache.org/licenses/LICENSE-2.0
 *
 *   Unless required by applicable law or agreed to in writing, software
 *   distributed under the License is distributed on an "AS IS" BASIS,
 *   WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *   See the License for the specific language governing permissions and
 *   limitations under the License.
 */
package org.jenkinsci.plugins.vsphere.tools.esxi;

import edu.umd.cs.findbugs.annotations.CheckForNull;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.jenkinsci.plugins.vsphere.tools.VSphereHostSelection.HostCandidate;

/**
 * What {@code vim-cmd hostsvc/hostsummary} says of the size and the load of a host: the speed and the number of its
 * CPU cores, its memory, and what is used of both right now (the {@code quickStats} the host keeps, which are a
 * few seconds old), and whether it is in maintenance mode. A number that is not there is null.
 */
final class EsxiHostStats {

    private static final Pattern MAINTENANCE = Pattern.compile("(?m)^\\s*inMaintenanceMode\\s*=\\s*(true|false)\\b");

    @CheckForNull
    final Long memorySizeBytes;

    @CheckForNull
    final Integer cpuMhz;

    @CheckForNull
    final Integer cpuCores;

    @CheckForNull
    final Integer cpuUsageMhz;

    @CheckForNull
    final Integer memoryUsageMB;

    final boolean inMaintenanceMode;

    private EsxiHostStats(
            Long memorySizeBytes,
            Integer cpuMhz,
            Integer cpuCores,
            Integer cpuUsageMhz,
            Integer memoryUsageMB,
            boolean inMaintenanceMode) {
        this.memorySizeBytes = memorySizeBytes;
        this.cpuMhz = cpuMhz;
        this.cpuCores = cpuCores;
        this.cpuUsageMhz = cpuUsageMhz;
        this.memoryUsageMB = memoryUsageMB;
        this.inMaintenanceMode = inMaintenanceMode;
    }

    @CheckForNull
    private static Long number(String text, String key) {
        final Matcher m =
                Pattern.compile("(?m)^\\s*" + key + "\\s*=\\s*(\\d+)\\b").matcher(text);
        return m.find() ? Long.valueOf(m.group(1)) : null;
    }

    static EsxiHostStats parse(String hostSummary) {
        final Long cpuMhz = number(hostSummary, "cpuMhz");
        final Long cores = number(hostSummary, "numCpuCores");
        final Long cpuUsage = number(hostSummary, "overallCpuUsage");
        final Long memUsage = number(hostSummary, "overallMemoryUsage");
        final Matcher maintenance = MAINTENANCE.matcher(hostSummary);
        return new EsxiHostStats(
                number(hostSummary, "memorySize"),
                cpuMhz == null ? null : Integer.valueOf(cpuMhz.intValue()),
                cores == null ? null : Integer.valueOf(cores.intValue()),
                cpuUsage == null ? null : Integer.valueOf(cpuUsage.intValue()),
                memUsage == null ? null : Integer.valueOf(memUsage.intValue()),
                maintenance.find() && Boolean.parseBoolean(maintenance.group(1)));
    }

    /**
     * The host as a candidate for the placement of a VM, which is how vCenter's hosts are ranked: the capacity of the
     * CPU is its speed times its cores; what is used is null (the host is not ranked) if the host does not say.
     */
    HostCandidate asCandidate(String name) {
        final int capacityMhz = cpuMhz == null || cpuCores == null ? 0 : cpuMhz * cpuCores;
        return new HostCandidate(
                name,
                true,
                inMaintenanceMode,
                cpuUsageMhz,
                capacityMhz,
                memoryUsageMB,
                memorySizeBytes == null ? 0L : memorySizeBytes / (1024L * 1024L),
                cpuCores == null ? 0 : cpuCores);
    }
}
