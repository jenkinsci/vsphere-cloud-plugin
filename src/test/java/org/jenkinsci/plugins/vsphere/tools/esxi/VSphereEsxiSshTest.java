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
import static org.hamcrest.Matchers.instanceOf;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.notNullValue;
import static org.hamcrest.Matchers.nullValue;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.vmware.vim25.VirtualDevice;
import com.vmware.vim25.VirtualDisk;
import com.vmware.vim25.VirtualEthernetCard;
import com.vmware.vim25.VirtualMachineConfigSpec;
import com.vmware.vim25.VirtualMachinePowerState;
import com.vmware.vim25.VirtualMachineToolsStatus;
import com.vmware.vim25.mo.VirtualMachine;
import org.jenkinsci.plugins.vsphere.tools.VSphereException;
import org.jenkinsci.plugins.vsphere.tools.VSphereNotFoundException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * The ESXi backend against {@link FakeEsxiHost}: the VM objects it hands out answer from what the host says and
 * act through {@code vim-cmd}, and the operations that the vCenter backend shares with it (power off with a grace
 * period, snapshots, destroy, ...) work on them unchanged.
 */
class VSphereEsxiSshTest {

    private static final String VMX = String.join(
            "\n",
            "displayName = \"kube-master\"",
            "numvcpus = \"4\"",
            "memSize = \"8192\"",
            "cpuid.coresPerSocket = \"2\"",
            "uuid.bios = \"42 1a 2b\"",
            "scsi0.present = \"TRUE\"",
            "scsi0:0.present = \"TRUE\"",
            "scsi0:0.fileName = \"kube-master.vmdk\"",
            "ethernet0.present = \"TRUE\"",
            "ethernet0.virtualDev = \"vmxnet3\"",
            "ethernet0.networkName = \"VM Network\"",
            "ethernet0.addressType = \"static\"",
            "ethernet0.address = \"00:50:56:01:02:03\"",
            "ethernet1.present = \"TRUE\"",
            "ethernet1.addressType = \"generated\"",
            "ethernet1.generatedAddress = \"00:0c:29:aa:bb:cc\"",
            "");

    private FakeEsxiHost host;
    private VSphereEsxiSsh esxi;

    @BeforeEach
    void setUp() {
        host = new FakeEsxiHost();
        host.addVm(1, "kube-master", "datastore1", "kube-master/kube-master.vmx", VMX);
        host.addVm(12, "my vm", "datastore 2", "my vm/my vm.vmx", "displayName = \"my vm\"\n");
        host.addVm(13, "a.*", "datastore1", "a/a.vmx", "displayName = \"a.*\"\n");
        esxi = new VSphereEsxiSsh(host);
    }

    // -- looking VMs up --

    @Test
    void findsVmsByTheirExactName() throws Exception {
        VirtualMachine vm = esxi.getVmByName("kube-master");

        assertThat(vm, notNullValue());
        assertThat(vm.getName(), is("kube-master"));
        assertThat(vm.getMOR().getVal(), is("1"));
        assertThat(esxi.getVmByName("my vm").getMOR().getVal(), is("12"));
        assertThat(esxi.getVmByName("nope"), is(nullValue()));
    }

    @Test
    void namesAreNotPatterns() throws Exception {
        // "kube-.*" and "a" would match other VMs if the name were used as a regular expression
        assertThat(esxi.getVmByName("kube-.*"), is(nullValue()));
        assertThat(esxi.getVmByName("a"), is(nullValue()));
        assertThat(esxi.getVmByName("a.*").getMOR().getVal(), is("13"));
    }

    @Test
    void countsVms() throws Exception {
        assertThat(esxi.countVms(), is(3));
        assertThat(esxi.countVmsByPrefix("kube"), is(1));
        assertThat(esxi.countVmsByPrefix("zzz"), is(0));
    }

    // -- what the VM objects say --

