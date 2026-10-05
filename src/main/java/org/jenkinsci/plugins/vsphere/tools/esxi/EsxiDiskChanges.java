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

import com.vmware.vim25.ParaVirtualSCSIController;
import com.vmware.vim25.VirtualBusLogicController;
import com.vmware.vim25.VirtualDeviceBackingInfo;
import com.vmware.vim25.VirtualDeviceConfigSpec;
import com.vmware.vim25.VirtualDeviceConfigSpecFileOperation;
import com.vmware.vim25.VirtualDeviceConfigSpecOperation;
import com.vmware.vim25.VirtualDisk;
import com.vmware.vim25.VirtualDiskFlatVer2BackingInfo;
import com.vmware.vim25.VirtualLsiLogicSASController;
import com.vmware.vim25.VirtualSCSIController;
import com.vmware.vim25.VirtualSCSISharing;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.jenkinsci.plugins.vsphere.tools.VSphereException;

/**
 * The changes of disks and of SCSI controllers in a reconfiguration of a VM: the lines in the {@code .vmx} that say
 * which controllers and disks there are, and the list of what has to be done to disk files for that
 * ({@code vmkfstools}: make a disk, make it larger, delete it).
 *
 * <p>What is done to files is not done here; it is returned as {@link Step}s, so that the caller can do what has to
 * be done before the {@code .vmx} is written (and undo it if writing fails) apart from what has to be done after
 * (deleting the disks that are no longer used).
 */
final class EsxiDiskChanges {

    static final int SCSI_CONTROLLER_KEY_BASE = 1000;
    static final int DISK_KEY_BASE = 2000;
    static final int MAX_SCSI_CONTROLLERS = 4;
    static final int MAX_SCSI_UNITS = 16;
    private static final int RESERVED_UNIT = 7;

    /** What is needed to look at the disks of the host. */
    interface Inspector {
        /** The descriptor of a disk, which fails if there is none. */
        VmdkDescriptor descriptor(String path) throws VSphereException;

        boolean exists(String path) throws VSphereException;
    }

    /** One thing to do to a file of a disk. */
    static final class Step {
        enum Kind {
            CREATE,
            EXTEND,
            DELETE
        }

        final Kind kind;
        final String path;
        final long sizeKb;
        final boolean thin;

        Step(Kind kind, String path, long sizeKb, boolean thin) {
            this.kind = kind;
            this.path = path;
            this.sizeKb = sizeKb;
            this.thin = thin;
        }

        @Override
        public String toString() {
            return kind + " " + path + (sizeKb > 0 ? " " + sizeKb + "K" : "");
        }
    }

    private final VmxFile vmx;
    private final String vmFolder;
    private final Inspector inspector;
    private final List<Step> before = new ArrayList<>();
    private final List<Step> after = new ArrayList<>();
    /** The keys that a reconfiguration gave to the controllers it adds (they are negative), and their buses. */
    private final Map<Integer, Integer> newControllers = new HashMap<>();

    EsxiDiskChanges(VmxFile vmx, String vmFolder, Inspector inspector) {
        this.vmx = vmx;
        this.vmFolder = vmFolder;
        this.inspector = inspector;
    }

    /** What is to be done before the .vmx is written: disks to make, or to make larger. */
    List<Step> before() {
        return before;
    }

    /** What is to be done once the .vmx is written: disks that are not used any more, to delete. */
    List<Step> after() {
        return after;
    }

    /** True if this kind of device is handled here. */
    static boolean handles(Object device) {
        return device instanceof VirtualDisk || device instanceof VirtualSCSIController;
    }

    void apply(VirtualDeviceConfigSpec change) throws VSphereException {
        final VirtualDeviceConfigSpecOperation operation = change.getOperation();
        if (operation == null) {
            throw new VSphereException("The operation on a disk or a controller is not given");
        }
        if (change.getDevice() instanceof VirtualSCSIController) {
            final VirtualSCSIController controller = (VirtualSCSIController) change.getDevice();
            switch (operation) {
                case add:
                    addController(controller);
                    break;
                case remove:
                    removeController(controller);
                    break;
                default:
                    throw new VSphereException("A SCSI controller can be added or removed, not changed");
            }
        } else {
            final VirtualDisk disk = (VirtualDisk) change.getDevice();
            switch (operation) {
                case add:
                    addDisk(disk, change.getFileOperation());
                    break;
                case edit:
                    editDisk(disk);
                    break;
                default:
                    removeDisk(disk, change.getFileOperation() == VirtualDeviceConfigSpecFileOperation.destroy);
            }
        }
    }

    // -- controllers --

