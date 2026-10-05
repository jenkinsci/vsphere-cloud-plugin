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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.jenkinsci.plugins.vsphere.tools.VSphereException;

/**
 * Just enough of an ESXi host to run the backend against: VMs with a power state, an address and snapshots, and
 * the handful of {@code vim-cmd} commands (and {@code cat} of a .vmx) that the backend uses, answered in the way
 * the real thing prints them. It keeps what it was asked, and fails the test on any command it does not know,
 * so that what the backend runs is exactly what the tests say.
 *
 * <p>Commands are split into words the way a POSIX shell does it (single quotes), so that what arrives is what
 * a shell would have given to {@code vim-cmd}: a value that was not quoted properly would show up here as more
 * words than expected.
 */
final class FakeEsxiHost implements EsxiShell {

    static final class FakeVm {
        final int id;
        String name;
        final String datastore;
        final String vmxRelativePath;
        String vmx;
        String power = "Powered off";
        String ip;
        String toolsStatus = "toolsOk";
        /** The id of the resource pool it is in; null for the top one. */
        String pool;

        int nextSnapshotId = 1;
        /** What the last revert / remove of a snapshot was asked as: the words after the id of the VM. */
        String lastSnapshotAction;

        final List<String[]> snapshots = new ArrayList<>(); // name, description, memory, quiesce

        FakeVm(int id, String name, String datastore, String vmxRelativePath, String vmx) {
            this.id = id;
            this.name = name;
            this.datastore = datastore;
            this.vmxRelativePath = vmxRelativePath;
            this.vmx = vmx;
        }
    }

    private final Map<Integer, FakeVm> vms = new LinkedHashMap<>();
    final List<String> commands = new ArrayList<>();
    boolean closed;
    /** What "vmware -v" prints on an ESXi 7 host. */
    String esxiVersion = "VMware ESXi 7.0.3 build-20036589";

    int notFoundExitCode;
    /** The resource pools that were made: name to id. */
    final Map<String, String> pools = new LinkedHashMap<>();
    /** The host does not take what is changed in a .vmsd when it reloads. */
    boolean ignoreVmsd;
    /** The -d type of the last disk made with vmkfstools -c. */
    String lastDiskType;
    /** What esxcli storage filesystem list prints, as seen on an ESXi 7 host. */
    String datastoreTable = datastoreTable(DATASTORE_ROWS);
    /** The short name of the host; null makes esxcli unknown. */
    String hostname = "esxi7";
    /** The port groups of the standard switches; null makes "esxcli" unknown, as on a host that has none. */
    java.util.List<String> portGroups =
            new java.util.ArrayList<>(java.util.List.of("VM Network", "Management Network"));

    // The files of the datastores, for what is done to files: path -> text. Directories are kept apart.
    Map<String, String> files = new LinkedHashMap<>();
    java.util.Set<String> directories = new java.util.TreeSet<>();
    /** Files that cannot be read, as those in use by a VM that is running cannot. */
    java.util.Set<String> locked = new java.util.HashSet<>();
    /** Where a datastore really is: /vmfs/volumes/name is a link to /vmfs/volumes/uuid. */
    Map<String, String> datastoreUuids = new LinkedHashMap<>();

    /** What esxcli storage filesystem list prints on an ESXi 7 host: mount point, name, UUID, mounted, type, size, free. */
    static final String[][] DATASTORE_ROWS = {
        {
            "/vmfs/volumes/5f1a2b3c-aaaaaaaa-bbbb-001122334455",
            "datastore1",
            "5f1a2b3c-aaaaaaaa-bbbb-001122334455",
            "true",
            "VMFS-6",
            "1000204886016",
            "500102443008"
        },
        {
            "/vmfs/volumes/5f1a2b3c-cccccccc-dddd-001122334455",
            "datastore 2",
            "5f1a2b3c-cccccccc-dddd-001122334455",
            "true",
            "VMFS-6",
            "250000000000",
            "100000000000"
        },
        {
            "/vmfs/volumes/6a6a6a6a-11111111-2222-001122334455",
            "nfs-share",
            "6a6a6a6a-11111111-2222-001122334455",
            "true",
            "NFS",
            "2000000000000",
            "1999999999999"
        },
        {
            "/vmfs/volumes/6a6a6a6a-33333333-4444-001122334455",
            "offline",
            "6a6a6a6a-33333333-4444-001122334455",
            "false",
            "NFS",
            "2000000000000",
            "1999999999999"
        },
        {
            "/vmfs/volumes/6b6b6b6b-55555555-6666-001122334455",
            "OSDATA-6b6b6b6b-5555",
            "6b6b6b6b-55555555-6666-001122334455",
            "true",
            "VMFS-L",
            "128580583424",
            "118380036096"
        },
        {
            "/vmfs/volumes/6b6b6b6b-77777777-8888-001122334455",
            "",
            "6b6b6b6b-77777777-8888-001122334455",
            "true",
            "vfat",
            "4293591040",
            "4277207040"
        },
    };

