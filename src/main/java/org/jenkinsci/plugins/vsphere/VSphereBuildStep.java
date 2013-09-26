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
 * Define a condition that can be used to decide whether to run a BuildStep or not.
 * 
 * A Run condition should not make any changes to the build or the build environment.
 * If the information that is required to make the decision is not available, then the RunCondition should
 * either explicitly throw an exception (or just allow one to be thrown) rather than handling it and trying to decide whether the build
 * should run based on bad data. This allows a user to choose what should happen - which could be different in different contexts.
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
		return Hudson.getInstance().<VSphereBuildStep, VSphereBuildStepDescriptor>getDescriptorList(VSphereBuildStep.class);
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
				for (Cloud cloud : Hudson.getInstance().clouds) {
					if (cloud instanceof vSphereCloud && ((vSphereCloud) cloud).getVsDescription().equals(serverName)) {
						return (vSphereCloud) cloud;
					}
				}
			}
			throw new RuntimeException(Messages.validation_instanceNotFound());
		}
		
		public static vSphereCloud getVSphereCloudByHash(int hash) throws RuntimeException, VSphereException {
			for (Cloud cloud : Hudson.getInstance().clouds) {
				if (cloud instanceof vSphereCloud && ((vSphereCloud) cloud).getHash()==hash ){
					return (vSphereCloud) cloud;
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
