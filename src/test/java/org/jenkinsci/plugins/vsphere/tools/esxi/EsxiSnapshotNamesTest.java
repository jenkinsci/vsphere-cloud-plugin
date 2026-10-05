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

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import org.jenkinsci.plugins.vsphere.tools.VSphereException;
import org.jenkinsci.plugins.vsphere.tools.VSphereNotFoundException;
import org.jenkinsci.plugins.vsphere.tools.VmSize;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** Renaming snapshots (in the .vmsd of the VM) and cloning the state a named snapshot froze. */
class EsxiSnapshotNamesTest {

    private static final String DS = "/vmfs/volumes/datastore1";
    private static final String MASTER = DS + "/master";
    private static final String VMSD_PATH = MASTER + "/master.vmsd";

    private static final String MASTER_VMX = String.join(
            "\n",
            "displayName = \"master\"",
            "scsi0.present = \"TRUE\"",
            "scsi0:0.present = \"TRUE\"",
            "scsi0:0.fileName = \"master-000002.vmdk\"",
            "");

    /** Two snapshots in a row: the first froze master.vmdk, the second froze master-000001.vmdk. */
    private static final String VMSD = String.join(
            "\n",
            ".encoding = \"UTF-8\"",
            "snapshot.lastUID = \"2\"",
            "snapshot.numSnapshots = \"2\"",
            "snapshot.current = \"2\"",
            "snapshot0.uid = \"1\"",
            "snapshot0.filename = \"master-Snapshot1.vmsn\"",
            "snapshot0.displayName = \"snap1\"",
            "snapshot0.description = \"the first\"",
            "snapshot0.disk0.fileName = \"master.vmdk\"",
            "snapshot0.disk0.node = \"scsi0:0\"",
            "snapshot0.numDisks = \"1\"",
            "snapshot1.uid = \"2\"",
            "snapshot1.parent = \"1\"",
            "snapshot1.displayName = \"snap2\"",
            "snapshot1.disk0.fileName = \"master-000001.vmdk\"",
            "snapshot1.disk0.node = \"scsi0:0\"",
            "snapshot1.numDisks = \"1\"",
            "");

    private static String descriptor(String parentHint, String extentType, String extent) {
        return "# Disk DescriptorFile\nversion=1\nparentCID=ffffffff\ncreateType=\"seSparse\"\n"
                + (parentHint == null ? "" : "parentFileNameHint=\"" + parentHint + "\"\n")
                + "\n# Extent description\nRW 100 " + extentType + " \"" + extent + "\"\n";
    }

    private FakeEsxiHost host;
    private VSphereEsxiSsh esxi;
    private final PrintStream log = new PrintStream(new ByteArrayOutputStream());

    @BeforeEach
    void setUp() throws Exception {
        host = new FakeEsxiHost();
        host.addVm(1, "master", "datastore1", "master/master.vmx", MASTER_VMX);
        host.addFile(MASTER + "/master.vmdk", descriptor(null, "VMFS", "master-flat.vmdk"));
        host.addFile(MASTER + "/master-flat.vmdk", "BASE");
        host.addFile(MASTER + "/master-000001.vmdk", descriptor("master.vmdk", "SESPARSE", "master-000001-s.vmdk"));
        host.addFile(MASTER + "/master-000001-s.vmdk", "DELTA 1");
        host.addFile(
                MASTER + "/master-000002.vmdk", descriptor("master-000001.vmdk", "SESPARSE", "master-000002-s.vmdk"));
        host.addFile(MASTER + "/master-000002-s.vmdk", "DELTA 2");
        host.addFile(VMSD_PATH, VMSD);
        esxi = new VSphereEsxiSsh(host);
        esxi.takeSnapshot("master", "snap1", "the first", false);
        esxi.takeSnapshot("master", "snap2", "", false);
    }

    private void cloneOf(String name, boolean linked, String snapshot) throws Exception {
        esxi.cloneOrDeployVm(
                name,
                "master",
                linked,
                "",
                "",
                "",
                "",
                false,
                snapshot,
                false,
                null,
                "",
                "",
                "",
                null,
                null,
                VmSize.NONE,
                log);
    }

    // -- renaming --

