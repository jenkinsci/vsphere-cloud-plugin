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
import static org.hamcrest.Matchers.notNullValue;
import static org.hamcrest.Matchers.nullValue;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.vmware.vim25.OptionValue;
import com.vmware.vim25.ResourceAllocationInfo;
import com.vmware.vim25.SharesInfo;
import com.vmware.vim25.SharesLevel;
import com.vmware.vim25.VirtualDevice;
import com.vmware.vim25.VirtualDeviceConfigSpec;
import com.vmware.vim25.VirtualDeviceConfigSpecOperation;
import com.vmware.vim25.VirtualDisk;
import com.vmware.vim25.VirtualE1000;
import com.vmware.vim25.VirtualEthernetCard;
import com.vmware.vim25.VirtualEthernetCardNetworkBackingInfo;
import com.vmware.vim25.VirtualMachineConfigSpec;
import com.vmware.vim25.VirtualVmxnet3;
import com.vmware.vim25.mo.VirtualMachine;
import hudson.EnvVars;
import hudson.model.TaskListener;
import java.util.List;
import org.jenkinsci.plugins.vsphere.builders.ReconfigureAnnotation;
import org.jenkinsci.plugins.vsphere.builders.ReconfigureCpu;
import org.jenkinsci.plugins.vsphere.builders.ReconfigureMemory;
import org.jenkinsci.plugins.vsphere.builders.ReconfigureNetworkAdapters;
import org.jenkinsci.plugins.vsphere.builders.ReconfigureStep;
import org.jenkinsci.plugins.vsphere.builders.ReconfigureStep.DeviceAction;
import org.jenkinsci.plugins.vsphere.tools.VSphereException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** Reconfiguring VMs of a standalone ESXi host: the changes are made in the {@code .vmx}, which is then reloaded. */
class EsxiReconfigureTest {

    private static final String VMX_PATH = "/vmfs/volumes/datastore1/web/web.vmx";

    private static final String VMX = String.join(
            "\n",
            "displayName = \"web\"",
            "numvcpus = \"2\"",
            "memSize = \"2048\"",
            "annotation = \"first|0Asecond\"",
            "scsi0.present = \"TRUE\"",
            "scsi0:0.present = \"TRUE\"",
            "scsi0:0.fileName = \"web.vmdk\"",
            "ethernet0.present = \"TRUE\"",
            "ethernet0.virtualDev = \"vmxnet3\"",
            "ethernet0.networkName = \"VM Network\"",
            "ethernet0.addressType = \"generated\"",
            "ethernet0.generatedAddress = \"00:0c:29:aa:bb:cc\"",
            "ethernet1.present = \"TRUE\"",
            "ethernet1.networkName = \"Management Network\"",
            "");

    private FakeEsxiHost host;
    private VSphereEsxiSsh esxi;

