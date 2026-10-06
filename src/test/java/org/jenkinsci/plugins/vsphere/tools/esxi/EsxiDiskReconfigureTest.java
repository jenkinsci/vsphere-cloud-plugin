/*   Copyright 2026, Jim Klimov
 *
 *   Licensed under the Apache License, Version 2.0 (the "License");
 *   you may not use this file except in compliance with the License.
 *   You may obtain a copy of the License at
 *
 *       http://www.apache.org/licenses/LICENSE-2.0
 *
 *   Unless required by applicable law or agreed to in writing, software
 *   distributed under the License is distributed on an "AS IS" BASIS,
 *   WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *   See the License for the specific language governing permissions and
 *   limitations under the License.
 */
package org.jenkinsci.plugins.vsphere.tools.esxi;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.nullValue;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.vmware.vim25.ParaVirtualSCSIController;
import com.vmware.vim25.VirtualDeviceConfigSpec;
import com.vmware.vim25.VirtualDeviceConfigSpecFileOperation;
import com.vmware.vim25.VirtualDeviceConfigSpecOperation;
import com.vmware.vim25.VirtualDisk;
import com.vmware.vim25.VirtualDiskFlatVer2BackingInfo;
import com.vmware.vim25.VirtualLsiLogicController;
import com.vmware.vim25.VirtualMachineConfigSpec;
import com.vmware.vim25.VirtualSCSIController;
import com.vmware.vim25.mo.VirtualMachine;
import hudson.EnvVars;
import hudson.model.TaskListener;
import java.util.List;
import org.jenkinsci.plugins.vsphere.builders.ReconfigureDisk;
import org.jenkinsci.plugins.vsphere.builders.ReconfigureStep;
import org.jenkinsci.plugins.vsphere.builders.ReconfigureStep.DeviceAction;
import org.jenkinsci.plugins.vsphere.tools.VSphereException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** Adding, enlarging and removing disks and controllers of VMs of a standalone ESXi host. */
class EsxiDiskReconfigureTest {

    private static final String FOLDER = "/vmfs/volumes/datastore1/web";
    private static final String VMX_PATH = FOLDER + "/web.vmx";

    private static final String VMX = String.join(
            "\n",
            "displayName = \"web\"",
            "scsi0.present = \"TRUE\"",
            "scsi0.virtualDev = \"pvscsi\"",
            "scsi0:0.present = \"TRUE\"",
            "scsi0:0.fileName = \"web.vmdk\"",
            "");

    private FakeEsxiHost host;
    private VSphereEsxiSsh esxi;

    private static String descriptor(String flat, long megabytes, String parent) {
        return "# Disk DescriptorFile\nversion=1\nCID=fffffffe\n"
                + (parent == null
                        ? "parentCID=ffffffff\n"
                        : "parentCID=12345678\nparentFileNameHint=\"" + parent + "\"\n")
                + "createType=\"vmfs\"\n\n# Extent description\nRW " + megabytes * 2048 + " VMFS \"" + flat + "\"\n";
    }

    @BeforeEach
    void setUp() {
        host = new FakeEsxiHost();
        host.addVm(7, "web", "datastore1", "web/web.vmx", VMX);
        host.addFile(FOLDER + "/web.vmdk", descriptor("web-flat.vmdk", 10 * 1024, null));
        host.addFile(FOLDER + "/web-flat.vmdk", "DATA");
        esxi = new VSphereEsxiSsh(host);
    }

    private VmxFile vmx() {
        return VmxFile.parse(host.file(VMX_PATH));
    }

    private void reconfigure(ReconfigureStep... steps) throws Exception {
        final VirtualMachine vm = esxi.getVmByName("web");
        final EnvVars env = new EnvVars();
        ReconfigureStep.reconfigureVm(
                esxi,
                vm,
                List.of(steps),
                step -> step.perform(env, TaskListener.NULL),
                spec -> esxi.reconfigureVm("web", spec),
                System.out);
    }

