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
import java.util.List;
import java.util.Set;
import java.util.function.Supplier;
import org.jenkinsci.plugins.vsphere.tools.VSphereDuplicateException;
import org.jenkinsci.plugins.vsphere.tools.VSphereException;
import org.jenkinsci.plugins.vsphere.tools.VSphereNotFoundException;
import org.jenkinsci.plugins.vsphere.tools.VmSize;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Several ESXi hosts that share a datastore, used as one: where VMs are found, where clones are made, what happens
 * when a host is down, and the guard for the disks that linked clones are made of.
 */
class EsxiClusterTest {

    private static final String SHARED = "/vmfs/volumes/shared";
    private static final String REAL = "/vmfs/volumes/uuid-shared";
    private static final String MASTER = SHARED + "/master";

    private static final String MASTER_VMX = String.join(
            "\n",
            "displayName = \"master\"",
            "scsi0.present = \"TRUE\"",
            "scsi0:0.present = \"TRUE\"",
            "scsi0:0.fileName = \"master-000001.vmdk\"",
            "");

    private FakeEsxiHost hostA;
    private FakeEsxiHost hostB;
    private final PrintStream log = new PrintStream(new ByteArrayOutputStream());
    private boolean bIsUp = true;

    private static String descriptor(String parentHint, String extent) {
        return "# Disk DescriptorFile\nversion=1\nparentCID=ffffffff\ncreateType=\"seSparse\"\n"
                + (parentHint == null ? "" : "parentFileNameHint=\"" + parentHint + "\"\n")
                + "\n# Extent description\nRW 100 SESPARSE \"" + extent + "\"\n";
    }

    private static String[][] tableWith(String... localNames) {
        final String[][] rows = new String[1 + localNames.length][];
        rows[0] = new String[] {REAL, "shared", "uuid-shared", "true", "NFS", "1000000000", "900000000"};
        for (int i = 0; i < localNames.length; i++) {
            rows[i + 1] = new String[] {
                "/vmfs/volumes/uuid-" + localNames[i],
                localNames[i],
                "uuid-" + localNames[i],
                "true",
                "VMFS-6",
                "1000000000",
                "900000000"
            };
        }
        return rows;
    }

    @BeforeEach
    void setUp() {
        hostA = new FakeEsxiHost();
        hostA.hostname = "esxia";
        hostB = new FakeEsxiHost();
        hostB.hostname = "esxib";
        hostB.shareStorageWith(hostA);
        hostA.datastoreUuids.put("shared", "uuid-shared");
        hostA.datastoreUuids.put("localB", "uuid-localB");
        hostA.datastoreTable = FakeEsxiHost.datastoreTable(tableWith());
        hostB.datastoreTable = FakeEsxiHost.datastoreTable(tableWith("localB"));

        hostA.addVm(1, "master", "shared", "master/master.vmx", MASTER_VMX);
        hostA.addFile(MASTER + "/master.vmdk", descriptor(null, "master-sesparse.vmdk"));
        hostA.addFile(MASTER + "/master-sesparse.vmdk", "BASE");
        hostA.addFile(MASTER + "/master-000001.vmdk", descriptor("master.vmdk", "master-000001-sesparse.vmdk"));
        hostA.addFile(MASTER + "/master-000001-sesparse.vmdk", "HEAD");
        hostA.vm(1).snapshots.add(new String[] {"snap", "", "0", "0", "1"});
        hostB.addVm(2, "on-b", "shared", "on-b/on-b.vmx", "displayName = \"on-b\"\n");
    }

    private VSphereEsxiCluster.Connector connector(String label, Supplier<VSphereEsxiSsh> session) {
        return new VSphereEsxiCluster.Connector() {
            @Override
            public String label() {
                return label;
            }

            @Override
            public VSphereEsxiSsh connect() throws VSphereException {
                final VSphereEsxiSsh made = session.get();
                if (made == null) {
                    throw new VSphereException("Connection refused");
                }
                return made;
            }
        };
    }

    private VSphereEsxiCluster cluster() throws Exception {
        return new VSphereEsxiCluster(List.of(
                connector("a.example", () -> new VSphereEsxiSsh(hostA)),
                connector("b.example", () -> bIsUp ? new VSphereEsxiSsh(hostB) : null)));
    }

    private void clone(VSphereEsxiCluster cluster, String name, boolean linked, String host, String mode)
            throws Exception {
        cluster.cloneOrDeployVm(
                name,
                "master",
                linked,
                "",
                "",
                "",
                "",
                false,
                null,
                false,
                null,
                "",
                host,
                mode,
                null,
                null,
                VmSize.NONE,
                log);
    }

