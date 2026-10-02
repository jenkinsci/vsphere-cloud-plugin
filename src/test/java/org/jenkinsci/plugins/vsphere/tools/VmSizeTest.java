package org.jenkinsci.plugins.vsphere.tools;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.nullValue;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.vmware.vim25.VirtualMachineConfigSpec;
import org.junit.jupiter.api.Test;

class VmSizeTest {

    @Test
    void blankMeansKeepTheSources() throws Exception {
        assertThat(VmSize.parseOptionalPositive("cpuCores", null), nullValue());
        assertThat(VmSize.parseOptionalPositive("cpuCores", ""), nullValue());
        assertThat(VmSize.parseOptionalPositive("cpuCores", "  "), nullValue());
    }

    @Test
    void parsesPositiveWholeNumbers() throws Exception {
        assertThat(VmSize.parseOptionalPositive("memoryMB", "8192"), is(8192));
        assertThat(VmSize.parseOptionalPositive("memoryMB", " 4 "), is(4));
    }

    @Test
    void rejectsAnythingElseNamingTheSetting() {
        for (String bad : new String[] {"0", "-2", "four", "1.5", "${UNSET}"}) {
            VSphereException e =
                    assertThrows(VSphereException.class, () -> VmSize.parseOptionalPositive("cpuCores", bad));
            assertThat(e.getMessage(), containsString("cpuCores"));
            assertThat(e.getMessage(), containsString(bad));
        }
    }

    @Test
    void ofParsesTheFourSettingsAndEmptyMeansKeepEverything() throws Exception {
        assertThat(VmSize.of(null, "", " ", null).isEmpty(), is(true));
        VmSize size = VmSize.of("8", "4", "2000", "16384");
        assertThat(size.getCpuCores(), is(8));
        assertThat(size.getCoresPerSocket(), is(4));
        assertThat(size.getCpuLimitMHz(), is(2000));
        assertThat(size.getMemorySize(), is(16384));
        assertThat(size.isEmpty(), is(false));
        assertThrows(VSphereException.class, () -> VmSize.of("8", "x", null, null));
    }

    @Test
    void appliesOnlyWhatIsSetToTheConfigSpec() throws Exception {
        VirtualMachineConfigSpec spec = new VirtualMachineConfigSpec();
        VmSize.of("8", "4", "2000", "16384").applyTo(spec);
        assertThat(spec.getNumCPUs(), is(8));
        assertThat(spec.getNumCoresPerSocket(), is(4));
        assertThat(spec.getMemoryMB(), is(16384L));
        assertThat(spec.getCpuAllocation().getReservation(), is(2000L));

        VirtualMachineConfigSpec untouched = new VirtualMachineConfigSpec();
        VmSize.of(null, null, null, "4096").applyTo(untouched);
        assertThat(untouched.getNumCPUs(), nullValue());
        assertThat(untouched.getNumCoresPerSocket(), nullValue());
        assertThat(untouched.getCpuAllocation(), nullValue());
        assertThat(untouched.getMemoryMB(), is(4096L));
    }

    @Test
    void coresMustBeAMultipleOfCoresPerSocketCountingTheSourcesValuesToo() throws Exception {
        VmSize.of("8", "4", null, null).validateAgainstSource(2, 1);
        assertThrows(
                VSphereException.class, () -> VmSize.of("6", "4", null, null).validateAgainstSource(2, 1));
        // cores per socket kept from the source: 6 vCPUs do not fit its 4
        VSphereException e = assertThrows(
                VSphereException.class, () -> VmSize.of("6", null, null, null).validateAgainstSource(2, 4));
        assertThat(e.getMessage(), containsString("kept from the source"));
        // only cores per socket changed: the source's vCPU count must fit
        assertThrows(
                VSphereException.class, () -> VmSize.of(null, "4", null, null).validateAgainstSource(6, 1));
        // nothing about CPUs set: nothing to check; unknown source values: nothing to check against
        VmSize.of(null, null, "1000", "4096").validateAgainstSource(6, 4);
        VmSize.of("6", null, null, null).validateAgainstSource(null, null);
    }

    @Test
    void describesWhatTheVmIsCreatedWith() throws Exception {
        assertThat(
                VmSize.of("8", "4", "2000", "16384").describe(),
                is("8 vCPU(s), 4 core(s) per socket, 16384 MB of memory, a CPU reservation of 2000 MHz"));
        assertThat(VmSize.of(null, null, null, "4096").describe(), is("4096 MB of memory"));
    }
}
