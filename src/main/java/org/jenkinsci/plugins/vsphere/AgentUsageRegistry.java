package org.jenkinsci.plugins.vsphere;

import hudson.Extension;
import hudson.ExtensionList;
import hudson.XmlFile;
import hudson.init.InitMilestone;
import hudson.init.Initializer;
import hudson.init.Terminator;
import hudson.model.Node;
import hudson.model.Run;
import hudson.security.ACL;
import hudson.security.ACLContext;
import hudson.util.XStream2;
import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.function.Predicate;
import java.util.function.ToIntFunction;
import java.util.logging.Level;
import java.util.logging.Logger;
import jenkins.model.Jenkins;
import jenkins.util.Timer;
import org.jenkinsci.plugins.vSphereCloudSlave;
import org.kohsuke.accmod.Restricted;
import org.kohsuke.accmod.restrictions.NoExternalUse;

/**
 * Owns how many "limited builds" each vSphere agent has done, so that the agent can be recycled once it reaches its
 * limit. All the agents of all the clouds share this one registry, which is keyed by the name of the agent (Jenkins
 * keeps those unique).
 * <p>
 * An agent that is replaced when its configuration is saved keeps its usage; one that is deleted, or created anew with
 * a name that was used before, starts from nothing. What a run is counted against is remembered per <em>entry</em>, not
 * per name, so a run that was counted on an agent that has since been replaced by a different one of the same name does
 * not recycle the new one.
 * <p>
 * Everything here happens in memory under one lock and calls nothing back into Jenkins, so it is safe to use from
 * the threads of any build. Recycling an agent is up to the caller, outside of that lock.
 */
@Restricted(NoExternalUse.class)
@Extension
public final class AgentUsageRegistry {

    /** What happened when a run asked to be counted. */
    public enum Outcome {
        /** The agent has no limit, so nothing is tracked. */
        UNTRACKED,
        /** The run is over already; counting it would never be undone. */
        NOT_BUILDING,
        /** The run is counted already, on this or on another agent. */
        ALREADY_COUNTED,
        /** The run is counted now. */
        COUNTED,
        /** The agent is at its limit, the run is not counted. */
        OVER_LIMIT
    }

    /** What happened when a run that may have been counted was over. */
    public enum Released {
        /** The run was not counted: nothing changes. */
        NOT_COUNTED,
        /** The run was counted on an agent that is not the one with that name any more. */
        STALE,
        /** The run is not counted any more. Its agent is not reset: that is for when it has been recycled. */
        RELEASED
    }

    /** How the recycling of an agent ended. */
    public enum RecycleOutcome {
        /** The agent was disconnected and what is done to its machine then was done. */
        COMPLETED,
        /** Something went wrong, or nobody said what became of it. */
        FAILED
    }

    /** The recycling of an agent that is under way, which only one thing at a time can do. */
    public static final class RecycleToken {
        private final Entry entry;
        private final CompletableFuture<RecycleOutcome> done = new CompletableFuture<>();

        private RecycleToken(Entry entry) {
            this.entry = entry;
        }

        /** Completes when the disconnect of the agent has done what it does to its machine. */
        public CompletableFuture<RecycleOutcome> done() {
            return done;
        }
    }

    /** The outcome and the usage of the agent right after. */
    public record Acquired(Outcome outcome, int count) {}

    /** The usage of one incarnation of an agent. Runs refer to it, so it tells a replaced agent from its successor. */
    private static final class Entry {
        String name;
        int count;
        RecycleToken recycle;

        Entry(String name) {
            this.name = name;
        }
    }

    /**
     * What is saved: the usage of the agents, and the ids of the runs counted on them (run id to agent name), so that a
     * Pipeline that goes on after a restart is known to be counted. Files saved before the runs were are still read.
     */
    private static final class Usage {
        Map<String, Integer> counts = new TreeMap<>();
        Map<String, String> runs = new TreeMap<>();
    }

    /** Reads and writes {@link Usage} under a name of its own: moving or renaming the class keeps the file readable. */
    private static final XStream2 XSTREAM = new XStream2();

    static {
        XSTREAM.alias("usage", Usage.class);
    }