    private static ReconfigureDisk disk(DeviceAction action, String size, String label) throws Exception {
        final ReconfigureDisk step = new ReconfigureDisk(size, "datastore1");
        step.setDeviceAction(action);
        step.setDeviceLabel(label);
        return step;
    }

    // -- the configuration tells about the disks --

    @Test
    void theConfigurationHasTheSizeOfDisksAndTheirControllers() throws Exception {
        final VirtualDisk disk = (VirtualDisk) java.util.Arrays.stream(
                        esxi.getVmByName("web").getConfig().getHardware().getDevice())
                .filter(d -> d instanceof VirtualDisk)
                .findFirst()
                .get();

        assertThat(disk.getCapacityInKB(), is(10L * 1024 * 1024));
        final VirtualSCSIController controller = (VirtualSCSIController) java.util.Arrays.stream(
                        esxi.getVmByName("web").getConfig().getHardware().getDevice())
                .filter(d -> d instanceof VirtualSCSIController)
                .findFirst()
                .get();
        assertThat(controller instanceof ParaVirtualSCSIController, is(true));
        assertThat(controller.getDevice().length, is(1));
    }

    // -- adding --

    @Test
    void addsADiskWithTheStepOfThePlugin() throws Exception {
        reconfigure(disk(DeviceAction.ADD, "5", null));

        assertThat(vmx().get("scsi0:1.present"), is("TRUE"));
        assertThat(vmx().get("scsi0:1.fileName"), is("web_1.vmdk"));
        assertThat(host.file(FOLDER + "/web_1.vmdk"), containsString("RW 10485760 VMFS"));
        assertThat(host.lastDiskType, is("zeroedthick"));
        final VirtualMachine vm = esxi.getVmByName("web");
        assertThat(
                java.util.Arrays.stream(vm.getConfig().getHardware().getDevice())
                        .filter(d -> d instanceof VirtualDisk)
                        .count(),
                is(2L));
    }

    @Test
    void addsAControllerWhenThereIsNoneThenTheDisk() throws Exception {
        host.addVm(8, "bare", "datastore1", "bare/bare.vmx", "displayName = \"bare\"\n");
        final VirtualMachine vm = esxi.getVmByName("bare");
        final EnvVars env = new EnvVars();

        ReconfigureStep.reconfigureVm(
                esxi,
                vm,
                List.of(disk(DeviceAction.ADD, "2", null)),
                step -> step.perform(env, TaskListener.NULL),
                spec -> esxi.reconfigureVm("bare", spec),
                System.out);

        final VmxFile bare = VmxFile.parse(host.file("/vmfs/volumes/datastore1/bare/bare.vmx"));
        assertThat(bare.get("scsi0.present"), is("TRUE"));
        assertThat(bare.get("scsi0.virtualDev"), is("lsilogic"));
        assertThat(bare.get("scsi0:0.fileName"), is("bare_1.vmdk"));
        assertThat(host.hasFile("/vmfs/volumes/datastore1/bare/bare_1.vmdk"), is(true));
    }

    @Test
    void aDiskCanBeThin() throws Exception {
        final VirtualDiskFlatVer2BackingInfo backing = new VirtualDiskFlatVer2BackingInfo();
        backing.setFileName("[datastore1] web/scratch.vmdk");
        backing.setDiskMode("independent_persistent");
        backing.setThinProvisioned(true);

        esxi.reconfigureVm("web", addDisk(backing, 1000 * 1024, 5, VirtualDeviceConfigSpecFileOperation.create));

        assertThat(host.lastDiskType, is("thin"));
        assertThat(vmx().get("scsi0:5.mode"), is("independent-persistent"));
    }

