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
import static org.hamcrest.Matchers.empty;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.nullValue;

import com.vmware.vim25.VirtualMachinePowerState;
import com.vmware.vim25.VirtualMachineSnapshotTree;
import com.vmware.vim25.VirtualMachineToolsStatus;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * The samples are laid out like what {@code vim-cmd} prints (see the notes in the esxi-linked-clone scripts for
 * some of them). They have not been checked against every ESXi version: confirm with the real output of a host.
 */
class VimCmdParsersTest {

    private static final String GETALLVMS = String.join(
            "\n",
            "Vmid         Name                           File                            Guest OS       Version   Annotation",
            "1      kube-master          [datastore1] kube-master/kube-master.vmx          ubuntu64Guest     vmx-14",
            "12     my vm                [datastore 2] my vm/my vm.vmx                     otherGuest64      vmx-13    Built by CI",
            "13     a.*                  [datastore1] a.star/a.star.vmx                    otherGuest        vmx-10",
            "");

    @Test
    void getAllVmsListsTheRegisteredVmsAndSkipsTheHeader() {
        List<VmEntry> vms = VimCmdParsers.parseGetAllVms(GETALLVMS);

        assertThat(vms.size(), is(3));
        assertThat(vms.get(0).getId(), is(1));
        assertThat(vms.get(0).getName(), is("kube-master"));
        assertThat(vms.get(0).getVmxPath(), is("[datastore1] kube-master/kube-master.vmx"));
        assertThat(vms.get(0).getGuestOs(), is("ubuntu64Guest"));
        assertThat(vms.get(0).getVersion(), is("vmx-14"));
        assertThat(vms.get(0).getAnnotation(), is(""));
    }

    @Test
    void namesAndDatastoresMayContainSpaces() {
        VmEntry vm = VimCmdParsers.parseGetAllVms(GETALLVMS).get(1);

        assertThat(vm.getId(), is(12));
        assertThat(vm.getName(), is("my vm"));
        assertThat(vm.getDatastore(), is("datastore 2"));
        assertThat(vm.getVmxRelativePath(), is("my vm/my vm.vmx"));
        assertThat(vm.getVmxFileSystemPath(), is("/vmfs/volumes/datastore 2/my vm/my vm.vmx"));
        assertThat(vm.getAnnotation(), is("Built by CI"));
    }

    // What an ESXi 8 host printed
    private static final String ESXI8_NO_VMS = "Vmid   Name   File   Guest OS   Version   Annotation\n";

    private static final String ESXI8_ONE_VM = String.join(
            "\n",
            "Vmid   Name          File              Guest OS       Version   Annotation",
            "1      x8     [pve-esx] x8/x8.vmx   centos9_64Guest   vmx-20",
            "");

    private static final String ESXI8_GUEST = String.join(
            "\n",
            "Guest information:",
            "",
            "(vim.vm.GuestInfo) {",
            "   toolsStatus = \"toolsNotInstalled\",",
            "   toolsVersionStatus = \"guestToolsNotInstalled\",",
            "   toolsVersionStatus2 = \"guestToolsNotInstalled\",",
            "   toolsRunningStatus = \"guestToolsNotRunning\",",
            "   toolsVersion = \"0\",",
            "   toolsInstallType = \"guestToolsTypeUnknown\",",
            "   toolsUpdateStatus = (vim.vm.GuestInfo.ToolsUpdateStatus) null,",
            "   guestId = <unset>,",
            "   guestFamily = <unset>,",
            "   guestFullName = <unset>,",
            "   guestDetailedData = <unset>,",
            "   hostName = <unset>,",
            "   ipAddress = <unset>,",
            "   net = <unset>,",
            "   ipStack = <unset>,",
            "   disk = <unset>,",
            "   screen = (vim.vm.GuestInfo.ScreenInfo) {",
            "      width = 1024,",
            "      height = 768",
            "   },",
            "   guestState = \"running\",",
            "   hwVersion = \"vmx-20\",",
            "   customizationInfo = (vim.vm.GuestInfo.CustomizationInfo) {",
            "      customizationStatus = \"TOOLSDEPLOYPKG_IDLE\",",
            "      startTime = <unset>,",
            "      endTime = <unset>,",
            "      errorMsg = <unset>",
            "   }",
            "}",
            "");

    // ESXi 7: a VM of another guest family and hardware version, and a field that is <unset> there
    private static final String ESXI7_ONE_VM = String.join(
            "\n",
            "Vmid   Name          File              Guest OS      Version   Annotation",
            "1      x7     [pve-esx] x7/x7.vmx   solaris10Guest   vmx-19",
            "");

