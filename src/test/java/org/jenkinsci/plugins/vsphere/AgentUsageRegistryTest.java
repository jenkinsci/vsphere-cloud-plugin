package org.jenkinsci.plugins.vsphere;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.notNullValue;
import static org.hamcrest.Matchers.nullValue;

import hudson.model.FreeStyleBuild;
import hudson.model.FreeStyleProject;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.jenkinsci.plugins.vsphere.AgentUsageRegistry.Acquired;
import org.jenkinsci.plugins.vsphere.AgentUsageRegistry.Outcome;
import org.jenkinsci.plugins.vsphere.AgentUsageRegistry.RecycleOutcome;
import org.jenkinsci.plugins.vsphere.AgentUsageRegistry.RecycleToken;
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
        final AgentUsageRegistry registry = AgentUsageRegistry.inMemory();
        final List<FreeStyleBuild> run = runs(r, 3);

        assertThat(acquire(registry, "agent", 2, run.get(0)), is(new Acquired(Outcome.COUNTED, 1)));
        assertThat(acquire(registry, "agent", 2, run.get(1)), is(new Acquired(Outcome.COUNTED, 2)));
        assertThat(acquire(registry, "agent", 2, run.get(2)), is(new Acquired(Outcome.OVER_LIMIT, 2)));
        assertThat(registry.count("agent"), is(2));
        assertThat(registry.isCounted(run.get(2)), is(false));
    }

    @Test
    void aRunIsKnownByItsId(JenkinsRule r) throws Exception {
        final AgentUsageRegistry registry = AgentUsageRegistry.inMemory();
        final String id = runs(r, 1).get(0).getExternalizableId();

        assertThat(registry.acquire("agent", 5, id, () -> BUILDING), is(new Acquired(Outcome.COUNTED, 1)));
        assertThat(registry.acquire("agent", 5, id, () -> BUILDING), is(new Acquired(Outcome.ALREADY_COUNTED, 1)));
        assertThat(registry.isCounted(id), is(true));
        assertThat(registry.nodeOf(id), is("agent"));
        assertThat(registry.release(id), is(Released.RELEASED));
        assertThat(registry.isCounted(id), is(false));
    }

    @Test
    void aRunIsCountedOnce(JenkinsRule r) throws Exception {
        final AgentUsageRegistry registry = AgentUsageRegistry.inMemory();
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
        final AgentUsageRegistry registry = AgentUsageRegistry.inMemory();
        final List<FreeStyleBuild> run = runs(r, 2);

        assertThat(acquire(registry, "agent", 0, run.get(0)).outcome(), is(Outcome.UNTRACKED));
        assertThat(acquire(registry, "agent", -1, run.get(1)).outcome(), is(Outcome.UNTRACKED));

        assertThat(registry.count("agent"), is(0));
        assertThat(registry.isCounted(run.get(0)), is(false));
    }

    @Test
    void aRunThatIsOverIsNotCounted(JenkinsRule r) throws Exception {
        final AgentUsageRegistry registry = AgentUsageRegistry.inMemory();
        final FreeStyleBuild finished = runs(r, 1).get(0);

        // The public method asks the run itself, and a finished build is not building.
        assertThat(registry.acquire("agent", 5, finished).outcome(), is(Outcome.NOT_BUILDING));

        assertThat(registry.count("agent"), is(0));
        assertThat(registry.isCounted(finished), is(false));
    }

    @Test
    void aBuildingRunIsCountedThroughThePublicMethod(JenkinsRule r) throws Exception {
        final AgentUsageRegistry registry = AgentUsageRegistry.inMemory();
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
        final AgentUsageRegistry registry = AgentUsageRegistry.inMemory();
        final List<FreeStyleBuild> run = runs(r, 2);
        acquire(registry, "agent", 1, run.get(0));
        acquire(registry, "agent", 1, run.get(1));

        // The second run was over the limit and was aborted: it must not reset what the first one is using.
        assertThat(registry.release(run.get(1)), is(Released.NOT_COUNTED));

        assertThat(registry.count("agent"), is(1));
        assertThat(registry.isCounted(run.get(0)), is(true));
    }

    @Test
    void releaseNeverResetsTheCount(JenkinsRule r) throws Exception {
        final AgentUsageRegistry registry = AgentUsageRegistry.inMemory();
        final List<FreeStyleBuild> run = runs(r, 2);
        acquire(registry, "agent", 2, run.get(0));
        acquire(registry, "agent", 2, run.get(1));

        // Resetting is for when the agent has been recycled, not for when a run ends.
        assertThat(registry.release(run.get(0)), is(Released.RELEASED));
        assertThat(registry.release(run.get(1)), is(Released.RELEASED));
        assertThat(registry.count("agent"), is(2));
    }

    @Test
    void aReleaseAfterTheNodeWasReplacedIsStale(JenkinsRule r) throws Exception {
        final AgentUsageRegistry registry = AgentUsageRegistry.inMemory();
        final List<FreeStyleBuild> run = runs(r, 2);
        acquire(registry, "agent", 1, run.get(0));
        // The agent was deleted and a new one got the same name.
        registry.forget("agent");
        acquire(registry, "agent", 1, run.get(1));

        assertThat(registry.release(run.get(0)), is(Released.STALE));

        assertThat(registry.count("agent"), is(1));
        assertThat(registry.isCounted(run.get(1)), is(true));
    }

    @Test
    void renameMovesTheCountAndTheCountedRuns(JenkinsRule r) throws Exception {
        final AgentUsageRegistry registry = AgentUsageRegistry.inMemory();
        final FreeStyleBuild run = runs(r, 1).get(0);
        acquire(registry, "old", 1, run);

        registry.rename("old", "new");

        assertThat(registry.count("old"), is(0));
        assertThat(registry.count("new"), is(1));
        assertThat(registry.nodeOf(run), is("new"));
        assertThat(registry.release(run), is(Released.RELEASED));
    }

    @Test
    void reconcileKeepsTheCountAtOrOverTheNewLimit(JenkinsRule r) throws Exception {
        final AgentUsageRegistry registry = AgentUsageRegistry.inMemory();
        final List<FreeStyleBuild> run = runs(r, 2);
        acquire(registry, "agent", 5, run.get(0));
        acquire(registry, "agent", 5, run.get(1));
        registry.release(run.get(0));
        registry.release(run.get(1));

        // The agent is recycled because it is at its limit, which is not for the registry to do.
        registry.reconcile("agent", 2);

        assertThat(registry.count("agent"), is(2));
        assertThat(registry.isRetiring("agent", 2), is(true));
    }

    @Test
    void reconcileForgetsWhenTheLimitIsRemoved(JenkinsRule r) throws Exception {
        final AgentUsageRegistry registry = AgentUsageRegistry.inMemory();
        final FreeStyleBuild run = runs(r, 1).get(0);
        acquire(registry, "agent", 3, run);

        registry.reconcile("agent", 0);

        assertThat(registry.count("agent"), is(0));
        assertThat(registry.release(run), is(Released.STALE));
    }

    @Test
    void retiringIsDerivedFromTheCountAndTheLimit(JenkinsRule r) throws Exception {
        final AgentUsageRegistry registry = AgentUsageRegistry.inMemory();
        final List<FreeStyleBuild> run = runs(r, 2);
        acquire(registry, "agent", 2, run.get(0));
        assertThat(registry.isRetiring("agent", 2), is(false));
        acquire(registry, "agent", 2, run.get(1));

        assertThat(registry.isRetiring("agent", 2), is(true));
        // Raising the limit gives the agent room again; no limit means never.
        assertThat(registry.isRetiring("agent", 3), is(false));
        assertThat(registry.isRetiring("agent", 0), is(false));
        assertThat(registry.isRetiring("other", 1), is(false));
        assertThat(registry.holdsCountedRuns("agent"), is(true));
        registry.release(run.get(0));
        registry.release(run.get(1));
        assertThat(registry.holdsCountedRuns("agent"), is(false));
    }

    @Test
    void anAgentIsNotRecycledWhileACountedRunUsesIt(JenkinsRule r) throws Exception {
        final AgentUsageRegistry registry = AgentUsageRegistry.inMemory();
        final List<FreeStyleBuild> run = runs(r, 2);
        acquire(registry, "agent", 2, run.get(0));
        acquire(registry, "agent", 2, run.get(1));
        registry.release(run.get(0));

        assertThat(registry.tryBeginRecycle("agent", 2), is(nullValue()));

        registry.release(run.get(1));
        assertThat(registry.tryBeginRecycle("agent", 2), is(notNullValue()));
    }

    @Test
    void onlyAnAgentAtItsLimitCanBeRecycled(JenkinsRule r) throws Exception {
        final AgentUsageRegistry registry = AgentUsageRegistry.inMemory();
        acquire(registry, "agent", 2, runs(r, 1).get(0));

        assertThat(registry.tryBeginRecycle("agent", 2), is(nullValue()));
        assertThat(registry.tryBeginRecycle("agent", 0), is(nullValue()));
        assertThat(registry.tryBeginRecycle("unknown", 1), is(nullValue()));
    }

    @Test
    void thereIsOneRecycleAtATime(JenkinsRule r) throws Exception {
        final AgentUsageRegistry registry = AgentUsageRegistry.inMemory();
        final FreeStyleBuild run = runs(r, 1).get(0);
        acquire(registry, "agent", 1, run);
        registry.release(run);

        final RecycleToken token = registry.tryBeginRecycle("agent", 1);

        assertThat(token, is(notNullValue()));
        assertThat(registry.isRecycling("agent"), is(true));
        assertThat(registry.tryBeginRecycle("agent", 1), is(nullValue()));
        registry.endRecycle(token, false);
        assertThat(registry.isRecycling("agent"), is(false));
        assertThat(registry.count("agent"), is(1));
        assertThat(registry.tryBeginRecycle("agent", 1), is(notNullValue()));
    }

    @Test
    void completingARecycleTwiceCompletesItOnce(JenkinsRule r) throws Exception {
        final AgentUsageRegistry registry = AgentUsageRegistry.inMemory();
        final FreeStyleBuild run = runs(r, 1).get(0);
        acquire(registry, "agent", 1, run);
        registry.release(run);
        final RecycleToken token = registry.tryBeginRecycle("agent", 1);

        assertThat(registry.completeRecycle("agent", RecycleOutcome.COMPLETED), is(true));
        assertThat(registry.completeRecycle("agent", RecycleOutcome.FAILED), is(false));
        assertThat(token.done().get(), is(RecycleOutcome.COMPLETED));
        // Nothing has been reset yet, and the recycle is not over, until whoever began it says so.
        assertThat(registry.count("agent"), is(1));
        assertThat(registry.isRecycling("agent"), is(true));
        registry.endRecycle(token, true);
        assertThat(registry.count("agent"), is(0));
        assertThat(registry.isRecycling("agent"), is(false));
        assertThat(registry.completeRecycle("agent", RecycleOutcome.COMPLETED), is(false));
    }

    @Test
    void endingARecycleOfAReplacedAgentDoesNotResetTheNewOne(JenkinsRule r) throws Exception {
        final AgentUsageRegistry registry = AgentUsageRegistry.inMemory();
        final List<FreeStyleBuild> run = runs(r, 2);
        acquire(registry, "agent", 1, run.get(0));
        registry.release(run.get(0));
        final RecycleToken token = registry.tryBeginRecycle("agent", 1);
        registry.forget("agent");
        acquire(registry, "agent", 1, run.get(1));

        registry.endRecycle(token, true);

        assertThat(registry.count("agent"), is(1));
        assertThat(registry.isRecycling("agent"), is(false));
    }

    @Test
    void aRunIsCountedOnTheAgentItWasCountedOn(JenkinsRule r) throws Exception {
        final AgentUsageRegistry registry = AgentUsageRegistry.inMemory();
        final List<FreeStyleBuild> run = runs(r, 2);
        acquire(registry, "agent", 5, run.get(0));
        final String counted = run.get(0).getExternalizableId();
        final String other = run.get(1).getExternalizableId();

        assertThat(registry.isCountedOn(counted, "agent"), is(true));
        assertThat(registry.isCountedOn(counted, "elsewhere"), is(false));
        assertThat(registry.isCountedAnywhere(counted), is(true));
        assertThat(registry.isCountedOn(other, "agent"), is(false));
        assertThat(registry.isCountedAnywhere(other), is(false));
    }

    @Test
    void aRunIsNotCountedOnAnAgentThatReplacedTheOneItWasCountedOn(JenkinsRule r) throws Exception {
        final AgentUsageRegistry registry = AgentUsageRegistry.inMemory();
        final FreeStyleBuild run = runs(r, 1).get(0);
        acquire(registry, "agent", 5, run);
        registry.forget("agent");
        registry.acquire("agent", 5, "someone-else#1", () -> BUILDING);

        assertThat(registry.isCountedOn(run.getExternalizableId(), "agent"), is(false));
    }

    @Test
    void theProjectedCountAddsTheRunsThatAreNotCountedYet(JenkinsRule r) throws Exception {
        final AgentUsageRegistry registry = AgentUsageRegistry.inMemory();
        final List<FreeStyleBuild> run = runs(r, 3);
        acquire(registry, "agent", 5, run.get(0));
        acquire(registry, "other", 5, run.get(1));
        final String counted = run.get(0).getExternalizableId();
        final String elsewhere = run.get(1).getExternalizableId();
        final String fresh = run.get(2).getExternalizableId();

        // Counted here, counted elsewhere: nothing to add. A new run counts once however many blocks it has.
        assertThat(registry.projectedCount("agent", List.of(counted, elsewhere)), is(1));
        assertThat(registry.projectedCount("agent", List.of(fresh, fresh)), is(2));
        // Work that is not known to be of any run is a use of its own, each.
        assertThat(registry.projectedCount("agent", Arrays.asList(null, null)), is(3));
        assertThat(registry.projectedCount("nobody", List.of()), is(0));
    }

    @Test
    void forgettingAnAgentBeingRecycledFailsItsRecycle(JenkinsRule r) throws Exception {
        final AgentUsageRegistry registry = AgentUsageRegistry.inMemory();
        final FreeStyleBuild run = runs(r, 1).get(0);
        acquire(registry, "agent", 1, run);
        registry.release(run);
        final RecycleToken token = registry.tryBeginRecycle("agent", 1);

        registry.forget("agent");

        // Nobody is left to say how it went, and nobody should wait ten minutes for it.
        assertThat(token.done().get(), is(RecycleOutcome.FAILED));
    }

    @Test
    void runsThatAreOverCanBeForgottenWithoutResettingTheAgent(JenkinsRule r) throws Exception {
        final AgentUsageRegistry registry = AgentUsageRegistry.inMemory();
        final FreeStyleBuild run = runs(r, 1).get(0);
        acquire(registry, "agent", 1, run);

        registry.pruneRuns(id -> false);

        // The agent used its one build: it is for the recycler to give it back, not for this.
        assertThat(registry.isCounted(run), is(false));
        assertThat(registry.count("agent"), is(1));
        assertThat(registry.isRetiring("agent", 1), is(true));
    }

    @Test
    void resetStartsOnlyThatAgentAgain(JenkinsRule r) throws Exception {
        final AgentUsageRegistry registry = AgentUsageRegistry.inMemory();
        final List<FreeStyleBuild> run = runs(r, 2);
        acquire(registry, "a", 5, run.get(0));
        acquire(registry, "b", 5, run.get(1));

        registry.reset("a");

        assertThat(registry.count("a"), is(0));
        assertThat(registry.count("b"), is(1));
        assertThat(registry.snapshot().size(), is(1));
    }

    @Test
    void anUnknownRunHasNoNode(JenkinsRule r) throws Exception {
        final AgentUsageRegistry registry = AgentUsageRegistry.inMemory();

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
                final AgentUsageRegistry registry = AgentUsageRegistry.inMemory();
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
