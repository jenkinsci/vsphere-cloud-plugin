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
import com.vmware.vim25.mo.VirtualMachineSnapshot;

public class Starter extends Builder{

	private final String template;
	private final String serverName;
	private final String clone;
	private final boolean powerOn;
	private final boolean linkedClone;
	private final int timeoutInSeconds;
	private final int serverHash;
	private VSphere vsphere = null;

	@DataBoundConstructor
	public Starter(String serverName, String template,
			String clone, boolean powerOn, boolean linkedClone, int timeoutInSeconds) throws VSphereException {
		this.template = template;
		this.serverName = serverName;
		this.clone = clone;
		this.powerOn = powerOn;
		this.linkedClone = linkedClone;
		this.timeoutInSeconds = timeoutInSeconds;
		this.serverHash = VSpherePlugin.DescriptorImpl.get().getVSphereCloudByName(serverName).getHash();
	}

	public String getTemplate() {
		return template;
	}

	public String getClone() {
		return clone;
	}

	public String getServerName(){
		return serverName;
	}

	public boolean isPowerOn() {
		return powerOn;
	}

	public boolean isLinkedClone() {
		return linkedClone;
	}
	
	public int getTimeoutInSeconds(){
		return timeoutInSeconds;
	}

	@Override
	public boolean perform(final AbstractBuild<?, ?> build, final Launcher launcher, final BuildListener listener) {

		PrintStream jLogger = listener.getLogger();
		VSphereLogger.vsLogger(jLogger, Messages.console_usingServerConfig(serverName));
		boolean success=false;

		try{
			//Need to ensure this server still exists.  If it's deleted
			//and a job is not opened, it will still try to connect
			vsphere = VSpherePlugin.DescriptorImpl.get().getVSphereCloudByHash(this.serverHash).vSphereInstance(); 
			success = deployFromTemplate(build, launcher, listener);

		} catch(VSphereException e){
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

		VirtualMachine vm = vsphere.shallowCloneVm(expandedClone, expandedTemplate, powerOn, linkedClone);
		if(vm==null)
			throw new VSphereException("VM is null");

		VSphereLogger.vsLogger(jLogger, "Clone successful!");
		if(!powerOn)
			return true;

		VSphereLogger.vsLogger(jLogger, "Waiting for IP (VM may be restarted during this time)");
		String vmIP = vsphere.getIp(vm, getTimeoutInSeconds());

		if(vmIP!=null){
			VSphereLogger.vsLogger(jLogger, "Got IP for \""+expandedClone+"\" ");
			VSphereEnvAction envAction = new VSphereEnvAction();
			envAction.add("VSPHERE_IP", vmIP);
			build.addAction(envAction);
			return true;
		}

		VSphereLogger.vsLogger(jLogger, "Error: Could not get IP for \""+expandedClone+"\" ");
		return false;
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
			return VSphere.vSphereOutput(Messages.vm_title_Starter());
		}

		@Override
		public boolean isApplicable(Class<? extends AbstractProject> jobType) {
			return true;
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
				return FormValidation.error("Please enter the template name");
			return FormValidation.ok();
		}

		/**
		 * Performs on-the-fly validation of the form field 'clone'.
		 *
		 * @param value
		 *      This parameter receives the value that the user has typed.
		 * @return
		 *      Indicates the outcome of the validation. This is sent to the browser.
		 */
		public FormValidation doCheckClone(@QueryParameter String value)
				throws IOException, ServletException {
			if (value.length() == 0)
				return FormValidation.error(Messages.validation_required("the clone name"));
			return FormValidation.ok();
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

		public FormValidation doTestData(@QueryParameter String serverName,
				@QueryParameter String template, @QueryParameter String clone) {
			try {

				if (template.length() == 0 || clone.length()==0 || serverName.length()==0)
					return FormValidation.error(Messages.validation_requiredValues());

				VSphere vsphere = VSpherePlugin.DescriptorImpl.get().getVSphereCloudByName(serverName).vSphereInstance();

				VirtualMachine cloneVM = vsphere.getVmByName(clone);
				if (cloneVM != null)
					return FormValidation.error(Messages.validation_exists("clone"));
				
				if (template.indexOf('$') >= 0)
					return FormValidation.warning(Messages.validation_buildParameter("VM"));

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

		public ListBoxModel doFillServerNameItems(){
			return VSpherePlugin.DescriptorImpl.get().doFillServerItems();
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
