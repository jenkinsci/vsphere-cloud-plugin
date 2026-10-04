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
