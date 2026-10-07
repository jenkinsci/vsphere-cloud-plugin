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
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.notNullValue;
import static org.hamcrest.Matchers.nullValue;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.vmware.vim25.mo.VirtualMachine;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.util.List;
import java.util.Set;
import java.util.function.Supplier;
import org.jenkinsci.plugins.vsphere.tools.HostSelectionOptions;
import org.jenkinsci.plugins.vsphere.tools.HostWeights;
import org.jenkinsci.plugins.vsphere.tools.VSphereDuplicateException;
import org.jenkinsci.plugins.vsphere.tools.VSphereException;
import org.jenkinsci.plugins.vsphere.tools.VSphereNotFoundException;
import org.jenkinsci.plugins.vsphere.tools.VmSize;
import org.junit.jupiter.api.AfterEach;
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

    @AfterEach
    void tearDown() {
        System.clearProperty(EsxiCloneGuard.PROPERTY);
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
    void aNameThatSeveralHostsHaveRegisteredIsRefusedAndNothingIsDone() throws Exception {
        hostA.addVm(3, "twice", "shared", "twice-a/twice.vmx", "displayName = \"twice\"\n");
        hostB.addVm(4, "twice", "shared", "twice-b/twice.vmx", "displayName = \"twice\"\n");
        final VSphereEsxiCluster cluster = cluster();

        final EsxiAmbiguousVmException refused =
                assertThrows(EsxiAmbiguousVmException.class, () -> cluster.getVmByName("twice"));
        assertThat(refused.getMessage(), containsString("a.example, b.example"));
        assertThrows(EsxiAmbiguousVmException.class, () -> cluster.takeSnapshot("twice", "s", "", false));
        assertThat(hostA.commands.stream().anyMatch(c -> c.contains("snapshot.create")), is(false));
        assertThat(hostB.commands.stream().anyMatch(c -> c.contains("snapshot.create")), is(false));
        // the others are as before
        assertThat(cluster.getVmByName("on-b"), is(notNullValue()));
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

    @Test
    void aVolumeThatAnotherHostCallsByAnotherNameIsTheSameByItsUuid() throws Exception {
        // b has the volume under the label "share-b" (the UUID is the same one)
        hostB.volumeAliases.put("share-b", "shared");
        hostB.datastoreTable = FakeEsxiHost.datastoreTable(
                new String[][] {{REAL, "share-b", "uuid-shared", "true", "NFS", "1000000000", "900000000"}});

        clone(cluster(), "lc", false, "esxib", null);

        assertThat(hostB.vmNamed("lc"), is(notNullValue()));
        // b read the master through its own name for the volume
        assertThat(
                hostB.commands.stream()
                        .anyMatch(c -> c.startsWith("vmkfstools -i '/vmfs/volumes/share-b/master/master-000001.vmdk'")),
                is(true));
        assertThat(hostA.hasFile(SHARED + "/lc/lc.vmx"), is(true));
    }

    @Test
    void aHostThatHasNoVolumeWithTheUuidOfTheMastersDatastoreDoesNotSeeTheMaster() throws Exception {
        hostB.datastoreTable = FakeEsxiHost.datastoreTable(new String[][] {
            {"/vmfs/volumes/uuid-other", "shared", "uuid-other", "true", "VMFS-6", "1000000000", "900000000"}
        });

        final VSphereException e =
                assertThrows(VSphereException.class, () -> clone(cluster(), "lc", true, "esxib", null));

        assertThat(e.getMessage(), containsString("cannot be used for the clone"));
    }

    // -- where a clone goes by how busy the hosts really are --

    private String cloneRanked(String mode, HostSelectionOptions options) throws Exception {
        final ByteArrayOutputStream said = new ByteArrayOutputStream();
        cluster()
                .cloneOrDeployVm(
                        "lc",
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
                        mode,
                        null,
                        options,
                        VmSize.NONE,
                        new PrintStream(said));
        return said.toString();
    }

    private String where() {
        return hostA.vmNamed("lc") != null ? "a" : hostB.vmNamed("lc") != null ? "b" : "nowhere";
    }

    /** a has nothing on but is nearly out of CPU; b has two VMs on and is nearly idle. */
    private void aBusyButEmptyAndAnIdleButFull() {
        hostA.cpuUsageMhz = 8000;
        hostA.memoryUsageMB = 12000;
        hostB.cpuUsageMhz = 300;
        hostB.memoryUsageMB = 1500;
        hostB.addVm(8, "r1", "shared", "r1/r1.vmx", "displayName = \"r1\"\n").power = "Powered on";
        hostB.addVm(9, "r2", "shared", "r2/r2.vmx", "displayName = \"r2\"\n").power = "Powered on";
    }

    @Test
    void byDefaultAndForFewestRunningVmsTheHostWithFewerVmsOnIsUsed() throws Exception {
        aBusyButEmptyAndAnIdleButFull();
        cloneRanked(null, null);
        assertThat(where(), is("a"));

        hostA.files.keySet().removeIf(f -> f.startsWith(SHARED + "/lc/"));
        hostA.removeVmRegistration("lc");
        hostA.directories.remove(SHARED + "/lc");
        cloneRanked(VSphereEsxiCluster.MODE_FEWEST_RUNNING_VMS, null);
        assertThat(where(), is("a"));
    }

    @Test
    void leastLoadedGoesByWhatTheHostsSayIsUsed() throws Exception {
        aBusyButEmptyAndAnIdleButFull();

        final String said = cloneRanked("LEAST_LOADED", null);

        assertThat(where(), is("b"));
        assertThat(said, containsString("Ranked the ESXi hosts by the lower of free CPU and memory:"));
        assertThat(said, containsString("b.example=0.9"));
    }

    @Test
    void theWeightsOfTheOptionsDecideWhatCounts() throws Exception {
        // a has the more free memory in MB (12 of 16 GB used vs. b's 1.5... so make b small on memory)
        aBusyButEmptyAndAnIdleButFull();
        hostB.memoryBytes = 4L * 1024 * 1024 * 1024;
        hostB.memoryUsageMB = 3000;
        hostA.cpuUsageMhz = 0;
        hostA.memoryUsageMB = 4000;

        // by free memory in MB alone: a (12 GB free) beats b (1 GB free)
        cloneRanked("LEAST_LOADED", HostSelectionOptions.NONE.withWeights(new HostWeights(0, 0, 1, 0)));
        assertThat(where(), is("a"));
    }

    @Test
    void aHostInMaintenanceIsNotUsed() throws Exception {
        aBusyButEmptyAndAnIdleButFull();
        hostB.inMaintenanceMode = true;

        cloneRanked("LEAST_LOADED", null);

        assertThat(where(), is("a"));
    }

    @Test
    void aHostWithTooLittleFreeMemoryForTheVmIsLeftOutIfTheOptionsAskForThat() throws Exception {
        aBusyButEmptyAndAnIdleButFull();
        hostB.memoryUsageMB = 15000;
        hostA.cpuUsageMhz = 9000; // a is the busier by CPU, but b cannot hold the VM

        final String said =
                cloneRanked("LEAST_LOADED", new HostSelectionOptions(false, false, true).withVmSize(null, 4000L));

        assertThat(where(), is("a"));
        assertThat(said, containsString("Not using the ESXi host b.example"));
    }

    @Test
    void whenNoHostSaysHowBusyItIsTheVmsThatAreOnDecide() throws Exception {
        aBusyButEmptyAndAnIdleButFull();
        hostA.cpuUsageMhz = null;
        hostB.cpuUsageMhz = null;

        final String said = cloneRanked("LEAST_LOADED", null);

        assertThat(where(), is("a"));
        assertThat(said, containsString("choosing by the number of VMs that are on"));
    }

    @Test
    void aStandaloneHostHasNoDrsAndSaysSo() throws Exception {
        aBusyButEmptyAndAnIdleButFull();

        final String said = cloneRanked("DRS_RECOMMENDED", null);

        assertThat(where(), is("b"));
        assertThat(said, containsString("has no DRS to ask"));
    }

    @Test
    void whatTheHostSaysOfItselfIsRead() {
        final EsxiHostStats stats = EsxiHostStats.parse(String.join(
                "\n",
                "(vim.host.Summary) {",
                "   hardware = (vim.host.Hardware.Summary) {",
                "      memorySize = 17104994304,",
                "      cpuMhz = 2394,",
                "      numCpuCores = 4,",
                "   },",
                "   runtime = (vim.host.RuntimeInfo) {",
                "      inMaintenanceMode = true,",
                "   },",
                "   quickStats = (vim.host.Summary.QuickStats) {",
                "      overallCpuUsage = 190,",
                "      overallMemoryUsage = 3098,",
                "   },",
                "}"));

        assertThat(stats.cpuMhz, is(2394));
        assertThat(stats.cpuCores, is(4));
        assertThat(stats.cpuUsageMhz, is(190));
        assertThat(stats.memoryUsageMB, is(3098));
        assertThat(stats.inMaintenanceMode, is(true));
        assertThat(stats.asCandidate("x").getCpuCapacityMhz(), is(2394 * 4));
        assertThat(stats.asCandidate("x").getMemCapacityMB(), is(16312L));
        assertThat(EsxiHostStats.parse("nothing useful").asCandidate("x").loadFraction(), is(nullValue()));
    }

    // -- the guard for the disks that clones are made of --

    private void linkedCloneOnB() throws Exception {
        hostB.addVm(5, "busy", "shared", "busy/busy.vmx", "displayName = \"busy\"\n").power = "Powered on";
        hostA.addVm(6, "busy2", "shared", "busy2/busy2.vmx", "displayName = \"busy2\"\n").power = "Powered on";
        hostA.addVm(7, "busy3", "shared", "busy3/busy3.vmx", "displayName = \"busy3\"\n").power = "Powered on";
        clone(cluster(), "lc", true, "esxib", null);
        assertThat(hostB.vmNamed("lc"), is(notNullValue()));
    }

    @Test
    void theSnapshotsOfAMasterThatHasALinkedCloneOnAnotherHostAreNotRemoved() throws Exception {
        linkedCloneOnB();

        final VSphereException e =
                assertThrows(VSphereException.class, () -> cluster().deleteSnapshot("master", "snap", false, true));

        assertThat(e.getMessage(), containsString("Refusing to remove the snapshot of the VM master"));
        assertThat(e.getMessage(), containsString("lc [" + REAL + "/lc/lc.vmx]"));
        assertThat(hostA.vm(1).snapshots.size(), is(1));
    }

    @Test
    void theMasterIsNotDeletedNorItsDisksChangedEither() throws Exception {
        linkedCloneOnB();
        final VSphereEsxiCluster cluster = cluster();

        assertThat(
                assertThrows(VSphereException.class, () -> cluster.destroyVm("master", true))
                        .getMessage(),
                containsString("delete the VM master"));
        assertThat(hostA.vmNamed("master"), is(notNullValue()));
        final VirtualMachine master = cluster.getVmByName("master");
        assertThat(
                ((EsxiTask) master.removeAllSnapshots_Task())
                        .getTaskInfo()
                        .getError()
                        .getLocalizedMessage(),
                containsString("remove all the snapshots of the VM master"));
    }

    @Test
    void aCloneThatIsNotRegisteredAnywhereIsFoundOnTheSharedDatastore() throws Exception {
        linkedCloneOnB();
        // unregistered, but its files are there
        hostB.removeVmRegistration("lc");

        final VSphereException e =
                assertThrows(VSphereException.class, () -> cluster().deleteSnapshot("master", "snap", false, true));

        assertThat(e.getMessage(), containsString("lc ["));
    }

    @Test
    void whenTheHostWithTheCloneIsDownItIsFoundByTheDatastoresTheOthersSeeToo() throws Exception {
        linkedCloneOnB();
        final VSphereEsxiCluster cluster = cluster();
        bIsUp = false;
        hostB.closed = true;
        cluster.getVmByName("lc"); // b is found to be gone

        final VSphereException e =
                assertThrows(VSphereException.class, () -> cluster.deleteSnapshot("master", "snap", false, true));

        assertThat(e.getMessage(), containsString("lc ["));
    }

    @Test
    void aCloneOnStorageOnlyADownHostSeesIsNotKnown() throws Exception {
        // the caveat: the definition is on a datastore that only host b has
        hostA.addFile("/vmfs/volumes/localB/lc/lc.vmx", "displayName = \"lc\"\nscsi0:0.fileName = \"lc.vmdk\"\n");
        hostA.addFile("/vmfs/volumes/localB/lc/lc.vmdk", descriptor(REAL + "/master/master.vmdk", "lc-delta.vmdk"));
        final VSphereEsxiCluster up = cluster();

        // asked while b is up, it is found there
        assertThat(
                assertThrows(VSphereException.class, () -> up.deleteSnapshot("master", "snap", false, true))
                        .getMessage(),
                containsString("lc ["));

        // b gone: nothing that can be asked has it
        bIsUp = false;
        final VSphereEsxiCluster down = cluster();
        down.deleteSnapshot("master", "snap", false, true);
        assertThat(hostA.vm(1).snapshots.isEmpty(), is(true));
    }

    @Test
    void whenAHostCannotBeAskedTheRefusalSaysSo() throws Exception {
        linkedCloneOnB();
        final VSphereEsxiCluster cluster = cluster();
        hostB.failing("esxcli storage filesystem list", "esxcli broke");

        final VSphereException e =
                assertThrows(VSphereException.class, () -> cluster.deleteSnapshot("master", "snap", false, true));

        assertThat(e.getMessage(), containsString("Hosts that could not be asked"));
        assertThat(e.getMessage(), containsString("b.example"));
    }

    @Test
    void onceTheCloneIsGoneTheSnapshotCanBeRemoved() throws Exception {
        linkedCloneOnB();
        final VSphereEsxiCluster cluster = cluster();
        cluster.destroyVm("lc", true);

        cluster.deleteSnapshot("master", "snap", false, true);

        assertThat(hostA.vm(1).snapshots.isEmpty(), is(true));
    }

    @Test
    void theGuardCanBeTurnedOff() throws Exception {
        linkedCloneOnB();
        System.setProperty(EsxiCloneGuard.PROPERTY, "false");

        cluster().deleteSnapshot("master", "snap", false, true);

        assertThat(hostA.vm(1).snapshots.isEmpty(), is(true));
    }

    @Test
    void aMasterWithNoClonesIsFreeToChange() throws Exception {
        cluster().deleteSnapshot("master", "snap", false, true);

        assertThat(hostA.vm(1).snapshots.isEmpty(), is(true));
    }

    @Test
    void theGuardWorksForASingleHostToo() throws Exception {
        final VSphereEsxiSsh single = new VSphereEsxiSsh(hostA);
        single.cloneOrDeployVm(
                "lc",
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
                "",
                "",
                null,
                null,
                VmSize.NONE,
                log);

        final VSphereException e =
                assertThrows(VSphereException.class, () -> single.deleteSnapshot("master", "snap", false, true));

        assertThat(e.getMessage(), containsString("lc ["));
        assertThat(e, not(instanceOf(EsxiPlatformConstraint.class)));
    }
}
