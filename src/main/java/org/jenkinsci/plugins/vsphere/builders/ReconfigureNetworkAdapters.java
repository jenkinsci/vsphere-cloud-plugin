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
package org.jenkinsci.plugins.vsphere.builders;

import static org.jenkinsci.plugins.vsphere.tools.PermissionUtils.throwUnlessUserHasPermissionToConfigureJob;

import com.vmware.vim25.*;
import com.vmware.vim25.mo.DistributedVirtualPortgroup;
import com.vmware.vim25.mo.DistributedVirtualSwitch;
import com.vmware.vim25.mo.Network;
import edu.umd.cs.findbugs.annotations.NonNull;
import hudson.*;
import hudson.Extension;
import hudson.model.Item;
import hudson.model.Run;
import hudson.model.TaskListener;
import hudson.util.FormValidation;
import jakarta.servlet.ServletException;
import java.io.IOException;
import java.io.PrintStream;
import java.util.Arrays;
import org.jenkinsci.plugins.vsphere.tools.MacAddresses;
import org.jenkinsci.plugins.vsphere.tools.VSphereException;
import org.jenkinsci.plugins.vsphere.tools.VSphereLogger;
import org.kohsuke.stapler.AncestorInPath;
import org.kohsuke.stapler.DataBoundConstructor;
import org.kohsuke.stapler.DataBoundSetter;
import org.kohsuke.stapler.QueryParameter;
import org.kohsuke.stapler.interceptor.RequirePOST;

public class ReconfigureNetworkAdapters extends ReconfigureStep {

    private final DeviceAction deviceAction;
    private final String deviceLabel;
    private String deviceNumber;
    private final String macAddress;
    private final boolean standardSwitch;
    private final String portGroup;
    private final boolean distributedSwitch;
    private final String distributedPortGroup;
    private final String distributedPortId;

    @DataBoundConstructor
    public ReconfigureNetworkAdapters(
            DeviceAction deviceAction,
            String deviceLabel,
            String macAddress,
            boolean standardSwitch,
            String portGroup,
            boolean distributedSwitch,
            String distributedPortGroup,
            String distributedPortId)
            throws VSphereException {
        this.deviceAction = deviceAction;
        this.deviceLabel = deviceLabel;
        this.macAddress = macAddress;
        this.standardSwitch = standardSwitch;
        this.portGroup = standardSwitch ? portGroup : null;
        this.distributedSwitch = distributedSwitch;
        this.distributedPortGroup = distributedSwitch ? distributedPortGroup : null;
        this.distributedPortId = distributedSwitch ? distributedPortId : null;
    }

    public DeviceAction getDeviceAction() {
        return deviceAction;
    }

    public String getDeviceLabel() {
        return deviceLabel;
    }

    public String getDeviceNumber() {
        return deviceNumber;
    }

    @DataBoundSetter
    public void setDeviceNumber(String deviceNumber) {
        this.deviceNumber = deviceNumber;
    }

    public String getMacAddress() {
        return macAddress;
    }

    public boolean isStandardSwitch() {
        return standardSwitch;
    }

    public boolean isDistributedSwitch() {
        return distributedSwitch;
    }

    public String getPortGroup() {
        return portGroup;
    }

    public String getDistributedPortGroup() {
        return distributedPortGroup;
    }

    public String getDistributedPortId() {
        return distributedPortId;
    }

    @Override
    public void perform(@NonNull EnvVars env, @NonNull TaskListener listener) throws VSphereException {
        reconfigureNetwork(env, listener);
    }

    @Override
    public void perform(
            @NonNull Run<?, ?> run,
            @NonNull FilePath filePath,
            @NonNull Launcher launcher,
            @NonNull TaskListener listener)
            throws InterruptedException, IOException {
        try {
            reconfigureNetwork(run, launcher, listener);
        } catch (Exception e) {
            throw new AbortException(e.getMessage());
        }
    }

    public boolean reconfigureNetwork(final Run<?, ?> run, final Launcher launcher, final TaskListener listener)
            throws VSphereException {
        EnvVars env = extractEnvironment(run, listener);

        return reconfigureNetwork(env, listener);
    }

    /**
     * Gives the adapter the requested MAC address and marks it as user-supplied. Without
     * {@code addressType = "manual"} vCenter keeps treating the adapter as "generated"/"assigned", and then
     * refuses the address with "is not a valid VPX-assigned Ethernet address" (JENKINS-34001).
     */
    static void applyMacAddress(final VirtualEthernetCard vEth, final String macAddress) {
        applyMacAddress(vEth, macAddress, true);
    }

