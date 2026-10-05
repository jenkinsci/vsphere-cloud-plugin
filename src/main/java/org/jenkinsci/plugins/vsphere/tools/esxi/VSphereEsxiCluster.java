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

import com.vmware.vim25.CustomizationSpecItem;
import com.vmware.vim25.ManagedObjectReference;
import com.vmware.vim25.mo.DistributedVirtualPortgroup;
import com.vmware.vim25.mo.DistributedVirtualSwitch;
import com.vmware.vim25.mo.Folder;
import com.vmware.vim25.mo.ManagedEntity;
import com.vmware.vim25.mo.Network;
import com.vmware.vim25.mo.VirtualMachine;
import com.vmware.vim25.mo.VirtualMachineSnapshot;
import edu.umd.cs.findbugs.annotations.CheckForNull;
import java.io.PrintStream;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.logging.Level;
import org.jenkinsci.plugins.vsphere.tools.AbstractVSphere;
import org.jenkinsci.plugins.vsphere.tools.HostSelectionOptions;
import org.jenkinsci.plugins.vsphere.tools.VSphereDuplicateException;
import org.jenkinsci.plugins.vsphere.tools.VSphereException;
import org.jenkinsci.plugins.vsphere.tools.VSphereHostSelection;
import org.jenkinsci.plugins.vsphere.tools.VSphereLogger;
import org.jenkinsci.plugins.vsphere.tools.VSphereNotFoundException;
import org.jenkinsci.plugins.vsphere.tools.VmSize;

/**
 * Several standalone ESXi hosts used as one, a "poor man's cluster": VMs are looked up on all the hosts, whatever is
 * done to a VM is done by the host it is registered on, and a clone is made on one of the hosts that can see the
 * files of its master, which they do if they have a datastore in common (NFS, or VMFS on shared storage).
 *
 * <p>The hosts that are down are left out, and tried again when the connection is checked
 * ({@link #isSessionAlive()}, which the connection pool does each time it hands the connection out) or an operation
 * looks for them again; as long as one host is up, the cluster is. All the sessions are part of this one object, so
 * the connection pool, which holds one connection for a cloud, holds all of them.
 *
 * <p>Where a clone goes, when no host is asked for: of the hosts that can see its master, the one with the fewest
 * VMs that are on, then the fewest that are registered, then the first as configured. The master is not moved, nor
 * any of the other VMs; this is not vMotion, nor a scheduler, and the load of a host is not measured.
 */
public final class VSphereEsxiCluster extends AbstractVSphere {

    /** How long a host that could not be reached is left alone before it is tried again. */
    static final long RETRY_MILLIS = 30_000L;

    /** Makes the session to one of the hosts. */
    public interface Connector {
        /** What the host is called in messages: how it is configured. */
        String label();

        VSphereEsxiSsh connect() throws VSphereException;
    }

    private static final class Member {
        final Connector connector;

        @CheckForNull
        VSphereEsxiSsh session;

        long failedAt;

        @CheckForNull
        String failure;

        Member(Connector connector) {
            this.connector = connector;
        }
    }

    /** What is asked of the cluster besides the hosts. */
    public static final class Options {
        /** Nothing special: no replicas. */
        public static final Options DEFAULT = new Options(false, EsxiRelay.Compression.PIGZ, 300);

        private final boolean replicateMasters;
        private final EsxiRelay.Compression compression;
        private final int idleSeconds;

        public Options(boolean replicateMasters, EsxiRelay.Compression compression, int idleSeconds) {
            this.replicateMasters = replicateMasters;
            this.compression = compression == null ? EsxiRelay.Compression.PIGZ : compression;
            this.idleSeconds = idleSeconds > 0 ? idleSeconds : 300;
        }
    }

    private final List<Member> members = new ArrayList<>();
    private final Options options;
    private boolean closed;

    /**
     * Connects to the hosts, those that can be reached at least; fails if none can.
     */
    public VSphereEsxiCluster(List<Connector> connectors) throws VSphereException {
        this(connectors, Options.DEFAULT);
    }

    public VSphereEsxiCluster(List<Connector> connectors, Options options) throws VSphereException {
        this.options = options == null ? Options.DEFAULT : options;
        for (Connector connector : connectors) {
            members.add(new Member(connector));
        }
        refresh(true);
        if (availableMembers().isEmpty()) {
            final List<String> why = new ArrayList<>();
            for (Member member : members) {
                why.add(member.connector.label() + ": " + member.failure);
            }
            throw new VSphereException("None of the ESXi hosts could be reached: " + String.join("; ", why));
        }
    }

