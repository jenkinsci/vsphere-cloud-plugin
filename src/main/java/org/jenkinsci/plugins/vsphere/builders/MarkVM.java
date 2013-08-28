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
import hudson.model.AbstractProject;
import hudson.tasks.BuildStepDescriptor;
import hudson.tasks.Builder;
import hudson.util.FormValidation;
import hudson.util.ListBoxModel;

import java.io.IOException;
import java.io.PrintStream;
import java.util.HashMap;
import java.util.Map;

import javax.servlet.ServletException;

import org.jenkinsci.plugins.vsphere.VSpherePlugin;
import org.jenkinsci.plugins.vsphere.tools.VSphere;
import org.jenkinsci.plugins.vsphere.tools.VSphereException;
import org.jenkinsci.plugins.vsphere.tools.VSphereLogger;
import org.kohsuke.stapler.DataBoundConstructor;
import org.kohsuke.stapler.QueryParameter;

import com.vmware.vim25.mo.VirtualMachine;

public class MarkVM extends Builder {

	private final String template;
	private final boolean powerOn;
	private final String serverName;
	private final int serverHash;
	private VSphere vsphere = null;

	@DataBoundConstructor
	public MarkVM(String serverName, String template, boolean powerOn) throws VSphereException {
		this.serverName = serverName;
		this.powerOn = powerOn;
		this.template = template;
		this.serverHash = VSpherePlugin.DescriptorImpl.get().getVSphereCloudByName(serverName).getHash();
	}

	public String getTemplate() {
		return template;
	}

	public String getServerName(){
		return serverName;
	}

	public boolean isPowerOn() {
		return powerOn;
	}

	@Override
	public boolean perform(final AbstractBuild<?, ?> build, final Launcher launcher, final BuildListener listener) {

		PrintStream jLogger = listener.getLogger();
		VSphereLogger.vsLogger(jLogger, Messages.console_usingServerConfig(serverName));
		boolean changed = false;

		try {
			//Need to ensure this server still exists.  If it's deleted
			//and a job is not opened, it will still try to connect
			vsphere = VSpherePlugin.DescriptorImpl.get().getVSphereCloudByHash(this.serverHash).vSphereInstance(); 
			changed = markVm(build, launcher, listener);

		} catch (VSphereException e) {
			VSphereLogger.vsLogger(jLogger, e.getMessage());
			e.printStackTrace(jLogger);
		}

		return changed;
	}

	private boolean markVm(final AbstractBuild<?, ?> build, final Launcher launcher, final BuildListener listener) throws VSphereException {
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

		VirtualMachine vm = vsphere.markAsVm(expandedTemplate);
		VSphereLogger.vsLogger(jLogger, "\""+expandedTemplate+"\" is a VM!");

		if(powerOn){
			vsphere.startVm(expandedTemplate);
			String vmIP = vsphere.getIp(vm); 
			if(vmIP!=null){
				VSphereLogger.vsLogger(jLogger, "Got IP for \""+expandedTemplate+"\" ");
				VSphereEnvAction envAction = new VSphereEnvAction();
				envAction.add("VSPHERE_IP", vmIP);
				build.addAction(envAction);
				return true;
			}

			VSphereLogger.vsLogger(jLogger, "Error: Could not get IP for \""+expandedTemplate+"\" ");
			return false;
		}

		return true;
	}


	@Override
	public DescriptorImpl getDescriptor() {
		return (DescriptorImpl )super.getDescriptor();
	}

	@Extension
	public static final class DescriptorImpl extends BuildStepDescriptor<Builder> {

		public DescriptorImpl() {
			load();
		}

		/**
		 * This human readable name is used in the configuration screen.
		 */
		@Override
		public String getDisplayName() {
			return VSphere.vSphereOutput(Messages.vm_title_MarkVM());
		}

		/**
		 * Performs on-the-fly validation of the form field 'name'.
		 *
		 * @param value
		 *      This parameter receives the value that the user has typed.
		 * @return
		 *      Indicates the outcome of the validation. This is sent to the browser.
		 */
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

				VSphere vsphere = VSpherePlugin.DescriptorImpl.get().getVSphereCloudByName(serverName).vSphereInstance();

				if (template.indexOf('$') >= 0)
					return FormValidation.warning(Messages.validation_buildParameter("Template"));

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

		@Override
		public boolean isApplicable(Class<? extends AbstractProject> jobType) {
			return true;
		}

		public ListBoxModel doFillServerNameItems(){
			return VSpherePlugin.DescriptorImpl.get().doFillServerItems();
		}
	}	


	//TODO move to own class/file
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
