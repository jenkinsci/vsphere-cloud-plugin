package org.jenkinsci.plugins.vsphere.tools;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;

import org.junit.jupiter.api.Test;

class HostWeightsTest {

    @Test
    void weightsAreLoggedAsWholeNumbers() {
        assertThat(
                new HostWeights(10, 0, 4096, 3).toString(),
                is("weights[free CPU MHz=10, free CPU %=0, free RAM MB=4096, free RAM %=3]"));
    }

    @Test
    void weightsParsedFromTextAreLoggedAsWholeNumbersToo() throws Exception {
        HostWeights parsed = HostWeights.parseOverride("1", " 2 ", null, "");
        assertThat(parsed.toString(), is("weights[free CPU MHz=1, free CPU %=2, free RAM MB=0, free RAM %=0]"));
    }

    @Test
    void aFractionalWeightSetProgrammaticallyIsNotTruncated() {
        assertThat(
                new HostWeights(0.5, 0, 0, 0).toString(),
                is("weights[free CPU MHz=0.5, free CPU %=0, free RAM MB=0, free RAM %=0]"));
    }

    @Test
    void invalidWeightsAreLoggedAsTheZeroTheyAreTreatedAs() {
        assertThat(
                new HostWeights(-5, Double.NaN, Double.POSITIVE_INFINITY, 2).toString(),
                is("weights[free CPU MHz=0, free CPU %=0, free RAM MB=0, free RAM %=2]"));
    }
}
