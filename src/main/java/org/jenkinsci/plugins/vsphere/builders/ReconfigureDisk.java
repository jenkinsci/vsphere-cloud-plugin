/*   Copyright 2014, Camille Meulien
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
package org.jenkinsci.plugins.vsphere.builders;

import com.vmware.vim25.*;
import com.vmware.vim25.mo.Datastore;
import com.vmware.vim25.mo.ManagedEntity;
import com.vmware.vim25.mo.Task;
import com.vmware.vim25.mo.VirtualMachine;

import hudson.*;
import hudson.Extension;
import hudson.model.AbstractBuild;
import hudson.model.BuildListener;
import hudson.model.Run;
import hudson.model.TaskListener;
import hudson.util.FormValidation;

import org.jenkinsci.plugins.vsphere.tools.VSphereException;
import org.jenkinsci.plugins.vsphere.tools.VSphereLogger;
import org.kohsuke.stapler.DataBoundConstructor;
import org.kohsuke.stapler.QueryParameter;

import edu.umd.cs.findbugs.annotations.NonNull;
import javax.servlet.ServletException;

import java.io.IOException;
import java.io.PrintStream;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class ReconfigureDisk extends ReconfigureStep {

	private final String diskSize;
	private final String datastore;
	private final static Pattern filenamePattern = Pattern.compile("^\\[[^]]*\\] (.*)$");

	@DataBoundConstructor
	public ReconfigureDisk(String diskSize, String datastore) throws VSphereException {
		this.diskSize = diskSize;
		this.datastore = datastore;
	}

	public String getDiskSize() {
		return diskSize;
	}

	public String getDataStore() {
		return datastore;
	}

	@Override
	public void perform(@NonNull Run<?, ?> run, @NonNull FilePath filePath, @NonNull Launcher launcher, @NonNull TaskListener listener) throws InterruptedException, IOException {
		try {
			reconfigureDisk(run, launcher, listener);
		} catch (Exception e) {
			throw new AbortException(e.getMessage());
		}
	}

	@Override
	public boolean perform(final AbstractBuild<?, ?> build, final Launcher launcher, final BuildListener listener)  {
		boolean retVal = false;
		try {
			retVal = reconfigureDisk(build, launcher, listener);
		} catch (Exception e) {
			e.printStackTrace();
		}
		return retVal;
		//TODO throw AbortException instead of returning value
	}

	public boolean reconfigureDisk(final Run<?, ?> run, final Launcher launcher, final TaskListener listener) throws VSphereException  {

		PrintStream jLogger = listener.getLogger();
		int diskSize = Integer.parseInt(this.diskSize);
		EnvVars env;

		try {
			env = run.getEnvironment(listener);
			if (run instanceof AbstractBuild) {
				env.overrideAll(((AbstractBuild) run).getBuildVariables()); // Add in matrix axes..
				diskSize = Integer.parseInt(env.expand(this.diskSize));
			}
			VirtualDeviceConfigSpec vdiskSpec = createAddDiskConfigSpec(vm, diskSize, jLogger);
			VirtualDeviceConfigSpec [] vdiskSpecArray = {vdiskSpec};

			spec.setDeviceChange(vdiskSpecArray);
			VSphereLogger.vsLogger(jLogger, "Configuration done");
		} catch (Exception e) {
			throw new VSphereException(e);
		}

		return true;
	}

	private VirtualDeviceConfigSpec createAddDiskConfigSpec(
			VirtualMachine vm, int diskSize, PrintStream jLogger) throws Exception
	{
		return createAddDiskConfigSpec(vm, diskSize, jLogger, 0);
	}

	private VirtualDeviceConfigSpec createAddDiskConfigSpec(
			VirtualMachine vm, int diskSize, PrintStream jLogger, Integer retry) throws Exception
	{
		VirtualDeviceConfigSpec diskSpec = new VirtualDeviceConfigSpec();
		VirtualDisk disk =  new VirtualDisk();
		VirtualDiskFlatVer2BackingInfo diskfileBacking = new VirtualDiskFlatVer2BackingInfo();
		VirtualSCSIController scsiController = null;

		int key = 0;
		int unitNumber;
		int diskSizeInKB = diskSize * 1024 * 1024;

		String diskMode = "persistent";
		HashMap<String, Boolean> diskNames = new HashMap<String, Boolean>();

		for (VirtualDevice vmDevice : vm.getConfig().getHardware().getDevice()) {
			if (vmDevice instanceof VirtualSCSIController) {
				int[] list = ((VirtualSCSIController)vmDevice).getDevice();
				if (scsiController == null && (list == null || list.length < 15)) {
					scsiController = (VirtualSCSIController) vmDevice;
				}
			} else if (vmDevice instanceof VirtualDisk) {
				if (vmDevice.getBacking() instanceof VirtualDeviceFileBackingInfo) {
					VirtualDeviceFileBackingInfo info = (VirtualDeviceFileBackingInfo) vmDevice.getBacking();
					Matcher m = filenamePattern.matcher(info.getFileName());
					if (m.matches()) {
						diskNames.put(m.group(1), true);
					} else {
						VSphereLogger.vsLogger(jLogger, String.format("Warning: unrecognized disk filename format: %s", info.getFileName()));
					}
				}
			}
		}

		String diskName = null;
		for (int i = 1; ; ++i) {
			if (!diskNames.containsKey(String.format("%s/%s_%d.vmdk", vm.getName(), vm.getName(), i))) {
				diskName = String.format("%s_%d", vm.getName(), i);
				break;
			}
		}

		VSphereLogger.vsLogger(jLogger, String.format("Preparing to add disk %s of %dGB", diskName, diskSize));

		if (scsiController == null) {
			if (retry > 1) {
				throw new VSphereException("Unable to add a SCSI Controller");
			}
			VSphereLogger.vsLogger(jLogger, String.format("Adding a SCSI Controller"));
			addSCSIController(vm);
			return createAddDiskConfigSpec(vm, diskSize, jLogger, retry + 1);
		}

		unitNumber = selectUnitNumber(vm, scsiController);
		key = scsiController.getKey();

		VSphereLogger.vsLogger(jLogger, String.format("Controller key: %d Unit Number %d", key, unitNumber));

		String dsName = selectDatastore(diskSizeInKB, jLogger);
		if (dsName == null)
		{
			return null;
		}
		String fileName = "["+ dsName +"] "+ vm.getName() + "/" + diskName + ".vmdk";

		diskfileBacking.setFileName(fileName);
		diskfileBacking.setDiskMode(diskMode);

		disk.setControllerKey(key);
		disk.setUnitNumber(unitNumber);
		disk.setBacking(diskfileBacking);
		disk.setCapacityInKB(diskSizeInKB);
		disk.setKey(-1);

		diskSpec.setOperation(VirtualDeviceConfigSpecOperation.add);
		diskSpec.setFileOperation(VirtualDeviceConfigSpecFileOperation.create);
		diskSpec.setDevice(disk);

		return diskSpec;
	}

	private VirtualLsiLogicController addSCSIController(VirtualMachine vm) throws Exception {
		VirtualMachineConfigInfo vmConfig = vm.getConfig();
		VirtualPCIController pci = null;
		Set<Integer> scsiBuses = new HashSet<Integer>();

		for (VirtualDevice vmDevice : vmConfig.getHardware().getDevice()) {
			if (vmDevice instanceof VirtualPCIController) {
				pci = (VirtualPCIController) vmDevice;
			} else if (vmDevice instanceof VirtualSCSIController) {
				VirtualSCSIController ctrl = (VirtualSCSIController) vmDevice;
				scsiBuses.add(ctrl.getBusNumber());
			}
		}
		if (pci == null) {
			throw new VSphereException("No PCI controller found");
		}
		VirtualMachineConfigSpec vmSpec = new VirtualMachineConfigSpec();
		VirtualDeviceConfigSpec deviceSpec = new VirtualDeviceConfigSpec();
		deviceSpec.setOperation(VirtualDeviceConfigSpecOperation.add);
		VirtualLsiLogicController scsiCtrl = new VirtualLsiLogicController();
		scsiCtrl.setControllerKey(pci.getKey());
		scsiCtrl.setSharedBus(VirtualSCSISharing.noSharing);
		for (int i=0 ; ; ++i) {
			if (!scsiBuses.contains(Integer.valueOf(i))) {
				scsiCtrl.setBusNumber(i);
				break;
			}
		}
		deviceSpec.setDevice(scsiCtrl);
		vmSpec.setDeviceChange(new VirtualDeviceConfigSpec[] {deviceSpec});
		Task task = vm.reconfigVM_Task(vmSpec);
		task.waitForTask();
		return scsiCtrl;
	}

	private int selectUnitNumber(VirtualMachine vm, VirtualController controller) {
		HashMap<Integer, Boolean> map = new HashMap<Integer, Boolean>();
		int unitNumber = 0;

		map.put(7, true); // Unit number 7 is reserved for the controller

		for (VirtualDevice vmDevice : vm.getConfig().getHardware().getDevice()) {
			if (vmDevice.getUnitNumber() != null &&
					(vmDevice.getControllerKey() == controller.getKey() || vmDevice.getKey() == controller.getKey())) {
				map.put(vmDevice.getUnitNumber(), true);
			}
		}
		while (map.containsKey(unitNumber)) {
			unitNumber++;
		}
		return unitNumber;
	}

	private String selectDatastore(int sizeInKB, PrintStream jLogger) throws Exception
	{
		Datastore datastore = null;
		long freeSpace = 0;

		for (ManagedEntity entity : vsphere.getDatastores()) {
			if (entity instanceof Datastore) {
				Datastore ds = (Datastore)entity;
				long fs = ds.getSummary().getFreeSpace();
				if (this.datastore != null && this.datastore.length() > 0 && !ds.getName().equals(this.datastore)) {
					continue;
                                }
				if (fs > sizeInKB && fs > freeSpace) {
					datastore = ds;
					freeSpace = fs;
				}
			}
		}

		if (datastore == null) {
			throw new VSphereException("No datastore with enough space found");
		}

		VSphereLogger.vsLogger(jLogger, String.format("Selected datastore `%s` with free size: %dGB", datastore.getName(), freeSpace / 1024 / 1024 / 1024));
		return datastore.getName();
	}

	@Extension
	public static final class ReconfigureDiskDescriptor extends ReconfigureStepDescriptor {

		public ReconfigureDiskDescriptor() {
			load();
		}

		public FormValidation doCheckDiskSize(@QueryParameter String value)
				throws IOException, ServletException {

			if (value.length() == 0)
				return FormValidation.error(Messages.validation_required("Disk size"));
			return FormValidation.ok();
		}

		public FormValidation doCheckDatastore(@QueryParameter String value)
				throws IOException, ServletException {
			return FormValidation.ok();
		}
		@Override
		public String getDisplayName() {
			return Messages.vm_title_ReconfigureDisk();
		}

		public FormValidation doTestData(@QueryParameter String diskSize, @QueryParameter String datastore) {
			try {
				if (Integer.valueOf(diskSize) < 0) {
					return FormValidation.error(Messages.validation_positiveInteger(diskSize));
				}
				return FormValidation.ok();

			} catch (Exception e) {
				throw new RuntimeException(e);
			}
		}
	}

}
