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
import java.io.PrintStream;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.jenkinsci.plugins.vsphere.tools.VSphereDuplicateException;
import org.jenkinsci.plugins.vsphere.tools.VSphereException;
import org.jenkinsci.plugins.vsphere.tools.VSphereLogger;
import org.jenkinsci.plugins.vsphere.tools.VSphereNotFoundException;
import org.jenkinsci.plugins.vsphere.tools.VmSize;

/**
 * Makes a copy of a VM on a standalone ESXi host, which has no way of cloning, by working on the files, as the
 * {@code esxi-linked-clone} scripts do:
 *
 * <ul>
 *   <li>a <b>linked clone</b> takes, for each disk of the master, the newest snapshot disk (the "delta" that
 *       holds the changes since the snapshot) that can be read, which is a small file, copies it into the
 *       folder of the clone, and makes the copy a change of the disk that the master has it a change of, by
 *       its parent. The data that the master and its clones share is kept once;
 *   <li>a <b>full clone</b> copies the whole disk (thin provisioned) with {@code vmkfstools}, which takes time.
 * </ul>
 *
 * Then the {@code .vmx} is made for the clone (a new name, no identity of the master: UUIDs, MAC addresses,
 * guest information), the clone is registered with the host, and started if so asked.
 *
 * <p>What is done is as much as possible done in Java on the text of the files, with a few quoted commands, and
 * what was made is removed again if anything goes wrong.
 */
final class EsxiVmCloner {

    private static final String VIM_CMD = "/bin/vim-cmd";

    private static final String[] BUSES = {"scsi", "sata", "nvme", "ide"};
    private static final int[] CONTROLLERS = {4, 4, 4, 2};
    private static final int[] UNITS = {16, 30, 15, 2};

    private static final Pattern EXTRA_CONFIG_KEY = Pattern.compile("[A-Za-z0-9][A-Za-z0-9_.:-]*");

    /** What is not of the clone, but of its master. */
    private static final String[] IDENTITY_PREFIXES = {
        "uuid.",
        "vc.uuid",
        "sched.swap.derivedname",
        "migrate.",
        "checkpoint.vmstate",
        "vmxstats.filename",
        "guestinfo."
    };

    private static final String[] NIC_IDENTITY_SUFFIXES = {
        ".generatedaddress", ".generatedaddressoffset", ".address", ".addresstype", ".checkmacaddress"
    };

    private final VSphereEsxiSsh host;
    private final EsxiDatastoreFiles files;
    private final PrintStream log;

    EsxiVmCloner(VSphereEsxiSsh host, EsxiDatastoreFiles files, PrintStream log) {
        this.host = host;
        this.files = files;
        this.log = log;
    }

    /** A disk of the master: where the VM says it is. */
    private static final class Disk {
        final String prefix;
        final String fileName;

        Disk(String prefix, String fileName) {
            this.prefix = prefix;
            this.fileName = fileName;
        }
    }

    /** The descriptor of a disk that is worked on, and where it is. */
    private static final class Located {
        final String directory;
        final String name;
        final VmdkDescriptor descriptor;

        Located(String directory, String name, VmdkDescriptor descriptor) {
            this.directory = directory;
            this.name = name;
            this.descriptor = descriptor;
        }

        String path() {
            return directory + "/" + name;
        }
    }

    private void say(String message) {
        if (log != null) {
            VSphereLogger.vsLogger(log, message);
        }
    }

