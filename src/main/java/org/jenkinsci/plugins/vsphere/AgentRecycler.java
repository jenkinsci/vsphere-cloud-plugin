package org.jenkinsci.plugins.vsphere;

import hudson.Extension;
import hudson.ExtensionList;
import hudson.model.Computer;
import hudson.model.Node;
import hudson.model.PeriodicWork;
import hudson.model.Queue;
import hudson.slaves.OfflineCause;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.LongSupplier;
import java.util.logging.Level;
import java.util.logging.Logger;
import jenkins.model.Jenkins;
import jenkins.util.Timer;
import org.jenkinsci.plugins.vSphereCloudSlave;
import org.jenkinsci.plugins.vsphere.AgentUsageRegistry.RecycleOutcome;
import org.jenkinsci.plugins.vsphere.AgentUsageRegistry.RecycleToken;
import org.kohsuke.accmod.Restricted;
import org.kohsuke.accmod.restrictions.NoExternalUse;

/**
 * Recycles the agents that have used all the builds they are allowed, once nothing is using them: disconnects them,
 * which does to their machines what the agents are set to do when idle, and starts their count again when that is done.
 * <p>
 * An agent is looked at soon after a run that was counted on it ends, and all of them every minute. An agent that is
 * not online, or was taken offline by someone else, is left alone. A recycle that does not work is tried again later,
 * further and further apart, and the agent is left at its limit meanwhile.
 */
@Restricted(NoExternalUse.class)
@Extension
public final class AgentRecycler {

    private static final Logger LOGGER = Logger.getLogger(AgentRecycler.class.getName());

    private static final long SWEEP_PERIOD_MS =
            Long.getLong(AgentRecycler.class.getName() + ".sweepPeriodMs", TimeUnit.MINUTES.toMillis(1));

    private static final long FIRST_BACKOFF_MS = TimeUnit.MINUTES.toMillis(1);
    private static final long LAST_BACKOFF_MS = TimeUnit.MINUTES.toMillis(30);

    /** Recycles that did not work, by agent: how many in a row and when to try again. */
    private static final class Failures {
        int count;
        long notBefore;
    }

    private final ConcurrentMap<String, Failures> failures = new ConcurrentHashMap<>();

    /** Nothing has been looked at since Jenkins started. */
    private final AtomicBoolean firstSweep = new AtomicBoolean(true);

    /** The time, which tests move. */
    private volatile LongSupplier clock = System::currentTimeMillis;

    /** How long after a run ends the agent is looked at, so that the executor of the run is done by then. */
    private volatile long checkDelayMs = Long.getLong(AgentRecycler.class.getName() + ".checkDelayMs", 5_000L);

    /** How long to wait for the disconnect to say that it is done. */
    private volatile long recycleTimeoutMs =
            Long.getLong(AgentRecycler.class.getName() + ".recycleTimeoutMs", TimeUnit.MINUTES.toMillis(10));

    public static AgentRecycler get() {
        return ExtensionList.lookupSingleton(AgentRecycler.class);
    }

    public void setClock(LongSupplier clock) {
        this.clock = clock;
    }

    public void setCheckDelayMs(long checkDelayMs) {
        this.checkDelayMs = checkDelayMs;
    }

    /** Looks at the agent soon: it may have just become free. */
    public void scheduleCheck(String node) {
        try {
            Timer.get().schedule(() -> checkQuietly(node), checkDelayMs, TimeUnit.MILLISECONDS);
        } catch (RuntimeException e) {
            // Jenkins is stopping. The sweep after the next start finds the agent.
            LOGGER.log(Level.FINE, "Could not schedule a look at " + node, e);
        }
    }

    private void checkQuietly(String node) {
        try {
            check(node);
        } catch (RuntimeException e) {
            LOGGER.log(Level.WARNING, "Failed to look whether the vSphere agent " + node + " is to be recycled", e);
        }
    }

    /** Looks at every vSphere agent and starts recycling those that are due. Returns their names. */
    public Set<String> sweepOnce() {
        final Set<String> started = new LinkedHashSet<>();
        final Jenkins jenkins = Jenkins.getInstanceOrNull();
        if (jenkins == null) {
            return started;
        }
        try {
            // A run that cannot be found any more would hold its agent for ever.
            AgentUsageRegistry.get().pruneFinishedRuns();
        } catch (RuntimeException e) {
            LOGGER.log(Level.WARNING, "Failed to forget the runs of the vSphere agents that are over", e);
        }
        final boolean first = firstSweep.compareAndSet(true, false);
        for (Node node : jenkins.getNodes()) {
            if (node instanceof vSphereCloudSlave) {
                final String name = node.getNodeName();
                try {
                    if (first) {
                        clearLeftOver(node);
                    }
                    if (check(name)) {
                        started.add(name);
                    }
                } catch (RuntimeException e) {
                    LOGGER.log(
                            Level.WARNING,
                            "Failed to look whether the vSphere agent " + name + " is to be recycled",
                            e);
                }
            }
        }
        return started;
    }

    /**
     * Left by a recycle that Jenkins was stopped in the middle of, which nobody is going to end. Only at the first
     * look after Jenkins starts, when no recycle can be under way.
     */
    private static void clearLeftOver(Node node) {
        final Computer computer = node.toComputer();
        if (computer != null
                && computer.isTemporarilyOffline()
                && computer.getOfflineCause() instanceof LimitedBuildsOfflineCause) {
            LOGGER.log(Level.INFO, "Putting the vSphere agent {0} back in service after an interrupted recycle", node);
            computer.setTemporarilyOffline(false, null);
        }
    }

