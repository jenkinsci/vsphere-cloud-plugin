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
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.jenkinsci.plugins.vsphere.tools.VSphereException;
import org.jenkinsci.plugins.vsphere.tools.VSphereLogger;

/**
 * A copy of the state of a master, made on a host that does not see the master's files, so that clones can be made
 * there: a VM of its own, kept powered off, that has the disks of the master (as they were at a snapshot, or are
 * now) as one thin disk each, and one snapshot, which is what linked clones are made of. The master is not touched.
 *
 * <p>The disks are exported on the source host in a sparse format ({@code vmkfstools -d 2gbsparse}), which holds
 * only what is written on a disk, sent to the target through the controller ({@link EsxiRelay}), checked there by
 * checksum, and imported as thin disks. A replica never changes: its name has a digest in it of where it is from and
 * of the {@code CID} of each of the disks (which the disk file changes when it is written to), so a master that has
 * changed gets a new replica and clones are never made of a state that is not the one asked for. The VM says where it
 * is from in settings of its {@code .vmx} file.
 */
final class EsxiReplica {

    static final String KEY_SOURCE = "jenkins.replica.source";
    static final String KEY_STATE = "jenkins.replica.state";
    static final String KEY_STAMP = "jenkins.replica.stamp";
    static final String KEY_CREATED = "jenkins.replica.created";
    static final String BASE_SNAPSHOT = "jenkins-replica-base";
    static final String NAME_PREFIX = "jenkins-replica-";

    private static final Pattern CID = Pattern.compile("(?m)^CID=([0-9a-fA-F]+)");
    private static final Pattern CKSUM = Pattern.compile("^(\\d+)\\s+(\\d+)\\s");
    private static final Map<String, ReentrantLock> LOCKS = new ConcurrentHashMap<>();

    private EsxiReplica() {}

    private static void say(@CheckForNull PrintStream log, String message) {
        if (log != null) {
            VSphereLogger.vsLogger(log, message);
        }
    }

    private static String parent(String path) {
        return path.substring(0, path.lastIndexOf('/'));
    }

    /** One disk of the master, as it is to be copied. */
    private static final class Frozen {
        final String prefix;
        final String path;
        final String cid;

        Frozen(String prefix, String path, String cid) {
            this.prefix = prefix;
            this.path = path;
            this.cid = cid;
        }
    }

    /**
     * Makes the replica on the target host, or finds the one that was made already.
     *
     * @param snapshotUid the snapshot of the master (as the number the source host knows it by) whose state is copied,
     *     or null for the state the master has now, which has to be readable (powered off, or with a snapshot)
     * @param datastore the datastore of the target to put it on, or blank for the one with the most room
     * @return the replica, as the target host has it registered
     */
    static EsxiVirtualMachine ensure(
            VSphereEsxiSsh source,
            VmEntry master,
            @CheckForNull String snapshotUid,
            VSphereEsxiSsh target,
            @CheckForNull String datastore,
            EsxiRelay.Compression compression,
            int idleSeconds,
            @CheckForNull PrintStream log)
            throws VSphereException {
        return ensure(source, master, snapshotUid, target, datastore, compression, idleSeconds, EsxiRelay.RELAY, log);
    }

