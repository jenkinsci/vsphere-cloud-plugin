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

import com.vmware.vim25.mo.VirtualMachineSnapshot;

public class DeleteSnapshot extends VSphereBuildStep {

	private final String vm;    
	private final String snapshotName;
	private final boolean consolidate;
	private final boolean failOnNoExist;

	@DataBoundConstructor
	public DeleteSnapshot(final String vm, final String snapshotName, final boolean consolidate, final boolean failOnNoExist) throws VSphereException {
		this.vm = vm;
		this.snapshotName = snapshotName;
		this.consolidate = consolidate;
		this.failOnNoExist = failOnNoExist;
	}

	public String getVm() {
		return vm;
	}

	public String getSnapshotName() {
		return snapshotName;
	}

	public boolean isConsolidate() {
		return consolidate;
	}

	public boolean isFailOnNoExist() {
		return failOnNoExist;
	}

	public boolean perform(final AbstractBuild<?, ?> build, Launcher launcher, final BuildListener listener) throws VSphereException {
		return deleteSnapshot(build, launcher, listener);
		//TODO throw AbortException instead of returning value
	}

	private boolean deleteSnapshot(final AbstractBuild<?, ?> build, Launcher launcher, final BuildListener listener) throws VSphereException{
		PrintStream jLogger = listener.getLogger();
		EnvVars env;
		try {
			env = build.getEnvironment(listener);
		} catch (Exception e) {
			throw new VSphereException(e);
		}

		env.overrideAll(build.getBuildVariables()); // Add in matrix axes..
		String expandedSnap = env.expand(snapshotName);
		String expandedVm = env.expand(vm);

		VSphereLogger.vsLogger(jLogger, "Deleting snapshot \""+expandedSnap+"\" of VM "+expandedVm+"...");
		vsphere.deleteSnapshot(expandedVm, expandedSnap, consolidate, failOnNoExist);
		VSphereLogger.vsLogger(jLogger, "Complete.");

		return true;
	}

	@Extension
	public static class DeleteSnapshotDescriptor extends VSphereBuildStepDescriptor {

		@Override
		public String getDisplayName() {
			return Messages.vm_title_DeleteSnapshot();
		}

		public FormValidation doCheckVm(@QueryParameter String value)
				throws IOException, ServletException {

			if (value.length() == 0)
				return FormValidation.error(Messages.validation_required("the VM name"));
			return FormValidation.ok();
		}

		public FormValidation doCheckSnapshotName(@QueryParameter String value)
				throws IOException, ServletException {

			if (value.length() == 0)
				return FormValidation.error(Messages.validation_required("the snapshot name"));
			return FormValidation.ok();
		}

		public FormValidation doTestData(@QueryParameter String serverName,
				@QueryParameter String vm, @QueryParameter String snapshotName) {
			try {

				if (vm.length() == 0 || serverName.length()==0 || snapshotName.length()==0)
					return FormValidation.error(Messages.validation_requiredValues());

				VSphere vsphere = getVSphereCloudByName(serverName).vSphereInstance();

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