    /** Starts recycling the agent if it is due, and says whether it did. The recycling itself is done on another thread. */
    public boolean check(String name) {
        final Jenkins jenkins = Jenkins.getInstanceOrNull();
        final Node node = jenkins == null ? null : jenkins.getNode(name);
        if (!(node instanceof vSphereCloudSlave)
                || ((vSphereCloudSlave) node).getRetentionStrategy() instanceof RunOnceCloudRetentionStrategy) {
            return false;
        }
        final AgentUsageRegistry registry = AgentUsageRegistry.get();
        final Computer computer = node.toComputer();
        if (computer == null) {
            return false;
        }
        final int limit = ((vSphereCloudSlave) node).getLimitedTestRunCount();
        if (!registry.isRetiring(name, limit)) {
            return false;
        }
        final Failures failed = failures.get(name);
        if (failed != null && clock.getAsLong() < failed.notBefore) {
            return false;
        }
        // Not connected, or taken offline by someone else: there is nothing to disconnect, or it is not ours to.
        if (!computer.isOnline()) {
            return false;
        }
        final RecycleToken token;
        try {
            // Only the decision is taken holding the lock of the queue.
            token = Queue.withLock((Callable<RecycleToken>)
                    () -> computer.countBusy() == 0 ? registry.tryBeginRecycle(name, limit) : null);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
        if (token == null) {
            return false;
        }
        // What the agent is marked offline with, then disconnected with: only these are ours to take away.
        final LimitedBuildsOfflineCause marked = new LimitedBuildsOfflineCause();
        final LimitedBuildsOfflineCause disconnecting =
                new LimitedBuildsOfflineCause(Messages._agentRecycler_OfflineReason_Disconnect());
        try {
            computer.setTemporarilyOffline(true, marked);
            // Offline, nothing more is handed to the agent. What it got before that is not for us to disconnect.
            if (computer.countBusy() > 0) {
                end(name, computer, token, false, false, marked, disconnecting);
                scheduleCheck(name);
                return false;
            }
            Computer.threadPoolForRemoting.execute(() -> recycle(name, computer, token, marked, disconnecting));
        } catch (RuntimeException e) {
            end(name, computer, token, false, true, marked, disconnecting);
            throw e;
        }
        return true;
    }

    private void recycle(
            String name,
            Computer computer,
            RecycleToken token,
            LimitedBuildsOfflineCause marked,
            LimitedBuildsOfflineCause disconnecting) {
        boolean completed = false;
        try {
            LOGGER.log(Level.INFO, "Recycling the vSphere agent {0}: it has used all its builds", name);
            computer.disconnect(disconnecting);
            completed = token.done().get(recycleTimeoutMs, TimeUnit.MILLISECONDS) == RecycleOutcome.COMPLETED;
            if (!completed) {
                LOGGER.log(Level.WARNING, "Recycling the vSphere agent {0} failed", name);
            }
        } catch (TimeoutException e) {
            LOGGER.log(Level.WARNING, "Recycling the vSphere agent {0} did not say it was done in time", name);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (ExecutionException | RuntimeException e) {
            LOGGER.log(Level.WARNING, "Recycling the vSphere agent " + name + " failed", e);
        } finally {
            end(name, computer, token, completed, !completed, marked, disconnecting);
        }
    }

    /**
     * The recycle is over. It {@code reset} the usage if it worked, and {@code failed} (which is waited for before
     * trying again) if it did not; one that was given up before it began is neither.
     */
    private void end(
            String name,
            Computer computer,
            RecycleToken token,
            boolean reset,
            boolean failed,
            LimitedBuildsOfflineCause marked,
            LimitedBuildsOfflineCause disconnecting) {
        try {
            // Before the agent can be looked at again, or the next look would not know to wait.
            if (reset) {
                failures.remove(name);
            } else if (failed) {
                final Failures failure = failures.computeIfAbsent(name, n -> new Failures());
                failure.count++;
                final long backoff = Math.min(LAST_BACKOFF_MS, FIRST_BACKOFF_MS << Math.min(failure.count - 1, 20));
                failure.notBefore = clock.getAsLong() + backoff;
            }
            final Jenkins jenkins = Jenkins.getInstanceOrNull();
            if (jenkins != null && jenkins.getNode(name) == null) {
                failures.remove(name);
            }
            // Only what we set: whoever took the agent offline after us, or a recycle that began after this one, keeps
            // it.
            final OfflineCause now = computer.getOfflineCause();
            if (computer.isTemporarilyOffline() && (now == marked || now == disconnecting)) {
                computer.setTemporarilyOffline(false, null);
            }
            AgentUsageRegistry.get().endRecycle(token, reset);
        } catch (RuntimeException e) {
            LOGGER.log(Level.WARNING, "Failed to finish recycling the vSphere agent " + name, e);
        }
    }

    /**
     * What is done to the machine of an agent when it is disconnected is over (or went wrong). Said by the launcher
     * every time it disconnects, which can be more than once: only what comes while an agent is being recycled counts.
     */
    public static void idleActionDone(String node, RecycleOutcome outcome) {
        try {
            AgentUsageRegistry.get().completeRecycle(node, outcome);
        } catch (RuntimeException e) {
            // Nothing is waiting for it if there is no Jenkins to wait.
            LOGGER.log(Level.FINE, "Could not say that the vSphere agent " + node + " was disconnected", e);
        }
    }

    /** Looks at all the agents once in a while: the end of a run is not the only way for an agent to become free. */
    @Extension
    public static final class Sweep extends PeriodicWork {
        @Override
        public long getRecurrencePeriod() {
            return SWEEP_PERIOD_MS;
        }

        @Override
        protected void doRun() {
            get().sweepOnce();
        }
    }
}
