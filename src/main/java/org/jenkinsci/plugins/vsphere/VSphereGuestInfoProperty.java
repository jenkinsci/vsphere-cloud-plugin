package org.jenkinsci.plugins.vsphere;

import hudson.Extension;
import hudson.model.Describable;
import hudson.model.Descriptor;
import jenkins.model.Jenkins;

import org.kohsuke.stapler.DataBoundConstructor;

/**
 * Represents a name/value pair that's sent to a vSphere virtual machine's
 * "guestinfo" object.
 */
public final class VSphereGuestInfoProperty implements Describable<VSphereGuestInfoProperty> {
    private final String name;
    private final String value;

    @DataBoundConstructor
    public VSphereGuestInfoProperty(String name, String value) {
        this.name = name == null ? "" : name.trim();
        this.value = value;
    }

    public String getName() {
        return name == null ? "" : name.trim();
    }

    public String getValue() {
        return value;
    }

    @Override
    public Descriptor<VSphereGuestInfoProperty> getDescriptor() {
        return Jenkins.getInstance().getDescriptor(getClass());
    }

    @Extension
    public static final class VSphereGuestInfoPropertyDescriptorImpl extends Descriptor<VSphereGuestInfoProperty> {
        @Override
        public String getDisplayName() {
            return null;
        }
    }
}
