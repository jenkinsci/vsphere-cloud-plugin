package org.jenkinsci.plugins.vsphere.tools;

import hudson.model.Node.Mode;
import hudson.slaves.ComputerLauncher;
import hudson.slaves.JNLPLauncher;
import hudson.slaves.RetentionStrategy;
import io.jenkins.plugins.casc.ConfigurationContext;
import io.jenkins.plugins.casc.ConfiguratorRegistry;
import io.jenkins.plugins.casc.misc.ConfiguredWithCode;
import io.jenkins.plugins.casc.misc.JenkinsConfiguredWithCodeRule;
import io.jenkins.plugins.casc.misc.junit.jupiter.WithJenkinsConfiguredWithCode;
import io.jenkins.plugins.casc.model.CNode;
import java.util.List;
import java.util.Set;
import org.jenkinsci.plugins.vSphereCloud;
import org.jenkinsci.plugins.vSphereCloudSlaveTemplate;
import org.jenkinsci.plugins.vsphere.RunOnceCloudRetentionStrategy;
import org.jenkinsci.plugins.vsphere.VSphereGuestInfoProperty;
import org.junit.jupiter.api.Test;
import org.jvnet.hudson.test.Issue;

import static io.jenkins.plugins.casc.misc.Util.getJenkinsRoot;
import static io.jenkins.plugins.casc.misc.Util.toStringFromYamlFile;
import static io.jenkins.plugins.casc.misc.Util.toYamlString;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.instanceOf;
import static org.hamcrest.Matchers.notNullValue;
import static org.hamcrest.Matchers.nullValue;
import static org.hamcrest.core.Is.is;

@WithJenkinsConfiguredWithCode
class ConfigurationAsCodeTest {

    @Test
    @ConfiguredWithCode("configuration-as-code.yml")
    void should_support_configuration_as_code(JenkinsConfiguredWithCodeRule r) {
        validateCasCLoading((vSphereCloud) r.jenkins.clouds.get(0));
    }

    @Test
    @Issue("JENKINS-69035")
    @ConfiguredWithCode("configuration-as-code-legacy.yml")
    void should_support_legacy_configuration_as_code(JenkinsConfiguredWithCodeRule r) {
        validateCasCLoading((vSphereCloud) r.jenkins.clouds.get(0));
    }

    @Test
    @ConfiguredWithCode("configuration-as-code.yml")
    void should_support_configuration_export(JenkinsConfiguredWithCodeRule r) throws Exception {
        validateCasCExport();
    }

    @Test
    @ConfiguredWithCode("configuration-as-code-legacy.yml")
    void should_support_legacy_configuration_export(JenkinsConfiguredWithCodeRule r) throws Exception {
        validateCasCExport();
    }

    @Test
    @ConfiguredWithCode("configuration-as-code.yml")
    void pool_is_disabled_by_default_when_not_specified_in_yaml(JenkinsConfiguredWithCodeRule r) {
        vSphereCloud cloud = (vSphereCloud) r.jenkins.clouds.get(0);
        assertThat(cloud.isUseConnectionPool(), is(false));
        assertThat(cloud.getPoolHealthCheckIntervalSecs(), is(0));
        assertThat(cloud.getSessionMaxAgeSecs(), is(0));
        assertThat(cloud.getSessionMaxUses(), is(0));
        assertThat(cloud.getPoolIdleTimeoutSecs(), is(0));
    }

    @Test
    @ConfiguredWithCode("configuration-as-code-with-pool.yml")
    void should_load_pool_configuration_from_yaml(JenkinsConfiguredWithCodeRule r) {
        vSphereCloud cloud = (vSphereCloud) r.jenkins.clouds.get(0);
        assertThat(cloud.isUseConnectionPool(), is(true));
        assertThat(cloud.getPoolHealthCheckIntervalSecs(), is(60));
        assertThat(cloud.getSessionMaxAgeSecs(), is(3600));
        assertThat(cloud.getSessionMaxUses(), is(500));
        assertThat(cloud.getPoolIdleTimeoutSecs(), is(300));
    }

    @Test
    @ConfiguredWithCode("configuration-as-code.yml")
    void host_selection_fields_are_unset_by_default_when_not_specified_in_yaml(JenkinsConfiguredWithCodeRule r) {
        // Backward compatibility: templates defined before this feature existed must
        // keep behaving exactly as before (no host restriction of any kind).
        vSphereCloud cloud = (vSphereCloud) r.jenkins.clouds.get(0);
        vSphereCloudSlaveTemplate template = cloud.getTemplates().get(0);
        assertThat(template.getTargetHost(), is(nullValue()));
        assertThat(template.getHostSelectionMode(), is(nullValue()));
        assertThat(template.getHostSelectionCandidates(), is(nullValue()));
    }

    @Test
    @ConfiguredWithCode("configuration-as-code-with-host-selection.yml")
    void should_load_host_selection_configuration_from_yaml(JenkinsConfiguredWithCodeRule r) {
        // This fixture gives hostSelectionCandidates as a plain comma-separated scalar string.
        vSphereCloud cloud = (vSphereCloud) r.jenkins.clouds.get(0);
        vSphereCloudSlaveTemplate template = cloud.getTemplates().get(0);
        assertThat(template.getTargetHost(), is("esx01.company.example"));
        assertThat(template.getHostSelectionMode(), is("LEAST_LOADED"));
        assertThat(template.getHostSelectionCandidates(), is(Set.of("esx01.company.example", "esx02.company.example")));
    }

