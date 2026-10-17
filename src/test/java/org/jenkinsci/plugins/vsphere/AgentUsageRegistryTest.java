package org.jenkinsci.plugins.vsphere;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.nullValue;

import hudson.model.FreeStyleBuild;
import hudson.model.FreeStyleProject;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.jenkinsci.plugins.vsphere.AgentUsageRegistry.Acquired;
import org.jenkinsci.plugins.vsphere.AgentUsageRegistry.Outcome;
import org.jenkinsci.plugins.vsphere.AgentUsageRegistry.Released;
import org.junit.jupiter.api.Test;
import org.jvnet.hudson.test.JenkinsRule;
import org.jvnet.hudson.test.SleepBuilder;
import org.jvnet.hudson.test.junit.jupiter.WithJenkins;

/**
 * The usage registry only needs {@code Run} objects to tell runs apart, so most of these use finished builds and say
 * by hand whether a run is still building.
 */
@WithJenkins
class AgentUsageRegistryTest {

    private static final boolean BUILDING = true;

    private static List<FreeStyleBuild> runs(JenkinsRule r, int howMany) throws Exception {
        final FreeStyleProject project = r.createFreeStyleProject();
        final List<FreeStyleBuild> builds = new ArrayList<>();
        for (int i = 0; i < howMany; i++) {
            builds.add(r.buildAndAssertSuccess(project));
        }
        return builds;
    }

    private static Acquired acquire(AgentUsageRegistry registry, String node, int limit, FreeStyleBuild run) {
        return registry.acquire(node, limit, run, () -> BUILDING);
    }

    @Test
    void acquireCountsUpToTheLimitThenReportsOverLimit(JenkinsRule r) throws Exception {
        final AgentUsageRegistry registry = new AgentUsageRegistry();
        final List<FreeStyleBuild> run = runs(r, 3);

        assertThat(acquire(registry, "agent", 2, run.get(0)), is(new Acquired(Outcome.COUNTED, 1)));
        assertThat(acquire(registry, "agent", 2, run.get(1)), is(new Acquired(Outcome.COUNTED, 2)));
        assertThat(acquire(registry, "agent", 2, run.get(2)), is(new Acquired(Outcome.OVER_LIMIT, 2)));
        assertThat(registry.count("agent"), is(2));
        assertThat(registry.isCounted(run.get(2)), is(false));
    }

    @Test
    void aRunIsCountedOnce(JenkinsRule r) throws Exception {
        final AgentUsageRegistry registry = new AgentUsageRegistry();
        final FreeStyleBuild run = runs(r, 1).get(0);
        acquire(registry, "first", 5, run);

        assertThat(acquire(registry, "first", 5, run).outcome(), is(Outcome.ALREADY_COUNTED));
        assertThat(acquire(registry, "second", 5, run).outcome(), is(Outcome.ALREADY_COUNTED));

        assertThat(registry.count("first"), is(1));
        assertThat(registry.count("second"), is(0));
        assertThat(registry.nodeOf(run), is("first"));
    }

    @Test
    void noLimitIsNotTracked(JenkinsRule r) throws Exception {
        final AgentUsageRegistry registry = new AgentUsageRegistry();
        final List<FreeStyleBuild> run = runs(r, 2);

        assertThat(acquire(registry, "agent", 0, run.get(0)).outcome(), is(Outcome.UNTRACKED));
        assertThat(acquire(registry, "agent", -1, run.get(1)).outcome(), is(Outcome.UNTRACKED));

        assertThat(registry.count("agent"), is(0));
        assertThat(registry.isCounted(run.get(0)), is(false));
    }

    @Test
    void aRunThatIsOverIsNotCounted(JenkinsRule r) throws Exception {
        final AgentUsageRegistry registry = new AgentUsageRegistry();
        final FreeStyleBuild finished = runs(r, 1).get(0);

        // The public method asks the run itself, and a finished build is not building.
        assertThat(registry.acquire("agent", 5, finished).outcome(), is(Outcome.NOT_BUILDING));

        assertThat(registry.count("agent"), is(0));
        assertThat(registry.isCounted(finished), is(false));
    }

    @Test
    void aBuildingRunIsCountedThroughThePublicMethod(JenkinsRule r) throws Exception {
        final AgentUsageRegistry registry = new AgentUsageRegistry();
        final FreeStyleProject project = r.createFreeStyleProject();
        project.getBuildersList().add(new SleepBuilder(600_000));
        final FreeStyleBuild building = project.scheduleBuild2(0).waitForStart();
        try {
            assertThat(registry.acquire("agent", 5, building), is(new Acquired(Outcome.COUNTED, 1)));
        } finally {
            building.getExecutor().interrupt();
            r.waitForCompletion(building);
        }
    }

    @Test
    void releasingAnUncountedRunChangesNothing(JenkinsRule r) throws Exception {
        final AgentUsageRegistry registry = new AgentUsageRegistry();
        final List<FreeStyleBuild> run = runs(r, 2);
        acquire(registry, "agent", 1, run.get(0));
        acquire(registry, "agent", 1, run.get(1));

        // The second run was over the limit and was aborted: it must not reset what the first one is using.
        assertThat(registry.release(run.get(1), 1), is(Released.NOT_COUNTED));

        assertThat(registry.count("agent"), is(1));
        assertThat(registry.isCounted(run.get(0)), is(true));
    }

