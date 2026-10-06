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
import org.jenkinsci.plugins.vsphere.tools.VSphereException;
import org.jenkinsci.plugins.vsphere.tools.VmSize;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** Hosts that do not share a datastore: a clone on one that does not see the master is made of a replica. */
class EsxiClusterReplicaTest {

    private static final String MASTER_DIR = "/vmfs/volumes/ds1/master";
    private static final String MASTER_VMX = String.join(
            "\n",
            "displayName = \"master\"",
            "scsi0.present = \"TRUE\"",
            "scsi0:0.present = \"TRUE\"",
            "scsi0:0.fileName = \"master.vmdk\"",
            "");

    private FakeEsxiHost hostA;
    private FakeEsxiHost hostB;
    private final ByteArrayOutputStream said = new ByteArrayOutputStream();
    private final PrintStream log = new PrintStream(said);

    @BeforeEach
    void setUp() {
        hostA = new FakeEsxiHost();
        hostA.hostname = "esxia";
        hostA.datastoreUuids.put("ds1", "uuid-ds1");
        hostA.datastoreTable = FakeEsxiHost.datastoreTable(new String[][] {
            {"/vmfs/volumes/uuid-ds1", "ds1", "uuid-ds1", "true", "VMFS-6", "1000000000", "900000000"}
        });
        hostA.addVm(1, "master", "ds1", "master/master.vmx", MASTER_VMX);
        hostA.addFile(
                MASTER_DIR + "/master.vmdk",
                "# Disk DescriptorFile\nversion=1\nCID=aaaa1111\nparentCID=ffffffff\ncreateType=\"vmfs\"\n\n"
                        + "# Extent description\nRW 100 VMFS \"master-flat.vmdk\"\n");
        hostA.addFile(MASTER_DIR + "/master-flat.vmdk", "BIGDATA");
        hostA.addFile(
                MASTER_DIR + "/master.vmsd",
                String.join(
                        "\n",
                        "snapshot.current = \"1\"",
                        "snapshot0.uid = \"1\"",
                        "snapshot0.displayName = \"snap\"",
                        "snapshot0.disk0.fileName = \"master.vmdk\"",
                        "snapshot0.disk0.node = \"scsi0:0\"",
                        ""));
        hostA.vm(1).snapshots.add(new String[] {"snap", "", "0", "0", "1"});
        hostB = new FakeEsxiHost();
        hostB.hostname = "esxib";
        hostB.realSnapshots = true;
        hostB.datastoreTable = FakeEsxiHost.datastoreTable(new String[][] {
            {"/vmfs/volumes/uuid-b1", "local-b", "uuid-b1", "true", "VMFS-6", "1000000000", "900000000"}
        });
        hostB.datastoreUuids.put("local-b", "uuid-b1");
    }

    private VSphereEsxiCluster cluster(boolean replicate) throws Exception {
        return new VSphereEsxiCluster(
                List.of(connector("a.example", hostA), connector("b.example", hostB)),
                new VSphereEsxiCluster.Options(replicate, EsxiRelay.Compression.PIGZ, 30));
    }

    private static VSphereEsxiCluster.Connector connector(String label, FakeEsxiHost host) {
        return new VSphereEsxiCluster.Connector() {
            @Override
            public String label() {
                return label;
            }

            @Override
            public VSphereEsxiSsh connect() {
                return new VSphereEsxiSsh(host);
            }
        };
    }

    private void clone(
            VSphereEsxiCluster cluster, String name, boolean linked, String host, boolean useCurrent, String snapshot)
            throws Exception {
        cluster.cloneOrDeployVm(
                name,
                "master",
                linked,
                "",
                "",
                "",
                "",
                useCurrent,
                snapshot,
                false,
                null,
                "",
                host,
                null,
                null,
                null,
                VmSize.NONE,
                log);
    }

    private long replicasOnB() {
        return hostB.files.keySet().stream()
                .filter(f -> f.endsWith(".vmx") && f.contains("/jenkins-replica-"))
                .count();
    }

