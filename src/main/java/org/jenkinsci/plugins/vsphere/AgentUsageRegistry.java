package org.jenkinsci.plugins.vsphere;

import hudson.Extension;
import hudson.ExtensionList;
import hudson.XmlFile;
import hudson.init.InitMilestone;
import hudson.init.Initializer;
import hudson.init.Terminator;
import hudson.model.Node;
import hudson.model.Run;
import hudson.util.XStream2;
import java.io.File;
import java.io.IOException;
import java.util.HashMap;
import java.util.Map;
import java.util.TreeMap;
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

    /** What to do when a run that may have been counted is over. */
    public enum Released {
        /** The run was not counted: nothing changes. */
        NOT_COUNTED,
        /** The run was counted on an agent that is not the one with that name any more: nothing to recycle. */
        STALE,
        /** The agent has more uses left. */
        KEEP,
        /** The agent has reached its limit: its usage was reset and it is to be recycled. */
        RECYCLE
    }

    /** The outcome and the usage of the agent right after. */
    public record Acquired(Outcome outcome, int count) {}

    /** The usage of one incarnation of an agent. Runs refer to it, so it tells a replaced agent from its successor. */
    private static final class Entry {
        String name;
        int count;

        Entry(String name) {
            this.name = name;
        }
    }

    /** What is saved: the usage of the agents, and nothing about the runs. */
    private static final class Usage {
        Map<String, Integer> counts = new TreeMap<>();
    }

    /** Reads and writes {@link Usage} under a name of its own, so that moving or renaming the class keeps the file readable. */
    private static final XStream2 XSTREAM = new XStream2();

    static {
        XSTREAM.alias("usage", Usage.class);
    }

    private static final Logger LOGGER = Logger.getLogger(AgentUsageRegistry.class.getName());
    private static final String STORE_NAME = "vsphere-cloud-usage.xml";

    private final Object lock = new Object();
    private final Map<String, Entry> usages = new HashMap<>();
    private final Map<Run<?, ?>, Entry> countedRuns = new HashMap<>();

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
        if (limit <= 0) {
            return new Acquired(Outcome.UNTRACKED, 0);
        }
        synchronized (lock) {
            // Asked while holding the lock: a run that ends in between would otherwise be counted for good.
            if (!stillBuilding.getAsBoolean()) {
                return new Acquired(Outcome.NOT_BUILDING, countOf(node));
            }
            if (countedRuns.containsKey(run)) {
                return new Acquired(Outcome.ALREADY_COUNTED, countOf(node));
            }
            final Entry entry = usages.computeIfAbsent(node, Entry::new);
            if (entry.count >= limit) {
                return new Acquired(Outcome.OVER_LIMIT, entry.count);
            }
            entry.count++;
            countedRuns.put(run, entry);
            changed();
            return new Acquired(Outcome.COUNTED, entry.count);
        }
    }

    /**
     * The run is over. Only a run that was counted can make its agent be recycled, and only if that agent is still the
     * one it was counted on and has reached its {@code limit} (the one it has now; none means never).
     */
    public Released release(Run<?, ?> run, int limit) {
        synchronized (lock) {
            final Entry entry = countedRuns.remove(run);
            if (entry == null) {
                return Released.NOT_COUNTED;
            }
            if (usages.get(entry.name) != entry) {
                return Released.STALE;
            }
            if (limit <= 0) {
                return Released.KEEP;
            }
            if (entry.count >= limit) {
                entry.count = 0;
                changed();
                return Released.RECYCLE;
            }
            return Released.KEEP;
        }
    }

    /** The name of the agent the run is counted on, or null if it is not counted. */
    public String nodeOf(Run<?, ?> run) {
        synchronized (lock) {
            final Entry entry = countedRuns.get(run);
            return entry == null ? null : entry.name;
        }
    }

    public boolean isCounted(Run<?, ?> run) {
        synchronized (lock) {
            return countedRuns.containsKey(run);
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
            if (usages.remove(node) != null) {
                changed();
            }
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
     * The limit of the agent may have changed. With no limit there is nothing to remember. An agent at or over its limit
     * with no run using it would never be recycled, so it starts again (one that has runs using it is recycled when the
     * first of them to end finds it at its limit).
     */
    public void reconcile(String node, int limit) {
        synchronized (lock) {
            final Entry entry = usages.get(node);
            if (entry == null) {
                return;
            }
            if (limit <= 0) {
                usages.remove(node);
                changed();
            } else if (entry.count >= limit && !hasCountedRuns(entry)) {
                entry.count = 0;
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

    /** Writes the usage of the agents. Writes are one after the other, each one with the usage as of its start. */
    void save() {
        if (store == null) {
            return;
        }
        synchronized (saveLock) {
            try {
                final Usage usage = new Usage();
                snapshot().forEach((name, count) -> {
                    // An entry without a name cannot be put back to an agent; it must not stop the others.
                    if (name != null) {
                        usage.counts.put(name, count);
                    }
                });
                new XmlFile(XSTREAM, store).write(usage);
            } catch (IOException | RuntimeException e) {
                LOGGER.log(Level.WARNING, "Failed to save the usage of the vSphere agents to " + store, e);
            }
        }
    }

    /**
     * Takes the usage saved by an earlier run of Jenkins. An agent that does not exist any more is not taken. Nothing is
     * using the agents now, so one that is at its limit would never be recycled: it starts again, as does one that has
     * no limit any more.
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
                if (limit <= 0 || count >= limit) {
                    return;
                }
                usages.computeIfAbsent(name, Entry::new).count = count;
            });
        }
    }

    /** Takes the saved usage once the agents are known. */
    @Initializer(after = InitMilestone.SYSTEM_CONFIG_LOADED)
    public static void loadAtStartup() {
        get().load(name -> Jenkins.get().getNode(name) instanceof vSphereCloudSlave, name -> {
            final Node node = Jenkins.get().getNode(name);
            return node instanceof vSphereCloudSlave ? ((vSphereCloudSlave) node).getLimitedTestRunCount() : 0;
        });
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