    // -- the sessions --

    /**
     * Connects the hosts that have no session (but not those that failed less than {@link #RETRY_MILLIS} ago, unless
     * forced), and drops those whose session has died.
     */
    private synchronized void refresh(boolean force) {
        if (closed) {
            return;
        }
        final long now = System.currentTimeMillis();
        for (Member member : members) {
            if (member.session != null) {
                if (force || !member.session.isSessionAlive()) {
                    if (!member.session.isSessionAlive()) {
                        drop(member, "the session ended");
                    }
                }
                continue;
            }
            if (!force && member.failedAt != 0 && now - member.failedAt < RETRY_MILLIS) {
                continue;
            }
            try {
                final VSphereEsxiSsh session = member.connector.connect();
                session.joinCluster(this, member.connector.label());
                member.session = session;
                member.failure = null;
                member.failedAt = 0;
                LOGGER.log(Level.INFO, "ESXi host {0} is part of the cluster", member.connector.label());
            } catch (VSphereException | RuntimeException e) {
                member.failedAt = now;
                member.failure = e.getMessage();
                LOGGER.log(Level.WARNING, "ESXi host " + member.connector.label() + " cannot be reached", e);
            }
        }
    }

    private void drop(Member member, String why) {
        final VSphereEsxiSsh session = member.session;
        member.session = null;
        member.failedAt = System.currentTimeMillis();
        member.failure = why;
        if (session != null) {
            try {
                session.disconnect();
            } catch (RuntimeException e) {
                LOGGER.log(Level.FINE, "Closing the session to " + member.connector.label(), e);
            }
        }
    }

    /** The hosts that have a session now, in the order they are configured. */
    synchronized List<VSphereEsxiSsh> availableMembers() {
        final List<VSphereEsxiSsh> available = new ArrayList<>();
        for (Member member : members) {
            if (member.session != null) {
                available.add(member.session);
            }
        }
        return available;
    }

    private synchronized void lost(VSphereEsxiSsh session, VSphereException why) {
        for (Member member : members) {
            if (member.session == session) {
                drop(member, why.getMessage());
            }
        }
    }

    @Override
    protected synchronized void closeSession() {
        closed = true;
        for (Member member : members) {
            if (member.session != null) {
                try {
                    member.session.disconnect();
                } catch (RuntimeException e) {
                    LOGGER.log(Level.FINE, "Closing the session to " + member.connector.label(), e);
                }
                member.session = null;
            }
        }
    }

    /** Tries the hosts that have no session now, without waiting for the time to try them again. */
    void retryNow() {
        refresh(true);
    }

    /** Alive as long as one host is; the others are tried again when they are due. */
    @Override
    public boolean isSessionAlive() {
        refresh(false);
        return !availableMembers().isEmpty();
    }

    // -- looking VMs up --

    @Override
    public VirtualMachine getVmByName(String vmName) throws VSphereException {
        VSphereException last = null;
        for (VSphereEsxiSsh member : availableMembers()) {
            try {
                final VirtualMachine vm = member.getVmByName(vmName);
                if (vm != null) {
                    return vm;
                }
            } catch (VSphereException e) {
                last = e;
                lost(member, e);
            }
        }
        if (last != null && availableMembers().isEmpty()) {
            throw last;
        }
        return null;
    }

    /** The host that has the VM registered, or null. */
    @CheckForNull
    private EsxiVirtualMachine locate(String vmName) throws VSphereException {
        final VirtualMachine vm = getVmByName(vmName);
        return vm instanceof EsxiVirtualMachine ? (EsxiVirtualMachine) vm : null;
    }

    @Override
    public int countVms() throws VSphereException {
        int count = 0;
        for (VSphereEsxiSsh member : availableMembers()) {
            count += member.countVms();
        }
        return count;
    }

    @Override
    public int countVmsByPrefix(String prefix) throws VSphereException {
        int count = 0;
        for (VSphereEsxiSsh member : availableMembers()) {
            count += member.countVmsByPrefix(prefix);
        }
        return count;
    }

    @Override
    protected VirtualMachineSnapshot newSnapshotProxy(VirtualMachine vm, ManagedObjectReference mor) {
        return ((EsxiVirtualMachine) vm).getHost().newSnapshotProxy(vm, mor);
    }

