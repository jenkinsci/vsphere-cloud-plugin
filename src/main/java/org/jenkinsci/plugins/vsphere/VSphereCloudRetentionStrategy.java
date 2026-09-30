package org.jenkinsci.plugins.vsphere;

import edu.umd.cs.findbugs.annotations.CheckForNull;
import edu.umd.cs.findbugs.annotations.NonNull;
import hudson.Extension;
import hudson.model.Descriptor;
import hudson.model.DescriptorVisibilityFilter;
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
import org.kohsuke.stapler.DataBoundSetter;

import static java.util.logging.Level.WARNING;

/**
 * Retains a cloud computer until it has been idle for {@link #getIdleMinutes()} minutes (that part
 * is delegated straight to {@link CloudRetentionStrategy}), OR -- optionally -- until it has existed
 * for {@link #getLifespanMinutes()} minutes in total, whichever happens first. For example, "keep a
 * computer around until it's been idle for an hour, or two days have passed, whichever comes first".
 *
 * <p>{@code lifespanMinutes} defaults to 0, which disables the age-based cap entirely and reproduces
 * this plugin's original keep-until-idle-only behaviour exactly, so existing configurations are
 * unaffected unless they opt in.
 *
 * <p>This subsumes what used to be a separate "fixed lifespan" retention strategy evaluated originally
 * in pull request https://github.com/jenkinsci/vsphere-cloud-plugin/pull/96/ : rather than make
 * the user choose one or the other, both conditions are available together on this one strategy.
 */
public class VSphereCloudRetentionStrategy extends CloudRetentionStrategy {

    private static final Logger LOGGER = Logger.getLogger(VSphereCloudRetentionStrategy.class.getName());

    /** time allowance to be idle, before a computer is retired; 0 is most aggressive (means retire
     *  right after the first check that says the computer is idle). */
    private final int idleMinutes;

    /** the maximum age, in minutes, before a computer is retired regardless of idleness; 0 means unlimited. */
    private int lifespanMinutes;

    // check() is documented @GuardedBy("hudson.model.Queue.lock") on RetentionStrategy itself, so all
    // calls to it (for every computer, not just this one) are already serialized by the caller -- the
    // reads of atEndOfLife within a single check() call can't race with a concurrent write from
    // another check() call, and synchronizing here would add nothing beyond what Queue.lock already
    // gives us. isAcceptingTasks(), however, is reached via Computer#isAcceptingTasks(), which is NOT
    // documented as lock-guarded and so may run on a different thread while check() (holding Queue.lock)
    // concurrently flips this flag. volatile is the right tool for that: it guarantees the other thread
    // promptly sees the update, without implying (as `synchronized` would) that mutual exclusion is
    // needed here too.
    /** Flag raised when we successfully call {@link AbstractCloudComputer#disconnect} with a descriptive
     *  {@link VSphereOfflineCause}, and the agent is left to complete its currently running executions.
     *  As soon as it is fully idle, and this flag is raised, that computer is ultimately terminated.
     */
    private transient volatile boolean atEndOfLife;

    @DataBoundConstructor
    public VSphereCloudRetentionStrategy(int idleMinutes) {
        super(idleMinutes);
        this.idleMinutes = idleMinutes;
    }

    /** @see #idleMinutes */
    public int getIdleMinutes() {
        return idleMinutes;
    }

    @DataBoundSetter
    public void setLifespanMinutes(int lifespanMinutes) {
        this.lifespanMinutes = lifespanMinutes;
    }

    /** @return the maximum age, in minutes, before a computer is retired regardless of idleness; 0 means unlimited. */
    public int getLifespanMinutes() {
        return lifespanMinutes;
    }

    /**
     * Whether a computer created {@code ageMillis} milliseconds ago has exceeded a lifespan of
     * {@code lifespanMinutes} minutes. A non-positive {@code lifespanMinutes} never counts as
     * exceeded -- that's what "0 disables the lifespan cap" means.
     */
    static boolean isPastLifespan(long ageMillis, int lifespanMinutes) {
        return lifespanMinutes > 0 && ageMillis > TimeUnit.MINUTES.toMillis(lifespanMinutes);
    }

    @Override
    public long check(final AbstractCloudComputer c) {
        if (lifespanMinutes <= 0) {
            return super.check(c);
        }

        if (atEndOfLife) {
            if (c.isIdle()) {
                terminate(c);
            }
            return 1; // at end of life: check frequently so we notice idleness promptly
        }

        final CloudComputerCreatedOnInvisibleAction created = c.getAction(CloudComputerCreatedOnInvisibleAction.class);
        if (created != null) {
            final long ageMillis = System.currentTimeMillis() - created.getCreationTimeMillis();
            if (isPastLifespan(ageMillis, lifespanMinutes)) {
                final String cname = c.getName();
                LOGGER.log(Level.FINE, "Will terminate {0} once idle - lifespan of {1} minutes reached.", new Object[] { cname, lifespanMinutes });
                final VSphereOfflineCause cause = new VSphereOfflineCause(Messages._vSphereCloudRetentionStrategy_OfflineReason_LifespanReached(String.valueOf(lifespanMinutes)));
                try {
                    c.disconnect(cause).get();
                    // Only latch atEndOfLife once the disconnect has actually gone through -- otherwise
                    // a transient failure here would permanently prevent any further disconnect attempts
                    // (isAcceptingTasks() would keep reporting "not accepting tasks" forever, but nothing
                    // would actually work towards terminating the agent).
                    atEndOfLife = true;
                } catch (InterruptedException | ExecutionException e) {
                    LOGGER.log(WARNING, "Failed to disconnect " + cname + "; will retry next check", e);
                }
            }
        }

        // Still short of the lifespan cap (or just reached it this cycle): defer to the ordinary
        // keep-until-idle behaviour too, so a computer that goes idle well before its lifespan is up
        // still gets retired for that reason, exactly as it would with lifespanMinutes left at 0.
        return super.check(c);
    }

    private void terminate(AbstractCloudComputer c) {
        final AbstractCloudSlave computerNode = c.getNode();
        if (computerNode != null) {
            try {
                LOGGER.log(Level.FINER, "Initiating termination of {0}.", c.getName());
                computerNode.terminate();
            } catch (InterruptedException | IOException e) {
                LOGGER.log(WARNING, "Failed to terminate " + c.getName(), e);
            }
        }
    }

    @Override
    public boolean isAcceptingTasks(AbstractCloudComputer c) {
        return !atEndOfLife;
    }

    @Override
    public void start(AbstractCloudComputer c) {
        c.addAction(new CloudComputerCreatedOnInvisibleAction(System.currentTimeMillis()));
        super.start(c); // CloudRetentionStrategy.start() calls c.connect(false)
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

    Object readResolve() {
        // without this, super.idleMinutes is not restored from persistence
        final VSphereCloudRetentionStrategy copy = new VSphereCloudRetentionStrategy(idleMinutes);
        copy.lifespanMinutes = lifespanMinutes;
        return copy;
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
