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
import org.kohsuke.stapler.DataBoundSetter;
import org.kohsuke.stapler.QueryParameter;

import edu.umd.cs.findbugs.annotations.NonNull;
import jakarta.servlet.ServletException;

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
	private DeviceAction deviceAction = DeviceAction.ADD;
	private String deviceLabel;
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

	public DeviceAction getDeviceAction() {
		return deviceAction;
	}

	@DataBoundSetter
	public void setDeviceAction(DeviceAction deviceAction) {
		this.deviceAction = deviceAction == null ? DeviceAction.ADD : deviceAction;
	}

	public String getDeviceLabel() {
		return deviceLabel;
	}

	@DataBoundSetter
	public void setDeviceLabel(String deviceLabel) {
		this.deviceLabel = deviceLabel;
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
		String expandedDiskSize = this.diskSize;
		String expandedDeviceLabel = deviceLabel;
		EnvVars env;

		try {
			env = run.getEnvironment(listener);
			if (run instanceof AbstractBuild) {
				env.overrideAll(((AbstractBuild) run).getBuildVariables()); // Add in matrix axes..
				if (this.diskSize != null) {
					expandedDiskSize = env.expand(this.diskSize);
				}
				if (deviceLabel != null) {
					expandedDeviceLabel = env.expand(deviceLabel);
				}
			}

			VirtualDeviceConfigSpec vdiskSpec;
			switch (deviceAction) {
				case EDIT:
					vdiskSpec = createEditDiskConfigSpec(vm, Integer.parseInt(expandedDiskSize), expandedDeviceLabel, jLogger);
					break;
				case REMOVE:
					vdiskSpec = createRemoveDiskConfigSpec(vm, expandedDeviceLabel, jLogger);
					break;
				case ADD:
				default:
					vdiskSpec = createAddDiskConfigSpec(vm, Integer.parseInt(expandedDiskSize), expandedDeviceLabel, jLogger);
					break;
			}
			VirtualDeviceConfigSpec [] vdiskSpecArray = {vdiskSpec};

			spec.setDeviceChange(vdiskSpecArray);
			VSphereLogger.vsLogger(jLogger, "Configuration done");
		} catch (Exception e) {
			throw new VSphereException(e);
		}

		return true;
	}

	private VirtualDeviceConfigSpec createAddDiskConfigSpec(
			VirtualMachine vm, int diskSize, String label, PrintStream jLogger) throws Exception
	{
		return createAddDiskConfigSpec(vm, diskSize, label, jLogger, 0);
	}

	private VirtualDeviceConfigSpec createAddDiskConfigSpec(
			VirtualMachine vm, int diskSize, String label, PrintStream jLogger, Integer retry) throws Exception
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

		String diskName;
		if (label != null && !label.isEmpty()) {
			if (diskNames.containsKey(String.format("%s/%s.vmdk", vm.getName(), label))) {
				throw new VSphereException("A disk named " + label + " already exists");
			}
			diskName = label;
		} else {
			diskName = null;
			for (int i = 1; ; ++i) {
				if (!diskNames.containsKey(String.format("%s/%s_%d.vmdk", vm.getName(), vm.getName(), i))) {
					diskName = String.format("%s_%d", vm.getName(), i);
					break;
				}
			}
		}

		VSphereLogger.vsLogger(jLogger, String.format("Preparing to add disk %s of %dGB", diskName, diskSize));

		if (scsiController == null) {
			if (retry > 1) {
				throw new VSphereException("Unable to add a SCSI Controller");
			}
			VSphereLogger.vsLogger(jLogger, String.format("Adding a SCSI Controller"));
			addSCSIController(vm);
			return createAddDiskConfigSpec(vm, diskSize, label, jLogger, retry + 1);
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

	private VirtualDeviceConfigSpec createEditDiskConfigSpec(
			VirtualMachine vm, int diskSize, String label, PrintStream jLogger) throws VSphereException
	{
		VirtualDisk disk = findDiskByLabel(vm, label, true);

		long diskSizeInKB = (long) diskSize * 1024 * 1024;
		long currentSizeInKB = disk.getCapacityInKB();

		if (diskSizeInKB < currentSizeInKB) {
			throw new VSphereException(String.format(
					"Cannot shrink disk %s from %dGB to %dGB", diskBaseName(disk), currentSizeInKB / 1024 / 1024, diskSize));
		}

		VSphereLogger.vsLogger(jLogger, String.format(
				"Resizing disk %s from %dGB to %dGB", diskBaseName(disk), currentSizeInKB / 1024 / 1024, diskSize));

		disk.setCapacityInKB(diskSizeInKB);

		VirtualDeviceConfigSpec diskSpec = new VirtualDeviceConfigSpec();
		diskSpec.setOperation(VirtualDeviceConfigSpecOperation.edit);
		diskSpec.setDevice(disk);

		return diskSpec;
	}

	private VirtualDeviceConfigSpec createRemoveDiskConfigSpec(
			VirtualMachine vm, String label, PrintStream jLogger) throws VSphereException
	{
		// Unlike EDIT, a lone disk is never auto-selected here: removing the wrong disk is destructive
		// and unrecoverable, so an explicit deviceLabel is always required.
		VirtualDisk disk = findDiskByLabel(vm, label, false);

		VSphereLogger.vsLogger(jLogger, String.format(
				"Removing disk %s (%dGB) and deleting its backing file", diskBaseName(disk), disk.getCapacityInKB() / 1024 / 1024));

		VirtualDeviceConfigSpec diskSpec = new VirtualDeviceConfigSpec();
		diskSpec.setOperation(VirtualDeviceConfigSpecOperation.remove);
		diskSpec.setFileOperation(VirtualDeviceConfigSpecFileOperation.destroy);
		diskSpec.setDevice(disk);

		return diskSpec;
	}

	/**
	 * Finds an existing disk. Disks are matched either by their vSphere device label (e.g. "Hard disk 1")
	 * or by their backing file's base name (e.g. "kube15_1", derived from "[datastore1] kube15/kube15_1.vmdk").
	 * If no label is given, {@code allowAutoSelectSingleDisk} controls whether a VM with exactly one disk
	 * may use it without a label.
	 */
	private VirtualDisk findDiskByLabel(VirtualMachine vm, String label, boolean allowAutoSelectSingleDisk) throws VSphereException {
		VirtualDisk match = null;
		VirtualDisk onlyDisk = null;
		int diskCount = 0;

		for (VirtualDevice vmDevice : vm.getConfig().getHardware().getDevice()) {
			if (!(vmDevice instanceof VirtualDisk)) {
				continue;
			}
			VirtualDisk disk = (VirtualDisk) vmDevice;
			diskCount++;
			onlyDisk = disk;

			if (label == null || label.isEmpty()) {
				continue;
			}

			String baseName = diskBaseName(disk);
			Description info = disk.getDeviceInfo();
			if (label.equals(baseName) || (info != null && label.equals(info.getLabel()))) {
				match = disk;
			}
		}

		if (label == null || label.isEmpty()) {
			if (allowAutoSelectSingleDisk && diskCount == 1) {
				return onlyDisk;
			}
			throw new VSphereException(String.format(
					"VM %s has %d disks attached; deviceLabel is required to select which one to use", vm.getName(), diskCount));
		}

		if (match == null) {
			throw new VSphereException("Could not find disk named " + label);
		}

		return match;
	}

	private String diskBaseName(VirtualDisk disk) {
		if (!(disk.getBacking() instanceof VirtualDeviceFileBackingInfo)) {
			return null;
		}
		VirtualDeviceFileBackingInfo info = (VirtualDeviceFileBackingInfo) disk.getBacking();
		Matcher m = filenamePattern.matcher(info.getFileName());
		if (!m.matches()) {
			return null;
		}
		String relativePath = m.group(1);
		String baseName = relativePath.substring(relativePath.lastIndexOf('/') + 1);
		if (baseName.endsWith(".vmdk")) {
			baseName = baseName.substring(0, baseName.length() - ".vmdk".length());
		}
		return baseName;
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
			// getControllerKey() is optional in the API, so compare from the controller's own key to keep a
			// device without one from unboxing null
			if (vmDevice.getUnitNumber() != null &&
					(Integer.valueOf(controller.getKey()).equals(vmDevice.getControllerKey()) || vmDevice.getKey() == controller.getKey())) {
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
