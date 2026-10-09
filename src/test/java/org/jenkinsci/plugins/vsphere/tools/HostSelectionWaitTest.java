package org.jenkinsci.plugins.vsphere.tools;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.nullValue;
import static org.junit.jupiter.api.Assertions.assertThrows;

import groovy.lang.Closure;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import org.jenkinsci.plugins.vsphere.tools.VSphereHostSelection.Evaluation;
import org.jenkinsci.plugins.vsphere.tools.VSphereHostSelection.HostCandidate;
import org.junit.jupiter.api.Test;

class HostSelectionWaitTest {

    private static HostCandidate host(String name) {
        return new HostCandidate(name, true, false, 0, 10000, 0, 10000L);
    }

    private static Evaluation none(HostSelectionWaitReason reason) {
        return Evaluation.none(reason, "nothing today.");
    }

    private static Evaluation some(String... names) {
        List<HostCandidate> hosts = new ArrayList<>();
        for (String name : names) {
            hosts.add(host(name));
        }
        return Evaluation.eligible(hosts);
    }

    /** A clock that only moves when the code under test sleeps. */
    private static final class FakeTime {
        final AtomicLong now = new AtomicLong(1_000_000L);
        final AtomicInteger sleeps = new AtomicInteger();
        final VSphereHostSelection.Sleeper sleeper = millis -> {
            sleeps.incrementAndGet();
            now.addAndGet(millis);
        };
    }

    // --- the reasons ---

    @Test
    void busyHostsAreTransientAndTheRestPersistent() {
        assertThat(HostSelectionWaitReason.BELOW_FREE_RESOURCE_LIMITS.isTransient(), is(true));
        assertThat(HostSelectionWaitReason.NO_HOST_WITH_FREE_RAM_FOR_VM.isTransient(), is(true));
        assertThat(HostSelectionWaitReason.NO_USABLE_HOSTS.isTransient(), is(false));
        assertThat(HostSelectionWaitReason.NO_HOST_FITS_VM_SIZE.isTransient(), is(false));
        assertThat(HostSelectionWaitReason.NO_USAGE_STATISTICS.isTransient(), is(false));
        for (HostSelectionWaitReason reason : HostSelectionWaitReason.values()) {
            assertThat(reason.name(), reason.isPersistent(), is(!reason.isTransient()));
        }
    }

    @Test
    void onlyTheLimitsFailTheOperationWhenGivingUp() {
        for (HostSelectionWaitReason reason : HostSelectionWaitReason.values()) {
            assertThat(
                    reason.name(),
                    reason.failsWhenGivingUp(),
                    is(reason == HostSelectionWaitReason.BELOW_FREE_RESOURCE_LIMITS));
        }
    }

    // --- what the log says ---

    @Test
    void theLogSaysTheOperationFailsRightAwayWhenNotWaiting() {
        String msg = VSphereHostSelection.waitAnnouncement(
                none(HostSelectionWaitReason.BELOW_FREE_RESOURCE_LIMITS), 0, 15000);
        assertThat(msg, containsString("BELOW_FREE_RESOURCE_LIMITS, transient"));
        assertThat(msg, containsString("nothing today."));
        assertThat(msg, containsString("hostSelectionWaitSeconds is 0, so not waiting: will fail the operation now."));
    }

    @Test
    void theLogSaysVSphereDecidesRightAwayWhenNotWaitingAndNotFailing() {
        String msg = VSphereHostSelection.waitAnnouncement(none(HostSelectionWaitReason.NO_USABLE_HOSTS), 0, 15000);
        assertThat(msg, containsString("NO_USABLE_HOSTS, persistent"));
        assertThat(msg, containsString("not waiting: will let vSphere decide the placement now."));
    }

    @Test
    void theLogSaysForHowLongItWaitsAndWhatHappensAfter() {
        String msg =
                VSphereHostSelection.waitAnnouncement(none(HostSelectionWaitReason.NO_HOST_FITS_VM_SIZE), 90, 15000);
        assertThat(msg, containsString("hostSelectionWaitSeconds is 90: will check again every 15 second(s)"));
        assertThat(msg, containsString("then let vSphere decide the placement."));
        assertThat(
                VSphereHostSelection.waitAnnouncement(
                        none(HostSelectionWaitReason.BELOW_FREE_RESOURCE_LIMITS), 90, 15000),
                containsString("then fail the operation."));
    }

    @Test
    void theLogSaysWhenItWaitsForever() {
        String msg = VSphereHostSelection.waitAnnouncement(
                none(HostSelectionWaitReason.NO_USAGE_STATISTICS), VSphereHostSelection.WAIT_FOREVER, 15000);
        assertThat(msg, containsString("hostSelectionWaitSeconds is unlimited"));
        assertThat(msg, containsString("for as long as it takes."));
    }

    // --- waiting ---

    @Test
    void waitingIsSkippedWhenAHostIsAlreadyEligible() throws Exception {
        FakeTime time = new FakeTime();
        Evaluation initial = some("a");
        Evaluation result = VSphereHostSelection.waitForEligible(
                initial,
                () -> {
                    throw new AssertionError("must not look again");
                },
                60,
                1000,
                time.now::get,
                time.sleeper,
                m -> {});
        assertThat(result, is(initial));
        assertThat(time.sleeps.get(), is(0));
    }

    @Test
    void zeroSecondsDoesNotWaitAndHandsBackTheReason() throws Exception {
        FakeTime time = new FakeTime();
        Evaluation initial = none(HostSelectionWaitReason.NO_USABLE_HOSTS);
        Evaluation result = VSphereHostSelection.waitForEligible(
                initial, () -> some("x"), 0, 1000, time.now::get, time.sleeper, m -> {});
        assertThat(result, is(initial));
        assertThat(time.sleeps.get(), is(0));
    }

