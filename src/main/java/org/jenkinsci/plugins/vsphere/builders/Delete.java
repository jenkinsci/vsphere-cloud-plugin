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
import hudson.util.FormValidation;

import java.io.IOException;
import java.io.PrintStream;

import edu.umd.cs.findbugs.annotations.NonNull;

import org.jenkinsci.plugins.vsphere.VSphereBuildStep;
import org.jenkinsci.plugins.vsphere.tools.VSphere;
import org.jenkinsci.plugins.vsphere.tools.VSphereException;
import org.jenkinsci.plugins.vsphere.tools.VSphereLogger;
import org.kohsuke.stapler.AncestorInPath;
import org.kohsuke.stapler.DataBoundConstructor;
import org.kohsuke.stapler.QueryParameter;
import org.kohsuke.stapler.interceptor.RequirePOST;

import com.vmware.vim25.mo.VirtualMachine;

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
	public void perform(@NonNull Run<?, ?> run, @NonNull FilePath filePath, @NonNull Launcher launcher, @NonNull TaskListener listener) throws InterruptedException, IOException {
		try {
			if(allowDelete()) {
				killVm(run, launcher, listener);
			} else {
				VSphereLogger.vsLogger(listener.getLogger(), "Deletion is disabled!");
			}
		} catch (Exception e) {
			throw new AbortException(e.getMessage());
		}
	}

	@Override
	public boolean perform(final AbstractBuild<?, ?> build, final Launcher launcher, final BuildListener listener)  {
		boolean retVal = false;
		try {
			if(allowDelete()) {
				retVal = killVm(build, launcher, listener);
			} else {
				VSphereLogger.vsLogger(listener.getLogger(), "Deletion is disabled!");
			}
		} catch (Exception e) {
			e.printStackTrace();
		}
		return retVal;
		//TODO throw AbortException instead of returning value
	}

	private boolean killVm(final Run<?, ?> run, final Launcher launcher, final TaskListener listener) throws VSphereException {

		PrintStream jLogger = listener.getLogger();
		String expandedVm = vm;
		EnvVars env;
		try {
			env = run.getEnvironment(listener);
		} catch (Exception e) {
			throw new VSphereException(e);
		}
		if (run instanceof AbstractBuild) {
			env.overrideAll(((AbstractBuild)run).getBuildVariables()); // Add in matrix axes..
			expandedVm = env.expand(vm);
		}

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

		public FormValidation doCheckVm(@QueryParameter String value) {
			if (value.length() == 0)
				return FormValidation.error(Messages.validation_required("the VM name"));
			return FormValidation.ok();
		}

		@Override
		public String getDisplayName() {
			return Messages.vm_title_Delete();
		}

        @RequirePOST
		public FormValidation doTestData(@AncestorInPath Item context,
                @QueryParameter String serverName,
				@QueryParameter String vm) {
            throwUnlessUserHasPermissionToConfigureJob(context);
			try {
				if (serverName.length() == 0 || vm.length()==0 )
					return FormValidation.error(Messages.validation_requiredValues());

				VSphere vsphere = getVSphereCloudByName(serverName, null).vSphereInstance();

				if (vm.indexOf('$') >= 0)
					return FormValidation.warning(Messages.validation_buildParameter("VM"));

				VirtualMachine vmObj = vsphere.getVmByName(vm);
				if (vmObj == null)
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
