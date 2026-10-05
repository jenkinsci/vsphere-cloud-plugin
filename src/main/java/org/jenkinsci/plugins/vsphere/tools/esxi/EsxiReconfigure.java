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

import com.vmware.vim25.OptionValue;
import com.vmware.vim25.ResourceAllocationInfo;
import com.vmware.vim25.SharesInfo;
import com.vmware.vim25.VirtualDevice;
import com.vmware.vim25.VirtualDeviceBackingInfo;
import com.vmware.vim25.VirtualDeviceConfigSpec;
import com.vmware.vim25.VirtualDeviceConfigSpecOperation;
import com.vmware.vim25.VirtualE1000e;
import com.vmware.vim25.VirtualEthernetCard;
import com.vmware.vim25.VirtualEthernetCardNetworkBackingInfo;
import com.vmware.vim25.VirtualMachineConfigSpec;
import com.vmware.vim25.VirtualVmxnet3;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;
import org.jenkinsci.plugins.vsphere.tools.VSphereException;

/**
 * Carries out a reconfiguration of a VM (what vCenter takes as a {@link VirtualMachineConfigSpec}) on the text of
 * its {@code .vmx} file: the number of CPUs and cores, the memory, the annotation, the name, extra configuration
 * parameters, and changes of the network adapters. Changes of the disks are refused, with a message saying so.
 *
 * <p>Nothing here touches the host: the caller reads the file, has this change it, and writes it back.
 */
final class EsxiReconfigure {

    private static final int NIC_KEY_BASE = 4000;
    private static final int MAX_NICS = 10;

    private static final Pattern MAC = Pattern.compile("[0-9A-Fa-f]{2}(:[0-9A-Fa-f]{2}){5}");
    private static final Pattern EXTRA_KEY = Pattern.compile("[A-Za-z0-9][A-Za-z0-9._:-]*");

    private EsxiReconfigure() {}

    /** Changes the settings as the specification says; fails, with nothing said to be done, if it cannot be. */
    static void apply(VmxFile vmx, VirtualMachineConfigSpec spec) throws VSphereException {
        applyAllocation(vmx, "cpu", spec.getCpuAllocation());
        applyAllocation(vmx, "mem", spec.getMemoryAllocation());
        if (spec.getNumCPUs() != null) {
            if (spec.getNumCPUs() < 1) {
                throw new VSphereException("The number of CPUs has to be at least 1, not " + spec.getNumCPUs());
            }
            vmx.put("numvcpus", spec.getNumCPUs().toString());
        }
        if (spec.getNumCoresPerSocket() != null) {
            if (spec.getNumCoresPerSocket() < 1) {
                throw new VSphereException(
                        "The number of cores per socket has to be at least 1, not " + spec.getNumCoresPerSocket());
            }
            vmx.put("cpuid.coresPerSocket", spec.getNumCoresPerSocket().toString());
        }
        if (spec.getNumCPUs() != null || spec.getNumCoresPerSocket() != null) {
            final int cpus = vmx.getInt("numvcpus", 1);
            final int cores = vmx.getInt("cpuid.coresPerSocket", 1);
            if (cores > 0 && cpus % cores != 0) {
                throw new VSphereException("The number of CPUs (" + cpus
                        + ") has to be a multiple of the cores per socket (" + cores + ")");
            }
        }
        if (spec.getMemoryMB() != null) {
            if (spec.getMemoryMB() < 4) {
                throw new VSphereException("The memory has to be at least 4 MB, not " + spec.getMemoryMB());
            }
            vmx.put("memSize", spec.getMemoryMB().toString());
        }
        if (spec.getAnnotation() != null) {
            if (spec.getAnnotation().isEmpty()) {
                vmx.remove("annotation");
            } else {
                vmx.put("annotation", VmxFile.escape(spec.getAnnotation()));
            }
        }
        if (spec.getName() != null) {
            vmx.put("displayName", VmxFile.escape(checkedName(spec.getName())));
        }
        applyExtraConfig(vmx, spec.getExtraConfig());
        applyDeviceChanges(vmx, spec.getDeviceChange());
    }

    /**
     * Reservation, limit and shares of CPU (in MHz) or memory (in MB), which the .vmx keeps as
     * {@code sched.cpu.min}, {@code .max} and {@code .shares} (and {@code sched.mem...}). A limit of -1 is "unlimited".
     */
    private static void applyAllocation(VmxFile vmx, String what, ResourceAllocationInfo allocation)
            throws VSphereException {
        if (allocation == null) {
            return;
        }
        final String prefix = "sched." + what + ".";
        if (allocation.getReservation() != null) {
            if (allocation.getReservation() < 0) {
                throw new VSphereException("The " + what + " reservation cannot be negative");
            }
            vmx.put(prefix + "min", allocation.getReservation().toString());
        }
        if (allocation.getLimit() != null) {
            if (allocation.getLimit() < -1) {
                throw new VSphereException("The " + what + " limit has to be -1 (unlimited) or more");
            }
            vmx.put(
                    prefix + "max",
                    allocation.getLimit() == -1
                            ? "unlimited"
                            : allocation.getLimit().toString());
        }
        final SharesInfo shares = allocation.getShares();
        if (shares != null && shares.getLevel() != null) {
            switch (shares.getLevel()) {
                case low:
                case normal:
                case high:
                    vmx.put(prefix + "shares", shares.getLevel().toString());
                    break;
                default:
                    if (shares.getShares() < 1) {
                        throw new VSphereException("Custom " + what + " shares have to be at least 1");
                    }
                    vmx.put(prefix + "shares", Integer.toString(shares.getShares()));
            }
        }
    }

