package org.jenkinsci.plugins.vsphere;

import static java.util.logging.Level.FINE;
import static java.util.logging.Level.INFO;
import static java.util.logging.Level.WARNING;

import java.io.IOException;
import java.util.Date;
import java.util.concurrent.locks.ReentrantLock;
import java.util.logging.Logger;

import org.jenkinsci.plugins.vSphereCloudSlaveComputer;
import org.kohsuke.accmod.Restricted;
import org.kohsuke.accmod.restrictions.NoExternalUse;
import org.kohsuke.stapler.DataBoundConstructor;

import hudson.model.Descriptor;
import hudson.slaves.AbstractCloudSlave;
import hudson.slaves.OfflineCause;
import hudson.slaves.RetentionStrategy;
import hudson.util.TimeUnit2;

public class VSphereCloudRetentionStrategy extends RetentionStrategy<vSphereCloudSlaveComputer> {
    private transient ReentrantLock checkLock;

    private final int idleMinutes;

    @DataBoundConstructor
    public VSphereCloudRetentionStrategy(int idleMinutes) {
        this.idleMinutes = idleMinutes;
        readResolve();
    }

    /**
     * Try to connect to it ASAP.
     */
    @Override
    public void start(vSphereCloudSlaveComputer c) {
        LOGGER.log(FINE, "start({0})", c.getName());
        c.connect(false);
    }

    @Override
    public long check(vSphereCloudSlaveComputer c) {
        if (disabled) {
            LOGGER.log(FINE, "Skipping check({0}) - disabled", c.getName());
            return 60L;
        }

        if (!checkLock.tryLock()) {
            LOGGER.log(INFO, "Failed to acquire retention lock - skipping check({0})", c.getName());
            return 1;
        }

        final long minutesUntilNextCheck;
        try {
            minutesUntilNextCheck = doCheck(c);
        } finally {
            checkLock.unlock();
        }
        LOGGER.log(FINE, "check({0}) returning {1}", new Object[] { c.getName(), minutesUntilNextCheck } );
        return minutesUntilNextCheck;
    }

    private long doCheck(vSphereCloudSlaveComputer c) {
        if (c.isConnecting()) return 1L; // Do not discard slave while launching for the first time when "idle time" does not make much sense
        if (!c.isIdle() || c.getOfflineCause() instanceof OfflineCause.UserCause) return 1L; // Occupied by user initiated activity

        final AbstractCloudSlave node = c.getNode();
        if (node == null) return 1L; // Node is gone already

        if (idleMinutes <= 0) return 60L; // Keep forever
        final long maxPermittedIdleTimeInMilliseconds = TimeUnit2.MINUTES.toMillis(idleMinutes);

        final long idleSinceTimestamp = c.getIdleStartMilliseconds();
        final long currentIdleTimeInMilliseconds = System.currentTimeMillis() - idleSinceTimestamp;
        final long millisecondsUntilWeHaveBeenIdleForTooLong = maxPermittedIdleTimeInMilliseconds - currentIdleTimeInMilliseconds;
        if (millisecondsUntilWeHaveBeenIdleForTooLong<=0) {
            LOGGER.log(INFO, "Scheduling {0} for termination as it was idle since {1}", new Object[] { c.getName(), new Date(idleSinceTimestamp) } );
            try {
                node.terminate();
                return idleMinutes;
            } catch (InterruptedException e) {
                LOGGER.log(WARNING, "Failed to terminate " + c.getName(), e);
            } catch (IOException e) {
                LOGGER.log(WARNING, "Failed to terminate " + c.getName(), e);
            }
        }
        final long minutesUntilWeHaveBeenIdleForTooLongRoundedDown = TimeUnit2.MINUTES.convert(millisecondsUntilWeHaveBeenIdleForTooLong, TimeUnit2.MILLISECONDS);
        final long minutesUntilNextCheck = Math.max(minutesUntilWeHaveBeenIdleForTooLongRoundedDown, 0L) + 1L;
        return minutesUntilNextCheck;
    }

    public int getIdleMinutes() {
        return idleMinutes;
    }

    @Override
    public DescriptorImpl getDescriptor() {
        return DESCRIPTOR;
    }

    @Restricted(NoExternalUse.class)
    public static final DescriptorImpl DESCRIPTOR = new DescriptorImpl();

    public static final class DescriptorImpl extends Descriptor<RetentionStrategy<?>> {
        @Override
        public String getDisplayName() {
            return "vSphere Keep-Until-Idle Retention Strategy";
        }
    }

    private static final Logger LOGGER = Logger.getLogger(VSphereCloudRetentionStrategy.class.getName());

    private static boolean disabled = Boolean.getBoolean(VSphereCloudRetentionStrategy.class.getName() + ".disabled");

    protected Object readResolve() {
        checkLock = new ReentrantLock(false);
        return this;
    }
}
