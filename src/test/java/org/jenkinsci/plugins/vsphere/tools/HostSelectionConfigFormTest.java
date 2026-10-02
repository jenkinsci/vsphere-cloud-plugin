package org.jenkinsci.plugins.vsphere.tools;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;

import java.util.List;
import org.htmlunit.html.HtmlForm;
import org.htmlunit.html.HtmlPage;
import org.jenkinsci.plugins.vSphereCloud;
import org.jenkinsci.plugins.vSphereCloudSlaveTemplate;
import org.jenkinsci.plugins.vsphere.VSphereConnectionConfig;
import org.junit.jupiter.api.Test;
import org.jvnet.hudson.test.JenkinsRule;
import org.jvnet.hudson.test.junit.jupiter.WithJenkins;

/**
 * Renders and submits the real cloud configuration page, so the Jelly (new weight fields, host size
 * requirement checkboxes and tri-state selects) and the form binding are exercised, not just the Java.
 */
@WithJenkins
class HostSelectionConfigFormTest {

    @Test
    void cloudSettingsSurviveAFormRoundTrip(JenkinsRule r) throws Exception {
        vSphereCloud cloud = new vSphereCloud(
                new VSphereConnectionConfig("vcenter.example.com", "creds", null), "roundtrip", 0, 0, false, List.of());
        cloud.setHostSelectionMode("LEAST_LOADED");
        cloud.setHostSelectionRequireCores(true);
        cloud.setHostSelectionRequireAvailableMemory(true);
        cloud.setHostWeightFreeCpuMhz(1);
        cloud.setHostWeightFreeCpuPercent(2);
        cloud.setHostWeightFreeMemoryMB(3);
        cloud.setHostWeightFreeMemoryPercent(4);
        r.jenkins.clouds.add(cloud);

        try (JenkinsRule.WebClient wc = r.createWebClient()) {
            HtmlPage page = wc.goTo(cloud.getUrl() + "configure");
            HtmlForm form = page.getFormByName("config");
            r.submit(form);
        }

        vSphereCloud saved = (vSphereCloud) r.jenkins.getCloud(cloud.name);
        assertThat(saved.getHostSelectionMode(), is("LEAST_LOADED"));
        assertThat(saved.isHostSelectionRequireCores(), is(true));
        assertThat(saved.isHostSelectionRequireMemory(), is(false));
        assertThat(saved.isHostSelectionRequireAvailableMemory(), is(true));
        assertThat(saved.getHostWeightFreeCpuMhz(), is(1));
        assertThat(saved.getHostWeightFreeCpuPercent(), is(2));
        assertThat(saved.getHostWeightFreeMemoryMB(), is(3));
        assertThat(saved.getHostWeightFreeMemoryPercent(), is(4));
    }

    @Test
    void templateTriStateSurvivesAFormRoundTripAndStaysUnsetWhenInherited(JenkinsRule r) throws Exception {
        vSphereCloudSlaveTemplate template = new vSphereCloudSlaveTemplate(
                "prefix",
                "master",
                null,
                null,
                false,
                null,
                null,
                null,
                null,
                null,
                null,
                1,
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
                0,
                null,
                new hudson.slaves.JNLPLauncher(),
                hudson.slaves.RetentionStrategy.NOOP,
                null,
                null,
                null);
        template.setHostSelectionRequireMemory(Boolean.FALSE);
        template.setHostSelectionRequireAvailableMemory(Boolean.FALSE);
        vSphereCloud cloud = new vSphereCloud(
                new VSphereConnectionConfig("vcenter.example.com", "creds", null),
                "roundtrip-template",
                0,
                0,
                false,
                List.of(template));
        cloud.setHostSelectionRequireMemory(true);
        cloud.setHostSelectionRequireAvailableMemory(true);
        r.jenkins.clouds.add(cloud);

        try (JenkinsRule.WebClient wc = r.createWebClient()) {
            HtmlPage page = wc.goTo(cloud.getUrl() + "configure");
            r.submit(page.getFormByName("config"));
        }

        vSphereCloud saved = (vSphereCloud) r.jenkins.getCloud(cloud.name);
        assertThat(saved.isHostSelectionRequireMemory(), is(true));
        vSphereCloudSlaveTemplate savedTemplate = saved.getTemplates().get(0);
        // Explicit "No" must stay an override of the cloud's "yes" ...
        assertThat(savedTemplate.getHostSelectionRequireMemory(), is(false));
        assertThat(savedTemplate.getHostSelectionRequireAvailableMemory(), is(false));
        // ... and a setting left on "inherit" must not turn into an explicit value.
        assertThat(savedTemplate.getHostSelectionRequireCores() == null, is(true));
    }

    @Test
    void cloneStepSizeAndTriStateSurviveAFormRoundTrip(JenkinsRule r) throws Exception {
        java.util.Map<String, Object> args = new java.util.HashMap<>();
        args.put("sourceName", "source-vm");
        args.put("clone", "new-vm");
        args.put("linkedClone", false);
        args.put("resourcePool", "Resources");
        args.put("cluster", "cluster");
        args.put("datastore", "");
        args.put("folder", "");
        args.put("powerOn", false);
        org.jenkinsci.plugins.vsphere.builders.Clone clone =
                org.jenkinsci.plugins.structs.describable.DescribableModel.of(
                                org.jenkinsci.plugins.vsphere.builders.Clone.class)
                        .instantiate(args);
        clone.setCpuCores("8");
        clone.setCoresPerSocket("4");
        clone.setCpuLimitMHz("2000");
        clone.setMemorySize("16384");
        clone.setHostSelectionRequireMemory(Boolean.TRUE);
        r.jenkins.clouds.add(new vSphereCloud(
                new VSphereConnectionConfig("vcenter.example.com", "creds", null),
                "clone-cloud",
                0,
                0,
                false,
                List.of()));
        hudson.model.FreeStyleProject project = r.createFreeStyleProject();
        project.getBuildersList()
                .add(new org.jenkinsci.plugins.vsphere.VSphereBuildStepContainer(clone, "clone-cloud"));

        try (JenkinsRule.WebClient wc = r.createWebClient()) {
            HtmlPage page = wc.goTo(project.getUrl() + "configure");
            r.submit(page.getFormByName("config"));
        }

        org.jenkinsci.plugins.vsphere.builders.Clone saved = (org.jenkinsci.plugins.vsphere.builders.Clone) r.jenkins
                .getItemByFullName(project.getFullName(), hudson.model.FreeStyleProject.class)
                .getBuildersList()
                .get(org.jenkinsci.plugins.vsphere.VSphereBuildStepContainer.class)
                .getBuildStep();
        assertThat(saved.getCpuCores(), is("8"));
        assertThat(saved.getCoresPerSocket(), is("4"));
        assertThat(saved.getCpuLimitMHz(), is("2000"));
        assertThat(saved.getMemorySize(), is("16384"));
        assertThat(saved.getHostSelectionRequireMemory(), is(true));
        // left on "inherit": must stay unset rather than becoming an explicit value
        assertThat(saved.getHostSelectionRequireCores() == null, is(true));
        assertThat(saved.getHostSelectionRequireAvailableMemory() == null, is(true));
    }
}
