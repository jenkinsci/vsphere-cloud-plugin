package org.jenkinsci.plugins.vsphere.tools;

import hudson.util.StreamTaskListener;
import org.jenkinsci.plugins.vSphereCloud;
import org.jenkinsci.plugins.vsphere.VSphereConnectionConfig;
import org.junit.jupiter.api.Test;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.core.Is.is;

/**
 * Unit tests for the maintenance-mode toggle and message on {@link vSphereCloud}.
 *
 * No live Jenkins/vSphere is required for these: they exercise configuration plumbing
 * and the fast (not-in-maintenance) path of {@link vSphereCloud#waitWhileInMaintenanceMode}
 * only, which must not touch {@code Jenkins.getInstance()} so it stays usable outside a
 * running Jenkins - see {@link VSphereConnectionPoolOrphanReapingTest} for coverage of the
 * blocking path, which does need a live Jenkins to re-resolve the cloud by name.
 */
class VSphereCloudMaintenanceModeTest {

    @Test
    void maintenance_mode_is_disabled_by_default() {
        assertThat(makeCloud().isMaintenanceMode(), is(false));
    }

    @Test
    void maintenance_message_defaults_to_empty() {
        assertThat(makeCloud().getMaintenanceMessage(), is(""));
    }

    @Test
    void maintenance_settings_are_stored_and_retrieved_correctly() {
        vSphereCloud cloud = makeCloud();

        cloud.setMaintenanceMode(true);
        cloud.setMaintenanceMessage("vCenter is down for patching until 18:00 UTC.");

        assertThat(cloud.isMaintenanceMode(), is(true));
        assertThat(cloud.getMaintenanceMessage(), is("vCenter is down for patching until 18:00 UTC."));
    }

    @Test
    void disabling_maintenance_mode_restores_flag_to_false() {
        vSphereCloud cloud = makeCloud();
        cloud.setMaintenanceMode(true);
        cloud.setMaintenanceMode(false);
        assertThat(cloud.isMaintenanceMode(), is(false));
    }

    @Test
    void waiting_returns_immediately_when_not_in_maintenance_mode() throws InterruptedException {
        // Deliberately run with no Jenkins instance up: the not-in-maintenance fast path
        // must not need to look up Jenkins.getInstance() to re-resolve this cloud.
        vSphereCloud cloud = makeCloud();
        cloud.waitWhileInMaintenanceMode(StreamTaskListener.fromStdout());
    }

    private static vSphereCloud makeCloud() {
        return new vSphereCloud(makeConnectionConfig(), "test-cloud", 0, 0, null);
    }

    private static VSphereConnectionConfig makeConnectionConfig() {
        // 3-arg internal constructor: host, allowUntrustedCertificate, credentialsId
        return new VSphereConnectionConfig("https://test-host", false, null);
    }
}
