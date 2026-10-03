package org.jenkinsci.plugins.vsphere.builders;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.nullValue;
import static org.hamcrest.Matchers.sameInstance;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.vmware.vim25.Description;
import com.vmware.vim25.VirtualDevice;
import com.vmware.vim25.VirtualE1000;
import com.vmware.vim25.VirtualEthernetCard;
import com.vmware.vim25.VirtualSCSIController;
import hudson.util.FormValidation.Kind;
import org.jenkinsci.plugins.vsphere.tools.VSphereException;
import org.junit.jupiter.api.Test;
import org.jvnet.hudson.test.Issue;

class ReconfigureNetworkAdaptersTest {

    private static ReconfigureNetworkAdapters newStep() throws VSphereException {
        return new ReconfigureNetworkAdapters(ReconfigureStep.DeviceAction.EDIT, "", "", false, "", false, "", "");
    }

    private static VirtualEthernetCard nic(String label) {
        VirtualEthernetCard nic = new VirtualE1000();
        Description info = new Description();
        info.setLabel(label);
        nic.setDeviceInfo(info);
        return nic;
    }

    // -- findNetworkDeviceByLabel --

    @Test
    void findNetworkDeviceByLabelMatchesExactLabel() throws Exception {
        ReconfigureNetworkAdapters step = newStep();
        VirtualEthernetCard first = nic("Network adapter 1");
        VirtualEthernetCard second = nic("Network adapter 2");
        VirtualDevice[] devices = {first, second};

        assertThat(step.findNetworkDeviceByLabel(devices, "Network adapter 2"), sameInstance(second));
    }

    @Test
    void findNetworkDeviceByLabelBlankMatchesFirstNic() throws Exception {
        ReconfigureNetworkAdapters step = newStep();
        VirtualSCSIController controller = new VirtualSCSIController();
        VirtualEthernetCard first = nic("Network adapter 1");
        VirtualDevice[] devices = {controller, first};

        assertThat(step.findNetworkDeviceByLabel(devices, ""), sameInstance(first));
    }

    @Test
    void findNetworkDeviceByLabelReturnsNullWhenNoMatch() throws Exception {
        ReconfigureNetworkAdapters step = newStep();
        VirtualDevice[] devices = {nic("Network adapter 1")};

        assertThat(step.findNetworkDeviceByLabel(devices, "Network adapter 9"), nullValue());
    }

    // -- findNetworkDeviceByIndex (one-based, vCenter list order) --

    @Test
    void findNetworkDeviceByIndexIsOneBased() throws Exception {
        ReconfigureNetworkAdapters step = newStep();
        VirtualEthernetCard first = nic("Network adapter 1");
        VirtualEthernetCard second = nic("Network adapter 2");
        VirtualDevice[] devices = {first, second};

        assertThat(step.findNetworkDeviceByIndex(devices, 1), sameInstance(first));
        assertThat(step.findNetworkDeviceByIndex(devices, 2), sameInstance(second));
    }

    @Test
    void findNetworkDeviceByIndexSkipsNonNicDevicesLikeAScsiController() throws Exception {
        ReconfigureNetworkAdapters step = newStep();
        VirtualEthernetCard onlyNic = nic("Network adapter 1");
        VirtualDevice[] devices = {new VirtualSCSIController(), onlyNic};

        assertThat(step.findNetworkDeviceByIndex(devices, 1), sameInstance(onlyNic));
    }

    @Test
    void findNetworkDeviceByIndexOutOfRangeThrows() throws Exception {
        ReconfigureNetworkAdapters step = newStep();
        VirtualDevice[] devices = {nic("Network adapter 1")};

        assertThrows(VSphereException.class, () -> step.findNetworkDeviceByIndex(devices, 0));
        assertThrows(VSphereException.class, () -> step.findNetworkDeviceByIndex(devices, 2));
    }

    // -- defaults / DataBoundSetter wiring --