    private static final Logger LOGGER = Logger.getLogger(AgentUsageRegistry.class.getName());
    private static final String STORE_NAME = "vsphere-cloud-usage.xml";

    private final Object lock = new Object();
    private final Map<String, Entry> usages = new HashMap<>();
    private final Map<String, Entry> countedRuns = new HashMap<>();

    /** Where the usage is saved, or null if it is not. */
    private final File store;

    /** Asks for a save to be made soon. */
    private final Consumer<Runnable> scheduler;

    /** A save has been asked for and has not started yet. */
    private final AtomicBoolean savePending = new AtomicBoolean();

    private final Object saveLock = new Object();

    /** Jenkins is stopping. */
    private volatile boolean terminated;

    /** The registry of Jenkins, which saves the usage in the home of Jenkins. */
    public AgentUsageRegistry() {
        this(
                new File(Jenkins.get().getRootDir(), STORE_NAME),
                save -> Timer.get().schedule(save, 1, TimeUnit.SECONDS));
    }

    AgentUsageRegistry(File store, Consumer<Runnable> scheduler) {
        this.store = store;
        this.scheduler = scheduler;
    }

    /** A registry that is not saved anywhere. */
    static AgentUsageRegistry inMemory() {
        return new AgentUsageRegistry(null, save -> {});
    }

    public static AgentUsageRegistry get() {
        return ExtensionList.lookupSingleton(AgentUsageRegistry.class);
    }

    /**
     * Counts the run against the agent, unless the agent has no limit, the run is over, it is counted already or the
     * agent is at its limit.
     */
    public Acquired acquire(String node, int limit, Run<?, ?> run) {
        return acquire(node, limit, run, run::isBuilding);
    }

    /** As {@link #acquire(String, int, Run)}, with the "is the run still building" question made by the caller. */
    Acquired acquire(String node, int limit, Run<?, ?> run, BooleanSupplier stillBuilding) {
        return acquire(node, limit, run.getExternalizableId(), stillBuilding);
    }

    /** As {@link #acquire(String, int, Run, BooleanSupplier)}, with the run known by its id. */
    Acquired acquire(String node, int limit, String runId, BooleanSupplier stillBuilding) {
        if (limit <= 0) {
            return new Acquired(Outcome.UNTRACKED, 0);
        }
        synchronized (lock) {
            // Asked while holding the lock: a run that ends in between would otherwise be counted for good.
            if (!stillBuilding.getAsBoolean()) {
                return new Acquired(Outcome.NOT_BUILDING, countOf(node));
            }
            if (countedRuns.containsKey(runId)) {
                return new Acquired(Outcome.ALREADY_COUNTED, countOf(node));
            }
            final Entry entry = usages.computeIfAbsent(node, Entry::new);
            if (entry.count >= limit) {
                return new Acquired(Outcome.OVER_LIMIT, entry.count);
            }
            entry.count++;
            countedRuns.put(runId, entry);
            changed();
            return new Acquired(Outcome.COUNTED, entry.count);
        }
    }

    /** The run is over: it is not counted as using its agent any more, which keeps the use it was counted for. */
    public Released release(Run<?, ?> run) {
        return release(run.getExternalizableId());
    }

    /** As {@link #release(Run)}, with the run known by its id. */
    public Released release(String runId) {
        synchronized (lock) {
            final Entry entry = countedRuns.remove(runId);
            if (entry == null) {
                return Released.NOT_COUNTED;
            }
            changed();
            return usages.get(entry.name) == entry ? Released.RELEASED : Released.STALE;
        }
    }

    /** The agent has used all its {@code limit} uses (none means never). Whether it is busy is another matter. */
    public boolean isRetiring(String node, int limit) {
        synchronized (lock) {
            return limit > 0 && limit <= countOf(node);
        }
    }

    /** Some run counted on the agent has not ended. */
    public boolean holdsCountedRuns(String node) {
        synchronized (lock) {
            final Entry entry = usages.get(node);
            return entry != null && hasCountedRuns(entry);
        }
    }