    @Test
    void powerStateAndSummary() throws Exception {
        VirtualMachine vm = esxi.getVmByName("kube-master");

        assertThat(vm.getRuntime().getPowerState(), is(VirtualMachinePowerState.poweredOff));
        host.vm(1).power = "Powered on";
        assertThat(vm.getRuntime().getPowerState(), is(VirtualMachinePowerState.poweredOn));
        assertThat(vm.getSummary().getRuntime().getPowerState(), is(VirtualMachinePowerState.poweredOn));
        assertThat(vm.getSummary().getConfig().getName(), is("kube-master"));
    }

    @Test
    void hardwareIsDescribedLikeVCenterDoes() throws Exception {
        VirtualMachine vm = esxi.getVmByName("kube-master");

        assertThat(vm.getConfig().template, is(false));
        assertThat(vm.getConfig().getHardware().getNumCPU(), is(4));
        assertThat(vm.getConfig().getHardware().getNumCoresPerSocket(), is(2));
        assertThat(vm.getConfig().getHardware().getMemoryMB(), is(8192));
        assertThat(vm.getConfig().getUuid(), is("42 1a 2b"));

        VirtualDisk disk = null;
        VirtualEthernetCard first = null;
        VirtualEthernetCard second = null;
        for (VirtualDevice device : vm.getConfig().getHardware().getDevice()) {
            if (device instanceof VirtualDisk) {
                disk = (VirtualDisk) device;
            } else if (device instanceof VirtualEthernetCard) {
                if (first == null) {
                    first = (VirtualEthernetCard) device;
                } else {
                    second = (VirtualEthernetCard) device;
                }
            }
        }
        assertThat(disk.getDeviceInfo().getLabel(), is("Hard disk 1"));
        assertThat(disk.getKey(), is(2000));
        assertThat(disk.getControllerKey(), is(1000));
        assertThat(disk.getUnitNumber(), is(0));
        assertThat(
                ((com.vmware.vim25.VirtualDeviceFileBackingInfo) disk.getBacking()).getFileName(),
                is("[datastore1] kube-master/kube-master.vmdk"));
        assertThat(first.getDeviceInfo().getLabel(), is("Network adapter 1"));
        assertThat(first.getMacAddress(), is("00:50:56:01:02:03"));
        assertThat(first.getAddressType(), is("manual"));
        assertThat(second.getDeviceInfo().getLabel(), is("Network adapter 2"));
        assertThat(second.getMacAddress(), is("00:0c:29:aa:bb:cc"));
        assertThat(second.getAddressType(), is("generated"));
    }

    @Test
    void guestInfoAndIpAddress() throws Exception {
        host.vm(1).power = "Powered on";
        host.vm(1).ip = "10.1.2.3";
        VirtualMachine vm = esxi.getVmByName("kube-master");

        assertThat(vm.getGuest().getIpAddress(), is("10.1.2.3"));
        assertThat(vm.getGuest().getToolsStatus(), is(VirtualMachineToolsStatus.toolsOk));
        assertThat(esxi.getIp(vm, 5), is("10.1.2.3"));
        assertThat(esxi.vmToolIsEnabled(vm), is(true));
    }

    @Test
    void aVmThatIsOffHasNoIpAndNoTools() throws Exception {
        host.vm(1).ip = "10.1.2.3";
        VirtualMachine vm = esxi.getVmByName("kube-master");

        assertThat(vm.getGuest().getIpAddress(), is(nullValue()));
        assertThat(esxi.vmToolIsEnabled(vm), is(false));
    }

    @Test
    void propertiesThatAreNotProvidedSayWhy() throws Exception {
        VirtualMachine vm = esxi.getVmByName("kube-master");

        UnsupportedOperationException e = assertThrows(UnsupportedOperationException.class, () -> vm.getDatastores());
        assertThat(e.getMessage(), containsString("not available from an ESXi host over SSH"));
    }