    @Test
    void renamesASnapshot() throws Exception {
        assertThat(esxi.renameVmSnapshot("master", "snap1", "first \"one\"", "new text", true), is(true));

        assertThat(host.file(VMSD_PATH), containsString("snapshot0.displayName = \"first |22one|22\""));
        assertThat(host.file(VMSD_PATH), containsString("snapshot0.description = \"new text\""));
        assertThat(host.file(VMSD_PATH), containsString("snapshot1.displayName = \"snap2\"")); // not touched
        assertThat(esxi.getSnapshotInTree(esxi.getVmByName("master"), "first \"one\""), is(notNullValue()));
        assertThat(esxi.getSnapshotInTree(esxi.getVmByName("master"), "snap1"), is(nullValue()));
        assertThat(host.ran("/bin/vim-cmd vmsvc/reload 1"), is(true));
    }

    @Test
    void aRunningVmsSnapshotsAreNotRenamed() {
        host.vm(1).power = "Powered on";

        final IllegalStateException e =
                assertThrows(IllegalStateException.class, () -> esxi.renameVmSnapshot("master", "snap1", "first", "", true));
        assertThat(e.getMessage(), containsString("powered off"));
        assertThat(host.file(VMSD_PATH), is(VMSD));
    }

    @Test
    void whenTheHostDoesNotTakeTheNewNameTheFileIsPutBack() {
        host.ignoreVmsd = true;

        assertThrows(Exception.class, () -> esxi.renameVmSnapshot("master", "snap1", "first", "", true));

        assertThat(host.file(VMSD_PATH), is(VMSD));
    }

    @Test
    void aNameWithControlCharactersIsRefused() {
        assertThrows(Exception.class, () -> esxi.renameVmSnapshot("master", "snap1", "bad\u0000name", "", true));
        assertThat(host.file(VMSD_PATH), is(VMSD));
    }

    @Test
    void renamingASnapshotThatIsNotThereIsNotFound() throws Exception {
        assertThrows(VSphereNotFoundException.class, () -> esxi.renameVmSnapshot("master", "nope", "x", "", true));
        assertThat(esxi.renameVmSnapshot("master", "nope", "x", "", false), is(false));
    }

    // -- cloning by name --

    @Test
    void aFullCloneOfTheFirstSnapshotIsACopyOfTheDiskItFroze() throws Exception {
        cloneOf("from-1", false, "snap1");

        assertThat(host.file(DS + "/from-1/from-1-flat.vmdk"), is("COPY OF " + MASTER + "/master.vmdk"));
        assertThat(VmxFile.parse(host.file(DS + "/from-1/from-1.vmx")).get("scsi0:0.fileName"), is("from-1.vmdk"));
    }

    @Test
    void aFullCloneOfTheSecondSnapshotIsACopyOfTheDiskThatOneFroze() throws Exception {
        cloneOf("from-2", false, "snap2");

        assertThat(host.file(DS + "/from-2/from-2-flat.vmdk"), is("COPY OF " + MASTER + "/master-000001.vmdk"));
    }

    @Test
    void aLinkedCloneOfTheNewestSnapshotIsMadeOfTheDiskAfterIt() throws Exception {
        cloneOf("linked", true, "snap2");

        assertThat(host.file(DS + "/linked/master-000002-s.vmdk"), is("DELTA 2"));
        assertThat(host.file(DS + "/linked/master-000002.vmdk"), containsString("master-000001.vmdk"));
    }

    @Test
    void aLinkedCloneOfAnOlderSnapshotIsRefusedAndTellsToMakeAFullOne() {
        final VSphereException e = assertThrows(VSphereException.class, () -> cloneOf("linked", true, "snap1"));

        assertThat(e.getMessage(), containsString("only be made of the newest snapshot"));
        assertThat(e.getMessage(), containsString("full clone"));
        assertThat(host.hasDirectory(DS + "/linked"), is(false));
    }

    @Test
    void aSnapshotThatIsNotThereIsNotFoundAndNothingIsMade() {
        assertThrows(VSphereNotFoundException.class, () -> cloneOf("c", false, "nope"));
        assertThat(host.hasDirectory(DS + "/c"), is(false));
    }

    @Test
    void withoutTheMetadataOfSnapshotsACloneByNameIsRefused() {
        host.files.remove(VMSD_PATH);

        final VSphereException e = assertThrows(VSphereException.class, () -> cloneOf("c", false, "snap1"));

        assertThat(e.getMessage(), containsString("master.vmsd is not there"));
        assertThat(host.hasDirectory(DS + "/c"), is(false));
    }

    @Test
    void withNoSnapshotNamedTheNewestOneIsUsedAsBefore() throws Exception {
        cloneOf("plain", true, "");

        assertThat(host.file(DS + "/plain/master-000002-s.vmdk"), is("DELTA 2"));
    }
}
