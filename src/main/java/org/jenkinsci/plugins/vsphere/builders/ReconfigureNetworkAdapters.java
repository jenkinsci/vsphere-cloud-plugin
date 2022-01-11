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

import com.vmware.vim25.*;
import com.vmware.vim25.mo.DistributedVirtualPortgroup;
import com.vmware.vim25.mo.DistributedVirtualSwitch;
import com.vmware.vim25.mo.Network;
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
import java.util.Arrays;

public class ReconfigureNetworkAdapters extends ReconfigureStep {

    private final DeviceAction deviceAction;
    private final String deviceLabel;
    private final String macAddress;
    private final boolean standardSwitch;
    private final String portGroup;
    private final boolean distributedSwitch;
    private final String distributedPortGroup;
    private final String distributedPortId;

    @DataBoundConstructor
    public ReconfigureNetworkAdapters(DeviceAction deviceAction, String deviceLabel, String macAddress,
            boolean standardSwitch,String portGroup, boolean distributedSwitch,
            String distributedPortGroup, String distributedPortId) throws VSphereException {
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
    public void perform(@NonNull Run<?, ?> run, @NonNull FilePath filePath, @NonNull Launcher launcher, @NonNull TaskListener listener) throws InterruptedException, IOException {
        try {
            reconfigureNetwork(run, launcher, listener);
        } catch (Exception e) {
            throw new AbortException(e.getMessage());
        }
    }

    @Override
    public boolean perform(final AbstractBuild<?, ?> build, final Launcher launcher, final BuildListener listener)  {
        boolean retVal = false;
        try {
            retVal = reconfigureNetwork(build, launcher, listener);
        } catch (Exception e) {
            e.printStackTrace();
        }
        return retVal;
        //TODO throw AbortException instead of returning value
    }

    public boolean reconfigureNetwork(final Run<?, ?> run, final Launcher launcher, final TaskListener listener) throws VSphereException  {
        PrintStream jLogger = listener.getLogger();
        String expandedDeviceLabel = deviceLabel;
        String expandedMacAddress = macAddress;
        String expandedPortGroup = portGroup;
        String expandedDistributedPortGroup = distributedPortGroup;
        String expandedDistributedPortId = distributedPortId;
        EnvVars env;
        try {
            env = run.getEnvironment(listener);
        } catch (Exception e) {
            throw new VSphereException(e);
        }
        if (run instanceof AbstractBuild) {
            env.overrideAll(((AbstractBuild) run).getBuildVariables()); // Add in matrix axes..
            expandedDeviceLabel = env.expand(deviceLabel);
            expandedMacAddress = env.expand(macAddress);
            expandedPortGroup = env.expand(portGroup);
            expandedDistributedPortGroup = env.expand(distributedPortGroup);
            expandedDistributedPortId = env.expand(distributedPortId);
        }
        VSphereLogger.vsLogger(jLogger, "Preparing reconfigure: "+ deviceAction.getLabel() +" Network Adapter \"" + expandedDeviceLabel + "\"");
        VirtualEthernetCard vEth = null;
        if (deviceAction == DeviceAction.ADD) {
            vEth = new VirtualE1000();
            vEth.setBacking(new VirtualEthernetCardNetworkBackingInfo());
            Description description = vEth.getDeviceInfo();
            if (description == null) {
                description = new Description();
            }
            description.setLabel(expandedDeviceLabel);
            vEth.setDeviceInfo(description);
        } else {
            vEth = findNetworkDeviceByLabel(vm.getConfig().getHardware().getDevice(), expandedDeviceLabel);
        }

        if (vEth == null) {
            throw new VSphereException("Could not find network device named " + expandedDeviceLabel);
        }

        // change mac address
        if (!expandedMacAddress.isEmpty()) {
            VSphereLogger.vsLogger(jLogger, "Reconfiguring MAC Address -> " + expandedMacAddress);
            vEth.setMacAddress(expandedMacAddress);
        }

        // extract backing from ethernet virtual card, always available
        VirtualDeviceBackingInfo virtualDeviceBackingInfo = vEth.getBacking();

        // change our port group
        if (standardSwitch && !expandedPortGroup.isEmpty()) {
            VSphereLogger.vsLogger(jLogger, "Reconfiguring Network Port Group -> " + expandedPortGroup);

            if (virtualDeviceBackingInfo instanceof VirtualEthernetCardNetworkBackingInfo) {
                VirtualEthernetCardNetworkBackingInfo backing = (VirtualEthernetCardNetworkBackingInfo) virtualDeviceBackingInfo;
    
                Network networkPortGroup = getVsphere().getNetworkPortGroupByName(getVM(), expandedPortGroup);
                if (networkPortGroup != null) {
                    backing.deviceName = expandedPortGroup;
                }
                else {
                    VSphereLogger.vsLogger(jLogger, "Failed to find Network for Port Group -> " + expandedPortGroup);
                }
            }
            else {
                VSphereLogger.vsLogger(jLogger, "Network Device -> " + expandedDeviceLabel + " isn't standard switch");
            }
        }
        // change out distributed switch port group
        else if (distributedSwitch && !expandedDistributedPortGroup.isEmpty()) {
        VSphereLogger.vsLogger(jLogger, "Reconfiguring Distributed Switch Port Group -> " + expandedDistributedPortGroup +
                   " Port Id -> " + expandedDistributedPortId);

            if (virtualDeviceBackingInfo instanceof VirtualEthernetCardDistributedVirtualPortBackingInfo) {
          
                VirtualEthernetCardDistributedVirtualPortBackingInfo virtualEthernetCardDistributedVirtualPortBackingInfo =
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
          
                    virtualEthernetCardDistributedVirtualPortBackingInfo.setPort(distributedVirtualSwitchPortConnection);
          
                    VSphereLogger.vsLogger(jLogger, "Distributed Switch Port Group -> " + expandedDistributedPortGroup +
                            "Port Id -> " + expandedDistributedPortId + " successfully configured!");
                }
                else {
                    VSphereLogger.vsLogger(jLogger, "Failed to find Distributed Virtual Portgroup for Port Group -> " +
                       expandedDistributedPortGroup);
                }
            }
            else {
                VSphereLogger.vsLogger(jLogger, "Network Device -> " + expandedDeviceLabel + " isn't distributed switch");
            }
        }

        VirtualDeviceConfigSpec vdspec = new VirtualDeviceConfigSpec();

        vdspec.setDevice(vEth);
        if (deviceAction == DeviceAction.EDIT) {
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
        deviceConfigSpecs[deviceConfigSpecs.length-1] = vdspec;
        spec.setDeviceChange(deviceConfigSpecs);

        VSphereLogger.vsLogger(jLogger, "Finished!");
        return true;
    }

    private VirtualEthernetCard findNetworkDeviceByLabel(VirtualDevice[] devices, String label) {
        for (VirtualDevice vd : devices) {
            if (vd instanceof VirtualEthernetCard && (label.isEmpty() || vd.getDeviceInfo().getLabel().contentEquals(label))) {
                return (VirtualEthernetCard) vd;
            }
        }
        return null;
    }

    @Extension
    public static final class ReconfigureNetworkAdaptersDescriptor extends ReconfigureStepDescriptor {

        public ReconfigureNetworkAdaptersDescriptor() {
            load();
        }
    
        public FormValidation doCheckMacAddress(@QueryParameter String value)
                throws IOException, ServletException {
            if (value.length() == 0)
                return FormValidation.error(Messages.validation_required("the MAC Address"));
            return FormValidation.ok();
        }
    
        @Override
        public String getDisplayName() {
            return Messages.vm_title_ReconfigureNetworkAdapter();
        }
    
        public FormValidation doTestData(@QueryParameter DeviceAction deviceAction, @QueryParameter String deviceLabel,
                @QueryParameter String macAddress, @QueryParameter boolean standardSwitch,
                @QueryParameter String portGroup, @QueryParameter boolean distributedSwitch,
                @QueryParameter String distributedPortGroup, @QueryParameter String distributedPortId) {
            try {
                if (standardSwitch && distributedSwitch) {
                    return FormValidation.error(Messages.validation_wrongSwitchSelection());
                }
                return doCheckMacAddress(macAddress);
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        }
    }
}