    @Test
    void aFaultTheHostPrintsIsAFailureWhateverTheExitCode() throws Exception {
        for (int exitCode : new int[] {0, 1}) {
            host.notFoundExitCode = exitCode;
            VirtualMachine vm = esxi.getVmByName("my vm");
            host.run("/bin/vim-cmd vmsvc/destroy 12"); // the VM vanishes behind our back

            IllegalStateException e = assertThrows(IllegalStateException.class, () -> vm.getGuest());
            assertThat(e.getMessage(), containsString("Unable to find a VM corresponding to"));
            assertThrows(IllegalStateException.class, () -> vm.getRuntime());
            host.addVm(12, "my vm", "datastore 2", "my vm/my vm.vmx", "displayName = \"my vm\"\n");
        }
    }

    @Test
    void anActionOnAVmThatVanishedGivesAFailedTask() throws Exception {
        VirtualMachine vm = esxi.getVmByName("kube-master");
        host.run("/bin/vim-cmd vmsvc/destroy 1");

        com.vmware.vim25.mo.Task task = vm.powerOnVM_Task(null);

        assertThat(task.waitForTask(), is("error"));
        assertThat(task.getTaskInfo().getError().getLocalizedMessage(), containsString("Unable to find a VM"));
    }

    // -- the operations that vCenter shares with it --

    @Test
    void startsAVm() throws Exception {
        esxi.startVm("kube-master", 30);

        assertThat(host.vm(1).power, is("Powered on"));
        assertThat(host.ran("/bin/vim-cmd vmsvc/power.on 1"), is(true));
    }

    @Test
    void startingAVmThatDoesNotExistFailsAndNamesIt() {
        // as with vCenter, startVm reports every failure as "VM cannot be started"
        VSphereException e = assertThrows(VSphereException.class, () -> esxi.startVm("nope", 30));

        assertThat(e.getMessage(), containsString("VM cannot be started"));
        assertThat(e.getMessage(), containsString("nope"));
    }

    @Test
    void poweringOffImmediately() throws Exception {
        host.vm(1).power = "Powered on";

        esxi.powerOffVm(esxi.getVmByName("kube-master"), true, 0);

        assertThat(host.vm(1).power, is("Powered off"));
        assertThat(host.ran("/bin/vim-cmd vmsvc/power.off 1"), is(true));
        assertThat(host.ran("/bin/vim-cmd vmsvc/power.shutdown 1"), is(false));
    }

    @Test
    void poweringOffWithAGracePeriodAsksTheGuestToShutDownFirst() throws Exception {
        host.vm(1).power = "Powered on";

        esxi.powerOffVm(esxi.getVmByName("kube-master"), true, 5);

        assertThat(host.vm(1).power, is("Powered off"));
        assertThat(host.ran("/bin/vim-cmd vmsvc/power.shutdown 1"), is(true));
        // the guest did shut down, so there was nothing left to power off
        assertThat(host.ran("/bin/vim-cmd vmsvc/power.off 1"), is(false));
    }

    @Test
    void withoutVmwareToolsThereIsNoGracePeriod() throws Exception {
        host.vm(1).power = "Powered on";
        host.vm(1).toolsStatus = "toolsNotInstalled";

        esxi.powerOffVm(esxi.getVmByName("kube-master"), true, 5);

        assertThat(host.ran("/bin/vim-cmd vmsvc/power.shutdown 1"), is(false));
        assertThat(host.ran("/bin/vim-cmd vmsvc/power.off 1"), is(true));
    }

    @Test
    void suspendsARunningVm() throws Exception {
        host.vm(1).power = "Powered on";

        esxi.suspendVm(esxi.getVmByName("kube-master"));

        assertThat(host.vm(1).power, is("Suspended"));
    }

    @Test
    void destroysAVm() throws Exception {
        host.vm(1).power = "Powered on";

        esxi.destroyVm("kube-master", true);

        assertThat(host.vm(1), is(nullValue()));
        assertThat(host.ran("/bin/vim-cmd vmsvc/power.off 1"), is(true));
        assertThat(host.ran("/bin/vim-cmd vmsvc/destroy 1"), is(true));
        assertThat(esxi.countVms(), is(2));
    }

    @Test
    void destroyingAVmThatIsNotThereFailsOnlyOnRequest() throws Exception {
        esxi.destroyVm("nope", false);

        assertThrows(VSphereNotFoundException.class, () -> esxi.destroyVm("nope", true));
    }

