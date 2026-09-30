package org.jenkinsci.plugins.vsphere.builders;

import com.vmware.vim25.Description;
import com.vmware.vim25.VirtualDevice;
import com.vmware.vim25.VirtualE1000;
import com.vmware.vim25.VirtualEthernetCard;
import com.vmware.vim25.VirtualSCSIController;
import org.jenkinsci.plugins.vsphere.tools.VSphereException;
import org.junit.jupiter.api.Test;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.nullValue;
import static org.hamcrest.Matchers.sameInstance;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ReconfigureNetworkAdaptersTest {

    private static ReconfigureNetworkAdapters newStep() throws VSphereException {
        return new ReconfigureNetworkAdapters(ReconfigureStep.DeviceAction.EDIT, "", "",
                false, "", false, "", "");
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
}
