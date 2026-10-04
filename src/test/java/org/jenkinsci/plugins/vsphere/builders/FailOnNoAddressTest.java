package org.jenkinsci.plugins.vsphere.builders;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.notNullValue;

import hudson.model.FreeStyleProject;
import java.util.HashMap;
import java.util.Map;
import org.jenkinsci.plugins.structs.describable.DescribableModel;
import org.jenkinsci.plugins.vSphereCloud;
import org.jenkinsci.plugins.vsphere.VSphereBuildStepContainer;
import org.jenkinsci.plugins.vsphere.VSphereConnectionConfig;
import org.junit.jupiter.api.Test;
import org.jvnet.hudson.test.Issue;
import org.jvnet.hudson.test.JenkinsRule;
import org.jvnet.hudson.test.junit.jupiter.WithJenkins;

/** JENKINS-38472: not getting an IP address fails Clone, Deploy and PowerOn only on request. */
@WithJenkins
@Issue("JENKINS-38472")
class FailOnNoAddressTest {

    private static Map<String, Object> cloneArgs() {
        Map<String, Object> args = new HashMap<>();
        args.put("sourceName", "linux-template");
        args.put("clone", "new-vm");
        args.put("linkedClone", false);
        args.put("resourcePool", "Resources");
        args.put("cluster", "my-cluster");
        args.put("datastore", "");
        args.put("folder", "");
        args.put("powerOn", true);
        return args;
    }

    @Test
    void isAKnownDataBoundParameterOfAllThreeSteps(JenkinsRule r) {
        assertThat(DescribableModel.of(PowerOn.class).getParameter("failOnNoAddress"), notNullValue());
        assertThat(DescribableModel.of(Clone.class).getParameter("failOnNoAddress"), notNullValue());
        assertThat(DescribableModel.of(Deploy.class).getParameter("failOnNoAddress"), notNullValue());
    }

    @Test
    void defaultsToTheLegacyWarnOnlyBehavior(JenkinsRule r) throws Exception {
        assertThat(new PowerOn("vm", 60).isFailOnNoAddress(), is(false));
        assertThat(DescribableModel.of(Clone.class).instantiate(cloneArgs()).isFailOnNoAddress(), is(false));
        assertThat(
                new Deploy("linux-template", "new-vm", false, "Resources", "my-cluster", "", "", "", null, true)
                        .isFailOnNoAddress(),
                is(false));
    }

    @Test
    void isHonouredWhenSetFromAPipelineMap(JenkinsRule r) throws Exception {
        Map<String, Object> powerOnArgs = new HashMap<>();
        powerOnArgs.put("vm", "my-vm");
        powerOnArgs.put("timeoutInSeconds", 60);
        powerOnArgs.put("failOnNoAddress", true);
        assertThat(DescribableModel.of(PowerOn.class).instantiate(powerOnArgs).isFailOnNoAddress(), is(true));

        Map<String, Object> args = cloneArgs();
        args.put("failOnNoAddress", true);
        assertThat(DescribableModel.of(Clone.class).instantiate(args).isFailOnNoAddress(), is(true));
    }

    @Test
    void checkboxSurvivesFreestyleConfigRoundTrip(JenkinsRule r) throws Exception {
        r.jenkins.clouds.add(new vSphereCloud(
                new VSphereConnectionConfig("vcenter.example.com", false, "creds"), "some-server", 0, 0, false, null));
        PowerOn step = new PowerOn("my-vm", 60);
        step.setFailOnNoAddress(true);
        FreeStyleProject p = r.createFreeStyleProject();
        p.getBuildersList().add(new VSphereBuildStepContainer(step, "some-server"));

        r.configRoundtrip(p);

        PowerOn saved =
                (PowerOn) ((VSphereBuildStepContainer) p.getBuildersList().get(0)).getBuildStep();
        assertThat(saved.isFailOnNoAddress(), is(true));
    }
}
