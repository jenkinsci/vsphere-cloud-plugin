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

import java.net.MalformedURLException;
import java.net.URL;
import java.rmi.RemoteException;

import com.vmware.vim25.InvalidProperty;
import com.vmware.vim25.ManagedObjectReference;
import com.vmware.vim25.RuntimeFault;
import com.vmware.vim25.TaskInfoState;
import com.vmware.vim25.VirtualMachineCloneSpec;
import com.vmware.vim25.VirtualMachinePowerState;
import com.vmware.vim25.VirtualMachineQuestionInfo;
import com.vmware.vim25.VirtualMachineRelocateSpec;
import com.vmware.vim25.VirtualMachineSnapshotTree;
import com.vmware.vim25.mo.ClusterComputeResource;
import com.vmware.vim25.mo.Folder;
import com.vmware.vim25.mo.InventoryNavigator;
import com.vmware.vim25.mo.ManagedEntity;
import com.vmware.vim25.mo.ResourcePool;
import com.vmware.vim25.mo.ServiceInstance;
import com.vmware.vim25.mo.Task;
import com.vmware.vim25.mo.VirtualMachine;

public class VSphere {
	private final URL url;
	private final String session;
	private final String resourcePool;
	private final String cluster;

	private VSphere(String url, String user, String pw, String cluster, String resourcePool) throws VSphereException{

		this.resourcePool = resourcePool;
		this.cluster = cluster;

		try {
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
	 * @throws VSphereException 
	 */
	public static VSphere connect(String server, String user, String pw) throws VSphereException {
		return new VSphere(server, user, pw, null, null);
	}

	public static VSphere connect(String server, String user, String pw, String cluster, String resourcePool) throws VSphereException {
		return new VSphere(server, user, pw, cluster, resourcePool);
	}

	public static String vSphereOutput(String msg){
		return (Messages.VSphereLogger_title()+": ").concat(msg);
	}

	/**
	 * Creates a new VM from a given template with a given name.
	 * 
	 * @param cloneName - name of VM to be created
	 * @param template - vsphere template name to clone
	 * @param verboseOutput - true for extra output to logs
	 * @return - Virtual Machine object of the new VM
	 * @throws Exception 
	 */
	public VirtualMachine shallowCloneVm(String cloneName, String template, boolean powerOn, boolean linkedClone) throws VSphereException {

		System.out.println("Creating a shallow clone of \""+ template + "\" to \""+cloneName+"\"");
		try{
			VirtualMachine sourceVm = getVmByName(template);

			if(sourceVm==null) {
				throw new VSphereException("No template " + template + " found");
			}

			if(getVmByName(cloneName)!=null){
				throw new VSphereException("VM " + cloneName + " already exists");
			}

			System.out.println("with \""+ cluster + "\" and  \""+resourcePool+"\"");
			VirtualMachineRelocateSpec rel  = new VirtualMachineRelocateSpec();

			if(linkedClone){
				rel.setDiskMoveType("createNewChildDiskBacking");
			}else{
				rel.setDiskMoveType("moveAllDiskBackingsAndDisallowSharing");
			}

			rel.setPool(getResourcePoolByName(resourcePool, getClusterByName(cluster)).getMOR());

			VirtualMachineCloneSpec cloneSpec = new VirtualMachineCloneSpec();
			cloneSpec.setLocation(rel);
			cloneSpec.setPowerOn(powerOn);
			cloneSpec.setTemplate(false);

			if(sourceVm.getCurrentSnapShot()==null){
				throw new VSphereException("Template \"" + template + "\" requires at least one snapshot!");
			}
			cloneSpec.setSnapshot(sourceVm.getCurrentSnapShot().getMOR());

			Task task = sourceVm.cloneVM_Task((Folder) sourceVm.getParent(), 
					cloneName, cloneSpec);
			System.out.println("Cloning VM. Please wait ...");

			String status = task.waitForTask();
			if(status==TaskInfoState.success.toString()) {
				System.out.println("VM got cloned successfully.");
				return getVmByName(cloneName);
			}

		}catch(Exception e){
			throw new VSphereException(e);
		}

		throw new VSphereException("Error cloning \""+template+"!\" Does \""+cloneName+"\" already exist?");
	}	  

	/**
	 * @param name - Name of VM to start
	 * @throws VSphereException 
	 */
	public void startVm(String name) throws VSphereException {

		try{
			VirtualMachine vm = getVmByName(name);
			if(isPoweredOn(vm))
				return;

			Task task = vm.powerOnVM_Task(null);

			for (int i=0, j=3; i<j; i++){

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
			throw new VSphereException("VM cannot be started:", e);
		}

		throw new VSphereException("VM cannot be started");
	}

	public ManagedObjectReference findSnapshotInTree(
			VirtualMachineSnapshotTree[] snapTree, String snapName) {
		for (int i = 0; i < snapTree.length; i++) {
			VirtualMachineSnapshotTree node = snapTree[i];
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

	public void revertToSnapshot(String vmName, String snapName){

		/*VirtualMachine vm = getVmByName(vmName);
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
        else
        {
            throw new Exception("No snapshots exist or unable to access the snapshot array");
        }            
        return null;


		VirtualMachineSnapshot snap = vsC.getSnapshotInTree(vm, snapName);
        if (snap == null) {
            throw new IOException("Virtual Machine snapshot cannot be found");
        }

        vSphereCloud.Log(slaveComputer, taskListener, "Reverting to snapshot:" + snapName);
        Task task = snap.revertToSnapshot_Task(null);
        if (!task.waitForTask().equals(Task.SUCCESS)) {
            throw new IOException("Error while reverting to virtual machine snapshot");
        }*/
	}

	public void takeSnapshot(String name, String snapshot, String description) throws VSphereException{

		try {
			Task task = getVmByName(name).createSnapshot_Task(snapshot, description, false, false);
			if (task.waitForTask()==Task.SUCCESS) {
				return;
			}
		} catch (Exception e) {
			throw new VSphereException("Could not take snapshot", e);
		}

		throw new VSphereException("Could not take snapshot");
	}

	public void markAsTemplate(String vmName, String snapName, String desc, boolean force) throws VSphereException {

		try{
			VirtualMachine vm = getVmByName(vmName);
			if(vm.getConfig().template)
				return;

			if(isPoweredOff(vm) || force){
				powerDown(vm, force);
				takeSnapshot(vmName, snapName, desc);
				vm.markAsTemplate();
				return;
			}
		}catch(Exception e){
			throw new VSphereException("Error: Could not convert to Template", e);
		}

		throw new VSphereException("Error: Could not mark as Template. Check it's power state or select \"force.\"");
	}

	public VirtualMachine markAsVm(String name) throws VSphereException{
		try{
			VirtualMachine vm = getVmByName(name);
			if(vm.getConfig().template){
				vm.markAsVirtualMachine(
						getResourcePoolByName(resourcePool, getClusterByName(cluster)),
						null
						);
			}
			return vm;

		}catch(Exception e){
			throw new VSphereException("Error: Could not convert to VM", e);
		}
	}

	/**
	 * Shortcut
	 * 
	 * @param name - VirtualMachine name of which IP is returned
	 * @return - String containing IP address
	 * @throws VSphereException 
	 */
	public String getIp(VirtualMachine vm) throws VSphereException {

		if (vm==null)
			throw new VSphereException("vm is null");

		for(int count=0; count<VSphereConstants.IP_MAX_TRIES; ++count){
			if(vm.getGuest().getIpAddress()!=null){
				return vm.getGuest().getIpAddress();
			}
			try {
				Thread.sleep(VSphereConstants.IP_MAX_SECONDS * 1000);
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
	public VirtualMachine getVmByName(String vmName) throws InvalidProperty, RuntimeFault, RemoteException, MalformedURLException {

		return (VirtualMachine) new InventoryNavigator(
				getServiceInstance().getRootFolder()).searchManagedEntity(
						"VirtualMachine", vmName);
	}

	/**
	 * @param poolName - Name of pool to use
	 * @return - ResourcePool obect
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
	 * @param vm - VM object to destroy
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

			if(vm.getConfig().template)
				throw new VSphereException("Error: Specified name represents a template, not a VM.");

			powerDown(vm, true);

			String status = vm.destroy_Task().waitForTask();
			if(status==Task.SUCCESS)
			{
				System.out.println("VM was deleted successfully.");
				return;
			}

		}catch(Exception e){
			throw new VSphereException(e.getMessage());
		}

		throw new VSphereException("Error destroying VM");
	}

	private boolean isSuspended(VirtualMachine vm){
		return (vm.getRuntime().getPowerState() ==  VirtualMachinePowerState.suspended);
	}

	private boolean isPoweredOn(VirtualMachine vm){
		return (vm.getRuntime().getPowerState() ==  VirtualMachinePowerState.poweredOn);
	}

	private boolean isPoweredOff(VirtualMachine vm){
		return (vm.getRuntime().getPowerState() ==  VirtualMachinePowerState.poweredOff);
	}

	private void powerDown(VirtualMachine vm, boolean evenIfSuspended) throws VSphereException{
		if (isPoweredOn(vm) || (evenIfSuspended && isSuspended(vm))) {
			String status;
			try {
				//TODO is this better?
				//vm.shutdownGuest()
				status = vm.powerOffVM_Task().waitForTask();
			} catch (Exception e) {
				throw new VSphereException(e);
			}

			if(status==Task.SUCCESS) {
				System.out.println("VM was powered down successfully.");
				return;
			}
		}
		else if (isPoweredOff(vm)){
			System.out.println("Machine is already off.");
			return;
		}

		throw new VSphereException("Machine could not be powered down!");
	}
}
