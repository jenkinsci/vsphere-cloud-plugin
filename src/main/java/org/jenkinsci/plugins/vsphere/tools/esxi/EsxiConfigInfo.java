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

import com.vmware.vim25.Description;
import com.vmware.vim25.ParaVirtualSCSIController;
import com.vmware.vim25.ResourceAllocationInfo;
import com.vmware.vim25.SharesInfo;
import com.vmware.vim25.SharesLevel;
import com.vmware.vim25.VirtualAHCIController;
import com.vmware.vim25.VirtualBusLogicController;
import com.vmware.vim25.VirtualController;
import com.vmware.vim25.VirtualDevice;
import com.vmware.vim25.VirtualDisk;
import com.vmware.vim25.VirtualDiskFlatVer2BackingInfo;
import com.vmware.vim25.VirtualE1000;
import com.vmware.vim25.VirtualEthernetCard;
import com.vmware.vim25.VirtualEthernetCardNetworkBackingInfo;
import com.vmware.vim25.VirtualHardware;
import com.vmware.vim25.VirtualIDEController;
import com.vmware.vim25.VirtualLsiLogicController;
import com.vmware.vim25.VirtualLsiLogicSASController;
import com.vmware.vim25.VirtualMachineConfigInfo;
import com.vmware.vim25.VirtualNVMEController;
import com.vmware.vim25.VirtualPCIController;
import com.vmware.vim25.VirtualSCSIController;
import com.vmware.vim25.VirtualVmxnet3;
import java.util.ArrayList;
import java.util.List;

/**
 * Describes a VM the way vCenter does (a {@link VirtualMachineConfigInfo}), out of the contents of its
 * {@code .vmx} file, so that the code that looks at the hardware of a VM (CPUs, memory, disks, network
 * adapters) does not need to know that the VM is on a host that is not reached through the vSphere API.
 *
 * <p>The devices get the keys and labels that vCenter gives them ("Hard disk 1", "Network adapter 1",
 * keys 2000+, 4000+ and so on), as that is what the code looking for a device goes by.
 */
final class EsxiConfigInfo {

    private static final int PCI_CONTROLLER_KEY = 100;
    private static final int SCSI_CONTROLLER_KEY_BASE = 1000;
    private static final int DISK_KEY_BASE = 2000;
    private static final int NIC_KEY_BASE = 4000;

    private static final int MAX_SCSI_CONTROLLERS = 4;
    private static final int MAX_SCSI_UNITS = 16;
    private static final int MAX_NICS = 10;

    private EsxiConfigInfo() {}

    static VirtualMachineConfigInfo build(VmEntry entry, VmxFile vmx) {
        final VirtualMachineConfigInfo config = new VirtualMachineConfigInfo();
        config.setName(VmxFile.unescape(vmx.get("displayName", entry.getName())));
        config.setTemplate(vmx.getBoolean("template"));
        config.setUuid(vmx.get("uuid.bios"));
        config.setGuestId(vmx.get("guestOS", entry.getGuestOs()));
        config.setVersion(entry.getVersion());
        final String annotation = vmx.get("annotation");
        config.setAnnotation(annotation == null ? entry.getAnnotation() : VmxFile.unescape(annotation));

        config.setCpuAllocation(allocation(vmx, "cpu"));
        config.setMemoryAllocation(allocation(vmx, "mem"));

        final VirtualHardware hardware = new VirtualHardware();
        hardware.setNumCPU(vmx.getInt("numvcpus", 1));
        hardware.setNumCoresPerSocket(vmx.getInt("cpuid.coresPerSocket", 1));
        hardware.setMemoryMB(vmx.getInt("memSize", 4));
        final List<VirtualDevice> devices = new ArrayList<>();
        final VirtualPCIController pci = new VirtualPCIController();
        pci.setKey(PCI_CONTROLLER_KEY);
        pci.setBusNumber(0);
        pci.setDeviceInfo(label("PCI controller 0"));
        devices.add(pci);
        addDisks(devices, entry, vmx);
        addNetworkAdapters(devices, vmx);
        hardware.setDevice(devices.toArray(new VirtualDevice[0]));
        config.setHardware(hardware);
        return config;
    }

    /** The reservation, limit and shares in the .vmx ({@code sched.cpu.min} and so on), or null if it has none. */
    private static ResourceAllocationInfo allocation(VmxFile vmx, String what) {
        final String prefix = "sched." + what + ".";
        final String min = vmx.get(prefix + "min");
        final String max = vmx.get(prefix + "max");
        final String shares = vmx.get(prefix + "shares");
        if (min == null && max == null && shares == null) {
            return null;
        }
        final ResourceAllocationInfo info = new ResourceAllocationInfo();
        info.setReservation(number(min, 0L));
        info.setLimit("unlimited".equalsIgnoreCase(max) ? Long.valueOf(-1) : number(max, -1L));
        final SharesInfo sharesInfo = new SharesInfo();
        if (shares == null) {
            sharesInfo.setLevel(SharesLevel.normal);
        } else if (shares.matches("(?i)low|normal|high")) {
            sharesInfo.setLevel(SharesLevel.valueOf(shares.toLowerCase()));
        } else {
            sharesInfo.setLevel(SharesLevel.custom);
            sharesInfo.setShares((int) number(shares, 0L).longValue());
        }
        info.setShares(sharesInfo);
        return info;
    }

    private static Long number(String text, long ifMissing) {
        try {
            return text == null ? Long.valueOf(ifMissing) : Long.valueOf(text.trim());
        } catch (NumberFormatException e) {
            return Long.valueOf(ifMissing);
        }
    }

