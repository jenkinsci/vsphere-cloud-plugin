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
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.notNullValue;
import static org.hamcrest.Matchers.nullValue;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.util.Map;
import org.jenkinsci.plugins.vsphere.tools.VSphereDuplicateException;
import org.jenkinsci.plugins.vsphere.tools.VSphereException;
import org.jenkinsci.plugins.vsphere.tools.VSphereNotFoundException;
import org.jenkinsci.plugins.vsphere.tools.VmSize;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Cloning a VM on a standalone ESXi host, by working on the files of its datastore, against {@link FakeEsxiHost}
 * (which has files and a few commands that work on them): the clone gets a delta of the master's snapshot as a change
 * of the master's disk (or a copy of the disk), a {@code .vmx} of its own, and is registered and started.
 */
class EsxiCloneTest {

    private static final String DS = "/vmfs/volumes/datastore1";
    private static final String MASTER = DS + "/master";
    /** The same datastore by where it really is */
    private static final String REAL = "/vmfs/volumes/5f3a-uuid-datastore1";

    private static final String MASTER_VMX = String.join(
            "\n",
            ".encoding = \"UTF-8\"",
            "displayName = \"master\"",
            "numvcpus = \"2\"",
            "memSize = \"4096\"",
            "cpuid.coresPerSocket = \"1\"",
            "nvram = \"master.nvram\"",
            "scsi0.present = \"TRUE\"",
            "scsi0:0.present = \"TRUE\"",
            "scsi0:0.fileName = \"master-000002.vmdk\"",
            "ide0:0.present = \"TRUE\"",
            "ide0:0.deviceType = \"cdrom-image\"",
            "ide0:0.fileName = \"/vmfs/volumes/datastore1/iso/os.iso\"",
            "uuid.bios = \"42 1a 2b 3c\"",
            "uuid.location = \"56 4d 5e 6f\"",
            "vc.uuid = \"52 aa bb cc\"",
            "sched.swap.derivedName = \"/vmfs/volumes/5f3a-uuid-datastore1/master/master-1234.vswp\"",
            "ethernet0.present = \"TRUE\"",
            "ethernet0.addressType = \"static\"",
            "ethernet0.address = \"00:50:56:01:02:03\"",
            "ethernet1.present = \"TRUE\"",
            "ethernet1.addressType = \"generated\"",
            "ethernet1.generatedAddress = \"00:0c:29:aa:bb:cc\"",
            "ethernet1.generatedAddressOffset = \"10\"",
            "guestinfo.old = \"stale\"",
            "");

    private static String descriptor(String parentHint, String extentType, String extent) {
        return "# Disk DescriptorFile\nversion=1\nCID=1234abcd\nparentCID=ffffffff\ncreateType=\"seSparse\"\n"
                + (parentHint == null ? "" : "parentFileNameHint=\"" + parentHint + "\"\n")
                + "\n# Extent description\nRW 100 " + extentType + " \"" + extent + "\"\n\n# The Disk Data Base\n";
    }

    private FakeEsxiHost host;
    private VSphereEsxiSsh esxi;
    private final ByteArrayOutputStream logBytes = new ByteArrayOutputStream();
    private final PrintStream log = new PrintStream(logBytes);

    @BeforeEach
    void setUp() {
        host = new FakeEsxiHost();
        host.datastoreUuids.put("datastore1", "5f3a-uuid-datastore1");
        host.addVm(1, "master", "datastore1", "master/master.vmx", MASTER_VMX);
        // a base disk, a snapshot of it, and what the master writes since (the disk that it has now)
        host.addFile(MASTER + "/master.vmdk", descriptor(null, "VMFS", "master-flat.vmdk"));
        host.addFile(MASTER + "/master-flat.vmdk", "BASE DATA");
        host.addFile(
                MASTER + "/master-000001.vmdk", descriptor("master.vmdk", "SESPARSE", "master-000001-sesparse.vmdk"));
        host.addFile(MASTER + "/master-000001-sesparse.vmdk", "SNAPSHOT DELTA");
        host.addFile(
                MASTER + "/master-000002.vmdk",
                descriptor("master-000001.vmdk", "SESPARSE", "master-000002-sesparse.vmdk"));
        host.addFile(MASTER + "/master-000002-sesparse.vmdk", "HEAD DELTA");
        esxi = new VSphereEsxiSsh(host);
    }