    static String datastoreTable(String[][] rows) {
        final String[] titles = {"Mount Point", "Volume Name", "UUID", "Mounted", "Type", "Size", "Free"};
        final int[] widths = new int[titles.length];
        for (int c = 0; c < titles.length; c++) {
            widths[c] = titles[c].length();
            for (String[] row : rows) {
                widths[c] = Math.max(widths[c], row[c].length());
            }
        }
        final StringBuilder out = new StringBuilder();
        final StringBuilder rule = new StringBuilder();
        for (int c = 0; c < titles.length; c++) {
            final boolean right = c >= 3 && c != 4;
            out.append(String.format(right ? "%" + widths[c] + "s" : "%-" + widths[c] + "s", titles[c]));
            rule.append("-".repeat(widths[c]));
            if (c < titles.length - 1) {
                out.append("  ");
                rule.append("  ");
            }
        }
        out.append('\n').append(rule).append('\n');
        for (String[] row : rows) {
            for (int c = 0; c < titles.length; c++) {
                final boolean right = c >= 3 && c != 4;
                out.append(String.format(right ? "%" + widths[c] + "s" : "%-" + widths[c] + "s", row[c]));
                if (c < titles.length - 1) {
                    out.append("  ");
                }
            }
            out.append('\n');
        }
        return out.toString();
    }

    private int nextVmId = 100;
    private final Map<String, String> failures = new LinkedHashMap<>();

    /** Has this host see the same datastores as the other: the same files, folders, locks and names for them. */
    void shareStorageWith(FakeEsxiHost other) {
        this.files = other.files;
        this.directories = other.directories;
        this.locked = other.locked;
        this.datastoreUuids = other.datastoreUuids;
    }

    /** The host forgets the VM, as when it is unregistered; its files stay. */
    void removeVmRegistration(String name) {
        vms.remove(vmNamed(name).id);
    }

    FakeVm addVm(int id, String name, String datastore, String vmxRelativePath, String vmx) {
        final FakeVm vm = new FakeVm(id, name, datastore, vmxRelativePath, vmx);
        vms.put(id, vm);
        addFile("/vmfs/volumes/" + datastore + "/" + vmxRelativePath, vmx);
        return vm;
    }

    /** Puts a file in a datastore, and the folders that lead to it. */
    void addFile(String path, String content) {
        files.put(path, content);
        addDirectories(parentOf(path));
    }

    private void addDirectories(String directory) {
        String current = directory;
        while (current.startsWith("/vmfs/volumes/") && current.length() > "/vmfs/volumes/".length()) {
            directories.add(current);
            current = parentOf(current);
        }
    }

    private static String parentOf(String path) {
        return path.substring(0, path.lastIndexOf('/'));
    }

    boolean hasFile(String path) {
        return files.containsKey(path);
    }

    /** The text of a file; fails the test if there is none. */
    String file(String path) {
        if (!files.containsKey(path)) {
            throw new AssertionError("No such file: " + path + " (there are " + files.keySet() + ")");
        }
        return files.get(path);
    }

    boolean hasDirectory(String path) {
        return directories.contains(path);
    }

    /** The VM that has the name, or null. */
    FakeVm vmNamed(String name) {
        for (FakeVm vm : vms.values()) {
            if (vm.name.equals(name)) {
                return vm;
            }
        }
        return null;
    }

