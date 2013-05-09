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

import org.jenkinsci.plugins.vsphere.Server;
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
	private transient VSphere vsphere = null;
	private transient final VSphereLogger logger;
	
	@DataBoundConstructor
	public MarkVM(String serverName, String template, boolean powerOn) throws VSphereException {
		this.serverName = serverName;
		this.powerOn = powerOn;
		this.template = template;
		this.logger  = VSphereLogger.getVSphereLogger();
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
		logger.verboseLogger(jLogger, "Attempting to use server configuration: " + serverName, true);
		boolean changed = false;

		try {
			Server server = VSpherePlugin.DescriptorImpl.get().getServer(serverName);
			//Need to ensure this server still exists.  If it's deleted
			//and a job is not opened, it will still try to connect
			VSpherePlugin.DescriptorImpl.get().checkServerExistence(server);

			vsphere = VSphere.connect(server);
			changed = markVm(build, launcher, listener);

		} catch (VSphereException e) {
			logger.verboseLogger(jLogger, e.getMessage(), true);
			e.printStackTrace(jLogger);
		}

		return changed;
	}

	/* (non-Javadoc)
	 * @see hudson.tasks.BuildWrapper#setUp(hudson.model.AbstractBuild, hudson.Launcher, hudson.model.BuildListener)
	 */
	private boolean markVm(final AbstractBuild<?, ?> build, final Launcher launcher, final BuildListener listener) throws VSphereException {
		PrintStream jLogger = listener.getLogger();
		logger.verboseLogger(jLogger, "Converting template to VM. Please wait ...", true);		

		EnvVars env;
		try {
			env = build.getEnvironment(listener);
		} catch (Exception e) {
			throw new VSphereException(e);
		}

		//TODO:  take in a comma delimited list and convert all
		env.overrideAll(build.getBuildVariables()); // Add in matrix axes..
		String expandedTemplate = env.expand(template);

		VirtualMachine vm = vsphere.markAsVm(expandedTemplate);
		logger.verboseLogger(jLogger, "\""+expandedTemplate+"\" is a VM!", true);

		if(powerOn){
			vsphere.startVm(expandedTemplate);
			String vmIP = vsphere.getIp(vm); 
			if(vmIP!=null){
				logger.verboseLogger(jLogger, "Got IP for \""+expandedTemplate+"\" ", true);
				VSphereEnvAction envAction = new VSphereEnvAction();
				envAction.add("VSPHERE_IP", vmIP);
				build.addAction(envAction);
				return true;
			}

			logger.verboseLogger(jLogger, "Error: Could not get IP for \""+expandedTemplate+"\" ", true);
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
				return FormValidation.error("Please enter the Template name");
			return FormValidation.ok();
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
