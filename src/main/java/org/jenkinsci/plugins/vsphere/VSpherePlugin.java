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
package org.jenkinsci.plugins.vsphere;

import hudson.Extension;
import hudson.model.AbstractProject;
import hudson.model.Hudson;
import hudson.slaves.Cloud;
import hudson.tasks.BuildStepDescriptor;
import hudson.tasks.Builder;
import hudson.util.ListBoxModel;

import org.jenkinsci.plugins.vSphereCloud;
import org.jenkinsci.plugins.vsphere.tools.VSphereException;

@Extension
public class VSpherePlugin extends Builder {

	@Override
	public DescriptorImpl getDescriptor() {
		// see Descriptor javadoc for more about what a descriptor is.
		return (DescriptorImpl)super.getDescriptor();
	}

	@Extension
	public static final class DescriptorImpl extends BuildStepDescriptor<Builder> {

		@Override
		public boolean isApplicable(Class<? extends AbstractProject> jobType) {
			return false;
		}

		@Override
		public String getDisplayName() {
			return null;
		}

		public DescriptorImpl () {
			load();
		}

		public vSphereCloud getVSphereCloudByName(String serverName) throws RuntimeException, VSphereException {
			if (serverName != null){
				for (Cloud cloud : Hudson.getInstance().clouds) {
					if (cloud instanceof vSphereCloud && ((vSphereCloud) cloud).getVsDescription().equals(serverName)) {
						return (vSphereCloud) cloud;
					}
				}
			}
			throw new RuntimeException(Messages.validation_instanceNotFound());
		}

		public vSphereCloud getVSphereCloudByHash(int hash) throws RuntimeException, VSphereException {
			for (Cloud cloud : Hudson.getInstance().clouds) {
				if (cloud instanceof vSphereCloud && ((vSphereCloud) cloud).getHash()==hash ){
					return (vSphereCloud) cloud;
				}
			}
			throw new RuntimeException(Messages.validation_serverExistence());
		}

		public ListBoxModel doFillServerItems(){
			ListBoxModel select = new ListBoxModel();

			for (Cloud cloud : Hudson.getInstance().clouds) {
				if (cloud instanceof vSphereCloud ){
					select.add( ((vSphereCloud) cloud).getVsDescription()  );
				}
			}

			return select;
		}

		public static DescriptorImpl get() {
			return Builder.all().get(DescriptorImpl.class);
		}

		//TODO Configure this base on config value or system property
		public static boolean allowDelete() {
			return ALLOW_VM_DELETE;
		}

		private static boolean ALLOW_VM_DELETE = true;
	}
}