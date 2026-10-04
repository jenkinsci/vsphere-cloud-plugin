package org.jenkinsci.plugins.vsphere.tools.esxi;

import com.vmware.vim25.Description;
import com.vmware.vim25.VirtualDevice;
import com.vmware.vim25.VirtualDisk;
import com.vmware.vim25.VirtualDiskFlatVer2BackingInfo;
import com.vmware.vim25.VirtualE1000;
import com.vmware.vim25.VirtualEthernetCard;
import com.vmware.vim25.VirtualEthernetCardNetworkBackingInfo;
import com.vmware.vim25.VirtualHardware;
import com.vmware.vim25.VirtualLsiLogicController;
import com.vmware.vim25.VirtualMachineConfigInfo;
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

    private static final int SCSI_CONTROLLER_KEY_BASE = 1000;
    private static final int DISK_KEY_BASE = 2000;
    private static final int NIC_KEY_BASE = 4000;

    private static final int MAX_SCSI_CONTROLLERS = 4;
    private static final int MAX_SCSI_UNITS = 16;
    private static final int MAX_NICS = 10;

    private EsxiConfigInfo() {}

    static VirtualMachineConfigInfo build(VmEntry entry, VmxFile vmx) {
        final VirtualMachineConfigInfo config = new VirtualMachineConfigInfo();
        config.setName(vmx.get("displayName", entry.getName()));
        config.setTemplate(false);
        config.setUuid(vmx.get("uuid.bios"));
        config.setGuestId(vmx.get("guestOS", entry.getGuestOs()));
        config.setVersion(entry.getVersion());
        config.setAnnotation(vmx.get("annotation", entry.getAnnotation()));

        final VirtualHardware hardware = new VirtualHardware();
        hardware.setNumCPU(vmx.getInt("numvcpus", 1));
        hardware.setNumCoresPerSocket(vmx.getInt("cpuid.coresPerSocket", 1));
        hardware.setMemoryMB(vmx.getInt("memSize", 4));
        final List<VirtualDevice> devices = new ArrayList<>();
        addDisks(devices, entry, vmx);
        addNetworkAdapters(devices, vmx);
        hardware.setDevice(devices.toArray(new VirtualDevice[0]));
        config.setHardware(hardware);
        return config;
    }

    private static void addDisks(List<VirtualDevice> devices, VmEntry entry, VmxFile vmx) {
        final String folder = entry.getVmxRelativePath().contains("/")
                ? entry.getVmxRelativePath()
                        .substring(0, entry.getVmxRelativePath().lastIndexOf('/') + 1)
                : "";
        int diskNumber = 0;
        for (int bus = 0; bus < MAX_SCSI_CONTROLLERS; bus++) {
            final String controllerPrefix = "scsi" + bus;
            if (!vmx.getBoolean(controllerPrefix + ".present")) {
                continue;
            }
            final VirtualSCSIController controller = new VirtualLsiLogicController();
            controller.setKey(SCSI_CONTROLLER_KEY_BASE + bus);
            controller.setBusNumber(bus);
            controller.setDeviceInfo(label("SCSI controller " + bus));
            devices.add(controller);
            for (int unit = 0; unit < MAX_SCSI_UNITS; unit++) {
                final String diskPrefix = controllerPrefix + ":" + unit;
                final String fileName = vmx.get(diskPrefix + ".fileName");
                if (!vmx.getBoolean(diskPrefix + ".present") || fileName == null || !fileName.endsWith(".vmdk")) {
                    continue;
                }
                diskNumber++;
                final VirtualDisk disk = new VirtualDisk();
                disk.setKey(DISK_KEY_BASE + bus * MAX_SCSI_UNITS + unit);
                disk.setControllerKey(controller.getKey());
                disk.setUnitNumber(unit);
                disk.setDeviceInfo(label("Hard disk " + diskNumber));
                final VirtualDiskFlatVer2BackingInfo backing = new VirtualDiskFlatVer2BackingInfo();
                backing.setFileName("[" + entry.getDatastore() + "] " + folder + fileName);
                disk.setBacking(backing);
                devices.add(disk);
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
