package org.jenkinsci.plugins.vsphere.tools;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.nullValue;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

class VmSizeTest {

    @Test
    void blankMeansKeepTheSources() throws Exception {
        assertThat(VmSize.parseOptionalPositive("numCpus", null), nullValue());
        assertThat(VmSize.parseOptionalPositive("numCpus", ""), nullValue());
        assertThat(VmSize.parseOptionalPositive("numCpus", "  "), nullValue());
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
                    assertThrows(VSphereException.class, () -> VmSize.parseOptionalPositive("numCpus", bad));
            assertThat(e.getMessage(), containsString("numCpus"));
            assertThat(e.getMessage(), containsString(bad));
        }
    }
}