    /** As above, moving the files the way that the mover does. */
    static EsxiVirtualMachine ensure(
            VSphereEsxiSsh source,
            VmEntry master,
            @CheckForNull String snapshotUid,
            VSphereEsxiSsh target,
            @CheckForNull String datastore,
            EsxiRelay.Compression compression,
            int idleSeconds,
            EsxiRelay.Mover mover,
            @CheckForNull PrintStream log)
            throws VSphereException {
        final EsxiDatastoreFiles sourceFiles = source.files();
        final String vmxPath = master.getVmxFileSystemPath();
        final String folder = parent(vmxPath);
        final VmxFile masterVmx = VmxFile.parse(sourceFiles.read(vmxPath));
        final List<EsxiVmCloner.Disk> disks = EsxiVmCloner.disksOf(masterVmx);
        if (disks.isEmpty()) {
            throw new VSphereException("The VM \"" + master.getName() + "\" has no virtual disk to make a replica of");
        }

        // which file holds each disk in the state that is wanted
        EsxiSnapshotMetadata metadata = null;
        int index = -1;
        if (snapshotUid != null) {
            metadata = EsxiSnapshotMetadata.parse(sourceFiles.read(EsxiSnapshotMetadata.pathFor(vmxPath)));
            index = metadata.indexOfUid(snapshotUid);
            if (index < 0) {
                throw new VSphereException("The snapshot " + snapshotUid + " is not in the snapshots of " + master);
            }
        }
        final List<Frozen> frozen = new ArrayList<>();
        final StringBuilder stamp = new StringBuilder();
        for (EsxiVmCloner.Disk disk : disks) {
            final String file = metadata == null ? disk.fileName : metadata.diskFile(index, disk.prefix);
            if (file == null) {
                throw new VSphereException("The disk " + disk.prefix + " was not in " + master.getName()
                        + " when the snapshot " + snapshotUid + " was taken");
            }
            final String path = file.startsWith("/") ? file : folder + "/" + file;
            final Matcher cid = CID.matcher(sourceFiles.read(path));
            if (!cid.find()) {
                throw new VSphereException("The disk " + path + " has no CID in its descriptor");
            }
            frozen.add(new Frozen(disk.prefix, path, cid.group(1)));
            stamp.append(disk.prefix).append('=').append(cid.group(1)).append(';');
        }

        final String volume = volumeOf(source, master);
        final String sourceKey = volume + ":" + master.getVmxRelativePath();
        final String state = snapshotUid == null ? "now" : "snapshot-" + snapshotUid;
        final String name = replicaName(master.getName(), sourceKey, state, stamp.toString());

        final String lockKey = target.getLabel() + "/" + name;
        final ReentrantLock lock = LOCKS.computeIfAbsent(lockKey, k -> new ReentrantLock());
        if (!lock.tryLock()) {
            say(log, "Another build is already making the replica " + name + " on " + target.getLabel() + "; waiting");
            lock.lock();
        }
        try {
            final VirtualMachineHolder found = existing(target, name, sourceKey, state, stamp.toString());
            if (found.vm != null) {
                say(log, "Using the replica " + name + " that " + target.getLabel() + " has of " + master.getName());
                return found.vm;
            }
            return make(
                    source,
                    master,
                    target,
                    datastore,
                    name,
                    sourceKey,
                    state,
                    stamp.toString(),
                    frozen,
                    masterVmx,
                    compression,
                    idleSeconds,
                    mover,
                    log);
        } finally {
            lock.unlock();
            if (!lock.hasQueuedThreads()) {
                LOCKS.remove(lockKey, lock);
            }
        }
    }

    private static final class VirtualMachineHolder {
        @CheckForNull
        EsxiVirtualMachine vm;
    }

    private static VirtualMachineHolder existing(
            VSphereEsxiSsh target, String name, String sourceKey, String state, String stamp) throws VSphereException {
        final VirtualMachineHolder holder = new VirtualMachineHolder();
        final com.vmware.vim25.mo.VirtualMachine vm = target.getVmByName(name);
        if (vm instanceof EsxiVirtualMachine) {
            final VmxFile vmx = VmxFile.parse(
                    target.files().read(((EsxiVirtualMachine) vm).getEntry().getVmxFileSystemPath()));
            if (sourceKey.equals(vmx.get(KEY_SOURCE))
                    && state.equals(vmx.get(KEY_STATE))
                    && stamp.equals(vmx.get(KEY_STAMP))) {
                holder.vm = (EsxiVirtualMachine) vm;
            } else {
                throw new VSphereException("The VM " + name + " on " + target.getLabel()
                        + " has the name of a replica but is not the replica of this state of the master");
            }
        }
        return holder;
    }

    /** The name of the volume that the master's datastore is: its UUID, which is the same on all the hosts that have it. */
    private static String volumeOf(VSphereEsxiSsh source, VmEntry master) {
        try {
            for (EsxiDatastoreEntry datastore : source.listDatastores()) {
                if (datastore.getName().equals(master.getDatastore())) {
                    return datastore.getUuid();
                }
            }
        } catch (VSphereException e) {
            // by its name, then
        }
        return master.getDatastore();
    }