    FakeVm vm(int id) {
        return vms.get(id);
    }

    /** Makes every command that contains the text fail with this message and exit code 1. */
    void failing(String commandContains, String message) {
        failures.put(commandContains, message);
    }

    boolean ran(String command) {
        return commands.contains(command);
    }

    @Override
    public ShellResult run(String command) throws VSphereException {
        commands.add(command);
        if (closed) {
            throw new VSphereException("The connection is closed");
        }
        if (command.equals("true")) {
            return ok("");
        }
        final ShellResult written = writeFile(command);
        if (written != null) {
            return written;
        }
        final List<String> words = split(command);
        for (Map.Entry<String, String> failure : failures.entrySet()) {
            if (!words.get(0).equals("/bin/vim-cmd") && command.contains(failure.getKey())) {
                return new ShellResult(1, "", failure.getValue());
            }
        }
        final ShellResult onFiles = onFiles(words);
        if (onFiles != null) {
            return onFiles;
        }
        if (words.equals(List.of("esxcli", "network", "vswitch", "standard", "portgroup", "list"))) {
            if (portGroups == null) {
                return new ShellResult(127, "", "esxcli: not found");
            }
            final StringBuilder table = new StringBuilder(
                            "Name                 Virtual Switch  Active Clients  VLAN ID\n")
                    .append("-------------------  --------------  --------------  -------\n");
            for (String group : portGroups) {
                table.append(String.format("%-19s  vSwitch0                       1        0%n", group));
            }
            return ok(table.toString());
        }
        if (words.equals(List.of("esxcli", "storage", "filesystem", "list"))) {
            return ok(datastoreTable);
        }
        if (words.equals(List.of("esxcli", "system", "hostname", "get"))) {
            return hostname == null
                    ? new ShellResult(127, "", "esxcli: not found")
                    : ok("   Domain Name: example.com\n   Fully Qualified Domain Name: " + hostname
                            + ".example.com\n   Host Name: " + hostname + "\n");
        }
        if (words.equals(List.of("hostname"))) {
            return ok(hostname + ".example.com\n");
        }
        if (words.equals(List.of("vmware", "-v"))) {
            return ok(esxiVersion + "\n");
        }
        if (words.get(0).equals("cat") && words.size() == 2) {
            if (words.get(1).equals("/etc/vmware/hostd/pools.xml")) {
                return ok(poolsXml());
            }
            if (files.containsKey(words.get(1))) {
                return ok(files.get(words.get(1)));
            }
            for (FakeVm vm : vms.values()) {
                if (words.get(1).equals("/vmfs/volumes/" + vm.datastore + "/" + vm.vmxRelativePath)) {
                    return ok(vm.vmx);
                }
            }
            return new ShellResult(1, "", "cat: can't open '" + words.get(1) + "': No such file or directory");
        }
        if (!words.get(0).equals("/bin/vim-cmd")) {
            throw new AssertionError("Unexpected command: " + command);
        }
        for (Map.Entry<String, String> failure : failures.entrySet()) {
            if (command.contains(failure.getKey())) {
                return new ShellResult(1, "", failure.getValue());
            }
        }
        final String sub = words.get(1);
        if (sub.equals("vmsvc/getallvms")) {
            return ok(allVms());
        }
        if (sub.equals("solo/registervm")) {
            return register(words.get(2), words.get(3), words.size() > 4 ? words.get(4) : null);
        }
        if (sub.equals("hostsvc/rsrc/create")) {
            // --cpu-min-expandable=true ... ha-root-pool name
            final String name = words.get(words.size() - 1);
            final String id = "pool-" + (pools.size() + 1);
            pools.put(name, id);
            return ok("'vim.ResourcePool:" + id + "'\n");
        }
        if (sub.equals("hostsvc/rsrc/destroy")) {
            pools.values().remove(words.get(2));
            return ok("");
        }
        final FakeVm vm = vms.get(Integer.parseInt(words.get(2)));
        if (vm == null) {
            // as printed by an ESXi 8 host; whether it exits with an error code too is not known, so it does not
            return new ShellResult(notFoundExitCode, notFound(words.get(2)), "");
        }
        switch (sub) {
            case "vmsvc/power.getstate":
                return ok("Retrieved runtime info\n" + vm.power + "\n");
            case "vmsvc/power.on":
                vm.power = "Powered on";
                return ok("Powering on VM:\n");
            case "vmsvc/power.off":
            case "vmsvc/power.shutdown":
                vm.power = "Powered off";
                return ok("");
            case "vmsvc/power.suspend":
                vm.power = "Suspended";
                return ok("");
            case "vmsvc/power.reset":
                return ok("");
            case "vmsvc/get.guest":
                return ok(guest(vm));
            case "vmsvc/snapshot.get":
                return ok(snapshots(vm));
            case "vmsvc/snapshot.create":
                if (words.size() != 7) {
                    throw new AssertionError("snapshot.create got " + words.size() + " words: " + words);
                }
                vm.snapshots.add(new String[] {
                    words.get(3), words.get(4), words.get(5), words.get(6), Integer.toString(vm.nextSnapshotId++)
                });
                return ok("Create Snapshot:\n");
            case "vmsvc/snapshot.revert":
            case "vmsvc/snapshot.remove":
                if (words.size() < 4 || vm.snapshots.stream().noneMatch(sn -> sn[4].equals(words.get(3)))) {
                    return new ShellResult(1, "", "Snapshot " + (words.size() < 4 ? "" : words.get(3)) + " not found");
                }
                vm.lastSnapshotAction = sub.substring("vmsvc/snapshot.".length()) + " "
                        + String.join(" ", words.subList(3, words.size()));
                if (sub.endsWith("remove")) {
                    vm.snapshots.removeIf(sn -> sn[4].equals(words.get(3)));
                } else if (words.get(4).equals("no")) {
                    vm.power = "Powered on";
                }
                return ok("Done:\n" + snapshots(vm));
            case "vmsvc/snapshot.removeall":
                vm.snapshots.clear();
                return ok("");
            case "vmsvc/reload":
                final String text = files.get("/vmfs/volumes/" + vm.datastore + "/" + vm.vmxRelativePath);
                vm.vmx = text;
                final java.util.regex.Matcher shown = java.util.regex.Pattern.compile("(?mi)^displayName = \"(.*)\"$")
                        .matcher(text);
                if (shown.find()) {
                    vm.name = shown.group(1);
                }
                final String vmsd = files.get(
                        "/vmfs/volumes/" + vm.datastore + "/" + vm.vmxRelativePath.replaceAll("\\.vmx$", ".vmsd"));
                if (vmsd != null && !ignoreVmsd) {
                    final VmxFile snapshotFile = VmxFile.parse(vmsd);
                    for (int n = 0; n < 64; n++) {
                        final String uid = snapshotFile.get("snapshot" + n + ".uid");
                        for (String[] snapshot : vm.snapshots) {
                            if (snapshot[4].equals(uid)) {
                                snapshot[0] = VmxFile.unescape(
                                        snapshotFile.get("snapshot" + n + ".displayName", snapshot[0]));
                                snapshot[1] = VmxFile.unescape(snapshotFile.get("snapshot" + n + ".description", ""));
                            }
                        }
                    }
                }
                return ok("");
            case "vmsvc/unregister":
                vms.remove(vm.id);
                return ok("");
            case "vmsvc/destroy":
                // the files of the VM go with it
                final String folder = "/vmfs/volumes/" + vm.datastore + "/"
                        + vm.vmxRelativePath.substring(0, vm.vmxRelativePath.lastIndexOf('/') + 1);
                files.keySet().removeIf(file -> file.startsWith(folder));
                vms.remove(vm.id);
                return ok("Destroying VM\n");
            default:
                throw new AssertionError("Unexpected vim-cmd command: " + command);
        }
    }

