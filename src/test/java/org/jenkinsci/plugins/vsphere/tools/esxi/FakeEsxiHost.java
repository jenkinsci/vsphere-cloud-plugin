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
        final String name;
        final String datastore;
        final String vmxRelativePath;
        String vmx;
        String power = "Powered off";
        String ip;
        String toolsStatus = "toolsOk";
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
    private final Map<String, String> failures = new LinkedHashMap<>();

    FakeVm addVm(int id, String name, String datastore, String vmxRelativePath, String vmx) {
        final FakeVm vm = new FakeVm(id, name, datastore, vmxRelativePath, vmx);
        vms.put(id, vm);
        return vm;
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
        final List<String> words = split(command);
        if (words.get(0).equals("cat") && words.size() == 2) {
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
        final FakeVm vm = vms.get(Integer.parseInt(words.get(2)));
        if (vm == null) {
            return new ShellResult(1, "", "Unable to find a VM corresponding to \"" + words.get(2) + "\"");
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
                vm.snapshots.add(new String[] {words.get(3), words.get(4), words.get(5), words.get(6)});
                return ok("Create Snapshot:\n");
            case "vmsvc/snapshot.removeall":
                vm.snapshots.clear();
                return ok("");
            case "vmsvc/destroy":
                vms.remove(vm.id);
                return ok("Destroying VM\n");
            default:
                throw new AssertionError("Unexpected vim-cmd command: " + command);
        }
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
        int id = 1;
        for (String[] snapshot : vm.snapshots) {
            out.append("--Snapshot Name        : ").append(snapshot[0]).append('\n');
            out.append("--Snapshot Id        : ").append(id++).append('\n');
            out.append("--Snapshot Desciption  : ").append(snapshot[1]).append('\n');
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
