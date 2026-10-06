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

import com.vmware.vim25.CustomizationSpecItem;
import com.vmware.vim25.VirtualMachineConfigSpec;
import com.vmware.vim25.mo.DistributedVirtualPortgroup;
import com.vmware.vim25.mo.DistributedVirtualSwitch;
import com.vmware.vim25.mo.Folder;
import com.vmware.vim25.mo.ManagedEntity;
import com.vmware.vim25.mo.Network;
import com.vmware.vim25.mo.VirtualMachine;
import com.vmware.vim25.mo.VirtualMachineSnapshot;
import edu.umd.cs.findbugs.annotations.CheckForNull;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.io.PrintStream;
import java.util.Map;
import java.util.Set;
import org.jenkinsci.plugins.vsphere.VSphereConnectionConfig;
import org.kohsuke.accmod.Restricted;
import org.kohsuke.accmod.restrictions.NoExternalUse;

/**
 * What the plugin needs of a connection to a hypervisor management endpoint: look up and operate on virtual
 * machines, clone and deploy them, take snapshots and so on.
 *
 * <p>The implementation talking to vCenter (or a standalone ESXi host) through the vSphere Web Services API
 * is {@link VSphereYavijava}. Obtain a connection with one of the {@code connect} factories, which are
 * where a choice between implementations belongs.
 */
public interface VSphere {