    @Test
    void deviceNumberDefaultsToNull() throws Exception {
        ReconfigureNetworkAdapters step = newStep();
        assertThat(step.getDeviceNumber(), nullValue());
    }

    @Test
    void deviceNumberIsSettable() throws Exception {
        ReconfigureNetworkAdapters step = newStep();
        step.setDeviceNumber("2");
        assertThat(step.getDeviceNumber(), is("2"));
    }

    // -- applyMacAddress (JENKINS-34001) --

    @Test
    @Issue("JENKINS-34001")
    void applyMacAddressMarksTheAddressAsManual() {
        VirtualEthernetCard nic = nic("Network adapter 1");
        nic.setAddressType("generated");

        ReconfigureNetworkAdapters.applyMacAddress(nic, "00:50:56:00:00:01");

        assertThat(nic.getMacAddress(), is("00:50:56:00:00:01"));
        assertThat(nic.getAddressType(), is("manual"));
    }

    @Test
    @Issue("JENKINS-34001")
    void applyMacAddressCanDeclareTheAddressAsAssignedInstead() {
        VirtualEthernetCard nic = nic("Network adapter 1");

        ReconfigureNetworkAdapters.applyMacAddress(nic, "00:50:56:80:00:01", false);

        assertThat(nic.getMacAddress(), is("00:50:56:80:00:01"));
        assertThat(nic.getAddressType(), is("assigned"));
    }

    @Test
    @Issue("JENKINS-34001")
    void macAddressWarningsExplainWhyVCenterWouldRefuse() {
        assertThat(ReconfigureNetworkAdapters.macAddressWarnings("00:50:56:01:02:03").kind, is(Kind.OK));
        assertThat(ReconfigureNetworkAdapters.macAddressWarnings("02:00:00:01:02:03").kind, is(Kind.OK));
        assertThat(ReconfigureNetworkAdapters.macAddressWarnings("${MAC}").kind, is(Kind.OK));
        assertThat(ReconfigureNetworkAdapters.macAddressWarnings("00:50:56:90:02:03").kind, is(Kind.WARNING));
        assertThat(ReconfigureNetworkAdapters.macAddressWarnings("00:50:56:50:02:03").kind, is(Kind.WARNING));
        assertThat(ReconfigureNetworkAdapters.macAddressWarnings("00:0C:29:01:02:03").kind, is(Kind.WARNING));
        assertThat(ReconfigureNetworkAdapters.macAddressWarnings("nonsense").kind, is(Kind.WARNING));
    }

    @Test
    @Issue("JENKINS-34001")
    void fallbackVariantKeepsTheConfigurationAndOnlyExistsWhenAMacIsSet() throws Exception {
        ReconfigureNetworkAdapters withMac = new ReconfigureNetworkAdapters(
                ReconfigureStep.DeviceAction.EDIT, "nic", "00:50:56:90:00:01", true, "pg", false, "", "");
        withMac.setDeviceNumber("2");

        ReconfigureNetworkAdapters variant = (ReconfigureNetworkAdapters) withMac.fallbackVariant();

        assertThat(variant == withMac, is(false));
        assertThat(variant.getMacAddress(), is("00:50:56:90:00:01"));
        assertThat(variant.getDeviceNumber(), is("2"));
        assertThat(variant.getPortGroup(), is("pg"));
        // the retry variant is never retried again, and a step without MAC has nothing to relax
        assertThat(variant.fallbackVariant(), nullValue());
        assertThat(newStep().fallbackVariant(), nullValue());
    }

    @Test
    @Issue("JENKINS-34001")
    void applyMacAddressMarksANewAdapterAsManualToo() {
        VirtualEthernetCard nic = new VirtualE1000();

        ReconfigureNetworkAdapters.applyMacAddress(nic, "00:50:56:00:00:02");

        assertThat(nic.getAddressType(), is("manual"));
    }
}
