package org.jenkinsci.plugins.vsphere;

import com.google.common.annotations.VisibleForTesting;
import edu.umd.cs.findbugs.annotations.CheckForNull;
import hudson.model.Cause;
import hudson.model.Computer;
import hudson.model.Executor;
import hudson.model.Queue;
import hudson.model.Run;
import hudson.model.queue.CauseOfBlockage;
import hudson.model.queue.SubTask;
import hudson.model.queue.WorkUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.logging.Level;
import java.util.logging.Logger;
import jenkins.util.SystemProperties;
import org.jenkinsci.plugins.vSphereCloudSlave;
import org.kohsuke.accmod.Restricted;
import org.kohsuke.accmod.restrictions.NoExternalUse;

/**
 * Keeps new work away from an agent that has used all the builds it is allowed, so that it can finish what it has and
 * be recycled, without anyone being aborted for coming too late.
 * <p>
 * What counts is the usage of the agent as it will be once the work already handed to it has started: the queue hands
 * out several items in one go, and a run is counted only when it starts on the executor, so asking the registry alone
 * would let more than the limit in.
 */
@Restricted(NoExternalUse.class)
public final class UsageGate {

    private static final Logger LOGGER = Logger.getLogger(UsageGate.class.getName());

    /** The first failure is a warning; the queue asks again at every pass, so the next ones would fill the log. */
    private static final AtomicBoolean REPORTED = new AtomicBoolean();

    /**
     * How long an agent that has used all its builds, and has nothing to do at all but for a run counted on it that has
     * not ended, waits before it takes work again, beyond its limit and uncounted, until that run ends. Such a run may
     * be waiting for a build that needs this very agent (a lock, or a {@code waitUntil} that depends on another build,
     * outside any {@code node {}}), which cannot be told from any other new build. Off (negative) unless set, as it
     * gives up the clean machine that the limit is for.
     */
    private static volatile long idleHeldMs = SystemProperties.getLong(UsageGate.class.getName() + ".idleHeldMs", -1L);

    /** How long an agent is held idle before the log says so, and between one time and the next. */
    private static final long REPORT_IDLE_AFTER_MS = TimeUnit.MINUTES.toMillis(10);

    private static final ConcurrentMap<String, Long> LAST_REPORTED = new ConcurrentHashMap<>();

    private UsageGate() {}

    /**
     * Why the agent should not be given the item, or null if it can. Called by the queue for every item and every
     * agent, with no way to recover from an exception, so it never throws: when in doubt the item is let through.
     */
    @CheckForNull
    public static CauseOfBlockage check(vSphereCloudSlave agent, Queue.BuildableItem item) {
        try {
            return decide(agent, item);
        } catch (RuntimeException e) {
            LOGGER.log(
                    REPORTED.compareAndSet(false, true) ? Level.WARNING : Level.FINE,
                    "Failed to find out whether " + agent.getNodeName() + " can take more builds",
                    e);
            return null;
        }
    }

    @CheckForNull
    private static CauseOfBlockage decide(vSphereCloudSlave agent, Queue.BuildableItem item) {
        final String name = agent.getNodeName();
        final int limit = agent.getLimitedTestRunCount();
        if (limit <= 0) {
            return null;
        }
        final AgentUsageRegistry registry = AgentUsageRegistry.get();
        if (registry.isRecycling(name)) {
            return CauseOfBlockage.fromMessage(Messages._usageGate_Recycling(name));
        }
        final Computer computer = agent.toComputer();
        if (computer == null) {
            return null;
        }
        // Nothing can be handed to an agent that is offline, and refusing would keep it from being launched on demand.
        // Unless a run counted on it keeps it from being recycled: it would be launched, refuse the work, be
        // disconnected for being idle (by its retention strategy) and be launched again, over and over.
        if (computer.isOffline() && !(registry.isRetiring(name, limit) && registry.holdsCountedRuns(name))) {
            return null;
        }
        // What the agent will have used is at most what it has and one more for each executor that is busy, so with
        // room for all of those there is nothing to look into.
        if (registry.count(name) + computer.countBusy() < limit) {
            return null;
        }
        // Work that goes on from before a restart is not new.
        if (ContinuedTasks.isContinued(item.task)) {
            return null;
        }
        final CauseOfBlockage refusal = refusalFor(name, limit, computer, item, registry);
        // Something that a run on this agent started, and may be waiting for: refusing it would have them wait for
        // each other for ever. It runs beyond the limit, as it did before new work was kept away.
        if (refusal == null || isStartedByARunOn(name, item, registry) || isHeldIdle(name, computer, registry)) {
            return null;
        }
        return refusal;
    }

    /** For tests. */
    @VisibleForTesting
    public static void setIdleHeldMs(long idleHeldMs) {
        UsageGate.idleHeldMs = idleHeldMs;
    }