    // -- looking VMs up --

    @Test
    void findsVmsOnAllTheHosts() throws Exception {
        final VSphereEsxiCluster cluster = cluster();

        assertThat(cluster.getVmByName("master").getName(), is("master"));
        assertThat(cluster.getVmByName("on-b").getName(), is("on-b"));
        assertThat(((EsxiVirtualMachine) cluster.getVmByName("on-b")).getHost().getLabel(), is("b.example"));
        assertThat(cluster.getVmByName("nope"), is(nullValue()));
        assertThat(cluster.countVms(), is(2));
        assertThat(cluster.countVmsByPrefix("on-"), is(1));
        assertThat(cluster.hostExists("esxib"), is(true));
        assertThat(cluster.hostExists("b.example"), is(true));
        assertThat(cluster.hostExists("esxic"), is(false));
    }

    @Test
    void whatIsDoneToAVmIsDoneByItsHost() throws Exception {
        final VSphereEsxiCluster cluster = cluster();

        cluster.takeSnapshot("on-b", "s", "", false);

        assertThat(hostB.vm(2).snapshots.size(), is(1));
        assertThat(hostA.commands.stream().anyMatch(c -> c.contains("snapshot.create")), is(false));
    }

    @Test
    void theDatastoresOfAllTheHostsAreListedOnceByName() throws Exception {
        final List<String> names = java.util.Arrays.stream(cluster().getDatastores())
                .map(d -> d.getName())
                .collect(java.util.stream.Collectors.toList());

        assertThat(names, is(List.of("shared", "localB")));
    }

    // -- hosts that are down --

    @Test
    void aHostThatIsDownIsLeftOutAndTriedAgainLater() throws Exception {
        bIsUp = false;
        final VSphereEsxiCluster cluster = cluster();

        assertThat(cluster.getVmByName("on-b"), is(nullValue()));
        assertThat(cluster.getVmByName("master"), is(notNullValue()));
        assertThat(cluster.isSessionAlive(), is(true));

        bIsUp = true;
        cluster.retryNow();

        assertThat(cluster.getVmByName("on-b"), is(notNullValue()));
    }

    @Test
    void aHostThatDiesOnTheWayIsDroppedAndTheOthersGoOn() throws Exception {
        final VSphereEsxiCluster cluster = cluster();
        hostB.closed = true;

        assertThat(cluster.getVmByName("master"), is(notNullValue()));
        assertThat(cluster.getVmByName("on-b"), is(nullValue()));
        assertThat(cluster.isSessionAlive(), is(true));
        assertThat(cluster.availableMembers().size(), is(1));
    }

    @Test
    void withNoHostUpThereIsNoCluster() {
        bIsUp = false;

        final VSphereException e = assertThrows(
                VSphereException.class,
                () -> new VSphereEsxiCluster(
                        List.of(connector("a.example", () -> null), connector("b.example", () -> null))));

        assertThat(e.getMessage(), containsString("None of the ESXi hosts could be reached"));
        assertThat(e.getMessage(), containsString("Connection refused"));
    }

    @Test
    void closingTheClusterClosesAllTheSessions() throws Exception {
        final VSphereEsxiCluster cluster = cluster();

        cluster.disconnect();

        assertThat(hostA.closed, is(true));
        assertThat(hostB.closed, is(true));
        assertThat(cluster.isSessionAlive(), is(false));
    }

    // -- clones --

    @Test
    void aCloneGoesToTheHostThatHasFewerVmsOn() throws Exception {
        hostA.addVm(3, "busy", "shared", "busy/busy.vmx", "displayName = \"busy\"\n").power = "Powered on";

        clone(cluster(), "lc", true, null, null);

        assertThat(hostB.vmNamed("lc"), is(notNullValue()));
        assertThat(hostA.vmNamed("lc"), is(nullValue()));
        // its files are on the datastore that the hosts share, and it is a change of the disk of the master
        assertThat(
                hostA.file(SHARED + "/lc/master-000001.vmdk"),
                containsString("parentFileNameHint=\"" + REAL + "/master/master.vmdk\""));
        assertThat(hostA.file(SHARED + "/lc/lc.vmx"), containsString("displayName = \"lc\""));
    }

    @Test
    void aCloneGoesToTheMastersHostWhenTheHostsAreEquallyBusy() throws Exception {
        // a has one VM registered, b has one: the first as configured is the tie
        clone(cluster(), "lc", true, null, null);

        assertThat(hostA.vmNamed("lc"), is(notNullValue()));
    }

