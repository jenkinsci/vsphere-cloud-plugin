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
import java.util.ArrayList;
import java.util.List;

/**
 * One line of {@code esxcli storage filesystem list}, a table whose columns are as wide as the dashes under their
 * titles say (a volume name can have spaces in it, and can be missing, so splitting at spaces would not do).
 */
final class EsxiDatastoreEntry {

    private final String mountPoint;
    private final String name;
    private final String uuid;
    private final boolean mounted;
    private final String type;
    private final long size;
    private final long free;
    private final boolean readOnly;

    EsxiDatastoreEntry(
            String mountPoint, String name, String uuid, boolean mounted, String type, long size, long free) {
        this(mountPoint, name, uuid, mounted, type, size, free, false);
    }

    private EsxiDatastoreEntry(
            String mountPoint,
            String name,
            String uuid,
            boolean mounted,
            String type,
            long size,
            long free,
            boolean readOnly) {
        this.readOnly = readOnly;
        this.mountPoint = mountPoint;
        this.name = name;
        this.uuid = uuid;
        this.mounted = mounted;
        this.type = type;
        this.size = size;
        this.free = free;
    }

    String getMountPoint() {
        return mountPoint;
    }

    String getName() {
        return name;
    }

    String getUuid() {
        return uuid;
    }

    boolean isMounted() {
        return mounted;
    }

    String getType() {
        return type;
    }

    long getSize() {
        return size;
    }

    long getFree() {
        return free;
    }

    /** True if this is a share that is mounted read-only (as one of ISO images often is): nothing is put on it. */
    boolean isReadOnly() {
        return readOnly;
    }

    /** The same, as one that is mounted read-only. */
    EsxiDatastoreEntry asReadOnly() {
        return new EsxiDatastoreEntry(mountPoint, name, uuid, mounted, type, size, free, true);
    }

    /** Where VMs can be kept: not the boot banks and the system volumes of the host. */
    boolean isForVms() {
        final String t = type.toUpperCase();
        return !name.isEmpty()
                && (t.equals("VMFS-5")
                        || t.equals("VMFS-6")
                        || t.equals("VMFS")
                        || t.startsWith("NFS")
                        || t.equals("VSAN"));
    }

    /** The entries of the table, or null if the text is not one. */
    @CheckForNull
    static List<EsxiDatastoreEntry> parse(String output) {
        final String[] lines = output.split("\\R");
        int dashes = -1;
        for (int i = 0; i < lines.length; i++) {
            if (lines[i].startsWith("---") && i > 0 && lines[i - 1].startsWith("Mount Point")) {
                dashes = i;
                break;
            }
        }
        if (dashes < 0) {
            return null;
        }
        final List<int[]> spans = new ArrayList<>();
        final String rule = lines[dashes];
        for (int i = 0; i < rule.length(); i++) {
            if (rule.charAt(i) == '-' && (i == 0 || rule.charAt(i - 1) != '-')) {
                int end = i;
                while (end < rule.length() && rule.charAt(end) == '-') {
                    end++;
                }
                spans.add(new int[] {i, end});
            }
        }
        if (spans.size() < 7) {
            return null;
        }
        final List<EsxiDatastoreEntry> entries = new ArrayList<>();
        for (int i = dashes + 1; i < lines.length; i++) {
            if (lines[i].trim().isEmpty()) {
                continue;
            }
            final String[] cells = new String[7];
            for (int c = 0; c < 7; c++) {
                final int from = Math.min(spans.get(c)[0], lines[i].length());
                final int to = Math.min(c == 6 ? lines[i].length() : spans.get(c)[1], lines[i].length());
                cells[c] = lines[i].substring(from, to).trim();
            }
            try {
                entries.add(new EsxiDatastoreEntry(
                        cells[0],
                        cells[1],
                        cells[2],
                        Boolean.parseBoolean(cells[3]),
                        cells[4],
                        Long.parseLong(cells[5]),
                        Long.parseLong(cells[6])));
            } catch (NumberFormatException e) {
                return null;
            }
        }
        return entries;
    }
}