    /** The way a host reports a fault: it prints it. */
    static String notFound(String vmId) {
        return String.join(
                "\n",
                "(vim.fault.NotFound) {",
                "   faultCause = (vmodl.MethodFault) null,",
                "   faultMessage = <unset>",
                "   msg = \"Unable to find a VM corresponding to \"" + vmId + "\"\"",
                "}",
                "");
    }

    private static final java.util.regex.Pattern PRINTF_TO_FILE = java.util.regex.Pattern.compile(
            "^printf '%s' (.*) > ('(?:[^']|'\\\\'')*')$", java.util.regex.Pattern.DOTALL);

    /** The one shape of writing a file that the backend uses: printf '%s' 'text' > 'path'. */
    private ShellResult writeFile(String command) {
        final java.util.regex.Matcher m = PRINTF_TO_FILE.matcher(command);
        if (!m.matches()) {
            return null;
        }
        final String text = split("x " + m.group(1)).get(1);
        final String path = split(m.group(2)).get(0);
        if (!directories.contains(parentOf(path))) {
            return new ShellResult(1, "", "sh: can't create " + path + ": nonexistent directory");
        }
        files.put(path, text);
        return ok("");
    }

    private ShellResult onFiles(List<String> words) {
        switch (words.get(0)) {
            case "mkdir":
                // mkdir -p dir
                addDirectories(words.get(2));
                return ok("");
            case "cp":
                if (!files.containsKey(words.get(1))) {
                    return new ShellResult(1, "", "cp: can't stat '" + words.get(1) + "'");
                }
                if (!directories.contains(parentOf(words.get(2)))) {
                    return new ShellResult(1, "", "cp: can't create '" + words.get(2) + "'");
                }
                files.put(words.get(2), files.get(words.get(1)));
                return ok("");
            case "ls":
                if (words.get(1).equals("-1d")) {
                    // ls -1d 'dir'/*/*.vmx: what a shell would have made of the pattern
                    final java.util.regex.Pattern pattern = java.util.regex.Pattern.compile(
                            java.util.regex.Pattern.quote(words.get(2)).replace("*", "\\E[^/]+\\Q"));
                    final java.util.SortedSet<String> found = new java.util.TreeSet<>();
                    for (String file : files.keySet()) {
                        if (pattern.matcher(file).matches()) {
                            found.add(file);
                        }
                    }
                    return found.isEmpty()
                            ? new ShellResult(1, "", "ls: " + words.get(2) + ": No such file or directory")
                            : ok(String.join("\n", found) + "\n");
                }
                // ls -1 dir
                return listDirectory(words.get(2));
            case "test":
                // test -e path
                return new ShellResult(
                        files.containsKey(words.get(2)) || directories.contains(words.get(2)) ? 0 : 1, "", "");
            case "dd":
                // dd if=path of=/dev/null bs=1 count=1
                final String path = words.get(1).substring("if=".length());
                return new ShellResult(files.containsKey(path) && !locked.contains(path) ? 0 : 1, "", "");
            case "readlink":
                // readlink -f path
                return ok(canonical(words.get(2)) + "\n");
            case "mv":
                // mv -f from to
                if (!files.containsKey(words.get(2))) {
                    return new ShellResult(1, "", "mv: can't stat '" + words.get(2) + "'");
                }
                files.put(words.get(3), files.remove(words.get(2)));
                return ok("");
            case "rm":
                if (words.get(1).equals("-f")) {
                    files.remove(words.get(2));
                    return ok("");
                }
                // rm -rf dir
                final String directory = words.get(2);
                files.keySet().removeIf(f -> f.startsWith(directory + "/"));
                directories.removeIf(d -> d.equals(directory) || d.startsWith(directory + "/"));
                return ok("");
            case "vmkfstools":
                if (!words.get(1).equals("-i")) {
                    return vmkfstoolsOnDisk(words);
                }
                // vmkfstools -i source destination -d thin
                final String source = words.get(2);
                final String destination = words.get(3);
                if (!files.containsKey(source)) {
                    return new ShellResult(1, "", "Failed to open disk '" + source + "'");
                }
                final String flat = destination.replace(".vmdk", "-flat.vmdk");
                files.put(
                        destination,
                        "# Disk DescriptorFile\nversion=1\ncreateType=\"vmfs\"\n\n# Extent description\nRW 100 VMFS \""
                                + flat.substring(flat.lastIndexOf('/') + 1)
                                + "\"\n");
                files.put(flat, "COPY OF " + source);
                return ok("Clone: 100% done.\n");
            default:
                return null;
        }
    }

