package org.jenkinsci.plugins.vsphere.tools;

import io.jenkins.plugins.casc.misc.ConfiguredWithCode;
import io.jenkins.plugins.casc.misc.JenkinsConfiguredWithCodeRule;
import io.jenkins.plugins.casc.misc.junit.jupiter.WithJenkinsConfiguredWithCode;
import org.jenkinsci.plugins.vSphereCloud;
import org.junit.jupiter.api.Test;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.core.Is.is;

/**
 * Covers the fix for PR #176's reported symptom where re-saving the cloud configuration
 * left the previous {@code vSphereCloud}'s connection pool (and its background thread /
 * vCenter session) running indefinitely under its old settings, since Jenkins core
 * replaces a reconfigured {@link hudson.slaves.Cloud} with no destroy/removal hook.
 */
@WithJenkinsConfiguredWithCode
class VSphereConnectionPoolOrphanReapingTest {

    @Test
    @ConfiguredWithCode("configuration-as-code.yml")
    void pool_is_not_reaped_while_its_owning_cloud_is_still_registered(JenkinsConfiguredWithCodeRule r) {
        vSphereCloud owner = new vSphereCloud(makeConnectionConfig(), "orphan-owner", 0, 0, null);
        r.jenkins.clouds.add(owner);
        VSphereConnectionPool pool = new VSphereConnectionPool(makeConnectionConfig(), owner, 0, 0, 0, 0);
        try {
            assertThat(pool.isOrphaned(), is(false));

            VSphereConnectionPoolRegistry.reapOrphans();

            assertThat(VSphereConnectionPoolRegistry.isTracked(pool), is(true));
        } finally {
            r.jenkins.clouds.remove(owner);
            pool.shutdown();
        }
    }

    @Test
    @ConfiguredWithCode("configuration-as-code.yml")
    void pool_is_reaped_once_its_owning_cloud_is_replaced(JenkinsConfiguredWithCodeRule r) {
        vSphereCloud owner = new vSphereCloud(makeConnectionConfig(), "orphan-owner", 0, 0, null);
        r.jenkins.clouds.add(owner);
        VSphereConnectionPool pool = new VSphereConnectionPool(makeConnectionConfig(), owner, 0, 0, 0, 0);

        // Simulate what Jenkins core does when the cloud config is re-saved: the old
        // Cloud instance is dropped from Jenkins.clouds in favour of a new one.
        r.jenkins.clouds.remove(owner);
        assertThat(pool.isOrphaned(), is(true));

        VSphereConnectionPoolRegistry.reapOrphans();

        assertThat(VSphereConnectionPoolRegistry.isTracked(pool), is(false));
    }

    private static org.jenkinsci.plugins.vsphere.VSphereConnectionConfig makeConnectionConfig() {
        return new org.jenkinsci.plugins.vsphere.VSphereConnectionConfig("https://test-host", false, null);
    }
}