    /** What a VM may be called, as for the VMs that are made: nothing that is not plain. */
    static String checkedName(String name) throws VSphereException {
        return EsxiDatastoreFiles.checkName("The name of the VM", name);
    }

    private static void applyExtraConfig(VmxFile vmx, OptionValue[] options) throws VSphereException {
        if (options == null) {
            return;
        }
        for (OptionValue option : options) {
            final String key = option.getKey();
            if (key == null || !EXTRA_KEY.matcher(key).matches()) {
                throw new VSphereException("The extra configuration parameter name \"" + key
                        + "\" cannot be used: it has to be made of letters, digits and . _ : - only");
            }
            final Object value = option.getValue();
            if (value == null || value.toString().isEmpty()) {
                // As with vCenter, an empty value takes the parameter away
                vmx.remove(key);
            } else {
                vmx.put(key, VmxFile.escape(value.toString()));
            }
        }
    }

    private static void applyDeviceChanges(VmxFile vmx, VirtualDeviceConfigSpec[] changes) throws VSphereException {
        if (changes == null) {
            return;
        }
        for (VirtualDeviceConfigSpec change : changes) {
            final VirtualDevice device = change.getDevice();
            if (!(device instanceof VirtualEthernetCard)) {
                throw new VSphereException("Over SSH to an ESXi host only network adapters can be changed, not "
                        + (device == null
                                ? "a device that is not given"
                                : device.getClass().getSimpleName())
                        + " (disks cannot be added, changed or removed this way)");
            }
            final VirtualEthernetCard card = (VirtualEthernetCard) device;
            final VirtualDeviceConfigSpecOperation operation = change.getOperation();
            if (operation == VirtualDeviceConfigSpecOperation.add) {
                addAdapter(vmx, card);
            } else if (operation == VirtualDeviceConfigSpecOperation.edit) {
                editAdapter(vmx, card, indexOf(vmx, card));
            } else if (operation == VirtualDeviceConfigSpecOperation.remove) {
                removeAdapter(vmx, indexOf(vmx, card));
            } else {
                throw new VSphereException("The operation on a network adapter is not given");
            }
        }
    }

    private static int indexOf(VmxFile vmx, VirtualEthernetCard card) throws VSphereException {
        final int index = card.getKey() - NIC_KEY_BASE;
        if (index < 0 || index >= MAX_NICS || !vmx.getBoolean("ethernet" + index + ".present")) {
            throw new VSphereException("There is no network adapter with the key " + card.getKey() + " in the VM");
        }
        return index;
    }

    private static void addAdapter(VmxFile vmx, VirtualEthernetCard card) throws VSphereException {
        int index = 0;
        while (index < MAX_NICS && vmx.hasSettingsUnder("ethernet" + index)) {
            index++;
        }
        if (index >= MAX_NICS) {
            throw new VSphereException("The VM has " + MAX_NICS + " network adapters already, which is all it can");
        }
        final String prefix = "ethernet" + index;
        vmx.put(prefix + ".present", "TRUE");
        vmx.put(prefix + ".virtualDev", virtualDevOf(card));
        vmx.put(prefix + ".startConnected", "TRUE");
        vmx.put(prefix + ".addressType", "generated");
        editAdapter(vmx, card, index);
    }

    private static String virtualDevOf(VirtualEthernetCard card) {
        if (card instanceof VirtualVmxnet3) {
            return "vmxnet3";
        }
        if (card instanceof VirtualE1000e) {
            return "e1000e";
        }
        return "e1000";
    }

    private static void removeAdapter(VmxFile vmx, int index) {
        final String prefix = ("ethernet" + index + ".").toLowerCase();
        final List<String> doomed = new ArrayList<>();
        for (String key : vmx.keys()) {
            if (key.toLowerCase().startsWith(prefix)) {
                doomed.add(key);
            }
        }
        for (String key : doomed) {
            vmx.remove(key);
        }
    }

    private static void editAdapter(VmxFile vmx, VirtualEthernetCard card, int index) throws VSphereException {
        final String prefix = "ethernet" + index;
        final String mac = card.getMacAddress();
        if (mac != null && !mac.isEmpty()) {
            if (!MAC.matcher(mac).matches()) {
                throw new VSphereException("\"" + mac + "\" is not a MAC address (aa:bb:cc:dd:ee:ff)");
            }
            final String type = card.getAddressType() == null ? "manual" : card.getAddressType();
            switch (type) {
                case "assigned":
                    vmx.put(prefix + ".addressType", "vpx");
                    vmx.put(prefix + ".generatedAddress", mac);
                    break;
                case "generated":
                    vmx.put(prefix + ".addressType", "generated");
                    vmx.put(prefix + ".generatedAddress", mac);
                    break;
                default:
                    vmx.put(prefix + ".addressType", "static");
                    vmx.put(prefix + ".address", mac);
            }
        }
        final VirtualDeviceBackingInfo backing = card.getBacking();
        if (backing instanceof VirtualEthernetCardNetworkBackingInfo) {
            final String network = ((VirtualEthernetCardNetworkBackingInfo) backing).getDeviceName();
            if (network != null && !network.isEmpty()) {
                vmx.put(prefix + ".networkName", VmxFile.escape(network));
            }
        } else if (backing != null) {
            throw new VSphereException("A network adapter on a standalone ESXi host can only be connected to a"
                    + " port group of a standard switch, not by "
                    + backing.getClass().getSimpleName());
        }
    }
}
