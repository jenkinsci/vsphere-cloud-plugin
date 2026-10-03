package org.jenkinsci.plugins.vsphere.builders;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;

import org.junit.jupiter.api.Test;

class ReconfigureMemoryLogTest {

    @Test
    void reportsTheNewAndTheCurrentMemorySize() {
        assertThat(
                ReconfigureMemory.describeChange(null, 4096, 8192L),
                is("Will set the memory of the VM to 8192 MB (currently 4096 MB)"));
    }

    @Test
    void saysWhenThePreviousValueCannotBeTold() {
        assertThat(
                ReconfigureMemory.describeChange(null, null, 16384L),
                is("Will set the memory of the VM to 16384 MB (previous value unknown)"));
    }
}