    private void clone(String name, boolean linked, boolean powerOn) throws Exception {
        esxi.cloneVm(name, "master", linked, "", "", "", "", powerOn, "", log);
    }

    // -- linked clones --

    @Test
    void aLinkedCloneGetsACopyOfTheNewestDeltaAsAChangeOfTheDisksParent() throws Exception {
        clone("clone-01", true, false);

        String cloneDir = DS + "/clone-01";
        // the delta that the master has now is what is copied, with the data that it holds
        assertThat(host.file(cloneDir + "/master-000002-sesparse.vmdk"), is("HEAD DELTA"));
        // and made a change of the disk that the master's is a change of, by where that really is
        String descriptor = host.file(cloneDir + "/master-000002.vmdk");
        assertThat(descriptor, containsString("parentFileNameHint=\"" + REAL + "/master/master-000001.vmdk\""));
        assertThat(descriptor, containsString("RW 100 SESPARSE \"master-000002-sesparse.vmdk\""));
        // nothing of the master is touched, and nothing of its shared data is copied
        assertThat(
                host.file(MASTER + "/master-000002.vmdk"), containsString("parentFileNameHint=\"master-000001.vmdk\""));
        assertThat(host.hasFile(cloneDir + "/master-flat.vmdk"), is(false));
        assertThat(host.hasFile(cloneDir + "/master-000001-sesparse.vmdk"), is(false));
    }

    @Test
    void theVmxOfTheCloneHasItsOwnNameAndNothingOfTheIdentityOfTheMaster() throws Exception {
        clone("clone-01", true, false);

        String vmx = host.file(DS + "/clone-01/clone-01.vmx");
        VmxFile parsed = VmxFile.parse(vmx);
        assertThat(parsed.get("displayName"), is("clone-01"));
        assertThat(parsed.get("nvram"), is("clone-01.nvram"));
        assertThat(parsed.get("scsi0:0.fileName"), is("master-000002.vmdk"));
        // the disks and hardware stay as they were, and so does what is not a disk
        assertThat(parsed.get("numvcpus"), is("2"));
        assertThat(parsed.get("memSize"), is("4096"));
        assertThat(parsed.get("ide0:0.fileName"), is("/vmfs/volumes/datastore1/iso/os.iso"));
        // what makes the master what it is, and not the clone, is gone, to be made new when it is started
        assertThat(parsed.get("uuid.bios"), is(nullValue()));
        assertThat(parsed.get("uuid.location"), is(nullValue()));
        assertThat(parsed.get("vc.uuid"), is(nullValue()));
        assertThat(parsed.get("sched.swap.derivedName"), is(nullValue()));
        assertThat(parsed.get("ethernet0.address"), is(nullValue()));
        assertThat(parsed.get("ethernet0.addressType"), is(nullValue()));
        assertThat(parsed.get("ethernet1.generatedAddress"), is(nullValue()));
        assertThat(parsed.get("ethernet1.generatedAddressOffset"), is(nullValue()));
        assertThat(parsed.get("ethernet0.present"), is("TRUE"));
        assertThat(parsed.get("guestinfo.old"), is(nullValue()));
        assertThat(parsed.get("guestinfo.mastername"), is("master"));
        assertThat(parsed.get("guestinfo.vmname"), is("clone-01"));
        assertThat(parsed.get("guestinfo.hostname"), is("clone-01"));
        // and the master's own is not touched
        assertThat(host.file(MASTER + "/master.vmx"), containsString("displayName = \"master\""));
    }

    @Test
    void theCloneIsRegisteredAndStartedOnRequest() throws Exception {
        clone("clone-01", true, true);

        FakeEsxiHost.FakeVm registered = host.vmNamed("clone-01");
        assertThat(registered, is(notNullValue()));
        assertThat(registered.power, is("Powered on"));
        assertThat(registered.vmxRelativePath, is("clone-01/clone-01.vmx"));
        assertThat(esxi.getVmByName("clone-01").getName(), is("clone-01"));
        // the master was neither touched nor started
        assertThat(host.vm(1).power, is("Powered off"));
    }