    private static final String ESXI7_GUEST = String.join(
            "\n",
            "Guest information:",
            "",
            "(vim.vm.GuestInfo) {",
            "   toolsStatus = \"toolsNotInstalled\",",
            "   toolsVersionStatus = \"guestToolsNotInstalled\",",
            "   toolsVersionStatus2 = \"guestToolsNotInstalled\",",
            "   toolsRunningStatus = \"guestToolsNotRunning\",",
            "   toolsVersion = \"0\",",
            "   toolsInstallType = <unset>,",
            "   ipAddress = <unset>,",
            "   guestState = \"notRunning\",",
            "   hwVersion = \"vmx-19\",",
            "}",
            "");

    // After "vim-cmd vmsvc/snapshot.create 1 snap1 "Shapshot #1"" on an ESXi 8 host
    private static final String ESXI8_ONE_SNAPSHOT = String.join(
            "\n",
            "Get Snapshot:",
            "|-ROOT",
            "--Snapshot Name        : snap1",
            "--Snapshot Id        : 1",
            "--Snapshot Desciption  : Shapshot #1",
            "--Snapshot Created On  : 10/4/2026 18:59:44",
            "--Snapshot State       : powered off",
            "");

    @Test
    void esxi7OutputIsReadLikeEsxi8Output() {
        List<VmEntry> vms = VimCmdParsers.parseGetAllVms(ESXI7_ONE_VM);

        assertThat(vms.size(), is(1));
        assertThat(vms.get(0).getName(), is("x7"));
        assertThat(vms.get(0).getGuestOs(), is("solaris10Guest"));
        assertThat(vms.get(0).getVersion(), is("vmx-19"));
        assertThat(VimCmdParsers.parseGuestIp(ESXI7_GUEST), is(nullValue()));
        assertThat(VimCmdParsers.parseToolsStatus(ESXI7_GUEST), is(VirtualMachineToolsStatus.toolsNotInstalled));
    }

    // The same on an ESXi 7 host, which prints the time without zero padding
    private static final String ESXI7_ONE_SNAPSHOT = String.join(
            "\n",
            "Get Snapshot:",
            "|-ROOT",
            "--Snapshot Name        : snap1",
            "--Snapshot Id        : 1",
            "--Snapshot Desciption  : Shapshot #1",
            "--Snapshot Created On  : 10/4/2026 19:0:52",
            "--Snapshot State       : powered off",
            "");

    // Two snapshots in a row on an ESXi 7 host: the second is a child of the first, shown with "--|-CHILD" and
    // its fields indented by four dashes, and its description is empty
    private static final String ESXI7_TWO_SNAPSHOTS_IN_A_ROW = String.join(
            "\n",
            "Get Snapshot:",
            "|-ROOT",
            "--Snapshot Name        : snap1",
            "--Snapshot Id        : 1",
            "--Snapshot Desciption  : Shapshot #1",
            "--Snapshot Created On  : 10/4/2026 19:0:52",
            "--Snapshot State       : powered off",
            "--|-CHILD",
            "----Snapshot Name        : Corelinux running",
            "----Snapshot Id        : 2",
            "----Snapshot Desciption  :",
            "----Snapshot Created On  : 10/4/2026 19:22:55",
            "----Snapshot State       : powered on",
            "");

    @Test
    void twoSnapshotsInARowAreAParentAndItsChild() {
        List<VirtualMachineSnapshotTree> roots = VimCmdParsers.parseSnapshotTree(ESXI7_TWO_SNAPSHOTS_IN_A_ROW);

        assertThat(roots.size(), is(1));
        VirtualMachineSnapshotTree first = roots.get(0);
        assertThat(first.getName(), is("snap1"));
        assertThat(first.getSnapshot().getVal(), is("1"));
        assertThat(first.getState(), is(VirtualMachinePowerState.poweredOff));
        assertThat(first.getChildSnapshotList().length, is(1));
        VirtualMachineSnapshotTree second = first.getChildSnapshotList()[0];
        assertThat(second.getName(), is("Corelinux running"));
        assertThat(second.getSnapshot().getVal(), is("2"));
        assertThat(second.getDescription(), is(""));
        assertThat(second.getState(), is(VirtualMachinePowerState.poweredOn));
        assertThat(second.getChildSnapshotList() == null, is(true));
    }

