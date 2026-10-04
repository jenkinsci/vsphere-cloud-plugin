package org.jenkinsci.plugins.vsphere.tools;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;

import org.jenkinsci.plugins.vsphere.tools.MacAddresses.Kind;
import org.junit.jupiter.api.Test;
import org.jvnet.hudson.test.Issue;

@Issue("JENKINS-34001")
class MacAddressesTest {

    @Test
    void vmwareManualBlockIsAccepted() {
        assertThat(MacAddresses.classify("00:50:56:00:00:00"), is(Kind.VMWARE_MANUAL));
        assertThat(MacAddresses.classify("00:50:56:3F:FF:FF"), is(Kind.VMWARE_MANUAL));
        assertThat(MacAddresses.classify("00:50:56:3f:ff:ff"), is(Kind.VMWARE_MANUAL));
    }

    @Test
    void vpxGeneratedBlockIsRecognised() {
        assertThat(MacAddresses.classify("00:50:56:80:00:00"), is(Kind.VMWARE_VPX_GENERATED));
        assertThat(MacAddresses.classify("00:50:56:BF:FF:FF"), is(Kind.VMWARE_VPX_GENERATED));
        assertThat(MacAddresses.classify("00-50-56-81-02-03"), is(Kind.VMWARE_VPX_GENERATED));
    }

    @Test
    void restOfTheVmwareBlockIsReserved() {
        assertThat(MacAddresses.classify("00:50:56:40:00:00"), is(Kind.VMWARE_RESERVED));
        assertThat(MacAddresses.classify("00:50:56:7F:FF:FF"), is(Kind.VMWARE_RESERVED));
        assertThat(MacAddresses.classify("00:50:56:C0:00:00"), is(Kind.VMWARE_RESERVED));
    }

    @Test
    void hostGeneratedAndOtherVendors() {
        assertThat(MacAddresses.classify("00:0c:29:12:34:56"), is(Kind.HOST_GENERATED));
        assertThat(MacAddresses.classify("02:00:00:12:34:56"), is(Kind.OTHER));
    }

    @Test
    void malformedAddresses() {
        assertThat(MacAddresses.classify(null), is(Kind.MALFORMED));
        assertThat(MacAddresses.classify(""), is(Kind.MALFORMED));
        assertThat(MacAddresses.classify("00:50:56:00:00"), is(Kind.MALFORMED));
        assertThat(MacAddresses.classify("00:50:56:00:00:0G"), is(Kind.MALFORMED));
        assertThat(MacAddresses.classify("0050.5600.0001"), is(Kind.MALFORMED));
    }
}
