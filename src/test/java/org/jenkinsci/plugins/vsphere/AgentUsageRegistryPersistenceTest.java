package org.jenkinsci.plugins.vsphere;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.not;

import hudson.model.FreeStyleBuild;
import hudson.model.FreeStyleProject;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.jvnet.hudson.test.JenkinsRule;
import org.jvnet.hudson.test.junit.jupiter.WithJenkins;

/** What of the usage of the agents is saved, and how it is read back. */
@WithJenkins
class AgentUsageRegistryPersistenceTest {

    private static List<FreeStyleBuild> runs(JenkinsRule r, int howMany) throws Exception {
        final FreeStyleProject project = r.createFreeStyleProject();
        final List<FreeStyleBuild> builds = new ArrayList<>();
        for (int i = 0; i < howMany; i++) {
            builds.add(r.buildAndAssertSuccess(project));
        }
        return builds;
    }

    /** A registry that saves to the file when the scheduled saves are run by hand. */
    private static AgentUsageRegistry registry(File store, List<Runnable> scheduled) {
        return new AgentUsageRegistry(store, scheduled::add);
    }

    private static void use(AgentUsageRegistry registry, String node, int limit, List<FreeStyleBuild> runs, int times) {
        for (int i = 0; i < times; i++) {
            registry.acquire(node, limit, runs.get(i), () -> true);
        }
    }

    @Test
    void theCountsAreSavedAndLoadedBack(JenkinsRule r, @TempDir Path dir) throws Exception {
        final File store = dir.resolve("usage.xml").toFile();
        final AgentUsageRegistry before = registry(store, new ArrayList<>());
        use(before, "agent", 5, runs(r, 2), 2);
        before.save();

        final AgentUsageRegistry after = registry(store, new ArrayList<>());
        after.load(node -> true, node -> 5);

        assertThat(after.count("agent"), is(2));
    }

    @Test
    void entriesOfNodesThatDoNotExistAreDropped(JenkinsRule r, @TempDir Path dir) throws Exception {
        final File store = dir.resolve("usage.xml").toFile();
        final AgentUsageRegistry before = registry(store, new ArrayList<>());
        final List<FreeStyleBuild> run = runs(r, 2);
        use(before, "gone", 5, run, 1);
        use(before, "here", 5, run.subList(1, 2), 1);
        before.save();

        final AgentUsageRegistry after = registry(store, new ArrayList<>());
        after.load("here"::equals, node -> 5);

        assertThat(after.count("gone"), is(0));
        assertThat(after.count("here"), is(1));
    }

    @Test
    void anAgentAtItsLimitWithoutSavedRunsOrWithoutALimitStartsAgainOnLoad(JenkinsRule r, @TempDir Path dir)
            throws Exception {
        final File store = dir.resolve("usage.xml").toFile();
        // As saved before the runs were: counts only.
        Files.write(
                store.toPath(),
                ("<usage><counts>"
                                + "<entry><string>atLimit</string><int>3</int></entry>"
                                + "<entry><string>belowLimit</string><int>2</int></entry>"
                                + "<entry><string>unlimited</string><int>1</int></entry>"
                                + "</counts></usage>")
                        .getBytes(StandardCharsets.UTF_8));

        final AgentUsageRegistry after = registry(store, new ArrayList<>());
        after.load(node -> true, node -> node.equals("atLimit") ? 3 : node.equals("unlimited") ? 0 : 5);

        assertThat(after.count("atLimit"), is(0));
        assertThat(after.count("belowLimit"), is(2));
        assertThat(after.count("unlimited"), is(0));
    }

    @Test
    void theCountedRunsAreSavedAndLoadedBack(JenkinsRule r, @TempDir Path dir) throws Exception {
        final File store = dir.resolve("usage.xml").toFile();
        final AgentUsageRegistry before = registry(store, new ArrayList<>());
        final List<FreeStyleBuild> run = runs(r, 2);
        use(before, "agent", 5, run, 2);
        before.save();

        final AgentUsageRegistry after = registry(store, new ArrayList<>());
        after.load(node -> true, node -> 5);

        assertThat(after.isCounted(run.get(0).getExternalizableId()), is(true));
        assertThat(after.nodeOf(run.get(1).getExternalizableId()), is("agent"));
        assertThat(after.count("agent"), is(2));
    }

