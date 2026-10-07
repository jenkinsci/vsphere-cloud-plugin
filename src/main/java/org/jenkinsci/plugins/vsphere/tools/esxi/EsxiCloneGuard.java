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
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import org.jenkinsci.plugins.vsphere.tools.VSphereException;

/**
 * A guard for the disks that linked clones are made of. A linked clone is a delta that has the disks of its master as
 * parents, by name; the host does not know that (it keeps no list of the clones of a VM). Removing a snapshot of the
 * master, or all of them, merges its disks, which changes or deletes the files that a clone relies on, and so does
 * deleting the master, deleting one of its disks, or making one larger. A running clone keeps its parents locked,
 * so that the host refuses; a clone that is not running gives no sign and is lost without a word.
 *
 * <p>So before any of these, the VMs are looked at: all those registered on the hosts that can be asked, and, in case
 * they are not (a clone that is registered on a host that cannot be asked, but that is on a datastore the others
 * see too), every {@code .vmx} file in the folders of the datastores of those hosts. The disks of each are followed
 * by their parents, and the VM is a dependent if one of the parents is in the folder of the master.
 *
 * <p>What this cannot see is a clone whose definition is on a datastore that none of the hosts asked have, such as
 * the local datastore of a host that is down. Turning it off is possible with the system property
 * {@value #PROPERTY} set to {@code false}.
 */
final class EsxiCloneGuard {

    static final String PROPERTY = "org.jenkinsci.plugins.vsphere.tools.esxi.protectLinkedCloneParents";

    private static final Pattern DISK_FILE = Pattern.compile("(scsi|sata|ide|nvme)\\d+:\\d+\\.filename");
    private static final int MOST_PARENTS = 64;

    private EsxiCloneGuard() {}

    static boolean isEnabled() {
        return !"false".equalsIgnoreCase(System.getProperty(PROPERTY, "true"));
    }

    private static String parent(String path) {
        final int slash = path.lastIndexOf('/');
        return slash <= 0 ? "" : path.substring(0, slash);
    }

    /** What was found out about the VMs that depend on a master. */
    static final class Findings {
        final List<String> dependents = new ArrayList<>();
        final List<String> notAsked = new ArrayList<>();
    }

    /**
     * The VMs that have disks which are linked clones of the disks of the master, found on the hosts that can be
     * asked (those of the cluster the host belongs to).
     */
    static Findings look(VSphereEsxiSsh host, VmEntry master) throws VSphereException {
        final Findings findings = new Findings();
        final String masterFolder = host.files().canonical(parent(master.getVmxFileSystemPath()));
        final Set<String> seen = new LinkedHashSet<>();
        for (VSphereEsxiSsh asked : host.clusterHosts()) {
            try {
                lookOn(asked, masterFolder, seen, findings);
            } catch (VSphereException e) {
                findings.notAsked.add(asked.getLabel() + " (" + e.getMessage() + ")");
            }
        }
        return findings;
    }

    /** Null if the master may be changed, otherwise why it may not. */
    @CheckForNull
    static String check(VSphereEsxiSsh host, VmEntry master, String action) throws VSphereException {
        if (!isEnabled()) {
            return null;
        }
        final Findings findings = look(host, master);
        if (findings.dependents.isEmpty()) {
            return null;
        }
        final StringBuilder message = new StringBuilder("Refusing to ")
                .append(action)
                .append(": ")
                .append(findings.dependents.size())
                .append(findings.dependents.size() == 1 ? " VM is" : " VMs are")
                .append(" a linked clone of ")
                .append(master.getName())
                .append(" (its disks have disks of ")
                .append(master.getName())
                .append(" as parents) and would be broken: ")
                .append(String.join(", ", findings.dependents))
                .append(". Delete those first");
        if (!findings.notAsked.isEmpty()) {
            message.append(". Hosts that could not be asked, whose VMs are therefore known only if they are on storage")
                    .append(" that the others see too: ")
                    .append(String.join(", ", findings.notAsked));
        }
        return message.append(" (the check can be turned off with -D")
                .append(PROPERTY)
                .append("=false)")
                .toString();
    }

    private static void lookOn(VSphereEsxiSsh host, String masterFolder, Set<String> seen, Findings findings)
            throws VSphereException {
        final EsxiDatastoreFiles files = host.files();
        final Set<String> definitions = new LinkedHashSet<>();
        for (VmEntry registered : host.listVms()) {
            definitions.add(registered.getVmxFileSystemPath());
        }
        // Those that are not registered here (nor perhaps anywhere that can be asked), in the folders of the datastores
        for (EsxiDatastoreEntry datastore : host.listDatastores()) {
            final ShellResult found =
                    files.run("ls -1d " + ShellQuote.quote("/vmfs/volumes/" + datastore.getName()) + "/*/*.vmx");
            if (found.succeeded()) {
                for (String line : found.getStdout().split("\\R")) {
                    if (line.trim().endsWith(".vmx")) {
                        definitions.add(line.trim());
                    }
                }
            }
        }
        final Map<String, String> canonical = new HashMap<>();
        for (String vmx : definitions) {
            final String real = canonical.computeIfAbsent(vmx, path -> {
                try {
                    return files.canonical(path);
                } catch (VSphereException e) {
                    return path;
                }
            });
            if (!seen.add(real) || parent(real).equals(masterFolder)) {
                continue;
            }
            try {
                final VmxFile definition = VmxFile.parse(files.read(vmx));
                if (dependsOn(files, definition, parent(vmx), masterFolder)) {
                    findings.dependents.add(definition.get("displayName", vmx) + " [" + real + "]");
                }
            } catch (VSphereException e) {
                // a definition that cannot be read cannot be a clone that we know of
            }
        }
    }

    private static boolean dependsOn(EsxiDatastoreFiles files, VmxFile definition, String folder, String masterFolder) {
        for (String key : definition.keys()) {
            if (!DISK_FILE.matcher(key.toLowerCase(Locale.ROOT)).matches()) {
                continue;
            }
            final String value = VmxFile.unescape(definition.get(key, ""));
            if (!value.endsWith(".vmdk")) {
                continue;
            }
            String path = value.startsWith("/") ? value : folder + "/" + value;
            for (int depth = 0; depth < MOST_PARENTS; depth++) {
                final String hint;
                try {
                    hint = files.readDescriptor(path).getParentHint();
                } catch (VSphereException e) {
                    break;
                }
                if (hint == null) {
                    break;
                }
                final String parentPath = hint.startsWith("/") ? hint : parent(path) + "/" + hint;
                String real = parentPath;
                try {
                    real = files.canonical(parentPath);
                } catch (VSphereException e) {
                    // the path as it is named, then
                }
                if (parent(real).equals(masterFolder)) {
                    return true;
                }
                path = parentPath;
            }
        }
        return false;
    }
}
