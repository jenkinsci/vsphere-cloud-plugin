package org.jenkinsci.plugins.vsphere.builders;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;

import hudson.model.FreeStyleProject;
import hudson.util.FormValidation;
import java.util.List;
import org.jenkinsci.plugins.vSphereCloud;
import org.jenkinsci.plugins.vsphere.VSphereBuildStepContainer;
import org.jenkinsci.plugins.vsphere.VSphereConnectionConfig;
import org.junit.jupiter.api.Test;
import org.jvnet.hudson.test.Issue;
import org.jvnet.hudson.test.JenkinsRule;
import org.jvnet.hudson.test.junit.jupiter.WithJenkins;

@WithJenkins
class ReconfigureVariablesTest {

    private static FormValidation.Kind kind(String value, boolean required) {
        return ReconfigureStep.checkPositiveIntegerOrVariable("Field", value, required).kind;
    }

    @Test
    @Issue("JENKINS-31468")
    void validatorAcceptsPositiveIntegersAndVariables(JenkinsRule r) {
        assertThat(kind("4096", true), is(FormValidation.Kind.OK));
        assertThat(kind("${RAM}", true), is(FormValidation.Kind.OK));
        assertThat(kind("$RAM", true), is(FormValidation.Kind.OK));
        assertThat(kind("${BASE}0", true), is(FormValidation.Kind.OK));
    }

    @Test
    @Issue("JENKINS-31468")
    void validatorStillRejectsGarbageAndMissingRequiredValues(JenkinsRule r) {
        assertThat(kind("", true), is(FormValidation.Kind.ERROR));
        assertThat(kind(null, true), is(FormValidation.Kind.ERROR));
        assertThat(kind("abc", true), is(FormValidation.Kind.ERROR));
        assertThat(kind("0", true), is(FormValidation.Kind.ERROR));
        assertThat(kind("-5", true), is(FormValidation.Kind.ERROR));
        assertThat(kind("", false), is(FormValidation.Kind.OK));
    }

    @Test
    @Issue("JENKINS-31468")
    void variablesSurviveFreestyleConfigRoundTrip(JenkinsRule r) throws Exception {
        r.jenkins.clouds.add(new vSphereCloud(
                new VSphereConnectionConfig("vcenter.example.com", false, "creds"), "some-server", 0, 0, false, null));
        FreeStyleProject p = r.createFreeStyleProject();
        p.getBuildersList()
                .add(new VSphereBuildStepContainer(
                        new Reconfigure(
                                "some-vm",
                                List.of(new ReconfigureMemory("${RAM}"), new ReconfigureCpu("${CPU}", "${SOCKETS}"))),
                        "some-server"));

        r.configRoundtrip(p);

        Reconfigure saved =
                (Reconfigure) ((VSphereBuildStepContainer) p.getBuildersList().get(0)).getBuildStep();
        assertThat(((ReconfigureMemory) saved.getReconfigureSteps().get(0)).getMemorySize(), is("${RAM}"));
        ReconfigureCpu cpu = (ReconfigureCpu) saved.getReconfigureSteps().get(1);
        assertThat(cpu.getCpuCores(), is("${CPU}"));
        assertThat(cpu.getCoresPerSocket(), is("${SOCKETS}"));
    }
}
