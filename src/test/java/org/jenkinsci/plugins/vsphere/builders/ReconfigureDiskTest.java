package org.jenkinsci.plugins.vsphere.builders;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.nullValue;
import static org.hamcrest.Matchers.sameInstance;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.vmware.vim25.Description;
import com.vmware.vim25.VirtualDevice;
import com.vmware.vim25.VirtualDeviceFileBackingInfo;
import com.vmware.vim25.VirtualDisk;
import com.vmware.vim25.VirtualIDEController;
import com.vmware.vim25.VirtualPCIController;
import com.vmware.vim25.VirtualSCSIController;
import java.util.HashMap;
import java.util.Map;
import org.jenkinsci.plugins.vsphere.tools.VSphereException;
import org.junit.jupiter.api.Test;

class ReconfigureDiskTest {

    private static ReconfigureDisk newStep() throws VSphereException {
        return new ReconfigureDisk("100", "");
    }

    private static VirtualDisk disk(int key, Integer controllerKey, Integer unitNumber, String fileName, String label) {
        VirtualDisk disk = new VirtualDisk();
        disk.setKey(key);
        disk.setControllerKey(controllerKey);
        disk.setUnitNumber(unitNumber);
        if (fileName != null) {
            VirtualDeviceFileBackingInfo backing = new VirtualDeviceFileBackingInfo();
            backing.setFileName(fileName);
            disk.setBacking(backing);
        }
        if (label != null) {
            Description info = new Description();
            info.setLabel(label);
            disk.setDeviceInfo(info);
        }
        return disk;
    }

    private static VirtualSCSIController scsiController(int key, int busNumber) {
        VirtualSCSIController controller = new VirtualSCSIController();
        controller.setKey(key);
        controller.setBusNumber(busNumber);
        return controller;
    }

    private static VirtualIDEController ideController(int key, int busNumber) {
        VirtualIDEController controller = new VirtualIDEController();
        controller.setKey(key);
        controller.setBusNumber(busNumber);
        return controller;
    }

    // -- defaults / DataBoundSetter wiring --

    @Test
    void defaultsToAddActionWithNoLabelOrNumber() throws Exception {
        ReconfigureDisk step = newStep();
        assertThat(step.getDeviceAction(), is(ReconfigureStep.DeviceAction.ADD));
        assertThat(step.getDeviceLabel(), nullValue());
        assertThat(step.getDeviceNumber(), nullValue());
    }

    @Test
    void settingDeviceActionToNullFallsBackToAdd() throws Exception {
        ReconfigureDisk step = newStep();
        step.setDeviceAction(ReconfigureStep.DeviceAction.EDIT);
        step.setDeviceAction(null);
        assertThat(step.getDeviceAction(), is(ReconfigureStep.DeviceAction.ADD));
    }

    // -- chooseDiskName --

    @Test
    void chooseDiskNameAutoPicksVmUnderscoreOneWhenNothingTaken() throws Exception {
        ReconfigureDisk step = newStep();
        String name = step.chooseDiskName("kube15", new HashMap<>(), null, null);
        assertThat(name, is("kube15_1"));
    }

    @Test
    void chooseDiskNameAutoPicksNextFreeSuffix() throws Exception {
        ReconfigureDisk step = newStep();
        Map<String, Boolean> taken = new HashMap<>();
        taken.put("kube15/kube15_1.vmdk", true);
        taken.put("kube15/kube15_2.vmdk", true);
        String name = step.chooseDiskName("kube15", taken, null, null);
        assertThat(name, is("kube15_3"));
    }

    @Test
    void chooseDiskNameUsesExplicitLabelVerbatim() throws Exception {
        ReconfigureDisk step = newStep();
        String name = step.chooseDiskName("kube15", new HashMap<>(), "boot-disk", null);
        assertThat(name, is("boot-disk"));
    }

    @Test
    void chooseDiskNameExplicitLabelCollisionThrows() throws Exception {
        ReconfigureDisk step = newStep();
        Map<String, Boolean> taken = new HashMap<>();
        taken.put("kube15/boot-disk.vmdk", true);
        assertThrows(VSphereException.class, () -> step.chooseDiskName("kube15", taken, "boot-disk", null));
    }

    @Test
    void chooseDiskNameUsesDeviceNumberAsVmUnderscoreN() throws Exception {
        ReconfigureDisk step = newStep();
        String name = step.chooseDiskName("kube15", new HashMap<>(), null, 3);
        assertThat(name, is("kube15_3"));
    }

    @Test
    void chooseDiskNameDeviceNumberCollisionThrows() throws Exception {
        ReconfigureDisk step = newStep();
        Map<String, Boolean> taken = new HashMap<>();
        taken.put("kube15/kube15_3.vmdk", true);
        assertThrows(VSphereException.class, () -> step.chooseDiskName("kube15", taken, null, 3));
    }

