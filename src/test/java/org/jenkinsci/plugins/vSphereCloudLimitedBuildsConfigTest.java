package org.jenkinsci.plugins;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;

import hudson.model.Node.Mode;
import hudson.slaves.JNLPLauncher;
import hudson.slaves.RetentionStrategy;
import java.util.Collections;
import jenkins.model.Jenkins;
import org.junit.jupiter.api.Test;
import org.jvnet.hudson.test.JenkinsRule;
import org.jvnet.hudson.test.junit.jupiter.WithJenkins;

/**
 * How the "Disconnect after Limited Builds" setting of an agent is read and saved. A running Jenkins is needed to
 * build and reload an agent, even though the tests do not use it otherwise.
 */
@WithJenkins
class vSphereCloudLimitedBuildsConfigTest {

    private static vSphereCloudSlave agentWithLimit(String limit) throws Exception {
        return new vSphereCloudSlave(
                "agent",
                "",
                "/home/jenkins",
                "1",
                Mode.NORMAL,
                "",
                new JNLPLauncher(),
                RetentionStrategy.NOOP,
                Collections.emptyList(),
                "some-cloud",
                "some-vm",
                false,
                false,
                "",
                "60",
                "Nothing",
                limit);
    }

    private static void assertLimit(String configured, int expected) throws Exception {
        final vSphereCloudSlave agent = agentWithLimit(configured);
        assertThat("on the agent, for " + configured, agent.getLimitedTestRunCount(), is(expected));
        assertThat(
                "on its launcher, for " + configured,
                ((vSphereCloudLauncher) agent.getLauncher()).getLimitedTestRunCount(),
                is(expected));
    }

    @Test
    void aMissingOrInvalidLimitMeansNoLimit(JenkinsRule r) throws Exception {
        assertLimit(null, 0);
        assertLimit("", 0);
        assertLimit("abc", 0);
        assertLimit("0", 0);
    }

    @Test
    void aValidLimitIsKept(JenkinsRule r) throws Exception {
        assertLimit("3", 3);
    }

    @Test
    void theLimitIsSaved(JenkinsRule r) throws Exception {
        final String xml = Jenkins.XSTREAM2.toXML(agentWithLimit("3"));

        final vSphereCloudSlave reloaded = (vSphereCloudSlave) Jenkins.XSTREAM2.fromXML(xml);

        assertThat(reloaded.getLimitedTestRunCount(), is(3));
    }

    @Test
    void anAgentSavedWithoutALimitLoadsWithNoLimit(JenkinsRule r) throws Exception {
        final String xml = Jenkins.XSTREAM2
                .toXML(agentWithLimit("3"))
                .replaceAll("<LimitedTestRunCount>.*?</LimitedTestRunCount>", "");

        final vSphereCloudSlave reloaded = (vSphereCloudSlave) Jenkins.XSTREAM2.fromXML(xml);

        assertThat(reloaded.getLimitedTestRunCount(), is(0));
        assertThat(reloaded.getNumberOfLimitedTestRuns(), is(0));
    }
}