    @Test
    void aLinkedCloneOnAHostThatDoesNotSeeTheMasterIsMadeOfAReplica() throws Exception {
        clone(cluster(true), "lc", true, "esxib", true, null);

        assertThat(hostB.vmNamed("lc"), is(notNullValue()));
        assertThat(hostA.vmNamed("lc"), is(nullValue()));
        assertThat(replicasOnB(), is(1L));
        final String replicaDir = hostB.files.keySet().stream()
                .filter(f -> f.endsWith(".vmx") && f.contains("/jenkins-replica-"))
                .findFirst()
                .get()
                .replaceAll("/[^/]+$", "");
        // it is a change of the replica's disk, in the folder of the replica's datastore
        final String descriptor = hostB.files.entrySet().stream()
                .filter(e -> e.getKey().startsWith("/vmfs/volumes/local-b/lc/")
                        && e.getKey().endsWith(".vmdk"))
                .map(java.util.Map.Entry::getValue)
                .filter(v -> v.contains("parentFileNameHint"))
                .findFirst()
                .get();
        assertThat(descriptor, containsString("parentFileNameHint=\"" + replicaDir.replace("local-b", "uuid-b1")));
        assertThat(said.toString(), containsString(", from a replica"));
        assertThat(said.toString(), containsString("Making the replica jenkins-replica-master-"));
    }

    @Test
    void theFilesOfAReplicaGoTheWayThatTheOptionsSay() throws Exception {
        final java.util.concurrent.atomic.AtomicInteger moves = new java.util.concurrent.atomic.AtomicInteger();
        final EsxiRelay.Mover counting = new EsxiRelay.Mover() {
            @Override
            public String describe() {
                return "by a test";
            }

            @Override
            public void move(EsxiRelay.Job job) throws VSphereException {
                moves.incrementAndGet();
                EsxiRelay.RELAY.move(job);
            }
        };
        final VSphereEsxiCluster cluster = new VSphereEsxiCluster(
                List.of(connector("a.example", hostA), connector("b.example", hostB)),
                new VSphereEsxiCluster.Options(true, EsxiRelay.Compression.PIGZ, 30, counting));

        clone(cluster, "lc", true, "esxib", true, null);

        assertThat(moves.get() > 0, is(true));
        assertThat(said.toString(), containsString(" by a test, compressed with pigz"));
        assertThat(replicasOnB(), is(1L));
    }

    @Test
    void aFullCloneIsMadeOfTheReplicaToo() throws Exception {
        clone(cluster(true), "full", false, "esxib", true, null);

        assertThat(hostB.vmNamed("full"), is(notNullValue()));
        assertThat(
                hostB.commands.stream()
                        .anyMatch(c -> c.startsWith("vmkfstools -i '/vmfs/volumes/local-b/jenkins-replica-master-")
                                && c.endsWith("-d thin")),
                is(true));
    }

    @Test
    void theReplicaIsMadeOnceAndUsedForTheNextClones() throws Exception {
        final VSphereEsxiCluster cluster = cluster(true);

        clone(cluster, "one", true, "esxib", true, null);
        final long imports = hostB.commands.stream()
                .filter(c -> c.contains("-d thin") && c.contains("/export/"))
                .count();
        clone(cluster, "two", true, "esxib", true, null);

        assertThat(replicasOnB(), is(1L));
        assertThat(
                hostB.commands.stream()
                        .filter(c -> c.contains("-d thin") && c.contains("/export/"))
                        .count(),
                is(imports));
        assertThat(hostB.vmNamed("two"), is(notNullValue()));
        assertThat(said.toString(), containsString("Using the replica jenkins-replica-master-"));
    }