    @Test
    void anAgentAtItsLimitIsKeptWhileItHasASavedRun(JenkinsRule r, @TempDir Path dir) throws Exception {
        final File store = dir.resolve("usage.xml").toFile();
        final AgentUsageRegistry before = registry(store, new ArrayList<>());
        final List<FreeStyleBuild> run = runs(r, 1);
        use(before, "agent", 1, run, 1);
        before.save();

        final AgentUsageRegistry after = registry(store, new ArrayList<>());
        after.load(node -> true, node -> 1);

        assertThat(after.count("agent"), is(1));
        assertThat(after.isCounted(run.get(0).getExternalizableId()), is(true));
    }

    @Test
    void runsNoLongerBuildingArePrunedAndAnAtLimitEntryWithoutRunsIsDropped(JenkinsRule r, @TempDir Path dir)
            throws Exception {
        final File store = dir.resolve("usage.xml").toFile();
        final AgentUsageRegistry registry = registry(store, new ArrayList<>());
        final List<FreeStyleBuild> run = runs(r, 3);
        use(registry, "atLimit", 1, run.subList(0, 1), 1);
        use(registry, "below", 5, run.subList(1, 3), 2);

        registry.pruneRuns(id -> id.equals(run.get(1).getExternalizableId()), node -> node.equals("atLimit") ? 1 : 5);

        assertThat(registry.count("atLimit"), is(0));
        assertThat(registry.isCounted(run.get(0).getExternalizableId()), is(false));
        assertThat(registry.isCounted(run.get(1).getExternalizableId()), is(true));
        assertThat(registry.isCounted(run.get(2).getExternalizableId()), is(false));
        // The count of an agent that has uses left is not touched: only the run is forgotten.
        assertThat(registry.count("below"), is(2));
    }

    @Test
    void releasingARunIsSaved(JenkinsRule r, @TempDir Path dir) throws Exception {
        final List<Runnable> scheduled = new ArrayList<>();
        final AgentUsageRegistry registry = registry(dir.resolve("usage.xml").toFile(), scheduled);
        final List<FreeStyleBuild> run = runs(r, 2);
        use(registry, "agent", 5, run, 2);
        scheduled.forEach(Runnable::run);
        scheduled.clear();

        registry.release(run.get(0));

        assertThat(scheduled.size(), is(1));
    }

    @Test
    void aFileWithoutRunsStillLoads(JenkinsRule r, @TempDir Path dir) throws Exception {
        final File store = dir.resolve("usage.xml").toFile();
        Files.write(
                store.toPath(),
                "<usage><counts><entry><string>agent</string><int>2</int></entry></counts></usage>"
                        .getBytes(StandardCharsets.UTF_8));

        final AgentUsageRegistry loaded = registry(store, new ArrayList<>());
        loaded.load(node -> true, node -> 5);

        assertThat(loaded.count("agent"), is(2));
    }

    @Test
    void aMissingOrUnreadableFileMeansNoUsage(JenkinsRule r, @TempDir Path dir) throws Exception {
        final File missing = dir.resolve("missing.xml").toFile();
        final AgentUsageRegistry none = registry(missing, new ArrayList<>());
        none.load(node -> true, node -> 5);
        assertThat(none.snapshot().isEmpty(), is(true));

        final File garbage = dir.resolve("garbage.xml").toFile();
        Files.write(garbage.toPath(), "this is not xml".getBytes(StandardCharsets.UTF_8));
        final AgentUsageRegistry unreadable = registry(garbage, new ArrayList<>());
        unreadable.load(node -> true, node -> 5);
        assertThat(unreadable.snapshot().isEmpty(), is(true));
    }

