package org.jenkinsci.plugins.vsphere;

import hudson.model.Computer;
import hudson.model.Queue;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.logging.Level;
import java.util.logging.Logger;
import org.jenkinsci.plugins.vSphereCloudSlave;
import org.kohsuke.accmod.Restricted;
import org.kohsuke.accmod.restrictions.NoExternalUse;

/**
 * Whether an agent is draining: it is online, its limit keeps it from taking new builds, and nothing that is ready to
 * run can be given to it. Jenkins counts the free executors of an agent that accepts tasks as capacity, whatever
 * {@code canTake} says about each item, so such an agent has to stop accepting them or no other one is provisioned for
 * the builds that wait.
 * <p>
 * It is not draining as long as anything in the queue can be given to it (the next {@code node {}} block of a Pipeline
 * that is counted on it, for instance), which {@code canTake} decides as it always does.
 * <p>
 * Each computer has its own, which remembers what it found the last time.
 */
@Restricted(NoExternalUse.class)
public final class DrainingCheck {

    private static final Logger LOGGER = Logger.getLogger(DrainingCheck.class.getName());

    /** How long what was found is used again, as long as the same items are ready to run. */
    public static final long DEFAULT_KEPT_MS = 1_000L;

    private static volatile long keptMs = DEFAULT_KEPT_MS;

    /** What a look through the queue found, which holds for the items that were ready then and for a moment. */
    private record Look(List<Long> readyItems, long at, boolean refusesAll) {}

    private final AtomicReference<Look> lastLook = new AtomicReference<>();

    /** For tests. */
    public static void setKeptMs(long keptMs) {
        DrainingCheck.keptMs = keptMs;
    }

    /** Never throws: when in doubt the agent is not draining, so that it accepts tasks. */
    public boolean isDraining(vSphereCloudSlave agent, Computer computer) {
        try {
            // An agent that is offline has to accept tasks to be launched when it is needed.
            return computer.isOnline() && UsageGate.limitState(agent) != null && refusesAllReadyItems(agent);
        } catch (RuntimeException e) {
            LOGGER.log(Level.FINE, "Failed to find out whether " + agent.getNodeName() + " is draining", e);
            return false;
        }
    }

    /**
     * Jenkins asks very often (for each item of the queue that it shows, for one) and {@code canTake} is not cheap, so
     * what it said is used again for a moment instead of asking it about every item each time. Only for the very same
     * items, so that one that has just become ready to run is seen at once.
     */
    private boolean refusesAllReadyItems(vSphereCloudSlave agent) {
        final List<Queue.BuildableItem> ready = Queue.getInstance().getBuildableItems();
        final List<Long> ids = ready.stream().map(Queue.Item::getId).toList();
        final long now = System.nanoTime();
        final Look last = lastLook.get();
        if (last != null
                && now - last.at() < TimeUnit.MILLISECONDS.toNanos(keptMs)
                && last.readyItems().equals(ids)) {
            return last.refusesAll();
        }
        // canTake must not ask the computer whether it accepts tasks, or this would call itself.
        final boolean refusesAll = ready.stream().allMatch(item -> agent.canTake(item) != null);
        lastLook.set(new Look(ids, now, refusesAll));
        return refusesAll;
    }
}
