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

import com.vmware.vim25.ManagedObjectReference;
import com.vmware.vim25.mo.Network;
import edu.umd.cs.findbugs.annotations.CheckForNull;

/** A port group of a standard switch of a standalone ESXi host, which is all that is known of it: its name. */
final class EsxiNetwork extends Network {

    private final String name;

    EsxiNetwork(String name) {
        super(null, reference(name));
        this.name = name;
    }

    private static ManagedObjectReference reference(String name) {
        final ManagedObjectReference mor = new ManagedObjectReference();
        mor.setType("Network");
        mor.setVal(name);
        return mor;
    }

    @Override
    protected Object getCurrentProperty(String propertyName) {
        if ("name".equals(propertyName)) {
            return name;
        }
        throw new UnsupportedOperationException(
                "The \"" + propertyName + "\" property of a network is not available from an ESXi host over SSH");
    }

    @Override
    public String toString() {
        return "network " + name;
    }

    /**
     * Whether the output of {@code esxcli network vswitch standard portgroup list} (a table whose first column is
     * the name of the port group, the columns being separated by two or more spaces) has the port group; null if
     * it does not look like that table, so that nothing is concluded from it.
     */
    @CheckForNull
    static Boolean isListed(String output, String name) {
        boolean sawHeader = false;
        boolean found = false;
        for (String line : output.split("\\R")) {
            if (line.startsWith("Name") && line.contains("Virtual Switch")) {
                sawHeader = true;
            } else if (sawHeader && !line.trim().isEmpty() && !line.startsWith("-")) {
                if (line.split("\\s{2,}", 2)[0].trim().equals(name)) {
                    found = true;
                }
            }
        }
        return sawHeader ? Boolean.valueOf(found) : null;
    }
}