    /**
     * Makes the clone.
     *
     * @param datastoreName where the clone goes; blank for the datastore of the master, which a linked clone has
     *     to be on
     * @param namedSnapshot clone the state of the master at this snapshot; blank for the newest one that can be read.
     *     A full clone can be made of any snapshot; a linked clone only of the newest, as it needs a disk that is a
     *     change of what the snapshot froze, with no changes of its own
     */
    void clone(
            String cloneName,
            String sourceName,
            boolean linkedClone,
            @CheckForNull String datastoreName,
            boolean powerOn,
            @CheckForNull Map<String, String> extraConfigParameters,
            @CheckForNull VmSize vmSize,
            @CheckForNull String namedSnapshot)
            throws VSphereException {
        EsxiDatastoreFiles.checkName("The name of the clone", cloneName);
        final List<VmEntry> vms = host.listVms();
        final VmEntry source = find(vms, sourceName);
        if (source == null) {
            throw new VSphereNotFoundException("VM", sourceName);
        }
        if (find(vms, cloneName) != null) {
            throw new VSphereDuplicateException("VM", cloneName);
        }

        final String datastore = datastoreName == null || datastoreName.trim().isEmpty()
                ? source.getDatastore()
                : EsxiDatastoreFiles.checkName("The datastore", datastoreName.trim());
        if (linkedClone && !datastore.equals(source.getDatastore())) {
            throw new VSphereException("A linked clone has to be on the datastore of its master ("
                    + source.getDatastore() + "), not on " + datastore);
        }
        final String sourceVmx = source.getVmxFileSystemPath();
        final String sourceDirectory = parent(sourceVmx);
        final String cloneDirectory = "/vmfs/volumes/" + datastore + "/" + cloneName;
        final String cloneVmx = cloneDirectory + "/" + cloneName + ".vmx";
        if (files.exists(cloneDirectory)) {
            throw new VSphereException("Cannot make " + (linkedClone ? "a linked clone" : "a clone") + " \"" + cloneName
                    + "\": " + cloneDirectory + " exists already");
        }

        final VmxFile vmx = VmxFile.parse(files.read(sourceVmx));
        final List<Disk> disks = disksOf(vmx);
        if (disks.isEmpty()) {
            throw new VSphereException("The VM \"" + sourceName + "\" has no virtual disk to clone");
        }

        final Map<String, String> frozen = frozenDisks(source, sourceVmx, disks, namedSnapshot);

        say("Making " + (linkedClone ? "a linked clone" : "a full clone") + " \"" + cloneName + "\" of \"" + sourceName
                + "\" in " + cloneDirectory);
        files.mkdirs(cloneDirectory);
        Integer registeredId = null;
        try {
            int index = 0;
            for (Disk disk : disks) {
                final String made = linkedClone
                        ? linkDisk(disk, sourceDirectory, cloneDirectory, frozen.get(disk.prefix))
                        : copyDisk(
                                disk,
                                sourceDirectory,
                                cloneDirectory,
                                index == 0 ? cloneName : cloneName + "_" + index,
                                frozen.get(disk.prefix));
                vmx.put(disk.prefix + ".fileName", made);
                index++;
            }
            prepareVmx(vmx, cloneName, sourceName, extraConfigParameters, vmSize);
            files.write(cloneVmx, vmx.toString());

            say("Registering \"" + cloneName + "\" with the host");
            registeredId = register(cloneVmx, cloneName);
            if (powerOn) {
                say("Powering on \"" + cloneName + "\"");
                host.vim(VIM_CMD + " vmsvc/power.on " + ShellQuote.id(registeredId))
                        .stdoutOrThrow("Powering on " + cloneName);
            }
        } catch (VSphereException | RuntimeException e) {
            cleanUp(registeredId, cloneDirectory, cloneName);
            throw e;
        }
        say("\"" + cloneName + "\" was cloned from \"" + sourceName + "\"");
    }

    /**
     * For a named snapshot, the disk file that held each disk when it was taken (a disk that was not there then is
     * an error); empty when no snapshot is named.
     */
    private Map<String, String> frozenDisks(VmEntry source, String sourceVmx, List<Disk> disks, String namedSnapshot)
            throws VSphereException {
        final Map<String, String> frozen = new HashMap<>();
        if (namedSnapshot == null || namedSnapshot.trim().isEmpty()) {
            return frozen;
        }
        final String id = host.snapshotIdByName(source, namedSnapshot);
        if (id == null) {
            throw new VSphereNotFoundException("Snapshot", namedSnapshot);
        }
        final String vmsd = EsxiSnapshotMetadata.pathFor(sourceVmx);
        if (!files.exists(vmsd)) {
            throw new VSphereException(
                    "Cannot tell the disks of the snapshot \"" + namedSnapshot + "\": " + vmsd + " is not there");
        }
        final EsxiSnapshotMetadata metadata = EsxiSnapshotMetadata.parse(files.read(vmsd));
        final int index = metadata.indexOfUid(id);
        if (index < 0) {
            throw new VSphereException("The snapshot \"" + namedSnapshot + "\" (" + id + ") is not in " + vmsd);
        }
        for (Disk disk : disks) {
            final String file = metadata.diskFile(index, disk.prefix);
            if (file == null) {
                throw new VSphereException("The disk " + disk.prefix + " was not in the VM when the snapshot \""
                        + namedSnapshot + "\" was taken");
            }
            frozen.put(disk.prefix, file);
        }
        return frozen;
    }

    private void cleanUp(@CheckForNull Integer registeredId, String cloneDirectory, String cloneName) {
        try {
            if (registeredId != null) {
                host.vim(VIM_CMD + " vmsvc/unregister " + ShellQuote.id(registeredId));
            }
            files.removeFolder(cloneDirectory);
            say("Removed what was made of \"" + cloneName + "\"");
        } catch (VSphereException e) {
            say("Could not remove all that was made of \"" + cloneName + "\": " + e.getMessage());
        }
    }