    private static String controllerType(VirtualSCSIController controller) {
        if (controller instanceof ParaVirtualSCSIController) {
            return "pvscsi";
        }
        if (controller instanceof VirtualLsiLogicSASController) {
            return "lsisas1068";
        }
        if (controller instanceof VirtualBusLogicController) {
            return "buslogic";
        }
        return "lsilogic";
    }

    private void addController(VirtualSCSIController controller) throws VSphereException {
        int bus = controller.getBusNumber();
        if (bus < 0 || bus >= MAX_SCSI_CONTROLLERS) {
            throw new VSphereException("A VM has SCSI controllers 0 to " + (MAX_SCSI_CONTROLLERS - 1) + ", not " + bus);
        }
        if (vmx.getBoolean("scsi" + bus + ".present")) {
            throw new VSphereException("The VM has the SCSI controller " + bus + " already");
        }
        final String prefix = "scsi" + bus;
        vmx.put(prefix + ".present", "TRUE");
        vmx.put(prefix + ".virtualDev", controllerType(controller));
        if (controller.getSharedBus() != null && controller.getSharedBus() != VirtualSCSISharing.noSharing) {
            vmx.put(
                    prefix + ".sharedBus",
                    controller.getSharedBus() == VirtualSCSISharing.physicalSharing ? "physical" : "virtual");
        }
        newControllers.put(controller.getKey(), bus);
    }

    private int busOf(VirtualSCSIController controller) throws VSphereException {
        final int bus = controller.getKey() - SCSI_CONTROLLER_KEY_BASE;
        if (bus < 0 || bus >= MAX_SCSI_CONTROLLERS || !vmx.getBoolean("scsi" + bus + ".present")) {
            throw new VSphereException("There is no SCSI controller with the key " + controller.getKey());
        }
        return bus;
    }

    private void removeController(VirtualSCSIController controller) throws VSphereException {
        final int bus = busOf(controller);
        for (int unit = 0; unit < MAX_SCSI_UNITS; unit++) {
            if (vmx.hasSettingsUnder("scsi" + bus + ":" + unit)) {
                throw new VSphereException(
                        "The SCSI controller " + bus + " still has a disk (unit " + unit + "): remove that first");
            }
        }
        final String prefix = ("scsi" + bus + ".").toLowerCase();
        for (String key : new ArrayList<>(vmx.keys())) {
            if (key.toLowerCase().startsWith(prefix)) {
                vmx.remove(key);
            }
        }
    }

    // -- disks --

    /** Where the file of a disk is, from how the .vmx names it (a name in the folder of the VM, or a full path). */
    String resolve(String fileName) {
        return fileName.startsWith("/") ? fileName : vmFolder + "/" + fileName;
    }

    /** The place in the file system that "[datastore] folder/name.vmdk" is, after checking it is plain. */
    static String pathOf(String datastorePath) throws VSphereException {
        final int end = datastorePath.indexOf(']');
        if (!datastorePath.startsWith("[") || end < 2) {
            throw new VSphereException("\"" + datastorePath + "\" is not a place on a datastore: [datastore] path");
        }
        final String datastore = EsxiDatastoreFiles.checkName("The datastore", datastorePath.substring(1, end));
        final String relative = datastorePath.substring(end + 1).trim();
        if (!relative.endsWith(".vmdk")) {
            throw new VSphereException("The file of a disk has to end with .vmdk, not \"" + relative + "\"");
        }
        final StringBuilder path = new StringBuilder("/vmfs/volumes/").append(datastore);
        for (String part : relative.split("/", -1)) {
            path.append('/').append(EsxiDatastoreFiles.checkName("The name of a disk or its folder", part));
        }
        return path.toString();
    }

    private String nameInVmx(String path) {
        return path.startsWith(vmFolder + "/") && path.indexOf('/', vmFolder.length() + 1) < 0
                ? path.substring(vmFolder.length() + 1)
                : path;
    }

    private int busOfController(int controllerKey) throws VSphereException {
        if (newControllers.containsKey(controllerKey)) {
            return newControllers.get(controllerKey);
        }
        final int bus = controllerKey - SCSI_CONTROLLER_KEY_BASE;
        if (bus < 0 || bus >= MAX_SCSI_CONTROLLERS || !vmx.getBoolean("scsi" + bus + ".present")) {
            throw new VSphereException(
                    "There is no SCSI controller with the key " + controllerKey + " to put a disk on");
        }
        return bus;
    }

