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
import com.vmware.vim25.mo.ResourcePool;
import edu.umd.cs.findbugs.annotations.CheckForNull;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * A resource pool of a standalone ESXi host: a name and the id the host knows it by. The host keeps the pools in
 * {@code /etc/vmware/hostd/pools.xml}; the top one, called "Resources" as on vCenter, is {@link #ROOT_ID}.
 */
public final class EsxiResourcePool extends ResourcePool {

    /** The id of the pool that every host has, which VMs are in unless they are put in another one. */
    public static final String ROOT_ID = "ha-root-pool";

    public static final String ROOT_NAME = "Resources";

    private static final Pattern VM = Pattern.compile(
            "<vm\\b[^>]*>(?:(?!</vm>).)*?<objID>(\\d+)</objID>(?:(?!</vm>).)*?<resourcePool>([^<]+)</resourcePool>",
            Pattern.DOTALL);
    private static final Pattern POOL = Pattern.compile("<name>([^<]*)</name>\\s*<objID>([^<]+)</objID>");

    private final String name;

    EsxiResourcePool(String name, String id) {
        super(null, reference(id));
        this.name = name;
    }

    private static ManagedObjectReference reference(String id) {
        final ManagedObjectReference mor = new ManagedObjectReference();
        mor.setType("ResourcePool");
        mor.setVal(id);
        return mor;
    }

    /** The id that the host knows the pool by. */
    public String getId() {
        return getMOR().getVal();
    }

    @Override
    protected Object getCurrentProperty(String propertyName) {
        if ("name".equals(propertyName)) {
            return name;
        }
        throw new UnsupportedOperationException("The \"" + propertyName
                + "\" property of a resource pool is not available from an ESXi host over SSH (yet)");
    }

    @Override
    public String toString() {
        return "resource pool " + name + " (" + getId() + ")";
    }

    /** True for the name of the pool that every host has (or for no name, which means the same). */
    static boolean isRoot(@CheckForNull String name) {
        return name == null || name.trim().isEmpty() || ROOT_NAME.equals(name.trim());
    }

    /**
     * The id of the pool that the text of {@code pools.xml} puts the VM in: it has an entry
     * {@code <vm>...<objID>id of the VM</objID>...<resourcePool>id of the pool</resourcePool></vm>} for each.
     */
    @CheckForNull
    static String poolIdOfVm(String xml, int vmId) {
        final Matcher m = VM.matcher(xml);
        while (m.find()) {
            if (m.group(1).equals(Integer.toString(vmId))) {
                return m.group(2).trim();
            }
        }
        return null;
    }

    /** The pools in the text of {@code pools.xml}: name to id, in the order of the file. */
    static Map<String, String> parse(String xml) {
        final Map<String, String> pools = new LinkedHashMap<>();
        final Matcher m = POOL.matcher(xml);
        while (m.find()) {
            pools.put(
                    m.group(1)
                            .replace("&lt;", "<")
                            .replace("&gt;", ">")
                            .replace("&quot;", "\"")
                            .replace("&amp;", "&"),
                    m.group(2).trim());
        }
        return pools;
    }
}
