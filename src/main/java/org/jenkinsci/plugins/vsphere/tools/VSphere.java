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

import javax.annotation.CheckForNull;
import javax.annotation.Nonnull;

import org.apache.commons.lang.StringUtils;

import com.vmware.vim25.GuestInfo;
import com.vmware.vim25.InvalidProperty;
import com.vmware.vim25.ManagedObjectReference;
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
import com.vmware.vim25.mo.HostSystem;
import com.vmware.vim25.mo.InventoryNavigator;
import com.vmware.vim25.mo.ManagedEntity;
import com.vmware.vim25.mo.ResourcePool;
import com.vmware.vim25.mo.ServiceInstance;
import com.vmware.vim25.mo.Task;
import com.vmware.vim25.mo.VirtualMachine;
import com.vmware.vim25.mo.VirtualMachineSnapshot;

public class VSphere {
	private final URL url;
	private final String session;

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
	 * @throws VSphereException 
	 */
	public static VSphere connect(@Nonnull String server, @Nonnull String user, @CheckForNull String pw) throws VSphereException {
		return new VSphere(server, user, pw);
	}
        
        /**
         * Disconnect from vSphere server
         */
        public void disconnect() {
            try {
                this.getServiceInstance().getServerConnection().logout();
            } catch (Exception e) {
                System.out.println("Caught exception when trying to disconnect vSphere." + String.format("%n") + e);
            }
        }

