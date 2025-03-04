package org.jenkinsci.plugins.vsphere.tools;

import hudson.model.Node.Mode;
import hudson.slaves.ComputerLauncher;
import hudson.slaves.JNLPLauncher;
import hudson.slaves.RetentionStrategy;
import io.jenkins.plugins.casc.ConfigurationContext;
import io.jenkins.plugins.casc.ConfiguratorRegistry;
import io.jenkins.plugins.casc.misc.ConfiguredWithCode;
import io.jenkins.plugins.casc.misc.JenkinsConfiguredWithCodeRule;
import io.jenkins.plugins.casc.model.CNode;
import java.util.List;
import org.jenkinsci.plugins.vSphereCloud;
import org.jenkinsci.plugins.vSphereCloudSlaveTemplate;
import org.jenkinsci.plugins.vsphere.RunOnceCloudRetentionStrategy;
import org.jenkinsci.plugins.vsphere.VSphereGuestInfoProperty;
import org.junit.ClassRule;
import org.junit.Rule;
import org.junit.Test;
import org.jvnet.hudson.test.Issue;

import static io.jenkins.plugins.casc.misc.Util.getJenkinsRoot;
import static io.jenkins.plugins.casc.misc.Util.toStringFromYamlFile;
import static io.jenkins.plugins.casc.misc.Util.toYamlString;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.instanceOf;
import static org.hamcrest.Matchers.notNullValue;
import static org.hamcrest.core.Is.is;

public class ConfigurationAsCodeTest {

    @Rule
    public JenkinsConfiguredWithCodeRule r = new JenkinsConfiguredWithCodeRule();

    @Test
    @ConfiguredWithCode("configuration-as-code.yml")
    public void should_support_configuration_as_code() {
        validateCasCLoading((vSphereCloud) r.jenkins.clouds.get(0));
    }

    @Test
    @Issue("JENKINS-69035")
    @ConfiguredWithCode("configuration-as-code-legacy.yml")
    public void should_support_legacy_configuration_as_code() {
        validateCasCLoading((vSphereCloud) r.jenkins.clouds.get(0));
    }

    @Test
    @ConfiguredWithCode("configuration-as-code.yml")
    public void should_support_configuration_export() throws Exception {
        validateCasCExport();
    }

    @Test
    @ConfiguredWithCode("configuration-as-code-legacy.yml")
    public void should_support_legacy_configuration_export() throws Exception {
        validateCasCExport();
    }
    
    private void validateCasCLoading(vSphereCloud cloud) {
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
        assertThat(template.getInstancesMin(), is(3));
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
