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

import org.jenkinsci.plugins.vsphere.VSpherePlugin;
import org.jenkinsci.plugins.vsphere.tools.VSphere;
import org.jenkinsci.plugins.vsphere.tools.VSphereException;
import org.jenkinsci.plugins.vsphere.tools.VSphereLogger;
import org.kohsuke.stapler.DataBoundConstructor;
import org.kohsuke.stapler.QueryParameter;

import com.vmware.vim25.mo.VirtualMachine;

public class Destroyer extends Builder{

	private final String vm;
	private final String serverName;
	private final boolean failOnNoExist;
	private VSphere vsphere = null;

	@DataBoundConstructor
	public Destroyer(String serverName,	String vm, boolean failOnNoExist) throws VSphereException {
		this.serverName = serverName;
		this.failOnNoExist = failOnNoExist;
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
		VSphereLogger.vsLogger(jLogger, "Attempting to use server configuration: " + serverName);
		boolean killed = false;

		try {
			//Need to ensure this server still exists.  If it's deleted
			//and a job is not opened, it will still try to connect
			vsphere = VSpherePlugin.DescriptorImpl.get().getVSphereCloud(serverName).vSphereInstance();
			//VSpherePlugin.DescriptorImpl.get().checkServerExistence(server);
			
			if(VSpherePlugin.DescriptorImpl.allowDelete())
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
		
		//TODO ensure variables are not null
		public FormValidation doTestData(@QueryParameter String serverName,
                @QueryParameter String vm) {
            try {
                VSphere vsphere = VSpherePlugin.DescriptorImpl.get().getVSphereCloud(serverName).vSphereInstance();
                VirtualMachine vmObj = vsphere.getVmByName(vm);         
                
                if (vmObj == null) {
                    return FormValidation.error("Specified VM not found!");
                }
                
                return FormValidation.ok("Success");
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        }

		public ListBoxModel doFillServerNameItems(){
			return VSpherePlugin.DescriptorImpl.get().doFillServerItems();
		}
	}
}
