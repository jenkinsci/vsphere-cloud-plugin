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

    // -- what the copies from host to host use: an address, the other hosts, a firewall, processes --

    /** The address that other hosts reach this one at, or null if it has none (then it has no endpoint either). */
    String address;
    /** The user that a session to this host is as, and the port of its SSH server. */
    String endpointUser = "jenkins";

    int endpointPort = 22;
    /** The hosts that this one can reach, by the address that they are reached at. */
    final Map<String, FakeEsxiHost> peers = new LinkedHashMap<>();
    /** The rulesets that the firewall has of its own, and whether they are on. */
    final Map<String, Boolean> rulesets = new java.util.TreeMap<>(Map.of("sshServer", true, "sshClient", false));
    /** The rulesets that are open to all, and the addresses of those that are not. */
    final Map<String, Boolean> allowedAll = new java.util.HashMap<>();

    final Map<String, java.util.Set<String>> allowedIps = new java.util.HashMap<>();
    /** The ports that a listener of nc has, with what is sent to it. */
    final Map<Integer, java.util.concurrent.BlockingQueue<Pending>> listeners =
            new java.util.concurrent.ConcurrentHashMap<>();
    /** Ports that the host will not bind, though nothing is seen on them. */
    final java.util.Set<Integer> unbindable = java.util.concurrent.ConcurrentHashMap.newKeySet();
    /** How many of the next listeners of nc are told that the port is in use. */
    final java.util.concurrent.atomic.AtomicInteger refuseBinds = new java.util.concurrent.atomic.AtomicInteger();
    /** The commands that are running now, which can be ended by killing them. */
    final List<Proc> processes = new java.util.concurrent.CopyOnWriteArrayList<>();
    /** The copy by ssh or nc stops, when it has begun, and stays so until it is killed. */
    volatile boolean stallTransfers;
    /** The ssh server of this host refuses the keys that it is shown, as a host would that does not read them. */
    boolean refuseKeys;

    private static final java.util.concurrent.atomic.AtomicInteger NEXT_PID =
            new java.util.concurrent.atomic.AtomicInteger(1000);

    static final class Proc {
        final int pid = NEXT_PID.incrementAndGet();
        final String command;
        volatile boolean killed;

        Proc(String command) {
            this.command = command;
        }
    }

    /** What a sender of nc leaves for a listener, which says how it went when it has unpacked it. */
    static final class Pending {
        final byte[] data;
        final java.util.concurrent.CompletableFuture<ShellResult> done = new java.util.concurrent.CompletableFuture<>();

        Pending(byte[] data) {
            this.data = data;
        }
    }

    @Override
    public EsxiEndpoint endpoint() {
        return address == null ? null : new EsxiEndpoint(address, endpointPort, endpointUser);
    }

    final List<String> commands = new ArrayList<>();
    boolean closed;
    /** What "vmware -v" prints on an ESXi 7 host. */
    String esxiVersion = "VMware ESXi 7.0.3 build-20036589";

    int notFoundExitCode;
    /** What du says of a file, where it is not the size of what the fake keeps in it (in KB). */
    final Map<String, Long> allocatedKb = new LinkedHashMap<>();
    /** Whether a snapshot also makes what a real one does: an entry in the .vmsd, and a delta that the VM then has. */
    boolean realSnapshots;
    /** The tools that are in /bin, as far as which is asked. */
    final java.util.Set<String> tools = new java.util.HashSet<>(java.util.List.of("pigz", "gzip", "bzip2"));
    /** Volumes this host calls by another name than the files are kept under: its label, to the one they are under. */
    final Map<String, String> volumeAliases = new LinkedHashMap<>();
    /** What the host says of its size and load (vim-cmd hostsvc/hostsummary); a null number is left out. */
    int cpuMhz = 2400;

    int cpuCores = 4;
    long memoryBytes = 16L * 1024 * 1024 * 1024;
    Integer cpuUsageMhz = 300;
    Integer memoryUsageMB = 2000;
    boolean inMaintenanceMode;
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

    {
        for (String directory : new String[] {"/tmp", "/etc", "/etc/ssh", "/etc/vmware", "/etc/vmware/firewall"}) {
            directories.add(directory);
        }
        files.put("/usr/lib/vmware/openssh/bin/ssh-keygen", "");
        files.put("/etc/ssh/ssh_host_rsa_key.pub", "ssh-rsa AAAAFAKEHOSTKEY root@fake\n");
    }

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

    private String unalias(String word) {
        for (Map.Entry<String, String> alias : volumeAliases.entrySet()) {
            final String prefix = "/vmfs/volumes/" + alias.getKey() + "/";
            if (word.startsWith(prefix)) {
                return "/vmfs/volumes/" + alias.getValue() + "/" + word.substring(prefix.length());
            }
        }
        return word;
    }

    private List<String> unaliased(List<String> words) {
        final List<String> mapped = new ArrayList<>();
        for (String word : words) {
            mapped.add(unalias(word));
        }
        return mapped;
    }

    private static final java.util.regex.Pattern PACK = java.util.regex.Pattern.compile(
            "(?s)^\\(tar cf - -C (.+?) \\|\\| echo TAR-FAILED >&2\\)(?: \\| (pigz|gzip|bzip2) -1)?$|^tar cf - -C (.+?) \\|\\| echo TAR-FAILED >&2$");
    private static final java.util.regex.Pattern UNPACK =
            java.util.regex.Pattern.compile("^(?:(pigz|gzip|bzip2) -d \\| )?tar xf - -C (.+)$");

    /** True if the command moves data through its input or output, which only {@link #stream} can run. */
    boolean streams(String command) {
        return command.startsWith("vmkfstools ")
                || command.contains(" | ssh ")
                || command.contains(" | nc -w ")
                || NC_LISTEN.matcher(command).matches()
                || PACK.matcher(command).matches()
                || UNPACK.matcher(command).matches()
                || command.equals(FakeEsxiSshServer.HANG);
    }

    /**
     * What a tar that packs files, and one that unpacks them, make of each other: the files are sent as a header
     * that says how they were compressed, and then their names, sizes and contents. A tool that is not the one that
     * packed them cannot unpack them, as with the real ones.
     */
    @Override
    public ShellResult stream(
            String command, java.io.InputStream stdin, java.io.OutputStream stdout, int idleTimeoutSeconds)
            throws VSphereException {
        if (command.startsWith("vmkfstools ")) {
            // not a stream, but a command that takes long: what it prints is what it prints
            final ShellResult result = run(command);
            try {
                if (stdout != null) {
                    stdout.write(result.getStdout().getBytes(java.nio.charset.StandardCharsets.UTF_8));
                }
            } catch (java.io.IOException e) {
                return new ShellResult(1, "", e.getMessage());
            }
            return new ShellResult(result.getExitCode(), "", result.getStderr());
        }
        commands.add(command);
        if (command.contains(" | ssh ")) {
            return streamSsh(command, idleTimeoutSeconds);
        }
        if (command.contains(" | nc -w ")) {
            return streamNcSend(command, idleTimeoutSeconds);
        }
        final java.util.regex.Matcher listen = NC_LISTEN.matcher(command);
        if (listen.matches()) {
            return streamNcListen(command, Integer.parseInt(listen.group(2)), listen.group(3), idleTimeoutSeconds);
        }
        try {
            for (Map.Entry<String, String> failure : failures.entrySet()) {
                if (command.contains(failure.getKey())) {
                    return new ShellResult(
                            1, "", failure.getValue() + (command.contains("tar cf") ? "\nTAR-FAILED\n" : ""));
                }
            }
            final java.util.regex.Matcher pack = PACK.matcher(command);
            if (pack.matches()) {
                final List<String> words = unaliased(split(pack.group(1) != null ? pack.group(1) : pack.group(3)));
                final String tool = pack.group(2) == null ? "" : pack.group(2);
                final java.io.DataOutputStream out = new java.io.DataOutputStream(stdout);
                final List<String> names = words.subList(1, words.size());
                for (String name : names) {
                    if (!files.containsKey(words.get(0) + "/" + name)) {
                        return new ShellResult(1, "", "tar: " + name + ": No such file or directory\nTAR-FAILED\n");
                    }
                }
                out.writeUTF(tool);
                out.writeInt(names.size());
                for (String name : names) {
                    final byte[] data =
                            files.get(words.get(0) + "/" + name).getBytes(java.nio.charset.StandardCharsets.ISO_8859_1);
                    out.writeUTF(name);
                    out.writeInt(data.length);
                    out.write(data);
                }
                out.flush();
                return ok("");
            }
            final java.util.regex.Matcher unpack = UNPACK.matcher(command);
            if (unpack.matches()) {
                final String directory = unalias(split(unpack.group(2)).get(0));
                final java.io.DataInputStream in = new java.io.DataInputStream(stdin);
                final String tool = in.readUTF();
                final String wanted = unpack.group(1) == null ? "" : unpack.group(1);
                if (!tool.equals(wanted)) {
                    return new ShellResult(
                            1, "", (wanted.isEmpty() ? "tar" : wanted) + ": not in the format that it reads");
                }
                final int count = in.readInt();
                for (int i = 0; i < count; i++) {
                    final String name = in.readUTF();
                    final byte[] data = new byte[in.readInt()];
                    in.readFully(data);
                    synchronized (this) {
                        if (!directories.contains(directory)) {
                            return new ShellResult(1, "", "tar: can't create " + directory + "/" + name);
                        }
                        files.put(
                                directory + "/" + name, new String(data, java.nio.charset.StandardCharsets.ISO_8859_1));
                    }
                }
                return ok("");
            }
            if (command.equals(FakeEsxiSshServer.HANG)) {
                Thread.sleep(30_000);
                return ok("");
            }
            throw new AssertionError("Unexpected command to stream: " + command);
        } catch (java.io.IOException | InterruptedException e) {
            return new ShellResult(1, "", "tar: " + e.getMessage());
        }
    }

    /**
     * What taking a snapshot does to the files: its disks, as they are, are what the snapshot froze (the .vmsd says
     * which they are), and each gets a delta that is empty, which the VM writes to from then on.
     */
    private void writeSnapshotFiles(FakeVm vm, String[] snapshot) {
        final String vmxPath = "/vmfs/volumes/" + vm.datastore + "/" + vm.vmxRelativePath;
        final String directory = vmxPath.substring(0, vmxPath.lastIndexOf('/'));
        final String vmsdPath = vmxPath.replaceAll("\\.vmx$", ".vmsd");
        final VmxFile vmx = VmxFile.parse(files.get(vmxPath));
        final VmxFile vmsd = VmxFile.parse(files.getOrDefault(vmsdPath, ""));
        int entry = 0;
        while (vmsd.get("snapshot" + entry + ".uid") != null) {
            entry++;
        }
        vmsd.put("snapshot" + entry + ".uid", snapshot[4]);
        vmsd.put("snapshot" + entry + ".displayName", snapshot[0]);
        int disk = 0;
        for (String key : new ArrayList<>(vmx.keys())) {
            final String lower = key.toLowerCase();
            if (!lower.matches("(scsi|sata|ide|nvme)\\d+:\\d+\\.filename")
                    || !vmx.get(key).endsWith(".vmdk")) {
                continue;
            }
            final String prefix = key.substring(0, key.indexOf('.'));
            final String current = vmx.get(key);
            vmsd.put("snapshot" + entry + ".disk" + disk + ".fileName", current);
            vmsd.put("snapshot" + entry + ".disk" + disk + ".node", prefix);
            disk++;
            final String base =
                    current.substring(0, current.length() - ".vmdk".length()).replaceAll("-\\d{6}$", "");
            final String delta = base + String.format("-%06d", Integer.parseInt(snapshot[4]));
            files.put(
                    directory + "/" + delta + ".vmdk",
                    "# Disk DescriptorFile\nversion=1\nCID=5555666" + snapshot[4] + "\nparentCID=ffffffff\n"
                            + "createType=\"seSparse\"\nparentFileNameHint=\"" + current + "\"\n\n"
                            + "# Extent description\nRW 100 SESPARSE \"" + delta + "-sesparse.vmdk\"\n");
            files.put(directory + "/" + delta + "-sesparse.vmdk", "HEAD");
            vmx.put(key, delta + ".vmdk");
        }
        vmsd.put("snapshot.current", snapshot[4]);
        vmsd.put("snapshot.numSnapshots", Integer.toString(entry + 1));
        files.put(vmxPath, vmx.toString());
        files.put(vmsdPath, vmsd.toString());
        vm.vmx = vmx.toString();
    }

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
        if (!directory.startsWith("/vmfs/volumes/")) {
            directories.add(directory);
            return;
        }
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
    public synchronized ShellResult run(String command) throws VSphereException {
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
        if (command.startsWith("ps -c | grep ")) {
            return listProcesses(command);
        }
        final List<String> words = unaliased(split(command));
        final ShellResult onHost = onTransferTools(words);
        if (onHost != null) {
            return onHost;
        }
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
        if (words.size() == 3 && words.get(0).equals("du") && words.get(1).equals("-k")) {
            final String text = files.get(words.get(2));
            if (text == null) {
                return new ShellResult(1, "", "du: " + words.get(2) + ": No such file or directory");
            }
            final long kb = allocatedKb.containsKey(words.get(2))
                    ? allocatedKb.get(words.get(2))
                    : Math.max(1, (text.length() + 1023) / 1024);
            return ok(kb + "\t" + words.get(2) + "\n");
        }
        if (words.size() == 2 && words.get(0).equals("cksum")) {
            final String text = files.get(words.get(1));
            if (text == null) {
                return new ShellResult(1, "", "cksum: " + words.get(1) + ": No such file or directory");
            }
            final java.util.zip.CRC32 crc = new java.util.zip.CRC32();
            crc.update(text.getBytes(java.nio.charset.StandardCharsets.ISO_8859_1));
            return ok(crc.getValue() + " " + text.length() + " " + words.get(1) + "\n");
        }
        if (words.size() == 2 && words.get(0).equals("which")) {
            return tools.contains(words.get(1)) ? ok("/bin/" + words.get(1) + "\n") : new ShellResult(1, "", "");
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
        if (sub.equals("hostsvc/hostsummary")) {
            return ok(
                    "(vim.host.Summary) {\n   host = 'vim.HostSystem:ha-host',\n   hardware = (vim.host.Hardware.Summary) {\n"
                            + "      vendor = \"Dell Inc.\",\n      memorySize = " + memoryBytes
                            + ",\n      cpuModel = \"Intel(R) Xeon(R)\",\n"
                            + "      cpuMhz = " + cpuMhz + ",\n      numCpuPkgs = 1,\n      numCpuCores = " + cpuCores
                            + ",\n"
                            + "      numCpuThreads = " + cpuCores * 2
                            + ",\n   },\n   runtime = (vim.host.RuntimeInfo) {\n"
                            + "      connectionState = \"connected\",\n      inMaintenanceMode = " + inMaintenanceMode
                            + ",\n   },\n"
                            + "   quickStats = (vim.host.Summary.QuickStats) {\n"
                            + (cpuUsageMhz == null ? "" : "      overallCpuUsage = " + cpuUsageMhz + ",\n")
                            + (memoryUsageMB == null ? "" : "      overallMemoryUsage = " + memoryUsageMB + ",\n")
                            + "      uptime = 12345\n   },\n}\n");
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
                if (realSnapshots) {
                    writeSnapshotFiles(vm, vm.snapshots.get(vm.snapshots.size() - 1));
                }
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
        final String path = unalias(split(m.group(2)).get(0));
        if (!directories.contains(parentOf(path))) {
            return new ShellResult(1, "", "sh: can't create " + path + ": nonexistent directory");
        }
        files.put(path, text);
        return ok("");
    }

    private ShellResult onFiles(List<String> words) {
        switch (words.get(0)) {
            case "mkdir":
                if (!words.get(1).equals("-p")) {
                    // mkdir dir: not where it is already
                    if (directories.contains(words.get(1))) {
                        return new ShellResult(
                                1, "", "mkdir: can't create directory '" + words.get(1) + "': File exists");
                    }
                    addDirectories(words.get(1));
                    return ok("");
                }
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
                if (words.get(1).equals("-lnA") && directories.contains(words.get(2))) {
                    // ls -lnA dir: the files in it
                    final StringBuilder listing = new StringBuilder("total 0\n");
                    for (Map.Entry<String, String> file : files.entrySet()) {
                        if (parentOf(file.getKey()).equals(words.get(2))) {
                            listing.append("-rw-r--r--    1 0        0        ")
                                    .append(String.format(
                                            "%11d", file.getValue().length()))
                                    .append(" Oct  5 06:51 ")
                                    .append(file.getKey().substring(words.get(2).length() + 1))
                                    .append('\n');
                        }
                    }
                    return ok(listing.toString());
                }
                if (words.get(1).equals("-ln")) {
                    // ls -ln path...: a line for each, as busybox prints them
                    final StringBuilder listing = new StringBuilder();
                    final StringBuilder missing = new StringBuilder();
                    for (String path : words.subList(2, words.size())) {
                        if (files.containsKey(path)) {
                            listing.append("-rw-r--r--    1 0        0        ")
                                    .append(String.format(
                                            "%11d", files.get(path).length()))
                                    .append(" Oct  5 06:51 ")
                                    .append(path)
                                    .append('\n');
                        } else {
                            missing.append("ls: ").append(path).append(": No such file or directory\n");
                        }
                    }
                    return new ShellResult(missing.length() == 0 ? 0 : 1, listing.toString(), missing.toString());
                }
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
                final String path = unalias(words.get(1).substring("if=".length()));
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
                // vmkfstools -i source destination -d thin   or   vmkfstools -i source -d 2gbsparse destination
                final String source = words.get(2);
                final int type = words.indexOf("-d");
                final String format = type < 0 ? "thin" : words.get(type + 1);
                final String destination = words.get(3).equals("-d") ? words.get(5) : words.get(3);
                if (!files.containsKey(source)) {
                    return new ShellResult(1, "", "Failed to open disk '" + source + "'");
                }
                final String flat = destination.replace(".vmdk", "-flat.vmdk");
                final String sparse = destination.replace(".vmdk", "-s001.vmdk");
                final String sourceDescriptor = files.get(source);
                final String sourceDirectory = source.substring(0, source.lastIndexOf('/'));
                final java.util.regex.Matcher extent = java.util.regex.Pattern.compile("(?m)^RW \\d+ \\w+ \"([^\"]+)\"")
                        .matcher(sourceDescriptor);
                final String extentFile = extent.find() ? sourceDirectory + "/" + extent.group(1) : null;
                final String content = extentFile != null && files.containsKey(extentFile)
                        ? files.get(extentFile)
                        : "COPY OF " + source;
                if (format.equals("2gbsparse")) {
                    files.put(
                            destination,
                            "# Disk DescriptorFile\nversion=1\nCID=11112222\ncreateType=\"twoGbMaxExtentSparse\"\n\n"
                                    + "# Extent description\nRW 100 SPARSE \""
                                    + sparse.substring(sparse.lastIndexOf('/') + 1)
                                    + "\"\n");
                    files.put(sparse, "SPARSE:" + (content.startsWith("SPARSE:") ? content.substring(7) : content));
                    return ok("Destination disk format: sparse with 2GB maximum extent size\nClone: 100% done.\n");
                }
                files.put(
                        destination,
                        "# Disk DescriptorFile\nversion=1\nCID=33334444\ncreateType=\"vmfs\"\n\n# Extent description\nRW 100 VMFS \""
                                + flat.substring(flat.lastIndexOf('/') + 1)
                                + "\"\n");
                // an import of a sparse export brings back what was in it; a copy of any other disk is "COPY OF" it
                files.put(
                        flat,
                        sourceDescriptor.contains("twoGbMaxExtentSparse") && content.startsWith("SPARSE:")
                                ? content.substring(7)
                                : "COPY OF " + source);
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

    // -- ssh-keygen, the firewall, processes, ssh and nc between the fakes --

    private static final java.util.regex.Pattern NC_LISTEN =
            java.util.regex.Pattern.compile("^nc -d -l -w (\\d+) (\\d+) \\| (.*)$", java.util.regex.Pattern.DOTALL);

    private ShellResult onTransferTools(List<String> words) {
        final String tool = words.get(0);
        if (tool.endsWith("/ssh-keygen")) {
            // ssh-keygen -q -t ecdsa -b 256 -N '' -C comment -f path
            final String path = words.get(words.indexOf("-f") + 1);
            final String comment = words.get(words.indexOf("-C") + 1);
            if (!directories.contains(parentOf(path))) {
                return new ShellResult(1, "", "Saving key \"" + path + "\" failed: No such file or directory");
            }
            final String blob = "AAAAFAKEKEY" + path.hashCode();
            files.put(path, "-----BEGIN OPENSSH PRIVATE KEY-----\n" + blob + "\n");
            files.put(path + ".pub", "ecdsa-sha2-nistp256 " + blob + " " + comment + "\n");
            return ok("");
        }
        switch (tool) {
            case "chmod":
                return ok("");
            case "touch":
                files.putIfAbsent(words.get(1), "");
                return ok("");
            case "rmdir":
                if (!directories.contains(words.get(1))) {
                    return new ShellResult(1, "", "rmdir: '" + words.get(1) + "': No such file or directory");
                }
                for (String file : files.keySet()) {
                    if (parentOf(file).equals(words.get(1))) {
                        return new ShellResult(1, "", "rmdir: '" + words.get(1) + "': Directory not empty");
                    }
                }
                directories.remove(words.get(1));
                return ok("");
            case "kill":
                for (String pid : words.subList(1, words.size())) {
                    for (Proc proc : processes) {
                        if (Integer.toString(proc.pid).equals(pid)) {
                            proc.killed = true;
                        }
                    }
                }
                return ok("");
            case "esxcli":
                return onFirewall(words);
            default:
                return null;
        }
    }

    private ShellResult listProcesses(String command) {
        // ps -c | grep pattern | grep -v grep
        final String text = command.substring("ps -c | grep ".length());
        final String pattern = unalias(
                split(text.substring(0, text.indexOf(" | grep -v grep"))).get(0));
        final StringBuilder lines = new StringBuilder();
        for (Proc proc : processes) {
            if (proc.command.contains(pattern)) {
                lines.append(String.format("%d  %d  sh      sh -c %s%n", proc.pid, proc.pid, proc.command));
            }
        }
        return new ShellResult(lines.length() == 0 ? 1 : 0, lines.toString(), "");
    }

    /** The names of the rulesets that the files in the firewall folder define. */
    private java.util.Set<String> definedRulesets() {
        final java.util.Set<String> names = new java.util.TreeSet<>(rulesets.keySet());
        for (Map.Entry<String, String> file : files.entrySet()) {
            if (file.getKey().startsWith("/etc/vmware/firewall/")
                    && file.getKey().endsWith(".xml")) {
                final java.util.regex.Matcher id =
                        java.util.regex.Pattern.compile("<id>([^<]+)</id>").matcher(file.getValue());
                while (id.find()) {
                    names.add(id.group(1));
                }
            }
        }
        return names;
    }

    private ShellResult onFirewall(List<String> words) {
        if (words.size() >= 4 && words.get(1).equals("network") && words.get(2).equals("firewall")) {
            final String what = String.join(" ", words.subList(3, words.size()));
            if (what.equals("refresh")) {
                return ok("true\n");
            }
            if (what.equals("ruleset list")) {
                final StringBuilder table = new StringBuilder("Name                         Enabled\n")
                        .append("---------------------------  -------\n");
                for (String name : definedRulesets()) {
                    table.append(String.format("%-27s  %s%n", name, rulesets.getOrDefault(name, false)));
                }
                return ok(table.toString());
            }
            if (words.get(3).equals("ruleset")
                    && words.size() >= 7
                    && words.get(4).equals("set")) {
                // esxcli network firewall ruleset set -r name -e true | --allowed-all true
                final String name = words.get(6);
                if (!definedRulesets().contains(name)) {
                    return new ShellResult(1, "", "Unable to find a ruleset named " + name);
                }
                if (words.get(7).equals("-e")) {
                    rulesets.put(name, Boolean.parseBoolean(words.get(8)));
                } else {
                    allowedAll.put(name, Boolean.parseBoolean(words.get(8)));
                }
                return ok("");
            }
            if (words.get(3).equals("ruleset") && words.get(4).equals("allowedip")) {
                // esxcli network firewall ruleset allowedip add|remove -r name -i ip
                final String name = words.get(7);
                if (!definedRulesets().contains(name)) {
                    return new ShellResult(1, "", "Unable to find a ruleset named " + name);
                }
                if (allowedAll.getOrDefault(name, true)) {
                    return new ShellResult(1, "", "Couldn't update allowed ip list when allowed-all flag is true.");
                }
                final java.util.Set<String> ips = allowedIps.computeIfAbsent(name, k -> new java.util.TreeSet<>());
                if (words.get(5).equals("add")) {
                    ips.add(words.get(9));
                } else {
                    ips.remove(words.get(9));
                }
                return ok("");
            }
        }
        if (words.size() == 5
                && words.get(1).equals("network")
                && words.get(2).equals("ip")
                && words.get(3).equals("connection")
                && words.get(4).equals("list")) {
            final StringBuilder table =
                    new StringBuilder("Proto  Recv Q  Send Q  Local Address  Foreign Address  State\n");
            for (Integer port : listeners.keySet()) {
                table.append("tcp         0       0  0.0.0.0:" + port + "      0.0.0.0:0          LISTEN         "
                        + (1000 + port % 1000) + "  newreno  nc\n");
            }
            return ok(table.toString());
        }
        return null;
    }

    /** Whether the firewall lets the traffic in or out: its ruleset has a rule for the port, and is open to the peer. */
    boolean allows(String direction, int port, String peer) {
        final String name = "jenkinsXfer";
        if (!rulesets.getOrDefault(name, false)) {
            return false;
        }
        final String xml = files.getOrDefault("/etc/vmware/firewall/jenkins-xfer.xml", "");
        if (!xml.contains("<direction>" + direction
                + "</direction><protocol>tcp</protocol><porttype>dst</porttype><port>" + port + "</port>")) {
            return false;
        }
        return allowedAll.getOrDefault(name, true)
                || allowedIps.getOrDefault(name, java.util.Set.of()).contains(peer);
    }

    private ShellResult streamSsh(String command, int idleSeconds) {
        final int at = command.indexOf(" | ssh ");
        final List<String> ssh = split(command.substring(at + 3));
        final String packCommand = command.substring(0, at);
        final Proc proc = new Proc(command);
        processes.add(proc);
        try {
            String keyFile = null;
            String known = null;
            String destination = null;
            for (int i = 0; i < ssh.size(); i++) {
                if (ssh.get(i).equals("-i")) {
                    keyFile = ssh.get(i + 1);
                } else if (ssh.get(i).startsWith("UserKnownHostsFile=")) {
                    known = ssh.get(i).substring("UserKnownHostsFile=".length());
                } else if (ssh.get(i).contains("@")) {
                    destination = ssh.get(i);
                }
            }
            final String user = destination.substring(0, destination.indexOf('@'));
            final String host = destination.substring(destination.indexOf('@') + 1);
            final java.io.ByteArrayOutputStream packed = new java.io.ByteArrayOutputStream();
            final ShellResult reading = stream(packCommand, null, packed, idleSeconds);
            if (!rulesets.getOrDefault("sshClient", false)) {
                return new ShellResult(255, "", "ssh: connect to host " + host + " port 22: Connection timed out");
            }
            final FakeEsxiHost peer = peers.get(host);
            if (peer == null) {
                return new ShellResult(255, "", "ssh: Could not resolve hostname " + host);
            }
            final String hostKey = peer.files.get("/etc/ssh/ssh_host_rsa_key.pub");
            final String knownText = files.get(known);
            if (knownText == null
                    || !knownText.contains(hostKey.trim().split("\\s+")[0] + " "
                            + hostKey.trim().split("\\s+")[1])) {
                return new ShellResult(255, "", "Host key verification failed.");
            }
            final String blob = files.get(keyFile + ".pub").trim().split("\\s+")[1];
            final String authorized = peer.files.getOrDefault("/etc/ssh/keys-" + user + "/authorized_keys", "");
            String forced = null;
            for (String line : authorized.split("\\R")) {
                if (line.contains(blob)) {
                    final java.util.regex.Matcher m = java.util.regex.Pattern.compile(
                                    "^restrict,command=\"(.*)\" ecdsa")
                            .matcher(line);
                    if (m.find()) {
                        forced = m.group(1);
                    }
                }
            }
            if (forced == null || peer.refuseKeys) {
                return new ShellResult(255, "", user + "@" + host + ": Permission denied (publickey).");
            }
            if (stallTransfers) {
                return stall(proc);
            }
            final ShellResult unpacked =
                    peer.stream(forced, new java.io.ByteArrayInputStream(packed.toByteArray()), null, idleSeconds);
            return new ShellResult(unpacked.getExitCode(), "", reading.getStderr() + unpacked.getStderr());
        } catch (VSphereException e) {
            return new ShellResult(255, "", e.getMessage());
        } finally {
            processes.remove(proc);
        }
    }

    private ShellResult stall(Proc proc) {
        final long end = System.nanoTime() + 30_000_000_000L;
        while (!proc.killed && System.nanoTime() < end) {
            try {
                Thread.sleep(20);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }
        return new ShellResult(143, "", "Terminated");
    }

    private ShellResult streamNcListen(String command, int port, String unpack, int idleSeconds) {
        if (unbindable.contains(port)
                || listeners.containsKey(port)
                || refuseBinds.getAndUpdate(left -> left > 0 ? left - 1 : 0) > 0) {
            return new ShellResult(1, "", "nc: Address already in use");
        }
        final Proc proc = new Proc(command);
        processes.add(proc);
        final java.util.concurrent.BlockingQueue<Pending> inbox = new java.util.concurrent.LinkedBlockingQueue<>();
        listeners.put(port, inbox);
        try {
            Pending pending = null;
            final long end = System.nanoTime() + 60_000_000_000L;
            while (pending == null && !proc.killed && System.nanoTime() < end) {
                pending = inbox.poll(20, java.util.concurrent.TimeUnit.MILLISECONDS);
            }
            if (pending == null) {
                return new ShellResult(143, "", "Terminated");
            }
            final ShellResult unpacked =
                    stream(unpack, new java.io.ByteArrayInputStream(pending.data), null, idleSeconds);
            pending.done.complete(unpacked);
            return unpacked;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return new ShellResult(1, "", "interrupted");
        } catch (VSphereException e) {
            return new ShellResult(1, "", String.valueOf(e.getMessage()));
        } finally {
            listeners.remove(port);
            processes.remove(proc);
        }
    }

    private ShellResult streamNcSend(String command, int idleSeconds) {
        final int at = command.indexOf(" | nc -w ");
        final List<String> nc = split(command.substring(at + 3));
        final String packCommand = command.substring(0, at);
        final Proc proc = new Proc(command);
        processes.add(proc);
        try {
            // nc -w N [-s IP] host port
            final int port = Integer.parseInt(nc.get(nc.size() - 1));
            final String host = nc.get(nc.size() - 2);
            final String from = nc.contains("-s") ? nc.get(nc.indexOf("-s") + 1) : address;
            final java.io.ByteArrayOutputStream packed = new java.io.ByteArrayOutputStream();
            final ShellResult reading = stream(packCommand, null, packed, idleSeconds);
            final FakeEsxiHost peer = peers.get(host);
            if (peer == null || !allows("outbound", port, host) || !peer.allows("inbound", port, from)) {
                return new ShellResult(1, "", "nc: connect to " + host + " port " + port + " (tcp) timed out");
            }
            final java.util.concurrent.BlockingQueue<Pending> inbox = peer.listeners.get(port);
            if (inbox == null) {
                return new ShellResult(
                        1, "", "nc: connect to " + host + " port " + port + " (tcp) failed: Connection refused");
            }
            if (stallTransfers) {
                return stall(proc);
            }
            final Pending pending = new Pending(packed.toByteArray());
            inbox.add(pending);
            pending.done.get(60, java.util.concurrent.TimeUnit.SECONDS);
            // as with the real one, what the listener makes of it is for the listener to say
            return new ShellResult(0, "", reading.getStderr());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return new ShellResult(1, "", "interrupted");
        } catch (java.util.concurrent.ExecutionException | java.util.concurrent.TimeoutException | VSphereException e) {
            return new ShellResult(1, "", String.valueOf(e.getMessage()));
        } finally {
            processes.remove(proc);
        }
    }

    @Override
    public void close() {
        closed = true;
    }
}
