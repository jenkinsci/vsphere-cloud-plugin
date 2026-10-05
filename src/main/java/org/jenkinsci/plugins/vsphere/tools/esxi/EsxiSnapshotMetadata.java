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

/**
 * The {@code .vmsd} file of a VM, where the host keeps what it knows of the snapshots (the same
 * {@code key = "value"} lines as a {@code .vmx}): {@code snapshotN.uid} is the number that {@code vim-cmd} knows a
 * snapshot by, {@code snapshotN.displayName} and {@code .description} are what it is called, and
 * {@code snapshotN.diskK.node} / {@code .fileName} say which disk file held each disk when the snapshot was taken.
 */
final class EsxiSnapshotMetadata {

    private static final int MOST_SNAPSHOTS = 512;

    private final VmxFile file;

    private EsxiSnapshotMetadata(VmxFile file) {
        this.file = file;
    }

    static EsxiSnapshotMetadata parse(String text) {
        return new EsxiSnapshotMetadata(VmxFile.parse(text));
    }

    /** Where the file of a VM is: next to its .vmx, with the same name. */
    static String pathFor(String vmxPath) {
        return (vmxPath.endsWith(".vmx") ? vmxPath.substring(0, vmxPath.length() - ".vmx".length()) : vmxPath)
                + ".vmsd";
    }

    /** The N of the snapshotN entries that have this number, or -1. */
    int indexOfUid(String uid) {
        for (int n = 0; n < MOST_SNAPSHOTS; n++) {
            final String found = file.get("snapshot" + n + ".uid");
            if (found != null && found.equals(uid)) {
                return n;
            }
        }
        return -1;
    }

    /** The disk file (as the entry names it) that held the disk on the node ({@code scsi0:0}), or null. */
    @CheckForNull
    String diskFile(int index, String node) {
        for (int k = 0; k < 64; k++) {
            final String found = file.get("snapshot" + index + ".disk" + k + ".node");
            if (found == null) {
                if (file.get("snapshot" + index + ".disk" + k + ".fileName") == null) {
                    return null;
                }
            } else if (found.equalsIgnoreCase(node)) {
                final String name = file.get("snapshot" + index + ".disk" + k + ".fileName");
                return name == null ? null : VmxFile.unescape(name);
            }
        }
        return null;
    }

    void rename(int index, String name, String description) {
        file.put("snapshot" + index + ".displayName", VmxFile.escape(name));
        if (description == null || description.isEmpty()) {
            file.remove("snapshot" + index + ".description");
        } else {
            file.put("snapshot" + index + ".description", VmxFile.escape(description));
        }
    }

    @Override
    public String toString() {
        return file.toString();
    }
}