    /**
     * Takes the recycling of the agent, if it is due: it has used all its {@code limit} uses, no run counted on it is
     * still using it, and it is not being recycled already. Null if not. The caller has to end it with
     * {@link #endRecycle}.
     */
    public RecycleToken tryBeginRecycle(String node, int limit) {
        synchronized (lock) {
            final Entry entry = usages.get(node);
            if (entry == null || limit <= 0 || entry.count < limit || entry.recycle != null || hasCountedRuns(entry)) {
                return null;
            }
            entry.recycle = new RecycleToken(entry);
            return entry.recycle;
        }
    }

    /** The run is counted on the agent as it is now, not on an earlier one of the same name. */
    public boolean isCountedOn(String runId, String node) {
        synchronized (lock) {
            final Entry entry = countedRuns.get(runId);
            return entry != null && usages.get(node) == entry;
        }
    }

    /** The run is counted on some agent. */
    public boolean isCountedAnywhere(String runId) {
        return isCounted(runId);
    }

    /**
     * How many uses the agent will have once the work it was handed has started: what it has, and one for each run
     * among the {@code busyRunIds} that is not counted anywhere yet. A null is work that is not known to be of any run,
     * which has to be taken as a use of its own.
     */
    public int projectedCount(String node, Collection<String> busyRunIds) {
        synchronized (lock) {
            final Set<String> uncounted = new HashSet<>();
            int unknown = 0;
            for (String runId : busyRunIds) {
                if (runId == null) {
                    unknown++;
                } else if (!countedRuns.containsKey(runId)) {
                    uncounted.add(runId);
                }
            }
            return countOf(node) + unknown + uncounted.size();
        }
    }

    public boolean isRecycling(String node) {
        synchronized (lock) {
            final Entry entry = usages.get(node);
            return entry != null && entry.recycle != null;
        }
    }

    /**
     * Says how the recycling of the agent went. Only the first to say it counts, so it may be said any number of
     * times. The recycling goes on until {@link #endRecycle}. Returns whether this was the first.
     */
    public boolean completeRecycle(String node, RecycleOutcome outcome) {
        final RecycleToken token;
        synchronized (lock) {
            final Entry entry = usages.get(node);
            token = entry == null ? null : entry.recycle;
        }
        return token != null && token.done.complete(outcome);
    }

    /**
     * The recycling is over. If it {@code reset}, the agent starts from nothing again, unless it was replaced in the
     * meantime by a different one of the same name.
     */
    public void endRecycle(RecycleToken token, boolean reset) {
        synchronized (lock) {
            if (token.entry.recycle == token) {
                token.entry.recycle = null;
            }
            if (reset && usages.get(token.entry.name) == token.entry) {
                token.entry.count = 0;
                changed();
            }
        }
    }

    /** The name of the agent the run is counted on, or null if it is not counted. */
    public String nodeOf(Run<?, ?> run) {
        return nodeOf(run.getExternalizableId());
    }

    String nodeOf(String runId) {
        synchronized (lock) {
            final Entry entry = countedRuns.get(runId);
            return entry == null ? null : entry.name;
        }
    }

    public boolean isCounted(Run<?, ?> run) {
        return isCounted(run.getExternalizableId());
    }

    public boolean isCounted(String runId) {
        synchronized (lock) {
            return countedRuns.containsKey(runId);
        }
    }

    public int count(String node) {
        synchronized (lock) {
            return countOf(node);
        }
    }

    /** Starts the agent from nothing again, as if it were new. The runs counted on it keep counting on it. */
    public void reset(String node) {
        synchronized (lock) {
            final Entry entry = usages.get(node);
            if (entry != null) {
                entry.count = 0;
                changed();
            }
        }
    }

    /** The agent is gone, or a new one takes its name: forget it. The runs counted on it become stale. */
    public void forget(String node) {
        synchronized (lock) {
            final Entry entry = usages.remove(node);
            if (entry != null) {
                abandonRecycle(entry);
                changed();
            }
        }
    }

    /** Nobody is going to say how the recycling of an agent that is gone went: it did not. */
    private static void abandonRecycle(Entry entry) {
        if (entry.recycle != null) {
            entry.recycle.done.complete(RecycleOutcome.FAILED);
        }
    }

