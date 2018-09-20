package org.jenkinsci.plugins.vsphere;

import hudson.model.Descriptor;
import hudson.model.InvisibleAction;
import hudson.slaves.AbstractCloudComputer;
import hudson.slaves.AbstractCloudSlave;
import hudson.slaves.CloudRetentionStrategy;
import hudson.slaves.RetentionStrategy;
import hudson.util.TimeUnit2;

import java.io.IOException;
import java.util.concurrent.ExecutionException;
import java.util.logging.Level;
import java.util.logging.Logger;

import org.kohsuke.accmod.Restricted;
import org.kohsuke.accmod.restrictions.NoExternalUse;
import org.kohsuke.stapler.DataBoundConstructor;

import static java.util.logging.Level.WARNING;

public final class FixedLifespanCloudRetentionStrategy extends RetentionStrategy<AbstractCloudComputer> {

    private static final Logger LOGGER = Logger.getLogger(CloudRetentionStrategy.class.getName());

    private final int lifespanMinutes;

    @DataBoundConstructor
    public FixedLifespanCloudRetentionStrategy(int lifespanMinutes) {
        this.lifespanMinutes = lifespanMinutes;
    }

    public int getLifespanMinutes() {
        return lifespanMinutes;
    }

    @Override
    public long check(AbstractCloudComputer c) {
        final long creationTimeMillis = c.getAction(CloudComputerCreatedOnInvisibleAction.class).getCreationTimeMillis();
        final long ageMillis = System.currentTimeMillis() - creationTimeMillis;
        final String cname = c.getName();
        if (!isAtEndOfLife() && ageMillis > TimeUnit2.MINUTES.toMillis(lifespanMinutes)) {
            LOGGER.log(Level.FINE, "Will terminate {0} once idle - lifespan of {1} minutes reached.", new Object[] { cname, lifespanMinutes });
            setAtEndOfLife();
            final VSphereOfflineCause cause = new VSphereOfflineCause(Messages._fixedLifespanCloudRetentionStrategy_OfflineReason_LifespanReached(String.valueOf(lifespanMinutes)));
            try {
                c.disconnect(cause).get();
            } catch (InterruptedException | ExecutionException e) {
                LOGGER.log(WARNING, "Failed to disconnect " + cname, e);
            }
        }
        if (isAtEndOfLife() && c.isIdle()) {
            final AbstractCloudSlave computerNode = c.getNode();
            if (computerNode != null) {
                try {
                    LOGGER.log(Level.FINER, "Initiating termination of {0}.", cname);
                    computerNode.terminate();
                } catch (InterruptedException | IOException e) {
                    LOGGER.log(WARNING, "Failed to terminate " + cname, e);
                }
            }
        }
        return 1; // re-check in 1 minute
    }

    @Override
    public DescriptorImpl getDescriptor() {
        return DESCRIPTOR;
    }

    @Override
    public void start(AbstractCloudComputer c) {
        c.addAction(new CloudComputerCreatedOnInvisibleAction(System.currentTimeMillis()));
        super.start(c);
        c.connect(false);
    }

    @Override
    public boolean isAcceptingTasks(AbstractCloudComputer c) {
        return !isAtEndOfLife();
    }

    private transient boolean atEndOfLife;

    private synchronized boolean isAtEndOfLife() {
        return atEndOfLife;
    }

    private synchronized void setAtEndOfLife() {
        atEndOfLife = true;
    }

    @Restricted(NoExternalUse.class)
    public static final DescriptorImpl DESCRIPTOR = new DescriptorImpl();

    public static final class DescriptorImpl extends Descriptor<RetentionStrategy<?>> {
        @Override
        public String getDisplayName() {
            return "vSphere Fixed Lifespan Retention Strategy";
        }
    }

    public static class CloudComputerCreatedOnInvisibleAction extends InvisibleAction {
        private final long creationTimeMillis;

        CloudComputerCreatedOnInvisibleAction(long creationTimeMillis) {
            this.creationTimeMillis = creationTimeMillis;
        }

        long getCreationTimeMillis() {
            return creationTimeMillis;
        }
    }
}