    @Test
    void aDiskCanBeOnAnotherDatastoreAndIsNamedByItsFullPath() throws Exception {
        host.addFile("/vmfs/volumes/datastore 2/keep/x", "x");
        final VirtualDiskFlatVer2BackingInfo backing = new VirtualDiskFlatVer2BackingInfo();
        backing.setFileName("[datastore 2] web/data.vmdk");

        esxi.reconfigureVm("web", addDisk(backing, 2048, null, VirtualDeviceConfigSpecFileOperation.create));

        assertThat(vmx().get("scsi0:1.fileName"), is("/vmfs/volumes/datastore 2/web/data.vmdk"));
        assertThat(host.hasFile("/vmfs/volumes/datastore 2/web/data.vmdk"), is(true));
        // and the configuration gives it back in the form of the API
        final VirtualDisk second = (VirtualDisk) java.util.Arrays.stream(
                        esxi.getVmByName("web").getConfig().getHardware().getDevice())
                .filter(d -> d instanceof VirtualDisk)
                .skip(1)
                .findFirst()
                .get();
        assertThat(
                ((com.vmware.vim25.VirtualDeviceFileBackingInfo) second.getBacking()).getFileName(),
                is("[datastore 2] web/data.vmdk"));
    }

    @Test
    void attachesADiskThatIsThere() throws Exception {
        host.addFile(FOLDER + "/old.vmdk", descriptor("old-flat.vmdk", 100, null));
        final VirtualDiskFlatVer2BackingInfo backing = new VirtualDiskFlatVer2BackingInfo();
        backing.setFileName("[datastore1] web/old.vmdk");

        esxi.reconfigureVm("web", addDisk(backing, 0, 3, null));

        assertThat(vmx().get("scsi0:3.fileName"), is("old.vmdk"));
        assertThat(host.commands.stream().anyMatch(c -> c.startsWith("vmkfstools -c")), is(false));
    }

    @Test
    void aDiskThatIsNotThereCannotBeAttached() {
        final VirtualDiskFlatVer2BackingInfo backing = new VirtualDiskFlatVer2BackingInfo();
        backing.setFileName("[datastore1] web/none.vmdk");

        final VSphereException e =
                assertThrows(VSphereException.class, () -> esxi.reconfigureVm("web", addDisk(backing, 0, 3, null)));

        assertThat(e.getMessage(), containsString("does not exist"));
    }

    @Test
    void namesThatAreNotPlainAreRefused() {
        for (String bad : new String[] {
            "[datastore1] ../etc/x.vmdk", "[datastore1] web/a'b.vmdk", "datastore1 web/a.vmdk", "[datastore1] web/a.txt"
        }) {
            final VirtualDiskFlatVer2BackingInfo backing = new VirtualDiskFlatVer2BackingInfo();
            backing.setFileName(bad);

            assertThrows(
                    VSphereException.class,
                    () -> esxi.reconfigureVm(
                            "web", addDisk(backing, 2048, null, VirtualDeviceConfigSpecFileOperation.create)));
        }
        assertThat(host.file(VMX_PATH), is(VMX));
    }

    @Test
    void aUnitInUseIsRefused() {
        final VirtualDiskFlatVer2BackingInfo backing = new VirtualDiskFlatVer2BackingInfo();
        backing.setFileName("[datastore1] web/x.vmdk");

        final VSphereException e = assertThrows(
                VSphereException.class,
                () -> esxi.reconfigureVm(
                        "web", addDisk(backing, 2048, 0, VirtualDeviceConfigSpecFileOperation.create)));

        assertThat(e.getMessage(), containsString("in use already"));
        assertThat(host.hasFile(FOLDER + "/x.vmdk"), is(false));
    }

    @Test
    void aFailureWritingTheVmxRemovesTheDisksThatWereMade() {
        host.failing("mv -f", "mv: read-only");

        assertThrows(VSphereException.class, () -> reconfigure(disk(DeviceAction.ADD, "5", null)));

        assertThat(host.hasFile(FOLDER + "/web_1.vmdk"), is(false));
        assertThat(host.file(VMX_PATH), is(VMX));
    }