    private int register(String vmxPath, String name) throws VSphereException {
        final String output = host.vim(
                        VIM_CMD + " solo/registervm " + ShellQuote.quote(vmxPath) + " " + ShellQuote.quote(name))
                .stdoutOrThrow("Registering " + vmxPath);
        final Matcher m = Pattern.compile("(\\d+)\\s*$").matcher(output.trim());
        if (!m.find()) {
            throw new VSphereException("The host did not say what the id of the registered VM is: " + output.trim());
        }
        return Integer.parseInt(m.group(1));
    }

    // -- the disks --

    private List<Disk> disksOf(VmxFile vmx) {
        final List<Disk> disks = new ArrayList<>();
        for (int b = 0; b < BUSES.length; b++) {
            for (int controller = 0; controller < CONTROLLERS[b]; controller++) {
                for (int unit = 0; unit < UNITS[b]; unit++) {
                    final String prefix = BUSES[b] + controller + ":" + unit;
                    final String fileName = vmx.get(prefix + ".fileName");
                    final String type = vmx.get(prefix + ".deviceType", "").toLowerCase(Locale.ROOT);
                    if (vmx.getBoolean(prefix + ".present")
                            && fileName != null
                            && fileName.endsWith(".vmdk")
                            && !type.contains("cdrom")) {
                        disks.add(new Disk(prefix, fileName));
                    }
                }
            }
        }
        return disks;
    }

    private Located locate(String directory, String name) throws VSphereException {
        final String path = name.startsWith("/") ? name : directory + "/" + name;
        return new Located(parent(path), baseName(path), VmdkDescriptor.parse(files.read(path)));
    }

    private boolean extentsCanBeRead(Located disk) throws VSphereException {
        for (String extent : disk.descriptor.getExtentFiles()) {
            if (!files.canRead(disk.directory + "/" + extent)) {
                return false;
            }
        }
        return true;
    }

    private static String parentPath(Located disk) {
        final String hint = disk.descriptor.getParentHint();
        return hint.startsWith("/") ? hint : disk.directory + "/" + hint;
    }

    /**
     * The newest disk, going from the one that the VM has to the ones it is a change of, that is a snapshot's and
     * that can be read (one that is in use by a VM that is running cannot).
     */
    private Located newestReadableSnapshotDisk(Disk disk, String sourceDirectory) throws VSphereException {
        Located current = locate(sourceDirectory, disk.fileName);
        for (int depth = 0; depth < 64; depth++) {
            if (!current.descriptor.isSnapshotDisk()) {
                break;
            }
            if (extentsCanBeRead(current)) {
                return current;
            }
            current = locate(sourceDirectory, parentPath(current));
        }
        throw new VSphereException("The disk " + disk.fileName + " of the master has no snapshot disk that a linked"
                + " clone can be made of: take a snapshot of the master (and, if it is running, a second one, so that"
                + " the first stays as it is)");
    }

    /** The newest disk, snapshot's or not, that can be read: what a full copy is made of. */
    private Located newestReadableDisk(Disk disk, String sourceDirectory) throws VSphereException {
        Located current = locate(sourceDirectory, disk.fileName);
        for (int depth = 0; depth < 64; depth++) {
            if (extentsCanBeRead(current)) {
                return current;
            }
            if (!current.descriptor.isSnapshotDisk()) {
                break;
            }
            current = locate(sourceDirectory, parentPath(current));
        }
        throw new VSphereException("The disk " + disk.fileName
                + " of the master cannot be read: is the master running? Take a snapshot of it");
    }

    private String linkDisk(Disk disk, String sourceDirectory, String cloneDirectory, String frozenFile)
            throws VSphereException {
        final Located chosen = newestReadableSnapshotDisk(disk, sourceDirectory);
        if (frozenFile != null) {
            final String wanted =
                    files.canonical(frozenFile.startsWith("/") ? frozenFile : sourceDirectory + "/" + frozenFile);
            if (!files.canonical(parentPath(chosen)).equals(wanted)) {
                throw new VSphereException("A linked clone can only be made of the newest snapshot, as the disk it"
                        + " shares has to be one with no changes of its own after the snapshot: " + disk.fileName
                        + " is a change of " + parentPath(chosen) + ", not of " + wanted
                        + ". Make a full clone of that snapshot instead");
            }
        }
        // The data that is shared is named by where it really is, as the datastore is also there by its name
        final String parent = files.canonical(parentPath(chosen));
        say("Linking disk " + disk.fileName + ": copying " + chosen.name + " and making it a change of " + parent);
        for (String extent : chosen.descriptor.getExtentFiles()) {
            files.copy(chosen.directory + "/" + extent, cloneDirectory + "/" + extent);
        }
        files.write(
                cloneDirectory + "/" + chosen.name,
                chosen.descriptor.withParentHint(parent).toString());
        return chosen.name;
    }

