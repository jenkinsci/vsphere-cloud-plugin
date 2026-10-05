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
import static org.hamcrest.Matchers.startsWith;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.jenkinsci.plugins.vsphere.tools.VSphereException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** A replica of a master on a host that does not see its files: what it is made of, when it is reused, what is left. */
class EsxiReplicaTest {

    private static final String MASTER_DIR = "/vmfs/volumes/ds1/master";
    private static final String MASTER_VMX = String.join(
            "\n",
            "displayName = \"master\"",
            "numvcpus = \"2\"",
            "memSize = \"2048\"",
            "nvram = \"master.nvram\"",
            "scsi0.present = \"TRUE\"",
            "scsi0:0.present = \"TRUE\"",
            "scsi0:0.fileName = \"master.vmdk\"",
            "uuid.bios = \"42 1a\"",
            "ethernet0.present = \"TRUE\"",
            "ethernet0.generatedAddress = \"00:0c:29:aa:bb:cc\"",
            "");

    private FakeEsxiHost hostA;
    private FakeEsxiHost hostB;
    private VSphereEsxiSsh source;
    private VSphereEsxiSsh target;
    private VmEntry master;
    private final ByteArrayOutputStream said = new ByteArrayOutputStream();
    private final PrintStream log = new PrintStream(said);

    private static String descriptor(String cid) {
        return "# Disk DescriptorFile\nversion=1\nCID=" + cid + "\nparentCID=ffffffff\ncreateType=\"vmfs\"\n\n"
                + "# Extent description\nRW 100 VMFS \"master-flat.vmdk\"\n";
    }

    @BeforeEach
    void setUp() throws Exception {
        hostA = new FakeEsxiHost();
        hostA.datastoreTable = FakeEsxiHost.datastoreTable(new String[][] {
            {"/vmfs/volumes/uuid-ds1", "ds1", "uuid-ds1", "true", "VMFS-6", "1000000000", "900000000"}
        });
        hostA.datastoreUuids.put("ds1", "uuid-ds1");
        hostA.addVm(1, "master", "ds1", "master/master.vmx", MASTER_VMX);
        hostA.addFile(MASTER_DIR + "/master.vmdk", descriptor("aaaa1111"));
        hostA.addFile(MASTER_DIR + "/master-flat.vmdk", "BIGDATA");
        hostA.addFile(
                MASTER_DIR + "/master.vmsd",
                String.join(
                        "\n",
                        "snapshot.current = \"1\"",
                        "snapshot0.uid = \"1\"",
                        "snapshot0.disk0.fileName = \"master.vmdk\"",
                        "snapshot0.disk0.node = \"scsi0:0\"",
                        ""));
        hostB = new FakeEsxiHost();
        source = new VSphereEsxiSsh(hostA);
        target = new VSphereEsxiSsh(hostB);
        master = ((EsxiVirtualMachine) source.getVmByName("master")).getEntry();
    }

    private EsxiVirtualMachine replicate(String snapshotUid, String datastore) throws Exception {
        return EsxiReplica.ensure(source, master, snapshotUid, target, datastore, EsxiRelay.Compression.PIGZ, 30, log);
    }

    private static String dirOf(EsxiVirtualMachine replica, String datastore) {
        return "/vmfs/volumes/" + datastore + "/" + replica.getName();
    }

    private boolean leftOver(FakeEsxiHost host, String marker) {
        return host.files.keySet().stream().anyMatch(f -> f.contains(marker))
                || host.directories.stream().anyMatch(d -> d.contains(marker));
    }

    @Test
    void aReplicaIsAVmOfItsOwnWithTheDisksOfTheSnapshotAndOneSnapshot() throws Exception {
        final EsxiVirtualMachine replica = replicate("1", "datastore1");

        final String name = replica.getName();
        assertThat(name, startsWith("jenkins-replica-master-"));
        final String dir = dirOf(replica, "datastore1");
        // the data came through the export, the copy and the import
        assertThat(hostB.file(dir + "/" + name + "_0-flat.vmdk"), is("BIGDATA"));
        // the VM is the master's, with the disks it has made and no identity of the master's
        final VmxFile vmx = VmxFile.parse(hostB.file(dir + "/" + name + ".vmx"));
        assertThat(vmx.get("displayName"), is(name));
        assertThat(vmx.get("scsi0:0.fileName"), is(name + "_0.vmdk"));
        assertThat(vmx.get("numvcpus"), is("2"));
        assertThat(vmx.get("uuid.bios"), is((String) null));
        assertThat(vmx.get("ethernet0.generatedAddress"), is((String) null));
        assertThat(vmx.get(EsxiReplica.KEY_SOURCE), is("uuid-ds1:master/master.vmx"));
        assertThat(vmx.get(EsxiReplica.KEY_STATE), is("snapshot-1"));
        assertThat(vmx.get(EsxiReplica.KEY_STAMP), is("scsi0:0=aaaa1111;"));
        // and it is registered, with the snapshot that clones are linked to
        assertThat(hostB.vmNamed(name), is(org.hamcrest.Matchers.notNullValue()));
        assertThat(hostB.vmNamed(name).snapshots.size(), is(1));
        assertThat(hostB.vmNamed(name).snapshots.get(0)[0], is(EsxiReplica.BASE_SNAPSHOT));
        assertThat(hostB.vmNamed(name).power, is("Powered off"));
    }