    @BeforeEach
    void setUp() {
        host = new FakeEsxiHost();
        host.addVm(7, "web", "datastore1", "web/web.vmx", VMX);
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

    private static ReconfigureNetworkAdapters nic(
            DeviceAction action, String label, String mac, boolean standard, String portGroup) throws Exception {
        return new ReconfigureNetworkAdapters(action, label, mac, standard, portGroup, false, "", "");
    }

    // -- CPU, memory, annotation --

    @Test
    void setsCpusMemoryAndAnnotationWithTheStepsOfThePlugin() throws Exception {
        final ReconfigureAnnotation annotation = new ReconfigureAnnotation();
        annotation.setAnnotation("built by \"Jenkins\"\nsecond line | end");

        reconfigure(new ReconfigureCpu("4", "2"), new ReconfigureMemory("4096"), annotation);

        assertThat(vmx().get("numvcpus"), is("4"));
        assertThat(vmx().get("cpuid.coresPerSocket"), is("2"));
        assertThat(vmx().get("memSize"), is("4096"));
        assertThat(vmx().get("annotation"), is("built by |22Jenkins|22|0Asecond line |7C end"));
        // and what the host reads back is the text that was given
        final VirtualMachine vm = esxi.getVmByName("web");
        assertThat(vm.getConfig().getAnnotation(), is("built by \"Jenkins\"\nsecond line | end"));
        assertThat(vm.getConfig().getHardware().getNumCPU(), is(4));
        assertThat(vm.getConfig().getHardware().getMemoryMB(), is(4096));
        assertThat(host.ran("/bin/vim-cmd vmsvc/reload 7"), is(true));
    }

    @Test
    void anEmptyAnnotationTakesItAway() throws Exception {
        final VirtualMachineConfigSpec spec = new VirtualMachineConfigSpec();
        spec.setAnnotation("");

        esxi.reconfigureVm("web", spec);

        assertThat(vmx().get("annotation"), is(nullValue()));
    }

    @Test
    void coresThatDoNotDivideTheCpusAreRefusedAndNothingIsWritten() throws Exception {
        final VSphereException e =
                assertThrows(VSphereException.class, () -> reconfigure(new ReconfigureCpu("5", "2")));

        assertThat(e.getMessage(), containsString("multiple of the cores per socket"));
        assertThat(host.file(VMX_PATH), is(VMX));
        assertThat(host.ran("/bin/vim-cmd vmsvc/reload 7"), is(false));
    }

    @Test
    void setsReservationsLimitsAndShares() throws Exception {
        final ReconfigureCpu cpu = new ReconfigureCpu("2", "1");
        cpu.setCpuLimitMHz("1000");

        reconfigure(cpu);

        assertThat(vmx().get("sched.cpu.min"), is("1000"));
        final VirtualMachineConfigSpec spec = new VirtualMachineConfigSpec();
        final ResourceAllocationInfo memory = new ResourceAllocationInfo();
        memory.setReservation(512L);
        memory.setLimit(-1L);
        final SharesInfo shares = new SharesInfo();
        shares.setLevel(SharesLevel.custom);
        shares.setShares(2000);
        memory.setShares(shares);
        spec.setMemoryAllocation(memory);
        final ResourceAllocationInfo limited = new ResourceAllocationInfo();
        limited.setLimit(3000L);
        final SharesInfo high = new SharesInfo();
        high.setLevel(SharesLevel.high);
        limited.setShares(high);
        spec.setCpuAllocation(limited);

        esxi.reconfigureVm("web", spec);

        assertThat(vmx().get("sched.mem.min"), is("512"));
        assertThat(vmx().get("sched.mem.max"), is("unlimited"));
        assertThat(vmx().get("sched.mem.shares"), is("2000"));
        assertThat(vmx().get("sched.cpu.max"), is("3000"));
        assertThat(vmx().get("sched.cpu.shares"), is("high"));
        assertThat(vmx().get("sched.cpu.min"), is("1000")); // not touched
        // and the VM tells them back
        final VirtualMachine vm = esxi.getVmByName("web");
        assertThat(vm.getConfig().getMemoryAllocation().getReservation(), is(512L));
        assertThat(vm.getConfig().getMemoryAllocation().getLimit(), is(-1L));
        assertThat(vm.getConfig().getMemoryAllocation().getShares().getLevel(), is(SharesLevel.custom));
        assertThat(vm.getConfig().getMemoryAllocation().getShares().getShares(), is(2000));
        assertThat(vm.getConfig().getCpuAllocation().getShares().getLevel(), is(SharesLevel.high));
    }

    @Test
    void aVmWithoutReservationsHasNoAllocation() throws Exception {
        assertThat(esxi.getVmByName("web").getConfig().getCpuAllocation(), is(nullValue()));
    }

    @Test
    void badReservationsAreRefused() {
        final VirtualMachineConfigSpec spec = new VirtualMachineConfigSpec();
        final ResourceAllocationInfo cpu = new ResourceAllocationInfo();
        cpu.setLimit(-5L);
        spec.setCpuAllocation(cpu);

        final VSphereException e = assertThrows(VSphereException.class, () -> esxi.reconfigureVm("web", spec));

        assertThat(e.getMessage(), containsString("-1 (unlimited) or more"));
        assertThat(host.file(VMX_PATH), is(VMX));
    }

    @Test
    void aRunningVmIsNotReconfigured() {
        host.vm(7).power = "Powered on";

        final VSphereException e =
                assertThrows(VSphereException.class, () -> reconfigure(new ReconfigureMemory("4096")));

        assertThat(e.getMessage(), containsString("has to be powered off"));
        assertThat(host.file(VMX_PATH), is(VMX));
    }

    @Test
    void aFailureOfTheHostIsReportedAndLeavesTheFileAsItWas() {
        host.failing("mv -f", "mv: can't rename: Read-only file system");

        final VSphereException e =
                assertThrows(VSphereException.class, () -> reconfigure(new ReconfigureMemory("4096")));

        assertThat(e.getMessage(), containsString("Read-only file system"));
        assertThat(host.file(VMX_PATH), is(VMX));
        assertThat(host.hasFile(VMX_PATH + ".jenkins-new"), is(false));
    }

    // -- extra configuration --

    @Test
    void setsAndRemovesExtraConfigurationParameters() throws Exception {
        final VirtualMachineConfigSpec spec = new VirtualMachineConfigSpec();
        spec.setExtraConfig(new OptionValue[] {
            option("guestinfo.role", "build \"node\""), option("scsi0:0.present", ""), option("isolation.tools", "x")
        });

        esxi.reconfigureVm("web", spec);

        assertThat(vmx().get("guestinfo.role"), is("build |22node|22"));
        assertThat(vmx().get("scsi0:0.present"), is(nullValue()));
        assertThat(vmx().get("isolation.tools"), is("x"));
    }

    @Test
    void extraConfigurationNamesAreChecked() {
        final VirtualMachineConfigSpec spec = new VirtualMachineConfigSpec();
        spec.setExtraConfig(new OptionValue[] {option("a = \"b\"\nevil", "x")});

        final VSphereException e = assertThrows(VSphereException.class, () -> esxi.reconfigureVm("web", spec));

        assertThat(e.getMessage(), containsString("cannot be used"));
        assertThat(host.file(VMX_PATH), is(VMX));
    }

    private static OptionValue option(String key, String value) {
        final OptionValue option = new OptionValue();
        option.setKey(key);
        option.setValue(value);
        return option;
    }

    // -- network adapters, with the step of the plugin --

    @Test
    void editsTheMacAddressAndPortGroupOfAnAdapter() throws Exception {
        reconfigure(nic(DeviceAction.EDIT, "Network adapter 2", "00:50:56:00:00:09", true, "VM Network"));

        assertThat(vmx().get("ethernet1.address"), is("00:50:56:00:00:09"));
        assertThat(vmx().get("ethernet1.addressType"), is("static"));
        assertThat(vmx().get("ethernet1.networkName"), is("VM Network"));
        // the other adapter is as it was
        assertThat(vmx().get("ethernet0.networkName"), is("VM Network"));
        assertThat(vmx().get("ethernet0.addressType"), is("generated"));
    }

    @Test
    void anAddressThatVcenterAssignsIsWrittenAsOne() throws Exception {
        final ReconfigureNetworkAdapters step =
                nic(DeviceAction.EDIT, "Network adapter 1", "00:50:56:00:00:09", false, "");
        reconfigure(step.fallbackVariant());

        assertThat(vmx().get("ethernet0.addressType"), is("vpx"));
        assertThat(vmx().get("ethernet0.generatedAddress"), is("00:50:56:00:00:09"));
    }

    @Test
    void anUnknownPortGroupIsLeftAsItWas() throws Exception {
        reconfigure(nic(DeviceAction.EDIT, "Network adapter 1", "", true, "No Such Network"));

        assertThat(vmx().get("ethernet0.networkName"), is("VM Network"));
    }

    @Test
    void whenThePortGroupsCannotBeListedTheNameIsTrusted() throws Exception {
        host.portGroups = null;
        host.failing("esxcli", "esxcli: not found");

        reconfigure(nic(DeviceAction.EDIT, "Network adapter 1", "", true, "Whatever"));

        assertThat(vmx().get("ethernet0.networkName"), is("Whatever"));
    }

    @Test
    void addsAnAdapterInTheFirstFreeSlot() throws Exception {
        reconfigure(nic(DeviceAction.ADD, "new one", "", true, "Management Network"));

        assertThat(vmx().get("ethernet2.present"), is("TRUE"));
        assertThat(vmx().get("ethernet2.networkName"), is("Management Network"));
        assertThat(vmx().get("ethernet2.virtualDev"), is("e1000"));
        assertThat(vmx().get("ethernet2.addressType"), is("generated"));
        assertThat(
                esxi.getVmByName("web").getConfig().getHardware().getDevice().length,
                is(1 + 1 + 1 + 3)); // PCI and SCSI controllers, a disk, three adapters
    }

    @Test
    void removesAnAdapter() throws Exception {
        reconfigure(nic(DeviceAction.REMOVE, "Network adapter 1", "", false, ""));

        assertThat(vmx().keys().stream().anyMatch(k -> k.startsWith("ethernet0.")), is(false));
        assertThat(vmx().get("ethernet1.present"), is("TRUE"));
    }

    @Test
    void editingAnAdapterThatIsNotThereFails() {
        final VirtualEthernetCard card = new VirtualVmxnet3();
        card.setKey(4005);

        final VSphereException e = assertThrows(VSphereException.class, () -> esxi.reconfigureVm("web", change(card)));

        assertThat(e.getMessage(), containsString("no network adapter with the key 4005"));
    }

    @Test
    void theCardOfAnAddIsMadeAs() throws Exception {
        final VirtualEthernetCard card = new VirtualVmxnet3();
        card.setKey(-1);
        final VirtualEthernetCardNetworkBackingInfo backing = new VirtualEthernetCardNetworkBackingInfo();
        backing.setDeviceName("VM Network");
        card.setBacking(backing);
        final VirtualDeviceConfigSpec add = new VirtualDeviceConfigSpec();
        add.setDevice(card);
        add.setOperation(VirtualDeviceConfigSpecOperation.add);
        final VirtualMachineConfigSpec spec = new VirtualMachineConfigSpec();
        spec.setDeviceChange(new VirtualDeviceConfigSpec[] {add});

        esxi.reconfigureVm("web", spec);

        assertThat(vmx().get("ethernet2.virtualDev"), is("vmxnet3"));
    }

    @Test
    void badMacAddressesAreRefused() {
        final VirtualEthernetCard card = new VirtualE1000();
        card.setKey(4000);
        card.setMacAddress("00:50:56:\"; rm -rf /");
        card.setAddressType("manual");

        final VSphereException e = assertThrows(VSphereException.class, () -> esxi.reconfigureVm("web", change(card)));

        assertThat(e.getMessage(), containsString("is not a MAC address"));
        assertThat(host.file(VMX_PATH), is(VMX));
    }

    @Test
    void devicesOfOtherKindsCannotBeChanged() {
        final VirtualDevice disk = new com.vmware.vim25.VirtualCdrom();

        final VSphereException e = assertThrows(VSphereException.class, () -> esxi.reconfigureVm("web", change(disk)));

        assertThat(e.getMessage(), containsString("only network adapters, disks and SCSI controllers can be changed"));
        assertThat(e.getMessage(), containsString("VirtualCdrom"));
    }

    private static VirtualMachineConfigSpec change(VirtualDevice device) {
        final VirtualDeviceConfigSpec change = new VirtualDeviceConfigSpec();
        change.setDevice(device);
        change.setOperation(VirtualDeviceConfigSpecOperation.edit);
        final VirtualMachineConfigSpec spec = new VirtualMachineConfigSpec();
        spec.setDeviceChange(new VirtualDeviceConfigSpec[] {change});
        return spec;
    }

    // -- renaming --

    @Test
    void renamesAVm() throws Exception {
        esxi.renameVm("web", "web 2");

        assertThat(vmx().get("displayName"), is("web 2"));
        assertThat(esxi.getVmByName("web 2"), is(notNullValue()));
        assertThat(esxi.getVmByName("web"), is(nullValue()));
    }

    @Test
    void aNameThatIsNotPlainIsRefusedForARename() {
        final VSphereException e = assertThrows(VSphereException.class, () -> esxi.renameVm("web", "a\"b"));

        assertThat(e.getMessage(), containsString("cannot be used"));
        assertThat(vmx().get("displayName"), is("web"));
    }

    // -- the port groups --

    @Test
    void knowsThePortGroupsOfTheHost() throws Exception {
        final VirtualMachine vm = esxi.getVmByName("web");

        assertThat(esxi.getNetworkPortGroupByName(vm, "VM Network").getName(), is("VM Network"));
        assertThat(esxi.getNetworkPortGroupByName(vm, "Nope"), is(nullValue()));
        assertThat(esxi.getNetworkPortGroupByName(vm, "VM"), is(nullValue()));
    }

    @Test
    void readsThePortGroupsTable() {
        final String table = "Name                 Virtual Switch  Active Clients  VLAN ID\n"
                + "-------------------  --------------  --------------  -------\n"
                + "Management Network   vSwitch0                     1        0\n"
                + "VM Network           vSwitch0                     2        0\n";

        assertThat(EsxiNetwork.isListed(table, "VM Network"), is(true));
        assertThat(EsxiNetwork.isListed(table, "Network"), is(false));
        assertThat(EsxiNetwork.isListed("something else", "VM Network"), is(nullValue()));
    }
}
