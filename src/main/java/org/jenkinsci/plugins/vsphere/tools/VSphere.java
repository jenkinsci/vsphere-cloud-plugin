/*   Copyright 2013, MANDIANT, Eric Lordahl
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
package org.jenkinsci.plugins.vsphere.tools;

import java.io.PrintStream;
import java.net.MalformedURLException;
import java.net.URL;
import java.rmi.RemoteException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.logging.Logger;
import java.util.logging.Level;

import edu.umd.cs.findbugs.annotations.CheckForNull;
import edu.umd.cs.findbugs.annotations.NonNull;

import org.apache.commons.lang3.StringUtils;
import org.jenkinsci.plugins.vsphere.VSphereConnectionConfig;

import com.vmware.vim25.ClusterRecommendation;
import com.vmware.vim25.CustomizationSpecItem;
import com.vmware.vim25.GuestInfo;
import com.vmware.vim25.HostHardwareSummary;
import com.vmware.vim25.HostListSummary;
import com.vmware.vim25.HostListSummaryQuickStats;
import com.vmware.vim25.HostRuntimeInfo;
import com.vmware.vim25.HostSystemConnectionState;
import com.vmware.vim25.InvalidProperty;
import com.vmware.vim25.ManagedObjectReference;
import com.vmware.vim25.OptionValue;
import com.vmware.vim25.PlacementResult;
import com.vmware.vim25.PlacementSpec;
import com.vmware.vim25.RuntimeFault;
import com.vmware.vim25.TaskInfo;
import com.vmware.vim25.TaskInfoState;
import com.vmware.vim25.VirtualMachineCloneSpec;
import com.vmware.vim25.VirtualMachineConfigInfo;
import com.vmware.vim25.VirtualMachineConfigSpec;
import com.vmware.vim25.VirtualMachinePowerState;
import com.vmware.vim25.VirtualMachineQuestionInfo;
import com.vmware.vim25.VirtualMachineRelocateSpec;
import com.vmware.vim25.VirtualMachineSnapshotInfo;
import com.vmware.vim25.VirtualMachineSnapshotTree;
import com.vmware.vim25.VirtualMachineToolsStatus;
import com.vmware.vim25.mo.ClusterComputeResource;
import com.vmware.vim25.mo.CustomizationSpecManager;
import com.vmware.vim25.mo.Datastore;
import com.vmware.vim25.mo.Folder;
import com.vmware.vim25.mo.HostSystem;
import com.vmware.vim25.mo.InventoryNavigator;
import com.vmware.vim25.mo.ManagedEntity;
import com.vmware.vim25.mo.ResourcePool;
import com.vmware.vim25.mo.ServerConnection;
import com.vmware.vim25.mo.ServiceInstance;
import com.vmware.vim25.mo.Task;
import com.vmware.vim25.mo.VirtualMachine;
import com.vmware.vim25.mo.VirtualMachineSnapshot;
import com.vmware.vim25.mo.Datacenter;
import com.vmware.vim25.mo.Network;
import com.vmware.vim25.mo.DistributedVirtualPortgroup;
import com.vmware.vim25.mo.DistributedVirtualSwitch;
import org.jenkinsci.plugins.vsphere.tools.VSphereHostSelection.HostCandidate;

public class VSphere {
    private final URL url;
    private final String session;
    private final static Logger LOGGER = Logger.getLogger(VSphere.class.getName());

    /**
     * When non-null, this instance is managed by a {@link VSphereConnectionPool}:
     * {@link #disconnect()} calls back into {@link VSphereConnectionPool#release()}
     * instead of logging out, so the pool can defer the real disconnect until every
     * borrower has released it.
     */
    private volatile VSphereConnectionPool owningPool = null;

    private VSphere(@NonNull String url, boolean ignoreCert, @NonNull String user, @CheckForNull String pw) throws VSphereException {
        try {
            this.url = new URL(url);
            final ServiceInstance serviceInstance = new ServiceInstance(this.url, user, pw, ignoreCert);
            final ServerConnection serverConnection = serviceInstance.getServerConnection();
            this.session = serverConnection.getSessionStr();
        } catch (Exception e) {
            throw new VSphereException(e);
        }
    }

    private ServiceInstance getServiceInstance() throws RemoteException, MalformedURLException {
        return new ServiceInstance(url, session, true);
    }

    /**
     * Initiates Connection to vSphere Server
     * @param connectionDetails Contains all the details we need to connect.
     * @throws VSphereException If an error occurred.
     * @return A connected instance.
     */
    public static VSphere connect(@NonNull VSphereConnectionConfig connectionDetails) throws VSphereException {
        final String server = connectionDetails.getVsHost() + "/sdk";
        final boolean ignoreCert = connectionDetails.getAllowUntrustedCertificate();
        final String user = connectionDetails.getUsername();
        final String pw = connectionDetails.getPassword();
        return new VSphere(server, ignoreCert, user, pw);
    }

    /**
     * Initiates Connection to vSphere Server
     * @param server Server URL
     * @param ignoreCert If true then we disable certificate verification, allowing the use of untrusted certificates but risk man-in-the-middle attacks.
     * @param user Username.
     * @param pw Password.
     * @throws VSphereException If an error occurred.
     * @return A connected instance.
     * @deprecated Use {@link #connect(VSphereConnectionConfig)} instead.
     */
    @Deprecated
    public static VSphere connect(@NonNull String server, boolean ignoreCert, @NonNull String user, @CheckForNull String pw) throws VSphereException {
        return new VSphere(server, ignoreCert, user, pw);
    }

    /**
     * Disconnect from vSphere server.
     * <p>
     * When this instance is managed by a {@link VSphereConnectionPool}, this instead
     * signals the pool that this caller is done with it (via
     * {@link VSphereConnectionPool#release()}); the pool decides when the underlying
     * session actually gets logged out.
     * </p>
     * <p>
     * Note: This logs any {@link Exception} it encounters - it does not pass
     * them to get to the calling method.
     * </p>
     */
    public void disconnect() {
        final VSphereConnectionPool pool = owningPool;
        if (pool != null) {
            pool.release();
            return;
        }
        try {
            this.getServiceInstance().getServerConnection().logout();
        } catch (Exception e) {
            LOGGER.log(Level.WARNING, "Caught exception when trying to disconnect vSphere.", e);
        }
    }

    /**
     * Marks this instance as owned by {@code pool}, so that {@link #disconnect()}
     * releases it back to the pool instead of logging out directly.
     * Package-private — only {@link VSphereConnectionPool} should call this.
     */
    void markAsPooled(VSphereConnectionPool pool) {
        owningPool = pool;
    }

    /**
     * Disconnects the underlying session regardless of pooled status.
     * Called by {@link VSphereConnectionPool} when it actually wants to tear down
     * the session (restart, idle timeout, shutdown).
     * Package-private — only {@link VSphereConnectionPool} should call this.
     */
    void forceDisconnect() {
        owningPool = null;
        disconnect();
    }

    /**
     * Checks whether the current vSphere session is still alive by issuing a
     * lightweight {@code currentTime()} call.
     *
     * @return {@code true} if the session responds normally; {@code false} if it
     *         has expired or the server is unreachable.
     */
    public boolean isSessionAlive() {
        try {
            getServiceInstance().currentTime();
            return true;
        } catch (Exception e) {
            LOGGER.log(Level.FINE, "vSphere session alive-check failed", e);
            return false;
        }
    }

    /**
     * Deploys a new VM from an existing (named) Template.
     *
     * @param cloneName - name of VM to be created
     * @param sourceName - name of VM or template to be cloned
     * @param linkedClone - true if you want to re-use disk backings
     * @param resourcePoolName - resource pool to use
     * @param cluster - ComputeClusterResource to use
     * @param datastoreName - Datastore to use
     * @param folderName - Folder name or path to use
     * @param powerOn - If true the VM will be powered on.
     * @param customizationSpec - Customization spec to use for this VM
     * @param jLogger - Where to log to.
     * @throws VSphereException If an error occurred.
     */
    public void deployVm(String cloneName, String sourceName, boolean linkedClone, String resourcePoolName, String cluster, String datastoreName, String folderName, boolean powerOn, String customizationSpec, PrintStream jLogger) throws VSphereException {
        deployVm(cloneName, sourceName, linkedClone, resourcePoolName, cluster, datastoreName, folderName, powerOn, customizationSpec, null, null, null, jLogger);
    }

    /**
     * Deploys a new VM from an existing template, with control over which ESXi host the clone
     * is placed on. See {@link #cloneOrDeployVm} for the meaning of {@code host}, {@code
     * hostSelectionMode} and {@code hostSelectionCandidates}.
     *
     * @throws VSphereException If an error occurred.
     */
    public void deployVm(String cloneName, String sourceName, boolean linkedClone, String resourcePoolName, String cluster, String datastoreName, String folderName, boolean powerOn, String customizationSpec, String host, String hostSelectionMode, Set<String> hostSelectionCandidates, PrintStream jLogger) throws VSphereException {
        final boolean useCurrentSnapshotIsFALSE = false;
        final String namedSnapshotIsNULL = null;
        final Map<String, String> extraConfigParameters = null;
        cloneOrDeployVm(cloneName, sourceName, linkedClone, resourcePoolName, cluster, datastoreName, folderName, useCurrentSnapshotIsFALSE, namedSnapshotIsNULL, powerOn, extraConfigParameters, customizationSpec, host, hostSelectionMode, hostSelectionCandidates, jLogger);
    }

    /**
     * Clones a new VM from an existing (named) VM.
     *
     * @param cloneName - name of VM to be created
     * @param sourceName - name of VM or template to be cloned
     * @param linkedClone - true if you want to re-use disk backings
     * @param resourcePoolName - resource pool to use
     * @param cluster - ComputeClusterResource to use
     * @param datastoreName - Datastore to use
     * @param folderName - Folder name or path to use
     * @param powerOn - If true the VM will be powered on.
     * @param customizationSpec - Customization spec to use for this VM
     * @param jLogger - Where to log to.
     * @throws VSphereException If an error occurred.
     */
    public void cloneVm(String cloneName, String sourceName, boolean linkedClone, String resourcePoolName, String cluster, String datastoreName, String folderName, boolean powerOn, String customizationSpec, PrintStream jLogger) throws VSphereException {
        cloneVm(cloneName, sourceName, linkedClone, resourcePoolName, cluster, datastoreName, folderName, powerOn, customizationSpec, null, null, null, jLogger);
    }

    /**
     * Clones a new VM from an existing (named) VM, with control over which ESXi host the clone
     * is placed on. See {@link #cloneOrDeployVm} for the meaning of {@code host}, {@code
     * hostSelectionMode} and {@code hostSelectionCandidates}.
     *
     * @throws VSphereException If an error occurred.
     */
    public void cloneVm(String cloneName, String sourceName, boolean linkedClone, String resourcePoolName, String cluster, String datastoreName, String folderName, boolean powerOn, String customizationSpec, String host, String hostSelectionMode, Set<String> hostSelectionCandidates, PrintStream jLogger) throws VSphereException {
        final boolean useCurrentSnapshotIsTRUE = true;
        final String namedSnapshotIsNULL = null;
        final Map<String, String> extraConfigParameters = null;
        cloneOrDeployVm(cloneName, sourceName, linkedClone, resourcePoolName, cluster, datastoreName, folderName, useCurrentSnapshotIsTRUE, namedSnapshotIsNULL, powerOn, extraConfigParameters, customizationSpec, host, hostSelectionMode, hostSelectionCandidates, jLogger);
    }

    /**
     * Creates a new VM by cloning an existing VM or Template.
     * 
     * @param cloneName
     *            The name for the new VM.
     * @param sourceName
     *            The name of the VM or Template that is to be cloned.
     * @param linkedClone
     *            If true then the clone will be defined as a delta from the
     *            original, rather than a "full fat" copy. If this is true then
     *            you will need to use a snapshot.
     * @param resourcePoolName
     *            (Optional) The name of the resource pool to use, or null.
     * @param cluster
     *            (Optional) The name of the cluster, or null.
     * @param datastoreName
     *            (Optional) The name of the data store, or null.
     * @param folderName
     *            (Optional) The name or path of the VSphere folder, or null
     * @param useCurrentSnapshot
     *            If true then the clone will be created from the source VM's
     *            "current" snapshot. This means that the VM <em>must</em> have
     *            at least one snapshot.
     * @param namedSnapshot
     *            If set then the clone will be created from the source VM's
     *            snapshot of this name. If this is set then
     *            <code>useCurrentSnapshot</code> must not be set.
     * @param powerOn
     *            If true then the new VM will be switched on after it has been
     *            created.
     * @param extraConfigParameters
     *            (Optional) parameters to set in the VM's "extra config"
     *            object. This data can then be read back at a later stage.In
     *            the case of parameters whose name starts "guestinfo.", the
     *            parameter can be read by the VMware Tools on the client OS.
     *            e.g. a variable named "guestinfo.Foo" with value "Bar" could
     *            be read on the guest using the command-line
     *            {@code vmtoolsd --cmd "info-get guestinfo.Foo"}.
     * @param customizationSpec
     *            (Optional) Customization spec to use for this VM, or null
     * @param jLogger
     *            Where to log to.
     * @throws VSphereException
     *             if anything goes wrong.
     */
    public void cloneOrDeployVm(String cloneName, String sourceName, boolean linkedClone, String resourcePoolName, String cluster, String datastoreName, String folderName, boolean useCurrentSnapshot, final String namedSnapshot, boolean powerOn, Map<String, String> extraConfigParameters, String customizationSpec, PrintStream jLogger) throws VSphereException {
        cloneOrDeployVm(cloneName, sourceName, linkedClone, resourcePoolName, cluster, datastoreName, folderName, useCurrentSnapshot, namedSnapshot, powerOn, extraConfigParameters, customizationSpec, null, null, null, jLogger);
    }

    /**
     * Creates a new VM by cloning an existing VM or Template, with control over which ESXi host
     * the clone is placed on. Without this, clones are placed wherever vCenter's own default
     * logic decides (in practice, often the same host the source VM/template is registered on),
     * which can cause load imbalance across a cluster.
     *
     * @param host
     *            (Optional) The name of a specific ESXi host to place the clone on. When set,
     *            this always wins and {@code hostSelectionMode} is ignored. Works regardless of
     *            vSphere edition/license and regardless of DRS configuration.
     * @param hostSelectionMode
     *            (Optional) When {@code host} is not set, how to automatically pick a host:
     *            {@code null}/empty for unchanged legacy behaviour (let vCenter decide),
     *            {@code "LEAST_LOADED"} to have this plugin rank candidate hosts in {@code
     *            cluster} by current CPU/memory usage and pick the least loaded one, or
     *            {@code "DRS_RECOMMENDED"} to ask vCenter's own DRS engine for a placement
     *            recommendation restricted to the candidate hosts (requires DRS to be enabled
     *            and licensed on the cluster; falls back to {@code "LEAST_LOADED"} behaviour
     *            if DRS is unavailable or returns no recommendation).
     * @param hostSelectionCandidates
     *            (Optional) Set of host names that {@code hostSelectionMode} is allowed to
     *            consider; other hosts in the cluster are ignored even if they would otherwise
     *            be a better pick. Use this when the vCenter account used for cloning does not
     *            have provisioning permission on every host in the cluster. Empty/null means
     *            every (usable) host in {@code cluster} is a candidate. Callers holding a
     *            comma-separated string can use {@link VSphereHostSelection#parseAllowList}.
     * @throws VSphereException
     *             if anything goes wrong.
     */
    public void cloneOrDeployVm(String cloneName, String sourceName, boolean linkedClone, String resourcePoolName, String cluster, String datastoreName, String folderName, boolean useCurrentSnapshot, final String namedSnapshot, boolean powerOn, Map<String, String> extraConfigParameters, String customizationSpec, String host, String hostSelectionMode, Set<String> hostSelectionCandidates, PrintStream jLogger) throws VSphereException {
        if (namedSnapshot == null && extraConfigParameters == null) {
            // NOTE: This "if" clause may be superfluous - just that previously
            // this message was only logged by cloneVm() or deployVm()... so for
            // least surprise and unexpected noise in the logs, effectively kept
            // so for upgraded plugins where we can also directly call this method
            // as a "buildStep" under a "vSphere" pipeline step.
            if (useCurrentSnapshot) {
                // Called from cloneVm() above.
                logMessage(jLogger, "Creating a " + (linkedClone ? "shallow" : "deep") + " clone of \"" + sourceName + "\" to \"" + cloneName + "\"");
            } else {
                // Called from deployVm() above.
                logMessage(jLogger, "Deploying new vm \""+ cloneName + "\" from template \""+sourceName+"\"");
            }
        }

        try {
            final VirtualMachine sourceVm = getVmByName(sourceName);
            if (sourceVm==null) {
                throw new VSphereNotFoundException("VM or template", sourceName);
            }
            if (getVmByName(cloneName)!=null) {
                throw new VSphereDuplicateException("VM", cloneName);
            }

            final VirtualMachineConfigInfo vmConfig = sourceVm.getConfig();
            final boolean sourceIsATemplate = vmConfig.template;
            final String sourceType = sourceIsATemplate?"Template":"VM";
            final VirtualMachineRelocateSpec rel = createRelocateSpec(jLogger, linkedClone, resourcePoolName, cluster, datastoreName, sourceIsATemplate);
            final VirtualMachineCloneSpec cloneSpec = createCloneSpec(rel);
            cloneSpec.setTemplate(false);
            cloneSpec.powerOn = powerOn;

            if (namedSnapshot != null && !namedSnapshot.isEmpty()) {
                if (useCurrentSnapshot) {
                    throw new IllegalArgumentException("It is not valid to request a clone of " + sourceType + " \"" + sourceName + "\" based on its snapshot \"" + namedSnapshot + "\" AND also specify that the latest snapshot should be used.  Either choose to use the latest snapshot, or name a snapshot, or neither, but not both.");
                }
                final VirtualMachineSnapshot namedVMSnapshot = getSnapshotInTree(sourceVm, namedSnapshot);
                if (namedVMSnapshot == null) {
                    throw new VSphereNotFoundException("Snapshot", namedSnapshot, "Source " + sourceType + " \"" + sourceName + "\" has no snapshot called \"" + namedSnapshot + "\".");
                }
                logMessage(jLogger, "Clone of " + sourceType + " \"" + sourceName + "\" will be based on named snapshot \"" + namedSnapshot + "\".");
                cloneSpec.setSnapshot(namedVMSnapshot.getMOR());
            }
            if (useCurrentSnapshot) {
                final VirtualMachineSnapshot currentSnapShot = sourceVm.getCurrentSnapShot();
                if (currentSnapShot==null) {
                    throw new VSphereNotFoundException("Snapshot", null, "Source " + sourceType + " \"" + sourceName + "\" requires at least one snapshot.");
                }
                logMessage(jLogger, "Clone of " + sourceType + " \"" + sourceName + "\" will be based on current snapshot \"" + currentSnapShot.toString() + "\".");
                cloneSpec.setSnapshot(currentSnapShot.getMOR());
            }
            if (extraConfigParameters != null && !extraConfigParameters.isEmpty()) {
                logMessage(jLogger, "Clone of " + sourceType + " \"" + sourceName + "\" will have extra configuration parameters " + extraConfigParameters + ".");
                VirtualMachineConfigSpec cs = createVMConfigSpecFromExtraConfigParameters(extraConfigParameters);
                cloneSpec.setConfig(cs);
            }
            if (customizationSpec != null && customizationSpec.length() > 0) {
                logMessage(jLogger, "Clone of " + sourceType + " \"" + sourceName + "\" will use customization specification \"" + customizationSpec + "\".");
                CustomizationSpecItem spec = getCustomizationSpecByName(customizationSpec);
                cloneSpec.setCustomization(spec.getSpec());
            }

            Folder folder;
            if (folderName == null || folderName.isEmpty() || folderName.equals(" ")) {
                //same folder as source
                folder = (Folder) sourceVm.getParent();
            } else if (!folderExists(folderName)) {
                folder = (Folder) sourceVm.getParent();
                logMessage(jLogger, "Unable to find the specified folder. Creating VM in the same folder as its parent ");
            } else {
                folder = getFolder(folderName);
            }

            final HostSystem selectedHost = selectHost(jLogger, getClusterByName(cluster), sourceVm, cloneName, cloneSpec, rel, host, hostSelectionMode, hostSelectionCandidates);
            if (selectedHost != null) {
                rel.setHost(selectedHost.getMOR());
                logMessage(jLogger, "Clone of " + sourceType + " \"" + sourceName + "\" will be placed on host \"" + selectedHost.getName() + "\".");
            }

            final Task task = sourceVm.cloneVM_Task(folder,
                    cloneName, cloneSpec);
            logMessage(jLogger, "Started cloning of " + sourceType + " \"" + sourceName + "\". Please wait ...");

            final String status = task.waitForTask();
            if (!TaskInfoState.success.toString().equals(status)) {
                throw newVSphereException(task.getTaskInfo(), "Couldn't clone \""+ sourceName +"\". " +
                        "Clone task ended with status " + status + ".");
            }
            logMessage(jLogger, "Successfully cloned VM \"" + sourceName + "\" to create \"" + cloneName + "\".");
        } catch(RuntimeException | VSphereException e) {
            throw e;
        } catch(Exception e) {
            throw new VSphereException(e);
        }
    }

    private VirtualMachineCloneSpec createCloneSpec(VirtualMachineRelocateSpec rel) {
        VirtualMachineCloneSpec cloneSpec = new VirtualMachineCloneSpec();
        cloneSpec.setLocation(rel);
        cloneSpec.setTemplate(false);
        cloneSpec.setPowerOn(true);
        return cloneSpec;
    }

    private VirtualMachineRelocateSpec createRelocateSpec(PrintStream jLogger, boolean linkedClone, String resourcePoolName,
            String cluster, String datastoreName, boolean isResourcePoolRequired) throws RemoteException, MalformedURLException, VSphereException {
        VirtualMachineRelocateSpec rel  = new VirtualMachineRelocateSpec();

        if (linkedClone) {
            rel.setDiskMoveType("createNewChildDiskBacking");
        } else {
            rel.setDiskMoveType("moveAllDiskBackingsAndDisallowSharing");
        }

        ClusterComputeResource clusterResource = getClusterByName(cluster);

        // probably only of interest if someone actually entered a cluster name
        if (clusterResource == null && StringUtils.isNotBlank(cluster)) {
            logMessage(jLogger, "Cluster resource " + cluster + " does not exist, root folder will be used for getting resource pool and datastore");
        }

        if (resourcePoolName != null && !resourcePoolName.isEmpty()) {
            ResourcePool resourcePool = getResourcePoolByName(resourcePoolName, clusterResource);
            if (resourcePool == null) {
                throw new VSphereNotFoundException("Resource pool", resourcePoolName);
            }
            rel.setPool(resourcePool.getMOR());
        } else if (isResourcePoolRequired) {
            throw new VSphereException("You must specify a resource  pool  when using a template");
        }

        if (datastoreName != null && !datastoreName.isEmpty()) {
            Datastore datastore = getDatastoreByName(datastoreName, clusterResource);
            if (datastore==null) {
                throw new VSphereNotFoundException("Datastore", datastoreName);
            }
            rel.setDatastore(datastore.getMOR());
        }
       return rel;
    }

    /**
     * Checks whether a host with this name exists anywhere in the vCenter inventory. Used by
     * build-step/config live-validation ("Check Data"/"Check Template" buttons) for the
     * {@code host}/{@code targetHost} and {@code hostSelectionCandidates} fields.
     *
     * @throws VSphereException If an error occurred while querying vCenter.
     */
    public boolean hostExists(final String hostName) throws VSphereException {
        try {
            return getHostByName(hostName, null) != null;
        } catch (Exception e) {
            throw new VSphereException(e);
        }
    }

    /**
     * @param hostName - Name of host to find
     * @param rootEntity - managed entity to search, or null to search the whole inventory
     * @return - HostSystem object, or null if not found
     */
    private HostSystem getHostByName(final String hostName, ManagedEntity rootEntity) throws InvalidProperty, RuntimeFault, RemoteException, MalformedURLException {
        if (rootEntity == null) rootEntity = getServiceInstance().getRootFolder();

        return (HostSystem) new InventoryNavigator(
                rootEntity).searchManagedEntity(
                        "HostSystem", hostName);
    }

    /**
     * If the vCenter inventory contains exactly one cluster, returns it - so that host
     * selection can still work when the caller didn't (or couldn't be bothered to)
     * specify a {@code cluster}. Returns null if there are zero or multiple clusters,
     * since then there's no way to pick one automatically.
     */
    private ClusterComputeResource getSingleClusterIfUnambiguous(PrintStream jLogger) throws InvalidProperty, RuntimeFault, RemoteException, MalformedURLException {
        final ManagedEntity[] allClusters = new InventoryNavigator(
                getServiceInstance().getRootFolder()).searchManagedEntities("ClusterComputeResource");
        if (allClusters == null || allClusters.length == 0) {
            logMessage(jLogger, "No cluster was specified, and no cluster exists in this vCenter's inventory; cannot auto-select a host.");
            return null;
        }
        if (allClusters.length > 1) {
            logMessage(jLogger, "No cluster was specified, and " + allClusters.length + " clusters exist in this vCenter's inventory; specify \"cluster\" explicitly to enable host selection.");
            return null;
        }
        final ClusterComputeResource onlyCluster = (ClusterComputeResource) allClusters[0];
        logMessage(jLogger, "No cluster was specified; auto-detected the only cluster in this vCenter's inventory: \"" + onlyCluster.getName() + "\".");
        return onlyCluster;
    }

    /**
     * Decides which ESXi host (if any) a clone should be placed on. Returns null to mean
     * "no restriction, let vCenter/DRS decide with its own default logic" (today's behaviour).
     */
    private HostSystem selectHost(PrintStream jLogger, ClusterComputeResource clusterResource, VirtualMachine sourceVm,
            String cloneName, VirtualMachineCloneSpec cloneSpec, VirtualMachineRelocateSpec rel,
            String host, String hostSelectionMode, Set<String> hostSelectionCandidates)
            throws InvalidProperty, RuntimeFault, RemoteException, MalformedURLException, VSphereException {
        if (host != null && !host.isEmpty()) {
            HostSystem explicitHost = getHostByName(host, clusterResource);
            if (explicitHost == null) {
                throw new VSphereNotFoundException("Host", host);
            }
            return explicitHost;
        }

        if (hostSelectionMode == null || hostSelectionMode.isEmpty()) {
            return null;
        }

        if (clusterResource == null) {
            clusterResource = getSingleClusterIfUnambiguous(jLogger);
        }
        if (clusterResource == null) {
            logMessage(jLogger, "Host selection mode \"" + hostSelectionMode + "\" requires a valid cluster to be specified (or exactly one cluster to exist in the vCenter inventory); letting vSphere decide placement.");
            return null;
        }

        final HostSystem[] members = clusterResource.getHost();
        final List<HostSystem> hostSystems = new ArrayList<>();
        final List<HostCandidate> candidates = new ArrayList<>();
        if (members != null) {
            for (HostSystem hostSystem : members) {
                hostSystems.add(hostSystem);
                candidates.add(toHostCandidate(hostSystem));
            }
        }

        final List<HostCandidate> filtered = VSphereHostSelection.filterCandidates(candidates, hostSelectionCandidates);
        if (filtered.isEmpty()) {
            logMessage(jLogger, "No usable candidate hosts found in cluster \"" + clusterResource.getName() + "\" for host selection mode \"" + hostSelectionMode + "\"; letting vSphere decide placement.");
            return null;
        }

        if ("DRS_RECOMMENDED".equals(hostSelectionMode)) {
            HostSystem recommended = recommendHostViaDrs(jLogger, clusterResource, sourceVm, cloneName, cloneSpec, rel, hostSystems, filtered);
            if (recommended != null) {
                return recommended;
            }
            logMessage(jLogger, "DRS placement recommendation was unavailable (DRS may be disabled or unlicensed on this cluster); falling back to least-loaded host selection.");
        }

        final HostCandidate winner = VSphereHostSelection.pickLeastLoaded(filtered);
        if (winner == null) {
            logMessage(jLogger, "Unable to determine current load for any candidate host; letting vSphere decide placement.");
            return null;
        }
        return findHostSystemByName(hostSystems, winner.getName());
    }

    /**
     * Asks vCenter's own DRS engine for a placement recommendation, restricted to the given
     * (already permission/availability-filtered) candidate hosts. Returns null - never throws -
     * if DRS is unavailable, unlicensed, disabled on the cluster, or gives no recommendation, so
     * callers can fall back to a simpler heuristic.
     */
    private HostSystem recommendHostViaDrs(PrintStream jLogger, ClusterComputeResource clusterResource, VirtualMachine sourceVm,
            String cloneName, VirtualMachineCloneSpec cloneSpec, VirtualMachineRelocateSpec rel,
            List<HostSystem> hostSystems, List<HostCandidate> filtered) {
        try {
            final ManagedObjectReference[] candidateMors = new ManagedObjectReference[filtered.size()];
            for (int i = 0; i < filtered.size(); i++) {
                HostSystem hostSystem = findHostSystemByName(hostSystems, filtered.get(i).getName());
                if (hostSystem == null) {
                    return null;
                }
                candidateMors[i] = hostSystem.getMOR();
            }

            final PlacementSpec placementSpec = new PlacementSpec();
            placementSpec.setPlacementType("clone");
            placementSpec.setVm(sourceVm.getMOR());
            placementSpec.setCloneName(cloneName);
            placementSpec.setCloneSpec(cloneSpec);
            placementSpec.setRelocateSpec(rel);
            placementSpec.setHosts(candidateMors);

            final PlacementResult result = clusterResource.placeVm(placementSpec);
            if (result == null || result.getRecommendations() == null || result.getRecommendations().length == 0) {
                return null;
            }

            ClusterRecommendation best = null;
            for (ClusterRecommendation recommendation : result.getRecommendations()) {
                if (best == null || recommendation.getRating() > best.getRating()) {
                    best = recommendation;
                }
            }
            if (best == null || best.getTarget() == null) {
                return null;
            }
            for (HostSystem hostSystem : hostSystems) {
                if (hostSystem.getMOR().equals(best.getTarget())) {
                    return hostSystem;
                }
            }
            return null;
        } catch (Exception e) {
            LOGGER.log(Level.WARNING, "DRS placement recommendation failed, will fall back to least-loaded host selection", e);
            return null;
        }
    }

    private HostCandidate toHostCandidate(HostSystem hostSystem) {
        final HostListSummary summary = hostSystem.getSummary();
        final HostRuntimeInfo runtime = hostSystem.getRuntime();
        final boolean connected = runtime != null && runtime.getConnectionState() == HostSystemConnectionState.connected;
        final boolean inMaintenanceMode = runtime != null && runtime.isInMaintenanceMode();

        Integer cpuUsageMhz = null;
        Integer memUsageMB = null;
        int cpuCapacityMhz = 0;
        long memCapacityMB = 0L;
        if (summary != null) {
            final HostListSummaryQuickStats quickStats = summary.getQuickStats();
            if (quickStats != null) {
                cpuUsageMhz = quickStats.getOverallCpuUsage();
                memUsageMB = quickStats.getOverallMemoryUsage();
            }
            final HostHardwareSummary hardware = summary.getHardware();
            if (hardware != null) {
                cpuCapacityMhz = hardware.getCpuMhz() * hardware.getNumCpuCores();
                memCapacityMB = hardware.getMemorySize() / (1024L * 1024L);
            }
        }
        return new HostCandidate(hostSystem.getName(), connected, inMaintenanceMode, cpuUsageMhz, cpuCapacityMhz, memUsageMB, memCapacityMB);
    }

    private static HostSystem findHostSystemByName(List<HostSystem> hostSystems, String name) {
        for (HostSystem hostSystem : hostSystems) {
            if (hostSystem.getName().equals(name)) {
                return hostSystem;
            }
        }
        return null;
    }

    public void reconfigureVm(String name, VirtualMachineConfigSpec spec) throws VSphereException {
        VirtualMachine vm = getVmByName(name);
        if (vm==null) {
            throw new VSphereNotFoundException("VM or template", name);
        }
        LOGGER.log(Level.FINER, "Reconfiguring VM. Please wait ...");
        try {
            Task task = vm.reconfigVM_Task(spec);
            String status = task.waitForTask();
            if (status.equals(TaskInfoState.success.toString())) {
                return;
            }
            throw newVSphereException(task.getTaskInfo(), "Couldn't reconfigure \""+ name +"\"!");
        } catch(RuntimeException | VSphereException e) {
            throw e;
        } catch(Exception e) {
            throw new VSphereException("VM cannot be reconfigured:" + e.getMessage(), e);
        }
    }

    /**
     * @param name - Name of VM to start
     * @param timeoutInSeconds How long to wait for the VM to be running.
     * @throws VSphereException If an error occurred.
     */
    public void startVm(String name, int timeoutInSeconds) throws VSphereException {
        try {
            VirtualMachine vm = getVmByName(name);
            if (vm == null) {
                throw new VSphereNotFoundException("VM", name);
            }
            if (isPoweredOn(vm))
                return;

            if (vm.getConfig().template)
                throw new VSphereException("VM represents a template!");

            Task task = vm.powerOnVM_Task(null);

            int timesToCheck = timeoutInSeconds / 5;
            // add one extra time for remainder
            timesToCheck++;
            LOGGER.log(Level.FINER, "Checking " + timesToCheck + " times for vm to be powered on");

            for (int i=0; i<timesToCheck; i++) {
                if (task.getTaskInfo().getState()==TaskInfoState.success) {
                    LOGGER.log(Level.FINER, "VM was powered up successfully.");
                    return;
                }
                if (task.getTaskInfo().getState()==TaskInfoState.running ||
                        task.getTaskInfo().getState()==TaskInfoState.queued) {
                    Thread.sleep(5000);
                }
                //Check for copied/moved question
                VirtualMachineQuestionInfo q = vm.getRuntime().getQuestion();
                if (q!=null && q.getId().equals("_vmx1")) {
                    vm.answerVM(q.getId(), q.getChoice().getDefaultIndex().toString());
                    return;
                }
            }
        } catch(InterruptedException e) { // build aborted
            Thread.currentThread().interrupt(); // pass interrupt upwards
            throw new VSphereException("VM cannot be started: " + e.getMessage(), e);
        } catch(Exception e) {
            throw new VSphereException("VM cannot be started: " + e.getMessage(), e);
        }

        throw new VSphereException("VM cannot be started");
    }

    private ManagedObjectReference findSnapshotInTree(
            VirtualMachineSnapshotTree[] snapTree, String snapName) {
        LOGGER.log(Level.FINER, "Looking for snapshot " + snapName);
        for (VirtualMachineSnapshotTree node : snapTree) {
            if (snapName.equals(node.getName())) {
                return node.getSnapshot();
            } else {
                VirtualMachineSnapshotTree[] childTree =
                        node.getChildSnapshotList();
                if (childTree != null) {
                    ManagedObjectReference mor = findSnapshotInTree(
                            childTree, snapName);
                    if (mor != null) {
                        return mor;
                    }
                }
            }
        }
        return null;
    }

    public VirtualMachineSnapshot getSnapshotInTree(
            VirtualMachine vm, String snapName) {
        if (vm == null || snapName == null) {
            return null;
        }

        LOGGER.log(Level.FINER, "Looking for snapshot " + snapName + " in " + vm.getName() );
        VirtualMachineSnapshotInfo info = vm.getSnapshot();
        if (info != null) {
            VirtualMachineSnapshotTree[] snapTree =
                    info.getRootSnapshotList();
            if (snapTree != null) {
                ManagedObjectReference mor = findSnapshotInTree(
                        snapTree, snapName);
                if (mor != null) {
                    return new VirtualMachineSnapshot(
                            vm.getServerConnection(), mor);
                }
            }
        }

        return null;
    }

    public void revertToSnapshot(String vmName, String snapName) throws VSphereException {
        revertToSnapshot(vmName, snapName, false);
    }

    public void revertToSnapshot(String vmName, String snapName, boolean suppressPowerOn) throws VSphereException {

        VirtualMachine vm = getVmByName(vmName);
        VirtualMachineSnapshot snap = getSnapshotInTree(vm, snapName);

        if (snap == null) {
            LOGGER.log(Level.SEVERE, "Cannot find snapshot: '" + snapName + "' for virtual machine: '" + vm.getName()+"'");
            throw new VSphereNotFoundException("Snapshot", snapName);
        }

        try {
            Task task = snap.revertToSnapshot_Task(null, Boolean.valueOf(suppressPowerOn));
            if (!task.waitForTask().equals(Task.SUCCESS)) {
                final String msg = "Could not revert to snapshot '" + snap.toString() + "' for virtual machine:'" + vm.getName()+"'";
                LOGGER.log(Level.SEVERE, msg);
                throw newVSphereException(task.getTaskInfo(), msg);
            }
        } catch(RuntimeException | VSphereException e) {
            throw e;
        } catch(Exception e) {
            throw new VSphereException(e);
        }
    }

    public void deleteSnapshot(String vmName, String snapName, boolean consolidate, boolean failOnNoExist) throws VSphereException {

        VirtualMachine vm = getVmByName(vmName);
        VirtualMachineSnapshot snap = getSnapshotInTree(vm, snapName);

        if (snap == null && failOnNoExist) {
            throw new VSphereNotFoundException("Snapshot", snapName);
        }

        try {
            Task task;
            if (snap!=null) {
                //Does not delete subtree; Implicitly consolidates disk
                task = snap.removeSnapshot_Task(false);
                if (!task.waitForTask().equals(Task.SUCCESS)) {
                    throw newVSphereException(task.getTaskInfo(), "Could not delete snapshot");
                }
            }

            if (!consolidate)
                return;

            //This might be redundant, but I think it consolidates all disks,
            //where as the removeSnapshot only consolidates the individual disk
            task = vm.consolidateVMDisks_Task();
            if (!task.waitForTask().equals(Task.SUCCESS)) {
                throw newVSphereException(task.getTaskInfo(), "Could not consolidate VM disks");
            }
        } catch(RuntimeException | VSphereException e) {
            throw e;
        } catch(Exception e) {
            throw new VSphereException(e);
        }
    }

    public void takeSnapshot(String vmName, String snapshot, String description, boolean snapMemory) throws VSphereException {

        final String message = "Could not take snapshot";
        VirtualMachine vmToSnapshot = getVmByName(vmName);
        if (vmToSnapshot == null) {
            throw new VSphereNotFoundException("VM", vmName);
        }
        try {
            Task task = vmToSnapshot.createSnapshot_Task(snapshot, description, snapMemory, !snapMemory);
            if (task.waitForTask().equals(Task.SUCCESS)) {
                return;
            }
            throw newVSphereException(task.getTaskInfo(), message);
        } catch(RuntimeException | VSphereException e) {
            throw e;
        } catch (Exception e) {
            throw new VSphereException(message, e);
        }
    }

    public void markAsTemplate(String vmName, String snapName, boolean force) throws VSphereException {

        final String message = "Could not mark as Template. Check it's power state or select \"force.\"";
        try {
            VirtualMachine vm = getVmByName(vmName);
            if (vm.getConfig().template)
                return;

            if (isPoweredOff(vm) || force) {
                powerOffVm(vm, force, 0);
                vm.markAsTemplate();
                return;
            }
        } catch(Exception e) {
            throw new VSphereException(message, e);
        }
        throw new VSphereException(message);
    }

    public void markAsVm(String name, String resourcePool, String cluster) throws VSphereException {
        try {
            VirtualMachine vm = getVmByName(name);
            if (vm.getConfig().template) {
                vm.markAsVirtualMachine(
                        getResourcePoolByName(resourcePool, getClusterByName(cluster)),
                        null
                        );
            }
        } catch(Exception e) {
            throw new VSphereException("Could not convert to VM", e);
        }
    }

    /**
     * Asks vSphere for the IP address used by a VM.
     * 
     * @param vm VirtualMachine name whose IP is to be returned.
     * @param timeout How long to wait (in seconds) for the IP address to known to vSphere.
     * @return String containing IP address.
     * @throws VSphereException If an error occurred.
     */
    public String getIp(VirtualMachine vm, int timeout) throws VSphereException {

        if (vm==null)
            throw new VSphereException("VM is null");

        //Determine how many attempts will be made to fetch the IP address
        final int waitSeconds = 5;
        final int maxTries;
        if (timeout<=waitSeconds)
            maxTries = 1;
        else
            maxTries = (int) Math.round((double)timeout / waitSeconds);

        for(int count=0; count<maxTries; count++) {

            GuestInfo guestInfo = vm.getGuest();

            // guest info can be null sometimes
            if (guestInfo != null && guestInfo.getIpAddress() != null) {
                return guestInfo.getIpAddress();
            }

            try {
                //wait
                Thread.sleep(waitSeconds * 1000);
            } catch (InterruptedException e) { // build aborted
                Thread.currentThread().interrupt(); // pass interrupt upwards
                break; // and abort our activities now.
            }
        }
        return null;
    }

    /**
     * @param vmName - name of VM object to retrieve
     * @return - VirtualMachine object
     * @throws VSphereException If an error occurred.
     */
    public VirtualMachine getVmByName(String vmName) throws VSphereException {
        try {
            return (VirtualMachine) new InventoryNavigator(
                    getServiceInstance().getRootFolder()).searchManagedEntity(
                            "VirtualMachine", vmName);
        } catch (Exception e) {
            throw new VSphereException(e);
        }
    }

    public int countVms() throws VSphereException {
        int count = 0;
        try {
            final InventoryNavigator navigator = new InventoryNavigator(getServiceInstance().getRootFolder());
            final ManagedEntity[] entities = navigator.searchManagedEntities(false);
            count = entities.length;
        } catch (Exception ex) {
            throw new VSphereException(ex);
        }
        return count;
    }

    public int countVmsByPrefix(final String prefix) throws VSphereException {
        int count = 0;
        try {
            final InventoryNavigator navigator = new InventoryNavigator(getServiceInstance().getRootFolder());
            final ManagedEntity[] entities = navigator.searchManagedEntities(false);
            for(final ManagedEntity entity : entities) {
                if (entity.getName().startsWith(prefix)) {
                    ++count;
                }
            }
        } catch (Exception ex) {
            throw new VSphereException(ex);
        }
        return count;
    }

    private Datastore getDatastoreByName(final String datastoreName, ManagedEntity rootEntity) throws RemoteException, MalformedURLException {
        if (rootEntity == null) {
            rootEntity = getServiceInstance().getRootFolder();
        }

        Datastore datastore = (Datastore) new InventoryNavigator(rootEntity).searchManagedEntity("Datastore", datastoreName);
        if (datastore != null) {
            return datastore;
        }

        if (rootEntity == null || !(rootEntity instanceof ClusterComputeResource)) {
            return null;
        }

        // try to fetch data store directly from cluster if above approach doesn't work
        ClusterComputeResource clusterResource = (ClusterComputeResource) rootEntity;

        for (Datastore dataStore : clusterResource.getDatastore()) {
            if (dataStore.getName().equals(datastoreName)) {
                return dataStore;
            }
        }
        return null;
    }

    /*
     Check if folder exists along all the vSphere folders
     */
    public Boolean folderExists(String folderPath) throws VSphereException {
        try {
            String[] folderHierarchy = folderPath.split("/");
            ManagedEntity folder = null;

            for (int i = 0; i < folderHierarchy.length; i++) {
                if (i == 0) {
                    folder = new InventoryNavigator(getServiceInstance().getRootFolder()).searchManagedEntity("Folder", folderHierarchy[i]);
                } else {
                    folder = new InventoryNavigator(folder).searchManagedEntity(null, folderHierarchy[i]);
                }
                if (folder == null) {
                    return false;
                }
            }
            return true;
        } catch (Exception e) {
            LOGGER.log(Level.SEVERE, "Failed while checking if folder exists");
            throw new VSphereException(e);
        }
    }

    public Folder getFolder(String folderPath) throws VSphereException {
        try {
            String[] folderHierarchy = folderPath.split("/");
            ManagedEntity folder = null;

            for (int i = 0; i < folderHierarchy.length; i++) {
                if (i == 0) {
                    folder = new InventoryNavigator(getServiceInstance().getRootFolder()).searchManagedEntity("Folder", folderHierarchy[i]);
                } else {
                    folder = new InventoryNavigator(folder).searchManagedEntity(null, folderHierarchy[i]);
                }
            }
            return (Folder) folder;
        } catch (Exception e) {
            LOGGER.log(Level.SEVERE, "Invalid folder");
            throw new VSphereException(e);
        }
    }
    
    public CustomizationSpecItem getCustomizationSpecByName(final String customizationSpecName) throws VSphereException {
        try {
            ServerConnection conn = getServiceInstance().getServerConnection();
            CustomizationSpecManager mgr = new CustomizationSpecManager(
                    conn,
                    getServiceInstance().getServiceContent().customizationSpecManager);

            return mgr.getCustomizationSpec(customizationSpecName);
        } catch (Exception e) {
            throw new VSphereException(e);
        }
    }

    /**
     * @return - ManagedEntity array of Datastore
     * @throws VSphereException If an error occurred.
     */
    public ManagedEntity[] getDatastores() throws VSphereException {
        try {
            return new InventoryNavigator(
                    getServiceInstance().getRootFolder()).searchManagedEntities(
                            "Datastore");
        } catch (Exception e) {
            throw new VSphereException(e);
        }
    }

    /**
     * @param poolName - Name of pool to use
     * @return - ResourcePool object
     * @throws InvalidProperty
     * @throws RuntimeFault
     * @throws RemoteException
     * @throws MalformedURLException
     * @throws VSphereException
     */
    private ResourcePool getResourcePoolByName(final String poolName, ManagedEntity rootEntity) throws InvalidProperty, RuntimeFault, RemoteException, MalformedURLException {
        if (rootEntity==null) rootEntity=getServiceInstance().getRootFolder();

        return (ResourcePool) new InventoryNavigator(
                rootEntity).searchManagedEntity(
                        "ResourcePool", poolName);
    }

    /**
     * @param clusterName - Name of cluster name to find
     * @param rootEntity - managed entity to search
     * @return - ClusterComputeResource object
     * @throws InvalidProperty
     * @throws RuntimeFault
     * @throws RemoteException
     * @throws MalformedURLException 
     * @throws VSphereException 
     */
    private ClusterComputeResource getClusterByName(final String clusterName, ManagedEntity rootEntity) throws InvalidProperty, RuntimeFault, RemoteException, MalformedURLException {
        if (rootEntity==null) rootEntity=getServiceInstance().getRootFolder();

        return (ClusterComputeResource) new InventoryNavigator(
                rootEntity).searchManagedEntity(
                        "ClusterComputeResource", clusterName);
    }

    /**
     * @param clusterName - Name of cluster name to find
     * @return - ClusterComputeResource object
     * @throws InvalidProperty
     * @throws RuntimeFault
     * @throws RemoteException
     * @throws MalformedURLException 
     * @throws VSphereException 
     */
    private ClusterComputeResource getClusterByName(final String clusterName) throws InvalidProperty, RuntimeFault, RemoteException, MalformedURLException {
        return getClusterByName(clusterName, null);
    }

    /**
     * Destroys the VM in vSphere
     * @param name - VM object to destroy
     * @param failOnNoExist If true and the VM does not exist then a {@link VSphereNotFoundException} will be thrown.
     * @throws VSphereException If an error occurred.
     */
    public void destroyVm(String name, boolean failOnNoExist) throws VSphereException {
        try {
            VirtualMachine vm = getVmByName(name);
            if (vm==null) {
                if (failOnNoExist) throw new VSphereNotFoundException("VM", name);

                LOGGER.log(Level.FINER, "VM \"" + name + "\" does not exist, or already deleted!");
                return;
            }

            if (!vm.getConfig().template) {
                powerOffVm(vm, true, 0);
            }

            final Task task = vm.destroy_Task();
            String status = task.waitForTask();
            if (status.equals(Task.SUCCESS)) {
                LOGGER.log(Level.FINER, "VM \"" + name + "\" was deleted successfully.");
                return;
            }
            throw newVSphereException(task.getTaskInfo(), "Could not delete VM \""+ name +"\"!");

        } catch(RuntimeException | VSphereException e) {
            throw e;
        } catch(Exception e) {
            throw new VSphereException(e.getMessage(), e);
        }
    }

    /**
     * Renames a VM Snapshot
     * @param vmName the name of the VM whose snapshot is being renamed.
     * @param oldName the current name of the VM's snapshot.
     * @param newName the new name of the VM's snapshot.
     * @param newDescription the new description of the VM's snapshot.
     * @throws VSphereException If an error occurred.
     */
    public void renameVmSnapshot(String vmName, String oldName, String newName, String newDescription) throws VSphereException {
        try {
            VirtualMachine vm = getVmByName(vmName);
            if (vm==null) {
                throw new VSphereNotFoundException("VM", vmName);
            }

            VirtualMachineSnapshot snapshot = getSnapshotInTree(vm, oldName);

            snapshot.rename(newName, newDescription);

            LOGGER.log(Level.FINER, "VM Snapshot was renamed successfully.");
            return;

        } catch(RuntimeException | VSphereException e) {
            throw e;
        } catch(Exception e) {
            throw new VSphereException(e.getMessage(), e);
        }
    }

    /**
     * Renames the VM vSphere
     * @param oldName the current name of the vm
     * @param newName the new name of the vm
     * @throws VSphereException If an error occurred.
     */
    public void renameVm(String oldName, String newName) throws VSphereException {
        try {
            VirtualMachine vm = getVmByName(oldName);
            if (vm==null) {
                throw new VSphereNotFoundException("VM", oldName);
            }

            final Task task = vm.rename_Task(newName);
            final String status = task.waitForTask();
            if (status.equals(Task.SUCCESS)) {
                LOGGER.log(Level.FINER, "VM was renamed successfully.");
                return;
            }
            throw newVSphereException(task.getTaskInfo(), "Could not rename VM \""+ oldName +"\"!");

        } catch(RuntimeException | VSphereException e) {
            throw e;
        } catch(Exception e) {
            throw new VSphereException(e.getMessage(), e);
        }
    }

    private boolean isSuspended(VirtualMachine vm) {
        return (vm.getRuntime().getPowerState() ==  VirtualMachinePowerState.suspended);
    }

    private boolean isPoweredOn(VirtualMachine vm) {
        return (vm.getRuntime().getPowerState() ==  VirtualMachinePowerState.poweredOn);
    }

    private boolean isPoweredOff(VirtualMachine vm) {
        return (vm.getRuntime() != null && vm.getRuntime().getPowerState() ==  VirtualMachinePowerState.poweredOff);
    }

    public boolean vmToolIsEnabled(VirtualMachine vm) {
        VirtualMachineToolsStatus status = vm.getGuest().toolsStatus;
        return ((status == VirtualMachineToolsStatus.toolsOk) || (status == VirtualMachineToolsStatus.toolsOld));
    }

    /**
     * Power off the given virtual machine, optionally waiting 180 seconds for its operating system to shut down.
     * @param vm The virtual machine to power off.
     * @param evenIfSuspended If false, a suspended VM is left as it was. If true, a suspended VM gets fully powered off.
     * @param shutdownGracefully If false, the VM is powered off immediately. If true (and VMware tools is installed), the guest operating system is given a grace period of 180 seconds to shut down.
     * @deprecated This method has been superseded by {@link #powerOffVm(VirtualMachine, boolean, int)}, which allows setting an arbitrary grace period.
     */
    @Deprecated
    public void powerOffVm(VirtualMachine vm, boolean evenIfSuspended, boolean shutdownGracefully) throws VSphereException {
        powerOffVm(vm, evenIfSuspended, shutdownGracefully ? 180 : 0);
    }

    /**
     * Power off the given virtual machine, optionally waiting a while for its operating system to shut down.
     * @param vm The virtual machine to power off.
     * @param evenIfSuspended If false, a suspended VM is left as it was. If true, a suspended VM gets fully powered off.
     * @param gracefulShutdownSeconds The number of seconds to wait for the guest operating system to shut down. If the passed value is zero or less (or if VMware tools is not installed on the VM), the VM is powered off immediately.
     */
    public void powerOffVm(VirtualMachine vm, boolean evenIfSuspended, int gracefulShutdownSeconds) throws VSphereException {

        if (vm.getConfig().template)
            throw new VSphereException("VM represents a template!");

        if (isPoweredOn(vm) || (evenIfSuspended && isSuspended(vm))) {
            boolean doHardShutdown = true;

            String status;
            try {
                if (!isSuspended(vm) && gracefulShutdownSeconds > 0 && vmToolIsEnabled(vm)) {
                    LOGGER.log(Level.FINER, "Requesting guest shutdown");
                    vm.shutdownGuest();

                    // Wait for a short while for a shutdown - then power off hard.
                    for (int i = 0; i <= gracefulShutdownSeconds; i++) {
                        try {
                            Thread.sleep(1000);
                        } catch (InterruptedException e) { // build aborted
                            Thread.currentThread().interrupt(); // pass interrupt upwards
                            throw new VSphereException("VM power-down interrupted", e);
                        }
                        if (isPoweredOff(vm)) {
                            doHardShutdown = false;
                            LOGGER.log(Level.FINER, "VM gracefully powered down successfully.");
                            return;
                        }
                    }
                }

                if (doHardShutdown) {
                    LOGGER.log(Level.FINER, "Powering off the VM");
                    final Task task = vm.powerOffVM_Task();
                    status = task.waitForTask();

                    if (status.equals(Task.SUCCESS)) {
                        LOGGER.log(Level.FINER, "VM was powered down successfully.");
                        return;
                    }
                    throw newVSphereException(task.getTaskInfo(), "Machine could not be powered down!");
                }
            } catch(RuntimeException | VSphereException e) {
                throw e;
            } catch (Exception e) {
                throw new VSphereException(e);
            }
        }
        else if (isPoweredOff(vm)) {
            LOGGER.log(Level.FINER, "Machine is already off.");
            return;
        }

        throw new VSphereException("Machine could not be powered down!");
    }

    public void suspendVm(VirtualMachine vm) throws VSphereException {
        if (isPoweredOn(vm)) {
            try {
                //TODO is this better?
                //vm.shutdownGuest()
                final Task task = vm.suspendVM_Task();
                final String status = task.waitForTask();
                if (Task.SUCCESS.equals(status)) {
                    LOGGER.log(Level.FINER, "VM was suspended successfully.");
                    return;
                }
                throw newVSphereException(task.getTaskInfo(), "Machine could not be suspended!");
            } catch(RuntimeException | VSphereException e) {
                throw e;
            } catch (Exception e) {
                throw new VSphereException(e);
            }
        }
        else {
            LOGGER.log(Level.FINER, "Machine not powered on.");
            return;
        }
    }

    /**
     * Private helper functions that finds the datanceter a VirtualMachine belongs to
     * @param managedEntity - VM object
     * @return returns Datacenter object
     */
    private Datacenter getDataCenter(ManagedEntity managedEntity) {
        if (managedEntity != null) {
            ManagedEntity parent = managedEntity.getParent();
            if (parent.getMOR().getType().equals("Datacenter")) {
                return (Datacenter) parent;
            } else {
                return getDataCenter(managedEntity.getParent());
            }
        } else {
            return null;
        }
    }

    /**
     * Find Distributed Virtual Port Group name in the same Datacenter as the VM
     * @param virtualMachine - VM object
     * @param name - the name of the Port Group
     * @return returns DistributedVirtualPortgroup object for the provided vDS PortGroup
     * @throws VSphereException If an error occurred.
     */
    public Network getNetworkPortGroupByName(VirtualMachine virtualMachine,
            String name) throws VSphereException {
        try {
            Datacenter datacenter = getDataCenter(virtualMachine);
            for (Network network : datacenter.getNetwork()) {
                if (network instanceof Network &&
                        (name.isEmpty() || network.getName().contentEquals(name))) {
                    return network;
                }
            }
        } catch (Exception e) {
            throw new VSphereException(e);
        }
        return null;
    }

    /**
     * Find Distributed Virtual Port Group name in the same Datacenter as the VM
     * @param virtualMachine - VM object
     * @param name - the name of the Port Group
     * @return returns DistributedVirtualPortgroup object for the provided vDS PortGroup
     * @throws VSphereException If an error occurred.
     */
    public DistributedVirtualPortgroup getDistributedVirtualPortGroupByName(VirtualMachine virtualMachine,
            String name) throws VSphereException {
        try {
            Datacenter datacenter = getDataCenter(virtualMachine);
            for (Network network : datacenter.getNetwork()) {
                if (network instanceof DistributedVirtualPortgroup &&
                        (name.isEmpty() || network.getName().contentEquals(name))) {
                    return (DistributedVirtualPortgroup)network;
                }
            }
        } catch (Exception e) {
            throw new VSphereException(e);
        }
        return null;
    }

    /**
     * Find Distributed Virtual Switch from the provided Distributed Virtual Portgroup
     * @param distributedVirtualPortgroup - DistributedVirtualPortgroup object for the provided vDS PortGroup
     * @return returns DistributedVirtualSwitch object that represents the vDS Switch
     * @throws VSphereException If an error occurred.
     */
    public DistributedVirtualSwitch getDistributedVirtualSwitchByPortGroup(
            DistributedVirtualPortgroup distributedVirtualPortgroup) throws VSphereException {
        try {
            ManagedObjectReference managedObjectReference = new ManagedObjectReference();
            managedObjectReference.setType("DistributedVirtualSwitch");
            managedObjectReference.setVal(distributedVirtualPortgroup.getConfig().getDistributedVirtualSwitch().getVal());
            return new DistributedVirtualSwitch(getServiceInstance().getServerConnection(), managedObjectReference);
        }
        catch (Exception e) {
            throw new VSphereException(e);
        }
    }

    /**
     * Passes data to a VM's "extra config" object. This data can then be read
     * back at a later stage.
     * In the case of parameters whose name starts "guestinfo.", the parameter
     * can be read by the VMware Tools on the client OS.
     * <p>
     * e.g. a variable named "guestinfo.Foo" with value "Bar" could be read on
     * the guest using the command-line
     * {@code vmtoolsd --cmd "info-get guestinfo.Foo"}.
     * </p>
     * 
     * @param vmName
     *            The name of the VM.
     * @param parameters
     *            A {@link Map} of variable name to variable value.
     * @throws VSphereException
     *             If an error occurred.
     */
    public void setExtraConfigParameters(String vmName, Map<String, String> parameters) throws VSphereException {
        VirtualMachineConfigSpec cs = createVMConfigSpecFromExtraConfigParameters(parameters);
        reconfigureVm(vmName, cs);
    }

    private static VirtualMachineConfigSpec createVMConfigSpecFromExtraConfigParameters(Map<String, String> parameters) {
        VirtualMachineConfigSpec cs = new VirtualMachineConfigSpec();
        OptionValue[] ourOptionValues = new OptionValue[parameters.size()];
        List<OptionValue> optionValues = new ArrayList<>();
        for (Map.Entry<String, String> eachVariable : parameters.entrySet()) {
            OptionValue ov = new OptionValue();
            ov.setKey(eachVariable.getKey());
            ov.setValue(eachVariable.getValue());
            optionValues.add(ov);
        }
        for (int i = 0; i < optionValues.size(); i++) {
            ourOptionValues[i] = optionValues.get(i);
        }
        cs.setExtraConfig(ourOptionValues);
        return cs;
    }

    private void logMessage(PrintStream jLogger, String message) {
        if (jLogger != null) {
            VSphereLogger.vsLogger(jLogger, message);
        }
        LOGGER.log(Level.FINER, message);
    }

    /**
     * Creates a {@link VSphereException} whose cause is the {@link TaskInfo}'s
     * exception. This provides an exception that is much more informative than
     * what is said by the <code>message</code> alone.
     * 
     * @param taskInfo
     *            The vSphere task that failed.
     * @param message
     *            A line of text that says what the task was trying to achieve.
     * @return An exception that includes the cause of the failure.
     */
    private static VSphereException newVSphereException(TaskInfo taskInfo, final String message) {
        final com.vmware.vim25.LocalizedMethodFault error = taskInfo == null ? null : taskInfo.getError();
        final String faultMsg = error == null ? null : error.getLocalizedMessage();
        final Exception fault = error == null ? null : error.getFault();
        final String combinedMsg = message + (faultMsg == null ? "" : ("\n" + faultMsg));
        if (fault != null) {
            return new VSphereException(combinedMsg, fault);
        } else {
            return new VSphereException(combinedMsg);
        }
    }
}
