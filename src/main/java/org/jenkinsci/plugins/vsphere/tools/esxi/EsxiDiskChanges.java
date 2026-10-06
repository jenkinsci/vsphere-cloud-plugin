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
import com.vmware.vim25.VirtualController;
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
    private final Map<Integer, EsxiDiskBus.Place> newControllers = new HashMap<>();

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
        return device instanceof VirtualDisk || EsxiDiskBus.isController(device);
    }

    void apply(VirtualDeviceConfigSpec change) throws VSphereException {
        final VirtualDeviceConfigSpecOperation operation = change.getOperation();
        if (operation == null) {
            throw new VSphereException("The operation on a disk or a controller is not given");
        }
        if (EsxiDiskBus.isController(change.getDevice())) {
            final VirtualController controller = (VirtualController) change.getDevice();
            switch (operation) {
                case add:
                    addController(controller);
                    break;
                case remove:
                    removeController(controller);
                    break;
                default:
                    throw new VSphereException("A controller can be added or removed, not changed");
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

    private void addController(VirtualController controller) throws VSphereException {
        final EsxiDiskBus kind = EsxiDiskBus.of(controller);
        if (kind == null) {
            throw new VSphereException("The device is not a controller that disks go on");
        }
        if (kind.builtIn) {
            throw new VSphereException("A VM has its two IDE controllers always (ide0 and ide1): they cannot be"
                    + " added, only disks put on them");
        }
        final int bus = controller.getBusNumber();
        if (bus < 0 || bus >= kind.maxControllers) {
            throw new VSphereException(
                    "A VM has " + kind.prefix + " controllers 0 to " + (kind.maxControllers - 1) + ", not " + bus);
        }
        if (vmx.getBoolean(kind.prefix + bus + ".present")) {
            throw new VSphereException("The VM has the " + kind.prefix + " controller " + bus + " already");
        }
        final String prefix = kind.prefix + bus;
        vmx.put(prefix + ".present", "TRUE");
        if (controller instanceof VirtualSCSIController) {
            final VirtualSCSIController scsi = (VirtualSCSIController) controller;
            vmx.put(prefix + ".virtualDev", controllerType(scsi));
            if (scsi.getSharedBus() != null && scsi.getSharedBus() != VirtualSCSISharing.noSharing) {
                vmx.put(
                        prefix + ".sharedBus",
                        scsi.getSharedBus() == VirtualSCSISharing.physicalSharing ? "physical" : "virtual");
            }
        }
        newControllers.put(controller.getKey(), new EsxiDiskBus.Place(kind, bus, -1));
    }

    private EsxiDiskBus.Place existingController(VirtualController controller) throws VSphereException {
        final EsxiDiskBus.Place place = EsxiDiskBus.ofControllerKey(controller.getKey());
        if (place == null || place.bus != EsxiDiskBus.of(controller) || !place.bus.isPresent(vmx, place.controller)) {
            throw new VSphereException("There is no controller with the key " + controller.getKey());
        }
        return place;
    }

    private void removeController(VirtualController controller) throws VSphereException {
        final EsxiDiskBus.Place place = existingController(controller);
        if (place.bus.builtIn) {
            throw new VSphereException("The IDE controllers of a VM cannot be removed");
        }
        for (int unit = 0; unit < place.bus.maxUnits; unit++) {
            if (vmx.hasSettingsUnder(place.controllerPrefix() + ":" + unit)) {
                throw new VSphereException("The controller " + place.controllerPrefix() + " still has a device (unit "
                        + unit + "): remove that first");
            }
        }
        final String prefix = (place.controllerPrefix() + ".").toLowerCase();
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

    private EsxiDiskBus.Place busOfController(int controllerKey) throws VSphereException {
        final EsxiDiskBus.Place made = newControllers.get(controllerKey);
        if (made != null) {
            return made;
        }
        final EsxiDiskBus.Place place = EsxiDiskBus.ofControllerKey(controllerKey);
        if (place == null || !place.bus.isPresent(vmx, place.controller)) {
            throw new VSphereException("There is no controller with the key " + controllerKey + " to put a disk on");
        }
        return place;
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
        final EsxiDiskBus.Place controller = busOfController(disk.getControllerKey());
        final EsxiDiskBus kind = controller.bus;
        final int unit = disk.getUnitNumber() == null ? firstFreeUnit(controller) : disk.getUnitNumber();
        if (unit < 0 || unit >= kind.maxUnits || unit == kind.reservedUnit) {
            throw new VSphereException("The unit " + unit + " cannot be used for a disk on " + kind.prefix
                    + " (0 to " + (kind.maxUnits - 1) + (kind.reservedUnit >= 0 ? ", not " + kind.reservedUnit : "")
                    + ")");
        }
        final String prefix = new EsxiDiskBus.Place(kind, controller.controller, unit).diskPrefix();
        if (vmx.hasSettingsUnder(prefix)) {
            throw new VSphereException(
                    "The unit " + unit + " of " + controller.controllerPrefix() + " is in use already");
        }
        if (kind == EsxiDiskBus.IDE
                && unit == 1
                && !vmx.hasSettingsUnder(new EsxiDiskBus.Place(kind, controller.controller, 0).diskPrefix())) {
            // the host refuses to power on a VM with a slave and no master ("There is an IDE slave with no master")
            throw new VSphereException("The unit 1 of " + controller.controllerPrefix() + " (the slave) cannot be used"
                    + " while its unit 0 (the master) has nothing on it: the host would not power the VM on");
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
        if (kind == EsxiDiskBus.IDE) {
            vmx.put(prefix + ".deviceType", "ata-hardDisk");
        }
        vmx.put(prefix + ".fileName", VmxFile.escape(nameInVmx(path)));
        final String mode = file.getDiskMode();
        if (mode != null && !mode.equals("persistent")) {
            vmx.put(prefix + ".mode", mode.replace('_', '-'));
        }
    }

    private int firstFreeUnit(EsxiDiskBus.Place controller) throws VSphereException {
        for (int unit = 0; unit < controller.bus.maxUnits; unit++) {
            if (unit != controller.bus.reservedUnit
                    && !vmx.hasSettingsUnder(
                            new EsxiDiskBus.Place(controller.bus, controller.controller, unit).diskPrefix())) {
                return unit;
            }
        }
        throw new VSphereException("The controller " + controller.controllerPrefix() + " has no free unit");
    }

    /** The prefix in the .vmx of the disk that the key stands for, or fails if there is no such disk. */
    private String existingDisk(VirtualDisk disk) throws VSphereException {
        final EsxiDiskBus.Place place = EsxiDiskBus.ofDiskKey(disk.getKey());
        if (place == null
                || !place.bus.isPresent(vmx, place.controller)
                || !vmx.getBoolean(place.diskPrefix() + ".present")
                || vmx.get(place.diskPrefix() + ".fileName") == null) {
            throw new VSphereException("There is no disk with the key " + disk.getKey() + " in the VM");
        }
        return place.diskPrefix();
    }

    private void editDisk(VirtualDisk disk) throws VSphereException {
        final String prefix = existingDisk(disk);
        final String path = resolve(VmxFile.unescape(vmx.get(prefix + ".fileName", "")));
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
        final EsxiDiskBus.Place place = EsxiDiskBus.ofDiskKey(disk.getKey());
        if (place != null
                && place.bus == EsxiDiskBus.IDE
                && place.unit == 0
                && vmx.hasSettingsUnder(new EsxiDiskBus.Place(place.bus, place.controller, 1).diskPrefix())) {
            throw new VSphereException("The unit 0 of " + place.controllerPrefix() + " (the master) cannot be removed"
                    + " while its unit 1 (the slave) has a device on it: the host would not power the VM on");
        }
        final String path = resolve(VmxFile.unescape(vmx.get(prefix + ".fileName", "")));
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