    /**
     * @param manual false for the retry variant: the address is then declared as one that vCenter "assigned",
     *               which is what it takes for addresses from the range that vCenter manages itself.
     */
    static void applyMacAddress(final VirtualEthernetCard vEth, final String macAddress, final boolean manual) {
        vEth.setMacAddress(macAddress);
        vEth.setAddressType(manual ? "manual" : "assigned");
    }

    private boolean reconfigureNetwork(final EnvVars env, final TaskListener listener) throws VSphereException {
        PrintStream jLogger = listener.getLogger();
        String expandedDeviceLabel = env.expand(deviceLabel);
        String expandedDeviceNumber = deviceNumber == null ? null : env.expand(deviceNumber);
        String expandedMacAddress = env.expand(macAddress);
        String expandedPortGroup = env.expand(portGroup);
        String expandedDistributedPortGroup = env.expand(distributedPortGroup);
        String expandedDistributedPortId = env.expand(distributedPortId);

        boolean hasLabel = expandedDeviceLabel != null && !expandedDeviceLabel.isEmpty();
        boolean hasNumber = expandedDeviceNumber != null && !expandedDeviceNumber.isEmpty();
        if (hasLabel && hasNumber) {
            throw new VSphereException("Specify either deviceLabel or deviceNumber, not both");
        }

        VSphereLogger.vsLogger(
                jLogger,
                "Preparing reconfigure: " + deviceAction.getLabel() + " Network Adapter "
                        + (hasNumber ? ("#" + expandedDeviceNumber) : ("\"" + expandedDeviceLabel + "\"")));
        VirtualEthernetCard vEth = null;
        if (deviceAction == DeviceAction.ADD) {
            if (hasNumber) {
                throw new VSphereException(
                        "deviceNumber is not supported for the Add action; use deviceLabel to name the new adapter");
            }
            vEth = new VirtualE1000();
            vEth.setBacking(new VirtualEthernetCardNetworkBackingInfo());
            Description description = vEth.getDeviceInfo();
            if (description == null) {
                description = new Description();
            }
            description.setLabel(expandedDeviceLabel);
            vEth.setDeviceInfo(description);
        } else if (hasNumber) {
            vEth = findNetworkDeviceByIndex(
                    vm.getConfig().getHardware().getDevice(), Integer.parseInt(expandedDeviceNumber));
        } else {
            vEth = findNetworkDeviceByLabel(vm.getConfig().getHardware().getDevice(), expandedDeviceLabel);
        }

        if (vEth == null) {
            throw new VSphereException("Could not find network device named " + expandedDeviceLabel);
        }

        // change mac address
        if (!expandedMacAddress.isEmpty()) {
            VSphereLogger.vsLogger(jLogger, "Reconfiguring MAC Address -> " + expandedMacAddress);
            applyMacAddress(vEth, expandedMacAddress, !nonManualMac);
        }

        // extract backing from ethernet virtual card, always available
        VirtualDeviceBackingInfo virtualDeviceBackingInfo = vEth.getBacking();

        // change our port group
        if (standardSwitch && !expandedPortGroup.isEmpty()) {
            VSphereLogger.vsLogger(jLogger, "Reconfiguring Network Port Group -> " + expandedPortGroup);

            if (virtualDeviceBackingInfo instanceof VirtualEthernetCardNetworkBackingInfo) {
                VirtualEthernetCardNetworkBackingInfo backing =
                        (VirtualEthernetCardNetworkBackingInfo) virtualDeviceBackingInfo;

                Network networkPortGroup = getVsphere().getNetworkPortGroupByName(getVM(), expandedPortGroup);
                if (networkPortGroup != null) {
                    backing.deviceName = expandedPortGroup;
                } else {
                    VSphereLogger.vsLogger(jLogger, "Failed to find Network for Port Group -> " + expandedPortGroup);
                }
            } else {
                VSphereLogger.vsLogger(jLogger, "Network Device -> " + expandedDeviceLabel + " isn't standard switch");
            }
        }
        // change out distributed switch port group
        else if (distributedSwitch && !expandedDistributedPortGroup.isEmpty()) {
            VSphereLogger.vsLogger(
                    jLogger,
                    "Reconfiguring Distributed Switch Port Group -> " + expandedDistributedPortGroup + " Port Id -> "
                            + expandedDistributedPortId);

            if (virtualDeviceBackingInfo instanceof VirtualEthernetCardDistributedVirtualPortBackingInfo) {

                VirtualEthernetCardDistributedVirtualPortBackingInfo
                        virtualEthernetCardDistributedVirtualPortBackingInfo =
                                (VirtualEthernetCardDistributedVirtualPortBackingInfo) virtualDeviceBackingInfo;

                DistributedVirtualPortgroup distributedVirtualPortgroup =
                        getVsphere().getDistributedVirtualPortGroupByName(getVM(), expandedDistributedPortGroup);

                if (distributedVirtualPortgroup != null) {
                    DistributedVirtualSwitch distributedVirtualSwitch =
                            getVsphere().getDistributedVirtualSwitchByPortGroup(distributedVirtualPortgroup);

                    DistributedVirtualSwitchPortConnection distributedVirtualSwitchPortConnection =
                            new DistributedVirtualSwitchPortConnection();

                    distributedVirtualSwitchPortConnection.setSwitchUuid(distributedVirtualSwitch.getUuid());
                    distributedVirtualSwitchPortConnection.setPortgroupKey(distributedVirtualPortgroup.getKey());
                    distributedVirtualSwitchPortConnection.setPortKey(expandedDistributedPortId);

                    virtualEthernetCardDistributedVirtualPortBackingInfo.setPort(
                            distributedVirtualSwitchPortConnection);

                    VSphereLogger.vsLogger(
                            jLogger,
                            "Distributed Switch Port Group -> " + expandedDistributedPortGroup + "Port Id -> "
                                    + expandedDistributedPortId + " successfully configured!");
                } else {
                    VSphereLogger.vsLogger(
                            jLogger,
                            "Failed to find Distributed Virtual Portgroup for Port Group -> "
                                    + expandedDistributedPortGroup);
                }
            } else {
                VSphereLogger.vsLogger(
                        jLogger, "Network Device -> " + expandedDeviceLabel + " isn't distributed switch");
            }
        }

        VirtualDeviceConfigSpec vdspec = new VirtualDeviceConfigSpec();

        vdspec.setDevice(vEth);
        if (deviceAction == DeviceAction.ADD) {
            vdspec.setOperation(VirtualDeviceConfigSpecOperation.add);
        } else if (deviceAction == DeviceAction.EDIT) {
            vdspec.setOperation(VirtualDeviceConfigSpecOperation.edit);
        } else if (deviceAction == DeviceAction.REMOVE) {
            vdspec.setOperation(VirtualDeviceConfigSpecOperation.remove);
        }

        // add change into config spec
        VirtualDeviceConfigSpec[] deviceConfigSpecs = spec.getDeviceChange();
        if (deviceConfigSpecs == null) {
            deviceConfigSpecs = new VirtualDeviceConfigSpec[1];
        } else {
            deviceConfigSpecs = Arrays.copyOf(deviceConfigSpecs, deviceConfigSpecs.length + 1);
        }
        deviceConfigSpecs[deviceConfigSpecs.length - 1] = vdspec;
        spec.setDeviceChange(deviceConfigSpecs);

        VSphereLogger.vsLogger(jLogger, "Finished!");
        return true;
    }

