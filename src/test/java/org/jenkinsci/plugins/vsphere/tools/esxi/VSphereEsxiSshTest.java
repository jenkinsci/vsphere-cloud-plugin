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
    void actingOnAnExistingSnapshotIsNotSupportedYetAndSays() throws Exception {
        esxi.takeSnapshot("kube-master", "snap", "", false);

        VSphereException e = assertThrows(VSphereException.class, () -> esxi.revertToSnapshot("kube-master", "snap"));

        assertThat(e.getMessage(), containsString("not supported by the ESXi SSH backend"));
    }

    // -- what is not supported --

    @Test
    void whatNeedsVCenterIsNotSupportedAndSays() {
        VSphereException e = assertThrows(VSphereException.class, () -> esxi.folderExists("folder"));
        assertThat(e.getMessage(), containsString("folderExists is not supported by the ESXi SSH backend"));
        assertThrows(VSphereException.class, () -> esxi.hostExists("host"));
        assertThrows(VSphereException.class, () -> esxi.getDatastores());
    }

    @Test
    void reconfiguringIsNotSupportedYetAndSays() {
        VSphereException e = assertThrows(
                VSphereException.class, () -> esxi.reconfigureVm("kube-master", new VirtualMachineConfigSpec()));

        assertThat(e.getMessage(), containsString("not supported by the ESXi SSH backend"));
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