    /** vmkfstools -c SIZE -d TYPE path, -X SIZE path and -U path. */
    private ShellResult vmkfstoolsOnDisk(List<String> words) {
        final String flag = words.get(1);
        final String path = words.get(words.size() - 1);
        final String flat = path.replace(".vmdk", "-flat.vmdk");
        final String name = flat.substring(flat.lastIndexOf('/') + 1);
        switch (flag) {
            case "-c":
            case "-X":
                final String size = words.get(2);
                if (!size.endsWith("K")) {
                    throw new AssertionError("The size should be in K: " + words);
                }
                final long sectors = Long.parseLong(size.substring(0, size.length() - 1)) * 2;
                if (flag.equals("-c")) {
                    if (!directories.contains(parentOf(path))) {
                        return new ShellResult(1, "", "Failed to create virtual disk: no such directory");
                    }
                    lastDiskType = words.get(4);
                } else if (!files.containsKey(path)) {
                    return new ShellResult(1, "", "Failed to open disk '" + path + "'");
                } else if (files.get(path).contains("parentFileNameHint")) {
                    return new ShellResult(1, "", "Failed to extend a disk that has snapshots");
                }
                files.put(
                        path,
                        "# Disk DescriptorFile\nversion=1\ncreateType=\"vmfs\"\n\n# Extent description\nRW " + sectors
                                + " VMFS \"" + name + "\"\n");
                files.put(flat, "DATA");
                return ok(flag.equals("-c") ? "Create: 100% done.\n" : "Grow: 100% done.\n");
            case "-U":
                if (!files.containsKey(path)) {
                    return new ShellResult(1, "", "Failed to open disk '" + path + "'");
                }
                files.remove(path);
                files.remove(flat);
                return ok("Destroy: 100% done.\n");
            default:
                throw new AssertionError("Unexpected vmkfstools command: " + words);
        }
    }

