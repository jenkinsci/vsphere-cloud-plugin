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

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.closeTo;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.nullValue;

import org.jenkinsci.plugins.vsphere.tools.VSphereHostSelection.HostCandidate;
import org.junit.jupiter.api.Test;

/**
 * What {@code vim-cmd hostsvc/hostsummary} prints, as it was printed by a real host.
 *
 * <p>The format is the same on ESXi 7.0.3 (build 20036589) and ESXi 8.0.1 (build 21813344), which are the ones it was
 * looked at on, so one example stands for both; older versions may differ, or may not.
 */
class EsxiHostStatsTest {

    /** The parts of what an ESXi 8.0.1 host printed that are about its size and its load, as they were printed. */
    private static final String ESXI8 = String.join(
            "\n",
            "(vim.host.Summary) {",
            "   host = 'vim.HostSystem:ha-host',",
            "   hardware = (vim.host.Summary.HardwareSummary) {",
            "      vendor = \"QEMU\",",
            "      model = \"Standard PC (Q35 + ICH9, 2009)\",",
            "      otherIdentifyingInfo = (vim.host.SystemIdentificationInfo) [",
            "         (vim.host.SystemIdentificationInfo) {",
            "            identifierValue = \"\",",
            "            identifierType = (vim.ElementDescription) {",
            "               label = \"Asset Tag\",",
            "               key = \"AssetTag\"",
            "            }",
            "         }",
            "      ],",
            "      memorySize = 8589328384,",
            "      cpuModel = \"AMD Ryzen 7 5825U with Radeon Graphics         \",",
            "      cpuMhz = 1996,",
            "      numCpuPkgs = 1,",
            "      numCpuCores = 8,",
            "      numCpuThreads = 8,",
            "      numNics = 1,",
            "      numHBAs = 2",
            "   },",
            "   runtime = (vim.host.RuntimeInfo) {",
            "      connectionState = \"connected\",",
            "      powerState = \"poweredOn\",",
            "      standbyMode = <unset>,",
            "      inMaintenanceMode = false,",
            "      inQuarantineMode = <unset>,",
            "      cpuCapacityForVm = <unset>,",
            "      memoryCapacityForVm = <unset>,",
            "      hostMaxVirtualDiskCapacity = 68169720922112,",
            "   },",
            "   quickStats = (vim.host.Summary.QuickStats) {",
            "      overallCpuUsage = 60,",
            "      overallMemoryUsage = 1850,",
            "      distributedCpuFairness = <unset>,",
            "      uptime = 72306",
            "   },",
            "}");

    @Test
    void aRealHostIsReadAsItIsPrinted() {
        final EsxiHostStats stats = EsxiHostStats.parse(ESXI8);

        assertThat(stats.cpuMhz, is(1996));
        assertThat(stats.cpuCores, is(8));
        assertThat(stats.memorySizeBytes, is(8589328384L));
        assertThat(stats.cpuUsageMhz, is(60));
        assertThat(stats.memoryUsageMB, is(1850));
        assertThat(stats.inMaintenanceMode, is(false));
    }

    @Test
    void itIsACandidateWithTheCapacityOfAllItsCores() {
        final HostCandidate candidate = EsxiHostStats.parse(ESXI8).asCandidate("esxi8");

        assertThat(candidate.getCpuCapacityMhz(), is(1996 * 8));
        assertThat(candidate.getMemCapacityMB(), is(8191L));
        assertThat(candidate.getCpuCores(), is(8));
        // the memory is the busier of the two: 1850 of 8191 MB, against 60 of 15968 MHz
        assertThat(candidate.loadFraction(), closeTo(1850d / 8191d, 1e-9));
    }

    @Test
    void whatIsNotANumberIsNotTakenForOne() {
        // "<unset>", and a name that only starts with the name that is wanted
        final EsxiHostStats stats = EsxiHostStats.parse("      cpuMhz = <unset>,\n      numCpuCoresX = 4,\n");

        assertThat(stats.cpuMhz, is(nullValue()));
        assertThat(stats.cpuCores, is(nullValue()));
        assertThat(stats.asCandidate("x").loadFraction(), is(nullValue()));
    }
}
