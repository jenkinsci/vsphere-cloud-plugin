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

import hudson.DescriptorExtensionList;
import hudson.ExtensionPoint;
import hudson.Launcher;
import hudson.model.BuildListener;
import hudson.model.Describable;
import hudson.model.AbstractBuild;
import hudson.model.Descriptor;
import hudson.model.Hudson;
import hudson.slaves.Cloud;

import org.jenkinsci.plugins.vSphereCloud;
import org.jenkinsci.plugins.vsphere.builders.Messages;
import org.jenkinsci.plugins.vsphere.tools.VSphere;
import org.jenkinsci.plugins.vsphere.tools.VSphereException;

/**
 * Define a base class for all vSphere build steps.  All vSphere build steps should extend 
 * this class, as it contains server information required by all.
 */
public abstract class VSphereBuildStep implements Describable<VSphereBuildStep>, ExtensionPoint {

	protected VSphere vsphere;

	public VSphere getVsphere() {
		return vsphere;
	}

	public void setVsphere(VSphere vsphere) {
		this.vsphere = vsphere;
	}

	public static DescriptorExtensionList<VSphereBuildStep, VSphereBuildStepDescriptor> all() {
		return Hudson.getInstance().getDescriptorList(VSphereBuildStep.class);
	}

	public abstract boolean perform(final AbstractBuild<?, ?> build, final Launcher launcher, final BuildListener listener) throws Exception;

	public VSphereBuildStepDescriptor getDescriptor() {
		return (VSphereBuildStepDescriptor)Hudson.getInstance().getDescriptor(getClass());
	}

	public static abstract class VSphereBuildStepDescriptor extends Descriptor<VSphereBuildStep> {

		protected VSphereBuildStepDescriptor() { }

		protected VSphereBuildStepDescriptor(Class<? extends VSphereBuildStep> clazz) {
			super(clazz);
		}

		public static vSphereCloud getVSphereCloudByName(String serverName) throws RuntimeException, VSphereException {
			if (serverName != null){
				for (vSphereCloud cloud : vSphereCloud.findAllVsphereClouds()) {
					if (cloud.getVsDescription().equals(serverName)) {
						return cloud;
					}
				}
			}
			throw new RuntimeException(Messages.validation_instanceNotFound(serverName));
		}

		public static vSphereCloud getVSphereCloudByHash(int hash) throws RuntimeException, VSphereException {
			for (vSphereCloud cloud : vSphereCloud.findAllVsphereClouds()) {
				if (cloud.getHash()==hash ){
					return cloud;
				}
			}
			throw new RuntimeException(Messages.validation_serverExistence());
		}
	}

	//TODO Configure this base on config value or system property
	public static boolean allowDelete() {
		return ALLOW_VM_DELETE;
	}

	private static boolean ALLOW_VM_DELETE = true;
}
