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

import com.vmware.vim25.mo.VirtualMachine;
import hudson.*;
import hudson.model.*;
import hudson.tasks.BuildStepMonitor;
import hudson.util.FormValidation;
import jenkins.tasks.SimpleBuildStep;
import org.jenkinsci.plugins.vsphere.VSphereBuildStep;
import org.jenkinsci.plugins.vsphere.tools.VSphere;
import org.jenkinsci.plugins.vsphere.tools.VSphereException;
import org.jenkinsci.plugins.vsphere.tools.VSphereLogger;
import org.kohsuke.stapler.AncestorInPath;
import org.kohsuke.stapler.DataBoundConstructor;
import org.kohsuke.stapler.QueryParameter;
import org.kohsuke.stapler.interceptor.RequirePOST;

import edu.umd.cs.findbugs.annotations.NonNull;

import java.io.IOException;
import java.io.PrintStream;
import java.util.Collection;

public class RenameSnapshot extends VSphereBuildStep implements SimpleBuildStep {

    private final String vm;
	private final String oldName;
	private final String newName;
    private final String newDescription;

	@DataBoundConstructor
	public RenameSnapshot(String vm, String oldName, String newName, String newDescription) throws VSphereException {
        this.vm = vm;
		this.oldName = oldName;
		this.newName = newName;
        this.newDescription = newDescription;
	}

    public String getVm() {
        return vm;
    }

	public String getOldName() {
		return oldName;
	}

    public String getNewName() {
        return newName;
    }

    public String getNewDescription() {
        return newDescription;
    }

	@Override
	public void perform(@NonNull Run<?, ?> run, @NonNull FilePath filePath, @NonNull Launcher launcher, @NonNull TaskListener listener) throws InterruptedException, IOException {
		try {
			renameSnapshot(run, launcher, listener);
		} catch (Exception e) {
			throw new AbortException(e.getMessage());
		}
	}

	@Override
	public boolean prebuild(AbstractBuild<?, ?> abstractBuild, BuildListener buildListener) {
		return false;
	}

	@Override
	public boolean perform(final AbstractBuild<?, ?> build, final Launcher launcher, final BuildListener listener)  {
		boolean retVal = false;
		try {
			retVal = renameSnapshot(build, launcher, listener);
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

	public boolean renameSnapshot(final Run<?, ?> run, final Launcher launcher, final TaskListener listener) throws VSphereException {

        PrintStream jLogger = listener.getLogger();
		String expandedVm = vm;
		String expandedOldName = oldName;
		String expandedNewName = newName;
		String expandedNewDescription = newDescription;
        EnvVars env;
        try {
            env = run.getEnvironment(listener);
        } catch (Exception e) {
            throw new VSphereException(e);
        }
		if (run instanceof AbstractBuild) {
			env.overrideAll(((AbstractBuild) run).getBuildVariables()); // Add in matrix axes..
			expandedVm = env.expand(vm);
			expandedOldName = env.expand(oldName);
			expandedNewName = env.expand(newName);
			expandedNewDescription = env.expand(newDescription);
		}

        VSphereLogger.vsLogger(jLogger, "Renaming snapshot of VM \""+expandedVm+"\" from \""+expandedOldName+"\" to \"" + expandedNewName + "\" with description \""+ expandedNewDescription +"\". Please wait ...");
		try {
        vsphere.renameVmSnapshot(expandedVm, expandedOldName, expandedNewName, expandedNewDescription);
		} catch (Exception e) {
			throw new VSphereException(e);
		}
        VSphereLogger.vsLogger(jLogger, "Renamed!");

        return true;
	}

	@Extension
	public static final class RenameSnapshotDescriptor extends VSphereBuildStepDescriptor {

		public RenameSnapshotDescriptor() {
			load();
		}

        public FormValidation doCheckVm(@QueryParameter String value) {
            if (value.length() == 0)
                return FormValidation.error(Messages.validation_required("the VM name"));
            return FormValidation.ok();
        }

		public FormValidation doCheckOldName(@QueryParameter String value) {
			if (value.length() == 0)
				return FormValidation.error(Messages.validation_required("the VM snapshot name"));
			return FormValidation.ok();
		}

		@Override
		public String getDisplayName() {
			return Messages.vm_title_RenameSnapshot();
		}

        @RequirePOST
		public FormValidation doTestData(@AncestorInPath Item context,
                @QueryParameter String serverName,
                @QueryParameter String vm,
				@QueryParameter String oldName,
                @QueryParameter String newName) {
            throwUnlessUserHasPermissionToConfigureJob(context);
			try {

				if (serverName.length() == 0 || oldName.length()==0 || newName.length()==0 )
					return FormValidation.error(Messages.validation_requiredValues());

				VSphere vsphere = getVSphereCloudByName(serverName, null).vSphereInstance();

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
