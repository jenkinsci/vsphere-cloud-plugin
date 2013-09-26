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

import hudson.DescriptorExtensionList;
import hudson.Extension;
import hudson.Launcher;
import hudson.model.BuildListener;
import hudson.model.AbstractBuild;
import hudson.model.AbstractProject;
import hudson.model.Hudson;
import hudson.slaves.Cloud;
import hudson.tasks.BuildStepDescriptor;
import hudson.tasks.Builder;
import hudson.util.ListBoxModel;

import org.jenkinsci.plugins.vSphereCloud;
import org.jenkinsci.plugins.vsphere.VSphereBuildStep;
import org.jenkinsci.plugins.vsphere.VSphereBuildStep.VSphereBuildStepDescriptor;
import org.jenkinsci.plugins.vsphere.tools.VSphere;
import org.jenkinsci.plugins.vsphere.tools.VSphereException;
import org.jenkinsci.plugins.vsphere.tools.VSphereLogger;
import org.kohsuke.stapler.DataBoundConstructor;

public class VSphereBuildStepContainer extends Builder {

    private final VSphereBuildStep buildStep;
    private final String serverName;
	private final int serverHash;
	
    @DataBoundConstructor
    public VSphereBuildStepContainer(final VSphereBuildStep buildStep, final String serverName) throws VSphereException {
        this.buildStep = buildStep;
        this.serverName = serverName;
        this.serverHash = VSphereBuildStep.VSphereBuildStepDescriptor.getVSphereCloudByName(serverName).getHash();
    }

    public VSphereBuildStep getBuildStep() {
        return buildStep;
    }

	@Override
	public boolean perform(final AbstractBuild<?, ?> build, final Launcher launcher, final BuildListener listener)  {
	    try {
	    	
	    	VSphereLogger.vsLogger(listener.getLogger(), Messages.console_usingServerConfig(serverName));
	    	VSphere vsphere = VSphereBuildStep.VSphereBuildStepDescriptor.getVSphereCloudByHash(this.serverHash).vSphereInstance(); 
	    	buildStep.setVsphere(vsphere);
	    	
			return buildStep.perform(build, launcher, listener);
		} catch (Exception e) {
			e.printStackTrace();
		}
		return false;	
	}
	
    @Extension
    public static final class VSphereBuildStepContainerDescriptor extends BuildStepDescriptor<Builder> {
    	
        @Override
        public String getDisplayName() {
            return Messages.plugin_title_BuildStep();
        }

        public DescriptorExtensionList<VSphereBuildStep, VSphereBuildStepDescriptor> getBuildSteps() {
            return VSphereBuildStep.all();
        }

		@Override
		public boolean isApplicable(Class<? extends AbstractProject> jobType) {
			return true;
		}
		
		public ListBoxModel doFillServerNameItems(){
			ListBoxModel select = new ListBoxModel();

			for (Cloud cloud : Hudson.getInstance().clouds) {
				if (cloud instanceof vSphereCloud ){
					select.add( ((vSphereCloud) cloud).getVsDescription()  );
				}
			}

			return select;
		}
    }

}