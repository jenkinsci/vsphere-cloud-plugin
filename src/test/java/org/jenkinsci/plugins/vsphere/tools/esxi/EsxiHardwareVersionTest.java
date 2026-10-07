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

import org.junit.jupiter.api.Test;

/** The virtual hardware version of a VM against what a host has. */
class EsxiHardwareVersionTest {

    @Test
    void theNewestVersionOfAHostIsToldFromItsVersion() {
        // as the two hosts of the test lab print it
        assertThat(EsxiHardwareVersion.maxFor("VMware ESXi 7.0.3 build-20036589"), is(19));
        assertThat(EsxiHardwareVersion.maxFor("VMware ESXi 8.0.1 build-21813344"), is(20));
        assertThat(EsxiHardwareVersion.maxFor("VMware ESXi 7.0.0 build-15843807"), is(17));
        assertThat(EsxiHardwareVersion.maxFor("VMware ESXi 7.0.1 build-1"), is(18));
        assertThat(EsxiHardwareVersion.maxFor("VMware ESXi 8.0.2 build-1"), is(21));
        assertThat(EsxiHardwareVersion.maxFor("VMware ESXi 6.7.0 build-1"), is(14));
        assertThat(EsxiHardwareVersion.maxFor("VMware ESXi 6.7.2 build-1"), is(15));
        assertThat(EsxiHardwareVersion.maxFor("VMware ESXi 6.5.0 build-1"), is(13));
    }

    @Test
    void aVersionThatIsNotKnownIsNotGuessed() {
        assertThat(EsxiHardwareVersion.maxFor("VMware ESXi 9.3.0 build-1"), is(nullValue()));
        assertThat(EsxiHardwareVersion.maxFor("something else"), is(nullValue()));
        assertThat(EsxiHardwareVersion.maxFor(null), is(nullValue()));
    }

    @Test
    void aVmOfANewerVersionIsMadeTheNewestThatTheHostHas() {
        final VmxFile vmx = VmxFile.parse("virtualHW.version = \"20\"\n");

        final String said = EsxiHardwareVersion.lowerTo(vmx, 19);

        assertThat(vmx.get("virtualHW.version"), is("19"));
        assertThat(said, containsString("is 20, which the host does not have; it is made 19"));
    }

    @Test
    void anOlderOrEqualOrUnknownVersionIsLeftAlone() {
        for (String text : new String[] {
            "virtualHW.version = \"19\"\n",
            "virtualHW.version = \"13\"\n",
            "x = \"1\"\n",
            "virtualHW.version = \"abc\"\n"
        }) {
            final VmxFile vmx = VmxFile.parse(text);
            assertThat(EsxiHardwareVersion.lowerTo(vmx, 19), is(nullValue()));
            assertThat(vmx.toString(), is(text));
        }
        final VmxFile vmx = VmxFile.parse("virtualHW.version = \"20\"\n");
        assertThat(EsxiHardwareVersion.lowerTo(vmx, null), is(nullValue()));
        assertThat(vmx.get("virtualHW.version"), is("20"));
    }
}