    // -- diskBaseName --

    @Test
    void diskBaseNameStripsDatastoreDirectoryAndExtension() throws Exception {
        ReconfigureDisk step = newStep();
        VirtualDisk disk = disk(0, null, null, "[datastore1] kube15/kube15_1.vmdk", null);
        assertThat(step.diskBaseName(disk), is("kube15_1"));
    }

    @Test
    void diskBaseNameReturnsNullWhenNotFileBacked() throws Exception {
        ReconfigureDisk step = newStep();
        VirtualDisk disk = new VirtualDisk();
        assertThat(step.diskBaseName(disk), nullValue());
    }

    // -- findDiskByIndex (one-based, vCenter list order) --

    @Test
    void findDiskByIndexIsOneBased() throws Exception {
        ReconfigureDisk step = newStep();
        VirtualDisk first = disk(1, null, null, "[ds] vm/vm_1.vmdk", null);
        VirtualDisk second = disk(2, null, null, "[ds] vm/vm_2.vmdk", null);
        VirtualDevice[] devices = {first, second};

        assertThat(step.findDiskByIndex(devices, 1), sameInstance(first));
        assertThat(step.findDiskByIndex(devices, 2), sameInstance(second));
    }

    @Test
    void findDiskByIndexSkipsNonDiskDevices() throws Exception {
        ReconfigureDisk step = newStep();
        VirtualDisk onlyDisk = disk(2, null, null, "[ds] vm/vm_1.vmdk", null);
        VirtualDevice[] devices = {scsiController(1000, 0), onlyDisk};

        assertThat(step.findDiskByIndex(devices, 1), sameInstance(onlyDisk));
    }

    @Test
    void findDiskByIndexOutOfRangeThrows() throws Exception {
        ReconfigureDisk step = newStep();
        VirtualDevice[] devices = {disk(1, null, null, "[ds] vm/vm_1.vmdk", null)};

        assertThrows(VSphereException.class, () -> step.findDiskByIndex(devices, 0));
        assertThrows(VSphereException.class, () -> step.findDiskByIndex(devices, 2));
    }

    // -- findDiskByLabel: device label / file base name matching --

    @Test
    void findDiskByLabelMatchesVSphereDeviceLabel() throws Exception {
        ReconfigureDisk step = newStep();
        VirtualDisk target = disk(1, null, null, "[ds] vm/vm_1.vmdk", "Hard disk 2");
        VirtualDevice[] devices = {disk(2, null, null, "[ds] vm/vm_2.vmdk", "Hard disk 1"), target};

        assertThat(step.findDiskByLabel(devices, "vm", "Hard disk 2", true), sameInstance(target));
    }

    @Test
    void findDiskByLabelMatchesFileBaseName() throws Exception {
        ReconfigureDisk step = newStep();
        VirtualDisk target = disk(1, null, null, "[datastore1] kube15/kube15_1.vmdk", null);
        VirtualDevice[] devices = {target};

        assertThat(step.findDiskByLabel(devices, "kube15", "kube15_1", true), sameInstance(target));
    }

    @Test
    void findDiskByLabelThrowsWhenNoMatch() throws Exception {
        ReconfigureDisk step = newStep();
        VirtualDevice[] devices = {disk(1, null, null, "[ds] vm/vm_1.vmdk", null)};

        assertThrows(VSphereException.class, () -> step.findDiskByLabel(devices, "vm", "does-not-exist", true));
    }

    @Test
    void findDiskByLabelAutoSelectsSingleDiskWhenBlankAndAllowed() throws Exception {
        ReconfigureDisk step = newStep();
        VirtualDisk onlyDisk = disk(1, null, null, "[ds] vm/boot.vmdk", null);
        VirtualDevice[] devices = {onlyDisk};

        assertThat(step.findDiskByLabel(devices, "vm", "", true), sameInstance(onlyDisk));
    }

    @Test
    void findDiskByLabelRequiresLabelWhenMultipleDisksEvenIfAutoSelectAllowed() throws Exception {
        ReconfigureDisk step = newStep();
        VirtualDevice[] devices = {
            disk(1, null, null, "[ds] vm/vm_1.vmdk", null), disk(2, null, null, "[ds] vm/vm_2.vmdk", null)
        };

        assertThrows(VSphereException.class, () -> step.findDiskByLabel(devices, "vm", "", true));
    }

    @Test
    void findDiskByLabelRequiresLabelWhenAutoSelectNotAllowedEvenWithOneDisk() throws Exception {
        // Mirrors REMOVE: never auto-select, even if there's only one candidate disk.
        ReconfigureDisk step = newStep();
        VirtualDevice[] devices = {disk(1, null, null, "[ds] vm/boot.vmdk", null)};

        assertThrows(VSphereException.class, () -> step.findDiskByLabel(devices, "vm", "", false));
    }