    @Test
    void esxi7SingleSnapshotIsReadLikeTheEsxi8One() {
        List<VirtualMachineSnapshotTree> roots = VimCmdParsers.parseSnapshotTree(ESXI7_ONE_SNAPSHOT);

        assertThat(roots.size(), is(1));
        assertThat(roots.get(0).getName(), is("snap1"));
        assertThat(roots.get(0).getSnapshot().getVal(), is("1"));
        assertThat(roots.get(0).getDescription(), is("Shapshot #1"));
        assertThat(roots.get(0).getState(), is(VirtualMachinePowerState.poweredOff));
    }

    @Test
    void esxi8SingleSnapshot() {
        List<VirtualMachineSnapshotTree> roots = VimCmdParsers.parseSnapshotTree(ESXI8_ONE_SNAPSHOT);

        assertThat(roots.size(), is(1));
        assertThat(roots.get(0).getName(), is("snap1"));
        assertThat(roots.get(0).getSnapshot().getVal(), is("1"));
        assertThat(roots.get(0).getDescription(), is("Shapshot #1"));
        assertThat(roots.get(0).getState(), is(VirtualMachinePowerState.poweredOff));
        assertThat(roots.get(0).getChildSnapshotList() == null, is(true));
    }

    @Test
    void esxi8WithoutVms() {
        assertThat(VimCmdParsers.parseGetAllVms(ESXI8_NO_VMS), is(empty()));
    }

    @Test
    void esxi8WithOneVm() {
        List<VmEntry> vms = VimCmdParsers.parseGetAllVms(ESXI8_ONE_VM);

        assertThat(vms.size(), is(1));
        assertThat(vms.get(0).getId(), is(1));
        assertThat(vms.get(0).getName(), is("x8"));
        assertThat(vms.get(0).getVmxPath(), is("[pve-esx] x8/x8.vmx"));
        assertThat(vms.get(0).getDatastore(), is("pve-esx"));
        assertThat(vms.get(0).getVmxFileSystemPath(), is("/vmfs/volumes/pve-esx/x8/x8.vmx"));
        assertThat(vms.get(0).getGuestOs(), is("centos9_64Guest"));
        assertThat(vms.get(0).getVersion(), is("vmx-20"));
        assertThat(vms.get(0).getAnnotation(), is(""));
    }

    @Test
    void esxi8GuestWithoutToolsHasNoIpAndNoTools() {
        assertThat(VimCmdParsers.parseGuestIp(ESXI8_GUEST), is(nullValue()));
        assertThat(VimCmdParsers.parseToolsStatus(ESXI8_GUEST), is(VirtualMachineToolsStatus.toolsNotInstalled));
        assertThat(VimCmdParsers.parseFault(ESXI8_GUEST), is(nullValue()));
    }

    @Test
    void faultsAreRecognisedAndExplained() {
        assertThat(
                VimCmdParsers.parseFault(FakeEsxiHost.notFound("1")),
                is("NotFound: Unable to find a VM corresponding to \"1\""));
        assertThat(VimCmdParsers.parseFault("(vim.fault.InvalidState) {\n}\n"), is("InvalidState"));
        // ordinary output is no fault, nor is a fault only mentioned inside of some other text
        assertThat(VimCmdParsers.parseFault("Retrieved runtime info\nPowered on\n"), is(nullValue()));
        assertThat(VimCmdParsers.parseFault(ESXI8_ONE_VM), is(nullValue()));
        assertThat(VimCmdParsers.parseFault("   x = (vim.fault.NotFound) null,\n"), is(nullValue()));
        assertThat(VimCmdParsers.parseFault(null), is(nullValue()));
    }

    @Test
    void nothingOrGarbageIsNoVms() {
        assertThat(VimCmdParsers.parseGetAllVms(null), is(empty()));
        assertThat(VimCmdParsers.parseGetAllVms(""), is(empty()));
        assertThat(VimCmdParsers.parseGetAllVms("Failed to get VMs\n"), is(empty()));
    }

    @Test
    void powerStates() {
        assertThat(
                VimCmdParsers.parsePowerState("Retrieved runtime info\nPowered on\n"),
                is(VirtualMachinePowerState.poweredOn));
        assertThat(
                VimCmdParsers.parsePowerState("Retrieved runtime info\nPowered off\n"),
                is(VirtualMachinePowerState.poweredOff));
        assertThat(
                VimCmdParsers.parsePowerState("Retrieved runtime info\nSuspended\n"),
                is(VirtualMachinePowerState.suspended));
        assertThat(VimCmdParsers.parsePowerState("something else"), is(nullValue()));
        assertThat(VimCmdParsers.parsePowerState(null), is(nullValue()));
    }

