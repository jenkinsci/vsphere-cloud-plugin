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
import com.vmware.vim25.mo.VirtualMachineSnapshot;
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

public class Deploy extends VSphereBuildStep {

	private final String template;
	private final String clone;
	private final boolean linkedClone;
	private final String resourcePool;
	private final String cluster;
    private final String datastore;
	private final boolean powerOn;

	@DataBoundConstructor
	public Deploy(String template, String clone, boolean linkedClone,
			String resourcePool, String cluster, String datastore, boolean powerOn) throws VSphereException {
		this.template = template;
		this.clone = clone;
		this.linkedClone = linkedClone;
		this.resourcePool=resourcePool;
		this.cluster=cluster;
        this.datastore=datastore;
		this.powerOn = powerOn;
	}

	public String getTemplate() {
		return template;
	}

	public String getClone() {
		return clone;
	}

	public boolean isLinkedClone() {
		return linkedClone;
	}

	public String getCluster() {
		return cluster;
	}

	public String getResourcePool() {
		return resourcePool;
	}

    public String getDatastore() {
        return datastore;
    }

	public boolean isPowerOn() {
		return powerOn;
	}

	public boolean perform(final AbstractBuild<?, ?> build, final Launcher launcher, final BuildListener listener) throws VSphereException {
		return deployFromTemplate(build, launcher, listener);
		//TODO throw AbortException instead of returning value
	}

	private boolean deployFromTemplate(final AbstractBuild<?, ?> build, final Launcher launcher, final BuildListener listener) throws VSphereException {
		PrintStream jLogger = listener.getLogger();

		EnvVars env;
		try {
			env = build.getEnvironment(listener);
		} catch (Exception e) {
			throw new VSphereException(e);
		}

		env.overrideAll(build.getBuildVariables()); // Add in matrix axes..
		String expandedClone = env.expand(clone), expandedTemplate = env.expand(template),
                expandedCluster = env.expand(cluster), expandedDatastore = env.expand(datastore);

        String resourcePoolName;
        if (resourcePool.length() == 0) {
            // Not all installations are using resource pools. But there is always a hidden "Resources" resource
            // pool, even if not visible in the vSphere Client.
            resourcePoolName = "Resources";
        } else {
            resourcePoolName = env.expand(resourcePool);
        }

        vsphere.deployVm(expandedClone, expandedTemplate, linkedClone, resourcePoolName, expandedCluster,
				expandedDatastore, powerOn, jLogger);
		VSphereLogger.vsLogger(jLogger, "\""+expandedClone+"\" successfully deployed!");

		return true;
	}

	@Extension
	public static final class DeployDescriptor extends VSphereBuildStepDescriptor {

		public DeployDescriptor() {
			load();
		}

		@Override
		public String getDisplayName() {
			return Messages.vm_title_Deploy();
		}

		public FormValidation doCheckTemplate(@QueryParameter String value)
				throws IOException, ServletException {
			if (value.length() == 0)
				return FormValidation.error("Please enter the template name");
			return FormValidation.ok();
		}

		public FormValidation doCheckClone(@QueryParameter String value)
				throws IOException, ServletException {
			if (value.length() == 0)
				return FormValidation.error(Messages.validation_required("the clone name"));
			return FormValidation.ok();
		}

		public FormValidation doCheckResourcePool(@QueryParameter String value)
				throws IOException, ServletException {
			return FormValidation.ok();
		}

		public FormValidation doCheckCluster(@QueryParameter String value)
				throws IOException, ServletException {
			if (value.length() == 0)
				return FormValidation.error(Messages.validation_required("the cluster"));
			return FormValidation.ok();
		}

		public FormValidation doTestData(@QueryParameter String serverName,
				@QueryParameter String template, @QueryParameter String clone,
				@QueryParameter String resourcePool, @QueryParameter String cluster) {
			try {
				if (template.length() == 0 || clone.length()==0 || serverName.length()==0
						|| cluster.length()==0 )
					return FormValidation.error(Messages.validation_requiredValues());

				VSphere vsphere = getVSphereCloudByName(serverName).vSphereInstance();

				//TODO what if clone name is variable?
				VirtualMachine cloneVM = vsphere.getVmByName(clone);
				if (cloneVM != null)
					return FormValidation.error(Messages.validation_exists("clone"));

				if (template.indexOf('$') >= 0)
					return FormValidation.warning(Messages.validation_buildParameter("template"));

				VirtualMachine vm = vsphere.getVmByName(template);      
				if (vm == null)
					return FormValidation.error(Messages.validation_notFound("template"));

				if(!vm.getConfig().template)
					return FormValidation.error(Messages.validation_notActually("template"));

				VirtualMachineSnapshot snap = vm.getCurrentSnapShot();
				if (snap == null)
					return FormValidation.error(Messages.validation_noSnapshots());

				return FormValidation.ok(Messages.validation_success());
			} catch (Exception e) {
				throw new RuntimeException(e);
			}
		}
	}
}