    // -- what the VM's host does --

    @Override
    public void markAsTemplate(String vmName, String snapName, boolean force) throws VSphereException {
        final EsxiVirtualMachine vm = locate(vmName);
        if (vm == null) {
            throw new VSphereNotFoundException("VM", vmName);
        }
        vm.getHost().markAsTemplate(vmName, snapName, force);
    }

    @Override
    public void markAsVm(String name, String resourcePool, String cluster) throws VSphereException {
        final EsxiVirtualMachine vm = locate(name);
        if (vm == null) {
            throw new VSphereNotFoundException("VM", name);
        }
        vm.getHost().markAsVm(name, resourcePool, cluster);
    }

    @Override
    public Network getNetworkPortGroupByName(VirtualMachine virtualMachine, String name) throws VSphereException {
        if (!(virtualMachine instanceof EsxiVirtualMachine)) {
            throw new VSphereException("The VM " + virtualMachine + " is not one of an ESXi host reached over SSH");
        }
        return ((EsxiVirtualMachine) virtualMachine).getHost().getNetworkPortGroupByName(virtualMachine, name);
    }

    @Override
    public boolean hostExists(String hostName) throws VSphereException {
        return findHost(hostName, availableMembers()) != null;
    }

    /** The datastores of all the hosts; one that several have by the same name is there once. */
    @Override
    public ManagedEntity[] getDatastores() throws VSphereException {
        final Map<String, ManagedEntity> byName = new LinkedHashMap<>();
        for (VSphereEsxiSsh member : availableMembers()) {
            for (ManagedEntity datastore : member.getDatastores()) {
                byName.putIfAbsent(datastore.getName(), datastore);
            }
        }
        return byName.values().toArray(new ManagedEntity[0]);
    }

    // -- what a standalone host does not have, as for one --

    @Override
    public Boolean folderExists(String folderPath) throws VSphereException {
        throw VSphereEsxiSsh.notApplicable("folderExists", "there are no folders in a standalone host's inventory");
    }

    @Override
    public Folder getFolder(String folderPath) throws VSphereException {
        throw VSphereEsxiSsh.notApplicable("getFolder", "there are no folders in a standalone host's inventory");
    }

    @Override
    public CustomizationSpecItem getCustomizationSpecByName(String customizationSpecName) throws VSphereException {
        throw VSphereEsxiSsh.notApplicable(
                "getCustomizationSpecByName", "customization specifications are kept by vCenter");
    }

    @Override
    public DistributedVirtualPortgroup getDistributedVirtualPortGroupByName(VirtualMachine virtualMachine, String name)
            throws VSphereException {
        throw VSphereEsxiSsh.notApplicable(
                "getDistributedVirtualPortGroupByName", "distributed switches are managed by vCenter");
    }

    @Override
    public DistributedVirtualSwitch getDistributedVirtualSwitchByPortGroup(
            DistributedVirtualPortgroup distributedVirtualPortgroup) throws VSphereException {
        throw VSphereEsxiSsh.notApplicable(
                "getDistributedVirtualSwitchByPortGroup", "distributed switches are managed by vCenter");
    }

    // -- cloning, on one of the hosts --

    /** The host (of those given) that is called this: as it is configured, or as it calls itself. */
    @CheckForNull
    private static VSphereEsxiSsh findHost(String name, List<VSphereEsxiSsh> hosts) {
        if (name == null || name.trim().isEmpty()) {
            return null;
        }
        final String wanted = name.trim();
        for (VSphereEsxiSsh host : hosts) {
            if (host.getLabel().equalsIgnoreCase(wanted)) {
                return host;
            }
        }
        for (VSphereEsxiSsh host : hosts) {
            try {
                if (host.hostExists(wanted)) {
                    return host;
                }
            } catch (VSphereException e) {
                LOGGER.log(Level.FINE, "Asking " + host.getLabel() + " for its name", e);
            }
        }
        return null;
    }

    private static boolean namedIn(VSphereEsxiSsh host, Set<String> names) {
        for (String name : names) {
            if (findHost(name, List.of(host)) != null) {
                return true;
            }
        }
        return false;
    }