    @Test
    void theCloneIsNotStartedUnlessAskedTo() throws Exception {
        clone("clone-01", true, false);

        assertThat(host.vmNamed("clone-01").power, is("Powered off"));
    }

    @Test
    void whenTheMasterRunsTheDiskItHasInUseIsNotCopiedButTheSnapshotDiskBeforeIt() throws Exception {
        host.locked.add(MASTER + "/master-000002-sesparse.vmdk");

        clone("clone-01", true, false);

        String cloneDir = DS + "/clone-01";
        assertThat(host.hasFile(cloneDir + "/master-000002.vmdk"), is(false));
        assertThat(host.file(cloneDir + "/master-000001-sesparse.vmdk"), is("SNAPSHOT DELTA"));
        assertThat(
                host.file(cloneDir + "/master-000001.vmdk"),
                containsString("parentFileNameHint=\"" + REAL + "/master/master.vmdk\""));
        assertThat(
                VmxFile.parse(host.file(cloneDir + "/clone-01.vmx")).get("scsi0:0.fileName"), is("master-000001.vmdk"));
    }

    @Test
    void severalClonesShareTheDataOfTheMaster() throws Exception {
        clone("clone-01", true, false);
        clone("clone-02", true, false);

        for (String name : new String[] {"clone-01", "clone-02"}) {
            assertThat(
                    host.file(DS + "/" + name + "/master-000002.vmdk"),
                    containsString("parentFileNameHint=\"" + REAL + "/master/master-000001.vmdk\""));
        }
        assertThat(host.vmNamed("clone-01").id != host.vmNamed("clone-02").id, is(true));
    }

    @Test
    void aMasterThatHasNoSnapshotCannotBeLinkedTo() throws Exception {
        host.addFile(
                MASTER + "/master.vmx",
                MASTER_VMX.replace("scsi0:0.fileName = \"master-000002.vmdk\"", "scsi0:0.fileName = \"master.vmdk\""));

        VSphereException e = assertThrows(VSphereException.class, () -> clone("clone-01", true, false));

        assertThat(e.getMessage(), containsString("has no snapshot disk that a linked clone can be made of"));
        // and nothing of the attempt is left
        assertThat(host.hasDirectory(DS + "/clone-01"), is(false));
        assertThat(host.vmNamed("clone-01"), is(nullValue()));
    }

    @Test
    void aLinkedCloneHasToBeOnTheDatastoreOfItsMaster() throws Exception {
        VSphereException e = assertThrows(
                VSphereException.class,
                () -> esxi.cloneVm("clone-01", "master", true, "", "", "datastore2", "", false, "", log));

        assertThat(e.getMessage(), containsString("A linked clone has to be on the datastore of its master"));
        assertThat(host.hasDirectory("/vmfs/volumes/datastore2/clone-01"), is(false));
    }

    // -- full clones --

    @Test
    void aFullCloneIsACopyOfTheDiskMadeWithVmkfstools() throws Exception {
        clone("clone-01", false, false);

        String cloneDir = DS + "/clone-01";
        assertThat(host.file(cloneDir + "/clone-01-flat.vmdk"), is("COPY OF " + MASTER + "/master-000002.vmdk"));
        assertThat(host.file(cloneDir + "/clone-01.vmdk"), not(containsString("parentFileNameHint")));
        assertThat(VmxFile.parse(host.file(cloneDir + "/clone-01.vmx")).get("scsi0:0.fileName"), is("clone-01.vmdk"));
        assertThat(
                host.commands.stream().anyMatch(c -> c.startsWith("vmkfstools -i '" + MASTER + "/master-000002.vmdk'")),
                is(true));
    }

    @Test
    void aFullCloneOfARunningMasterIsMadeFromTheDiskBeforeTheOneInUse() throws Exception {
        host.locked.add(MASTER + "/master-000002-sesparse.vmdk");

        clone("clone-01", false, false);

        assertThat(host.file(DS + "/clone-01/clone-01-flat.vmdk"), is("COPY OF " + MASTER + "/master-000001.vmdk"));
    }