    @Test
    void whenTheHostRefusesTheReasonIsReported() {
        host.vm(1).power = "Powered on";
        host.failing("power.off", "The operation is not allowed in the current state");

        VSphereException e =
                assertThrows(VSphereException.class, () -> esxi.powerOffVm(esxi.getVmByName("kube-master"), true, 0));

        assertThat(e.getMessage(), containsString("Machine could not be powered down"));
        assertThat(e.getMessage(), containsString("The operation is not allowed in the current state"));
    }

    // -- snapshots --

    @Test
    void takesSnapshots() throws Exception {
        assertThat(esxi.getVmByName("kube-master").getSnapshot(), is(nullValue()));

        esxi.takeSnapshot("kube-master", "before-update", "A description", false);

        String[] taken = host.vm(1).snapshots.get(0);
        assertThat(taken[0], is("before-update"));
        assertThat(taken[1], is("A description"));
        assertThat(taken[2], is("0")); // without memory
        VirtualMachine vm = esxi.getVmByName("kube-master");
        assertThat(vm.getSnapshot().getRootSnapshotList()[0].getName(), is("before-update"));
        assertThat(esxi.getSnapshotInTree(vm, "before-update"), instanceOf(EsxiVirtualMachineSnapshot.class));
        assertThat(esxi.getSnapshotInTree(vm, "other"), is(nullValue()));
    }

    @Test
    void whateverIsInANameStaysInsideTheQuotes() throws Exception {
        final String name = "x'; rm -rf / #";
        final String description = "it's \"quoted\" $(reboot) `halt` \\ end";

        esxi.takeSnapshot("kube-master", name, description, true);

        // the host got exactly one word for each, and no shell syntax outside the quotes (the fake fails on that)
        String[] taken = host.vm(1).snapshots.get(0);
        assertThat(taken[0], is(name));
        assertThat(taken[1], is(description));
        assertThat(taken[2], is("1")); // with memory
    }

    @Test
    void revertsToASnapshotByItsNumber() throws Exception {
        esxi.takeSnapshot("kube-master", "one", "", false);
        esxi.takeSnapshot("kube-master", "two", "", false);

        esxi.revertToSnapshot("kube-master", "two", true);
        assertThat(host.vm(1).lastSnapshotAction, is("revert 2 yes"));
        esxi.revertToSnapshot("kube-master", "one");
        assertThat(host.vm(1).lastSnapshotAction, is("revert 1 no"));
    }

    @Test
    void deletesASnapshotWithoutItsChildren() throws Exception {
        esxi.takeSnapshot("kube-master", "one", "", false);
        esxi.takeSnapshot("kube-master", "two", "", false);

        esxi.deleteSnapshot("kube-master", "one", false, true);

        assertThat(host.vm(1).lastSnapshotAction, is("remove 1 no"));
        assertThat(esxi.getSnapshotInTree(esxi.getVmByName("kube-master"), "one"), is(nullValue()));
        assertThat(esxi.getSnapshotInTree(esxi.getVmByName("kube-master"), "two"), is(notNullValue()));
    }

    @Test
    void aSnapshotThatIsNotThereIsNotFound() {
        assertThrows(VSphereException.class, () -> esxi.revertToSnapshot("kube-master", "nope"));
    }

    @Test
    void aFailureOfTheHostWhenRevertingIsReported() throws Exception {
        esxi.takeSnapshot("kube-master", "one", "", false);
        host.failing("snapshot.revert", "Snapshot is busy");

        VSphereException e = assertThrows(VSphereException.class, () -> esxi.revertToSnapshot("kube-master", "one"));

        assertThat(e.getMessage(), containsString("Snapshot is busy"));
    }

    @Test
    void aSnapshotThatIsNotANumberIsNotPassedOn() throws Exception {
        final EsxiVirtualMachineSnapshot snapshot = new EsxiVirtualMachineSnapshot(
                esxi, ((EsxiVirtualMachine) esxi.getVmByName("kube-master")).getEntry(), mor("1; reboot"));

        assertThat(snapshot.revertToSnapshot_Task(null).waitForTask(), is("error"));
        assertThat(host.ran("/bin/vim-cmd vmsvc/snapshot.revert 1 '1; reboot' no"), is(false));
    }

