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

import edu.umd.cs.findbugs.annotations.CheckForNull;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The virtual hardware version of a VM ({@code virtualHW.version} in its {@code .vmx}) has to be one that the host
 * knows: a host registers a VM of a newer one as invalid, which is what a clone made on an ESXi 7 host of a master
 * from an ESXi 8 host is. So a copy of a VM that is made for a host is given the newest version that the host has, if
 * the master's is newer, which the VM is mostly all right with (a guest uses no more of the hardware than it did).
 *
 * <p>What a host has is told from its version ({@code vmware -v}), by what VMware documents: this is a table, and a
 * version that is not in it is not changed.
 */
final class EsxiHardwareVersion {

    private static final Pattern VERSION = Pattern.compile("ESXi\\s+(\\d+)\\.(\\d+)\\.(\\d+)");

    private EsxiHardwareVersion() {}

    /** The newest hardware version of the ESXi that {@code vmware -v} printed, or null if it is not known. */
    @CheckForNull
    static Integer maxFor(@CheckForNull String vmwareVersion) {
        if (vmwareVersion == null) {
            return null;
        }
        final Matcher m = VERSION.matcher(vmwareVersion);
        if (!m.find()) {
            return null;
        }
        final int major = Integer.parseInt(m.group(1));
        final int minor = Integer.parseInt(m.group(2));
        final int update = Integer.parseInt(m.group(3));
        if (major == 6 && minor == 0) {
            return 11;
        }
        if (major == 6 && minor == 5) {
            return 13;
        }
        if (major == 6 && minor == 7) {
            return update >= 2 ? 15 : 14;
        }
        if (major == 7 && minor == 0) {
            // 7.0 has 17, 7.0 U1 18, and 7.0 U2 and U3 19
            return update >= 2 ? 19 : update == 1 ? 18 : 17;
        }
        if (major == 8 && minor == 0) {
            // 8.0 and 8.0 U1 have 20, 8.0 U2 and U3 21
            return update >= 2 ? 21 : 20;
        }
        return null;
    }

    /**
     * Gives the VM the newest version that the host has, if it has one that is newer.
     *
     * @return what was done, to say, or null if nothing was
     */
    @CheckForNull
    static String lowerTo(VmxFile vmx, @CheckForNull Integer max) {
        final String have = vmx.get("virtualHW.version");
        if (max == null || have == null) {
            return null;
        }
        try {
            final int version = Integer.parseInt(have.trim());
            if (version <= max) {
                return null;
            }
            vmx.put("virtualHW.version", Integer.toString(max));
            return "The virtual hardware version of the VM is " + version + ", which the host does not have; it is "
                    + "made " + max + ", the newest that it has";
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