    @Test
    void aHostCanBeAskedFor() throws Exception {
        hostB.addVm(4, "busy", "shared", "busy/busy.vmx", "displayName = \"busy\"\n").power = "Powered on";

        clone(cluster(), "lc", false, "esxib", null);

        assertThat(hostB.vmNamed("lc"), is(notNullValue()));
        assertThat(hostA.vmNamed("lc"), is(nullValue()));
        // a full copy of a disk of the master that the other host reads through the shared datastore
        assertThat(
                hostB.commands.stream()
                        .anyMatch(c -> c.startsWith("vmkfstools -i '" + MASTER + "/master-000001.vmdk'")),
                is(true));
    }

    @Test
    void aHostThatCannotBeUsedIsNotUsedEvenIfAsked() {
        bIsUp = false;

        final VSphereException e =
                assertThrows(VSphereException.class, () -> clone(clusterOrFail(), "lc", true, "esxib", null));

        assertThat(e.getMessage(), containsString("cannot be used for the clone"));
    }

    private VSphereEsxiCluster clusterOrFail() {
        try {
            return cluster();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    @Test
    void aHostThatDoesNotSeeTheFilesOfTheMasterIsLeftOut() throws Exception {
        final FakeEsxiHost lonely = new FakeEsxiHost();
        lonely.hostname = "esxic";
        final VSphereEsxiCluster cluster = new VSphereEsxiCluster(List.of(
                connector("a.example", () -> new VSphereEsxiSsh(hostA)),
                connector("c.example", () -> new VSphereEsxiSsh(lonely))));

        clone(cluster, "lc", true, null, null);

        assertThat(hostA.vmNamed("lc"), is(notNullValue()));
        assertThat(lonely.vmNamed("lc"), is(nullValue()));
        assertThat(
                assertThrows(VSphereException.class, () -> clone(cluster, "lc2", true, "esxic", null))
                        .getMessage(),
                containsString("cannot be used for the clone"));
    }

    @Test
    void thePoolOfCandidatesCanBeNarrowedAndSelectionSwitchedOff() throws Exception {
        hostA.addVm(3, "busy", "shared", "busy/busy.vmx", "displayName = \"busy\"\n").power = "Powered on";
        final VSphereEsxiCluster cluster = cluster();

        // "NONE" keeps it with the master, though b has less to do
        clone(cluster, "stays", true, null, "NONE");
        assertThat(hostA.vmNamed("stays"), is(notNullValue()));

        // and a list of hosts that may be used is kept to
        cluster.cloneOrDeployVm(
                "listed",
                "master",
                true,
                "",
                "",
                "",
                "",
                false,
                null,
                false,
                null,
                "",
                null,
                "LEAST_LOADED",
                Set.of("esxia"),
                null,
                VmSize.NONE,
                log);
        assertThat(hostA.vmNamed("listed"), is(notNullValue()));
    }

    @Test
    void aNameThatIsTakenOnAnyHostIsADuplicate() throws Exception {
        final VSphereEsxiCluster cluster = cluster();

        assertThrows(VSphereDuplicateException.class, () -> clone(cluster, "on-b", true, null, null));
        assertThrows(
                VSphereNotFoundException.class,
                () -> cluster.cloneOrDeployVm(
                        "x",
                        "nope",
                        true,
                        "",
                        "",
                        "",
                        "",
                        false,
                        null,
                        false,
                        null,
                        "",
                        null,
                        null,
                        null,
                        null,
                        VmSize.NONE,
                        log));
    }

    @Test
    void aMasterOnTheOtherHostCanBeSnapshotNamedToo() throws Exception {
        hostA.addFile(
                MASTER + "/master.vmsd",
                String.join(
                        "\n",
                        "snapshot.current = \"1\"",
                        "snapshot0.uid = \"1\"",
                        "snapshot0.displayName = \"snap\"",
                        "snapshot0.disk0.fileName = \"master.vmdk\"",
                        "snapshot0.disk0.node = \"scsi0:0\"",
                        ""));

        cluster()
                .cloneOrDeployVm(
                        "named",
                        "master",
                        false,
                        "",
                        "",
                        "",
                        "",
                        false,
                        "snap",
                        false,
                        null,
                        "",
                        "esxib",
                        null,
                        null,
                        null,
                        VmSize.NONE,
                        log);

        assertThat(hostB.vmNamed("named"), is(notNullValue()));
        assertThat(hostA.file(SHARED + "/named/named-flat.vmdk"), is("COPY OF " + MASTER + "/master.vmdk"));
    }
}
