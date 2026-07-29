package org.jenkinsci.plugins.vsphere.tools;

import hudson.util.StreamTaskListener;
import io.jenkins.plugins.casc.misc.ConfiguredWithCode;
import io.jenkins.plugins.casc.misc.JenkinsConfiguredWithCodeRule;
import io.jenkins.plugins.casc.misc.junit.jupiter.WithJenkinsConfiguredWithCode;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.jenkinsci.plugins.vSphereCloud;
import org.junit.jupiter.api.Test;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.core.Is.is;

/**
 * Covers the actual blocking behaviour of {@link vSphereCloud#waitWhileInMaintenanceMode}: a
 * consumer thread must be blocked while a cloud is in maintenance mode, and must unblock once
 * maintenance mode is turned off - even when that happens on a *different* {@link vSphereCloud}
 * instance sharing the same name, mirroring how Jenkins core replaces a reconfigured
 * {@code Cloud} with a brand-new instance rather than mutating the existing one (see
 * {@link VSphereConnectionPoolOrphanReapingTest} for the same concern applied to the
 * connection pool).
 */
@WithJenkinsConfiguredWithCode
class VSphereCloudMaintenanceModeBlockingTest {

    @Test
    @ConfiguredWithCode("configuration-as-code.yml")
    void waiting_blocks_while_in_maintenance_mode_and_returns_once_it_ends(JenkinsConfiguredWithCodeRule r) throws Exception {
        vSphereCloud cloud = new vSphereCloud(makeConnectionConfig(), "maint-blocking-test", 0, 0, null);
        cloud.setMaintenanceMode(true);
        r.jenkins.clouds.add(cloud);
        try {
            final CountDownLatch waiting = new CountDownLatch(1);
            final CountDownLatch done = new CountDownLatch(1);
            Thread consumer = new Thread(() -> {
                waiting.countDown();
                try {
                    cloud.waitWhileInMaintenanceMode(StreamTaskListener.fromStdout());
                    done.countDown();
                } catch (InterruptedException ignored) {
                    // test teardown interrupting us; nothing to assert here
                }
            }, "maintenance-mode-consumer");
            consumer.setDaemon(true);
            consumer.start();

            assertThat(waiting.await(5, TimeUnit.SECONDS), is(true));
            // Still blocked shortly after starting - maintenance mode has not been lifted yet.
            assertThat(done.await(500, TimeUnit.MILLISECONDS), is(false));

            // Simulate reconfiguration replacing the cloud instance, as Jenkins core does on save.
            vSphereCloud reconfigured = new vSphereCloud(makeConnectionConfig(), "maint-blocking-test", 0, 0, null);
            reconfigured.setMaintenanceMode(false);
            r.jenkins.clouds.remove(cloud);
            r.jenkins.clouds.add(reconfigured);

            assertThat(done.await(20, TimeUnit.SECONDS), is(true));
        } finally {
            r.jenkins.clouds.removeIf(c -> c instanceof vSphereCloud
                    && "maint-blocking-test".equals(((vSphereCloud) c).getVsDescription()));
        }
    }

    private static org.jenkinsci.plugins.vsphere.VSphereConnectionConfig makeConnectionConfig() {
        return new org.jenkinsci.plugins.vsphere.VSphereConnectionConfig("https://test-host", false, null);
    }
}
