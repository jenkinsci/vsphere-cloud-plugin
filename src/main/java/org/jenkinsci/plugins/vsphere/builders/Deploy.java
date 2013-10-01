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
import com.vmware.vim25.mo.VirtualMachineSnapshot;

public class Deploy extends VSphereBuildStep {

	private final String template;
	private final String clone;
	private final boolean linkedClone;
	private final String resourcePool;
	private final String cluster;

	@DataBoundConstructor
	public Deploy(String template, String clone, boolean linkedClone,
			String resourcePool, String cluster) throws VSphereException {
		this.template = template;
		this.clone = clone;
		this.linkedClone = linkedClone;
		this.resourcePool=resourcePool;
		this.cluster=cluster;
	}

	public String getTemplate() {
		return template;
	}

	public String getClone() {
		return clone;
	}

	public boolean isLinkedClone() {
		return linkedClone;
	}

	public String getCluster() {
		return cluster;
	}

	public String getResourcePool() {
		return resourcePool;
	}

	@Override
	public boolean perform(final AbstractBuild<?, ?> build, final Launcher launcher, final BuildListener listener) {

		PrintStream jLogger = listener.getLogger();
		boolean success=false;

		try{
			success = deployFromTemplate(build, launcher, listener);
		} 
		catch(VSphereException e){
			VSphereLogger.vsLogger(jLogger, e.getMessage());
			e.printStackTrace(jLogger);
		}

		//TODO throw AbortException instead of returning value
		return success;
	}

	private boolean deployFromTemplate(final AbstractBuild<?, ?> build, final Launcher launcher, final BuildListener listener) throws VSphereException {
		PrintStream jLogger = listener.getLogger();
		VSphereLogger.vsLogger(jLogger, "Cloning VM. Please wait ...");

		EnvVars env;
		try {
			env = build.getEnvironment(listener);
		} catch (Exception e) {
			throw new VSphereException(e);
		}

		env.overrideAll(build.getBuildVariables()); // Add in matrix axes..
		String expandedClone = env.expand(clone), expandedTemplate = env.expand(template);

		vsphere.cloneVm(expandedClone, expandedTemplate, linkedClone, resourcePool, cluster);
		VSphereLogger.vsLogger(jLogger, "\""+expandedClone+"\" successfully deployed!");

		return true;
	}

	@Extension
	public static final class DeployDescriptor extends VSphereBuildStepDescriptor {

		public DeployDescriptor() {
			load();
		}

		@Override
		public String getDisplayName() {
			return Messages.vm_title_Deploy();
		}

		public FormValidation doCheckTemplate(@QueryParameter String value)
				throws IOException, ServletException {
			if (value.length() == 0)
				return FormValidation.error("Please enter the template name");
			return FormValidation.ok();
		}

		public FormValidation doCheckClone(@QueryParameter String value)
				throws IOException, ServletException {
			if (value.length() == 0)
				return FormValidation.error(Messages.validation_required("the clone name"));
			return FormValidation.ok();
		}

		public FormValidation doCheckResourcePool(@QueryParameter String value)
				throws IOException, ServletException {
			if (value.length() == 0)
				return FormValidation.error(Messages.validation_required("the resource pool"));
			return FormValidation.ok();
		}

		public FormValidation doCheckCluster(@QueryParameter String value)
				throws IOException, ServletException {
			if (value.length() == 0)
				return FormValidation.error(Messages.validation_required("the cluster"));
			return FormValidation.ok();
		}

		public FormValidation doTestData(@QueryParameter String serverName,
				@QueryParameter String template, @QueryParameter String clone,
				@QueryParameter String resourcePool, @QueryParameter String cluster) {
			try {
				if (template.length() == 0 || clone.length()==0 || serverName.length()==0
						||resourcePool.length()==0 || cluster.length()==0 )
					return FormValidation.error(Messages.validation_requiredValues());

				VSphere vsphere = getVSphereCloudByName(serverName).vSphereInstance();

				//TODO what if clone name is variable?
				VirtualMachine cloneVM = vsphere.getVmByName(clone);
				if (cloneVM != null)
					return FormValidation.error(Messages.validation_exists("clone"));

				if (template.indexOf('$') >= 0)
					return FormValidation.warning(Messages.validation_buildParameter("template"));

				VirtualMachine vm = vsphere.getVmByName(template);      
				if (vm == null)
					return FormValidation.error(Messages.validation_notFound("template"));

				if(!vm.getConfig().template)
					return FormValidation.error(Messages.validation_notActually("template"));

				VirtualMachineSnapshot snap = vm.getCurrentSnapShot();
				if (snap == null)
					return FormValidation.error(Messages.validation_noSnapshots());

				return FormValidation.ok(Messages.validation_success());
			} catch (Exception e) {
				throw new RuntimeException(e);
			}
		}
	}
}
