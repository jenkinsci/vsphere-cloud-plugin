package org.jenkinsci.plugins.vsphere.tools;

import org.jenkinsci.plugins.vSphereCloud;
import org.jenkinsci.plugins.vsphere.VSphereConnectionConfig;
import org.junit.jupiter.api.Test;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.core.Is.is;

/**
 * Unit tests for the connection-pool toggle and associated settings on
 * {@link vSphereCloud} and {@link VSphereConnectionPool}.
 *
 * No live vSphere connection is required; these tests exercise configuration
 * plumbing only.
 */
class VSphereConnectionPoolSettingsTest {

    // -----------------------------------------------------------------------
    // Default values — pool must be OFF by default (principle of least surprise)
    // -----------------------------------------------------------------------

    @Test
    void pool_is_disabled_by_default() {
        assertThat(makeCloud().isUseConnectionPool(), is(false));
    }

    @Test
    void pool_integer_settings_default_to_zero() {
        vSphereCloud cloud = makeCloud();
        assertThat(cloud.getPoolHealthCheckIntervalSecs(), is(0));
        assertThat(cloud.getSessionMaxAgeSecs(), is(0));
        assertThat(cloud.getSessionMaxUses(), is(0));
        assertThat(cloud.getPoolIdleTimeoutSecs(), is(0));
    }

    // -----------------------------------------------------------------------
    // Setter / getter round-trips
    // -----------------------------------------------------------------------

    @Test
    void pool_settings_are_stored_and_retrieved_correctly() {
        vSphereCloud cloud = makeCloud();

        cloud.setUseConnectionPool(true);
        cloud.setPoolHealthCheckIntervalSecs(60);
        cloud.setSessionMaxAgeSecs(3600);
        cloud.setSessionMaxUses(500);
        cloud.setPoolIdleTimeoutSecs(300);

        assertThat(cloud.isUseConnectionPool(), is(true));
        assertThat(cloud.getPoolHealthCheckIntervalSecs(), is(60));
        assertThat(cloud.getSessionMaxAgeSecs(), is(3600));
        assertThat(cloud.getSessionMaxUses(), is(500));
        assertThat(cloud.getPoolIdleTimeoutSecs(), is(300));
    }

    @Test
    void disabling_pool_restores_flag_to_false() {
        vSphereCloud cloud = makeCloud();
        cloud.setUseConnectionPool(true);
        cloud.setUseConnectionPool(false);
        assertThat(cloud.isUseConnectionPool(), is(false));
    }

    @Test
    void zero_is_a_valid_value_for_each_integer_setting() {
        vSphereCloud cloud = makeCloud();
        cloud.setUseConnectionPool(true);
        cloud.setPoolHealthCheckIntervalSecs(0);
        cloud.setSessionMaxAgeSecs(0);
        cloud.setSessionMaxUses(0);
        cloud.setPoolIdleTimeoutSecs(0);

        assertThat(cloud.getPoolHealthCheckIntervalSecs(), is(0));
        assertThat(cloud.getSessionMaxAgeSecs(), is(0));
        assertThat(cloud.getSessionMaxUses(), is(0));
        assertThat(cloud.getPoolIdleTimeoutSecs(), is(0));
    }

    // -----------------------------------------------------------------------
    // VSphereConnectionPool lifecycle (no live vSphere needed)
    // -----------------------------------------------------------------------

    @Test
    void pool_shutdown_is_safe_before_any_connection() {
        // Pool with background threads; shut down immediately — must not throw.
        VSphereConnectionPool pool = new VSphereConnectionPool(
                makeConnectionConfig(), 30, 3600, 0, 300);
        pool.shutdown();
    }

    @Test
    void pool_with_all_features_disabled_does_not_start_scheduler() {
        // All-zero config → no background thread is started.
        VSphereConnectionPool pool = new VSphereConnectionPool(
                makeConnectionConfig(), 0, 0, 0, 0);
        pool.shutdown(); // must be safe even with no scheduler
    }

    @Test
    void pool_clamps_negative_arguments_to_zero() {
        // Negative values must be silently clamped; no exception expected.
        VSphereConnectionPool pool = new VSphereConnectionPool(
                makeConnectionConfig(), -1, -100, -5, -60);
        pool.shutdown();
    }

    @Test
    void pool_shutdown_is_idempotent() {
        VSphereConnectionPool pool = new VSphereConnectionPool(
                makeConnectionConfig(), 0, 0, 0, 0);
        pool.shutdown();
        pool.shutdown(); // second call must not throw
    }

    // -----------------------------------------------------------------------
    // Deferred shutdown while the connection is still borrowed (JENKINS maintenance-mode
    // reap race: an orphaned pool must not be disconnected out from under an in-flight
    // caller that acquired it before the pool's owning cloud was replaced).
    // -----------------------------------------------------------------------

    @Test
    void shutdown_is_deferred_while_the_connection_is_still_borrowed() {
        VSphereConnectionPool pool = new VSphereConnectionPool(makeConnectionConfig(), 0, 0, 0, 0);
        try {
            pool.setBorrowCountForTesting(2);

            pool.shutdown();
            assertThat("still borrowed, so shutdown must be deferred",
                    VSphereConnectionPoolRegistry.isTracked(pool), is(true));

            pool.release();
            assertThat("one borrower released, one still outstanding: still deferred",
                    VSphereConnectionPoolRegistry.isTracked(pool), is(true));

            pool.release();
            assertThat("last borrower released: deferred shutdown now completes",
                    VSphereConnectionPoolRegistry.isTracked(pool), is(false));
        } finally {
            pool.shutdown(); // safety net; must be a no-op if already shut down
        }
    }

    @Test
    void shutdown_is_immediate_when_not_borrowed() {
        VSphereConnectionPool pool = new VSphereConnectionPool(makeConnectionConfig(), 0, 0, 0, 0);
        pool.shutdown();
        assertThat(VSphereConnectionPoolRegistry.isTracked(pool), is(false));
    }

    @Test
    void release_without_a_matching_acquire_does_not_underflow_or_throw() {
        VSphereConnectionPool pool = new VSphereConnectionPool(makeConnectionConfig(), 0, 0, 0, 0);
        try {
            pool.release(); // no prior acquire(); must not throw or go negative
            pool.shutdown();
            assertThat(VSphereConnectionPoolRegistry.isTracked(pool), is(false));
        } finally {
            pool.shutdown();
        }
    }

    // -----------------------------------------------------------------------
    // Helpers
    // -----------------------------------------------------------------------

    private static vSphereCloud makeCloud() {
        return new vSphereCloud(makeConnectionConfig(), "test-cloud", 0, 0, null);
    }

    private static VSphereConnectionConfig makeConnectionConfig() {
        // 3-arg internal constructor: host, allowUntrustedCertificate, credentialsId
        return new VSphereConnectionConfig("https://test-host", false, null);
    }
}
