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
import hudson.model.AbstractBuild;
import hudson.util.FormValidation;

import java.io.IOException;
import java.io.PrintStream;

import javax.servlet.ServletException;

import org.jenkinsci.plugins.vsphere.VSphereBuildStep;
import org.jenkinsci.plugins.vsphere.tools.VSphere;
import org.jenkinsci.plugins.vsphere.tools.VSphereException;
import org.jenkinsci.plugins.vsphere.tools.VSphereLogger;
import org.kohsuke.stapler.DataBoundConstructor;
import org.kohsuke.stapler.QueryParameter;

public class Delete extends VSphereBuildStep {

	private final String vm;
	private final boolean failOnNoExist;

	@DataBoundConstructor
	public Delete(String vm, boolean failOnNoExist) throws VSphereException {
		this.failOnNoExist = failOnNoExist;
		this.vm = vm;
	}

	public String getVm() {
		return vm;
	}

	public boolean isFailOnNoExist(){
		return failOnNoExist;
	}

	@Override
	public boolean perform(final AbstractBuild<?, ?> build, final Launcher launcher, final BuildListener listener)  {

		PrintStream jLogger = listener.getLogger();
		boolean killed = false;

		try {
			//Need to ensure this server still exists.  If it's deleted
			//and a job is not opened, it will still try to connect

			if(allowDelete())
				killed = killVm(build, launcher, listener);
			else
				VSphereLogger.vsLogger(jLogger, "Deletion is disabled!");

		} catch (VSphereException e) {
			VSphereLogger.vsLogger(jLogger, e.getMessage());
			e.printStackTrace(jLogger);
		}

		return killed;
	}

	private boolean killVm(final AbstractBuild<?, ?> build, final Launcher launcher, final BuildListener listener) throws VSphereException {

		PrintStream jLogger = listener.getLogger();
		EnvVars env;
		try {
			env = build.getEnvironment(listener);
		} catch (Exception e) {
			throw new VSphereException(e);
		}
		env.overrideAll(build.getBuildVariables()); // Add in matrix axes..
		String expandedVm = env.expand(vm);

		VSphereLogger.vsLogger(jLogger, "Destroying VM \""+expandedVm+".\" Please wait ...");
		vsphere.destroyVm(expandedVm, failOnNoExist);
		VSphereLogger.vsLogger(jLogger, "Destroyed!");

		return true;
	}
	
	

	@Extension
	public static final class DeleteDescriptor extends VSphereBuildStepDescriptor {

		public DeleteDescriptor() {
			load();
		}

		/**
		 * Performs on-the-fly validation of the form field 'clone'.
		 *
		 * @param value
		 *      This parameter receives the value that the user has typed.
		 * @return
		 *      Indicates the outcome of the validation. This is sent to the browser.
		 */
		public FormValidation doCheckVm(@QueryParameter String value)
				throws IOException, ServletException {

			if (value.length() == 0)
				return FormValidation.error(Messages.validation_required("the VM name"));
			return FormValidation.ok();
		}

		/**
		 * This human readable name is used in the configuration screen.
		 */
		@Override
		public String getDisplayName() {
			return Messages.vm_title_Destroyer();
		}

		public FormValidation doTestData(@QueryParameter String serverName,
				@QueryParameter String vm) {
			try {

				if (serverName.length() == 0 || vm.length()==0 )
					return FormValidation.error(Messages.validation_requiredValues());

				VSphere vsphere = getVSphereCloudByName(serverName).vSphereInstance();

				if (vm.indexOf('$') >= 0)
					return FormValidation.warning(Messages.validation_buildParameter("VM"));

				if (vsphere.getVmByName(vm) == null)
					return FormValidation.error(Messages.validation_notFound("VM"));

				return FormValidation.ok(Messages.validation_success());
			} catch (Exception e) {
				throw new RuntimeException(e);
			}
		}
	}
}
