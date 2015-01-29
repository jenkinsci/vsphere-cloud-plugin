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

import hudson.EnvVars;
import hudson.Extension;
import hudson.Launcher;
import hudson.model.BuildListener;
import hudson.model.EnvironmentContributingAction;
import hudson.model.AbstractBuild;
import hudson.util.FormValidation;

import java.io.IOException;
import java.io.PrintStream;
import java.util.HashMap;
import java.util.Map;

import javax.servlet.ServletException;

import org.jenkinsci.plugins.vsphere.VSphereBuildStep;
import org.jenkinsci.plugins.vsphere.tools.VSphere;
import org.jenkinsci.plugins.vsphere.tools.VSphereException;
import org.jenkinsci.plugins.vsphere.tools.VSphereLogger;
import org.kohsuke.stapler.DataBoundConstructor;
import org.kohsuke.stapler.QueryParameter;

import com.vmware.vim25.mo.VirtualMachine;

public class PowerOn extends VSphereBuildStep {

	private final String vm;    
	private final int timeoutInSeconds;

	@DataBoundConstructor
	public PowerOn(final String vm, final int timeoutInSeconds) throws VSphereException {
		this.vm = vm;
		this.timeoutInSeconds = timeoutInSeconds;
	}

	public String getVm() {
		return vm;
	}

	public int getTimeoutInSeconds() {
		return timeoutInSeconds;
	}

	public boolean perform(final AbstractBuild<?, ?> build, Launcher launcher, final BuildListener listener) throws VSphereException {
		return powerOn(build, launcher, listener);
		//TODO throw AbortException instead of returning value
	}

	private boolean powerOn(final AbstractBuild<?, ?> build, Launcher launcher, final BuildListener listener) throws VSphereException{
		PrintStream jLogger = listener.getLogger();
		EnvVars env;
		try {
			env = build.getEnvironment(listener);
		} catch (Exception e) {
			throw new VSphereException(e);
		}

		env.overrideAll(build.getBuildVariables()); // Add in matrix axes..
		String expandedVm = env.expand(vm);

		VSphereLogger.vsLogger(jLogger, "Waiting for IP (VM may be restarted during this time)");
		vsphere.startVm(expandedVm);
		String vmIP = vsphere.getIp(vsphere.getVmByName(expandedVm), getTimeoutInSeconds());

		if(vmIP==null){
			VSphereLogger.vsLogger(jLogger, "Error: Could not get IP for \""+expandedVm+"\" ");
			return false;
		}

		VSphereLogger.vsLogger(jLogger, "Successfully retrieved IP for \""+expandedVm+"\" : "+vmIP);

        // useful to tell user about the environment variable
        VSphereLogger.vsLogger(jLogger, "Exposing " + vmIP + " as environment variable VSPHERE_IP");
        VSphereEnvAction envAction = new VSphereEnvAction();
		envAction.add("VSPHERE_IP", vmIP);
		build.addAction(envAction);
		return true;
	}

	@Extension
	public static class PowerOnDescriptor extends VSphereBuildStepDescriptor {

		@Override
		public String getDisplayName() {
			return Messages.vm_title_PowerOn();
		}

		public FormValidation doCheckTimeoutInSeconds(@QueryParameter String value)
				throws IOException, ServletException {
			if (value.length() == 0)
				return FormValidation.error(Messages.validation_required("Timeout"));

			if (!value.matches("\\d+"))
				return FormValidation.error(Messages.validation_positiveInteger("Timeout"));

			if (Integer.parseInt(value)>3600)
				return FormValidation.error(Messages.validation_maxValue(3600));

			return FormValidation.ok();
		}

		public FormValidation doCheckVm(@QueryParameter String value)
				throws IOException, ServletException {

			if (value.length() == 0)
				return FormValidation.error(Messages.validation_required("the VM name"));
			return FormValidation.ok();
		}

		public FormValidation doTestData(@QueryParameter String serverName,
				@QueryParameter String vm) {
			try {

				if (vm.length() == 0 || serverName.length()==0)
					return FormValidation.error(Messages.validation_requiredValues());

				VSphere vsphere = getVSphereCloudByName(serverName).vSphereInstance();

				if (vm.indexOf('$') >= 0)
					return FormValidation.warning(Messages.validation_buildParameter("VM"));

				VirtualMachine vmObj = vsphere.getVmByName(vm);
				if ( vmObj == null)
					return FormValidation.error(Messages.validation_notFound("VM"));

				if (vmObj.getConfig().template)
					return FormValidation.error(Messages.validation_notActually("VM"));

				return FormValidation.ok(Messages.validation_success());
			} catch (Exception e) {
				throw new RuntimeException(e);
			}
		}
	}
	/**
	 * This class is used to inject the IP value into the build environment
	 * as a variable so that it can be used with other plugins.
	 * 
	 * @author Lordahl
	 */
	private static class VSphereEnvAction implements EnvironmentContributingAction {
		// Decided not to record this data in build.xml, so marked transient:
		private transient Map<String,String> data = new HashMap<String,String>();

		private void add(String key, String val) {
			if (data==null) return;
			data.put(key, val);
		}

		public void buildEnvVars(AbstractBuild<?,?> build, EnvVars env) {
			if (data!=null) env.putAll(data);
		}

		public String getIconFileName() { return null; }
		public String getDisplayName() { return null; }
		public String getUrlName() { return null; }
	}
}
