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

import java.util.ArrayList;
import java.util.List;
import org.jenkinsci.plugins.vsphere.tools.VSphereException;

/**
 * How large a virtual disk is, and how much of that is written: the size it presents to the VM is in its descriptor,
 * and the space that its files take, which is what a copy of it has to hold, is what {@code du -k} says of them
 * (on a datastore that keeps what is not written out, as VMFS does for a thin disk, and as an NFS server may: the
 * 4 GB disk of a VM that was only set up took 1 KB there, a 512 MB memory file 8.6 MB). On a file system that
 * compresses, the number is smaller than what has to be read, so it is a guide to what has to be copied, and not a
 * promise.
 */
final class EsxiDiskSizes {

    /** The size of the disk as the VM sees it. */
    final long logicalKb;

    /** What its files (and the files of the disks that it is a change of) take, or -1 if the host could not say. */
    final long allocatedKb;

    /** True if the disk is a change of another (it is the disk of a snapshot), so that its data is in several files. */
    final boolean chained;

    /** The files of the disk itself: its descriptor and the extents that this names (not those of its parents). */
    final List<String> ownFiles;

    private EsxiDiskSizes(long logicalKb, long allocatedKb, boolean chained, List<String> ownFiles) {
        this.logicalKb = logicalKb;
        this.allocatedKb = allocatedKb;
        this.chained = chained;
        this.ownFiles = ownFiles;
    }

    private static String parent(String path) {
        return path.substring(0, path.lastIndexOf('/'));
    }

    /** Looks at the disk with this descriptor and at the disks that it is a change of. */
    static EsxiDiskSizes measure(EsxiDatastoreFiles files, String descriptorPath) throws VSphereException {
        VmdkDescriptor descriptor = files.readDescriptor(descriptorPath);
        final long logical = descriptor.getCapacityKb();
        final List<String> own = new ArrayList<>();
        own.add(descriptorPath.substring(descriptorPath.lastIndexOf('/') + 1));
        own.addAll(descriptor.getExtentFiles());
        long allocated = 0;
        boolean known = true;
        boolean chained = false;
        String current = descriptorPath;
        for (int depth = 0; depth < 64; depth++) {
            for (String extent : descriptor.getExtentFiles()) {
                final long kb = files.allocatedKb(extent.startsWith("/") ? extent : parent(current) + "/" + extent);
                if (kb < 0) {
                    known = false;
                } else {
                    allocated += kb;
                }
            }
            final String hint = descriptor.getParentHint();
            if (hint == null) {
                break;
            }
            chained = true;
            current = hint.startsWith("/") ? hint : parent(current) + "/" + hint;
            descriptor = files.readDescriptor(current);
        }
        return new EsxiDiskSizes(logical, known ? allocated : -1, chained, own);
    }

    /**
     * True if what is written is most of what the disk can hold, so that sending its files as they are costs about
     * as much as anything that skips what is not written.
     */
    boolean isDense() {
        return allocatedKb >= 0 && logicalKb > 0 && allocatedKb * 2 >= logicalKb;
    }

    /** What a copy of this much needs, with room to spare. */
    static long withMargin(long kb) {
        return kb + kb / 20 + 64 * 1024;
    }
}