    private ShellResult listDirectory(String directory) {
        if (!directories.contains(directory)) {
            return new ShellResult(1, "", "ls: " + directory + ": No such file or directory");
        }
        final java.util.SortedSet<String> names = new java.util.TreeSet<>();
        for (String file : files.keySet()) {
            if (parentOf(file).equals(directory)) {
                names.add(file.substring(file.lastIndexOf('/') + 1));
            }
        }
        for (String child : directories) {
            if (child.length() > directory.length() && parentOf(child).equals(directory)) {
                names.add(child.substring(child.lastIndexOf('/') + 1));
            }
        }
        return ok(String.join("\n", names) + (names.isEmpty() ? "" : "\n"));
    }

    /** Where a path really is: a datastore is also there by its name, as a link to where it is by its UUID. */
    String canonical(String path) {
        for (Map.Entry<String, String> datastore : datastoreUuids.entrySet()) {
            final String named = "/vmfs/volumes/" + datastore.getKey() + "/";
            if (path.startsWith(named)) {
                return "/vmfs/volumes/" + datastore.getValue() + "/" + path.substring(named.length());
            }
        }
        return path;
    }

    /** pools.xml as an ESXi 8 host writes it: the pools, then an entry for each VM and the pool it is in. */
    private String poolsXml() {
        final StringBuilder xml = new StringBuilder("<ConfigRoot>\n");
        xml.append("  <resourcePool id=\"0000\">\n    <name>Resources</name>\n    <objID>ha-root-pool</objID>\n")
                .append("    <path>host/user</path>\n  </resourcePool>\n");
        int n = 1;
        for (Map.Entry<String, String> pool : pools.entrySet()) {
            xml.append("  <resourcePool id=\"000" + n++ + "\">\n    <name>")
                    .append(pool.getKey().replace("&", "&amp;"))
                    .append("</name>\n    <objID>")
                    .append(pool.getValue())
                    .append("</objID>\n    <path>host/user/")
                    .append(pool.getKey())
                    .append("</path>\n  </resourcePool>\n");
        }
        int m = 0;
        for (FakeVm vm : vms.values()) {
            xml.append("  <vm id=\"000" + m++ + "\">\n    <lastModified>2026-10-04T19:12:28.567498Z</lastModified>\n")
                    .append("    <objID>")
                    .append(vm.id)
                    .append("</objID>\n    <resourcePool>")
                    .append(vm.pool == null ? "ha-root-pool" : vm.pool)
                    .append("</resourcePool>\n  </vm>\n");
        }
        return xml.append("</ConfigRoot>\n").toString();
    }