    private static com.vmware.vim25.ManagedObjectReference mor(String value) {
        final com.vmware.vim25.ManagedObjectReference mor = new com.vmware.vim25.ManagedObjectReference();
        mor.setType("VirtualMachineSnapshot");
        mor.setVal(value);
        return mor;
    }

    @Test
    void renamingASnapshotIsNotSupported() throws Exception {
        esxi.takeSnapshot("kube-master", "one", "", false);

        UnsupportedOperationException e = assertThrows(
                UnsupportedOperationException.class,
                () -> esxi.renameVmSnapshot("kube-master", "one", "uno", "", true));

        assertThat(e.getMessage(), containsString("vim-cmd cannot do it"));
    }

    // -- the host --

    @Test
    void knowsItsOwnNameAndNoOthers() throws Exception {
        assertThat(esxi.hostExists("esxi7"), is(true));
        assertThat(esxi.hostExists("ESXI7.example.com"), is(true));
        assertThat(esxi.hostExists("esxi8"), is(false));
        assertThat(esxi.hostExists("example.com"), is(false));
        assertThat(esxi.hostExists(" "), is(false));
        assertThat(esxi.hostExists(null), is(false));
    }

    @Test
    void asksTheHostnameCommandWhenEsxcliIsNotThere() throws Exception {
        host.hostname = null;
        host.failing("esxcli", "esxcli: not found");
        host.hostname = "plain";

        assertThat(esxi.hostExists("plain"), is(true));
        assertThat(esxi.hostExists("plain.example.com"), is(true));
    }

    // -- what is not supported --

    @Test
    void whatTheHostDoesNotHaveIsToldApartFromWhatIsNotDoneYet() throws Exception {
        // by the nature of the platform
        assertThat(
                assertThrows(VSphereException.class, () -> esxi.getCustomizationSpecByName("x")),
                instanceOf(EsxiPlatformConstraint.class));
        assertThat(
                assertThrows(VSphereException.class, () -> esxi.getDistributedVirtualPortGroupByName(null, "x")),
                instanceOf(EsxiPlatformConstraint.class));
        VirtualMachine vm = esxi.getVmByName("kube-master");
        assertThat(
                assertThrows(UnsupportedOperationException.class, () -> vm.migrateVM_Task(null, null, null, null)),
                instanceOf(EsxiPlatformConstraint.class));
        assertThat(
                assertThrows(UnsupportedOperationException.class, () -> vm.relocateVM_Task(null)),
                instanceOf(EsxiPlatformConstraint.class));
        // not done yet: an ordinary failure
        assertThat(
                assertThrows(UnsupportedOperationException.class, () -> vm.getParent())
                        .getClass(),
                is((Object) UnsupportedOperationException.class));
    }

    @Test
    void whatNeedsVCenterIsNotSupportedAndSays() {
        VSphereException e = assertThrows(VSphereException.class, () -> esxi.folderExists("folder"));
        assertThat(e.getMessage(), containsString("folderExists is not applicable to a standalone ESXi host"));
        assertThat(e, instanceOf(EsxiPlatformConstraint.class));
        assertThrows(VSphereException.class, () -> esxi.getDatastores());
    }

    @Test
    void reconfiguringARunningVmSaysItHasToBePoweredOff() {
        host.vm(1).power = "Powered on";

        VSphereException e = assertThrows(
                VSphereException.class, () -> esxi.reconfigureVm("kube-master", new VirtualMachineConfigSpec()));

        assertThat(e.getMessage(), containsString("has to be powered off"));
    }

    // -- the connection --

    @Test
    void disconnectingClosesTheShell() {
        assertThat(esxi.isSessionAlive(), is(true));

        esxi.disconnect();

        assertThat(host.closed, is(true));
        assertThat(esxi.isSessionAlive(), is(false));
    }
}