    private static final String GUEST = String.join(
            "\n",
            "Guest information:",
            "(vim.vm.GuestInfo) {",
            "   toolsStatus = \"toolsOk\",",
            "   toolsVersionStatus = \"guestToolsCurrent\",",
            "   toolsRunningStatus = \"guestToolsRunning\",",
            "   hostName = \"kube-master\",",
            "   ipAddress = \"10.1.2.3\",",
            "   net = (vim.vm.GuestInfo.NicInfo) [",
            "      (vim.vm.GuestInfo.NicInfo) {",
            "         network = \"VM Network\",",
            "         ipAddress = (string) [",
            "            \"10.9.9.9\",",
            "            \"fe80::1\"",
            "         ],",
            "      }",
            "   ],",
            "}",
            "");

    @Test
    void guestIpIsTheOneTheGuestReportsForItself() {
        assertThat(VimCmdParsers.parseGuestIp(GUEST), is("10.1.2.3"));
    }

    @Test
    void guestWithoutAnIpHasNone() {
        assertThat(
                VimCmdParsers.parseGuestIp("(vim.vm.GuestInfo) {\n   toolsStatus = \"toolsNotRunning\",\n}\n"),
                is(nullValue()));
        // an address that is only in the per-adapter lists is not the guest's own
        assertThat(
                VimCmdParsers.parseGuestIp("   ipAddress = (string) [\n      \"10.9.9.9\",\n   ],\n"), is(nullValue()));
        assertThat(VimCmdParsers.parseGuestIp(null), is(nullValue()));
    }

    @Test
    void toolsStatus() {
        assertThat(VimCmdParsers.parseToolsStatus(GUEST), is(VirtualMachineToolsStatus.toolsOk));
        assertThat(
                VimCmdParsers.parseToolsStatus("   toolsStatus = \"toolsNotInstalled\","),
                is(VirtualMachineToolsStatus.toolsNotInstalled));
        assertThat(VimCmdParsers.parseToolsStatus("   toolsStatus = \"somethingNew\","), is(nullValue()));
        assertThat(VimCmdParsers.parseToolsStatus("nothing here"), is(nullValue()));
    }

    // The way children are indented is how it is believed to look, to be confirmed on a host with nested snapshots
    private static final String SNAPSHOTS = String.join(
            "\n",
            "Get Snapshot:",
            "|-ROOT",
            "--Snapshot Name        : forclone-1616763091",
            "--Snapshot Id        : 7",
            "--Snapshot Desciption  : Base for CI VM clone",
            "--Snapshot Created On  : 3/26/2021 12:51:38",
            "--Snapshot State       : powered off",
            "|   |-CHILD",
            "|   --Snapshot Name        : formaster-1616763092",
            "|   --Snapshot Id        : 8",
            "|   --Snapshot Desciption  : Files where master would write",
            "|   --Snapshot Created On  : 3/26/2021 12:51:40",
            "|   --Snapshot State       : powered off",
            "--Snapshot Name        : another-root",
            "--Snapshot Id        : 9",
            "--Snapshot Desciption  : ",
            "--Snapshot Created On  : 3/27/2021 1:00:00",
            "--Snapshot State       : powered on",
            "");

    @Test
    void snapshotTreeIsRebuiltFromTheIndentation() {
        List<VirtualMachineSnapshotTree> roots = VimCmdParsers.parseSnapshotTree(SNAPSHOTS);

        assertThat(roots.size(), is(2));
        VirtualMachineSnapshotTree first = roots.get(0);
        assertThat(first.getName(), is("forclone-1616763091"));
        assertThat(first.getSnapshot().getVal(), is("7"));
        assertThat(first.getDescription(), is("Base for CI VM clone"));
        assertThat(first.getState(), is(VirtualMachinePowerState.poweredOff));
        assertThat(first.getChildSnapshotList().length, is(1));
        assertThat(first.getChildSnapshotList()[0].getName(), is("formaster-1616763092"));
        assertThat(first.getChildSnapshotList()[0].getSnapshot().getVal(), is("8"));
        assertThat(roots.get(1).getName(), is("another-root"));
        assertThat(roots.get(1).getState(), is(VirtualMachinePowerState.poweredOn));
        assertThat(roots.get(1).getChildSnapshotList() == null, is(true));
    }

    @Test
    void noSnapshots() {
        assertThat(VimCmdParsers.parseSnapshotTree("Get Snapshot:\n"), is(empty()));
        assertThat(VimCmdParsers.parseSnapshotTree(null), is(empty()));
    }
}