	public static String vSphereOutput(String msg){
		return (Messages.VSphereLogger_title()+": ").concat(msg);
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
     * @throws VSphereException
     */
    public void deployVm(String cloneName, String sourceName, boolean linkedClone, String resourcePoolName, String cluster, String datastoreName, PrintStream jLogger) throws VSphereException {
        boolean DO_NOT_USE_SNAPSHOTS = false;
        logMessage(jLogger, "Deploying new vm \""+ cloneName + "\" from template \""+sourceName+"\"");
        cloneOrDeployVm(cloneName, sourceName, linkedClone, resourcePoolName, cluster, datastoreName, DO_NOT_USE_SNAPSHOTS, jLogger, null);
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
     * @param hostName 
     * @throws VSphereException
     */
    public void cloneVm(String cloneName, String sourceName, boolean linkedClone, String resourcePoolName, String cluster, String datastoreName, PrintStream jLogger, String hostName) throws VSphereException {
        boolean DO_USE_SNAPSHOTS = true;
        logMessage(jLogger, "Creating a shallow clone of \""+ sourceName + "\" to \""+cloneName+"\"");
        cloneOrDeployVm(cloneName, sourceName, linkedClone, resourcePoolName, cluster, datastoreName, DO_USE_SNAPSHOTS, jLogger, hostName);
    }

    private void cloneOrDeployVm(String cloneName, String sourceName, boolean linkedClone, String resourcePoolName, String cluster, String datastoreName, boolean useSnapshot, PrintStream jLogger, String hostName) throws VSphereException {
        try{
            VirtualMachine sourceVm = getVmByName(sourceName);

            if(sourceVm==null) {
                throw new VSphereException("VM or template \"" + sourceName + "\" not found");
            }

            if(getVmByName(cloneName)!=null){
                throw new VSphereException("VM \"" + cloneName + "\" already exists");
            }

            VirtualMachineRelocateSpec rel = createRelocateSpec(jLogger, linkedClone, resourcePoolName, 
            		cluster, datastoreName, sourceVm.getConfig().template, hostName);

            VirtualMachineCloneSpec cloneSpec = createCloneSpec(rel);
            cloneSpec.setTemplate(false);

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
                                                          String cluster, String datastoreName, boolean isResourcePoolRequired, String hostName) throws RemoteException, MalformedURLException, VSphereException {
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

        if (hostName != null && !hostName.isEmpty()) {
        	HostSystem hostSystem = getHostSystemByName(hostName,  clusterResource);

        	if (hostSystem == null) {
        		throw new VSphereException("Host System specified \"" + hostName + "\" not found");
        	}
        	
        	rel.setHost(hostSystem.getMOR());
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
        System.out.println("Reconfiguring VM. Please wait ...");
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
	 * @throws VSphereException 
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
            System.out.println("Checking " + timesToCheck + " times for vm to be powered on");

			for (int i=0; i<timesToCheck; i++){

				if(task.getTaskInfo().getState()==TaskInfoState.success){
					System.out.println("VM was powered up successfully.");
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
			throw new VSphereException("Virtual Machine snapshot cannot be found");
		}

		try{
			Task task = snap.revertToSnapshot_Task(null);
			if (!task.waitForTask().equals(Task.SUCCESS)) {
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
	 * Shortcut
	 * 
	 * @param vm - VirtualMachine name of which IP is returned
	 * @return - String containing IP address
	 * @throws VSphereException 
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
	 * @throws InvalidProperty
	 * @throws RuntimeFault
	 * @throws RemoteException
	 * @throws MalformedURLException 
	 * @throws VSphereException 
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

	private HostSystem getHostSystemByName(final String hostName, ManagedEntity rootEntity) throws RemoteException, MalformedURLException{
        if (rootEntity == null) {
            rootEntity = getServiceInstance().getRootFolder();
        }
        HostSystem hostsystem = (HostSystem) new InventoryNavigator(rootEntity).searchManagedEntity("HostSystem", hostName);
        if (hostsystem != null) {
            return hostsystem;
        }

        if (rootEntity == null || !(rootEntity instanceof ClusterComputeResource)) {
            return null;
        }

        // try to fetch host system directly from cluster if above approach doesn't work
        ClusterComputeResource clusterResource = (ClusterComputeResource) rootEntity;

        for (HostSystem hostSystem : clusterResource.getHosts()) {
            if (hostSystem.getName().equals(hostName)) {
                return hostSystem;
            }
        }
        return null;
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
	 * @throws InvalidProperty
	 * @throws RuntimeFault
	 * @throws RemoteException
	 * @throws MalformedURLException
	 * @throws VSphereException
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
	 * @throws VSphereException 
	 */
	public void destroyVm(String name, boolean failOnNoExist) throws VSphereException{
		try{
			VirtualMachine vm = getVmByName(name);
			if(vm==null){
				if(failOnNoExist) throw new VSphereException("VM does not exist");

				System.out.println("VM does not exist, or already deleted!");
				return;
			}


			if(!vm.getConfig().template) {
                powerOffVm(vm, true, false);
            }

			String status = vm.destroy_Task().waitForTask();
			if(status.equals(Task.SUCCESS))
			{
				System.out.println("VM was deleted successfully.");
				return;
			}

		}catch(Exception e){
			throw new VSphereException(e.getMessage());
		}

		throw new VSphereException("Could not delete VM!");
	}


    /**
     * Renames a VM Snapshot
     * @param oldName the current name of the vm
     * @param newName the new name of the vm
     * @param newDescription the new description of the vm
     * @throws VSphereException
     */
    public void renameVmSnapshot(String vmName, String oldName, String newName, String newDescription) throws VSphereException{
        try{
            VirtualMachine vm = getVmByName(vmName);
            if(vm==null){
                throw new VSphereException("VM does not exist");
            }

            VirtualMachineSnapshot snapshot = getSnapshotInTree(vm, oldName);

            snapshot.renameSnapshot(newName, newDescription);

            System.out.println("VM Snapshot was renamed successfully.");
            return;

        }catch(Exception e){
            throw new VSphereException(e.getMessage());
        }
    }


    /**
     * Renames the VM vSphere
     * @param oldName the current name of the vm
     * @param newName the new name of the vm
     * @throws VSphereException
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
                System.out.println("VM was renamed successfully.");
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
                    System.out.println("Requesting guest shutdown");
                    vm.shutdownGuest();

                    // Wait for up to 180 seconds for a shutdown - then shutdown hard.
                    for (int i = 0; i <= 180; i++) {
                        Thread.sleep(1000);
                        if (isPoweredOff(vm)) {
                            doHardShutdown = false;
                            System.out.println("VM gracefully powered down successfully.");
                            return;
                        }
                    }
                }

                if (doHardShutdown) {
                    System.out.println("Powering off the VM");
                    status = vm.powerOffVM_Task().waitForTask();

                    if(status.equals(Task.SUCCESS)) {
                        System.out.println("VM was powered down successfully.");
                        return;
                    }
                }
			} catch (Exception e) {
				throw new VSphereException(e);
			}
		}
		else if (isPoweredOff(vm)){
			System.out.println("Machine is already off.");
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
				System.out.println("VM was suspended successfully.");
				return;
			}
		}
		else {
			System.out.println("Machine not powered on.");
			return;
		}

		throw new VSphereException("Machine could not be suspended!");
	}

    private void logMessage(PrintStream jLogger, String message) {
        if (jLogger != null) {
            VSphereLogger.vsLogger(jLogger, message);
        } else {
            System.out.println(message);
        }
    }
}
