package org.jenkinsci.plugins.vsphere;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.containsInAnyOrder;
import static org.hamcrest.Matchers.empty;
import static org.hamcrest.Matchers.is;

import hudson.model.Node.Mode;
import hudson.model.TaskListener;
import hudson.slaves.ComputerLauncher;
import hudson.slaves.RetentionStrategy;
import hudson.slaves.SlaveComputer;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.jvnet.hudson.test.Issue;
import org.jvnet.hudson.test.JenkinsRule;
import org.jvnet.hudson.test.junit.jupiter.WithJenkins;

/** JENKINS-62570: saving the config of a connected agent must not leak the launcher that holds its connection. */
@WithJenkins
class vSphereCloudSlaveLauncherLeakTest {

    /** Stands in for e.g. an SSHLauncher, which keeps its live connection inside the launcher instance. */
    private static final class RecordingLauncher extends ComputerLauncher {
        final String id;
        final List<String> events;

        RecordingLauncher(String id, List<String> events) {
            this.id = id;
            this.events = events;
        }

        @Override
        public void afterDisconnect(SlaveComputer computer, TaskListener listener) {
            events.add("afterDisconnect:" + id);
        }

        @Override
        public String toString() {
            return id;
        }
    }

    private static vSphereCloudSlave agentWith(ComputerLauncher delegate) throws Exception {
        return new vSphereCloudSlave(
                "agent",
                "",
                "/home/jenkins",
                "1",
                Mode.NORMAL,
                "",
                delegate,
                RetentionStrategy.NOOP,
                Collections.emptyList(),
                "some-cloud",
                "some-vm",
                false,
                false,
                "",
                "60",
                "Shutdown",
                "0");
    }

    private static vSphereCloudLauncher launcherOf(vSphereCloudSlave agent) {
        return (vSphereCloudLauncher) agent.getLauncher();
    }

    @Test
    @Issue("JENKINS-62570")
    void replacedDelegateIsTornDownAtDisconnectWhenConfigWasSavedWhileConnected(JenkinsRule r) throws Exception {
        List<String> events = new ArrayList<>();
        vSphereCloudSlave before = agentWith(new RecordingLauncher("old", events));
        vSphereCloudSlave after = agentWith(new RecordingLauncher("new", events));

        vSphereCloudSlave.carryOverConnectedLauncher(before, after, true);
        launcherOf(after).tearDownRetiredDelegates(null, TaskListener.NULL);

        assertThat(events, contains("afterDisconnect:old"));
    }

    @Test
    @Issue("JENKINS-62570")
    void nothingIsCarriedOverWhenTheAgentWasNotConnected(JenkinsRule r) throws Exception {
        List<String> events = new ArrayList<>();
        vSphereCloudSlave before = agentWith(new RecordingLauncher("old", events));
        vSphereCloudSlave after = agentWith(new RecordingLauncher("new", events));

        vSphereCloudSlave.carryOverConnectedLauncher(before, after, false);
        launcherOf(after).tearDownRetiredDelegates(null, TaskListener.NULL);

        assertThat(events, is(empty()));
    }

    @Test
    @Issue("JENKINS-62570")
    void retiredDelegatesAreTornDownOnlyOnce(JenkinsRule r) throws Exception {
        List<String> events = new ArrayList<>();
        vSphereCloudSlave before = agentWith(new RecordingLauncher("old", events));
        vSphereCloudSlave after = agentWith(new RecordingLauncher("new", events));

        vSphereCloudSlave.carryOverConnectedLauncher(before, after, true);
        launcherOf(after).tearDownRetiredDelegates(null, TaskListener.NULL);
        launcherOf(after).tearDownRetiredDelegates(null, TaskListener.NULL);

        assertThat(events, contains("afterDisconnect:old"));
    }

    @Test
    @Issue("JENKINS-62570")
    void everyReplacedDelegateIsTornDownWhenSavedRepeatedlyWhileConnected(JenkinsRule r) throws Exception {
        List<String> events = new ArrayList<>();
        vSphereCloudSlave first = agentWith(new RecordingLauncher("first", events));
        vSphereCloudSlave second = agentWith(new RecordingLauncher("second", events));
        vSphereCloudSlave third = agentWith(new RecordingLauncher("third", events));

        vSphereCloudSlave.carryOverConnectedLauncher(first, second, true);
        vSphereCloudSlave.carryOverConnectedLauncher(second, third, true);
        launcherOf(third).tearDownRetiredDelegates(null, TaskListener.NULL);

        // The live delegate ("third") is torn down by the regular disconnect procedure, not by this
        assertThat(events, containsInAnyOrder("afterDisconnect:first", "afterDisconnect:second"));
    }
}