    @Test
    void withoutTheSettingNoReplicaIsMadeAndTheMessageSaysSo() throws Exception {
        final VSphereException e =
                assertThrows(VSphereException.class, () -> clone(cluster(false), "lc", true, "esxib", true, null));

        assertThat(e.getMessage(), containsString("cannot be used for the clone"));
        assertThat(e.getMessage(), containsString("replicas of masters are not made"));
        assertThat(replicasOnB(), is(0L));
    }

    @Test
    void whenTheHostsAreBalancedAReplicaIsMadeWhereTheClonesGo() throws Exception {
        hostA.addVm(3, "busy", "ds1", "busy/busy.vmx", "displayName = \"busy\"\n").power = "Powered on";

        clone(cluster(true), "lc", true, null, true, null);

        // b has fewer VMs on: the clone goes there, though it does not see the master
        assertThat(hostB.vmNamed("lc"), is(notNullValue()));
        assertThat(replicasOnB(), is(1L));
    }

    @Test
    void aNamedSnapshotIsWhatTheReplicaIsOf() throws Exception {
        clone(cluster(true), "lc", false, "esxib", false, "snap");

        final String vmx = hostB.files.entrySet().stream()
                .filter(e -> e.getKey().endsWith(".vmx") && e.getKey().contains("/jenkins-replica-"))
                .map(java.util.Map.Entry::getValue)
                .findFirst()
                .get();
        assertThat(VmxFile.parse(vmx).get(EsxiReplica.KEY_STATE), is("snapshot-1"));
    }

    @Test
    void theStateTheMasterHasNowIsWhatADeploymentIsOf() throws Exception {
        clone(cluster(true), "dep", false, "esxib", false, null);

        final String vmx = hostB.files.entrySet().stream()
                .filter(e -> e.getKey().endsWith(".vmx") && e.getKey().contains("/jenkins-replica-"))
                .map(java.util.Map.Entry::getValue)
                .findFirst()
                .get();
        assertThat(VmxFile.parse(vmx).get(EsxiReplica.KEY_STATE), is("now"));
    }

    @Test
    void aSnapshotThatIsNotThereOrNoneAtAllIsRefusedBeforeAnythingIsCopied() throws Exception {
        final VSphereEsxiCluster cluster = cluster(true);
        assertThrows(VSphereException.class, () -> clone(cluster, "lc", true, "esxib", false, "nope"));

        hostA.files.remove(MASTER_DIR + "/master.vmsd");
        final VSphereException e =
                assertThrows(VSphereException.class, () -> clone(cluster, "lc2", true, "esxib", true, null));

        assertThat(e.getMessage(), containsString("requires at least one snapshot"));
        assertThat(hostB.commands.stream().anyMatch(c -> c.startsWith("vmkfstools")), is(false));
    }

    @Test
    void aFailureOfTheReplicaLeavesNoCloneAndNoReplica() throws Exception {
        hostB.failing("-d thin", "No space left on device");

        final VSphereException e =
                assertThrows(VSphereException.class, () -> clone(cluster(true), "lc", true, "esxib", true, null));

        assertThat(e.getMessage(), containsString("No space left on device"));
        assertThat(hostB.vmNamed("lc"), is(nullValue()));
        assertThat(replicasOnB(), is(0L));
        assertThat(hostA.files.keySet().stream().anyMatch(f -> f.contains(".jenkins-export-")), is(false));
    }

    @Test
    void theReplicaIsProtectedLikeAnyMasterOfLinkedClones() throws Exception {
        final VSphereEsxiCluster cluster = cluster(true);
        clone(cluster, "lc", true, "esxib", true, null);
        final String replica = hostB.files.keySet().stream()
                .filter(f -> f.endsWith(".vmx") && f.contains("/jenkins-replica-"))
                .map(f -> f.substring(f.lastIndexOf('/') + 1, f.length() - 4))
                .findFirst()
                .get();

        final VSphereException e = assertThrows(VSphereException.class, () -> cluster.destroyVm(replica, true));

        assertThat(e.getMessage(), containsString("delete the VM " + replica));
        assertThat(e.getMessage(), containsString("lc ["));
    }
}
