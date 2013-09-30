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

import com.vmware.vim25.mo.VirtualMachine;

public class ConvertToVm extends VSphereBuildStep {

	private final String template;

	@DataBoundConstructor
	public ConvertToVm(String template) throws VSphereException {
		this.template = template;
	}

	public String getTemplate() {
		return template;
	}

	@Override
	public boolean perform(final AbstractBuild<?, ?> build, final Launcher launcher, final BuildListener listener) {

		PrintStream jLogger = listener.getLogger();
		boolean changed = false;

		try {
			changed = convert(build, launcher, listener);
		} 
		catch (VSphereException e) {
			VSphereLogger.vsLogger(jLogger, e.getMessage());
			e.printStackTrace(jLogger);
		}

		return changed;
	}

	private boolean convert(final AbstractBuild<?, ?> build, final Launcher launcher, final BuildListener listener) throws VSphereException {
		PrintStream jLogger = listener.getLogger();
		VSphereLogger.vsLogger(jLogger, "Converting template to VM. Please wait ...");		

		EnvVars env;
		try {
			env = build.getEnvironment(listener);
		} catch (Exception e) {
			throw new VSphereException(e);
		}

		//TODO:  take in a comma delimited list and convert all
		env.overrideAll(build.getBuildVariables()); // Add in matrix axis..
		String expandedTemplate = env.expand(template);

		vsphere.markAsVm(expandedTemplate);
		VSphereLogger.vsLogger(jLogger, "\""+expandedTemplate+"\" is a VM!");

		return true;
	}

	@Extension
	public static final class ConvertToVmDescriptor extends VSphereBuildStepDescriptor {

		public ConvertToVmDescriptor() {
			load();
		}

		@Override
		public String getDisplayName() {
			return Messages.vm_title_ConvertToVM();
		}

		public FormValidation doCheckTemplate(@QueryParameter String value)
				throws IOException, ServletException {
			if (value.length() == 0)
				return FormValidation.error(Messages.validation_required("the Template name"));
			return FormValidation.ok();
		}

		public FormValidation doTestData(@QueryParameter String serverName,
				@QueryParameter String template) {
			try {

				if (serverName.length() == 0 || template.length() == 0)
					return FormValidation.error(Messages.validation_requiredValues());

				if (template.indexOf('$') >= 0)
					return FormValidation.warning(Messages.validation_buildParameter("Template"));

				VSphere vsphere = getVSphereCloudByName(serverName).vSphereInstance();
				VirtualMachine vm = vsphere.getVmByName(template);         
				if (vm == null)
					return FormValidation.error(Messages.validation_notFound("template"));

				if(!vm.getConfig().template)
					return FormValidation.error(Messages.validation_alreadySet("template", "VM"));

				return FormValidation.ok(Messages.validation_success());
			} catch (Exception e) {
				throw new RuntimeException(e);
			}
		}
	}	
}