    /** The agent was renamed: its usage and the runs counted on it go with it. */
    public void rename(String from, String to) {
        synchronized (lock) {
            final Entry entry = usages.remove(from);
            if (entry != null) {
                entry.name = to;
                usages.put(to, entry);
                changed();
            }
        }
    }

    /**
     * The limit of the agent may have changed. With no limit there is nothing to remember. An agent that is at or over
     * its new limit keeps its count: it is recycled once nothing is using it.
     */
    public void reconcile(String node, int limit) {
        synchronized (lock) {
            final Entry entry = usages.get(node);
            if (entry == null) {
                return;
            }
            if (limit <= 0) {
                usages.remove(node);
                abandonRecycle(entry);
                changed();
            }
        }
    }

    /** A copy of the usage of the agents that have any. */
    public Map<String, Integer> snapshot() {
        synchronized (lock) {
            final Map<String, Integer> copy = new HashMap<>();
            usages.forEach((name, entry) -> {
                if (entry.count > 0) {
                    copy.put(name, entry.count);
                }
            });
            return copy;
        }
    }

    /** The usage of the agents changed: have it saved soon. A burst of changes makes one save. */
    private void changed() {
        // Once Jenkins is stopping, the timer that would do the save is about to be shut down.
        if (store != null && !terminated && savePending.compareAndSet(false, true)) {
            try {
                scheduler.accept(() -> {
                    savePending.set(false);
                    save();
                });
            } catch (RuntimeException e) {
                savePending.set(false);
                LOGGER.log(Level.WARNING, "Failed to schedule saving the usage of the vSphere agents", e);
            }
        }
    }

    /** Jenkins is stopping: saves what there is and no more saves are asked for. */
    void terminate() {
        terminated = true;
        save();
    }

    /** What is to be saved, as of now. */
    private Usage capture() {
        final Usage usage = new Usage();
        synchronized (lock) {
            usages.forEach((name, entry) -> {
                // An entry without a name cannot be put back to an agent; it must not stop the others.
                if (name != null && entry.count > 0) {
                    usage.counts.put(name, entry.count);
                }
            });
            countedRuns.forEach((runId, entry) -> {
                // A run counted on an agent that was replaced since says nothing about the one that has its name now.
                if (entry.name != null && usages.get(entry.name) == entry && entry.count > 0) {
                    usage.runs.put(runId, entry.name);
                }
            });
        }
        return usage;
    }

    /** Writes the usage of the agents. Writes are one after the other, each one with the usage as of its start. */
    void save() {
        if (store == null) {
            return;
        }
        synchronized (saveLock) {
            try {
                new XmlFile(XSTREAM, store).write(capture());
            } catch (IOException | RuntimeException e) {
                LOGGER.log(Level.WARNING, "Failed to save the usage of the vSphere agents to " + store, e);
            }
        }
    }

    /**
     * Takes the usage saved by an earlier run of Jenkins. An agent that does not exist any more is not taken, nor is
     * one that has no limit any more. One that is at its limit is taken if runs counted on it were saved, as those may
     * go on after the restart; when none was, nothing is using it and it would never be recycled, so it starts again.
     */
    void load(Predicate<String> nodeExists, ToIntFunction<String> limitOf) {
        if (store == null || !store.exists()) {
            return;
        }
        final Usage saved;
        try {
            final Object read = new XmlFile(XSTREAM, store).read();
            if (!(read instanceof Usage)) {
                return;
            }
            saved = (Usage) read;
        } catch (IOException | RuntimeException e) {
            LOGGER.log(Level.WARNING, "Failed to read the usage of the vSphere agents from " + store, e);
            return;
        }
        if (saved.counts == null) {
            return;
        }
        synchronized (lock) {
            saved.counts.forEach((name, count) -> {
                if (count == null || count <= 0 || !nodeExists.test(name)) {
                    return;
                }
                final int limit = limitOf.applyAsInt(name);
                final List<String> runIds = new ArrayList<>();
                if (saved.runs != null) {
                    saved.runs.forEach((runId, node) -> {
                        if (name.equals(node)) {
                            runIds.add(runId);
                        }
                    });
                }
                if (limit <= 0 || (count >= limit && runIds.isEmpty())) {
                    return;
                }
                final Entry entry = usages.computeIfAbsent(name, Entry::new);
                entry.count = count;
                runIds.forEach(runId -> countedRuns.put(runId, entry));
            });
        }
    }