    /**
     * Initiates Connection to vSphere Server
     * @param connectionDetails Contains all the details we need to connect.
     * @throws VSphereException If an error occurred.
     * @return A connected instance.
     */
    static VSphere connect(@NonNull VSphereConnectionConfig connectionDetails) throws VSphereException {
        // This is where the choice between the ways of talking to a hypervisor is made: by the backend that
        // the connection configuration is for
        return connectionDetails.getBackend().connect(connectionDetails);
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
    static VSphere connect(@NonNull String server, boolean ignoreCert, @NonNull String user, @CheckForNull String pw)
            throws VSphereException {
        return VSphereYavijava.connect(server, ignoreCert, user, pw);
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
    void disconnect();

    /**
     * Marks this instance as owned by {@code pool}, so that {@link #disconnect()}
     * releases it back to the pool instead of logging out directly.
     * Internal: only {@link VSphereConnectionPool} should call this.
     */
    @Restricted(NoExternalUse.class)
    void markAsPooled(VSphereConnectionPool pool);

    /**
     * Disconnects the underlying session regardless of pooled status.
     * Called by {@link VSphereConnectionPool} when it actually wants to tear down
     * the session (restart, idle timeout, shutdown).
     * Internal: only {@link VSphereConnectionPool} should call this.
     */
    @Restricted(NoExternalUse.class)
    void forceDisconnect();

    /**
     * Checks whether the current vSphere session is still alive by issuing a
     * lightweight {@code currentTime()} call.
     *
     * @return {@code true} if the session responds normally; {@code false} if it
     *         has expired or the server is unreachable.
     */
    boolean isSessionAlive();

    /**
     * Whether a connection of this kind is to be checked with {@link #isSessionAlive()} when the pool hands it out
     * after it has been idle for a while. Not for a vCenter session, which the server keeps and times out itself;
     * but a session over SSH is only a connection, which a host, a firewall or a restart can have ended without
     * either side being told, and that is found out by using it.
     */
    @Restricted(NoExternalUse.class)
    default boolean shouldBeCheckedWhenAcquired() {
        return false;
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
    void deployVm(
            String cloneName,
            String sourceName,
            boolean linkedClone,
            String resourcePoolName,
            String cluster,
            String datastoreName,
            String folderName,
            boolean powerOn,
            String customizationSpec,
            PrintStream jLogger)
            throws VSphereException;

    /**
     * Deploys a new VM from an existing template, with control over which ESXi host the clone
     * is placed on. See {@link #cloneOrDeployVm} for the meaning of {@code host}, {@code
     * hostSelectionMode} and {@code hostSelectionCandidates}.
     *
     * @throws VSphereException If an error occurred.
     */
    void deployVm(
            String cloneName,
            String sourceName,
            boolean linkedClone,
            String resourcePoolName,
            String cluster,
            String datastoreName,
            String folderName,
            boolean powerOn,
            String customizationSpec,
            String host,
            String hostSelectionMode,
            Set<String> hostSelectionCandidates,
            PrintStream jLogger)
            throws VSphereException;

    /**
     * As the overload without them, plus the opt-in VM-size checks described at {@link
     * #cloneOrDeployVm} for {@code hostSelectionOptions}.
     *
     * @throws VSphereException If an error occurred.
     */
    void deployVm(
            String cloneName,
            String sourceName,
            boolean linkedClone,
            String resourcePoolName,
            String cluster,
            String datastoreName,
            String folderName,
            boolean powerOn,
            String customizationSpec,
            String host,
            String hostSelectionMode,
            Set<String> hostSelectionCandidates,
            HostSelectionOptions hostSelectionOptions,
            PrintStream jLogger)
            throws VSphereException;

    /**
     * As the overload without them, plus a vCPU count and memory size (MB) to create the VM with
     * in the same operation, instead of reconfiguring it afterwards; see {@link #cloneOrDeployVm}.
     *
     * @throws VSphereException If an error occurred.
     */
    void deployVm(
            String cloneName,
            String sourceName,
            boolean linkedClone,
            String resourcePoolName,
            String cluster,
            String datastoreName,
            String folderName,
            boolean powerOn,
            String customizationSpec,
            String host,
            String hostSelectionMode,
            Set<String> hostSelectionCandidates,
            HostSelectionOptions hostSelectionOptions,
            VmSize vmSize,
            PrintStream jLogger)
            throws VSphereException;

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
    void cloneVm(
            String cloneName,
            String sourceName,
            boolean linkedClone,
            String resourcePoolName,
            String cluster,
            String datastoreName,
            String folderName,
            boolean powerOn,
            String customizationSpec,
            PrintStream jLogger)
            throws VSphereException;

    /**
     * Clones a new VM from an existing (named) VM, with control over which ESXi host the clone
     * is placed on. See {@link #cloneOrDeployVm} for the meaning of {@code host}, {@code
     * hostSelectionMode} and {@code hostSelectionCandidates}.
     *
     * @throws VSphereException If an error occurred.
     */
    void cloneVm(
            String cloneName,
            String sourceName,
            boolean linkedClone,
            String resourcePoolName,
            String cluster,
            String datastoreName,
            String folderName,
            boolean powerOn,
            String customizationSpec,
            String host,
            String hostSelectionMode,
            Set<String> hostSelectionCandidates,
            PrintStream jLogger)
            throws VSphereException;

    /**
     * As the overload without them, plus the opt-in VM-size checks described at {@link
     * #cloneOrDeployVm} for {@code hostSelectionOptions}.
     *
     * @throws VSphereException If an error occurred.
     */
    void cloneVm(
            String cloneName,
            String sourceName,
            boolean linkedClone,
            String resourcePoolName,
            String cluster,
            String datastoreName,
            String folderName,
            boolean powerOn,
            String customizationSpec,
            String host,
            String hostSelectionMode,
            Set<String> hostSelectionCandidates,
            HostSelectionOptions hostSelectionOptions,
            PrintStream jLogger)
            throws VSphereException;

    /**
     * As the overload without them, plus a vCPU count and memory size (MB) to create the VM with
     * in the same operation, instead of reconfiguring it afterwards; see {@link #cloneOrDeployVm}.
     *
     * @throws VSphereException If an error occurred.
     */
    void cloneVm(
            String cloneName,
            String sourceName,
            boolean linkedClone,
            String resourcePoolName,
            String cluster,
            String datastoreName,
            String folderName,
            boolean powerOn,
            String customizationSpec,
            String host,
            String hostSelectionMode,
            Set<String> hostSelectionCandidates,
            HostSelectionOptions hostSelectionOptions,
            VmSize vmSize,
            PrintStream jLogger)
            throws VSphereException;

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
    void cloneOrDeployVm(
            String cloneName,
            String sourceName,
            boolean linkedClone,
            String resourcePoolName,
            String cluster,
            String datastoreName,
            String folderName,
            boolean useCurrentSnapshot,
            final String namedSnapshot,
            boolean powerOn,
            Map<String, String> extraConfigParameters,
            String customizationSpec,
            PrintStream jLogger)
            throws VSphereException;

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
    void cloneOrDeployVm(
            String cloneName,
            String sourceName,
            boolean linkedClone,
            String resourcePoolName,
            String cluster,
            String datastoreName,
            String folderName,
            boolean useCurrentSnapshot,
            final String namedSnapshot,
            boolean powerOn,
            Map<String, String> extraConfigParameters,
            String customizationSpec,
            String host,
            String hostSelectionMode,
            Set<String> hostSelectionCandidates,
            PrintStream jLogger)
            throws VSphereException;

    /**
     * As the overload without them, plus two opt-in checks on top of {@code
     * hostSelectionMode}'s candidate list, both comparing the VM's configuration with each
     * host's total physical capacity (not what is currently free):
     *
     * @param hostSelectionOptions
     *            (Optional, null means {@link HostSelectionOptions#NONE}) Refinements of
     *            automatic selection: {@link HostSelectionOptions#isRequireCores()} only
     *            considers hosts with at least as many physical CPU cores as the VM has
     *            vCPUs, {@link HostSelectionOptions#isRequireMemory()} only hosts with at least
     *            as much physical RAM as the VM is configured with.
     *            <p>
     *            Both default to off elsewhere, as many sites run oversubscribed (swap,
     *            hyperthreads, ...). They have no effect when {@code host} is set or
     *            {@code hostSelectionMode} is empty. If no host satisfies them, placement is
     *            left to vCenter (as when no candidate is usable at all).
     * @throws VSphereException
     *             if anything goes wrong.
     */
    void cloneOrDeployVm(
            String cloneName,
            String sourceName,
            boolean linkedClone,
            String resourcePoolName,
            String cluster,
            String datastoreName,
            String folderName,
            boolean useCurrentSnapshot,
            final String namedSnapshot,
            boolean powerOn,
            Map<String, String> extraConfigParameters,
            String customizationSpec,
            String host,
            String hostSelectionMode,
            Set<String> hostSelectionCandidates,
            HostSelectionOptions hostSelectionOptions,
            PrintStream jLogger)
            throws VSphereException;

    /**
     * As the overload without them, plus the size to create the VM with.
     *
     * @param vmSize
     *            (Optional, null means {@link VmSize#NONE}) CPU and memory settings to give the
     *            new VM in the same operation that creates it, instead of reconfiguring it
     *            afterwards; unset parts keep the source's. {@code cpuCores} and {@code
     *            memorySize} are also what the host size checks compare against, in place of the
     *            source's.
     * @throws VSphereException
     *             if anything goes wrong.
     */
    void cloneOrDeployVm(
            String cloneName,
            String sourceName,
            boolean linkedClone,
            String resourcePoolName,
            String cluster,
            String datastoreName,
            String folderName,
            boolean useCurrentSnapshot,
            final String namedSnapshot,
            boolean powerOn,
            Map<String, String> extraConfigParameters,
            String customizationSpec,
            String host,
            String hostSelectionMode,
            Set<String> hostSelectionCandidates,
            HostSelectionOptions hostSelectionOptions,
            VmSize vmSize,
            PrintStream jLogger)
            throws VSphereException;

    /**
     * Checks whether a host with this name exists anywhere in the vCenter inventory. Used by
     * build-step/config live-validation ("Check Data"/"Check Template" buttons) for the
     * {@code host}/{@code targetHost} and {@code hostSelectionCandidates} fields.
     *
     * @throws VSphereException If an error occurred while querying vCenter.
     */
    boolean hostExists(final String hostName) throws VSphereException;

    void reconfigureVm(String name, VirtualMachineConfigSpec spec) throws VSphereException;

    /**
     * @param name - Name of VM to start
     * @param timeoutInSeconds How long to wait for the VM to be running.
     * @throws VSphereException If an error occurred.
     */
    void startVm(String name, int timeoutInSeconds) throws VSphereException;

    VirtualMachineSnapshot getSnapshotInTree(VirtualMachine vm, String snapName);

    void revertToSnapshot(String vmName, String snapName) throws VSphereException;

    void revertToSnapshot(String vmName, String snapName, boolean suppressPowerOn) throws VSphereException;

    void deleteSnapshot(String vmName, String snapName, boolean consolidate, boolean failOnNoExist)
            throws VSphereException;

    void takeSnapshot(String vmName, String snapshot, String description, boolean snapMemory) throws VSphereException;

    void markAsTemplate(String vmName, String snapName, boolean force) throws VSphereException;

    void markAsVm(String name, String resourcePool, String cluster) throws VSphereException;

    /**
     * Asks vSphere for the IP address used by a VM.
     *
     * @param vm VirtualMachine name whose IP is to be returned.
     * @param timeout How long to wait (in seconds) for the IP address to known to vSphere.
     * @return String containing IP address.
     * @throws VSphereException If an error occurred.
     */
    String getIp(VirtualMachine vm, int timeout) throws VSphereException;

    /**
     * @param vmName - name of VM object to retrieve
     * @return - VirtualMachine object
     * @throws VSphereException If an error occurred.
     */
    VirtualMachine getVmByName(String vmName) throws VSphereException;

    int countVms() throws VSphereException;

    int countVmsByPrefix(final String prefix) throws VSphereException;

    Boolean folderExists(String folderPath) throws VSphereException;

    Folder getFolder(String folderPath) throws VSphereException;

    CustomizationSpecItem getCustomizationSpecByName(final String customizationSpecName) throws VSphereException;

    /**
     * @return - ManagedEntity array of Datastore
     * @throws VSphereException If an error occurred.
     */
    ManagedEntity[] getDatastores() throws VSphereException;

    /**
     * Destroys the VM in vSphere
     * @param name - VM object to destroy
     * @param failOnNoExist If true and the VM does not exist then a {@link VSphereNotFoundException} will be thrown.
     * @throws VSphereException If an error occurred.
     */
    void destroyVm(String name, boolean failOnNoExist) throws VSphereException;

    /**
     * Renames a VM Snapshot
     * @param vmName the name of the VM whose snapshot is being renamed.
     * @param oldName the current name of the VM's snapshot.
     * @param newName the new name of the VM's snapshot.
     * @param newDescription the new description of the VM's snapshot.
     * @throws VSphereException If an error occurred.
     */
    void renameVmSnapshot(String vmName, String oldName, String newName, String newDescription) throws VSphereException;

    /**
     * Renames a VM Snapshot
     * @param vmName the name of the VM whose snapshot is being renamed.
     * @param oldName the current name of the VM's snapshot.
     * @param newName the new name of the VM's snapshot.
     * @param newDescription the new description of the VM's snapshot.
     * @param failOnNoExist If true and the snapshot does not exist then a {@link VSphereNotFoundException} will be
     *                      thrown; otherwise nothing is renamed.
     * @return true if the snapshot was renamed, false if it did not exist (and that was tolerated).
     * @throws VSphereException If an error occurred (the VM not existing is always an error).
     */
    boolean renameVmSnapshot(
            String vmName, String oldName, String newName, String newDescription, boolean failOnNoExist)
            throws VSphereException;

    /**
     * Renames the VM vSphere
     * @param oldName the current name of the vm
     * @param newName the new name of the vm
     * @throws VSphereException If an error occurred.
     */
    void renameVm(String oldName, String newName) throws VSphereException;

    boolean vmToolIsEnabled(VirtualMachine vm);

    /**
     * Power off the given virtual machine, optionally waiting 180 seconds for its operating system to shut down.
     * @param vm The virtual machine to power off.
     * @param evenIfSuspended If false, a suspended VM is left as it was. If true, a suspended VM gets fully powered off.
     * @param shutdownGracefully If false, the VM is powered off immediately. If true (and VMware tools is installed), the guest operating system is given a grace period of 180 seconds to shut down.
     * @deprecated This method has been superseded by {@link #powerOffVm(VirtualMachine, boolean, int)}, which allows setting an arbitrary grace period.
     */
    @Deprecated
    void powerOffVm(VirtualMachine vm, boolean evenIfSuspended, boolean shutdownGracefully) throws VSphereException;

    /**
     * Power off the given virtual machine, optionally waiting a while for its operating system to shut down.
     * @param vm The virtual machine to power off.
     * @param evenIfSuspended If false, a suspended VM is left as it was. If true, a suspended VM gets fully powered off.
     * @param gracefulShutdownSeconds The number of seconds to wait for the guest operating system to shut down. If the passed value is zero or less (or if VMware tools is not installed on the VM), the VM is powered off immediately.
     */
    void powerOffVm(VirtualMachine vm, boolean evenIfSuspended, int gracefulShutdownSeconds) throws VSphereException;

    void suspendVm(VirtualMachine vm) throws VSphereException;

    /**
     * Find Distributed Virtual Port Group name in the same Datacenter as the VM
     * @param virtualMachine - VM object
     * @param name - the name of the Port Group
     * @return returns DistributedVirtualPortgroup object for the provided vDS PortGroup
     * @throws VSphereException If an error occurred.
     */
    Network getNetworkPortGroupByName(VirtualMachine virtualMachine, String name) throws VSphereException;

    /**
     * Find Distributed Virtual Port Group name in the same Datacenter as the VM
     * @param virtualMachine - VM object
     * @param name - the name of the Port Group
     * @return returns DistributedVirtualPortgroup object for the provided vDS PortGroup
     * @throws VSphereException If an error occurred.
     */
    DistributedVirtualPortgroup getDistributedVirtualPortGroupByName(VirtualMachine virtualMachine, String name)
            throws VSphereException;

    /**
     * Find Distributed Virtual Switch from the provided Distributed Virtual Portgroup
     * @param distributedVirtualPortgroup - DistributedVirtualPortgroup object for the provided vDS PortGroup
     * @return returns DistributedVirtualSwitch object that represents the vDS Switch
     * @throws VSphereException If an error occurred.
     */
    DistributedVirtualSwitch getDistributedVirtualSwitchByPortGroup(
            DistributedVirtualPortgroup distributedVirtualPortgroup) throws VSphereException;

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
    void setExtraConfigParameters(String vmName, Map<String, String> parameters) throws VSphereException;
}
