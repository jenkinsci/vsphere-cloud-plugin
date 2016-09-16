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

import javax.annotation.CheckForNull;
import javax.annotation.Nonnull;

import org.apache.commons.lang.StringUtils;

import com.vmware.vim25.GuestInfo;
import com.vmware.vim25.InvalidProperty;
import com.vmware.vim25.ManagedObjectReference;
import com.vmware.vim25.OptionValue;
import com.vmware.vim25.RuntimeFault;
import com.vmware.vim25.TaskInfoState;
import com.vmware.vim25.VirtualMachineCloneSpec;
import com.vmware.vim25.VirtualMachineConfigSpec;
import com.vmware.vim25.VirtualMachinePowerState;
import com.vmware.vim25.VirtualMachineQuestionInfo;
import com.vmware.vim25.VirtualMachineRelocateSpec;
import com.vmware.vim25.VirtualMachineSnapshotInfo;
import com.vmware.vim25.VirtualMachineSnapshotTree;
import com.vmware.vim25.VirtualMachineToolsStatus;
import com.vmware.vim25.mo.ClusterComputeResource;
import com.vmware.vim25.mo.Datastore;
import com.vmware.vim25.mo.Folder;
import com.vmware.vim25.mo.InventoryNavigator;
import com.vmware.vim25.mo.ManagedEntity;
import com.vmware.vim25.mo.ResourcePool;
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

	private VSphere(@Nonnull String url, @Nonnull String user, @CheckForNull String pw) throws VSphereException{
		try {
			//TODO - change ignoreCert to be configurable
			this.url = new URL(url);
			this.session = (new ServiceInstance(this.url, user, pw, true)).getServerConnection().getSessionStr();
		} catch (Exception e) {
			throw new VSphereException(e);
		}
	}

	private ServiceInstance getServiceInstance() throws RemoteException, MalformedURLException{
		return new ServiceInstance(url, session, true);
	}

	/**
	 * Initiates Connection to vSphere Server
         * @param server Server URL
	 * @param user Username.
	 * @param pw Password.
	 * @throws VSphereException If an error occurred.
	 * @return A connected instance.
	 */
	public static VSphere connect(@Nonnull String server, @Nonnull String user, @CheckForNull String pw) throws VSphereException {
		return new VSphere(server, user, pw);
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
     * Deploys a new VM from a given template with a given name.
     *
     * @param cloneName - name of VM to be created
     * @param sourceName - name of VM or template to be cloned
     * @param linkedClone - true if you want to re-use disk backings
     * @param resourcePoolName - resource pool to use
     * @param cluster - ComputeClusterResource to use
     * @param datastoreName - Datastore to use
     * @param powerOn - If true the VM will be powered on.
     * @param jLogger - Where to log to.
     * @throws VSphereException If an error occurred.
     */
    public void deployVm(String cloneName, String sourceName, boolean linkedClone, String resourcePoolName, String cluster, String datastoreName, boolean powerOn, PrintStream jLogger) throws VSphereException {
        boolean DO_NOT_USE_SNAPSHOTS = false;
        logMessage(jLogger, "Deploying new vm \""+ cloneName + "\" from template \""+sourceName+"\"");
        cloneOrDeployVm(cloneName, sourceName, linkedClone, resourcePoolName, cluster, datastoreName, DO_NOT_USE_SNAPSHOTS, powerOn, jLogger);
    }

    /**
     * Clones a new VM from a given vm or template with a given name.
     *
     * @param cloneName - name of VM to be created
     * @param sourceName - name of VM or template to be cloned
     * @param linkedClone - true if you want to re-use disk backings
     * @param resourcePoolName - resource pool to use
     * @param cluster - ComputeClusterResource to use
     * @param datastoreName - Datastore to use
     * @param powerOn - If true the VM will be powered on.
     * @param jLogger - Where to log to.
     * @throws VSphereException If an error occurred.
     */
    public void cloneVm(String cloneName, String sourceName, boolean linkedClone, String resourcePoolName, String cluster, String datastoreName, boolean powerOn, PrintStream jLogger) throws VSphereException {
        boolean DO_USE_SNAPSHOTS = true;
        logMessage(jLogger, "Creating a shallow clone of \""+ sourceName + "\" to \""+cloneName+"\"");
        cloneOrDeployVm(cloneName, sourceName, linkedClone, resourcePoolName, cluster, datastoreName, DO_USE_SNAPSHOTS, powerOn, jLogger);
    }

    private void cloneOrDeployVm(String cloneName, String sourceName, boolean linkedClone, String resourcePoolName, String cluster, String datastoreName, boolean useSnapshot, boolean powerOn, PrintStream jLogger) throws VSphereException {
        try{
            VirtualMachine sourceVm = getVmByName(sourceName);

            if(sourceVm==null) {
                throw new VSphereException("VM or template \"" + sourceName + "\" not found");
            }

            if(getVmByName(cloneName)!=null){
                throw new VSphereException("VM \"" + cloneName + "\" already exists");
            }

            VirtualMachineRelocateSpec rel = createRelocateSpec(jLogger, linkedClone, resourcePoolName, cluster, datastoreName, sourceVm.getConfig().template);

            VirtualMachineCloneSpec cloneSpec = createCloneSpec(rel);
            cloneSpec.setTemplate(false);
	    cloneSpec.powerOn = powerOn;

            if (useSnapshot) {
                //TODO add config to allow state of VM or snapshot
                if(sourceVm.getCurrentSnapShot()==null){
                    throw new VSphereException("Source VM or Template \"" + sourceName + "\" requires at least one snapshot!");
                }
                cloneSpec.setSnapshot(sourceVm.getCurrentSnapShot().getMOR());
            }

            Task task = sourceVm.cloneVM_Task((Folder) sourceVm.getParent(),
                    cloneName, cloneSpec);
            logMessage(jLogger, "Started cloning of VM. Please wait ...");

            String status = task.waitForTask();
            if(!TaskInfoState.success.toString().equals(status)) {
                throw new VSphereException("Couldn't clone \""+ sourceName +"\"! Does \""+cloneName+"\" already exist? " +
                        "Clone task ended with status " + status);
            }

        } catch(Exception e){
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

        if(linkedClone){
            rel.setDiskMoveType("createNewChildDiskBacking");
        }else{
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
        		throw new VSphereException("Resource pool \"" + resourcePoolName + "\" not found");
        	}

        	rel.setPool(resourcePool.getMOR());
        } else if (isResourcePoolRequired) {
    		throw new VSphereException("You must specify a resource  pool  when using a template");
        }

        if (datastoreName != null && !datastoreName.isEmpty()) {
            Datastore datastore = getDatastoreByName(datastoreName, clusterResource);
            if (datastore==null){
                throw new VSphereException("Datastore \"" + datastoreName + "\" not found!");
            }
            rel.setDatastore(datastore.getMOR());
        }
       return rel;
    }

    public void reconfigureVm(String name, VirtualMachineConfigSpec spec) throws VSphereException {
        VirtualMachine vm = getVmByName(name);
        if(vm==null) {
            throw new VSphereException("No VM or template " + name + " found");
        }
        LOGGER.log(Level.FINER, "Reconfiguring VM. Please wait ...");
        try {
            Task task = vm.reconfigVM_Task(spec);
            String status = task.waitForTask();
            if(status.equals(TaskInfoState.success.toString())) {
                return;
            }
        } catch(Exception e){
            throw new VSphereException("VM cannot be reconfigured:" + e.getMessage(), e);
        }
        throw new VSphereException("Couldn't reconfigure \""+ name +"\"!");
    }

	/**
	 * @param name - Name of VM to start
	 * @param timeoutInSeconds How long to wait for the VM to be running.
	 * @throws VSphereException If an error occurred.
	 */
	public void startVm(String name, int timeoutInSeconds) throws VSphereException {
		try{
			VirtualMachine vm = getVmByName(name);
            if (vm == null) {
                throw new VSphereException("Vm " + name + " was not found");
            }
			if(isPoweredOn(vm))
				return;

			if(vm.getConfig().template)
				throw new VSphereException("VM represents a template!");

			Task task = vm.powerOnVM_Task(null);

            int timesToCheck = timeoutInSeconds / 5;
            // add one extra time for remainder
            timesToCheck++;
            LOGGER.log(Level.FINER, "Checking " + timesToCheck + " times for vm to be powered on");

			for (int i=0; i<timesToCheck; i++){

				if(task.getTaskInfo().getState()==TaskInfoState.success){
                    LOGGER.log(Level.FINER, "VM was powered up successfully.");
                    return;
				}

				if (task.getTaskInfo().getState()==TaskInfoState.running ||
						task.getTaskInfo().getState()==TaskInfoState.queued){
					Thread.sleep(5000);
				}

				//Check for copied/moved question
				VirtualMachineQuestionInfo q = vm.getRuntime().getQuestion();
				if(q!=null && q.getId().equals("_vmx1")){
					vm.answerVM(q.getId(), q.getChoice().getDefaultIndex().toString());
                    return;
				}
			}
		}catch(Exception e){
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
		if (info != null)
		{
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

	public void revertToSnapshot(String vmName, String snapName) throws VSphereException{

		VirtualMachine vm = getVmByName(vmName);
		VirtualMachineSnapshot snap = getSnapshotInTree(vm, snapName);

		if (snap == null) {
			LOGGER.log(Level.SEVERE, "Cannot find snapshot: '" + snapName + "' for virtual machine: '" + vm.getName()+"'");
			throw new VSphereException("Virtual Machine snapshot cannot be found");
		}

		try{
			Task task = snap.revertToSnapshot_Task(null);
			if (!task.waitForTask().equals(Task.SUCCESS)) {
				LOGGER.log(Level.SEVERE, "Could not revert to snapshot '" + snap.toString() + "' for virtual machine:'" + vm.getName()+"'");
				throw new VSphereException("Could not revert to snapshot");
			}
		}catch(Exception e){
			throw new VSphereException(e);
		}
	}

	public void deleteSnapshot(String vmName, String snapName, boolean consolidate, boolean failOnNoExist) throws VSphereException{

		VirtualMachine vm = getVmByName(vmName);
		VirtualMachineSnapshot snap = getSnapshotInTree(vm, snapName);

		if (snap == null && failOnNoExist) {
			throw new VSphereException("Virtual Machine snapshot cannot be found");
		}

		try{

			Task task;
			if (snap!=null){
				//Does not delete subtree; Implicitly consolidates disk
				task = snap.removeSnapshot_Task(false);
				if (!task.waitForTask().equals(Task.SUCCESS)) {
					throw new VSphereException("Could not delete snapshot");
				}
			}

			if(!consolidate)
				return;

			//This might be redundant, but I think it consolidates all disks,
			//where as the removeSnapshot only consolidates the individual disk
			task = vm.consolidateVMDisks_Task();
			if (!task.waitForTask().equals(Task.SUCCESS)) {
				throw new VSphereException("Could not consolidate VM disks");
			}
		}catch(Exception e){
			throw new VSphereException(e);
		}
	}

	public void takeSnapshot(String vmName, String snapshot, String description, boolean snapMemory) throws VSphereException{

            VirtualMachine vmToSnapshot = getVmByName(vmName);
            if (vmToSnapshot == null) {
                throw new VSphereException("Vm " + vmName + " was not found");
            }
        try {
			Task task = vmToSnapshot.createSnapshot_Task(snapshot, description, snapMemory, !snapMemory);
			if (task.waitForTask().equals(Task.SUCCESS)) {
				return;
			}
		} catch (Exception e) {
            throw new VSphereException(e);
        }

        throw new VSphereException("Could not take snapshot");
	}

	public void markAsTemplate(String vmName, String snapName, boolean force) throws VSphereException {

		try{
			VirtualMachine vm = getVmByName(vmName);
			if(vm.getConfig().template)
				return;

			if(isPoweredOff(vm) || force){
				powerOffVm(vm, force, false);
				vm.markAsTemplate();
				return;
			}
		}catch(Exception e){
			throw new VSphereException("Could not convert to Template", e);
		}

		throw new VSphereException("Could not mark as Template. Check it's power state or select \"force.\"");
	}

	public void markAsVm(String name, String resourcePool, String cluster) throws VSphereException{
		try{
			VirtualMachine vm = getVmByName(name);
			if(vm.getConfig().template){
				vm.markAsVirtualMachine(
						getResourcePoolByName(resourcePool, getClusterByName(cluster)),
						null
						);
			}
		}catch(Exception e){
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

		for(int count=0; count<maxTries; count++){

            GuestInfo guestInfo = vm.getGuest();

            // guest info can be null sometimes
			if (guestInfo != null && guestInfo.getIpAddress() != null){
				return guestInfo.getIpAddress();
			}

			try {
				//wait
				Thread.sleep(waitSeconds * 1000);
			} catch (InterruptedException e) {
				e.printStackTrace();
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
                    if(entity.getName().startsWith(prefix)) {
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
	 * Detroys the VM in vSphere
	 * @param name - VM object to destroy
	 * @param failOnNoExist If true and the VM does not exist then a VSphereException will be thrown.
	 * @throws VSphereException If an error occurred.
	 */
	public void destroyVm(String name, boolean failOnNoExist) throws VSphereException{
		try{
			VirtualMachine vm = getVmByName(name);
			if(vm==null){
				if(failOnNoExist) throw new VSphereException("VM does not exist");

				LOGGER.log(Level.FINER, "VM does not exist, or already deleted!");
				return;
			}


			if(!vm.getConfig().template) {
                powerOffVm(vm, true, false);
            }

			String status = vm.destroy_Task().waitForTask();
			if(status.equals(Task.SUCCESS))
			{
				LOGGER.log(Level.FINER, "VM was deleted successfully.");
				return;
			}

		}catch(Exception e){
			throw new VSphereException(e.getMessage());
		}

		throw new VSphereException("Could not delete VM!");
	}

    /**
     * Renames a VM Snapshot
     * @param vmName the name of the VM whose snapshot is being renamed.
     * @param oldName the current name of the VM's snapshot.
     * @param newName the new name of the VM's snapshot.
     * @param newDescription the new description of the VM's snapshot.
     * @throws VSphereException If an error occurred.
     */
    public void renameVmSnapshot(String vmName, String oldName, String newName, String newDescription) throws VSphereException{
        try{
            VirtualMachine vm = getVmByName(vmName);
            if(vm==null){
                throw new VSphereException("VM does not exist");
            }

            VirtualMachineSnapshot snapshot = getSnapshotInTree(vm, oldName);

            snapshot.renameSnapshot(newName, newDescription);

            LOGGER.log(Level.FINER, "VM Snapshot was renamed successfully.");
            return;

        }catch(Exception e){
            throw new VSphereException(e.getMessage());
        }
    }

    /**
     * Renames the VM vSphere
     * @param oldName the current name of the vm
     * @param newName the new name of the vm
     * @throws VSphereException If an error occurred.
     */
    public void renameVm(String oldName, String newName) throws VSphereException{
        try{
            VirtualMachine vm = getVmByName(oldName);
            if(vm==null){
                throw new VSphereException("VM does not exist");
            }

            String status = vm.rename_Task(newName).waitForTask();
            if(status.equals(Task.SUCCESS))
            {
                LOGGER.log(Level.FINER, "VM was renamed successfully.");
                return;
            }

        }catch(Exception e){
            throw new VSphereException(e.getMessage());
        }

        throw new VSphereException("Could not rename VM!");
    }

	private boolean isSuspended(VirtualMachine vm){
		return (vm.getRuntime().getPowerState() ==  VirtualMachinePowerState.suspended);
	}

	private boolean isPoweredOn(VirtualMachine vm){
		return (vm.getRuntime().getPowerState() ==  VirtualMachinePowerState.poweredOn);
	}

	private boolean isPoweredOff(VirtualMachine vm){
		return (vm.getRuntime() != null && vm.getRuntime().getPowerState() ==  VirtualMachinePowerState.poweredOff);
	}

    public boolean vmToolIsEnabled(VirtualMachine vm) {
        VirtualMachineToolsStatus status = vm.getGuest().toolsStatus;
        return ((status == VirtualMachineToolsStatus.toolsOk) || (status == VirtualMachineToolsStatus.toolsOld));
    }

	public void powerOffVm(VirtualMachine vm, boolean evenIfSuspended, boolean shutdownGracefully) throws VSphereException{

		if(vm.getConfig().template)
			throw new VSphereException("VM represents a template!");

		if (isPoweredOn(vm) || (evenIfSuspended && isSuspended(vm))) {
            boolean doHardShutdown = true;

            String status;
			try {
                if (!isSuspended(vm) && shutdownGracefully && vmToolIsEnabled(vm)) {
                    LOGGER.log(Level.FINER, "Requesting guest shutdown");
                    vm.shutdownGuest();

                    // Wait for up to 180 seconds for a shutdown - then shutdown hard.
                    for (int i = 0; i <= 180; i++) {
                        Thread.sleep(1000);
                        if (isPoweredOff(vm)) {
                            doHardShutdown = false;
                            LOGGER.log(Level.FINER, "VM gracefully powered down successfully.");
                            return;
                        }
                    }
                }

                if (doHardShutdown) {
                    LOGGER.log(Level.FINER, "Powering off the VM");
                    status = vm.powerOffVM_Task().waitForTask();

                    if(status.equals(Task.SUCCESS)) {
                        LOGGER.log(Level.FINER, "VM was powered down successfully.");
                        return;
                    }
                }
			} catch (Exception e) {
				throw new VSphereException(e);
			}
		}
		else if (isPoweredOff(vm)){
			LOGGER.log(Level.FINER, "Machine is already off.");
			return;
		}

		throw new VSphereException("Machine could not be powered down!");
	}

	public void suspendVm(VirtualMachine vm) throws VSphereException{
		if (isPoweredOn(vm)) {
			String status;
			try {
				//TODO is this better?
				//vm.shutdownGuest()
				status = vm.suspendVM_Task().waitForTask();
			} catch (Exception e) {
				throw new VSphereException(e);
			}

			if(Task.SUCCESS.equals(status)) {
				LOGGER.log(Level.FINER, "VM was suspended successfully.");
				return;
			}
		}
		else {
			LOGGER.log(Level.FINER, "Machine not powered on.");
			return;
		}

		throw new VSphereException("Machine could not be suspended!");
	}

	/**
	 * Private helper functions that finds the datanceter a VirtualMachine belongs to
	 * @param managedEntity - VM object
	 * @return returns Datacenter object
	 */
	private Datacenter getDataCenter(ManagedEntity managedEntity)
	{
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
														String name) throws VSphereException
	{
		try {
			Datacenter datacenter = getDataCenter(virtualMachine);
			for (Network network : datacenter.getNetworks())
			{
				if (network instanceof Network &&
						(name.isEmpty() || network.getName().contentEquals(name)))
				{
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
																			 String name) throws VSphereException
	{
		try {
			Datacenter datacenter = getDataCenter(virtualMachine);
			for (Network network : datacenter.getNetworks())
			{
				if (network instanceof DistributedVirtualPortgroup &&
						(name.isEmpty() || network.getName().contentEquals(name)))
				{
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
			DistributedVirtualPortgroup distributedVirtualPortgroup) throws VSphereException
	{
		try
		{
			ManagedObjectReference managedObjectReference = new ManagedObjectReference();
			managedObjectReference.setType("DistributedVirtualSwitch");
			managedObjectReference.setVal(distributedVirtualPortgroup.getConfig().getDistributedVirtualSwitch().getVal());
			return new DistributedVirtualSwitch(getServiceInstance().getServerConnection(), managedObjectReference);
		}
		catch (Exception e)
		{
			throw new VSphereException(e);
		}
	}

    /**
     * Passes data to a VM's "guestinfo" object. This data can then be read by
     * the VMware Tools on the guest.
     * <p>
     * e.g. a variable named "Foo" with value "Bar" could be read on the guest
     * using the command-line <tt>vmtoolsd --cmd "info-get guestinfo.Foo"</tt>.
     * </p>
     * 
     * @param vmName
     *            The name of the VM.
     * @param variables
     *            A {@link Map} of variable name to variable value.
     * @throws VSphereException
     *             If an error occurred.
     */
    public void addGuestInfoVariable(String vmName, Map<String, String> variables) throws VSphereException {
        VirtualMachineConfigSpec cs = new VirtualMachineConfigSpec();
        OptionValue[] ourOptionValues = new OptionValue[variables.size()];
        List<OptionValue> optionValues = new ArrayList<>();
        for (Map.Entry<String, String> eachVariable : variables.entrySet()) {
            OptionValue ov = new OptionValue();
            ov.setKey("guestinfo." + eachVariable.getKey());
            ov.setValue(eachVariable.getValue());
            optionValues.add(ov);
        }
        for (int i = 0; i < optionValues.size(); i++) {
            ourOptionValues[i] = optionValues.get(i);
        }
        cs.setExtraConfig(ourOptionValues);
        reconfigureVm(vmName, cs);
    }

    private void logMessage(PrintStream jLogger, String message) {
        if (jLogger != null) {
            VSphereLogger.vsLogger(jLogger, message);
        } else {
            LOGGER.log(Level.FINER, message);
        }
    }
}
