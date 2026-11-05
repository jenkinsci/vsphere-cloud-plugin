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
import static org.hamcrest.Matchers.containsInAnyOrder;
import static org.hamcrest.Matchers.empty;
import static org.hamcrest.Matchers.is;

import java.util.List;
import org.junit.jupiter.api.Test;

/** The shares that a host has mounted read-only. */
class EsxiNfsTableTest {

    private static final String SAMPLE = String.join(
            "\n",
            "Volume Name  Host        Share         Accessible  Mounted  Read-Only  Hardware Acceleration",
            "-----------  ----------  ------------  ----------  -------  ---------  ---------------------",
            "pve-iso      10.94.56.1  /export/iso   true        true     true       Unknown",
            "pve-esx      10.94.56.1  /export/esx   true        true     false      Unknown",
            "backups      10.94.56.2  /export/back  true        true     true       Supported",
            "");

    @Test
    void theReadOnlySharesAreTold() {
        assertThat(EsxiNfsTable.readOnlyVolumes(SAMPLE), containsInAnyOrder("pve-iso", "backups"));
    }

    @Test
    void aTableWithNoShareOrNotATableTellsNone() {
        assertThat(EsxiNfsTable.readOnlyVolumes("").isEmpty(), is(true));
        assertThat(EsxiNfsTable.readOnlyVolumes("esxcli: not found").isEmpty(), is(true));
        assertThat(
                EsxiNfsTable.readOnlyVolumes(String.join(
                        "\n",
                        "Volume Name  Host  Share  Accessible  Mounted  Read-Only  Hardware Acceleration",
                        "-----------  ----  -----  ----------  -------  ---------  ---------------------",
                        "")),
                is(empty()));
    }

    @Test
    void aShareThatIsReadOnlyIsTold() throws Exception {
        final FakeEsxiHost host = new FakeEsxiHost();
        host.readOnlyShares.add("nfs-share");
        final VSphereEsxiSsh esxi = new VSphereEsxiSsh(host);

        final List<EsxiDatastoreEntry> datastores = esxi.listDatastores();

        assertThat(
                datastores.stream()
                        .filter(EsxiDatastoreEntry::isReadOnly)
                        .map(EsxiDatastoreEntry::getName)
                        .count(),
                is(1L));
        assertThat(
                datastores.stream()
                        .filter(d -> d.getName().equals("datastore1"))
                        .findFirst()
                        .get()
                        .isReadOnly(),
                is(false));
        assertThat(esxi.getDatastoreByName("nfs-share").getSummary().isAccessible(), is(false));
        assertThat(esxi.getDatastoreByName("datastore1").getSummary().isAccessible(), is(true));
    }
}
