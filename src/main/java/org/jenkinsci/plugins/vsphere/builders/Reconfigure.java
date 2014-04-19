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

import com.vmware.vim25.VirtualMachineConfigSpec;
import com.vmware.vim25.mo.VirtualMachine;
import hudson.EnvVars;
import hudson.Extension;
import hudson.Launcher;
import hudson.model.AbstractBuild;
import hudson.model.BuildListener;
import hudson.model.Hudson;
import hudson.util.FormValidation;
import org.jenkinsci.plugins.vsphere.VSphereBuildStep;
import org.jenkinsci.plugins.vsphere.tools.VSphere;
import org.jenkinsci.plugins.vsphere.tools.VSphereException;
import org.jenkinsci.plugins.vsphere.tools.VSphereLogger;
import org.kohsuke.stapler.DataBoundConstructor;
import org.kohsuke.stapler.QueryParameter;

import javax.servlet.ServletException;
import java.io.IOException;
import java.io.PrintStream;
import java.util.List;

public class Reconfigure extends VSphereBuildStep {

    private final List<ReconfigureStep> reconfigureSteps;
	private final String vm;

	@DataBoundConstructor
	public Reconfigure(final String vm, final List<ReconfigureStep> reconfigureSteps) throws VSphereException {
		this.vm = vm;
        this.reconfigureSteps = reconfigureSteps;
	}

	public String getVm() {
		return vm;
	}

    public List<ReconfigureStep> getReconfigureSteps() {
        return reconfigureSteps;
    }

	public boolean perform(final AbstractBuild<?, ?> build, final Launcher launcher, final BuildListener listener) throws VSphereException  {
        return reconfigureVm(build, launcher, listener);
	}

	private boolean reconfigureVm(final AbstractBuild<?, ?> build, final Launcher launcher, final BuildListener listener) throws VSphereException {

		PrintStream jLogger = listener.getLogger();
		EnvVars env;
		try {
			env = build.getEnvironment(listener);
		} catch (Exception e) {
			throw new VSphereException(e);
		}
		env.overrideAll(build.getBuildVariables());
		String expandedVm = env.expand(vm);

        VirtualMachine realVM = vsphere.getVmByName(expandedVm);

		VSphereLogger.vsLogger(jLogger, "Reconfiguring VM \""+expandedVm+"\". Please wait ...");
        VirtualMachineConfigSpec spec = new VirtualMachineConfigSpec();
        for(ReconfigureStep actionStep : reconfigureSteps) {
            actionStep.setVsphere(getVsphere());
            actionStep.setVM(realVM);
            actionStep.setVirtualMachineConfigSpec(spec);
            actionStep.perform(build, launcher, listener);
        }
		vsphere.reconfigureVm(expandedVm, spec);
		VSphereLogger.vsLogger(jLogger, "Finished!");

		return true;
	}

    public ReconfigureDescriptor getDescriptor() {
        return (ReconfigureDescriptor) Hudson.getInstance().getDescriptor(getClass());
    }

	@Extension
	public static final class ReconfigureDescriptor extends VSphereBuildStepDescriptor {

		public ReconfigureDescriptor() {
			load();
		}

        public List<ReconfigureStep.ReconfigureStepDescriptor> getReconfigureStepsDescriptors() {
            return ReconfigureStep.all();
        }

		public FormValidation doCheckVm(@QueryParameter String value)
				throws IOException, ServletException {

			if (value.length() == 0)
				return FormValidation.error(Messages.validation_required("the VM name"));
			return FormValidation.ok();
		}

		@Override
		public String getDisplayName() {
			return Messages.vm_title_Reconfigure();
		}

		public FormValidation doTestData(@QueryParameter String serverName,
				@QueryParameter String vm) {
			try {

				if (serverName.length() == 0 || vm.length()==0 )
					return FormValidation.error(Messages.validation_requiredValues());

				VSphere vsphere = getVSphereCloudByName(serverName).vSphereInstance();

				if (vm.indexOf('$') >= 0)
					return FormValidation.warning(Messages.validation_buildParameter("VM"));

				VirtualMachine vmObj = vsphere.getVmByName(vm);
				if (vmObj == null)
					return FormValidation.error(Messages.validation_notFound("VM"));

				return FormValidation.ok(Messages.validation_success());
			} catch (Exception e) {
				throw new RuntimeException(e);
			}
		}
	}
}
