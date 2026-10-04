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
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.nullValue;

import org.junit.jupiter.api.Test;

class VmxFileTest {

    private static final String VMX = String.join(
            "\n",
            ".encoding = \"UTF-8\"",
            "# a comment line",
            "displayName = \"kube-master\"",
            "numvcpus = \"4\"",
            "memSize = \"8192\"",
            "scsi0:0.fileName = \"kube-master-000001.vmdk\"",
            "ethernet0.addressType = \"generated\"",
            "ethernet0.generatedAddress = \"00:0c:29:aa:bb:cc\"",
            "");

    @Test
    void readsSettingsWithoutRegardToCaseOfTheKey() {
        VmxFile vmx = VmxFile.parse(VMX);

        assertThat(vmx.get("displayName"), is("kube-master"));
        assertThat(vmx.get("DISPLAYNAME"), is("kube-master"));
        assertThat(vmx.getInt("numvcpus", 1), is(4));
        assertThat(vmx.getInt("missing", 7), is(7));
        assertThat(vmx.get("missing"), is(nullValue()));
        assertThat(vmx.get("missing", "dflt"), is("dflt"));
        assertThat(vmx.get("scsi0:0.fileName"), is("kube-master-000001.vmdk"));
    }

    @Test
    void settingsCanBeReplacedAddedAndRemovedKeepingTheRest() {
        VmxFile vmx = VmxFile.parse(VMX);

        vmx.put("displayName", "clone-01");
        vmx.put("guestinfo.vmname", "clone-01");
        assertThat(vmx.remove("ethernet0.generatedAddress"), is(true));
        assertThat(vmx.remove("ethernet0.generatedAddress"), is(false));

        assertThat(vmx.get("displayName"), is("clone-01"));
        assertThat(vmx.get("guestinfo.vmname"), is("clone-01"));
        assertThat(vmx.get("ethernet0.generatedAddress"), is(nullValue()));
        String text = vmx.toString();
        assertThat(text.contains("# a comment line\n"), is(true));
        assertThat(text.contains(".encoding = \"UTF-8\"\n"), is(true));
        assertThat(text.endsWith("guestinfo.vmname = \"clone-01\"\n"), is(true));
    }

    @Test
    void settingsUnderAPrefix() {
        VmxFile vmx = VmxFile.parse(VMX);

        assertThat(vmx.hasSettingsUnder("ethernet0"), is(true));
        assertThat(vmx.hasSettingsUnder("ethernet1"), is(false));
        assertThat(vmx.hasSettingsUnder("eth"), is(false));
    }

    @Test
    void keysInFileOrder() {
        assertThat(
                VmxFile.parse(VMX).keys(),
                contains(
                        ".encoding",
                        "displayName",
                        "numvcpus",
                        "memSize",
                        "scsi0:0.fileName",
                        "ethernet0.addressType",
                        "ethernet0.generatedAddress"));
    }

    @Test
    void emptyTextIsAnEmptyFile() {
        assertThat(VmxFile.parse(null).toString(), is(""));
        assertThat(VmxFile.parse("").keys().size(), is(0));
    }
}
