package org.jenkinsci.plugins.vsphere.tools;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.empty;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.notNullValue;
import static org.hamcrest.Matchers.nullValue;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import org.jenkinsci.plugins.vSphereCloud;
import org.jenkinsci.plugins.vsphere.tools.VSphereHostSelection.HostCandidate;
import org.junit.jupiter.api.Test;

class HostLimitsTest {

    /** 10000 MHz and 10000 MB hosts, with the given usage. */
    private static HostCandidate host(String name, Integer cpuUsedMhz, Integer memUsedMB) {
        return new HostCandidate(name, true, false, cpuUsedMhz, 10000, memUsedMB, 10000L);
    }

    @Test
    void noLimitsNeverRuleOutAHost() {
        assertThat(HostLimits.NONE.isActive(), is(false));
        // Even a completely loaded host, or one without statistics, as zero matches nothing.
        assertThat(HostLimits.NONE.shortfall(host("full", 10000, 10000)), nullValue());
        assertThat(HostLimits.NONE.shortfall(host("unknown", null, null)), nullValue());
    }

    @Test
    void absoluteLimitsCompareFreeAmounts() {
        HostCandidate h = host("h", 7000, 9000); // free: 3000 MHz, 1000 MB
        assertThat(new HostLimits(3000, 0, 1000, 0).shortfall(h), nullValue());
        assertThat(new HostLimits(3001, 0, 0, 0).shortfall(h), containsString("3000 MHz of free CPU"));
        assertThat(new HostLimits(0, 0, 1001, 0).shortfall(h), containsString("1000 MB of free RAM"));
    }

    @Test
    void relativeLimitsCompareFreeShares() {
        HostCandidate h = host("h", 7000, 9000); // 30% CPU free, 10% RAM free
        assertThat(new HostLimits(0, 30, 0, 10).shortfall(h), nullValue());
        assertThat(new HostLimits(0, 31, 0, 0).shortfall(h), containsString("30.0% of its CPU free"));
        assertThat(new HostLimits(0, 0, 0, 11).shortfall(h), containsString("10.0% of its RAM free"));
    }

    @Test
    void aHostMustSatisfyEveryLimitSet() {
        HostCandidate h = host("h", 5000, 5000);
        assertThat(new HostLimits(5000, 50, 5000, 50).shortfall(h), nullValue());
        assertThat(new HostLimits(5000, 50, 5000, 51).shortfall(h), notNullValue());
    }

    @Test
    void aHostWithUnknownUsageFailsAnActiveLimit() {
        assertThat(new HostLimits(1, 0, 0, 0).shortfall(host("h", null, null)), containsString("unknown"));
    }

    @Test
    void nonsenseValuesAreSanitized() {
        HostLimits limits = new HostLimits(-5, 250, -1, -20);
        assertThat(limits.getMinFreeCpuMhz(), is(0L));
        assertThat(limits.getMinFreeCpuPercent(), is(100));
        assertThat(limits.getMinFreeMemoryMB(), is(0L));
        assertThat(limits.getMinFreeMemoryPercent(), is(0));
    }

    @Test
    void filterKeepsOnlyHostsWithinTheLimits() {
        List<HostCandidate> hosts =
                Arrays.asList(host("busy", 9500, 1000), host("idle", 1000, 1000), host("swapping", 1000, 9900));
        List<HostCandidate> kept = VSphereHostSelection.filterByLimits(hosts, new HostLimits(0, 10, 0, 10));
        assertThat(names(kept), contains("idle"));
        assertThat(VSphereHostSelection.filterByLimits(hosts, HostLimits.NONE), is(hosts));
        assertThat(VSphereHostSelection.filterByLimits(hosts, null), is(hosts));
    }

    private static List<String> names(List<HostCandidate> hosts) {
        List<String> names = new ArrayList<>();
        for (HostCandidate h : hosts) {
            names.add(h.getName());
        }
        return names;
    }

    // --- per-call overrides ---

    @Test
    void noOverrideWhenAllCallSiteLimitsAreBlank() throws Exception {
        assertThat(HostLimits.parseOverride(null, "", "  ", null), nullValue());
    }

