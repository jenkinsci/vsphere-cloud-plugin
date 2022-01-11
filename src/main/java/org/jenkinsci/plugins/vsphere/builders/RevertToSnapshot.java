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
import org.kohsuke.stapler.QueryParameter;
import org.kohsuke.stapler.interceptor.RequirePOST;

import com.vmware.vim25.mo.VirtualMachineSnapshot;

public class RevertToSnapshot extends VSphereBuildStep implements SimpleBuildStep {

	private final String vm;    
	private final String snapshotName;

	@DataBoundConstructor
	public RevertToSnapshot(final String vm, final String snapshotName) throws VSphereException {
		this.vm = vm;
		this.snapshotName = snapshotName;
	}

	public String getVm() {
		return vm;
	}

	public String getSnapshotName() {
		return snapshotName;
	}

	@Override
	public void perform(@NonNull Run<?, ?> run, @NonNull FilePath filePath, @NonNull Launcher launcher, @NonNull TaskListener listener) throws InterruptedException, IOException {
		try {
			revertToSnapshot (run, launcher, listener);
		} catch (Exception e) {
			throw new AbortException(e.getMessage());
		}
	}

	@Override
	public boolean prebuild(AbstractBuild<?, ?> abstractBuild, BuildListener buildListener) {
		return false;
	}

	@Override
	public boolean perform(final AbstractBuild<?, ?> build, Launcher launcher, final BuildListener listener) {
		boolean retVal = false;
		try {
			retVal = revertToSnapshot(build, launcher, listener);
		} catch (Exception e) {
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

	private boolean revertToSnapshot(final Run<?, ?> run, Launcher launcher, final TaskListener listener) throws VSphereException{
		PrintStream jLogger = listener.getLogger();
		String expandedSnap = snapshotName;
		String expandedVm = vm;
		EnvVars env;
		try {
			env = run.getEnvironment(listener);
		} catch (Exception e) {
			throw new VSphereException(e);
		}

		if (run instanceof AbstractBuild) {
			env.overrideAll(((AbstractBuild)run).getBuildVariables()); // Add in matrix axes..
			expandedSnap = env.expand(snapshotName);
			expandedVm = env.expand(vm);
		}

		VSphereLogger.vsLogger(jLogger, "Reverting to snapshot \""+expandedSnap+"\" for VM "+expandedVm+"...");
		vsphere.revertToSnapshot(expandedVm, expandedSnap);
		VSphereLogger.vsLogger(jLogger, "Complete.");

		return true;
	}

	@Extension
	public static class RevertToSnapshotDescriptor extends VSphereBuildStepDescriptor {

		@Override
		public String getDisplayName() {
			return Messages.vm_title_RevertToSnapshot();
		}

		public FormValidation doCheckVm(@QueryParameter String value) {
			if (value.length() == 0)
				return FormValidation.error(Messages.validation_required("the VM name"));
			return FormValidation.ok();
		}

		public FormValidation doCheckSnapshotName(@QueryParameter String value) {
			if (value.length() == 0)
				return FormValidation.error(Messages.validation_required("the snapshot name"));
			return FormValidation.ok();
		}

        @RequirePOST
		public FormValidation doTestData(@AncestorInPath Item context,
                @QueryParameter String serverName,
				@QueryParameter String vm, @QueryParameter String snapshotName) {
            throwUnlessUserHasPermissionToConfigureJob(context);
			try {

				if (vm.length() == 0 || serverName.length()==0 || snapshotName.length()==0)
					return FormValidation.error(Messages.validation_requiredValues());

				VSphere vsphere = getVSphereCloudByName(serverName, null).vSphereInstance();

				if (vm.indexOf('$') >= 0)
					return FormValidation.warning(Messages.validation_buildParameter("VM"));

				if (vsphere.getVmByName(vm) == null)
					return FormValidation.error(Messages.validation_notFound("VM"));

				if (snapshotName.indexOf('$') >= 0)
					return FormValidation.warning(Messages.validation_buildParameter("Snapshot"));

				VirtualMachineSnapshot snap = vsphere.getSnapshotInTree(vsphere.getVmByName(vm), snapshotName);
				if (snap==null){
					return FormValidation.error(Messages.validation_notFound("Snapshot"));
				}

				return FormValidation.ok(Messages.validation_success());
			} catch (Exception e) {
				throw new RuntimeException(e);
			}
		}
	}
}