    private ShellResult register(String vmxPath, String name, String pool) {
        if (!files.containsKey(vmxPath)) {
            return new ShellResult(1, "", "Failed to register: " + vmxPath + " does not exist");
        }
        final String below = vmxPath.substring("/vmfs/volumes/".length());
        final String datastore = below.substring(0, below.indexOf('/'));
        final String relative = below.substring(below.indexOf('/') + 1);
        final int id = nextVmId++;
        if (pool != null && !pool.equals("ha-root-pool") && !pools.containsValue(pool)) {
            return new ShellResult(1, "", "Failed to register: no resource pool " + pool);
        }
        addVm(id, name, datastore, relative, files.get(vmxPath)).pool = pool;
        return ok(id + "\n");
    }

    private String allVms() {
        final StringBuilder out =
                new StringBuilder("Vmid     Name      File      Guest OS      Version   Annotation\n");
        for (FakeVm vm : vms.values()) {
            out.append(vm.id)
                    .append("      ")
                    .append(vm.name)
                    .append("      [")
                    .append(vm.datastore)
                    .append("] ")
                    .append(vm.vmxRelativePath)
                    .append("      otherGuest64      vmx-13\n");
        }
        return out.toString();
    }

    private static String guest(FakeVm vm) {
        final boolean running = vm.power.equals("Powered on");
        final StringBuilder out = new StringBuilder("Guest information:\n(vim.vm.GuestInfo) {\n");
        out.append("   toolsStatus = \"")
                .append(running ? vm.toolsStatus : "toolsNotRunning")
                .append("\",\n");
        if (running && vm.ip != null) {
            out.append("   ipAddress = \"").append(vm.ip).append("\",\n");
        }
        return out.append("}\n").toString();
    }

    private static String snapshots(FakeVm vm) {
        if (vm.snapshots.isEmpty()) {
            return "Get Snapshot:\n";
        }
        final StringBuilder out = new StringBuilder("Get Snapshot:\n|-ROOT\n");
        for (String[] snapshot : vm.snapshots) {
            out.append("--Snapshot Name        : ").append(snapshot[0]).append('\n');
            out.append("--Snapshot Id        : ").append(snapshot[4]).append('\n');
            out.append("--Snapshot Desciption  : ").append(snapshot[1]).append('\n');
            out.append("--Snapshot Created On  : 10/4/2026 18:59:44\n");
            out.append("--Snapshot State       : powered off\n");
        }
        return out.toString();
    }

    private static ShellResult ok(String stdout) {
        return new ShellResult(0, stdout, "");
    }

    /** What a POSIX shell makes of a command line that has nothing but words and single-quoted words. */
    static List<String> split(String command) {
        final List<String> words = new ArrayList<>();
        final StringBuilder word = new StringBuilder();
        boolean inWord = false;
        boolean quoted = false;
        for (int i = 0; i < command.length(); i++) {
            final char c = command.charAt(i);
            if (quoted) {
                if (c == '\'') {
                    quoted = false;
                } else {
                    word.append(c);
                }
            } else if (c == '\'') {
                quoted = true;
                inWord = true;
            } else if (c == '\\' && i + 1 < command.length()) {
                word.append(command.charAt(++i));
                inWord = true;
            } else if (Character.isWhitespace(c)) {
                if (inWord) {
                    words.add(word.toString());
                    word.setLength(0);
                    inWord = false;
                }
            } else if (";&|<>$`()".indexOf(c) >= 0) {
                throw new AssertionError("An unquoted shell metacharacter '" + c + "' in: " + command);
            } else {
                word.append(c);
                inWord = true;
            }
        }
        if (quoted) {
            throw new AssertionError("Unbalanced quote in: " + command);
        }
        if (inWord) {
            words.add(word.toString());
        }
        return words;
    }

    @Override
    public void close() {
        closed = true;
    }
}