    /**
     * Makes the clone on the host that is asked for, or on the least busy of those that can see the files of the
     * master (and the datastore it is to be on). An explicit {@code NONE} mode of host selection keeps it on the host
     * the master is registered on.
     */
    @Override
    public void cloneOrDeployVm(
            String cloneName,
            String sourceName,
            boolean linkedClone,
            String resourcePoolName,
            String cluster,
            String datastoreName,
            String folderName,
            boolean useCurrentSnapshot,
            String namedSnapshot,
            boolean powerOn,
            Map<String, String> extraConfigParameters,
            String customizationSpec,
            String hostName,
            String hostSelectionMode,
            Set<String> hostSelectionCandidates,
            HostSelectionOptions hostSelectionOptions,
            VmSize vmSize,
            PrintStream jLogger)
            throws VSphereException {
        if (customizationSpec != null && !customizationSpec.trim().isEmpty()) {
            throw new EsxiConstraintException("Over SSH to an ESXi host, a customization specification cannot be"
                    + " used (it was given as \"" + customizationSpec + "\"): leave it empty");
        }
        if (getVmByName(cloneName) != null) {
            throw new VSphereDuplicateException("VM", cloneName);
        }
        final EsxiVirtualMachine master = locate(sourceName);
        if (master == null) {
            throw new VSphereNotFoundException("VM or template", sourceName);
        }
        final VSphereEsxiSsh owner = master.getHost();
        final VmEntry masterEntry = master.getEntry();

        // the hosts that see the files of the master, and the datastore the clone is to be on
        final List<VSphereEsxiSsh> able = new ArrayList<>();
        final List<VSphereEsxiSsh> replicable = new ArrayList<>();
        final List<String> unable = new ArrayList<>();
        final Map<VSphereEsxiSsh, VmEntry> masterOn = new LinkedHashMap<>();
        // The datastore of the master is the same one for the hosts that have the volume with the same UUID, which
        // they may call something else (an NFS share mounted with a label of its own is the same volume)
        String masterVolume = null;
        try {
            for (EsxiDatastoreEntry datastore : owner.listDatastores()) {
                if (datastore.getName().equals(masterEntry.getDatastore())) {
                    masterVolume = datastore.getUuid();
                }
            }
        } catch (VSphereException e) {
            LOGGER.log(Level.FINE, "Asking " + owner.getLabel() + " for its datastores", e);
        }
        for (VSphereEsxiSsh host : availableMembers()) {
            try {
                VmEntry seen = host == owner ? masterEntry : null;
                if (seen == null && masterVolume != null) {
                    for (EsxiDatastoreEntry datastore : host.listDatastores()) {
                        if (datastore.getUuid().equals(masterVolume)
                                && host.fileExists("/vmfs/volumes/" + datastore.getName() + "/"
                                        + masterEntry.getVmxRelativePath())) {
                            seen = masterEntry.onDatastore(datastore.getName());
                        }
                    }
                } else if (seen == null && host.fileExists(masterEntry.getVmxFileSystemPath())) {
                    seen = masterEntry;
                }
                final boolean seesMaster = seen != null;
                if (seen != null) {
                    masterOn.put(host, seen);
                }
                final boolean seesDatastore = datastoreName == null
                        || datastoreName.trim().isEmpty()
                        || host.fileExists("/vmfs/volumes/" + datastoreName.trim());
                if (seesMaster && seesDatastore) {
                    able.add(host);
                } else if (options.replicateMasters && !seesMaster && seesDatastore) {
                    // it can have a replica of the master, and make the clone of that
                    replicable.add(host);
                } else {
                    unable.add(host.getLabel()
                            + (seesMaster
                                    ? " has no datastore " + datastoreName
                                    : " cannot see the files of " + sourceName));
                }
            } catch (VSphereException e) {
                lost(host, e);
                unable.add(host.getLabel() + " (" + e.getMessage() + ")");
            }
        }

        final List<VSphereEsxiSsh> usable = new ArrayList<>(able);
        usable.addAll(replicable);
        List<VSphereEsxiSsh> candidates = usable;
        if (hostName != null && !hostName.trim().isEmpty()) {
            final VSphereEsxiSsh asked = findHost(hostName, usable);
            if (asked == null) {
                throw new VSphereException("The host \"" + hostName + "\" cannot be used for the clone of " + sourceName
                        + ": it is not up, or does not see the files of " + sourceName
                        + (options.replicateMasters ? "" : " (and replicas of masters are not made)") + " (" + unable
                        + ")");
            }
            candidates = List.of(asked);
        } else if (hostSelectionCandidates != null && !hostSelectionCandidates.isEmpty()) {
            final List<VSphereEsxiSsh> allowed = new ArrayList<>();
            for (VSphereEsxiSsh host : usable) {
                if (namedIn(host, hostSelectionCandidates)) {
                    allowed.add(host);
                }
            }
            candidates = allowed;
        }
        if (candidates.isEmpty()) {
            throw new VSphereException("No ESXi host can make the clone of " + sourceName + ": " + unable);
        }

        final VSphereEsxiSsh target;
        if (candidates.size() == 1) {
            target = candidates.get(0);
        } else if (VSphereHostSelection.HOST_SELECTION_MODE_NONE.equals(hostSelectionMode)) {
            target = candidates.contains(owner) ? owner : candidates.get(0);
        } else {
            target = choose(candidates, hostSelectionMode, hostSelectionOptions, master, vmSize, jLogger);
        }
        if (jLogger != null) {
            VSphereLogger.vsLogger(
                    jLogger,
                    "Making the clone \"" + cloneName + "\" of \"" + sourceName + "\" on the ESXi host "
                            + target.getLabel()
                            + (target == owner
                                    ? ""
                                    : replicable.contains(target)
                                            ? ", from a replica"
                                            : ", where it is not registered"));
        }
        if (replicable.contains(target)) {
            final EsxiVirtualMachine replica = EsxiReplica.ensure(
                    owner,
                    masterEntry,
                    stateToReplicate(owner, masterEntry, namedSnapshot, useCurrentSnapshot),
                    target,
                    datastoreName,
                    options.compression,
                    options.idleSeconds,
                    jLogger);
            // the replica is what the clone is of, at its one snapshot, which is the state that was asked for
            target.cloneFrom(
                    null,
                    null,
                    cloneName,
                    replica.getName(),
                    linkedClone,
                    resourcePoolName,
                    cluster,
                    datastoreName,
                    folderName,
                    true,
                    null,
                    powerOn,
                    extraConfigParameters,
                    customizationSpec,
                    vmSize,
                    jLogger);
            return;
        }
        target.cloneFrom(
                target == owner ? null : owner,
                target == owner ? null : masterOn.get(target),
                cloneName,
                sourceName,
                linkedClone,
                resourcePoolName,
                cluster,
                datastoreName,
                folderName,
                useCurrentSnapshot,
                namedSnapshot,
                powerOn,
                extraConfigParameters,
                customizationSpec,
                vmSize,
                jLogger);
    }