    @Test
    void aFullCloneMayBeOnAnotherDatastore() throws Exception {
        host.addFile("/vmfs/volumes/datastore2/.keep", "");

        esxi.cloneVm("clone-01", "master", false, "", "", "datastore2", "", false, "", log);

        assertThat(host.hasFile("/vmfs/volumes/datastore2/clone-01/clone-01.vmx"), is(true));
        assertThat(host.vmNamed("clone-01").datastore, is("datastore2"));
    }

    // -- what is asked for, and what goes wrong --

    @Test
    void aNameThatIsTakenIsADuplicate() throws Exception {
        assertThrows(VSphereDuplicateException.class, () -> clone("master", true, false));
        clone("clone-01", true, false);

        assertThrows(VSphereDuplicateException.class, () -> clone("clone-01", true, false));
    }

    @Test
    void aMasterThatIsNotThereIsNotFound() {
        assertThrows(
                VSphereNotFoundException.class,
                () -> esxi.cloneVm("clone-01", "no-such-master", true, "", "", "", "", false, "", log));
    }

    @Test
    void namesThatCouldBeUsedForMischiefAreRefusedBeforeAnythingIsDone() {
        for (String name : new String[] {"../escape", "a/b", "x;rm -rf /", "$(reboot)", "a'b", ".hidden", "", "x\ny"}) {
            int before = host.commands.size();

            assertThrows(VSphereException.class, () -> clone(name, true, false), name);

            // only the VMs were listed, if even that: nothing was made or removed
            assertThat(
                    host.commands.subList(before, host.commands.size()).stream()
                            .noneMatch(c -> c.startsWith("mkdir") || c.startsWith("rm") || c.startsWith("cp")),
                    is(true));
        }
    }

    @Test
    void whenSomethingGoesWrongWhatWasMadeIsRemovedAgain() throws Exception {
        host.failing("master-000002-sesparse.vmdk' '", "cp: no space left on device");

        VSphereException e = assertThrows(VSphereException.class, () -> clone("clone-01", true, true));

        assertThat(e.getMessage(), containsString("no space left on device"));
        assertThat(host.hasDirectory(DS + "/clone-01"), is(false));
        assertThat(host.vmNamed("clone-01"), is(nullValue()));
        assertThat(logBytes.toString(), containsString("Removed what was made of \"clone-01\""));
    }

    @Test
    void aCloneThatIsRegisteredButCannotBeStartedIsUnregisteredAndRemoved() throws Exception {
        host.failing("power.on", "Insufficient resources");

        VSphereException e = assertThrows(VSphereException.class, () -> clone("clone-01", true, true));

        assertThat(e.getMessage(), containsString("Insufficient resources"));
        assertThat(host.vmNamed("clone-01"), is(nullValue()));
        assertThat(host.hasDirectory(DS + "/clone-01"), is(false));
    }

    @Test
    void thingsThatHaveNoMeaningForAStandaloneHostAreRefusedWhereTheyWouldChangeTheOutcome() {
        VSphereException spec = assertThrows(
                VSphereException.class, () -> esxi.cloneVm("c", "master", true, "", "", "", "", false, "a-spec", log));
        assertThat(spec.getMessage(), containsString("a customization specification cannot be used"));
        VSphereException target = assertThrows(
                VSphereException.class,
                () -> esxi.cloneOrDeployVm(
                        "c",
                        "master",
                        true,
                        "",
                        "",
                        "",
                        "",
                        true,
                        "",
                        false,
                        null,
                        "",
                        "other-host",
                        "",
                        null,
                        null,
                        VmSize.NONE,
                        log));
        assertThat(target.getMessage(), containsString("choosing a host cannot be used"));
        assertThat(target, org.hamcrest.Matchers.instanceOf(EsxiPlatformConstraint.class));
        VSphereException snapshot = assertThrows(
                VSphereException.class,
                () -> esxi.cloneOrDeployVm(
                        "c",
                        "master",
                        true,
                        "",
                        "",
                        "",
                        "",
                        false,
                        "snap1",
                        false,
                        null,
                        "",
                        "",
                        "",
                        null,
                        null,
                        VmSize.NONE,
                        log));
        assertThat(snapshot.getMessage(), containsString("named snapshot"));
    }

