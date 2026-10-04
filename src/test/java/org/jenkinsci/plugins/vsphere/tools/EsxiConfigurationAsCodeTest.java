package org.jenkinsci.plugins.vsphere.tools;

import static io.jenkins.plugins.casc.misc.Util.getJenkinsRoot;
import static io.jenkins.plugins.casc.misc.Util.toYamlString;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.nullValue;

import io.jenkins.plugins.casc.ConfigurationContext;
import io.jenkins.plugins.casc.ConfiguratorRegistry;
import io.jenkins.plugins.casc.misc.ConfiguredWithCode;
import io.jenkins.plugins.casc.misc.JenkinsConfiguredWithCodeRule;
import io.jenkins.plugins.casc.misc.junit.jupiter.WithJenkinsConfiguredWithCode;
import io.jenkins.plugins.casc.model.CNode;
import org.jenkinsci.plugins.vSphereCloud;
import org.jenkinsci.plugins.vsphere.EsxiSshBackendConfig;
import org.jenkinsci.plugins.vsphere.VSphereConnectionConfig;
import org.jenkinsci.plugins.vsphere.VSphereConnectionConfig.BackendType;
import org.jenkinsci.plugins.vsphere.tools.esxi.EsxiHostKeyPolicy;
import org.junit.jupiter.api.Test;

/** A cloud that connects to a standalone ESXi host over SSH, in Configuration as Code. */
@WithJenkinsConfiguredWithCode
class EsxiConfigurationAsCodeTest {

    private static VSphereConnectionConfig connectionOf(JenkinsConfiguredWithCodeRule r, int cloud) {
        return ((vSphereCloud) r.jenkins.clouds.get(cloud)).getVsConnectionConfig();
    }

    @Test
    @ConfiguredWithCode("configuration-as-code-esxi-ssh.yml")
    void anEsxiHostOverSshIsLoadedWithAllItsSettings(JenkinsConfiguredWithCodeRule r) {
        VSphereConnectionConfig config = connectionOf(r, 0);

        assertThat(config.getBackendType(), is(BackendType.ESXI_SSH));
        assertThat(config.getVsHost(), is("esxi7.example.com"));
        EsxiSshBackendConfig esxi = config.getEsxiSsh();
        assertThat(esxi.getCredentialsId(), is("esxi-ssh"));
        assertThat(esxi.getPort(), is(2222));
        assertThat(esxi.getHostKeyPolicy(), is(EsxiHostKeyPolicy.TRUST_FIRST_USE));
        assertThat(esxi.getHostKeyFingerprint(), is("SHA256:abcdefghijklmnopqrstuvwxyz0123456789ABCDEFG"));
        assertThat(esxi.getConnectTimeoutSeconds(), is(15));
        assertThat(esxi.getCommandTimeoutSeconds(), is(120));
    }

    @Test
    @ConfiguredWithCode("configuration-as-code-esxi-ssh.yml")
    void unsetSettingsOfAnEsxiHostHaveSafeDefaults(JenkinsConfiguredWithCodeRule r) {
        EsxiSshBackendConfig esxi = connectionOf(r, 1).getEsxiSsh();

        assertThat(esxi.getCredentialsId(), is("esxi-key"));
        assertThat(esxi.getPort(), is(22));
        // no host key is trusted unless it is said which one, or that the first one seen is
        assertThat(esxi.getHostKeyPolicy(), is(EsxiHostKeyPolicy.FINGERPRINT));
        assertThat(esxi.getHostKeyFingerprint(), is(nullValue()));
        assertThat(esxi.getConnectTimeoutSeconds(), is(30));
        assertThat(esxi.getCommandTimeoutSeconds(), is(600));
    }

    @Test
    @ConfiguredWithCode("configuration-as-code-esxi-ssh.yml")
    void aVCenterNextToThemIsStillOne(JenkinsConfiguredWithCodeRule r) {
        VSphereConnectionConfig config = connectionOf(r, 2);

        assertThat(config.getBackendType(), is(BackendType.VCENTER));
        assertThat(config.getEsxiSsh(), is(nullValue()));
        assertThat(config.getCredentialsId(), is("vcenter"));
        assertThat(config.getVsHost(), is("https://vcenter.example.com"));
    }

    @Test
    @ConfiguredWithCode("configuration-as-code-esxi-ssh.yml")
    void theEsxiSettingsAreExportedAndNothingOfThemForAVCenter(JenkinsConfiguredWithCodeRule r) throws Exception {
        ConfiguratorRegistry registry = ConfiguratorRegistry.get();
        final CNode clouds = getJenkinsRoot(new ConfigurationContext(registry)).get("clouds");

        String exported = toYamlString(clouds);

        assertThat(exported, containsString("esxiSsh:"));
        assertThat(exported, containsString("credentialsId: \"esxi-ssh\""));
        assertThat(exported, containsString("hostKeyPolicy: TRUST_FIRST_USE")); // enums are exported unquoted
        assertThat(exported, containsString("port: 2222"));
        assertThat(
                exported, containsString("hostKeyFingerprint: \"SHA256:abcdefghijklmnopqrstuvwxyz0123456789ABCDEFG\""));
        // exactly two of the three clouds are ESXi hosts
        assertThat(exported.split("esxiSsh:", -1).length - 1, is(2));
        assertThat(exported, not(containsString("esxiSsh: null")));
    }
}