    VirtualEthernetCard findNetworkDeviceByLabel(VirtualDevice[] devices, String label) {
        for (VirtualDevice vd : devices) {
            if (vd instanceof VirtualEthernetCard
                    && (label.isEmpty() || vd.getDeviceInfo().getLabel().contentEquals(label))) {
                return (VirtualEthernetCard) vd;
            }
        }
        return null;
    }

    /**
     * Finds the Nth network adapter, ONE-based (so deviceNumber=1 is the first adapter, matching how
     * vSphere itself numbers things in its UI, e.g. "Network adapter 1"), counting only
     * VirtualEthernetCard entries in the same order vCenter itself returns them via
     * VirtualHardware.device -- no re-sorting or address scheme of our own (raw PCI unit number is
     * shared with unrelated PCI-bus devices like storage/USB/video controllers, so it doesn't
     * correspond to "the Nth NIC"), just vCenter's own list order.
     */
    VirtualEthernetCard findNetworkDeviceByIndex(VirtualDevice[] devices, int number) throws VSphereException {
        int count = 0;
        for (VirtualDevice vd : devices) {
            if (!(vd instanceof VirtualEthernetCard)) {
                continue;
            }
            count++;
            if (count == number) {
                return (VirtualEthernetCard) vd;
            }
        }
        throw new VSphereException(String.format(
                "VM has %d network adapters; no adapter with deviceNumber %d (deviceNumber is one-based)",
                count, number));
    }