    /**
     * The agent has nothing to do at all, and has not for long enough, only because of the runs counted on it that have
     * not ended. Said in the log, once in a while, when it is for long and nothing is done about it.
     */
    private static boolean isHeldIdle(String name, Computer computer, AgentUsageRegistry registry) {
        if (!computer.isIdle() || !registry.holdsCountedRuns(name)) {
            return false;
        }
        final long start = computer.getIdleStartMilliseconds();
        // An agent with no executors has no start.
        if (start == Long.MIN_VALUE) {
            return false;
        }
        final long now = System.currentTimeMillis();
        final long idle = now - start;
        final long held = idleHeldMs;
        if (held >= 0 && idle >= held) {
            LOGGER.log(Level.FINE, "{0} is idle and kept from being recycled by a counted run: taking work", name);
            return true;
        }
        if (idle >= REPORT_IDLE_AFTER_MS) {
            final Long last = LAST_REPORTED.get(name);
            if (last == null || now - last >= REPORT_IDLE_AFTER_MS) {
                LAST_REPORTED.put(name, now);
                LOGGER.log(
                        Level.WARNING,
                        "{0} has used all its builds and has had nothing to do for {1} minutes, as it cannot be"
                                + " recycled while these runs have not ended: {2}. The builds that need it wait.",
                        new Object[] {name, TimeUnit.MILLISECONDS.toMinutes(idle), registry.countedRunIds(name)});
            }
        }
        return false;
    }

    @CheckForNull
    private static CauseOfBlockage refusalFor(
            String name, int limit, Computer computer, Queue.BuildableItem item, AgentUsageRegistry registry) {
        // The run has to be found before the registry is asked anything about it.
        final String runId = runIdOf(item.task);
        if (runId != null) {
            if (registry.isCountedOn(runId, name)) {
                return null;
            }
            if (registry.isCounted(runId)) {
                // Another block of a run that is counted on another agent. If this one is waiting to be recycled it
                // is not for it to take more, unless it is waiting for that very run, which would then wait for it.
                return registry.isRetiring(name, limit) && !registry.holdsCountedRuns(name)
                        ? CauseOfBlockage.fromMessage(Messages._usageGate_LimitReached(name, limit))
                        : null;
            }
        }
        final List<String> busy = busyRuns(computer);
        // The run is already on its way to this agent, only not counted yet.
        if (runId != null && busy.contains(runId)) {
            return null;
        }
        // A run that has just ended is released while its executor still holds it, so for the one pass of the queue it
        // is counted again here; at worst a build is refused once more than it needed to be.
        return registry.projectedCount(name, busy) >= limit
                ? CauseOfBlockage.fromMessage(Messages._usageGate_LimitReached(name, limit))
                : null;
    }

    /** How far up the runs that started one another to look. */
    private static final int MAX_UPSTREAM_DEPTH = 10;

    /**
     * The item was started, however far up, by a run that is counted on the agent and still building: by the item
     * itself (a build of a job), or by the run that it is a block of (a block of a Pipeline is queued with no cause).
     */
    private static boolean isStartedByARunOn(String agent, Queue.BuildableItem item, AgentUsageRegistry registry) {
        List<Cause> causes = new ArrayList<>(item.getCauses());
        final Queue.Executable owner = item.task.getOwnerExecutable();
        if (owner instanceof Run) {
            causes.addAll(((Run<?, ?>) owner).getCauses());
        }
        for (int depth = 0; depth < MAX_UPSTREAM_DEPTH && !causes.isEmpty(); depth++) {
            final List<Cause> next = new ArrayList<>();
            for (Cause cause : causes) {
                if (cause instanceof Cause.UpstreamCause) {
                    final Cause.UpstreamCause upstream = (Cause.UpstreamCause) cause;
                    // By its id, and not the run: finding it could load a build from the disk, and one that is counted
                    // has not ended.
                    final String runId = upstream.getUpstreamProject() + "#" + upstream.getUpstreamBuild();
                    if (registry.isCountedOn(runId, agent)) {
                        return true;
                    }
                    next.addAll(upstream.getUpstreamCauses());
                }
            }
            causes = next;
        }
        return false;
    }

    /** The run that the task is a part of, or null if it is not known to be part of one (a new build of a job). */
    @CheckForNull
    private static String runIdOf(Queue.Task task) {
        final Queue.Executable owner = task.getOwnerExecutable();
        return owner instanceof Run ? ((Run<?, ?>) owner).getExternalizableId() : null;
    }

    /**
     * As {@link #runIdOf(Queue.Task)}, for a part of a task. Not through {@code getOwnerTask}, which for the block of a
     * Pipeline is the job: a job is not part of any run, so every block would be taken for a build of its own.
     */
    @CheckForNull
    private static String runIdOf(SubTask part) {
        return part instanceof Queue.Task ? runIdOf((Queue.Task) part) : runIdOf(part.getOwnerTask());
    }

    /**
     * The run of the work in each busy executor, with a null for work that is not known to be of any. An executor that
     * was handed work but has not started it yet has a work unit and no executable.
     */
    private static List<String> busyRuns(Computer computer) {
        final List<String> busy = new ArrayList<>();
        for (Executor executor : computer.getExecutors()) {
            // As the queue does (JENKINS-28840), not to touch an executor that is being interrupted, which can be
            // waiting for the lock of the queue: it is busy, with work that is not known to be of any run.
            if (executor.isInterrupted()) {
                busy.add(null);
                continue;
            }
            final WorkUnit unit = executor.getCurrentWorkUnit();
            if (unit == null) {
                continue;
            }
            final Queue.Executable executable = executor.getCurrentExecutable();
            if (executable instanceof Run) {
                busy.add(((Run<?, ?>) executable).getExternalizableId());
            } else if (executable != null) {
                busy.add(runIdOf(executable.getParent()));
            } else {
                busy.add(runIdOf(unit.work));
            }
        }
        return busy;
    }
}