    /**
     * Forgets the runs that are not building: one that did not get to say it was over (the job is gone, the build could
     * not be resumed) would hold its agent for ever. An agent at its limit that is left with no run starts again.
     */
    void pruneRuns(Predicate<String> stillBuilding) {
        pruneRuns(stillBuilding, null);
    }

    /**
     * As {@link #pruneRuns(Predicate)}, and an agent at its limit that is left with no run starts again (it has no
     * {@code limitOf} to know that with the other).
     */
    void pruneRuns(Predicate<String> stillBuilding, ToIntFunction<String> limitOf) {
        final List<String> ids;
        synchronized (lock) {
            ids = new ArrayList<>(countedRuns.keySet());
        }
        // Asking about a run goes into Jenkins, which is not done holding the lock.
        final Set<String> over = new HashSet<>();
        for (String id : ids) {
            if (!stillBuilding.test(id)) {
                over.add(id);
            }
        }
        final Set<String> names = new HashSet<>();
        synchronized (lock) {
            for (String id : over) {
                final Entry entry = countedRuns.get(id);
                if (entry != null) {
                    names.add(entry.name);
                }
            }
        }
        final Map<String, Integer> limits = new HashMap<>();
        if (limitOf != null) {
            for (String name : names) {
                limits.put(name, limitOf.applyAsInt(name));
            }
        }
        synchronized (lock) {
            boolean modified = false;
            for (String id : over) {
                if (countedRuns.remove(id) != null) {
                    modified = true;
                }
            }
            for (Map.Entry<String, Integer> limit : limits.entrySet()) {
                final Entry entry = usages.get(limit.getKey());
                if (entry != null
                        && limit.getValue() > 0
                        && entry.count >= limit.getValue()
                        && !hasCountedRuns(entry)) {
                    entry.count = 0;
                    modified = true;
                }
            }
            if (modified) {
                changed();
            }
        }
    }

    /** Takes the saved usage once the agents are known. */
    @Initializer(after = InitMilestone.SYSTEM_CONFIG_LOADED)
    public static void loadAtStartup() {
        get().load(name -> Jenkins.get().getNode(name) instanceof vSphereCloudSlave, AgentUsageRegistry::limitOf);
    }

    /** Once the builds are loaded and resumed, the runs that were saved as counted are the ones still building. */
    @Initializer(after = InitMilestone.JOB_CONFIG_ADAPTED, before = InitMilestone.COMPLETED)
    public static void pruneAtStartup() {
        get().pruneRuns(AgentUsageRegistry::isBuilding, AgentUsageRegistry::limitOf);
    }

    /** Forgets the counted runs that are not building any more, which did not get to say they were over. */
    public void pruneFinishedRuns() {
        pruneRuns(AgentUsageRegistry::isBuilding);
    }

    private static boolean isBuilding(String runId) {
        // Without permission to see them, the runs would be taken for gone.
        try (ACLContext ignored = ACL.as2(ACL.SYSTEM2)) {
            final Run<?, ?> run = Run.fromExternalizableId(runId);
            return run != null && run.isBuilding();
        } catch (IllegalArgumentException e) {
            // Not the id of a run that there is.
            return false;
        } catch (RuntimeException e) {
            // Not knowing is not a reason to let the agent be recycled under it.
            LOGGER.log(Level.WARNING, "Failed to find out whether the run " + runId + " is building", e);
            return true;
        }
    }

    private static int limitOf(String name) {
        final Node node = Jenkins.get().getNode(name);
        return node instanceof vSphereCloudSlave ? ((vSphereCloudSlave) node).getLimitedTestRunCount() : 0;
    }

    /** Saves what is pending before Jenkins stops. */
    @Terminator
    public static void saveAtShutdown() {
        if (Jenkins.getInstanceOrNull() != null) {
            get().terminate();
        }
    }

    private int countOf(String node) {
        final Entry entry = usages.get(node);
        return entry == null ? 0 : entry.count;
    }

    private boolean hasCountedRuns(Entry entry) {
        return countedRuns.containsValue(entry);
    }
}