    /**
     * The snapshot of the master (by the number the host that has it knows it by) whose state a replica is to have: the
     * one that is named, or the one that the master is at if the clone is of that; none, for the state it has now.
     */
    @CheckForNull
    private static String stateToReplicate(
            VSphereEsxiSsh owner, VmEntry master, @CheckForNull String namedSnapshot, boolean useCurrentSnapshot)
            throws VSphereException {
        final boolean named = namedSnapshot != null && !namedSnapshot.trim().isEmpty();
        if (named && useCurrentSnapshot) {
            throw new VSphereException("It is not valid to name the snapshot \"" + namedSnapshot
                    + "\" to clone AND also say that the latest snapshot is to be used: choose one, or neither");
        }
        if (named) {
            final String id = owner.snapshotIdByName(master, namedSnapshot);
            if (id == null) {
                throw new VSphereNotFoundException("Snapshot", namedSnapshot);
            }
            return id;
        }
        if (!useCurrentSnapshot) {
            return null;
        }
        final String vmsd = EsxiSnapshotMetadata.pathFor(master.getVmxFileSystemPath());
        final String current = owner.files().exists(vmsd)
                ? EsxiSnapshotMetadata.parse(owner.files().read(vmsd)).currentUid()
                : null;
        if (current == null) {
            throw new VSphereNotFoundException(
                    "Snapshot", null, "Source VM \"" + master.getName() + "\" requires at least one snapshot.");
        }
        return current;
    }

    /** The mode that asks for the host with the fewest VMs that are on. */
    public static final String MODE_FEWEST_RUNNING_VMS = "FEWEST_RUNNING_VMS";