    private static VirtualMachineConfigSpec addDisk(
            VirtualDiskFlatVer2BackingInfo backing,
            long capacityKb,
            Integer unit,
            VirtualDeviceConfigSpecFileOperation fileOperation) {
        final VirtualDisk disk = new VirtualDisk();
        disk.setBacking(backing);
        disk.setCapacityInKB(capacityKb);
        disk.setControllerKey(1000);
        disk.setUnitNumber(unit);
        disk.setKey(-1);
        final VirtualDeviceConfigSpec change = new VirtualDeviceConfigSpec();
        change.setDevice(disk);
        change.setOperation(VirtualDeviceConfigSpecOperation.add);
        change.setFileOperation(fileOperation);
        final VirtualMachineConfigSpec spec = new VirtualMachineConfigSpec();
        spec.setDeviceChange(new VirtualDeviceConfigSpec[] {change});
        return spec;
    }

    // -- enlarging --

    @Test
    void makesADiskLarger() throws Exception {
        reconfigure(disk(DeviceAction.EDIT, "30", "Hard disk 1"));

        assertThat(host.file(FOLDER + "/web.vmdk"), containsString("RW " + 30L * 1024 * 2048 + " VMFS"));
    }

    @Test
    void doesNotShrinkADisk() {
        final VSphereException e =
                assertThrows(VSphereException.class, () -> reconfigure(disk(DeviceAction.EDIT, "5", "Hard disk 1")));

        assertThat(e.getMessage(), containsString("Cannot shrink"));
        assertThat(host.file(FOLDER + "/web.vmdk"), containsString("RW " + 10L * 1024 * 2048 + " VMFS"));
    }

    @Test
    void doesNotEnlargeADiskThatHasSnapshots() throws Exception {
        host.addFile(FOLDER + "/web-000001.vmdk", descriptor("web-000001-delta.vmdk", 10 * 1024, "web.vmdk"));
        host.addFile(FOLDER + "/web.vmx", VMX.replace("\"web.vmdk\"", "\"web-000001.vmdk\""));

        final VSphereException e =
                assertThrows(VSphereException.class, () -> reconfigure(disk(DeviceAction.EDIT, "30", "Hard disk 1")));

        assertThat(e.getMessage(), containsString("has snapshots"));
    }

    // -- removing --

    @Test
    void removesADiskAndItsFiles() throws Exception {
        reconfigure(disk(DeviceAction.REMOVE, "1", "Hard disk 1"));

        assertThat(vmx().get("scsi0:0.present"), is(nullValue()));
        assertThat(vmx().get("scsi0:0.fileName"), is(nullValue()));
        assertThat(host.hasFile(FOLDER + "/web.vmdk"), is(false));
        assertThat(host.hasFile(FOLDER + "/web-flat.vmdk"), is(false));
        assertThat(vmx().get("scsi0.present"), is("TRUE")); // the controller stays
    }

    @Test
    void doesNotDeleteTheFilesOfADiskThatHasSnapshots() {
        host.addFile(FOLDER + "/web-000001.vmdk", descriptor("web-000001-delta.vmdk", 10 * 1024, "web.vmdk"));
        host.addFile(FOLDER + "/web.vmx", VMX.replace("\"web.vmdk\"", "\"web-000001.vmdk\""));

        final VSphereException e =
                assertThrows(VSphereException.class, () -> reconfigure(disk(DeviceAction.REMOVE, "1", "Hard disk 1")));

        assertThat(e.getMessage(), containsString("remove the snapshots first"));
        assertThat(host.hasFile(FOLDER + "/web-000001.vmdk"), is(true));
    }

    // -- controllers --

