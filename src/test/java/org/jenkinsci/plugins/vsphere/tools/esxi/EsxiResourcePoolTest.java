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
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.instanceOf;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.nullValue;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.util.Map;
import org.jenkinsci.plugins.vsphere.tools.VSphereException;
import org.jenkinsci.plugins.vsphere.tools.VSphereNotFoundException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** Resource pools of a standalone ESXi host: listing, making, removing, cloning into one, and asking a VM. */
class EsxiResourcePoolTest {

    private static final String DS = "/vmfs/volumes/datastore1";
    private static final String MASTER_VMX = String.join(
            "\n",
            "displayName = \"master\"",
            "scsi0.present = \"TRUE\"",
            "scsi0:0.present = \"TRUE\"",
            "scsi0:0.fileName = \"master.vmdk\"",
            "");

    private FakeEsxiHost host;
    private VSphereEsxiSsh esxi;
    private final PrintStream log = new PrintStream(new ByteArrayOutputStream());

    @BeforeEach
    void setUp() {
        host = new FakeEsxiHost();
        host.addVm(1, "master", "datastore1", "master/master.vmx", MASTER_VMX);
        host.addFile(
                DS + "/master/master.vmdk",
                "# Disk DescriptorFile\nversion=1\ncreateType=\"vmfs\"\n\n# Extent description\nRW 2048 VMFS \"master-flat.vmdk\"\n");
        host.addFile(DS + "/master/master-flat.vmdk", "DATA");
        esxi = new VSphereEsxiSsh(host);
    }

    /** Whether the host was given the command, which it gets with every word quoted. */
    private boolean ranQuotedOrNot(String command) {
        return host.commands.stream().anyMatch(c -> c.replace("'", "").equals(command));
    }

    @Test
    void readsThePoolsFileOfTheHost() {
        final Map<String, String> pools = EsxiResourcePool.parse(
                "<ConfigRoot>\n  <ResourcePool>\n    <config><x/></config>\n    <name>ci &amp; co</name>\n"
                        + "    <objID>pool-7</objID>\n  </ResourcePool>\n  <ResourcePool>\n    <name>other</name>\n"
                        + "    <objID>pool-9</objID>\n</ResourcePool></ConfigRoot>");

        assertThat(pools.keySet(), contains("ci & co", "other"));
        assertThat(pools.get("ci & co"), is("pool-7"));
    }

    @Test
    void theTopPoolIsAlwaysThere() throws Exception {
        assertThat(esxi.listResourcePools(), is(Map.of("Resources", "ha-root-pool")));
        assertThat(esxi.getResourcePoolByName("Resources").getId(), is("ha-root-pool"));
        assertThat(esxi.getResourcePoolByName("").getName(), is("Resources"));
        assertThat(esxi.getResourcePoolByName("nope"), is(nullValue()));
    }

    @Test
    void makesAPoolOnceAndFindsItAfterwards() throws Exception {
        final EsxiResourcePool made = esxi.createResourcePool("ci");

        assertThat(made.getId(), is("pool-1"));
        assertThat(
                ranQuotedOrNot("/bin/vim-cmd hostsvc/rsrc/create --cpu-min-expandable=true --cpu-shares=normal"
                        + " --mem-min-expandable=true --mem-shares=normal ha-root-pool ci"),
                is(true));
        assertThat(esxi.getResourcePoolByName("ci").getId(), is("pool-1"));
        assertThat(esxi.listResourcePools().keySet(), contains("Resources", "ci"));

        host.commands.clear();
        assertThat(esxi.createResourcePool("ci").getId(), is("pool-1"));
        assertThat(host.commands.stream().anyMatch(c -> c.contains("rsrc/create")), is(false));
    }

    @Test
    void aNameThatIsNotPlainIsRefused() {
        assertThrows(VSphereException.class, () -> esxi.createResourcePool("a'; reboot; '"));
        assertThat(host.pools.isEmpty(), is(true));
    }

    @Test
    void removesAPoolButNotTheTopOne() throws Exception {
        esxi.createResourcePool("ci");

        esxi.deleteResourcePool("ci");

        assertThat(esxi.getResourcePoolByName("ci"), is(nullValue()));
        assertThat(
                assertThrows(VSphereException.class, () -> esxi.deleteResourcePool("Resources")),
                instanceOf(EsxiPlatformConstraint.class));
        assertThrows(VSphereNotFoundException.class, () -> esxi.deleteResourcePool("ci"));
    }

    @Test
    void aCloneIsRegisteredInThePoolItIsAskedToBeIn() throws Exception {
        esxi.createResourcePool("ci");
        host.commands.clear();

        esxi.cloneVm("c1", "master", false, "ci", "", "", "", false, "", log);

        assertThat(host.vmNamed("c1").pool, is("pool-1"));
        assertThat(ranQuotedOrNot("/bin/vim-cmd solo/registervm " + DS + "/c1/c1.vmx c1 pool-1"), is(true));
        assertThat(host.commands.stream().anyMatch(c -> c.contains("rsrc/create")), is(false));
        assertThat(esxi.getVmByName("c1").getResourcePool().getName(), is("ci"));
    }

    @Test
    void aPoolThatIsNotThereIsMadeForTheClone() throws Exception {
        esxi.cloneVm("c1", "master", false, "fresh", "", "", "", false, "", log);

        assertThat(host.pools.keySet(), contains("fresh"));
        assertThat(host.vmNamed("c1").pool, is("pool-1"));
    }

    @Test
    void theTopPoolIsTheDefaultAndNeedsNoArgument() throws Exception {
        esxi.cloneVm("c1", "master", false, "Resources", "", "", "", false, "", log);
        esxi.cloneVm("c2", "master", false, "", "", "", "", false, "", log);

        assertThat(ranQuotedOrNot("/bin/vim-cmd solo/registervm " + DS + "/c1/c1.vmx c1"), is(true));
        assertThat(ranQuotedOrNot("/bin/vim-cmd solo/registervm " + DS + "/c2/c2.vmx c2"), is(true));
        assertThat(esxi.getVmByName("c1").getResourcePool().getName(), is("Resources"));
        assertThat(host.pools.isEmpty(), is(true));
    }

    @Test
    void aBadPoolNameStopsTheCloneBeforeAnythingIsMade() {
        assertThrows(
                VSphereException.class,
                () -> esxi.cloneVm("c1", "master", false, "bad'name", "", "", "", false, "", log));

        assertThat(host.hasDirectory(DS + "/c1"), is(false));
    }

    @Test
    void aPoolMadeForACloneThatFailsStaysForTheNextOne() {
        host.failing("solo/registervm", "Failed to register");

        assertThrows(
                VSphereException.class, () -> esxi.cloneVm("c1", "master", false, "ci", "", "", "", false, "", log));

        assertThat(host.hasDirectory(DS + "/c1"), is(false)); // what was made of the clone is removed
        assertThat(host.pools.keySet(), contains("ci")); // the pool was not the clone's alone
    }
}
