package org.jenkinsci.plugins.vsphere;

import hudson.model.Descriptor;
import org.junit.jupiter.api.Test;

import java.util.concurrent.TimeUnit;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.sameInstance;

/**
 * Unit tests for the pure decision logic and configuration plumbing of
 * {@link VSphereCloudRetentionStrategy}. No live Jenkins/vCenter or Computer/Slave mocking is
 * needed for these: {@link VSphereCloudRetentionStrategy#check} itself depends on
 * {@code Computer.isIdle()}/{@code getIdleStartMilliseconds()}, which are {@code final} and so
 * can't be stubbed without a live Computer (or a Mockito inline mock maker, which this project
 * doesn't depend on) -- so the age-vs-lifespan boundary math that check() relies on is exercised
 * directly here instead, via the package-private {@link VSphereCloudRetentionStrategy#isPastLifespan}.
 */
class VSphereCloudRetentionStrategyTest {

    @Test
    void idleMinutesReturnsConstructorValue() {
        assertThat(new VSphereCloudRetentionStrategy(42).getIdleMinutes(), is(42));
    }

    @Test
    void lifespanMinutesDefaultsToZero() {
        // Backward compatibility: existing configs that predate this field must keep behaving
        // exactly as they did when this strategy was purely keep-until-idle.
        assertThat(new VSphereCloudRetentionStrategy(5).getLifespanMinutes(), is(0));
    }

    @Test
    void lifespanMinutesIsSettable() {
        VSphereCloudRetentionStrategy strategy = new VSphereCloudRetentionStrategy(5);
        strategy.setLifespanMinutes(120);
        assertThat(strategy.getLifespanMinutes(), is(120));
    }

    @Test
    void isPastLifespanIsAlwaysFalseWhenDisabled() {
        long hugeAge = TimeUnit.DAYS.toMillis(3650);
        assertThat(VSphereCloudRetentionStrategy.isPastLifespan(hugeAge, 0), is(false));
        assertThat(VSphereCloudRetentionStrategy.isPastLifespan(hugeAge, -1), is(false));
    }

    @Test
    void isPastLifespanIsFalseBelowTheThreshold() {
        long ageMillis = TimeUnit.MINUTES.toMillis(119);
        assertThat(VSphereCloudRetentionStrategy.isPastLifespan(ageMillis, 120), is(false));
    }

    @Test
    void isPastLifespanIsFalseExactlyAtTheThreshold() {
        // check() uses a strict ">", matching how the standalone strategy this replaces behaved.
        long ageMillis = TimeUnit.MINUTES.toMillis(120);
        assertThat(VSphereCloudRetentionStrategy.isPastLifespan(ageMillis, 120), is(false));
    }

    @Test
    void isPastLifespanIsTrueJustPastTheThreshold() {
        long ageMillis = TimeUnit.MINUTES.toMillis(120) + 1;
        assertThat(VSphereCloudRetentionStrategy.isPastLifespan(ageMillis, 120), is(true));
    }

    @Test
    void isPastLifespanHandlesTheMotivatingExampleFromReview() {
        // "retain VMs until they've been idle for an hour, or until they were over two days old"
        int twoDaysInMinutes = (int) TimeUnit.DAYS.toMinutes(2);
        long justUnderTwoDays = TimeUnit.DAYS.toMillis(2) - TimeUnit.MINUTES.toMillis(1);
        long justOverTwoDays = TimeUnit.DAYS.toMillis(2) + TimeUnit.MINUTES.toMillis(1);

        assertThat(VSphereCloudRetentionStrategy.isPastLifespan(justUnderTwoDays, twoDaysInMinutes), is(false));
        assertThat(VSphereCloudRetentionStrategy.isPastLifespan(justOverTwoDays, twoDaysInMinutes), is(true));
    }

    @Test
    void isPastLifespanHandlesMultiYearAgesWithoutOverflow() {
        long tenYearsMillis = TimeUnit.DAYS.toMillis(3650);
        int oneYearInMinutes = (int) TimeUnit.DAYS.toMinutes(365);
        assertThat(VSphereCloudRetentionStrategy.isPastLifespan(tenYearsMillis, oneYearInMinutes), is(true));
    }

    @Test
    void isAcceptingTasksIsTrueForAFreshInstance() {
        // isAcceptingTasks() ignores its Computer argument entirely (it only reflects our own
        // atEndOfLife flag), so null is a valid, safe argument here without needing a real Computer.
        assertThat(new VSphereCloudRetentionStrategy(5).isAcceptingTasks(null), is(true));
    }

    @Test
    void readResolvePreservesIdleMinutes() {
        VSphereCloudRetentionStrategy strategy = new VSphereCloudRetentionStrategy(7);
        Object resolved = strategy.readResolve();
        assertThat(resolved, not(sameInstance((Object) strategy)));
        assertThat(((VSphereCloudRetentionStrategy) resolved).getIdleMinutes(), is(7));
    }

    @Test
    void readResolvePreservesLifespanMinutesToo() {
        // This is the specific thing readResolve() must get right for the new field: it rebuilds
        // the instance from scratch (to work around idleMinutes not surviving persistence via the
        // inherited CloudRetentionStrategy field otherwise), so it's easy to forget to carry over a
        // field added after that trick was first written.
        VSphereCloudRetentionStrategy strategy = new VSphereCloudRetentionStrategy(7);
        strategy.setLifespanMinutes(2880);

        Object resolved = strategy.readResolve();

        assertThat(((VSphereCloudRetentionStrategy) resolved).getLifespanMinutes(), is(2880));
    }

    @Test
    void descriptorHasADistinctDisplayName() {
        assertThat(VSphereCloudRetentionStrategy.DESCRIPTOR.getDisplayName(),
                is("vSphere Keep-Until-Idle Retention Strategy"));
    }

    @Test
    void descriptorVisibilityFilterHidesOnlyItsOwnDescriptor() {
        VSphereCloudRetentionStrategy.DescriptorVisibilityFilterImpl filter =
                new VSphereCloudRetentionStrategy.DescriptorVisibilityFilterImpl();

        assertThat(filter.filter(null, VSphereCloudRetentionStrategy.DESCRIPTOR), is(false));

        Descriptor<?> unrelatedDescriptor = new RunOnceCloudRetentionStrategy(5).getDescriptor();
        assertThat(filter.filter(null, unrelatedDescriptor), is(true));
    }
}
