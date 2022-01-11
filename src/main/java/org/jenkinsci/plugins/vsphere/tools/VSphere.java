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
import java.util.logging.Logger;
import java.util.logging.Level;

import edu.umd.cs.findbugs.annotations.CheckForNull;
import edu.umd.cs.findbugs.annotations.NonNull;

import org.apache.commons.lang.StringUtils;
import org.jenkinsci.plugins.vsphere.VSphereConnectionConfig;

import com.vmware.vim25.CustomizationSpecItem;
import com.vmware.vim25.GuestInfo;
import com.vmware.vim25.InvalidProperty;
import com.vmware.vim25.ManagedObjectReference;
import com.vmware.vim25.OptionValue;
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

public class VSphere {
    private final URL url;
    private final String session;
    private final static Logger LOGGER = Logger.getLogger(VSphere.class.getName());

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
     * Note: This logs any {@link Exception} it encounters - it does not pass
     * them to get to the calling method.
     * </p>
     */
    public void disconnect() {
        try {
            this.getServiceInstance().getServerConnection().logout();
        } catch (Exception e) {
            LOGGER.log(Level.WARNING, "Caught exception when trying to disconnect vSphere.", e);
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
        final boolean useCurrentSnapshotIsFALSE = false;
        final String namedSnapshotIsNULL = null;
        final Map<String, String> extraConfigParameters = null;
        logMessage(jLogger, "Deploying new vm \""+ cloneName + "\" from template \""+sourceName+"\"");
        cloneOrDeployVm(cloneName, sourceName, linkedClone, resourcePoolName, cluster, datastoreName, folderName, useCurrentSnapshotIsFALSE, namedSnapshotIsNULL, powerOn, extraConfigParameters, customizationSpec, jLogger);
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
        final boolean useCurrentSnapshotIsTRUE = true;
        final String namedSnapshotIsNULL = null;
        final Map<String, String> extraConfigParameters = null;
        logMessage(jLogger, "Creating a " + (linkedClone?"shallow":"deep") + " clone of \"" + sourceName + "\" to \"" + cloneName + "\"");
        cloneOrDeployVm(cloneName, sourceName, linkedClone, resourcePoolName, cluster, datastoreName, folderName, useCurrentSnapshotIsTRUE, namedSnapshotIsNULL, powerOn, extraConfigParameters, customizationSpec, jLogger);
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
     *            <tt>vmtoolsd --cmd "info-get guestinfo.Foo"</tt>.
     * @param customizationSpec
     *            (Optional) Customization spec to use for this VM, or null
     * @param jLogger
     *            Where to log to.
     * @throws VSphereException
     *             if anything goes wrong.
     */
    public void cloneOrDeployVm(String cloneName, String sourceName, boolean linkedClone, String resourcePoolName, String cluster, String datastoreName, String folderName, boolean useCurrentSnapshot, final String namedSnapshot, boolean powerOn, Map<String, String> extraConfigParameters, String customizationSpec, PrintStream jLogger) throws VSphereException {
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
                    throw new IllegalArgumentException("It is not valid to request a clone of " + sourceType + "  \"" + sourceName + "\" based on its snapshot \"" + namedSnapshot + "\" AND also specify that the latest snapshot should be used.  Either choose to use the latest snapshot, or name a snapshot, or neither, but not both.");
                }
                final VirtualMachineSnapshot namedVMSnapshot = getSnapshotInTree(sourceVm, namedSnapshot);
                if (namedVMSnapshot == null) {
                    throw new VSphereNotFoundException("Snapshot", namedSnapshot, "Source " + sourceType + "  \"" + sourceName + "\" has no snapshot called \"" + namedSnapshot + "\".");
                }
                logMessage(jLogger, "Clone of " + sourceType + " \"" + sourceName + "\" will be based on named snapshot \"" + namedSnapshot + "\".");
                cloneSpec.setSnapshot(namedVMSnapshot.getMOR());
            }
            if (useCurrentSnapshot) {
                final VirtualMachineSnapshot currentSnapShot = sourceVm.getCurrentSnapShot();
                if (currentSnapShot==null) {
                    throw new VSphereNotFoundException("Snapshot", null, "Source " + sourceType + "  \"" + sourceName + "\" requires at least one snapshot.");
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

        VirtualMachine vm = getVmByName(vmName);
        VirtualMachineSnapshot snap = getSnapshotInTree(vm, snapName);

        if (snap == null) {
            LOGGER.log(Level.SEVERE, "Cannot find snapshot: '" + snapName + "' for virtual machine: '" + vm.getName()+"'");
            throw new VSphereNotFoundException("Snapshot", snapName);
        }

        try {
            Task task = snap.revertToSnapshot_Task(null);
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

        for (Datastore dataStore : clusterResource.getDatastores()) {
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

            snapshot.renameSnapshot(newName, newDescription);

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
            for (Network network : datacenter.getNetworks()) {
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
            for (Network network : datacenter.getNetworks()) {
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
     * <tt>vmtoolsd --cmd "info-get guestinfo.Foo"</tt>.
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
