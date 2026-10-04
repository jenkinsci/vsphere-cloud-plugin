package org.jenkinsci.plugins.vsphere.tools.esxi;

import com.vmware.vim25.ManagedObjectReference;
import com.vmware.vim25.VirtualMachinePowerState;
import com.vmware.vim25.VirtualMachineSnapshotTree;
import com.vmware.vim25.VirtualMachineToolsStatus;
import edu.umd.cs.findbugs.annotations.CheckForNull;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Reads what {@code vim-cmd} on an ESXi host prints. The formats are those of the host's management agent and
 * are not documented as an interface, so every parser is lenient about what it does not need and returns
 * "unknown" (null or empty) rather than failing on what it does not understand.
 */
public final class VimCmdParsers {

    // "12     my vm name     [datastore1] my vm name/my vm name.vmx     ubuntu64Guest     vmx-13     annotation"
    private static final Pattern GETALLVMS_LINE = Pattern.compile(
            "^(\\d+)\\s+(.+?)\\s+(\\[[^\\]]*\\]\\s*.*?\\.vmx)(?:\\s+(\\S+))?(?:\\s+(vmx-\\d+))?(?:\\s+(.*))?$");

    private static final Pattern GUEST_IP = Pattern.compile("^\\s*ipAddress\\s*=\\s*\"([^\"]+)\"", Pattern.MULTILINE);
    private static final Pattern GUEST_TOOLS_STATUS =
            Pattern.compile("^\\s*toolsStatus\\s*=\\s*\"?(\\w+)\"?", Pattern.MULTILINE);

    private static final Pattern SNAPSHOT_FIELD = Pattern.compile("^(.*?)--Snapshot (\\w[\\w ]*?)\\s*:\\s?(.*)$");

    private VimCmdParsers() {}

    /** The registered VMs, in the order the host lists them. The header line and anything odd are skipped. */
    public static List<VmEntry> parseGetAllVms(@CheckForNull String output) {
        final List<VmEntry> vms = new ArrayList<>();
        if (output == null) {
            return vms;
        }
        for (String line : output.split("\\R")) {
            final Matcher m = GETALLVMS_LINE.matcher(line.trim());
            if (!m.matches()) {
                continue;
            }
            vms.add(new VmEntry(
                    Integer.parseInt(m.group(1)),
                    m.group(2).trim(),
                    m.group(3).trim(),
                    m.group(4),
                    m.group(5),
                    m.group(6) == null ? "" : m.group(6).trim()));
        }
        return vms;
    }

    /** From {@code vim-cmd vmsvc/power.getstate}: "Powered on", "Powered off" or "Suspended". */
    public static @CheckForNull VirtualMachinePowerState parsePowerState(@CheckForNull String output) {
        if (output == null) {
            return null;
        }
        final String text = output.toLowerCase();
        if (text.contains("powered on")) {
            return VirtualMachinePowerState.poweredOn;
        }
        if (text.contains("powered off")) {
            return VirtualMachinePowerState.poweredOff;
        }
        if (text.contains("suspended")) {
            return VirtualMachinePowerState.suspended;
        }
        return null;
    }

    /**
     * From {@code vim-cmd vmsvc/get.guest}: the address that the guest reports for itself, if it has one. The
     * per-adapter lists, written as {@code ipAddress = (string) [ ... ]}, are not looked at.
     */
    public static @CheckForNull String parseGuestIp(@CheckForNull String output) {
        if (output == null) {
            return null;
        }
        final Matcher m = GUEST_IP.matcher(output);
        return m.find() ? m.group(1) : null;
    }

    /** From {@code vim-cmd vmsvc/get.guest}: what is known of the VMware Tools in the guest. */
    public static @CheckForNull VirtualMachineToolsStatus parseToolsStatus(@CheckForNull String output) {
        if (output == null) {
            return null;
        }
        final Matcher m = GUEST_TOOLS_STATUS.matcher(output);
        if (!m.find()) {
            return null;
        }
        try {
            return VirtualMachineToolsStatus.valueOf(m.group(1));
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    /**
     * From {@code vim-cmd vmsvc/snapshot.get}, which prints one block of {@code --Snapshot Name / Id /
     * Desciption / Created On / State} lines for each snapshot, the children of a snapshot being indented
     * further than it. The tree is rebuilt from the indentation.
     */
    public static List<VirtualMachineSnapshotTree> parseSnapshotTree(@CheckForNull String output) {
        final List<VirtualMachineSnapshotTree> roots = new ArrayList<>();
        if (output == null) {
            return roots;
        }
        final List<VirtualMachineSnapshotTree> stack = new ArrayList<>();
        final List<Integer> indents = new ArrayList<>();
        VirtualMachineSnapshotTree current = null;
        for (String line : output.split("\\R")) {
            final Matcher m = SNAPSHOT_FIELD.matcher(line);
            if (!m.matches()) {
                continue;
            }
            final String field = m.group(2).trim().toLowerCase();
            final String value = m.group(3).trim();
            if (field.equals("name")) {
                final int indent = m.group(1).length();
                current = new VirtualMachineSnapshotTree();
                current.setName(value);
                current.setDescription("");
                while (!indents.isEmpty() && indents.get(indents.size() - 1) >= indent) {
                    indents.remove(indents.size() - 1);
                    stack.remove(stack.size() - 1);
                }
                if (stack.isEmpty()) {
                    roots.add(current);
                } else {
                    final VirtualMachineSnapshotTree parent = stack.get(stack.size() - 1);
                    final VirtualMachineSnapshotTree[] children = parent.getChildSnapshotList();
                    final List<VirtualMachineSnapshotTree> list = new ArrayList<>();
                    if (children != null) {
                        list.addAll(Arrays.asList(children));
                    }
                    list.add(current);
                    parent.setChildSnapshotList(list.toArray(new VirtualMachineSnapshotTree[0]));
                }
                stack.add(current);
                indents.add(indent);
            } else if (current != null && field.equals("id")) {
                final ManagedObjectReference mor = new ManagedObjectReference();
                mor.setType("VirtualMachineSnapshot");
                mor.setVal(value);
                current.setSnapshot(mor);
            } else if (current != null && (field.equals("desciption") || field.equals("description"))) {
                current.setDescription(value);
            } else if (current != null && field.equals("state")) {
                final VirtualMachinePowerState state = parsePowerState(value);
                if (state != null) {
                    current.setState(state);
                }
            }
        }
        return roots;
    }
}
