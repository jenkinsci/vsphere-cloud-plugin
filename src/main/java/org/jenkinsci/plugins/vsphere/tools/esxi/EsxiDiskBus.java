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

import com.vmware.vim25.VirtualController;
import com.vmware.vim25.VirtualIDEController;
import com.vmware.vim25.VirtualNVMEController;
import com.vmware.vim25.VirtualSATAController;
import com.vmware.vim25.VirtualSCSIController;
import edu.umd.cs.findbugs.annotations.CheckForNull;

/**
 * The kinds of bus that disks are on, as the {@code .vmx} of a VM has them: {@code scsiN}, {@code ideN}, {@code sataN}
 * and {@code nvmeN} controllers, and {@code scsiN:U} and so on for the disks on them. The keys of the devices that the
 * VM is described with (as vCenter numbers them: controllers at 1000, 200, 15000 and 31000, disks at 2000, 3000, 16000
 * and 19000) are made from the bus and the unit, and tell them back.
 *
 * <p>A VM always has its two IDE controllers: they cannot be added or removed, only disks put on them.
 */
enum EsxiDiskBus {
    SCSI("scsi", "SCSI controller ", 1000, 2000, 4, 16, 7, false),
    IDE("ide", "IDE ", 200, 3000, 2, 2, -1, true),
    SATA("sata", "SATA controller ", 15000, 16000, 4, 30, -1, false),
    NVME("nvme", "NVME controller ", 31000, 19000, 4, 15, -1, false);

    /** Where on which bus: a controller, and a disk if a unit is given. */
    static final class Place {
        final EsxiDiskBus bus;
        final int controller;
        final int unit;

        Place(EsxiDiskBus bus, int controller, int unit) {
            this.bus = bus;
            this.controller = controller;
            this.unit = unit;
        }

        /** The start of the .vmx keys of the controller. */
        String controllerPrefix() {
            return bus.prefix + controller;
        }

        /** The start of the .vmx keys of the disk. */
        String diskPrefix() {
            return bus.prefix + controller + ":" + unit;
        }
    }

    final String prefix;
    final String label;
    final int controllerKeyBase;
    final int diskKeyBase;
    final int maxControllers;
    final int maxUnits;
    final int reservedUnit;
    final boolean builtIn;

    EsxiDiskBus(
            String prefix,
            String label,
            int controllerKeyBase,
            int diskKeyBase,
            int maxControllers,
            int maxUnits,
            int reservedUnit,
            boolean builtIn) {
        this.prefix = prefix;
        this.label = label;
        this.controllerKeyBase = controllerKeyBase;
        this.diskKeyBase = diskKeyBase;
        this.maxControllers = maxControllers;
        this.maxUnits = maxUnits;
        this.reservedUnit = reservedUnit;
        this.builtIn = builtIn;
    }

    int controllerKey(int controller) {
        return controllerKeyBase + controller;
    }

    int diskKey(int controller, int unit) {
        return diskKeyBase + controller * maxUnits + unit;
    }

    /** True if the VM has the controller: the IDE ones always, the others if their {@code .present} says so. */
    boolean isPresent(VmxFile vmx, int controller) {
        return builtIn || vmx.getBoolean(prefix + controller + ".present");
    }

    /** The bus that this kind of controller is on, or null if it is not one that disks go on. */
    @CheckForNull
    static EsxiDiskBus of(@CheckForNull Object device) {
        if (device instanceof VirtualSCSIController) {
            return SCSI;
        }
        if (device instanceof VirtualIDEController) {
            return IDE;
        }
        if (device instanceof VirtualSATAController) {
            return SATA;
        }
        if (device instanceof VirtualNVMEController) {
            return NVME;
        }
        return null;
    }

    static boolean isController(@CheckForNull Object device) {
        return device instanceof VirtualController && of(device) != null;
    }

    /** The controller that has the key, or null. */
    @CheckForNull
    static Place ofControllerKey(int key) {
        for (EsxiDiskBus bus : values()) {
            final int controller = key - bus.controllerKeyBase;
            if (controller >= 0 && controller < bus.maxControllers) {
                return new Place(bus, controller, -1);
            }
        }
        return null;
    }

    /** The disk that has the key, or null. */
    @CheckForNull
    static Place ofDiskKey(int key) {
        for (EsxiDiskBus bus : values()) {
            final int index = key - bus.diskKeyBase;
            if (index >= 0 && index < bus.maxControllers * bus.maxUnits) {
                return new Place(bus, index / bus.maxUnits, index % bus.maxUnits);
            }
        }
        return null;
    }
}