    @Test
    void addsAndRemovesAController() throws Exception {
        final VirtualSCSIController controller = new VirtualLsiLogicController();
        controller.setKey(-2);
        controller.setBusNumber(1);

        esxi.reconfigureVm("web", controllerChange(controller, VirtualDeviceConfigSpecOperation.add));

        assertThat(vmx().get("scsi1.present"), is("TRUE"));
        assertThat(vmx().get("scsi1.virtualDev"), is("lsilogic"));

        final VirtualSCSIController same = new VirtualLsiLogicController();
        same.setKey(1001);
        esxi.reconfigureVm("web", controllerChange(same, VirtualDeviceConfigSpecOperation.remove));

        assertThat(vmx().get("scsi1.present"), is(nullValue()));
        assertThat(vmx().get("scsi1.virtualDev"), is(nullValue()));
    }

    @Test
    void aControllerWithADiskIsNotRemoved() {
        final VirtualSCSIController controller = new VirtualLsiLogicController();
        controller.setKey(1000);

        final VSphereException e = assertThrows(
                VSphereException.class,
                () -> esxi.reconfigureVm("web", controllerChange(controller, VirtualDeviceConfigSpecOperation.remove)));

        assertThat(e.getMessage(), containsString("still has a device"));
        assertThat(host.file(VMX_PATH), is(VMX));
    }

    @Test
    void aControllerThatIsThereIsNotAddedAgain() {
        final VirtualSCSIController controller = new VirtualLsiLogicController();
        controller.setKey(-2);
        controller.setBusNumber(0);

        final VSphereException e = assertThrows(
                VSphereException.class,
                () -> esxi.reconfigureVm("web", controllerChange(controller, VirtualDeviceConfigSpecOperation.add)));

        assertThat(e.getMessage(), containsString("has the scsi controller 0 already"));
    }

    // -- the other buses: IDE, SATA, NVMe, and a second SCSI controller --

    private static ReconfigureDisk diskOn(ReconfigureDisk.DiskBus bus) throws Exception {
        final ReconfigureDisk step = new ReconfigureDisk("1", "datastore1");
        step.setDiskBus(bus);
        return step;
    }

    @Test
    void theStepPutsADiskOnTheBusThatIsAskedFor() throws Exception {
        reconfigure(diskOn(ReconfigureDisk.DiskBus.IDE));
        assertThat(vmx().get("ide0:0.fileName"), is("web_1.vmdk"));
        assertThat(vmx().get("ide0:0.deviceType"), is("ata-hardDisk"));

        reconfigure(diskOn(ReconfigureDisk.DiskBus.IDE));
        assertThat(vmx().get("ide0:1.fileName"), is("web_2.vmdk"));
    }

    @Test
    void theStepAddsASataControllerWhenThereIsNoneAndThenUsesIt() throws Exception {
        reconfigure(diskOn(ReconfigureDisk.DiskBus.SATA));
        assertThat(vmx().get("sata0.present"), is("TRUE"));
        assertThat(vmx().get("sata0:0.fileName"), is("web_1.vmdk"));

        reconfigure(diskOn(ReconfigureDisk.DiskBus.SATA));
        assertThat(vmx().get("sata0:1.fileName"), is("web_2.vmdk"));
        assertThat(vmx().get("sata1.present"), is(nullValue()));
    }

    @Test
    void theStepAddsAnNvmeControllerWhenThereIsNone() throws Exception {
        reconfigure(diskOn(ReconfigureDisk.DiskBus.NVME));

        assertThat(vmx().get("nvme0.present"), is("TRUE"));
        assertThat(vmx().get("nvme0:0.fileName"), is("web_1.vmdk"));
    }

    @Test
    void theStepStillDefaultsToScsiAndSkipsTheReservedUnit() throws Exception {
        final VmxFile with = vmx();
        for (int unit = 1; unit < 7; unit++) {
            with.put("scsi0:" + unit + ".present", "TRUE");
            with.put("scsi0:" + unit + ".fileName", "x" + unit + ".vmdk");
        }
        host.addFile(VMX_PATH, with.toString());

        reconfigure(new ReconfigureDisk("1", "datastore1"));

        assertThat(vmx().get("scsi0:8.fileName"), is("web_1.vmdk"));
        assertThat(vmx().get("scsi0:7.fileName"), is(nullValue()));
    }