    @Test
    void nothingIsLeftOfTheCopyAndTheMasterIsNotTouched() throws Exception {
        replicate("1", "datastore1");

        assertThat(leftOver(hostA, ".jenkins-export-"), is(false));
        assertThat(leftOver(hostB, "/export"), is(false));
        assertThat(leftOver(hostB, ".jenkins-incoming-"), is(false));
        assertThat(hostA.file(MASTER_DIR + "/master.vmdk"), is(descriptor("aaaa1111")));
        assertThat(hostA.file(MASTER_VMX_PATH()), is(MASTER_VMX));
    }

    private static String MASTER_VMX_PATH() {
        return MASTER_DIR + "/master.vmx";
    }

    @Test
    void theCopyIsMadeThroughTheRelayAndIsCheckedByChecksum() throws Exception {
        replicate("1", "datastore1");

        assertThat(hostA.commands.stream().anyMatch(c -> c.contains("-d 2gbsparse")), is(true));
        assertThat(hostA.commands.stream().anyMatch(c -> c.startsWith("cksum ")), is(true));
        assertThat(hostB.commands.stream().anyMatch(c -> c.startsWith("cksum ")), is(true));
        assertThat(hostB.commands.stream().anyMatch(c -> c.contains("-d thin")), is(true));
        assertThat(said.toString(), containsString("Making the replica jenkins-replica-master-"));
        assertThat(said.toString(), containsString("is ready on the ESXi host"));
    }

    @Test
    void aReplicaThatIsThereIsUsedAgain() throws Exception {
        final EsxiVirtualMachine first = replicate("1", "datastore1");
        final int commands = hostA.commands.size();

        final EsxiVirtualMachine second = replicate("1", "datastore1");

        assertThat(second.getName(), is(first.getName()));
        assertThat(second.getVmId(), is(first.getVmId()));
        // the master was looked at (for its disks' CIDs), and nothing was made
        assertThat(hostA.commands.stream().skip(commands).anyMatch(c -> c.startsWith("vmkfstools")), is(false));
        assertThat(said.toString(), containsString("Using the replica " + first.getName()));
    }

    @Test
    void aMasterThatHasChangedGetsANewReplicaAndTheOldOneStays() throws Exception {
        final EsxiVirtualMachine first = replicate("1", "datastore1");
        hostA.addFile(MASTER_DIR + "/master.vmdk", descriptor("bbbb2222"));

        final EsxiVirtualMachine second = replicate("1", "datastore1");

        assertThat(second.getName(), not(is(first.getName())));
        assertThat(hostB.vmNamed(first.getName()), is(org.hamcrest.Matchers.notNullValue()));
        assertThat(
                VmxFile.parse(hostB.file(dirOf(second, "datastore1") + "/" + second.getName() + ".vmx"))
                        .get(EsxiReplica.KEY_STAMP),
                is("scsi0:0=bbbb2222;"));
    }

    @Test
    void theStateTheMasterHasNowIsAnotherReplicaThanThatOfASnapshot() throws Exception {
        final EsxiVirtualMachine ofSnapshot = replicate("1", "datastore1");

        final EsxiVirtualMachine now = replicate(null, "datastore1");

        assertThat(now.getName(), not(is(ofSnapshot.getName())));
        assertThat(
                VmxFile.parse(hostB.file(dirOf(now, "datastore1") + "/" + now.getName() + ".vmx"))
                        .get(EsxiReplica.KEY_STATE),
                is("now"));
    }

