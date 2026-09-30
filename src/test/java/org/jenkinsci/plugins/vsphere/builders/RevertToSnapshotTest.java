package org.jenkinsci.plugins.vsphere.builders;

import java.util.HashMap;
import java.util.Map;
import org.jenkinsci.plugins.structs.describable.DescribableModel;
import org.junit.jupiter.api.Test;
import org.jvnet.hudson.test.Issue;
import org.jvnet.hudson.test.JenkinsRule;
import org.jvnet.hudson.test.junit.jupiter.WithJenkins;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.notNullValue;

@WithJenkins
class RevertToSnapshotTest {

    @Test
    @Issue("JENKINS-76498")
    void suppressPowerOnIsAKnownDataBoundParameter(JenkinsRule r) {
        // Guards against a regression of the "Unknown parameter" warning: the model must
        // advertise suppressPowerOn as a settable parameter of the build step.
        DescribableModel<RevertToSnapshot> model = DescribableModel.of(RevertToSnapshot.class);
        assertThat(model.getParameter("suppressPowerOn"), notNullValue());
    }

    @Test
    void suppressPowerOnDefaultsToFalse(JenkinsRule r) throws Exception {
        // Backward compatibility: existing configs / pipelines that omit the field must behave
        // exactly as before (power state restored from the snapshot).
        Map<String, Object> args = new HashMap<>();
        args.put("vm", "some-vm");
        args.put("snapshotName", "clean");

        RevertToSnapshot step = DescribableModel.of(RevertToSnapshot.class).instantiate(args);

        assertThat(step.getVm(), is("some-vm"));
        assertThat(step.getSnapshotName(), is("clean"));
        assertThat(step.isSuppressPowerOn(), is(false));
    }

    @Test
    @Issue("JENKINS-76498")
    void suppressPowerOnIsHonouredWhenSet(JenkinsRule r) throws Exception {
        Map<String, Object> args = new HashMap<>();
        args.put("vm", "some-vm");
        args.put("snapshotName", "clean");
        args.put("suppressPowerOn", true);

        RevertToSnapshot step = DescribableModel.of(RevertToSnapshot.class).instantiate(args);

        assertThat(step.isSuppressPowerOn(), is(true));
    }
}