    @Test
    void aBurstOfChangesIsOneSave(JenkinsRule r, @TempDir Path dir) throws Exception {
        final File store = dir.resolve("usage.xml").toFile();
        final List<Runnable> scheduled = new ArrayList<>();
        final AgentUsageRegistry registry = registry(store, scheduled);
        final List<FreeStyleBuild> run = runs(r, 4);

        use(registry, "agent", 100, run.subList(0, 3), 3);
        for (int i = 0; i < 100; i++) {
            registry.reset("agent");
        }
        assertThat(scheduled.size(), is(1));
        assertThat(store.exists(), is(false));

        scheduled.get(0).run();
        assertThat(store.exists(), is(true));

        // Once it was saved, the next change asks for another save.
        use(registry, "other", 100, run.subList(3, 4), 1);
        assertThat(scheduled.size(), is(2));
    }

    @Test
    void nothingIsScheduledOnceJenkinsIsStopping(JenkinsRule r, @TempDir Path dir) throws Exception {
        final File store = dir.resolve("usage.xml").toFile();
        final List<Runnable> scheduled = new ArrayList<>();
        final AgentUsageRegistry registry = registry(store, scheduled);
        final List<FreeStyleBuild> run = runs(r, 2);
        use(registry, "agent", 100, run.subList(0, 1), 1);

        registry.terminate();
        use(registry, "other", 100, run.subList(1, 2), 1);

        // The pending save is for the timer to do, or nobody: it is shut down after this.
        assertThat(store.exists(), is(true));
        assertThat(scheduled.size(), is(1));
    }

    @Test
    void aSchedulerThatFailsDoesNotStopLaterSaves(JenkinsRule r, @TempDir Path dir) throws Exception {
        final File store = dir.resolve("usage.xml").toFile();
        final List<Runnable> scheduled = new ArrayList<>();
        final boolean[] failNext = {true};
        final AgentUsageRegistry registry = new AgentUsageRegistry(store, save -> {
            if (failNext[0]) {
                failNext[0] = false;
                throw new IllegalStateException("the timer is gone");
            }
            scheduled.add(save);
        });
        final List<FreeStyleBuild> run = runs(r, 2);

        use(registry, "agent", 100, run.subList(0, 1), 1);
        use(registry, "other", 100, run.subList(1, 2), 1);

        assertThat(scheduled.size(), is(1));
    }

    @Test
    void anAgentWithoutANameDoesNotStopTheOthersFromBeingSaved(JenkinsRule r, @TempDir Path dir) throws Exception {
        final File store = dir.resolve("usage.xml").toFile();
        final AgentUsageRegistry registry = registry(store, new ArrayList<>());
        final List<FreeStyleBuild> run = runs(r, 2);
        use(registry, null, 5, run.subList(0, 1), 1);
        use(registry, "agent", 5, run.subList(1, 2), 1);

        registry.save();

        final AgentUsageRegistry loaded = registry(store, new ArrayList<>());
        loaded.load(node -> true, node -> 5);
        assertThat(loaded.count("agent"), is(1));
    }

    @Test
    void theSavedFileDoesNotNameTheJavaClass(JenkinsRule r, @TempDir Path dir) throws Exception {
        final File store = dir.resolve("usage.xml").toFile();
        final AgentUsageRegistry registry = registry(store, new ArrayList<>());
        use(registry, "agent", 5, runs(r, 1), 1);

        registry.save();

        // Moving or renaming the class later must not make what was saved unreadable.
        final String saved = new String(Files.readAllBytes(store.toPath()), StandardCharsets.UTF_8);
        assertThat(saved, containsString("<usage>"));
        assertThat(saved, not(containsString("AgentUsageRegistry")));
    }

    @Test
    void onlyTheCountsAreSaved(JenkinsRule r, @TempDir Path dir) throws Exception {
        final File store = dir.resolve("usage.xml").toFile();
        final AgentUsageRegistry registry = registry(store, new ArrayList<>());
        use(registry, "agent", 5, runs(r, 2), 2);

        registry.save();

        final String saved = new String(Files.readAllBytes(store.toPath()), StandardCharsets.UTF_8);
        assertThat(saved, containsString("agent"));
        assertThat(saved, not(containsString("FreeStyleBuild")));
    }
}