    @Test
    void aClusterAFolderAndAResourcePoolAreIgnoredAndSaid() throws Exception {
        esxi.cloneVm("clone-01", "master", true, "Pool", "Cluster1", "", "Folder/Sub", false, "", log);

        assertThat(host.vmNamed("clone-01"), is(notNullValue()));
        String said = logBytes.toString();
        assertThat(said, containsString("The cluster \"Cluster1\" is ignored"));
        assertThat(said, containsString("The folder \"Folder/Sub\" is ignored"));
        assertThat(said, containsString("The resource pool \"Pool\" is ignored"));
    }

    @Test
    void theSizeAndExtraConfigurationAreApplied() throws Exception {
        VmSize size = VmSize.of("4", "2", "1000", "8192");
        esxi.cloneOrDeployVm(
                "clone-01",
                "master",
                true,
                "",
                "",
                "",
                "",
                true,
                "",
                false,
                Map.of("guestinfo.role", "build-agent", "tools.syncTime", "TRUE"),
                "",
                "",
                "",
                null,
                null,
                size,
                log);

        VmxFile vmx = VmxFile.parse(host.file(DS + "/clone-01/clone-01.vmx"));
        assertThat(vmx.get("numvcpus"), is("4"));
        assertThat(vmx.get("cpuid.coresPerSocket"), is("2"));
        assertThat(vmx.get("memSize"), is("8192"));
        assertThat(vmx.get("sched.cpu.min"), is("1000"));
        assertThat(vmx.get("guestinfo.role"), is("build-agent"));
        assertThat(vmx.get("tools.syncTime"), is("TRUE"));
    }

    @Test
    void extraConfigurationThatCannotBePutInAVmxFileIsRefusedAndNothingIsLeft() throws Exception {
        for (Map<String, String> bad : java.util.List.of(
                Map.of("bad key", "x"),
                Map.of("guestinfo.x", "has \"a quote\""),
                Map.of("guestinfo.y", "two\nlines"))) {
            VSphereException e = assertThrows(
                    VSphereException.class,
                    () -> esxi.cloneOrDeployVm(
                            "clone-01",
                            "master",
                            true,
                            "",
                            "",
                            "",
                            "",
                            true,
                            "",
                            false,
                            bad,
                            "",
                            "",
                            "",
                            null,
                            null,
                            VmSize.NONE,
                            log));

            assertThat(e.getMessage(), containsString("extra configuration parameter"));
            assertThat(host.hasDirectory(DS + "/clone-01"), is(false));
        }
    }

    @Test
    void severalDisksAreAllLinked() throws Exception {
        host.addFile(
                MASTER + "/master.vmx",
                MASTER_VMX + "scsi0:1.present = \"TRUE\"\nscsi0:1.fileName = \"data-000001.vmdk\"\n");
        host.addFile(MASTER + "/data.vmdk", descriptor(null, "VMFS", "data-flat.vmdk"));
        host.addFile(MASTER + "/data-flat.vmdk", "DATA BASE");
        host.addFile(MASTER + "/data-000001.vmdk", descriptor("data.vmdk", "SESPARSE", "data-000001-sesparse.vmdk"));
        host.addFile(MASTER + "/data-000001-sesparse.vmdk", "DATA DELTA");

        clone("clone-01", true, false);

        VmxFile vmx = VmxFile.parse(host.file(DS + "/clone-01/clone-01.vmx"));
        assertThat(vmx.get("scsi0:0.fileName"), is("master-000002.vmdk"));
        assertThat(vmx.get("scsi0:1.fileName"), is("data-000001.vmdk"));
        assertThat(
                host.file(DS + "/clone-01/data-000001.vmdk"),
                containsString("parentFileNameHint=\"" + REAL + "/master/data.vmdk\""));
        assertThat(host.file(DS + "/clone-01/data-000001-sesparse.vmdk"), is("DATA DELTA"));
    }
}