    /**
     * Tells the user up front when vCenter is going to refuse a (manual) MAC address, and why (JENKINS-34001).
     * These are warnings rather than errors as the allocation rules vary a little between vSphere versions.
     */
    static FormValidation macAddressWarnings(final String value) {
        if (value.contains("$")) {
            return FormValidation.ok(); // known only once the variables are expanded in a build
        }
        switch (MacAddresses.classify(value)) {
            case MALFORMED:
                return FormValidation.warning(Messages.validation_macAddress_malformed());
            case VMWARE_VPX_GENERATED:
                return FormValidation.warning(Messages.validation_macAddress_vpxRange());
            case VMWARE_RESERVED:
                return FormValidation.warning(Messages.validation_macAddress_vmwareReserved());
            case HOST_GENERATED:
                return FormValidation.warning(Messages.validation_macAddress_hostGenerated());
            default:
                return FormValidation.ok();
        }
    }

    /** Set when this is the retry variant of a step, see {@link #fallbackVariant()}. */
    private transient boolean nonManualMac;

    /**
     * The MAC address is normally set as a manual one. If vCenter refuses that (e.g. because the address is
     * in the range that it hands out itself), the reconfiguration is retried once with a step that does not
     * claim the address to be manual.
     */
    @Override
    public ReconfigureStep fallbackVariant() {
        if (nonManualMac || macAddress == null || macAddress.isEmpty()) {
            return null;
        }
        try {
            final ReconfigureNetworkAdapters copy = new ReconfigureNetworkAdapters(
                    deviceAction,
                    deviceLabel,
                    macAddress,
                    standardSwitch,
                    portGroup,
                    distributedSwitch,
                    distributedPortGroup,
                    distributedPortId);
            copy.setDeviceNumber(deviceNumber);
            copy.nonManualMac = true;
            return copy;
        } catch (VSphereException e) {
            return null;
        }
    }

    @Extension
    public static final class ReconfigureNetworkAdaptersDescriptor extends ReconfigureStepDescriptor {

        public ReconfigureNetworkAdaptersDescriptor() {
            load();
        }

        @RequirePOST
        public FormValidation doCheckMacAddress(@AncestorInPath Item context, @QueryParameter String value)
                throws IOException, ServletException {
            throwUnlessUserHasPermissionToConfigureJob(context);
            if (value.length() == 0) return FormValidation.error(Messages.validation_required("the MAC Address"));
            return macAddressWarnings(value);
        }

        @RequirePOST
        public FormValidation doCheckDeviceNumber(@AncestorInPath Item context, @QueryParameter String value)
                throws IOException, ServletException {
            throwUnlessUserHasPermissionToConfigureJob(context);
            if (value == null || value.isEmpty()) {
                return FormValidation.ok();
            }
            try {
                if (Integer.parseInt(value) < 1) {
                    return FormValidation.error(Messages.validation_positiveInteger(value));
                }
            } catch (NumberFormatException e) {
                return FormValidation.error(Messages.validation_positiveInteger(value));
            }
            return FormValidation.ok();
        }

        @Override
        public String getDisplayName() {
            return Messages.vm_title_ReconfigureNetworkAdapter();
        }

        @RequirePOST
        public FormValidation doTestData(
                @AncestorInPath Item context,
                @QueryParameter DeviceAction deviceAction,
                @QueryParameter String deviceLabel,
                @QueryParameter String deviceNumber,
                @QueryParameter String macAddress,
                @QueryParameter boolean standardSwitch,
                @QueryParameter String portGroup,
                @QueryParameter boolean distributedSwitch,
                @QueryParameter String distributedPortGroup,
                @QueryParameter String distributedPortId) {
            throwUnlessUserHasPermissionToConfigureJob(context);
            try {
                if (standardSwitch && distributedSwitch) {
                    return FormValidation.error(Messages.validation_wrongSwitchSelection());
                }
                if (deviceLabel != null && !deviceLabel.isEmpty() && deviceNumber != null && !deviceNumber.isEmpty()) {
                    return FormValidation.error("Specify either Device Label or Device Number, not both");
                }
                return doCheckMacAddress(context, macAddress);
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        }
    }
}