    @Test
    void theStepFailsWhenTheTwoIdeControllersAreFull() throws Exception {
        final VmxFile with = vmx();
        for (String unit : new String[] {"ide0:0", "ide0:1", "ide1:0", "ide1:1"}) {
            with.put(unit + ".present", "TRUE");
            with.put(unit + ".fileName", unit.replace(':', '_') + ".vmdk");
        }
        host.addFile(VMX_PATH, with.toString());

        final Exception e = assertThrows(Exception.class, () -> reconfigure(diskOn(ReconfigureDisk.DiskBus.IDE)));

        assertThat(String.valueOf(e.getMessage()), containsString("have no free unit"));
    }

    @Test
    void theStepFindsADiskOnSataByItsMoniker() throws Exception {
        reconfigure(diskOn(ReconfigureDisk.DiskBus.SATA));

        reconfigure(disk(DeviceAction.EDIT, "2", "SATA(0:0)"));

        assertThat(host.file(FOLDER + "/web_1.vmdk"), containsString("RW 4194304 VMFS"));
    }

    private static VirtualDisk newDisk(int controllerKey, Integer unit, String file, long kb) {
        final VirtualDiskFlatVer2BackingInfo backing = new VirtualDiskFlatVer2BackingInfo();
        backing.setFileName(file);
        backing.setThinProvisioned(true);
        final VirtualDisk disk = new VirtualDisk();
        disk.setBacking(backing);
        disk.setCapacityInKB(kb);
        disk.setControllerKey(controllerKey);
        disk.setUnitNumber(unit);
        disk.setKey(-1);
        return disk;
    }

    private static VirtualDeviceConfigSpec add(com.vmware.vim25.VirtualDevice device, boolean create) {
        final VirtualDeviceConfigSpec change = new VirtualDeviceConfigSpec();
        change.setDevice(device);
        change.setOperation(VirtualDeviceConfigSpecOperation.add);
        if (create) {
            change.setFileOperation(VirtualDeviceConfigSpecFileOperation.create);
        }
        return change;
    }

    private static VirtualMachineConfigSpec specOf(VirtualDeviceConfigSpec... changes) {
        final VirtualMachineConfigSpec spec = new VirtualMachineConfigSpec();
        spec.setDeviceChange(changes);
        return spec;
    }

    private java.util.List<com.vmware.vim25.VirtualDevice> devices() throws Exception {
        return java.util.Arrays.asList(
                esxi.getVmByName("web").getConfig().getHardware().getDevice());
    }

    @Test
    void aDiskCanBeAddedOnAnIdleControllerThatIsAlwaysThere() throws Exception {
        esxi.reconfigureVm("web", specOf(add(newDisk(200, null, "[datastore1] web/ide.vmdk", 2048), true)));

        assertThat(vmx().get("ide0:0.present"), is("TRUE"));
        assertThat(vmx().get("ide0:0.fileName"), is("ide.vmdk"));
        assertThat(vmx().get("ide0:0.deviceType"), is("ata-hardDisk"));
        assertThat(host.hasFile(FOLDER + "/ide.vmdk"), is(true));
        // the VM tells it back, on the controller with the key of IDE 0, as a disk of its own
        final com.vmware.vim25.VirtualDisk ide = (com.vmware.vim25.VirtualDisk)
                devices().stream().filter(d -> d.getKey() == 3000).findFirst().get();
        assertThat(ide.getControllerKey(), is(200));
        assertThat(ide.getUnitNumber(), is(0));
        assertThat(
                devices().stream()
                        .filter(d -> d instanceof com.vmware.vim25.VirtualIDEController)
                        .count(),
                is(2L));
    }

