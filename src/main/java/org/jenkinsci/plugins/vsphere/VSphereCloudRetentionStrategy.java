package org.jenkinsci.plugins.vsphere;

import edu.umd.cs.findbugs.annotations.CheckForNull;
import edu.umd.cs.findbugs.annotations.NonNull;
import hudson.Extension;
import hudson.model.Descriptor;
import hudson.model.DescriptorVisibilityFilter;
import hudson.slaves.AbstractCloudComputer;
import hudson.slaves.AbstractCloudSlave;
import hudson.slaves.CloudRetentionStrategy;
import hudson.slaves.RetentionStrategy;

import java.io.IOException;
import java.util.logging.Level;
import java.util.logging.Logger;

import static java.util.concurrent.TimeUnit.*;
import javax.annotation.concurrent.GuardedBy;

import org.kohsuke.accmod.Restricted;
import org.kohsuke.accmod.restrictions.NoExternalUse;
import org.kohsuke.stapler.DataBoundConstructor;

public class VSphereCloudRetentionStrategy extends CloudRetentionStrategy {

    private static final Logger LOGGER = Logger.getLogger(VSphereCloudRetentionStrategy.class.getName());

    private final int idleMinutes;

    @DataBoundConstructor
    public VSphereCloudRetentionStrategy(int idleMinutes) {
        super(idleMinutes);
        this.idleMinutes = idleMinutes;
    }

    public int getIdleMinutes() {
        return idleMinutes;
    }

    @Override
    @GuardedBy("hudson.model.Queue.lock")
    public long check(final AbstractCloudComputer c) {
        if (VSphereNodeReconcileWork.shouldNodeBeRetained(c)) {
            LOGGER.log(Level.FINE, "Keeping {0} to meet minimum requirements", c.getName());
            return 1;
        }
        return super.check(c);
    }

    @Override
    public DescriptorImpl getDescriptor() {
        return DESCRIPTOR;
    }

    @Restricted(NoExternalUse.class)
    @Extension
    public static final DescriptorImpl DESCRIPTOR = new DescriptorImpl();

    public static final class DescriptorImpl extends Descriptor<RetentionStrategy<?>> {
        @Override
        public String getDisplayName() {
            return "vSphere Keep-Until-Idle Retention Strategy";
        }
    }

    @Extension
    public static class DescriptorVisibilityFilterImpl extends DescriptorVisibilityFilter {
        @Override
        public boolean filter(@CheckForNull Object context, @NonNull Descriptor descriptor) {
            return !(descriptor instanceof DescriptorImpl);
        }
    }

    private Object readResolve() {
        // without this, super.idleMinutes is not restored from persistence
        return new VSphereCloudRetentionStrategy(idleMinutes);
    }
}
