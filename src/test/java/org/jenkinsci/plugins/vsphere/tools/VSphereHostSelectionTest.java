package org.jenkinsci.plugins.vsphere.tools;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.closeTo;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.empty;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.nullValue;

import java.util.List;
import java.util.Set;
import org.jenkinsci.plugins.vsphere.tools.VSphereHostSelection.HostCandidate;
import org.junit.jupiter.api.Test;

class VSphereHostSelectionTest {

    @Test
    void toCsvReturnsEmptyStringForNullOrEmpty() {
        assertThat(VSphereHostSelection.toCsv(null), is(""));
        assertThat(VSphereHostSelection.toCsv(List.of()), is(""));
    }

    @Test
    void toCsvJoinsWithCommaAndSpace() {
        assertThat(VSphereHostSelection.toCsv(List.of("esx1", "esx2", "esx3")), is("esx1, esx2, esx3"));
    }

    @Test
    void parseAllowListAndToCsvRoundTrip() {
        Set<String> parsed = VSphereHostSelection.parseAllowList(" esx1 , esx2, esx3 ");
        assertThat(VSphereHostSelection.toCsv(parsed), is("esx1, esx2, esx3"));
    }

    @Test
    void parseAllowListOrNullReturnsNullForBlankOrWhitespace() {
        assertThat(VSphereHostSelection.parseAllowListOrNull(null), nullValue());
        assertThat(VSphereHostSelection.parseAllowListOrNull(""), nullValue());
        assertThat(VSphereHostSelection.parseAllowListOrNull("   "), nullValue());
    }

    @Test
    void parseAllowListOrNullReturnsEmptySetForACommaSentinel() {
        // Not blank after trim, so it survives as an explicit "override to nothing",
        // distinct from a field that was never set at all.
        assertThat(VSphereHostSelection.parseAllowListOrNull(","), is(Set.of()));
    }

    @Test
    void parseAllowListOrNullParsesRealValuesNormally() {
        assertThat(VSphereHostSelection.parseAllowListOrNull("esx1, esx2"), is(Set.of("esx1", "esx2")));
    }

    @Test
    void toAllowListStringRoundTripsThroughParseAllowListOrNull() {
        assertThat(
                VSphereHostSelection.parseAllowListOrNull(VSphereHostSelection.toAllowListString(null)), nullValue());
        assertThat(
                VSphereHostSelection.parseAllowListOrNull(VSphereHostSelection.toAllowListString(Set.of())),
                is(Set.of()));
        assertThat(
                VSphereHostSelection.parseAllowListOrNull(
                        VSphereHostSelection.toAllowListString(Set.of("esx1", "esx2"))),
                is(Set.of("esx1", "esx2")));
    }

    @Test
    void toAllowListStringUsesACommaForExplicitlyEmpty() {
        assertThat(VSphereHostSelection.toAllowListString(null), is(""));
        assertThat(VSphereHostSelection.toAllowListString(Set.of()), is(","));
    }

    @Test
    void resolveModeInheritsCloudDefaultWhenOverrideIsBlank() {
        assertThat(VSphereHostSelection.resolveMode("LEAST_LOADED", null), is("LEAST_LOADED"));
        assertThat(VSphereHostSelection.resolveMode("LEAST_LOADED", ""), is("LEAST_LOADED"));
        assertThat(VSphereHostSelection.resolveMode(null, null), nullValue());
    }

    @Test
    void resolveModeNoneExplicitlyDisablesRegardlessOfCloudDefault() {
        assertThat(VSphereHostSelection.resolveMode("LEAST_LOADED", "NONE"), is(""));
        assertThat(VSphereHostSelection.resolveMode(null, "NONE"), is(""));
    }

    @Test
    void resolveModeExplicitOverrideWins() {
        assertThat(VSphereHostSelection.resolveMode("LEAST_LOADED", "DRS_RECOMMENDED"), is("DRS_RECOMMENDED"));
        assertThat(VSphereHostSelection.resolveMode(null, "LEAST_LOADED"), is("LEAST_LOADED"));
    }

    @Test
    void resolveCandidatesInheritsCloudDefaultWhenOverrideIsNull() {
        assertThat(VSphereHostSelection.resolveCandidates(Set.of("esx1"), null), is(Set.of("esx1")));
        assertThat(VSphereHostSelection.resolveCandidates(null, null), nullValue());
    }

    @Test
    void resolveCandidatesExplicitOverrideWinsEvenWhenEmpty() {
        assertThat(VSphereHostSelection.resolveCandidates(Set.of("esx1"), Set.of()), is(Set.of()));
        assertThat(VSphereHostSelection.resolveCandidates(Set.of("esx1"), Set.of("esx2")), is(Set.of("esx2")));
    }

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

        List<HostCandidate> filtered =
                VSphereHostSelection.filterCandidates(List.of(connected, disconnected, inMaintenance), Set.of());