    /**
     * Picks the host to make the clone on, by the mode of host selection: {@code FEWEST_RUNNING_VMS}, which is also
     * what no mode means, counts the VMs that are on (then the VMs that are registered); {@code LEAST_LOADED} (and
     * {@code DRS_RECOMMENDED}, which a standalone host has no counterpart for) ranks the hosts by the CPU and
     * memory that they say are used, with the weights of the host selection options, as it does for the hosts of a
     * vCenter cluster, and drops those that are in maintenance mode or too small for the VM if the options ask for
     * that; where no host says how busy it is, the count of VMs decides.
     */
    private VSphereEsxiSsh choose(
            List<VSphereEsxiSsh> candidates,
            @CheckForNull String mode,
            @CheckForNull HostSelectionOptions options,
            EsxiVirtualMachine master,
            @CheckForNull VmSize vmSize,
            @CheckForNull PrintStream log) {
        if (mode == null || mode.isEmpty() || MODE_FEWEST_RUNNING_VMS.equals(mode)) {
            return leastBusy(candidates);
        }
        if ("DRS_RECOMMENDED".equals(mode) && log != null) {
            VSphereLogger.vsLogger(
                    log, "A standalone ESXi host has no DRS to ask; ranking the hosts by how busy they are instead");
        }
        final HostSelectionOptions opts = options == null ? HostSelectionOptions.NONE : options;
        final Map<VSphereHostSelection.HostCandidate, VSphereEsxiSsh> byCandidate = new LinkedHashMap<>();
        for (VSphereEsxiSsh host : candidates) {
            try {
                byCandidate.put(host.candidate(), host);
            } catch (VSphereException e) {
                lost(host, e);
            }
        }
        List<VSphereHostSelection.HostCandidate> usable =
                VSphereHostSelection.filterCandidates(new ArrayList<>(byCandidate.keySet()), null);
        Integer cpus = opts.getVmCpus();
        Long memory = opts.getVmMemoryMB();
        try {
            if (cpus == null) {
                cpus = master.getConfig().getHardware().getNumCPU();
            }
            if (memory == null) {
                memory = (long) master.getConfig().getHardware().getMemoryMB();
            }
        } catch (RuntimeException e) {
            LOGGER.log(Level.FINE, "The size of the master is not known", e);
        }
        final List<VSphereHostSelection.HostCandidate> kept = new ArrayList<>();
        for (VSphereHostSelection.HostCandidate candidate : usable) {
            final String shortfall = VSphereHostSelection.sizeShortfall(
                    candidate,
                    opts.isRequireCores(),
                    cpus,
                    opts.isRequireMemory(),
                    opts.isRequireAvailableMemory(),
                    memory == null ? null : Integer.valueOf(memory.intValue()));
            if (shortfall == null) {
                kept.add(candidate);
            } else if (log != null) {
                VSphereLogger.vsLogger(log, "Not using the ESXi host " + candidate.getName() + ": it " + shortfall);
            }
        }
        final List<VSphereHostSelection.ScoredHost> ranked = VSphereHostSelection.rank(kept, opts.getWeights());
        if (ranked.isEmpty()) {
            if (log != null) {
                VSphereLogger.vsLogger(
                        log,
                        "None of the ESXi hosts says how busy it is, or is fit for the VM; choosing by the number of"
                                + " VMs that are on");
            }
            final List<VSphereEsxiSsh> fit = new ArrayList<>();
            for (VSphereHostSelection.HostCandidate candidate : kept.isEmpty() ? usable : kept) {
                fit.add(byCandidate.get(candidate));
            }
            return leastBusy(fit.isEmpty() ? candidates : fit);
        }
        if (log != null) {
            final StringBuilder said = new StringBuilder("Ranked the ESXi hosts by ")
                    .append(opts.getWeights().isDefault() ? "the lower of free CPU and memory" : "their weights")
                    .append(":");
            for (VSphereHostSelection.ScoredHost scored : ranked) {
                said.append(' ')
                        .append(scored.getHost().getName())
                        .append('=')
                        .append(String.format(java.util.Locale.ROOT, "%.2f", scored.getScore()));
            }
            VSphereLogger.vsLogger(log, said.toString());
        }
        return byCandidate.get(ranked.get(0).getHost());
    }

    private VSphereEsxiSsh leastBusy(List<VSphereEsxiSsh> candidates) {
        VSphereEsxiSsh best = null;
        long[] bestLoad = null;
        for (VSphereEsxiSsh host : candidates) {
            try {
                final long[] load = host.load();
                if (bestLoad == null || load[0] < bestLoad[0] || (load[0] == bestLoad[0] && load[1] < bestLoad[1])) {
                    best = host;
                    bestLoad = load;
                }
            } catch (VSphereException e) {
                lost(host, e);
            }
        }
        if (best == null) {
            return candidates.get(0);
        }
        return best;
    }
}