    @Test
    void waitingReturnsAsSoonAsAHostComesBack() throws Exception {
        FakeTime time = new FakeTime();
        AtomicInteger looks = new AtomicInteger();
        List<String> log = new ArrayList<>();
        Evaluation result = VSphereHostSelection.waitForEligible(
                none(HostSelectionWaitReason.NO_USABLE_HOSTS),
                () -> looks.incrementAndGet() < 3 ? none(HostSelectionWaitReason.NO_USABLE_HOSTS) : some("late"),
                60,
                1000,
                time.now::get,
                time.sleeper,
                log::add);
        assertThat(result.isEligible(), is(true));
        assertThat(result.getEligible().get(0).getName(), is("late"));
        assertThat(time.sleeps.get(), is(3));
        assertThat(log.get(0), containsString("After 3 second(s), 1 host(s) are available."));
    }

    @Test
    void whenTimeRunsOutTheLatestReasonIsHandedBackNotThrown() throws Exception {
        FakeTime time = new FakeTime();
        AtomicInteger looks = new AtomicInteger();
        List<String> log = new ArrayList<>();
        Evaluation result = VSphereHostSelection.waitForEligible(
                none(HostSelectionWaitReason.NO_USABLE_HOSTS),
                // the situation changes while waiting: the hosts are back, but busy
                () -> none(HostSelectionWaitReason.BELOW_FREE_RESOURCE_LIMITS),
                5,
                2000,
                time.now::get,
                time.sleeper,
                log::add);
        assertThat(result.isEligible(), is(false));
        assertThat(result.getReason(), is(HostSelectionWaitReason.BELOW_FREE_RESOURCE_LIMITS));
        // 2s + 2s + the remaining 1s: never sleeps past the deadline.
        assertThat(time.sleeps.get(), is(3));
        assertThat(time.now.get(), is(1_005_000L));
        assertThat(log.get(0), containsString("Gave up after waiting 5 second(s)"));
        assertThat(looks.get(), is(0));
    }

    @Test
    void waitingForeverOnlyEndsWhenAHostComesBack() throws Exception {
        FakeTime time = new FakeTime();
        AtomicInteger looks = new AtomicInteger();
        Evaluation result = VSphereHostSelection.waitForEligible(
                none(HostSelectionWaitReason.NO_USAGE_STATISTICS),
                () -> looks.incrementAndGet() < 1000
                        ? none(HostSelectionWaitReason.NO_USAGE_STATISTICS)
                        : some("eventually"),
                VSphereHostSelection.WAIT_FOREVER,
                15000,
                time.now::get,
                time.sleeper,
                m -> {});
        assertThat(result.isEligible(), is(true));
        assertThat(time.sleeps.get(), is(1000));
    }

    @Test
    void anInterruptedWaitFailsAndKeepsTheInterruptFlag() {
        VSphereException e = assertThrows(
                VSphereException.class,
                () -> VSphereHostSelection.waitForEligible(
                        none(HostSelectionWaitReason.NO_USABLE_HOSTS),
                        () -> none(HostSelectionWaitReason.NO_USABLE_HOSTS),
                        VSphereHostSelection.WAIT_FOREVER,
                        1000,
                        System::currentTimeMillis,
                        millis -> {
                            throw new InterruptedException();
                        },
                        m -> {}));
        assertThat(e.getMessage(), containsString("Interrupted"));
        assertThat(Thread.interrupted(), is(true)); // also clears it for the other tests
    }

    // --- the notification closure ---

    @Test
    void noClosureMeansNoListener() throws Exception {
        assertThat(HostSelectionWaitNotification.of(null), nullValue());
    }

    @Test
    void somethingThatIsNotAClosureIsRejected() {
        VSphereException e =
                assertThrows(VSphereException.class, () -> HostSelectionWaitNotification.of("not a closure"));
        assertThat(e.getMessage(), containsString("must be a closure"));
    }

    @Test
    void theClosureGetsTheMessageAndTheReason() throws Exception {
        List<Object> seen = new ArrayList<>();
        Closure<Object> closure = new Closure<Object>(this) {
            @SuppressWarnings("unused")
            public Object doCall(String message, HostSelectionWaitReason reason) {
                seen.add(message);
                seen.add(reason);
                return null;
            }
        };
        HostSelectionOptions.Listener listener = HostSelectionWaitNotification.of(closure);
        listener.hostSelectionWaiting("no hosts", HostSelectionWaitReason.NO_USABLE_HOSTS);
        assertThat(seen, is(Arrays.<Object>asList("no hosts", HostSelectionWaitReason.NO_USABLE_HOSTS)));
    }

    @Test
    void theListenerAndTheIgnoreErrorsOptionAreOffByDefaultAndSurviveTheOtherWithers() {
        assertThat(HostSelectionOptions.NONE.getWaitListener(), nullValue());
        assertThat(HostSelectionOptions.NONE.isIgnoreWaitListenerErrors(), is(false));
        HostSelectionOptions.Listener listener = (m, r) -> {};
        HostSelectionOptions o = HostSelectionOptions.NONE
                .withWaitListener(listener, true)
                .withWaitSeconds(30)
                .withLimits(new HostLimits(1, 0, 0, 0))
                .withVmSize(2, 1024L);
        assertThat(o.getWaitListener() == listener, is(true));
        assertThat(o.isIgnoreWaitListenerErrors(), is(true));
        assertThat(o.getWaitSeconds(), is(30L));
        assertThat(o.getWaitListener(), not(nullValue()));
    }
}