    @Test
    void releaseRecyclesOnlyWhenTheLimitIsReached(JenkinsRule r) throws Exception {
        final AgentUsageRegistry registry = new AgentUsageRegistry();
        final List<FreeStyleBuild> run = runs(r, 3);
        acquire(registry, "agent", 2, run.get(0));

        assertThat(registry.release(run.get(0), 2), is(Released.KEEP));
        assertThat(registry.count("agent"), is(1));

        acquire(registry, "agent", 2, run.get(1));
        acquire(registry, "agent", 2, run.get(2));
        assertThat(registry.release(run.get(1), 2), is(Released.RECYCLE));
        assertThat(registry.count("agent"), is(0));
    }

    @Test
    void releaseWithNoLimitDoesNotRecycle(JenkinsRule r) throws Exception {
        final AgentUsageRegistry registry = new AgentUsageRegistry();
        final FreeStyleBuild run = runs(r, 1).get(0);
        acquire(registry, "agent", 3, run);

        // The limit was removed while the run was using the agent.
        assertThat(registry.release(run, 0), is(Released.KEEP));
    }

    @Test
    void aReleaseAfterTheNodeWasReplacedIsStale(JenkinsRule r) throws Exception {
        final AgentUsageRegistry registry = new AgentUsageRegistry();
        final List<FreeStyleBuild> run = runs(r, 2);
        acquire(registry, "agent", 1, run.get(0));
        // The agent was deleted and a new one got the same name.
        registry.forget("agent");
        acquire(registry, "agent", 1, run.get(1));

        assertThat(registry.release(run.get(0), 1), is(Released.STALE));

        assertThat(registry.count("agent"), is(1));
        assertThat(registry.isCounted(run.get(1)), is(true));
    }

    @Test
    void renameMovesTheCountAndTheCountedRuns(JenkinsRule r) throws Exception {
        final AgentUsageRegistry registry = new AgentUsageRegistry();
        final FreeStyleBuild run = runs(r, 1).get(0);
        acquire(registry, "old", 1, run);

        registry.rename("old", "new");

        assertThat(registry.count("old"), is(0));
        assertThat(registry.count("new"), is(1));
        assertThat(registry.nodeOf(run), is("new"));
        assertThat(registry.release(run, 1), is(Released.RECYCLE));
    }

    @Test
    void reconcileResetsAnIdleEntryAtOrOverTheNewLimit(JenkinsRule r) throws Exception {
        final AgentUsageRegistry registry = new AgentUsageRegistry();
        final List<FreeStyleBuild> run = runs(r, 2);
        acquire(registry, "agent", 5, run.get(0));
        acquire(registry, "agent", 5, run.get(1));
        registry.release(run.get(0), 5);
        registry.release(run.get(1), 5);
        assertThat(registry.count("agent"), is(2));

        registry.reconcile("agent", 2);

        assertThat(registry.count("agent"), is(0));
    }

    @Test
    void reconcileKeepsTheCountOfARunThatIsStillUsingTheAgent(JenkinsRule r) throws Exception {
        final AgentUsageRegistry registry = new AgentUsageRegistry();
        final FreeStyleBuild run = runs(r, 1).get(0);
        acquire(registry, "agent", 1, run);

        // Saving the agent while its last allowed run is working must not lose that the run will have to recycle it.
        registry.reconcile("agent", 1);

        assertThat(registry.count("agent"), is(1));
        assertThat(registry.release(run, 1), is(Released.RECYCLE));
    }

    @Test
    void reconcileForgetsWhenTheLimitIsRemoved(JenkinsRule r) throws Exception {
        final AgentUsageRegistry registry = new AgentUsageRegistry();
        final FreeStyleBuild run = runs(r, 1).get(0);
        acquire(registry, "agent", 3, run);

        registry.reconcile("agent", 0);

        assertThat(registry.count("agent"), is(0));
        assertThat(registry.release(run, 0), is(Released.STALE));
    }

    @Test
    void resetAndResetAll(JenkinsRule r) throws Exception {
        final AgentUsageRegistry registry = new AgentUsageRegistry();
        final List<FreeStyleBuild> run = runs(r, 2);
        acquire(registry, "a", 5, run.get(0));
        acquire(registry, "b", 5, run.get(1));

        registry.reset("a");
        assertThat(registry.count("a"), is(0));
        assertThat(registry.count("b"), is(1));

        registry.resetAll();
        assertThat(registry.count("b"), is(0));
        assertThat(registry.snapshot().isEmpty(), is(true));
    }

    @Test
    void anUnknownRunHasNoNode(JenkinsRule r) throws Exception {
        final AgentUsageRegistry registry = new AgentUsageRegistry();

        assertThat(registry.nodeOf(runs(r, 1).get(0)), is(nullValue()));
    }

    @Test
    void concurrentAcquiresNeverExceedTheLimit(JenkinsRule r) throws Exception {
        final int threads = 16;
        final int limit = 5;
        final List<FreeStyleBuild> run = runs(r, threads);
        final ExecutorService pool = Executors.newFixedThreadPool(threads);
        try {
            for (int round = 0; round < 50; round++) {
                final AgentUsageRegistry registry = new AgentUsageRegistry();
                final CyclicBarrier together = new CyclicBarrier(threads);
                final List<Future<Outcome>> outcomes = new ArrayList<>();
                for (final FreeStyleBuild one : run) {
                    outcomes.add(pool.submit(() -> {
                        together.await();
                        return acquire(registry, "agent", limit, one).outcome();
                    }));
                }
                int counted = 0;
                for (final Future<Outcome> outcome : outcomes) {
                    if (outcome.get(30, TimeUnit.SECONDS) == Outcome.COUNTED) {
                        counted++;
                    }
                }
                assertThat("round " + round, counted, is(limit));
                assertThat(registry.count("agent"), is(limit));
            }
        } finally {
            pool.shutdownNow();
        }
    }
}
