package org.jenkinsci.plugins.vsphere;

import hudson.model.Descriptor;
import hudson.model.InvisibleAction;
import hudson.slaves.AbstractCloudComputer;
import hudson.slaves.AbstractCloudSlave;
import hudson.slaves.CloudRetentionStrategy;
import hudson.slaves.RetentionStrategy;

import java.io.IOException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;
import java.util.logging.Logger;

import org.kohsuke.accmod.Restricted;
import org.kohsuke.accmod.restrictions.NoExternalUse;
import org.kohsuke.stapler.DataBoundConstructor;

import static java.util.logging.Level.WARNING;

public final class FixedLifespanCloudRetentionStrategy extends RetentionStrategy<AbstractCloudComputer> {

    private static final Logger LOGGER = Logger.getLogger(CloudRetentionStrategy.class.getName());

    private final int lifespanMinutes;
    private transient boolean atEndOfLife;

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

        if (!isAtEndOfLife()) {
            final long lifespanMillis = TimeUnit.MINUTES.toMillis(lifespanMinutes);
            if (ageMillis <= lifespanMillis) {
                // Not yet at end of life: no point polling every minute, just wait until the
                // deadline is actually due.
                return Math.max(1, TimeUnit.MILLISECONDS.toMinutes(lifespanMillis - ageMillis));
            }
            LOGGER.log(Level.FINE, "Will terminate {0} once idle - lifespan of {1} minutes reached.", new Object[] { cname, lifespanMinutes });
            final VSphereOfflineCause cause = new VSphereOfflineCause(Messages._fixedLifespanCloudRetentionStrategy_OfflineReason_LifespanReached(String.valueOf(lifespanMinutes)));
            try {
                c.disconnect(cause).get();
                // Only latch atEndOfLife once the disconnect has actually gone through -- otherwise
                // a transient failure here would permanently prevent any further disconnect attempts
                // (isAcceptingTasks() would keep reporting "not accepting tasks" forever, but nothing
                // would actually work towards terminating the agent).
                setAtEndOfLife();
            } catch (InterruptedException | ExecutionException e) {
                LOGGER.log(WARNING, "Failed to disconnect " + cname + "; will retry next check", e);
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
        return 1; // at or past end of life: check frequently so we notice idleness promptly
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
