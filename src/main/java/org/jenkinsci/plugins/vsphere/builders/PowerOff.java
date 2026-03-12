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

import static org.jenkinsci.plugins.vsphere.tools.PermissionUtils.throwUnlessUserHasPermissionToConfigureJob;

import hudson.*;
import hudson.model.*;
import hudson.tasks.BuildStepMonitor;
import hudson.util.FormValidation;

import java.io.IOException;
import java.io.PrintStream;
import java.util.Collection;

import edu.umd.cs.findbugs.annotations.NonNull;

import jenkins.tasks.SimpleBuildStep;
import org.jenkinsci.plugins.vsphere.VSphereBuildStep;
import org.jenkinsci.plugins.vsphere.tools.VSphere;
import org.jenkinsci.plugins.vsphere.tools.VSphereException;
import org.jenkinsci.plugins.vsphere.tools.VSphereLogger;
import org.kohsuke.stapler.AncestorInPath;
import org.kohsuke.stapler.DataBoundConstructor;
import org.kohsuke.stapler.DataBoundSetter;
import org.kohsuke.stapler.QueryParameter;
import org.kohsuke.stapler.interceptor.RequirePOST;

import com.vmware.vim25.mo.VirtualMachine;

public class PowerOff extends VSphereBuildStep implements SimpleBuildStep {

	private final String vm;    
	private final boolean evenIfSuspended;
    private final boolean shutdownGracefully;
	private Integer gracefulShutdownTimeout;

	private final boolean ignoreIfNotExists;

	@DataBoundConstructor
	public PowerOff( final String vm, final boolean evenIfSuspended, final boolean shutdownGracefully, final boolean ignoreIfNotExists) throws VSphereException {
		this.vm = vm;
		this.evenIfSuspended = evenIfSuspended;
        this.shutdownGracefully = shutdownGracefully;
        this.ignoreIfNotExists = ignoreIfNotExists;
	}

	@DataBoundSetter
	public void setGracefulShutdownTimeout(int gracefulShutdownTimeout) {
		this.gracefulShutdownTimeout = gracefulShutdownTimeout == 180 ? null : gracefulShutdownTimeout;
	}

	public boolean isIgnoreIfNotExists() {
		return ignoreIfNotExists;
	}

	public boolean isEvenIfSuspended() {
		return evenIfSuspended;
	}

    public boolean isShutdownGracefully() {return shutdownGracefully; }

	public int getGracefulShutdownTimeout() {
		if (!shutdownGracefully || gracefulShutdownTimeout == null)
			return 180;
		return gracefulShutdownTimeout;
	}

	public String getVm() {
		return vm;
	}

	@Override
	public boolean prebuild(AbstractBuild<?, ?> abstractBuild, BuildListener buildListener) {
		return false;
	}

	@Override
	public void perform(@NonNull Run<?, ?> run, @NonNull FilePath filePath, @NonNull Launcher launcher, @NonNull TaskListener listener) throws InterruptedException, IOException {
		try {
			powerOff(run, launcher, listener);
		} catch (Exception e) {
			throw new AbortException(e.getMessage());
		}
	}

	@Override
	public boolean perform(final AbstractBuild<?, ?> build, Launcher launcher, final BuildListener listener) {
		boolean retVal = false;
		try {
			retVal = powerOff(build, launcher, listener);
		} catch (VSphereException e) {
			e.printStackTrace();
		}
		return retVal;
		//TODO throw AbortException instead of returning value
	}

	@Override
	public Action getProjectAction(AbstractProject<?, ?> abstractProject) {
		return null;
	}

	@Override
	public Collection<? extends Action> getProjectActions(AbstractProject<?, ?> abstractProject) {
		return null;
	}

	@Override
	public BuildStepMonitor getRequiredMonitorService() {
		return null;
	}

	private boolean powerOff(final Run<?, ?> run, Launcher launcher, final TaskListener listener) throws VSphereException{
		PrintStream jLogger = listener.getLogger();
		EnvVars env;
		String expandedVm = vm;
		if (run instanceof AbstractBuild) {
			try {
				env = run.getEnvironment(listener);
			} catch (Exception e) {
				throw new VSphereException(e);
			}

			env.overrideAll(((AbstractBuild)run).getBuildVariables()); // Add in matrix axes..
			expandedVm = env.expand(vm);
		}

		VSphereLogger.vsLogger(jLogger, "Shutting Down VM " + expandedVm + "...");
        VirtualMachine vsphereVm = vsphere.getVmByName(expandedVm);
        if (vsphereVm == null && !ignoreIfNotExists) {
            throw new RuntimeException(Messages.validation_notFound("vm " + expandedVm));
        }

        if (vsphereVm != null) {
			vsphere.powerOffVm(vsphereVm, evenIfSuspended, shutdownGracefully ? getGracefulShutdownTimeout() : 0);

			VSphereLogger.vsLogger(jLogger, "Successfully shutdown \"" + expandedVm + "\"");
		} else {
			VSphereLogger.vsLogger(jLogger, "Does not exists, BUT ignore it! \"" + expandedVm + "\"");
		}

		return true;
	}

	@Extension
	public static class PowerOffDescriptor extends VSphereBuildStepDescriptor {

		@Override
		public String getDisplayName() {
			return Messages.vm_title_PowerOff();
		}

		public FormValidation doCheckVm(@QueryParameter String value) {
			if (value.length() == 0)
				return FormValidation.error(Messages.validation_required("the VM name"));
			return FormValidation.ok();
		}

        @RequirePOST
		public FormValidation doTestData(@AncestorInPath Item context,
                @QueryParameter String serverName,
				@QueryParameter String vm) {
            throwUnlessUserHasPermissionToConfigureJob(context);
			try {
				if (serverName.length() == 0 || vm.length()==0 )
					return FormValidation.error(Messages.validation_requiredValues());

				if (vm.indexOf('$') >= 0)
					return FormValidation.warning(Messages.validation_buildParameter("VM"));

				VSphere vsphere = getVSphereCloudByName(serverName).vSphereInstance();
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
}
