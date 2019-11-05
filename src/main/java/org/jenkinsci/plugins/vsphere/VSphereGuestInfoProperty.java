package org.jenkinsci.plugins.vsphere;

import hudson.Extension;
import hudson.model.Describable;
import hudson.model.Descriptor;
import hudson.util.FormValidation;
import jenkins.model.Jenkins;

import org.kohsuke.stapler.DataBoundConstructor;
import org.kohsuke.stapler.QueryParameter;

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

    @SuppressWarnings("unchecked")
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

        public FormValidation doCheckName(@QueryParameter String name) {
            if (name == null || name.isEmpty()) {
                return FormValidation.error("Must not be empty.");
            }
            final String acceptableCharacters = "a-zA-Z0-9_.-";
            final String regex = "[" + acceptableCharacters + "]+";
            if (!name.matches(regex)) {
                return FormValidation.error("Unacceptable characters.  Use only [" + acceptableCharacters + "].");
            }
            return FormValidation.ok();
        }
    }
}
