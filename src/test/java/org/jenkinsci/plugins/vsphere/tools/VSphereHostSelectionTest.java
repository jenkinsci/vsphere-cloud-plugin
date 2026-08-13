package org.jenkinsci.plugins.vsphere.tools;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.empty;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.nullValue;

import java.util.List;
import java.util.Set;

import org.jenkinsci.plugins.vsphere.tools.VSphereHostSelection.HostCandidate;
import org.junit.jupiter.api.Test;

class VSphereHostSelectionTest {

    @Test
    void parseAllowListReturnsEmptySetForNullOrBlank() {
        assertThat(VSphereHostSelection.parseAllowList(null), empty());
        assertThat(VSphereHostSelection.parseAllowList(""), empty());
        assertThat(VSphereHostSelection.parseAllowList("   "), empty());
    }

    @Test
    void parseAllowListTrimsAndDropsEmptyEntries() {
        Set<String> allowList = VSphereHostSelection.parseAllowList(" esx1 , esx2,, esx3 ");
        assertThat(allowList, contains("esx1", "esx2", "esx3"));
    }

    @Test
    void filterCandidatesExcludesDisconnectedAndMaintenanceHosts() {
        HostCandidate connected = candidate("esx1", true, false, 1000, 2000, 1000, 2000);
        HostCandidate disconnected = candidate("esx2", false, false, 1000, 2000, 1000, 2000);
        HostCandidate inMaintenance = candidate("esx3", true, true, 1000, 2000, 1000, 2000);

        List<HostCandidate> filtered = VSphereHostSelection.filterCandidates(
                List.of(connected, disconnected, inMaintenance), Set.of());

        assertThat(filtered, contains(connected));
    }

    @Test
    void filterCandidatesHonoursNonEmptyAllowList() {
        HostCandidate esx1 = candidate("esx1", true, false, 1000, 2000, 1000, 2000);
        HostCandidate esx2 = candidate("esx2", true, false, 1000, 2000, 1000, 2000);

        List<HostCandidate> filtered = VSphereHostSelection.filterCandidates(
                List.of(esx1, esx2), Set.of("esx2"));

        assertThat(filtered, contains(esx2));
    }

    @Test
    void filterCandidatesWithEmptyAllowListConsidersAllUsableHosts() {
        HostCandidate esx1 = candidate("esx1", true, false, 1000, 2000, 1000, 2000);
        HostCandidate esx2 = candidate("esx2", true, false, 1000, 2000, 1000, 2000);

        List<HostCandidate> filtered = VSphereHostSelection.filterCandidates(
                List.of(esx1, esx2), Set.of());

        assertThat(filtered, contains(esx1, esx2));
    }

    @Test
    void pickLeastLoadedPicksLowerCpuAndMemoryUsageFraction() {
        // esx1 at 80% cpu, esx2 at 20% cpu -> esx2 should win
        HostCandidate busy = candidate("esx1", true, false, 1600, 2000, 400, 2000);
        HostCandidate idle = candidate("esx2", true, false, 400, 2000, 400, 2000);

        HostCandidate winner = VSphereHostSelection.pickLeastLoaded(List.of(busy, idle));

        assertThat(winner, is(idle));
    }

    @Test
    void pickLeastLoadedUsesTheMoreConstrainedOfCpuOrMemory() {
        // esx1: low cpu but very high memory usage -> should lose to esx2 which is moderate on both
        HostCandidate memoryBound = candidate("esx1", true, false, 100, 2000, 1900, 2000);
        HostCandidate balanced = candidate("esx2", true, false, 1000, 2000, 1000, 2000);

        HostCandidate winner = VSphereHostSelection.pickLeastLoaded(List.of(memoryBound, balanced));

        assertThat(winner, is(balanced));
    }

    @Test
    void pickLeastLoadedExcludesHostsWithMissingStats() {
        HostCandidate noStats = candidate("esx1", true, false, null, 2000, null, 2000);
        HostCandidate withStats = candidate("esx2", true, false, 1000, 2000, 1000, 2000);

        HostCandidate winner = VSphereHostSelection.pickLeastLoaded(List.of(noStats, withStats));

        assertThat(winner, is(withStats));
    }

    @Test
    void pickLeastLoadedReturnsNullWhenNoCandidateHasStats() {
        HostCandidate noStats1 = candidate("esx1", true, false, null, 2000, null, 2000);
        HostCandidate noStats2 = candidate("esx2", true, false, null, 2000, null, 2000);

        HostCandidate winner = VSphereHostSelection.pickLeastLoaded(List.of(noStats1, noStats2));

        assertThat(winner, nullValue());
    }

    @Test
    void pickLeastLoadedReturnsNullForEmptyList() {
        assertThat(VSphereHostSelection.pickLeastLoaded(List.of()), nullValue());
    }

    private static HostCandidate candidate(String name, boolean connected, boolean inMaintenanceMode,
            Integer cpuUsageMhz, int cpuCapacityMhz, Integer memUsageMB, long memCapacityMB) {
        return new HostCandidate(name, connected, inMaintenanceMode, cpuUsageMhz, cpuCapacityMhz, memUsageMB, memCapacityMB);
    }
}