    /** "[datastore] path" for a disk file the .vmx names relative to the folder of the VM or by its full path. */
    private static String backingName(VmEntry entry, String folder, String fileName) {
        final String volumes = "/vmfs/volumes/";
        if (fileName.startsWith(volumes) && fileName.indexOf('/', volumes.length()) > 0) {
            final int slash = fileName.indexOf('/', volumes.length());
            return "[" + fileName.substring(volumes.length(), slash) + "] " + fileName.substring(slash + 1);
        }
        return "[" + entry.getDatastore() + "] " + folder + fileName;
    }

    /** The place in the file system of a disk file as {@link #backingName} gave it; folder is that of the VM. */
    static String pathOf(String backingName, String vmFolderPath) {
        final int end = backingName.indexOf(']');
        if (backingName.startsWith("[") && end > 0) {
            return "/vmfs/volumes/" + backingName.substring(1, end) + "/"
                    + backingName.substring(end + 1).trim();
        }
        return vmFolderPath + "/" + backingName;
    }

    private static VirtualSCSIController controllerOf(String virtualDev) {
        switch (virtualDev == null ? "" : virtualDev.toLowerCase()) {
            case "pvscsi":
                return new ParaVirtualSCSIController();
            case "lsisas1068":
                return new VirtualLsiLogicSASController();
            case "buslogic":
                return new VirtualBusLogicController();
            default:
                return new VirtualLsiLogicController();
        }
    }

    private static VirtualController controllerFor(EsxiDiskBus kind, VmxFile vmx, int bus) {
        switch (kind) {
            case SCSI:
                return controllerOf(vmx.get("scsi" + bus + ".virtualDev"));
            case IDE:
                return new VirtualIDEController();
            case SATA:
                return new VirtualAHCIController();
            default:
                return new VirtualNVMEController();
        }
    }

    /**
     * The controllers of the VM and the disks on them, on all the kinds of bus: the SCSI, SATA and NVMe controllers
     * that its .vmx says it has, and the two IDE ones that it always has.
     */
    private static void addDisks(List<VirtualDevice> devices, VmEntry entry, VmxFile vmx) {
        final String folder = entry.getVmxRelativePath().contains("/")
                ? entry.getVmxRelativePath()
                        .substring(0, entry.getVmxRelativePath().lastIndexOf('/') + 1)
                : "";
        int diskNumber = 0;
        for (EsxiDiskBus kind : EsxiDiskBus.values()) {
            for (int bus = 0; bus < kind.maxControllers; bus++) {
                if (!kind.isPresent(vmx, bus)) {
                    continue;
                }
                final VirtualController controller = controllerFor(kind, vmx, bus);
                controller.setKey(kind.controllerKey(bus));
                controller.setBusNumber(bus);
                controller.setDeviceInfo(label(kind.label + bus));
                devices.add(controller);
                final List<Integer> diskKeys = new ArrayList<>();
                for (int unit = 0; unit < kind.maxUnits; unit++) {
                    final String diskPrefix = new EsxiDiskBus.Place(kind, bus, unit).diskPrefix();
                    final String fileName = vmx.get(diskPrefix + ".fileName");
                    if (!vmx.getBoolean(diskPrefix + ".present")
                            || fileName == null
                            || !fileName.endsWith(".vmdk")
                            || vmx.get(diskPrefix + ".deviceType", "")
                                    .toLowerCase()
                                    .contains("cdrom")) {
                        continue;
                    }
                    diskNumber++;
                    final VirtualDisk disk = new VirtualDisk();
                    disk.setKey(kind.diskKey(bus, unit));
                    disk.setControllerKey(controller.getKey());
                    disk.setUnitNumber(unit);
                    disk.setDeviceInfo(label("Hard disk " + diskNumber));
                    final VirtualDiskFlatVer2BackingInfo backing = new VirtualDiskFlatVer2BackingInfo();
                    backing.setFileName(backingName(entry, folder, VmxFile.unescape(fileName)));
                    disk.setBacking(backing);
                    devices.add(disk);
                    diskKeys.add(disk.getKey());
                    controller.setDevice(
                            diskKeys.stream().mapToInt(Integer::intValue).toArray());
                }
            }
        }
    }

    private static void addNetworkAdapters(List<VirtualDevice> devices, VmxFile vmx) {
        int adapterNumber = 0;
        for (int n = 0; n < MAX_NICS; n++) {
            final String prefix = "ethernet" + n;
            if (!vmx.getBoolean(prefix + ".present")) {
                continue;
            }
            adapterNumber++;
            final VirtualEthernetCard nic = "vmxnet3".equalsIgnoreCase(vmx.get(prefix + ".virtualDev"))
                    ? new VirtualVmxnet3()
                    : new VirtualE1000();
            nic.setKey(NIC_KEY_BASE + n);
            nic.setDeviceInfo(label("Network adapter " + adapterNumber));
            final String address = vmx.get(prefix + ".address", vmx.get(prefix + ".generatedAddress"));
            nic.setMacAddress(address);
            nic.setAddressType(apiAddressType(vmx.get(prefix + ".addressType")));
            final VirtualEthernetCardNetworkBackingInfo backing = new VirtualEthernetCardNetworkBackingInfo();
            backing.setDeviceName(vmx.get(prefix + ".networkName"));
            nic.setBacking(backing);
            devices.add(nic);
        }
    }

    /** The address types are called differently in the .vmx file and in the API. */
    static String apiAddressType(String vmxAddressType) {
        if (vmxAddressType == null) {
            return "generated";
        }
        switch (vmxAddressType.toLowerCase()) {
            case "static":
                return "manual";
            case "vpx":
                return "assigned";
            default:
                return "generated";
        }
    }

    private static Description label(String text) {
        final Description description = new Description();
        description.setLabel(text);
        description.setSummary(text);
        return description;
    }
}
