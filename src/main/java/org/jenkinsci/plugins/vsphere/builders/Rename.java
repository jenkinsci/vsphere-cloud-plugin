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

import com.vmware.vim25.mo.VirtualMachine;
import hudson.EnvVars;
import hudson.Extension;
import hudson.Launcher;
import hudson.model.AbstractBuild;
import hudson.model.BuildListener;
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

public class Rename extends VSphereBuildStep {

	private final String oldName;
	private final String newName;

	@DataBoundConstructor
	public Rename(String oldName, String newName) throws VSphereException {
		this.oldName = oldName;
		this.newName = newName;
	}

	public String getOldName() {
		return oldName;
	}

    public String getNewName() {
        return newName;
    }

	public boolean perform(final AbstractBuild<?, ?> build, final Launcher launcher, final BuildListener listener) throws VSphereException  {

        PrintStream jLogger = listener.getLogger();
        EnvVars env;
        try {
            env = build.getEnvironment(listener);
        } catch (Exception e) {
            throw new VSphereException(e);
        }
        env.overrideAll(build.getBuildVariables()); // Add in matrix axes..
        String expandedOldName = env.expand(oldName);
        String expandedNewName = env.expand(newName);

        VSphereLogger.vsLogger(jLogger, "Renaming VM \""+expandedOldName+".\" to \"" + expandedNewName + "\" Please wait ...");
        vsphere.renameVm(expandedOldName, expandedNewName);
        VSphereLogger.vsLogger(jLogger, "Renamed!");

        return true;
	}

	@Extension
	public static final class RenameDescriptor extends VSphereBuildStepDescriptor {

		public RenameDescriptor() {
			load();
		}

		public FormValidation doCheckOldName(@QueryParameter String value)
				throws IOException, ServletException {

			if (value.length() == 0)
				return FormValidation.error(Messages.validation_required("the VM name"));
			return FormValidation.ok();
		}

		@Override
		public String getDisplayName() {
			return Messages.vm_title_Rename();
		}

		public FormValidation doTestData(@QueryParameter String serverName,
				@QueryParameter String oldName,
                @QueryParameter String newName) {
			try {

				if (serverName.length() == 0 || oldName.length()==0 || newName.length()==0 )
					return FormValidation.error(Messages.validation_requiredValues());

				VSphere vsphere = getVSphereCloudByName(serverName).vSphereInstance();

				VirtualMachine vmObj = vsphere.getVmByName(oldName);
				if (vmObj == null)
					return FormValidation.error(Messages.validation_notFound("VM"));

				return FormValidation.ok(Messages.validation_success());
			} catch (Exception e) {
				throw new RuntimeException(e);
			}
		}
	}
}
