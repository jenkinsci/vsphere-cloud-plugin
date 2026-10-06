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

import com.vmware.vim25.DatastoreSummary;
import com.vmware.vim25.ManagedObjectReference;
import com.vmware.vim25.mo.Datastore;

/**
 * A datastore of a standalone ESXi host, as the rest of the plugin sees them: it has a name and a summary (type,
 * size, free space), which is what is looked at when the place for a disk is chosen.
 */
public final class EsxiDatastore extends Datastore {

    private final String name;
    private final DatastoreSummary summary;

    EsxiDatastore(EsxiDatastoreEntry entry) {
        super(null, reference(entry.getName()));
        this.name = entry.getName();
        this.summary = new DatastoreSummary();
        summary.setName(entry.getName());
        summary.setUrl(entry.getMountPoint());
        summary.setType(entry.getType());
        summary.setCapacity(entry.getSize());
        summary.setFreeSpace(entry.getFree());
        // not accessible, as far as putting files on it goes, if it is mounted read-only
        summary.setAccessible(entry.isMounted() && !entry.isReadOnly());
        summary.setMultipleHostAccess(entry.getType().toUpperCase().startsWith("NFS"));
    }

    /** One that is known by name only, such as the datastore of a VM that the host does not list. */
    EsxiDatastore(String name) {
        super(null, reference(name));
        this.name = name;
        this.summary = new DatastoreSummary();
        summary.setName(name);
        summary.setAccessible(true);
    }

    private static ManagedObjectReference reference(String name) {
        final ManagedObjectReference mor = new ManagedObjectReference();
        mor.setType("Datastore");
        mor.setVal(name);
        return mor;
    }

    @Override
    protected Object getCurrentProperty(String propertyName) {
        switch (propertyName) {
            case "name":
                return name;
            case "summary":
                return summary;
            default:
                throw new UnsupportedOperationException("The \"" + propertyName
                        + "\" property of a datastore is not available from an ESXi host over SSH (yet)");
        }
    }

    @Override
    public String toString() {
        return "datastore " + name;
    }
}