    @Test
    @ConfiguredWithCode("configuration-as-code-with-host-selection-list.yml")
    void should_load_candidate_hosts_given_as_a_yaml_list(JenkinsConfiguredWithCodeRule r) {
        // Same as above, but hostSelectionCandidates is given as a native YAML list instead of a
        // comma-separated scalar string - both forms must be accepted equivalently.
        vSphereCloud cloud = (vSphereCloud) r.jenkins.clouds.get(0);
        vSphereCloudSlaveTemplate template = cloud.getTemplates().get(0);
        assertThat(template.getHostSelectionCandidates(), is(Set.of("esx01.company.example", "esx02.company.example")));
    }

    @Test
    @ConfiguredWithCode("configuration-as-code.yml")
    void maintenance_mode_is_disabled_by_default_when_not_specified_in_yaml(JenkinsConfiguredWithCodeRule r) {
        vSphereCloud cloud = (vSphereCloud) r.jenkins.clouds.get(0);
        assertThat(cloud.isMaintenanceMode(), is(false));
        assertThat(cloud.getMaintenanceMessage(), is(""));
    }

    @Test
    @ConfiguredWithCode("configuration-as-code-with-maintenance.yml")
    void should_load_maintenance_mode_configuration_from_yaml(JenkinsConfiguredWithCodeRule r) {
        vSphereCloud cloud = (vSphereCloud) r.jenkins.clouds.get(0);
        assertThat(cloud.isMaintenanceMode(), is(true));
        assertThat(cloud.getMaintenanceMessage(), is("vCenter is undergoing scheduled maintenance."));
    }

    private static void validateCasCLoading(vSphereCloud cloud) {
        assertThat(cloud.getVsDescription(), is("Company vSphere"));
        assertThat(cloud.getVsHost(), is("https://company-vsphere"));
        assertThat(cloud.getInstanceCap(), is(100));
        assertThat(cloud.getMaxOnlineSlaves(), is(0));
        List<? extends vSphereCloudSlaveTemplate> templates = cloud.getTemplates();
        assertThat(templates, hasSize(1));
        vSphereCloudSlaveTemplate template = templates.get(0);
        assertThat(template, notNullValue());
        assertThat(template.getCloneNamePrefix(), is("windows-"));
        assertThat(template.getCluster(), is("Company"));
        assertThat(template.getDatastore(), is("Company-FMD-01"));

        assertThat(template.getForceVMLaunch(), is(true));
        assertThat(template.getLabelString(), is("windows vsphere"));
        assertThat(template.getLaunchDelay(), is(60));
        assertThat(template.getLimitedRunCount(), is(1));
        assertThat(template.getLinkedClone(), is(true));
        assertThat(template.getMasterImageName(), is("windows-server-2019"));
        assertThat(template.getMode(), is(Mode.EXCLUSIVE));
        assertThat(template.getNumberOfExecutors(), is(1));
        assertThat(template.getRemoteFS(), is("C:/jenkins"));
        assertThat(template.getResourcePool(), is("Resources"));
        assertThat(template.getSaveFailure(), is(false));
        assertThat(template.getTemplateInstanceCap(), is(5));
        assertThat(template.getUseSnapshot(), is(true));
        assertThat(template.getWaitForVMTools(), is(true));
        List<? extends VSphereGuestInfoProperty> guestInfoProperties = template.getGuestInfoProperties();
        assertThat(guestInfoProperties, hasSize(1));
        VSphereGuestInfoProperty guestInfoProperty = guestInfoProperties.get(0);
        assertThat(guestInfoProperty, notNullValue());
        assertThat(guestInfoProperty.getName(), is("JENKINS_URL"));
        assertThat(guestInfoProperty.getValue(), is("${JENKINS_URL}"));
        ComputerLauncher launcher = template.getLauncher();
        assertThat(launcher, notNullValue());
        assertThat(launcher, instanceOf(JNLPLauncher.class));
        JNLPLauncher jnlpLauncher = (JNLPLauncher) launcher;
        assertThat(jnlpLauncher.tunnel, is("jenkins:"));
        RetentionStrategy<?> retentionStrategy = template.getRetentionStrategy();
        assertThat(retentionStrategy, notNullValue());
        assertThat(retentionStrategy, instanceOf(RunOnceCloudRetentionStrategy.class));
        RunOnceCloudRetentionStrategy runOnce = (RunOnceCloudRetentionStrategy) retentionStrategy;
        assertThat(runOnce.getIdleMinutes(), is(2));
    }

    private void validateCasCExport() throws Exception {
        ConfiguratorRegistry registry = ConfiguratorRegistry.get();
        ConfigurationContext context = new ConfigurationContext(registry);
        final CNode cloud = getJenkinsRoot(context).get("clouds");

        String exported = toYamlString(cloud);

        String expected = toStringFromYamlFile(this, "expected_output.yml");

        assertThat(exported, is(expected));

    }
}
