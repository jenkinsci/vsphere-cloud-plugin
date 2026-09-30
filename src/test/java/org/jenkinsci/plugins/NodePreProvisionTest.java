package org.jenkinsci.plugins;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;

import hudson.slaves.JNLPLauncher;
import hudson.slaves.RetentionStrategy;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;
import org.jenkinsci.plugins.vsphere.VSphereConnectionConfig;
import org.jenkinsci.plugins.vsphere.tools.CloudProvisioningRecord;
import org.jenkinsci.plugins.vsphere.tools.CloudProvisioningState;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Covers {@link vSphereCloud#preProvisionNodes}'s decision of how many more nodes are needed to
 * reach a template's {@code instancesMin}, given some are already active.
 *
 * <p>The original draft of this test asserted against a {@link CloudProvisioningState} it built
 * itself, separately from the {@link vSphereCloud} under test -- but preProvisionNodes() only ever
 * looks at the cloud's own lazily-created internal state (a private field), so that assertion could
 * never have observed anything real. Fixed here via {@link vSphereCloud#getTemplateState}, a
 * package-private test hook onto that same internal instance.
 *
 * <p>Actually spinning up a VM for each node preProvisionNodes() decides it needs runs
 * asynchronously on {@code Computer.threadPoolForRemoting} and calls {@code Jenkins.getInstance()},
 * neither of which is available outside a running Jenkins. So every test here pins the cloud's
 * instanceCap down to the number of nodes already "active", which makes {@code cloudHasCapacity()}
 * return false and stops preProvisionNodes() from ever reaching that code -- while still exercising,
 * and letting us observe via the log line it emits, the actual "how many more do we need" decision.
 */
class NodePreProvisionTest {

    private Logger vsphereCloudLogger;
    private Handler logCapture;
    private List<LogRecord> loggedMessages;

    @BeforeEach
    void setup() {
        loggedMessages = new ArrayList<>();
        vsphereCloudLogger = Logger.getLogger("vsphere-cloud");
        vsphereCloudLogger.setLevel(Level.ALL);
        logCapture = new Handler() {
            @Override
            public void publish(LogRecord record) {
                loggedMessages.add(record);
            }

            @Override
            public void flush() {}

            @Override
            public void close() {}
        };
        vsphereCloudLogger.addHandler(logCapture);
    }

    @AfterEach
    void teardown() {
        vsphereCloudLogger.removeHandler(logCapture);
    }

    @Test
    void wantsOneMoreNodeWhenOneOfTwoRequiredIsAlreadyActive() {
        final vSphereCloudSlaveTemplate template = stubTemplate("cap10min2", 10, 2);
        // instanceCap == 1: the cloud considers itself full once that one node is active, so
        // preProvisionNodes() logs its decision and then stops, without trying to act on it.
        final vSphereCloud cloud = stubCloud(1, template);
        markOneNodeActive(cloud, template);

        cloud.preProvisionNodes(template);

        assertThat(preProvisionDecisionMessage(), containsString("should pre-provision 1 nodes"));
    }

    @Test
    void wantsNoMoreNodesOnceInstancesMinIsAlreadyMet() {
        final vSphereCloudSlaveTemplate template = stubTemplate("cap10min1", 10, 1);
        final vSphereCloud cloud = stubCloud(1, template);
        markOneNodeActive(cloud, template);

        cloud.preProvisionNodes(template);

        assertThat(preProvisionDecisionMessage(), containsString("should pre-provision 0 nodes"));
    }

    @Test
    void doesNotCountAnUnrelatedTemplatesActiveNodes() {
        final vSphereCloudSlaveTemplate wanted = stubTemplate("wanted-cap10min1", 10, 1);
        final vSphereCloudSlaveTemplate other = stubTemplate("other-cap10min5", 10, 5);
        // instanceCap == 1: "other"'s one active node alone already fills the whole cloud, so
        // preProvisionNodes() logs its decision for "wanted" and then stops there too.
        final vSphereCloud cloud = stubCloud(1, wanted, other);
        markOneNodeActive(cloud, other);

        cloud.preProvisionNodes(wanted);

        // "wanted" has nothing active yet, so it still needs its own 1 -- unaffected by "other"
        // already having (and still needing many more of) its own active nodes. If the two
        // templates' records were ever mixed up, this would come out wrong.
        assertThat(preProvisionDecisionMessage(), containsString("should pre-provision 1 nodes"));
    }

    private static void markOneNodeActive(vSphereCloud cloud, vSphereCloudSlaveTemplate template) {
        final CloudProvisioningState state = cloud.getTemplateState();
        final CloudProvisioningRecord record = state.getOrCreateRecord(template);
        state.provisionedSlaveNowActive(record, template.getCloneNamePrefix() + "-already-active");
    }

    /**
     * preProvisionNodes() logs its "should pre-provision N nodes" decision first, then -- only
     * when N &gt; 0 -- goes on to log the separate cloud-capacity check too, so the decision line
     * is not reliably the last one logged; find it explicitly instead.
     */
    private String preProvisionDecisionMessage() {
        for (LogRecord record : loggedMessages) {
            if (record.getMessage() != null && record.getMessage().contains("should pre-provision")) {
                return record.getMessage();
            }
        }
        throw new AssertionError(
                "preProvisionNodes() never logged a \"should pre-provision\" decision; logged: " + loggedMessages);
    }

    private static vSphereCloud stubCloud(int instanceCap, vSphereCloudSlaveTemplate... templates) {
        final VSphereConnectionConfig vsConnectionConfig =
                new VSphereConnectionConfig("vsHost", false, "credentialsId");
        return new vSphereCloud(vsConnectionConfig, "vsDescription", 100, instanceCap, false, Arrays.asList(templates));
    }

    private static vSphereCloudSlaveTemplate stubTemplate(String prefix, int templateInstanceCap, int instancesMin) {
        // labelString is deliberately left null: vSphereCloudSlaveTemplate's readResolve() calls
        // Label.parse(labelString), which needs a live Jenkins instance to resolve a non-null/
        // non-blank label expression, but short-circuits safely when it's null.
        return new vSphereCloudSlaveTemplate(
                prefix,
                "",
                null,
                null,
                false,
                null,
                null,
                null,
                null,
                null,
                null,
                templateInstanceCap,
                1,
                null,
                null,
                null,
                false,
                false,
                0,
                0,
                false,
                null,
                null,
                instancesMin,
                null,
                new JNLPLauncher(),
                RetentionStrategy.NOOP,
                null,
                null,
                null);
    }
}
