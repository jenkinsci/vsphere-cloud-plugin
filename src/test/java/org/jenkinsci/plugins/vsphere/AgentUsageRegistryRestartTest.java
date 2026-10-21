package org.jenkinsci.plugins.vsphere;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;

import hudson.model.FreeStyleBuild;
import hudson.model.Node.Mode;
import hudson.slaves.JNLPLauncher;
import hudson.slaves.RetentionStrategy;
import java.util.Collections;
import org.jenkinsci.plugins.vSphereCloudSlave;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.jvnet.hudson.test.JenkinsRule;
import org.jvnet.hudson.test.junit.jupiter.JenkinsSessionExtension;

/** The usage of the agents through the real stop and start of Jenkins. */
class AgentUsageRegistryRestartTest {

    @RegisterExtension
    private final JenkinsSessionExtension sessions = new JenkinsSessionExtension();

    private static vSphereCloudSlave agent(String name, int limit) throws Exception {
        return new vSphereCloudSlave(
                name,
                "",
                "/home/jenkins",
                "1",
                Mode.NORMAL,
                "",
                new JNLPLauncher(),
                RetentionStrategy.NOOP,
                Collections.emptyList(),
                "some-cloud",
                "vm-" + name,
                false,
                false,
                "",
                "60",
                "Nothing",
                Integer.toString(limit));
    }

    @Test
    void theUsageSurvivesJenkinsBeingRestarted() throws Throwable {
        sessions.then(r -> {
            r.jenkins.addNode(agent("kept", 5));
            r.jenkins.addNode(agent("full", 2));
            r.jenkins.addNode(agent("gone", 5));
            use(r, "kept", 5, 2);
            use(r, "full", 2, 2);
            use(r, "gone", 5, 1);
            r.jenkins.removeNode(r.jenkins.getNode("gone"));
        });

        sessions.then(r -> {
            final AgentUsageRegistry registry = AgentUsageRegistry.get();
            // Taken back as it was.
            assertThat(registry.count("kept"), is(2));
            // It had reached its limit and nothing is using it now, so it would never be recycled: it starts again.
            assertThat(registry.count("full"), is(0));
            // Deleted before the restart.
            assertThat(registry.count("gone"), is(0));
        });
    }

    private static void use(JenkinsRule r, String node, int limit, int times) throws Exception {
        for (int i = 0; i < times; i++) {
            // Any build will do to tell the uses apart.
            final FreeStyleBuild build = r.buildAndAssertSuccess(r.createFreeStyleProject());
            AgentUsageRegistry.get().acquire(node, limit, build, () -> true);
        }
    }
}