    @Test
    void anySetLimitReplacesTheCloudsAsAWholeWithBlanksAsZero() throws Exception {
        HostLimits parsed = HostLimits.parseOverride(null, " 25 ", "", null);
        assertThat(parsed.getMinFreeCpuMhz(), is(0L));
        assertThat(parsed.getMinFreeCpuPercent(), is(25));
        assertThat(parsed.getMinFreeMemoryMB(), is(0L));
        assertThat(parsed.getMinFreeMemoryPercent(), is(0));
    }

    @Test
    void zerosAreAnOverrideThatLiftsTheCloudsLimits() throws Exception {
        HostLimits parsed = HostLimits.parseOverride("0", null, null, null);
        assertThat(parsed, notNullValue());
        assertThat(parsed.isActive(), is(false));
    }

    @Test
    void badCallSiteLimitsAreRejected() {
        assertThrows(VSphereException.class, () -> HostLimits.parseOverride("-1", null, null, null));
        assertThrows(VSphereException.class, () -> HostLimits.parseOverride(null, "abc", null, null));
        assertThrows(VSphereException.class, () -> HostLimits.parseOverride(null, "101", null, null));
        assertThrows(VSphereException.class, () -> HostLimits.parseOverride(null, null, "1.5", null));
    }

    @Test
    void callSiteLimitsReplaceTheClouds() throws Exception {
        vSphereCloud cloud = new vSphereCloud(
                new org.jenkinsci.plugins.vsphere.VSphereConnectionConfig("vcenter.example.com", "creds", null),
                "wiring",
                0,
                0,
                false,
                null);
        cloud.setHostMinFreeCpuMhz(500);
        cloud.setHostMinFreeMemoryPercent(20);
        assertThat(
                vSphereCloud
                        .hostSelectionOptions(cloud, null, null, null, null, null, null)
                        .getLimits()
                        .getMinFreeCpuMhz(),
                is(500L));
        HostLimits own = HostLimits.parseOverride(null, "10", null, null);
        HostLimits resolved = vSphereCloud
                .hostSelectionOptions(cloud, null, null, null, null, null, own)
                .getLimits();
        assertThat(resolved.getMinFreeCpuMhz(), is(0L));
        assertThat(resolved.getMinFreeCpuPercent(), is(10));
        assertThat(resolved.getMinFreeMemoryPercent(), is(0));
    }

    // --- the call site's wait setting ---

    @Test
    void waitSecondsAreParsedFromText() throws Exception {
        assertThat(HostSelectionOptions.parseWaitSeconds(null), nullValue());
        assertThat(HostSelectionOptions.parseWaitSeconds("  "), nullValue());
        assertThat(HostSelectionOptions.parseWaitSeconds("0"), is(0L));
        assertThat(HostSelectionOptions.parseWaitSeconds(" 90 "), is(90L));
        assertThat(HostSelectionOptions.parseWaitSeconds("-1"), is(VSphereHostSelection.WAIT_FOREVER));
        assertThat(HostSelectionOptions.parseWaitSeconds("-30"), is(VSphereHostSelection.WAIT_FOREVER));
        assertThat(HostSelectionOptions.parseWaitSeconds("Infinite"), is(VSphereHostSelection.WAIT_FOREVER));
        assertThat(HostSelectionOptions.parseWaitSeconds("forever"), is(VSphereHostSelection.WAIT_FOREVER));
        assertThrows(VSphereException.class, () -> HostSelectionOptions.parseWaitSeconds("soon"));
    }

    @Test
    void optionsDefaultToNoLimitsAndNoWaiting() {
        assertThat(HostSelectionOptions.NONE.getLimits().isActive(), is(false));
        assertThat(HostSelectionOptions.NONE.getWaitSeconds(), is(0L));
        HostSelectionOptions o = HostSelectionOptions.NONE
                .withLimits(new HostLimits(1, 2, 3, 4))
                .withWaitSeconds(-7)
                .withVmSize(2, 1024L); // other withers keep the new settings
        assertThat(o.getLimits().getMinFreeMemoryMB(), is(3L));
        assertThat(o.getWaitSeconds(), is(VSphereHostSelection.WAIT_FOREVER));
        assertThat(names(new ArrayList<>()), is(empty()));
    }
}
