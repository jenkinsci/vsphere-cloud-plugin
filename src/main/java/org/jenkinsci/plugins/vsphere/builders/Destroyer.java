package org.jenkinsci.plugins.vsphere.builders;

import hudson.EnvVars;
import hudson.Extension;
import hudson.Launcher;
import hudson.model.BuildListener;
import hudson.model.AbstractBuild;
import hudson.model.AbstractProject;
import hudson.tasks.BuildStepDescriptor;
import hudson.tasks.Builder;
import hudson.util.FormValidation;
import hudson.util.ListBoxModel;

import java.io.IOException;
import java.io.PrintStream;

import javax.servlet.ServletException;

import org.jenkinsci.plugins.vsphere.Server;
import org.jenkinsci.plugins.vsphere.VSpherePlugin;
import org.jenkinsci.plugins.vsphere.tools.VSphere;
import org.jenkinsci.plugins.vsphere.tools.VSphereException;
import org.jenkinsci.plugins.vsphere.tools.VSphereLogger;
import org.kohsuke.stapler.DataBoundConstructor;
import org.kohsuke.stapler.QueryParameter;

public class Destroyer extends Builder{

	private final String vm;
	private final Server server;
	private final String serverName;
	private final boolean failOnNoExist;
	private VSphere vsphere = null;
	private final VSphereLogger logger = VSphereLogger.getVSphereLogger();

	@DataBoundConstructor
	public Destroyer(String serverName,	String vm, boolean failOnNoExist) throws VSphereException {
		this.serverName = serverName;
		this.failOnNoExist = failOnNoExist;
		server = VSpherePlugin.DescriptorImpl.get().getServer(serverName);
		this.vm = vm;
	}

	public String getVm() {
		return vm;
	}
	
	public boolean isFailOnNoExist(){
		return failOnNoExist;
	}

	public String getServerName(){
		return serverName;
	}

	@Override
	public boolean perform(final AbstractBuild<?, ?> build, final Launcher launcher, final BuildListener listener)  {

		PrintStream jLogger = listener.getLogger();
		logger.verboseLogger(jLogger, "Using server configuration: " + server.getName(), true);
		boolean killed = false;

		try {
			//Need to ensure this server still exists.  If it's deleted
			//and a job is not opened, it will still try to connect
			VSpherePlugin.DescriptorImpl.get().checkServerExistence(server);

			vsphere = VSphere.connect(server);
			
			if(VSpherePlugin.DescriptorImpl.allowDelete())
				killed = killVm(build, launcher, listener);
			else
				logger.verboseLogger(jLogger, "Deletion is disabled!", true);

		} catch (VSphereException e) {
			logger.verboseLogger(jLogger, e.getMessage(), true);
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

		logger.verboseLogger(jLogger, "Destroying VM \""+expandedVm+".\" Please wait ...", true);
		vsphere.destroyVm(expandedVm, failOnNoExist);
		logger.verboseLogger(jLogger, "Destroyed!", true);

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
				return FormValidation.error("Please enter the VM name");
			//TODO check if Vm exists
			return FormValidation.ok();
		}

		/**
		 * This human readable name is used in the configuration screen.
		 */
		@Override
		public String getDisplayName() {
			return VSphere.vSphereOutput(Messages.vm_title_Destroyer());
		}

		@Override
		public boolean isApplicable(Class<? extends AbstractProject> jobType) {
			// TODO Auto-generated method stub
			return true;
		}

		public ListBoxModel doFillServerNameItems(){
			return VSpherePlugin.DescriptorImpl.get().doFillServerItems();
		}
	}
}
