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
import static org.hamcrest.Matchers.nullValue;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import org.jenkinsci.plugins.vsphere.tools.VSphereException;
import org.jenkinsci.plugins.vsphere.tools.VSphereNotFoundException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** The mark that stands in for vCenter's templates on a standalone ESXi host. */
class EsxiTemplateTest {

    private static final String VMX_PATH = "/vmfs/volumes/datastore1/base/base.vmx";
    private static final String VMX = String.join(
            "\n",
            "displayName = \"base\"",
            "scsi0.present = \"TRUE\"",
            "scsi0:0.present = \"TRUE\"",
            "scsi0:0.fileName = \"base.vmdk\"",
            "");

    private FakeEsxiHost host;
    private VSphereEsxiSsh esxi;

    @BeforeEach
    void setUp() {
        host = new FakeEsxiHost();
        host.addVm(5, "base", "datastore1", "base/base.vmx", VMX);
        host.addFile(
                "/vmfs/volumes/datastore1/base/base.vmdk",
                "# Disk DescriptorFile\nversion=1\ncreateType=\"vmfs\"\n\n# Extent description\n"
                        + "RW 2048 VMFS \"base-flat.vmdk\"\n");
        host.addFile("/vmfs/volumes/datastore1/base/base-flat.vmdk", "DATA");
        esxi = new VSphereEsxiSsh(host);
    }

    private boolean isTemplate() throws Exception {
        return esxi.getVmByName("base").getConfig().isTemplate();
    }

    @Test
    void marksAPoweredOffVmAsATemplateAndBack() throws Exception {
        assertThat(isTemplate(), is(false));

        esxi.markAsTemplate("base", "ignored", false);

        assertThat(VmxFile.parse(host.file(VMX_PATH)).get("template"), is("TRUE"));
        assertThat(isTemplate(), is(true));
        assertThat(esxi.getVmByName("base").getSummary().getConfig().isTemplate(), is(true));
        assertThat(host.ran("/bin/vim-cmd vmsvc/reload 5"), is(true));

        esxi.markAsVm("base", "", "");

        assertThat(VmxFile.parse(host.file(VMX_PATH)).get("template"), is(nullValue()));
        assertThat(isTemplate(), is(false));
    }

    @Test
    void aTemplateIsNotStarted() throws Exception {
        esxi.markAsTemplate("base", "x", false);

        final VSphereException e = assertThrows(VSphereException.class, () -> esxi.startVm("base", 10));

        assertThat(e.getMessage(), containsString("template"));
        assertThat(host.vm(5).power, is("Powered off"));
    }

    @Test
    void aRunningVmIsMarkedOnlyWhenForced() throws Exception {
        host.vm(5).power = "Powered on";

        final VSphereException e = assertThrows(VSphereException.class, () -> esxi.markAsTemplate("base", "x", false));
        assertThat(e.getMessage(), containsString("select \"force\"."));
        assertThat(isTemplate(), is(false));

        esxi.markAsTemplate("base", "x", true);

        assertThat(host.vm(5).power, is("Powered off"));
        assertThat(isTemplate(), is(true));
    }

    @Test
    void marksNothingTwice() throws Exception {
        esxi.markAsTemplate("base", "x", false);
        host.commands.clear();

        esxi.markAsTemplate("base", "x", false);

        assertThat(host.ran("/bin/vim-cmd vmsvc/reload 5"), is(false));
    }

    @Test
    void aVmThatIsNotThereIsNotFound() {
        assertThrows(VSphereNotFoundException.class, () -> esxi.markAsTemplate("nope", "x", false));
        assertThrows(VSphereNotFoundException.class, () -> esxi.markAsVm("nope", "", ""));
    }

    @Test
    void aVmDeployedFromATemplateIsNotOne() throws Exception {
        esxi.markAsTemplate("base", "x", false);

        final PrintStream log = new PrintStream(new ByteArrayOutputStream());
        esxi.deployVm("deployed", "base", false, "", "", "", "", false, "", log);

        final VmxFile deployed = VmxFile.parse(host.file("/vmfs/volumes/datastore1/deployed/deployed.vmx"));
        assertThat(deployed.get("template"), is(nullValue()));
        assertThat(esxi.getVmByName("deployed").getConfig().isTemplate(), is(false));
        assertThat(isTemplate(), is(true));
    }
}