    private String copyDisk(Disk disk, String sourceDirectory, String cloneDirectory, String newName, String frozenFile)
            throws VSphereException {
        final Located chosen;
        if (frozenFile != null) {
            // what the snapshot froze is not written to any more, and a copy of it takes what it is a change of along
            chosen = locate(sourceDirectory, frozenFile);
            if (!extentsCanBeRead(chosen)) {
                throw new VSphereException("The disk " + chosen.path() + " of the snapshot cannot be read");
            }
        } else {
            chosen = newestReadableDisk(disk, sourceDirectory);
        }
        final String made = newName + ".vmdk";
        say("Copying disk " + disk.fileName + " as " + made + ", which takes a while");
        host.runLong(
                "vmkfstools -i " + ShellQuote.quote(chosen.path()) + " " + ShellQuote.quote(cloneDirectory + "/" + made)
                        + " -d thin",
                "Copying the disk " + disk.fileName);
        return made;
    }

    // -- the VM --

    private void prepareVmx(
            VmxFile vmx,
            String cloneName,
            String sourceName,
            @CheckForNull Map<String, String> extraConfigParameters,
            @CheckForNull VmSize vmSize)
            throws VSphereException {
        // What makes the master what it is, and not the clone: all of it is made new when the clone is started
        for (String key : vmx.keys()) {
            final String lower = key.toLowerCase(Locale.ROOT);
            // a VM deployed from a template is not one
            boolean identity = lower.equals("template");
            for (String prefix : IDENTITY_PREFIXES) {
                identity |= lower.startsWith(prefix);
            }
            if (lower.startsWith("ethernet")) {
                for (String suffix : NIC_IDENTITY_SUFFIXES) {
                    identity |= lower.endsWith(suffix);
                }
            }
            if (identity) {
                vmx.remove(key);
            }
        }
        vmx.put("displayName", cloneName);
        if (vmx.get("nvram") != null) {
            vmx.put("nvram", cloneName + ".nvram");
        }
        vmx.put("guestinfo.mastername", sourceName);
        vmx.put("guestinfo.vmname", cloneName);
        vmx.put("guestinfo.hostname", cloneName.replaceAll("[^A-Za-z0-9-]", ""));

        if (vmSize != null && !vmSize.isEmpty()) {
            if (vmSize.getCpuCores() != null) {
                vmx.put("numvcpus", Integer.toString(vmSize.getCpuCores()));
            }
            if (vmSize.getCoresPerSocket() != null) {
                vmx.put("cpuid.coresPerSocket", Integer.toString(vmSize.getCoresPerSocket()));
            }
            if (vmSize.getMemorySize() != null) {
                vmx.put("memSize", Integer.toString(vmSize.getMemorySize()));
            }
            if (vmSize.getCpuLimitMHz() != null) {
                vmx.put("sched.cpu.min", Integer.toString(vmSize.getCpuLimitMHz()));
            }
        }
        if (extraConfigParameters != null) {
            for (Map.Entry<String, String> parameter : extraConfigParameters.entrySet()) {
                final String key = parameter.getKey();
                final String value = parameter.getValue() == null ? "" : parameter.getValue();
                if (key == null || !EXTRA_CONFIG_KEY.matcher(key).matches()) {
                    throw new VSphereException("The extra configuration parameter \"" + key + "\" has a name that"
                            + " cannot be put in a .vmx file");
                }
                if (value.contains("\"") || value.contains("\n") || value.contains("\r")) {
                    throw new VSphereException("The value of the extra configuration parameter \"" + key
                            + "\" cannot be put in a .vmx file: it has a quote or a line break in it");
                }
                vmx.put(key, value);
            }
        }
    }

    private static @CheckForNull VmEntry find(List<VmEntry> vms, String name) {
        for (VmEntry vm : vms) {
            if (vm.getName().equals(name)) {
                return vm;
            }
        }
        return null;
    }

    private static String parent(String path) {
        return path.substring(0, path.lastIndexOf('/'));
    }

    private static String baseName(String path) {
        return path.substring(path.lastIndexOf('/') + 1);
    }
}
