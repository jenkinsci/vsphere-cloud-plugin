package org.jenkinsci.plugins.vsphere.builders;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.notNullValue;

import hudson.model.FreeStyleProject;
import java.util.HashMap;
import java.util.Map;
import org.jenkinsci.plugins.structs.describable.DescribableModel;
import org.jenkinsci.plugins.vsphere.VSphereBuildStepContainer;
import org.jenkinsci.plugins.vsphere.VSphereConnectionConfig;
import org.jenkinsci.plugins.vsphere.vSphereCloud;
import org.junit.jupiter.api.Test;
import org.jvnet.hudson.test.Issue;
import org.jvnet.hudson.test.JenkinsRule;
import org.jvnet.hudson.test.junit.jupiter.WithJenkins;

@WithJenkins
class RenameSnapshotTest {

    private static Map<String, Object> baseArgs() {
        Map<String, Object> args = new HashMap<>();
        args.put("vm", "some-vm");
        args.put("oldName", "current");
        args.put("newName", "old");
        args.put("newDescription", "");
        return args;
    }

    @Test
    @Issue("JENKINS-56127")
    void failOnNoExistIsAKnownDataBoundParameter(JenkinsRule r) {
        DescribableModel<RenameSnapshot> model = DescribableModel.of(RenameSnapshot.class);
        assertThat(model.getParameter("failOnNoExist"), notNullValue());
    }

    @Test
    @Issue("JENKINS-56127")
    void failOnNoExistDefaultsToTrueForBackwardCompatibility(JenkinsRule r) throws Exception {
        // Existing configs / pipelines that omit the option must keep failing on a missing snapshot
        RenameSnapshot step = DescribableModel.of(RenameSnapshot.class).instantiate(baseArgs());
        assertThat(step.isFailOnNoExist(), is(true));
    }

    @Test
    @Issue("JENKINS-56127")
    void failOnNoExistIsHonouredWhenSet(JenkinsRule r) throws Exception {
        Map<String, Object> args = baseArgs();
        args.put("failOnNoExist", false);
        RenameSnapshot step = DescribableModel.of(RenameSnapshot.class).instantiate(args);
        assertThat(step.isFailOnNoExist(), is(false));
    }

    @Test
    @Issue("JENKINS-56127")
    void failOnNoExistSurvivesFreestyleConfigRoundTrip(JenkinsRule r) throws Exception {
        r.jenkins.clouds.add(new vSphereCloud(
                new VSphereConnectionConfig("vcenter.example.com", false, "creds"), "some-server", 0, 0, false, null));
        RenameSnapshot step = new RenameSnapshot("some-vm", "current", "old", "");
        step.setFailOnNoExist(false);
        FreeStyleProject p = r.createFreeStyleProject();
        p.getBuildersList().add(new VSphereBuildStepContainer(step, "some-server"));

        r.configRoundtrip(p);

        RenameSnapshot saved = (RenameSnapshot)
                ((VSphereBuildStepContainer) p.getBuildersList().get(0)).getBuildStep();
        assertThat(saved.isFailOnNoExist(), is(false));
    }
}