    @Test
    void withNoDatastoreTheOneWithTheMostRoomIsUsed() throws Exception {
        final EsxiVirtualMachine replica = replicate("1", null);

        // of the datastores that the fake has, "nfs-share" has the most free
        assertThat(hostB.hasFile(dirOf(replica, "nfs-share") + "/" + replica.getName() + ".vmx"), is(true));
    }

    @Test
    void aSnapshotThatIsNotThereIsRefused() {
        final VSphereException e = assertThrows(VSphereException.class, () -> replicate("9", "datastore1"));

        assertThat(e.getMessage(), containsString("The snapshot 9 is not in the snapshots of"));
        assertThat(hostB.vmNamed("master"), is((FakeEsxiHost.FakeVm) null));
        assertThat(leftOver(hostB, "jenkins-replica"), is(false));
    }

    @Test
    void aFailureOfTheImportLeavesNothingOnEitherHost() {
        hostB.failing("-d thin", "No space left on device");

        final VSphereException e = assertThrows(VSphereException.class, () -> replicate("1", "datastore1"));

        assertThat(e.getMessage(), containsString("No space left on device"));
        assertThat(leftOver(hostB, "jenkins-replica"), is(false));
        assertThat(leftOver(hostA, ".jenkins-export-"), is(false));
        assertThat(said.toString(), containsString("Removed what was made of the replica"));
    }

    @Test
    void aFailureOfTheExportLeavesNothingEither() {
        hostA.failing("-d 2gbsparse", "Failed to open disk");

        assertThrows(VSphereException.class, () -> replicate("1", "datastore1"));

        assertThat(leftOver(hostB, "jenkins-replica"), is(false));
        assertThat(leftOver(hostA, ".jenkins-export-"), is(false));
    }

    @Test
    void aCopyThatIsNotWhatWasSentIsNotMadeIntoAReplica() throws Exception {
        // b says, of what it was sent, something else than what a had
        final EsxiShell lying = new EsxiShell() {
            @Override
            public ShellResult run(String command) throws VSphereException {
                final ShellResult result = hostB.run(command);
                return command.startsWith("cksum ") && command.contains("/export/")
                        ? new ShellResult(0, result.getStdout().replaceFirst("^\\d+", "1"), "")
                        : result;
            }

            @Override
            public ShellResult stream(
                    String command, java.io.InputStream stdin, java.io.OutputStream stdout, int idleTimeoutSeconds)
                    throws VSphereException {
                return hostB.stream(command, stdin, stdout, idleTimeoutSeconds);
            }

            @Override
            public void close() {}
        };
        final VSphereEsxiSsh lyingTarget = new VSphereEsxiSsh(lying);

        final VSphereException e = assertThrows(
                VSphereException.class,
                () -> EsxiReplica.ensure(
                        source, master, "1", lyingTarget, "datastore1", EsxiRelay.Compression.GZIP, 30, log));

        assertThat(e.getMessage(), containsString("has the checksum and size"));
        assertThat(leftOver(hostB, "jenkins-replica"), is(false));
    }

    @Test
    void twoBuildsAtOnceMakeOneReplica() throws Exception {
        final ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            final Callable<EsxiVirtualMachine> make = () -> replicate("1", "datastore1");
            final Future<EsxiVirtualMachine> one = pool.submit(make);
            final Future<EsxiVirtualMachine> other = pool.submit(make);

            assertThat(one.get().getName(), is(other.get().getName()));
        } finally {
            pool.shutdownNow();
        }

        assertThat(
                hostB.commands.stream()
                        .filter(c -> c.contains("solo/registervm"))
                        .count(),
                is(1L));
    }

    @Test
    void theNameHasADigestOfWhereItIsFromAndWhatTheDisksAre() {
        final String a = EsxiReplica.replicaName("master", "uuid:master/master.vmx", "snapshot-1", "scsi0:0=aaaa;");

        assertThat(a, is(EsxiReplica.replicaName("master", "uuid:master/master.vmx", "snapshot-1", "scsi0:0=aaaa;")));
        assertThat(
                a, not(is(EsxiReplica.replicaName("master", "uuid:master/master.vmx", "snapshot-1", "scsi0:0=bbbb;"))));
        assertThat(
                a, not(is(EsxiReplica.replicaName("master", "uuid:other/master.vmx", "snapshot-1", "scsi0:0=aaaa;"))));
        // a name that cannot be a folder of a datastore is made one
        assertThat(
                EsxiReplica.replicaName("a/b 'c'", "k", "now", "s").matches("jenkins-replica-[A-Za-z0-9._-]+"),
                is(true));
    }
}