    static String replicaName(String masterName, String sourceKey, String state, String stamp) {
        final String plain = masterName.replaceAll("[^A-Za-z0-9._-]", "-");
        final String shortName = plain.length() > 30 ? plain.substring(0, 30) : plain;
        try {
            final byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest((sourceKey + "|" + state + "|" + stamp).getBytes(StandardCharsets.UTF_8));
            final StringBuilder hex = new StringBuilder();
            for (int i = 0; i < 5; i++) {
                hex.append(String.format(Locale.ROOT, "%02x", digest[i]));
            }
            return NAME_PREFIX + shortName + "-" + hex;
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    /** {@code cksum} of a file: its checksum and size, which is what a copy has to have as the original. */
    private static String cksum(EsxiShell shell, String path) throws VSphereException {
        final ShellResult result = shell.run("cksum " + ShellQuote.quote(path));
        final Matcher m =
                CKSUM.matcher(result.stdoutOrThrow("Checksumming " + path).trim());
        if (!m.find()) {
            throw new VSphereException("Could not read the checksum of " + path + " from: "
                    + result.getStdout().trim());
        }
        return m.group(1) + " " + m.group(2);
    }

    /** What is to be done with one disk of the master. */
    private static final class Plan {
        final Frozen disk;
        final int index;
        final String directory;
        final String descriptorName;
        final EsxiDiskSizes sizes;
        /** True if its files are sent as they are, false if it is exported in a sparse format and imported. */
        boolean asIs;

        Plan(Frozen disk, int index, EsxiDiskSizes sizes) {
            this.disk = disk;
            this.index = index;
            this.directory = parent(disk.path);
            this.descriptorName = disk.path.substring(disk.path.lastIndexOf('/') + 1);
            this.sizes = sizes;
            // its own files have to be plain names in the folder it is in, and it has to be a disk of its own
            boolean plain = true;
            for (String file : sizes.ownFiles) {
                plain &= !file.contains("/");
            }
            this.asIs = plain && !sizes.chained && sizes.isDense();
        }

        String why() {
            if (sizes.chained) {
                return "it is a change of other disks, which are exported as one";
            }
            if (sizes.allocatedKb < 0) {
                return "how much of it is written is not known";
            }
            return asIs
                    ? "most of it is written: its files are sent as they are"
                    : "little of it is written: it is exported in a format that has only that";
        }
    }

    private static void checkRoom(
            VSphereEsxiSsh host, String datastore, long neededKb, String what, @CheckForNull PrintStream log)
            throws VSphereException {
        for (EsxiDatastoreEntry entry : host.listDatastores()) {
            if (entry.getName().equals(datastore)) {
                if (entry.getFree() / 1024 < neededKb) {
                    throw new VSphereException("Not enough room for " + what + ": about " + neededKb / 1024
                            + " MB are needed on " + datastore + " of " + host.getLabel() + ", which has "
                            + entry.getFree() / (1024 * 1024) + " MB free");
                }
                return;
            }
        }
        say(log, "Cannot tell how much room " + datastore + " of " + host.getLabel() + " has, for " + what);
    }

    private static EsxiVirtualMachine make(
            VSphereEsxiSsh source,
            VmEntry master,
            VSphereEsxiSsh target,
            @CheckForNull String datastore,
            String name,
            String sourceKey,
            String state,
            String stamp,
            List<Frozen> frozen,
            VmxFile masterVmx,
            EsxiRelay.Compression compression,
            int idleSeconds,
            EsxiRelay.Mover mover,
            @CheckForNull PrintStream log)
            throws VSphereException {
        final EsxiDatastoreFiles sourceFiles = source.files();
        final EsxiDatastoreFiles targetFiles = target.files();

        String where = datastore == null ? "" : datastore.trim();
        if (where.isEmpty()) {
            long room = -1;
            for (EsxiDatastoreEntry candidate : target.listDatastores()) {
                if (candidate.getFree() > room) {
                    room = candidate.getFree();
                    where = candidate.getName();
                }
            }
            if (where.isEmpty()) {
                throw new VSphereException(target.getLabel() + " has no datastore to put a replica on");
            }
        }
        EsxiDatastoreFiles.checkName("The datastore", where);

        // how much of each disk is written decides how it is copied, and whether there is room for it
        final List<Plan> plans = new ArrayList<>();
        final java.util.Set<String> taken = new java.util.HashSet<>();
        long needed = 0;
        long exportNeeded = 0;
        for (int i = 0; i < frozen.size(); i++) {
            final Plan plan = new Plan(frozen.get(i), i, EsxiDiskSizes.measure(sourceFiles, frozen.get(i).path));
            // the files of a disk keep their names, which two disks must not share
            if (plan.asIs && !taken.addAll(plan.sizes.ownFiles)) {
                plan.asIs = false;
            }
            plans.add(plan);
            say(
                    log,
                    "The disk " + plan.disk.prefix + " of " + master.getName() + " has "
                            + (plan.sizes.allocatedKb < 0 ? "an unknown amount" : plan.sizes.allocatedKb / 1024 + " MB")
                            + " written of " + plan.sizes.logicalKb / 1024 + " MB: " + plan.why());
            if (plan.sizes.allocatedKb >= 0) {
                needed += plan.sizes.allocatedKb;
                if (!plan.asIs) {
                    exportNeeded += plan.sizes.allocatedKb;
                }
            }
        }
        if (needed > 0) {
            checkRoom(target, where, EsxiDiskSizes.withMargin(needed), "the replica " + name, log);
        }
        if (exportNeeded > 0) {
            checkRoom(
                    source,
                    master.getDatastore(),
                    EsxiDiskSizes.withMargin(exportNeeded),
                    "the export of the disks of " + master.getName(),
                    log);
        }
        final String replicaDir = "/vmfs/volumes/" + where + "/" + name;
        final String importDir = replicaDir + "/export";
        final String exportDir = parent(masterPathOf(master)) + "/.jenkins-export-"
                + UUID.randomUUID().toString().substring(0, 8);

        say(
                log,
                "Making the replica " + name + " of " + master.getName() + " (" + state + ") on " + target.getLabel()
                        + ", in " + where);
        // A folder that is there is another build's, or one that was left: it is not ours to use
        final ShellResult made = target.files().run("mkdir " + ShellQuote.quote(replicaDir));
        if (!made.succeeded()) {
            throw new VSphereException(replicaDir + " exists already on " + target.getLabel()
                    + ", but there is no replica in it: another copy is being made or was left; remove it");
        }
        Integer registered = null;
        try {
            final VmxFile vmx = VmxFile.parse(masterVmx.toString());
            final List<Plan> exported = new ArrayList<>();
            for (Plan plan : plans) {
                if (plan.asIs) {
                    // its files, as they are: a descriptor and the extents it names
                    say(log, "Sending the files of the disk " + plan.disk.prefix + " as they are");
                    EsxiRelay.copy(
                            sourceFiles.shell(),
                            source.getLabel(),
                            targetFiles.shell(),
                            target.getLabel(),
                            plan.directory,
                            plan.sizes.ownFiles,
                            replicaDir,
                            compression,
                            idleSeconds,
                            log,
                            mover);
                    // the extents are as large as they were (and sent by a stream that checks itself); the descriptor
                    // is small, and is compared whole
                    final String descriptorHere = cksum(sourceFiles.shell(), plan.disk.path);
                    final String descriptorThere = cksum(targetFiles.shell(), replicaDir + "/" + plan.descriptorName);
                    if (!descriptorHere.equals(descriptorThere)) {
                        throw new VSphereException("The copy of " + plan.descriptorName + " on " + target.getLabel()
                                + " has the checksum and size " + descriptorThere + ", not the " + descriptorHere
                                + " that it has on " + source.getLabel());
                    }
                    vmx.put(plan.disk.prefix + ".fileName", plan.descriptorName);
                } else {
                    exported.add(plan);
                }
            }
            if (!exported.isEmpty()) {
                sourceFiles.mkdirs(exportDir);
                targetFiles.mkdirs(importDir);

                // export, in a format that has what is written and not the rest
                for (Plan plan : exported) {
                    say(
                            log,
                            "Exporting the disk " + plan.disk.prefix + " of " + master.getName() + " on "
                                    + source.getLabel());
                    source.runUntilIdle(
                            "vmkfstools -i " + ShellQuote.quote(plan.disk.path) + " -d 2gbsparse "
                                    + ShellQuote.quote(exportDir + "/d" + plan.index + ".vmdk"),
                            "Exporting the disk " + plan.disk.path,
                            idleSeconds);
                }
                final List<String> names = new ArrayList<>();
                final String listed =
                        sourceFiles.run("ls -1 " + ShellQuote.quote(exportDir)).stdoutOrThrow("Listing " + exportDir);
                for (String line : listed.split("\\R")) {
                    if (!line.trim().isEmpty()) {
                        names.add(line.trim());
                    }
                }
                final List<String> checksums = new ArrayList<>();
                for (String file : names) {
                    checksums.add(cksum(sourceFiles.shell(), exportDir + "/" + file));
                }

                EsxiRelay.copy(
                        sourceFiles.shell(),
                        source.getLabel(),
                        targetFiles.shell(),
                        target.getLabel(),
                        exportDir,
                        names,
                        importDir,
                        compression,
                        idleSeconds,
                        log,
                        mover);
                for (int i = 0; i < names.size(); i++) {
                    final String there = cksum(targetFiles.shell(), importDir + "/" + names.get(i));
                    if (!there.equals(checksums.get(i))) {
                        throw new VSphereException("The copy of " + names.get(i) + " on " + target.getLabel()
                                + " has the checksum and size " + there + ", not the " + checksums.get(i)
                                + " that it has on " + source.getLabel());
                    }
                }

                // import, as thin disks
                for (Plan plan : exported) {
                    say(log, "Importing the disk " + plan.disk.prefix + " on " + target.getLabel());
                    final String imported = name + "_" + plan.index + ".vmdk";
                    target.runUntilIdle(
                            "vmkfstools -i " + ShellQuote.quote(importDir + "/d" + plan.index + ".vmdk") + " -d thin "
                                    + ShellQuote.quote(replicaDir + "/" + imported),
                            "Importing the disk " + plan.disk.prefix,
                            idleSeconds);
                    vmx.put(plan.disk.prefix + ".fileName", imported);
                }
                targetFiles.removeFolder(importDir);
            }

            // the VM: what the master is, with the disks that were made, and where it is from
            EsxiVmCloner.prepareVmx(vmx, name, master.getName(), null, null);
            final String adapted = target.adaptHardwareVersion(vmx);
            if (adapted != null) {
                say(log, adapted);
            }
            vmx.put(KEY_SOURCE, sourceKey);
            vmx.put(KEY_STATE, state);
            vmx.put(KEY_STAMP, stamp);
            vmx.put(KEY_CREATED, Long.toString(System.currentTimeMillis() / 1000));
            final String replicaVmx = replicaDir + "/" + name + ".vmx";
            targetFiles.write(replicaVmx, vmx.toString());
            final String output = target.vim("/bin/vim-cmd solo/registervm " + ShellQuote.quote(replicaVmx) + " "
                            + ShellQuote.quote(name))
                    .stdoutOrThrow("Registering " + replicaVmx);
            final Matcher id = Pattern.compile("(\\d+)\\s*$").matcher(output.trim());
            if (!id.find()) {
                throw new VSphereException(
                        "The host did not say what the id of the registered replica is: " + output.trim());
            }
            registered = Integer.parseInt(id.group(1));

            // the snapshot that clones are linked to
            final com.vmware.vim25.mo.VirtualMachine replica = target.getVmByName(name);
            if (!(replica instanceof EsxiVirtualMachine)) {
                throw new VSphereException("The host does not list the replica " + name + " after registering "
                        + replicaVmx + " (it said: " + output.trim() + "), which is what it does for a VM that it takes"
                        + " for invalid, such as one of a virtual hardware version that it does not have (the master has "
                        + masterVmx.get("virtualHW.version", "no version") + "); it lists: " + target.listVms());
            }
            final EsxiTask snapshot = target.createSnapshotTask(
                    ((EsxiVirtualMachine) replica).getEntry(),
                    BASE_SNAPSHOT,
                    "What linked clones of the replica are made of",
                    false,
                    false);
            if (!"success".equals(snapshot.waitForTask())) {
                throw new VSphereException("Taking the base snapshot of the replica failed: "
                        + snapshot.getTaskInfo().getError().getLocalizedMessage());
            }
            say(log, "The replica " + name + " is ready on " + target.getLabel());
            return (EsxiVirtualMachine) replica;
        } catch (VSphereException | RuntimeException e) {
            try {
                if (registered != null) {
                    target.vim("/bin/vim-cmd vmsvc/unregister " + ShellQuote.id(registered));
                }
                targetFiles.removeFolder(replicaDir);
                say(log, "Removed what was made of the replica " + name + " on " + target.getLabel());
            } catch (VSphereException again) {
                say(log, "Could not remove all that was made of the replica " + name + ": " + again.getMessage());
            }
            throw e;
        } finally {
            try {
                sourceFiles.removeFolder(exportDir);
            } catch (VSphereException e) {
                say(log, "Could not remove " + exportDir + " on " + source.getLabel() + ": " + e.getMessage());
            }
        }
    }

    private static String masterPathOf(VmEntry master) {
        return master.getVmxFileSystemPath();
    }
}