        assertThat(filtered, contains(connected));
    }

    @Test
    void filterCandidatesTreatsNullAllowListAsNoRestriction() {
        HostCandidate esx1 = candidate("esx1", true, false, 1000, 2000, 1000, 2000);

        List<HostCandidate> filtered = VSphereHostSelection.filterCandidates(List.of(esx1), null);

        assertThat(filtered, contains(esx1));
    }

    @Test
    void filterCandidatesHonoursNonEmptyAllowList() {
        HostCandidate esx1 = candidate("esx1", true, false, 1000, 2000, 1000, 2000);
        HostCandidate esx2 = candidate("esx2", true, false, 1000, 2000, 1000, 2000);

        List<HostCandidate> filtered = VSphereHostSelection.filterCandidates(List.of(esx1, esx2), Set.of("esx2"));

        assertThat(filtered, contains(esx2));
    }

    @Test
    void filterCandidatesWithEmptyAllowListConsidersAllUsableHosts() {
        HostCandidate esx1 = candidate("esx1", true, false, 1000, 2000, 1000, 2000);
        HostCandidate esx2 = candidate("esx2", true, false, 1000, 2000, 1000, 2000);

        List<HostCandidate> filtered = VSphereHostSelection.filterCandidates(List.of(esx1, esx2), Set.of());

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

    @Test
    void filterByVmSizeDoesNothingWhenBothChecksAreOff() {
        List<HostCandidate> hosts = List.of(sized("small", 4, 8192), sized("big", 32, 262144));
        assertThat(VSphereHostSelection.filterByVmSize(hosts, false, 64, false, 999999), is(hosts));
    }

    @Test
    void filterByVmSizeCanRequireCoresOnly() {
        HostCandidate small = sized("small", 4, 262144);
        HostCandidate big = sized("big", 32, 8192);
        List<HostCandidate> kept = VSphereHostSelection.filterByVmSize(List.of(small, big), true, 8, false, 100000);
        assertThat(kept, contains(big));
    }

    @Test
    void filterByVmSizeCanRequireMemoryOnly() {
        HostCandidate small = sized("small", 32, 8192);
        HostCandidate big = sized("big", 4, 262144);
        List<HostCandidate> kept = VSphereHostSelection.filterByVmSize(List.of(small, big), false, 64, true, 16384);
        assertThat(kept, contains(big));
    }

    @Test
    void filterByVmSizeRequiringBothNeedsBoth() {
        HostCandidate fewCores = sized("fewCores", 4, 262144);
        HostCandidate littleRam = sized("littleRam", 32, 8192);
        HostCandidate fits = sized("fits", 16, 65536);
        HostCandidate exactFit = sized("exactFit", 8, 16384);
        List<HostCandidate> kept =
                VSphereHostSelection.filterByVmSize(List.of(fewCores, littleRam, fits, exactFit), true, 8, true, 16384);
        assertThat(kept, contains(fits, exactFit));
    }

    @Test
    void filterByVmSizeTreatsUnknownHostCapacityAsNotSatisfyingAnEnabledCheck() {
        HostCandidate unknown = sized("unknown", 0, 0);
        assertThat(VSphereHostSelection.filterByVmSize(List.of(unknown), true, 2, false, null), is(empty()));
        assertThat(VSphereHostSelection.filterByVmSize(List.of(unknown), false, null, true, 1024), is(empty()));
    }

    @Test
    void filterByVmSizeSkipsACheckWhoseVmSizeIsUnknown() {
        HostCandidate small = sized("small", 2, 2048);
        assertThat(VSphereHostSelection.filterByVmSize(List.of(small), true, null, true, null), contains(small));
    }

    @Test
    void defaultWeightsRankLikePickLeastLoaded() {
        // busy: 90% CPU. mixed: 20% CPU but 70% memory. calm: 40% on both.
        HostCandidate busy = loaded("busy", 900, 1000, 100, 1000);
        HostCandidate mixed = loaded("mixed", 200, 1000, 700, 1000);
        HostCandidate calm = loaded("calm", 400, 1000, 400, 1000);
        List<HostCandidate> all = List.of(busy, mixed, calm);

        assertThat(VSphereHostSelection.pickBest(all, HostWeights.DEFAULT), is(calm));
        assertThat(VSphereHostSelection.pickBest(all, null), is(calm));
        assertThat(
                VSphereHostSelection.pickBest(all, HostWeights.DEFAULT), is(VSphereHostSelection.pickLeastLoaded(all)));
    }

    @Test
    void relativeCpuWeightFavoursTheMostIdleHostByPercentage() {
        HostCandidate smallIdle = loaded("smallIdle", 100, 1000, 500, 1000); // 90% CPU free
        HostCandidate bigBusier = loaded("bigBusier", 4000, 10000, 500, 1000); // 60% CPU free
        HostWeights relativeCpu = new HostWeights(0, 1, 0, 0);
        assertThat(VSphereHostSelection.pickBest(List.of(bigBusier, smallIdle), relativeCpu), is(smallIdle));
    }

    @Test
    void absoluteCpuWeightFavoursTheHostWithMostFreeMhz() {
        HostCandidate smallIdle = loaded("smallIdle", 100, 1000, 500, 1000); // 900 MHz free
        HostCandidate bigBusier = loaded("bigBusier", 4000, 10000, 500, 1000); // 6000 MHz free
        HostWeights absoluteCpu = new HostWeights(1, 0, 0, 0);
        assertThat(VSphereHostSelection.pickBest(List.of(smallIdle, bigBusier), absoluteCpu), is(bigBusier));
    }

    @Test
    void memoryWeightsWorkTheSameWayAsCpuOnes() {
        HostCandidate smallIdle = loaded("smallIdle", 500, 1000, 100, 1000); // 900 MB free, 90%
        HostCandidate bigBusier = loaded("bigBusier", 500, 1000, 40000, 100000); // 60000 MB free, 60%
        assertThat(
                VSphereHostSelection.pickBest(List.of(smallIdle, bigBusier), new HostWeights(0, 0, 1, 0)),
                is(bigBusier));
        assertThat(
                VSphereHostSelection.pickBest(List.of(smallIdle, bigBusier), new HostWeights(0, 0, 0, 1)),
                is(smallIdle));
    }

    @Test
    void weightsAreProportionsAndScoresStayBetweenZeroAndOne() {
        HostCandidate a = loaded("a", 100, 1000, 500, 1000);
        HostCandidate b = loaded("b", 4000, 10000, 500, 1000);
        List<VSphereHostSelection.ScoredHost> small =
                VSphereHostSelection.rank(List.of(a, b), new HostWeights(1, 1, 1, 1));
        List<VSphereHostSelection.ScoredHost> large =
                VSphereHostSelection.rank(List.of(a, b), new HostWeights(50, 50, 50, 50));
        assertThat(small.get(0).getHost(), is(large.get(0).getHost()));
        assertThat(small.get(0).getScore(), closeTo(large.get(0).getScore(), 1e-9));
        for (VSphereHostSelection.ScoredHost scored : small) {
            assertThat(scored.getScore() >= 0 && scored.getScore() <= 1, is(true));
        }
    }

    @Test
    void mixedWeightsBalanceTheMeasures() {
        // idle by percentage but tiny, versus large with plenty of MHz but proportionally busier
        HostCandidate tinyIdle = loaded("tinyIdle", 100, 1000, 100, 1000);
        HostCandidate bigBusier = loaded("bigBusier", 5000, 10000, 5000, 10000);
        assertThat(
                VSphereHostSelection.pickBest(List.of(tinyIdle, bigBusier), new HostWeights(10, 1, 10, 1)),
                is(bigBusier));
        assertThat(
                VSphereHostSelection.pickBest(List.of(tinyIdle, bigBusier), new HostWeights(1, 10, 1, 10)),
                is(tinyIdle));
    }

    @Test
    void hostsWithoutStatisticsAreNotRankedAndTiesKeepIncomingOrder() {
        HostCandidate noStats = candidate("noStats", true, false, null, 1000, null, 1000);
        HostCandidate first = loaded("first", 500, 1000, 500, 1000);
        HostCandidate second = loaded("second", 500, 1000, 500, 1000);
        List<VSphereHostSelection.ScoredHost> ranked =
                VSphereHostSelection.rank(List.of(noStats, first, second), new HostWeights(1, 1, 1, 1));
        assertThat(ranked.size(), is(2));
        assertThat(ranked.get(0).getHost(), is(first));
        assertThat(VSphereHostSelection.pickBest(List.of(noStats), HostWeights.DEFAULT), nullValue());
    }

    @Test
    void negativeOrNonFiniteWeightsCountAsZero() {
        HostWeights weights = new HostWeights(-5, Double.NaN, Double.POSITIVE_INFINITY, 2);
        assertThat(weights.getFreeCpuMhz(), is(0d));
        assertThat(weights.getFreeCpuPercent(), is(0d));
        assertThat(weights.getFreeMemoryMB(), is(0d));
        assertThat(weights.total(), is(2d));
        assertThat(new HostWeights(-1, -1, -1, -1).isDefault(), is(true));
    }

    @Test
    void exclusionReasonsAreSpelledOut() {
        HostCandidate down = candidate("down", false, false, 1, 1000, 1, 1000);
        HostCandidate maintenance = candidate("maintenance", true, true, 1, 1000, 1, 1000);
        HostCandidate ok = candidate("ok", true, false, 1, 1000, 1, 1000);
        assertThat(VSphereHostSelection.excludedBecause(down, Set.of()), is("not connected"));
        assertThat(VSphereHostSelection.excludedBecause(maintenance, Set.of()), is("in maintenance mode"));
        assertThat(VSphereHostSelection.excludedBecause(ok, Set.of("other")), is("not in the list of candidate hosts"));
        assertThat(VSphereHostSelection.excludedBecause(ok, Set.of("ok")), nullValue());
        assertThat(VSphereHostSelection.excludedBecause(ok, null), nullValue());
    }

    @Test
    void sizeShortfallExplainsWhichLimitIsMissed() {
        HostCandidate host = sized("host", 8, 16384);
        assertThat(
                VSphereHostSelection.sizeShortfall(host, true, 16, false, null), containsString("8 physical core(s)"));
        assertThat(VSphereHostSelection.sizeShortfall(host, false, null, true, 32768), containsString("16384 MB"));
        assertThat(VSphereHostSelection.sizeShortfall(host, true, 8, true, 16384), nullValue());
        assertThat(VSphereHostSelection.sizeShortfall(host, false, 64, false, 999999), nullValue());
    }

    @Test
    void availableMemoryCheckLooksAtWhatIsFreeNotWhatIsInstalled() {
        // 64 GB installed, but only 4 GB free: enough RAM sticks, not enough room right now.
        HostCandidate crowded = loaded("crowded", 100, 1000, 61440, 65536);
        HostCandidate roomy = loaded("roomy", 100, 1000, 8192, 65536);
        assertThat(VSphereHostSelection.sizeShortfall(crowded, false, null, true, false, 16384), nullValue());
        assertThat(
                VSphereHostSelection.sizeShortfall(crowded, false, null, false, true, 16384),
                containsString("only 4096 MB of free RAM"));
        assertThat(VSphereHostSelection.sizeShortfall(roomy, false, null, false, true, 16384), nullValue());
    }

    @Test
    void availableMemoryCheckAcceptsAnExactFitAndRejectsUnknownUsage() {
        HostCandidate exact = loaded("exact", 100, 1000, 49152, 65536); // exactly 16384 free
        HostCandidate unknown = candidate("unknown", true, false, 100, 1000, null, 65536);
        assertThat(VSphereHostSelection.sizeShortfall(exact, false, null, false, true, 16384), nullValue());
        assertThat(
                VSphereHostSelection.sizeShortfall(unknown, false, null, false, true, 16384),
                containsString("usage is unknown"));
        // unknown VM size disables the check, as for the other size checks
        assertThat(VSphereHostSelection.sizeShortfall(unknown, false, null, false, true, null), nullValue());
    }

    @Test
    void callSiteWeightsAreAbsentWhenNoneIsSet() throws Exception {
        assertThat(HostWeights.parseOverride(null, null, null, null), nullValue());
        assertThat(HostWeights.parseOverride("", " ", null, ""), nullValue());
    }

    @Test
    void anyCallSiteWeightReplacesTheWholeSetWithBlanksCountingAsZero() throws Exception {
        HostWeights weights = HostWeights.parseOverride(null, "3", "", null);
        assertThat(weights.getFreeCpuMhz(), is(0d));
        assertThat(weights.getFreeCpuPercent(), is(3d));
        assertThat(weights.getFreeMemoryMB(), is(0d));
        assertThat(weights.getFreeMemoryPercent(), is(0d));
    }

    @Test
    void explicitZerosOverrideToTheOriginalRanking() throws Exception {
        HostWeights weights = HostWeights.parseOverride("0", "0", "0", "0");
        assertThat(weights.isDefault(), is(true));
    }

    @Test
    void callSiteWeightsMustBeWholeNumbersOfZeroOrMore() {
        for (String bad : new String[] {"-1", "1.5", "lots", "${UNSET}"}) {
            org.junit.jupiter.api.Assertions.assertThrows(
                    VSphereException.class, () -> HostWeights.parseOverride("1", bad, null, null));
        }
    }

    /** A host with the given CPU use/capacity (MHz) and memory use/capacity (MB). */
    private static HostCandidate loaded(String name, int cpuUsed, int cpuCap, int memUsed, long memCap) {
        return new HostCandidate(name, true, false, cpuUsed, cpuCap, memUsed, memCap);
    }

    private static HostCandidate sized(String name, int cores, long memMB) {
        return new HostCandidate(name, true, false, 100, 1000, 100, memMB, cores);
    }

    private static HostCandidate candidate(
            String name,
            boolean connected,
            boolean inMaintenanceMode,
            Integer cpuUsageMhz,
            int cpuCapacityMhz,
            Integer memUsageMB,
            long memCapacityMB) {
        return new HostCandidate(
                name, connected, inMaintenanceMode, cpuUsageMhz, cpuCapacityMhz, memUsageMB, memCapacityMB);
    }
}
