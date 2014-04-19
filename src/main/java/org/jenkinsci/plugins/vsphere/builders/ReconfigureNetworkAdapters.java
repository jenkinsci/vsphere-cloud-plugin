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
import hudson.EnvVars;
import hudson.Extension;
import hudson.Launcher;
import hudson.model.AbstractBuild;
import hudson.model.BuildListener;
import hudson.util.FormValidation;
import org.jenkinsci.plugins.vsphere.tools.VSphereException;
import org.jenkinsci.plugins.vsphere.tools.VSphereLogger;
import org.kohsuke.stapler.DataBoundConstructor;
import org.kohsuke.stapler.QueryParameter;

import javax.servlet.ServletException;
import java.io.IOException;
import java.io.PrintStream;
import java.util.Arrays;

public class ReconfigureNetworkAdapters extends ReconfigureStep {

    private final DeviceAction deviceAction;
    private final String deviceLabel;
    private final String macAddress;
    private final String portGroup;

	@DataBoundConstructor
	public ReconfigureNetworkAdapters(DeviceAction deviceAction, String deviceLabel, String macAddress, String portGroup) throws VSphereException {
		this.deviceAction = deviceAction;
        this.deviceLabel = deviceLabel;
        this.macAddress = macAddress;
        this.portGroup = portGroup;
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

    public String getPortGroup() {
        return portGroup;
    }

	public boolean perform(final AbstractBuild<?, ?> build, final Launcher launcher, final BuildListener listener) throws VSphereException  {

        PrintStream jLogger = listener.getLogger();
        EnvVars env;
        try {
            env = build.getEnvironment(listener);
        } catch (Exception e) {
            throw new VSphereException(e);
        }
        env.overrideAll(build.getBuildVariables());
        String expandedDeviceLabel = env.expand(deviceLabel);
        String expandedMacAddress = env.expand(macAddress);
        String expandedPortGroup = env.expand(portGroup);

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

        // change our port group
        if (!expandedPortGroup.isEmpty()) {
            VSphereLogger.vsLogger(jLogger, "Reconfiguring Network Port Group -> " + expandedPortGroup);
            VirtualEthernetCardNetworkBackingInfo backing = (VirtualEthernetCardNetworkBackingInfo) vEth.getBacking();
            backing.deviceName = expandedPortGroup;
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
				@QueryParameter String macAddress, @QueryParameter String portGroup) {
			try {
				return doCheckMacAddress(macAddress);
			} catch (Exception e) {
				throw new RuntimeException(e);
			}
		}
	}
}