    private void addDisk(VirtualDisk disk, VirtualDeviceConfigSpecFileOperation fileOperation) throws VSphereException {
        final VirtualDeviceBackingInfo backing = disk.getBacking();
        if (!(backing instanceof VirtualDiskFlatVer2BackingInfo)) {
            throw new VSphereException("A disk needs a file backing to be added");
        }
        final VirtualDiskFlatVer2BackingInfo file = (VirtualDiskFlatVer2BackingInfo) backing;
        if (disk.getControllerKey() == null) {
            throw new VSphereException("The controller to put the disk on is not given");
        }
        final int bus = busOfController(disk.getControllerKey());
        final int unit = disk.getUnitNumber() == null ? firstFreeUnit(bus) : disk.getUnitNumber();
        if (unit < 0 || unit >= MAX_SCSI_UNITS || unit == RESERVED_UNIT) {
            throw new VSphereException("The unit " + unit + " cannot be used for a disk (0 to 15, not 7)");
        }
        final String prefix = "scsi" + bus + ":" + unit;
        if (vmx.hasSettingsUnder(prefix)) {
            throw new VSphereException("The unit " + unit + " of the SCSI controller " + bus + " is in use already");
        }
        final String path = pathOf(file.getFileName());
        if (fileOperation == VirtualDeviceConfigSpecFileOperation.create) {
            if (disk.getCapacityInKB() < 1024) {
                throw new VSphereException("A disk has to be at least 1 MB, not " + disk.getCapacityInKB() + " KB");
            }
            if (inspector.exists(path)) {
                throw new VSphereException("A disk file " + path + " exists already");
            }
            before.add(new Step(
                    Step.Kind.CREATE, path, disk.getCapacityInKB(), Boolean.TRUE.equals(file.getThinProvisioned())));
        } else if (!inspector.exists(path)) {
            throw new VSphereException("The disk file " + path + " to attach does not exist");
        }
        vmx.put(prefix + ".present", "TRUE");
        vmx.put(prefix + ".fileName", VmxFile.escape(nameInVmx(path)));
        final String mode = file.getDiskMode();
        if (mode != null && !mode.equals("persistent")) {
            vmx.put(prefix + ".mode", mode.replace('_', '-'));
        }
    }

    private int firstFreeUnit(int bus) throws VSphereException {
        for (int unit = 0; unit < MAX_SCSI_UNITS; unit++) {
            if (unit != RESERVED_UNIT && !vmx.hasSettingsUnder("scsi" + bus + ":" + unit)) {
                return unit;
            }
        }
        throw new VSphereException("The SCSI controller " + bus + " has no free unit");
    }

    /** The prefix in the .vmx of the disk that the key stands for, or fails if there is no such disk. */
    private String existingDisk(VirtualDisk disk) throws VSphereException {
        final int index = disk.getKey() - DISK_KEY_BASE;
        final int bus = index / MAX_SCSI_UNITS;
        final int unit = index % MAX_SCSI_UNITS;
        final String prefix = "scsi" + bus + ":" + unit;
        if (index < 0
                || bus >= MAX_SCSI_CONTROLLERS
                || !vmx.getBoolean(prefix + ".present")
                || vmx.get(prefix + ".fileName") == null) {
            throw new VSphereException("There is no disk with the key " + disk.getKey() + " in the VM");
        }
        return prefix;
    }

    private void editDisk(VirtualDisk disk) throws VSphereException {
        final String prefix = existingDisk(disk);
        final String path = resolve(VmxFile.unescape(vmx.get(prefix + ".fileName")));
        final VmdkDescriptor descriptor = inspector.descriptor(path);
        final long current = descriptor.getCapacityKb();
        final long wanted = disk.getCapacityInKB();
        if (wanted < current) {
            throw new VSphereException(
                    "A disk cannot be made smaller (" + current / 1024 + " MB to " + wanted / 1024 + " MB)");
        }
        if (wanted == current) {
            return;
        }
        if (descriptor.isSnapshotDisk()) {
            throw new VSphereException("The disk " + path + " has snapshots, and a disk can only be made larger"
                    + " without: remove the snapshots first");
        }
        before.add(new Step(Step.Kind.EXTEND, path, wanted, false));
    }

    private void removeDisk(VirtualDisk disk, boolean destroy) throws VSphereException {
        final String prefix = existingDisk(disk);
        final String path = resolve(VmxFile.unescape(vmx.get(prefix + ".fileName")));
        if (destroy) {
            if (inspector.descriptor(path).isSnapshotDisk()) {
                throw new VSphereException("The disk " + path + " has snapshots, so its files cannot be deleted:"
                        + " remove the snapshots first");
            }
            after.add(new Step(Step.Kind.DELETE, path, 0, false));
        }
        final String dotted = (prefix + ".").toLowerCase();
        for (String key : new ArrayList<>(vmx.keys())) {
            if (key.toLowerCase().startsWith(dotted)) {
                vmx.remove(key);
            }
        }
    }
}