    @Test
    void anIdeControllerCannotBeAddedOrRemoved() {
        final com.vmware.vim25.VirtualIDEController controller = new com.vmware.vim25.VirtualIDEController();
        controller.setBusNumber(0);
        controller.setKey(200);

        final VSphereException added =
                assertThrows(VSphereException.class, () -> esxi.reconfigureVm("web", specOf(add(controller, false))));
        assertThat(added.getMessage(), containsString("A VM has its two IDE controllers always"));

        final VirtualDeviceConfigSpec remove = add(controller, false);
        remove.setOperation(VirtualDeviceConfigSpecOperation.remove);
        final VSphereException removed =
                assertThrows(VSphereException.class, () -> esxi.reconfigureVm("web", specOf(remove)));
        assertThat(removed.getMessage(), containsString("cannot be removed"));
        assertThat(host.file(VMX_PATH), is(VMX));
    }

    @Test
    void anIdeSlaveNeedsItsMaster() throws Exception {
        final VSphereException e = assertThrows(
                VSphereException.class,
                () -> esxi.reconfigureVm("web", specOf(add(newDisk(200, 1, "[datastore1] web/x.vmdk", 2048), true))));
        assertThat(e.getMessage(), containsString("the slave"));
        assertThat(host.hasFile(FOLDER + "/x.vmdk"), is(false));

        // with a master it is all right, and then the master is not taken away from under it
        esxi.reconfigureVm(
                "web",
                specOf(
                        add(newDisk(200, 0, "[datastore1] web/m.vmdk", 2048), true),
                        add(newDisk(200, 1, "[datastore1] web/s.vmdk", 2048), true)));
        assertThat(vmx().get("ide0:1.fileName"), is("s.vmdk"));
        final com.vmware.vim25.VirtualDisk master = newDisk(200, 0, "[datastore1] web/m.vmdk", 2048);
        master.setKey(3000);
        final VirtualDeviceConfigSpec remove = add(master, false);
        remove.setOperation(VirtualDeviceConfigSpecOperation.remove);
        final VSphereException removed =
                assertThrows(VSphereException.class, () -> esxi.reconfigureVm("web", specOf(remove)));
        assertThat(removed.getMessage(), containsString("the master"));
    }

    @Test
    void theUnitsOfIdeAreTwo() {
        final VSphereException e = assertThrows(
                VSphereException.class,
                () -> esxi.reconfigureVm("web", specOf(add(newDisk(201, 2, "[datastore1] web/x.vmdk", 2048), true))));

        assertThat(e.getMessage(), containsString("cannot be used for a disk on ide (0 to 1)"));
    }

    @Test
    void aSataControllerAndADiskOnItAreAddedTogether() throws Exception {
        final com.vmware.vim25.VirtualAHCIController controller = new com.vmware.vim25.VirtualAHCIController();
        controller.setBusNumber(0);
        controller.setKey(-100);

        esxi.reconfigureVm(
                "web",
                specOf(add(controller, false), add(newDisk(-100, null, "[datastore1] web/sata.vmdk", 4096), true)));

        assertThat(vmx().get("sata0.present"), is("TRUE"));
        assertThat(vmx().get("sata0:0.fileName"), is("sata.vmdk"));
        final com.vmware.vim25.VirtualDisk sata = (com.vmware.vim25.VirtualDisk)
                devices().stream().filter(d -> d.getKey() == 16000).findFirst().get();
        assertThat(sata.getControllerKey(), is(15000));
        assertThat(
                devices().stream()
                        .anyMatch(d -> d instanceof com.vmware.vim25.VirtualAHCIController && d.getKey() == 15000),
                is(true));
    }