    // -- findDiskByLabel: SCSI(bus:unit) / IDE(bus:unit) controller monikers --

    @Test
    void findDiskByLabelMatchesScsiMoniker() throws Exception {
        ReconfigureDisk step = newStep();
        VirtualSCSIController controller = scsiController(1000, 0);
        VirtualDisk target = disk(1, 1000, 2, "[ds] vm/vm_1.vmdk", null);
        VirtualDevice[] devices = {controller, target};

        assertThat(step.findDiskByLabel(devices, "vm", "SCSI(0:2)", true), sameInstance(target));
    }

    @Test
    void findDiskByLabelMatchesIdeMoniker() throws Exception {
        ReconfigureDisk step = newStep();
        VirtualIDEController controller = ideController(2000, 1);
        VirtualDisk target = disk(1, 2000, 0, "[ds] vm/vm_1.vmdk", null);
        VirtualDevice[] devices = {controller, target};

        assertThat(step.findDiskByLabel(devices, "vm", "IDE(1:0)", true), sameInstance(target));
    }

    @Test
    void findDiskByLabelMonikerDisambiguatesMultipleScsiControllers() throws Exception {
        // The scenario that motivated the moniker feature: two SCSI controllers each with a disk
        // at unit 0 -- deviceNumber (or a bare unit number) alone can't tell them apart, but the
        // bus-qualified moniker can.
        ReconfigureDisk step = newStep();
        VirtualSCSIController bus0 = scsiController(1000, 0);
        VirtualSCSIController bus1 = scsiController(1001, 1);
        VirtualDisk onBus0 = disk(1, 1000, 0, "[ds] vm/vm_1.vmdk", null);
        VirtualDisk onBus1 = disk(2, 1001, 0, "[ds] vm/vm_2.vmdk", null);
        VirtualDevice[] devices = {bus0, bus1, onBus0, onBus1};

        assertThat(step.findDiskByLabel(devices, "vm", "SCSI(0:0)", true), sameInstance(onBus0));
        assertThat(step.findDiskByLabel(devices, "vm", "SCSI(1:0)", true), sameInstance(onBus1));
    }

    @Test
    void findDiskByLabelMonikerDisambiguatesIdeFromScsiAtTheSameBusAndUnit() throws Exception {
        // IDE master/slave (unit 0/1) shares the same low unit numbers SCSI disks start from, so a
        // bare unit number would be ambiguous here too -- the controller-type-qualified moniker isn't.
        ReconfigureDisk step = newStep();
        VirtualSCSIController scsi = scsiController(1000, 0);
        VirtualIDEController ide = ideController(2000, 0);
        VirtualDisk onScsi = disk(1, 1000, 0, "[ds] vm/vm_1.vmdk", null);
        VirtualDisk onIde = disk(2, 2000, 0, "[ds] vm/vm_2.vmdk", null);
        VirtualDevice[] devices = {scsi, ide, onScsi, onIde};

        assertThat(step.findDiskByLabel(devices, "vm", "SCSI(0:0)", true), sameInstance(onScsi));
        assertThat(step.findDiskByLabel(devices, "vm", "IDE(0:0)", true), sameInstance(onIde));
    }

    @Test
    void findDiskByLabelMonikerFindsNothingForAWrongBusOrUnit() throws Exception {
        ReconfigureDisk step = newStep();
        VirtualSCSIController controller = scsiController(1000, 0);
        VirtualDisk onlyDisk = disk(1, 1000, 0, "[ds] vm/vm_1.vmdk", null);
        VirtualDevice[] devices = {controller, onlyDisk};

        assertThrows(VSphereException.class, () -> step.findDiskByLabel(devices, "vm", "SCSI(0:5)", true));
        assertThrows(VSphereException.class, () -> step.findDiskByLabel(devices, "vm", "SCSI(3:0)", true));
        assertThrows(VSphereException.class, () -> step.findDiskByLabel(devices, "vm", "IDE(0:0)", true));
    }

    @Test
    void findDiskByLabelMonikerIgnoresUnrelatedDevicesLikeAPciController() throws Exception {
        ReconfigureDisk step = newStep();
        VirtualPCIController pci = new VirtualPCIController();
        pci.setKey(100);
        VirtualSCSIController scsi = scsiController(1000, 0);
        VirtualDisk target = disk(1, 1000, 0, "[ds] vm/vm_1.vmdk", null);
        VirtualDevice[] devices = {pci, scsi, target};

        assertThat(step.findDiskByLabel(devices, "vm", "SCSI(0:0)", true), sameInstance(target));
    }
}
