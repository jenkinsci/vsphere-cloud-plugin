package org.jenkinsci.plugins;

import hudson.Extension;
import hudson.model.Descriptor;
import hudson.slaves.RetentionStrategy;
import org.kohsuke.stapler.DataBoundConstructor;

public class vSphereAlwaysRetentionStrategy extends vSphereRetentionStrategy {
    
    @DataBoundConstructor
    public vSphereAlwaysRetentionStrategy() {
    }

    public long check(vSphereCloudSlaveComputer c) {
        if (c.isOffline() && !c.isConnecting() && c.isLaunchSupported()) {
            c.tryReconnect();
        }
        return 1;
    }

    @Extension(ordinal = 100)
    public static class DescriptorImpl extends Descriptor<RetentionStrategy<?>> {

        public String getDisplayName() {
            return "";
        }
    }

}
