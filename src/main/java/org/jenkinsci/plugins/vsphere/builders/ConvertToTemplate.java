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
import java.text.SimpleDateFormat;
import java.util.Date;

import javax.servlet.ServletException;

import org.jenkinsci.plugins.vsphere.VSphereBuildStep;
import org.jenkinsci.plugins.vsphere.tools.VSphere;
import org.jenkinsci.plugins.vsphere.tools.VSphereException;
import org.jenkinsci.plugins.vsphere.tools.VSphereLogger;
import org.kohsuke.stapler.DataBoundConstructor;
import org.kohsuke.stapler.QueryParameter;

public class ConvertToTemplate extends VSphereBuildStep {

	private final String vm;
	private final boolean force;
	private final String description;

	@DataBoundConstructor
	public ConvertToTemplate(String vm, String description, boolean force) throws VSphereException {
		this.force = force;
		this.vm = vm;
		this.description = description;
	}

	public String getVm() {
		return vm;
	}

	public String getDescription(){
		return description;
	}

	public boolean isForce() {
		return force;
	}

	@Override
	public boolean perform(final AbstractBuild<?, ?> build, final Launcher launcher, final BuildListener listener) {

		PrintStream jLogger = listener.getLogger();
		boolean changed = false;

		try {
			//Need to ensure this server still exists.  If it's deleted
			//and a job is not opened, it will still try to connect
			changed = convert(build, launcher, listener);

		} catch (VSphereException e) {
			VSphereLogger.vsLogger(jLogger, e.getMessage());
			e.printStackTrace(jLogger);
		}

		return changed;
	}

	private boolean convert(final AbstractBuild<?, ?> build, final Launcher launcher, final BuildListener listener) throws VSphereException {
		PrintStream jLogger = listener.getLogger();
		VSphereLogger.vsLogger(jLogger, "Converting VM to template. Please wait ...");	

		EnvVars env;
		try {
			env = build.getEnvironment(listener);
		} catch (Exception e) {
			throw new VSphereException(e);
		}

		Date date = new Date();
		SimpleDateFormat df = new SimpleDateFormat("MMM dd, yyyy hh:mm:ss aaa");

		env.overrideAll(build.getBuildVariables()); // Add in matrix axes..
		String expandedVm = env.expand(vm);

		vsphere.markAsTemplate(expandedVm, df.format(date), env.expand(description), force);
		VSphereLogger.vsLogger(jLogger, "\""+expandedVm+"\" is now a template.");

		return true;
	}

	@Extension
	public static final class ConvertToTemplateDescriptor extends VSphereBuildStepDescriptor {

		public ConvertToTemplateDescriptor() {
			load();
		}

		/**
		 * This human readable name is used in the configuration screen.
		 */
		@Override
		public String getDisplayName() {
			return Messages.vm_title_MarkVmAsTemplate();
		}

		/**
		 * Performs on-the-fly validation of the form field 'name'.
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

		public FormValidation doCheckDescription(@QueryParameter String value)
				throws IOException, ServletException {
			if (value.length() == 0)
				return FormValidation.error(Messages.validation_required("the description"));
			return FormValidation.ok();
		}

		public FormValidation doTestData(@QueryParameter String serverName,
				@QueryParameter String vm, @QueryParameter String description) {
			try {

				if (serverName.length() == 0 || vm.length() == 0 || description.length() == 0)
					return FormValidation.error(Messages.validation_requiredValues());

				if (vm.indexOf('$') >= 0)
					return FormValidation.warning(Messages.validation_buildParameter("VM"));

				VSphere vsphere = getVSphereCloudByName(serverName).vSphereInstance();
				if (vsphere.getVmByName(vm) == null)
					return FormValidation.error(Messages.validation_notFound("VM"));

				return FormValidation.ok(Messages.validation_success());
			} catch (Exception e) {
				throw new RuntimeException(e);
			}
		}
	}	
}