    @Test
    void anNvmeControllerAndADiskOnItAreAddedTogether() throws Exception {
        final com.vmware.vim25.VirtualNVMEController controller = new com.vmware.vim25.VirtualNVMEController();
        controller.setBusNumber(0);
        controller.setKey(-7);

        esxi.reconfigureVm(
                "web", specOf(add(controller, false), add(newDisk(-7, 3, "[datastore1] web/nvme.vmdk", 4096), true)));

        assertThat(vmx().get("nvme0.present"), is("TRUE"));
        assertThat(vmx().get("nvme0:3.fileName"), is("nvme.vmdk"));
        assertThat(
                devices().stream().anyMatch(d -> d instanceof com.vmware.vim25.VirtualDisk && d.getKey() == 19003),
                is(true));
    }

    @Test
    void aSecondScsiControllerOfAnotherTypeTakesDisksToo() throws Exception {
        final com.vmware.vim25.VirtualLsiLogicSASController controller =
                new com.vmware.vim25.VirtualLsiLogicSASController();
        controller.setBusNumber(1);
        controller.setKey(-3);

        esxi.reconfigureVm(
                "web", specOf(add(controller, false), add(newDisk(-3, null, "[datastore1] web/two.vmdk", 4096), true)));

        assertThat(vmx().get("scsi1.virtualDev"), is("lsisas1068"));
        assertThat(vmx().get("scsi1:0.fileName"), is("two.vmdk"));
        assertThat(
                devices().stream()
                        .anyMatch(
                                d -> d instanceof com.vmware.vim25.VirtualLsiLogicSASController && d.getKey() == 1001),
                is(true));
    }

    @Test
    void aDiskOnAnIdeControllerCanBeEnlargedAndRemoved() throws Exception {
        esxi.reconfigureVm("web", specOf(add(newDisk(201, 0, "[datastore1] web/ide.vmdk", 2048), true)));
        final com.vmware.vim25.VirtualDisk ide = newDisk(201, 0, "[datastore1] web/ide.vmdk", 4096);
        ide.setKey(3002);
        final VirtualDeviceConfigSpec bigger = add(ide, false);
        bigger.setOperation(VirtualDeviceConfigSpecOperation.edit);

        esxi.reconfigureVm("web", specOf(bigger));
        assertThat(host.file(FOLDER + "/ide.vmdk"), containsString("RW 8192 VMFS"));

        final VirtualDeviceConfigSpec remove = add(ide, false);
        remove.setOperation(VirtualDeviceConfigSpecOperation.remove);
        remove.setFileOperation(VirtualDeviceConfigSpecFileOperation.destroy);
        esxi.reconfigureVm("web", specOf(remove));

        assertThat(vmx().get("ide1:0.fileName"), is(nullValue()));
        assertThat(host.hasFile(FOLDER + "/ide.vmdk"), is(false));
    }

    @Test
    void aCdromOnTheBusIsNotADisk() throws Exception {
        final VmxFile with = VmxFile.parse(VMX);
        with.put("sata0.present", "TRUE");
        with.put("sata0:0.present", "TRUE");
        with.put("sata0:0.deviceType", "cdrom-image");
        with.put("sata0:0.fileName", "/vmfs/volumes/iso/os.iso");
        host.addFile(VMX_PATH, with.toString());

        assertThat(
                devices().stream()
                        .filter(d -> d instanceof com.vmware.vim25.VirtualDisk)
                        .count(),
                is(1L));
        // but the controller it is on is there
        assertThat(devices().stream().anyMatch(d -> d.getKey() == 15000), is(true));
    }

    private static VirtualMachineConfigSpec controllerChange(
            VirtualSCSIController controller, VirtualDeviceConfigSpecOperation operation) {
        final VirtualDeviceConfigSpec change = new VirtualDeviceConfigSpec();
        change.setDevice(controller);
        change.setOperation(operation);
        final VirtualMachineConfigSpec spec = new VirtualMachineConfigSpec();
        spec.setDeviceChange(new VirtualDeviceConfigSpec[] {change});
        return spec;
    }
}
