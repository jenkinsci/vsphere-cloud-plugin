/*   Copyright 2026, Jim Klimov
 *
 *   Licensed under the Apache License, Version 2.0 (the "License");
 *   you may not use this file except in compliance with the License.
 *   You may obtain a copy of the License at
 *
 *       http://www.apache.org/licenses/LICENSE-2.0
 *
 *   Unless required by applicable law or agreed to in writing, software
 *   distributed under the License is distributed on an "AS IS" BASIS,
 *   WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *   See the License for the specific language governing permissions and
 *   limitations under the License.
 */
package org.jenkinsci.plugins.vsphere.tools;

import static io.jenkins.plugins.casc.misc.Util.getJenkinsRoot;
import static io.jenkins.plugins.casc.misc.Util.toYamlString;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.instanceOf;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.nullValue;

import io.jenkins.plugins.casc.ConfigurationContext;
import io.jenkins.plugins.casc.ConfiguratorRegistry;
import io.jenkins.plugins.casc.misc.ConfiguredWithCode;
import io.jenkins.plugins.casc.misc.JenkinsConfiguredWithCodeRule;
import io.jenkins.plugins.casc.misc.junit.jupiter.WithJenkinsConfiguredWithCode;
import io.jenkins.plugins.casc.model.CNode;
import java.util.ArrayList;
import java.util.List;
import org.jenkinsci.plugins.vSphereCloud;
import org.jenkinsci.plugins.vsphere.EsxiSshBackendConfig;
import org.jenkinsci.plugins.vsphere.EsxiSshHost;
import org.jenkinsci.plugins.vsphere.VCenterBackendConfig;
import org.jenkinsci.plugins.vsphere.VSphereConnectionConfig;
import org.jenkinsci.plugins.vsphere.VSphereConnectionConfig.BackendType;
import org.jenkinsci.plugins.vsphere.tools.esxi.EsxiHostKeyPolicy;
import org.jenkinsci.plugins.vsphere.tools.esxi.EsxiRelay;
import org.junit.jupiter.api.Test;

/**
 * The connection configurations that have a backend: one for a standalone ESXi host over SSH, one for vCenter,
 * and the layout from before the settings were grouped by backend, which is still understood.
 */
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
        // what is of vCenter is not of it
        assertThat(config.getVCenter(), is(nullValue()));
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
    void aVCenterIsLoadedFromItsGroup(JenkinsConfiguredWithCodeRule r) {
        VSphereConnectionConfig config = connectionOf(r, 2);

        assertThat(config.getBackendType(), is(BackendType.VCENTER));
        assertThat(config.getEsxiSsh(), is(nullValue()));
        assertThat(config.getVsHost(), is("https://vcenter.example.com"));
        assertThat(config.getBackend(), instanceOf(VCenterBackendConfig.class));
        VCenterBackendConfig vcenter = config.getVCenter();
        assertThat(vcenter.getCredentialsId(), is("vcenter"));
        assertThat(vcenter.getAllowUntrustedCertificate(), is(true));
        assertThat(vcenter.getHttpClientClassName(), is("ApacheHttpClient"));
    }

    @Test
    @ConfiguredWithCode("configuration-as-code-esxi-ssh.yml")
    void theOldFlatLayoutIsConvertedIntoTheGroupOfVCenter(JenkinsConfiguredWithCodeRule r) {
        VSphereConnectionConfig config = connectionOf(r, 3);

        assertThat(config.getBackendType(), is(BackendType.VCENTER));
        assertThat(config.getVsHost(), is("https://old.example.com"));
        VCenterBackendConfig vcenter = config.getVCenter();
        assertThat(vcenter.getCredentialsId(), is("old-vcenter"));
        assertThat(vcenter.getAllowUntrustedCertificate(), is(true));
        assertThat(vcenter.getHttpClientClassName(), is("ApacheHttpClient"));
    }

    @Test
    @ConfiguredWithCode("configuration-as-code-esxi-ssh.yml")
    void aHostAloneIsAVCenterWithDefaults(JenkinsConfiguredWithCodeRule r) {
        VSphereConnectionConfig config = connectionOf(r, 4);

        assertThat(config.getBackendType(), is(BackendType.VCENTER));
        assertThat(config.getVCenter().getCredentialsId(), is(nullValue()));
        assertThat(config.getVCenter().getAllowUntrustedCertificate(), is(false));
    }

    @Test
    @ConfiguredWithCode("configuration-as-code-esxi-ssh.yml")
    void moreHostsOfAClusterAreLoadedWithWhatTheyDoNotInherit(JenkinsConfiguredWithCodeRule r) {
        VSphereConnectionConfig config = connectionOf(r, 5);

        assertThat(config.getVsHost(), is("esxi-a.example.com"));
        EsxiSshBackendConfig esxi = config.getEsxiSsh();
        assertThat(esxi.getAdditionalHosts().size(), is(2));
        EsxiSshHost bare = esxi.getAdditionalHosts().get(0);
        assertThat(bare.getHost(), is("esxi-b.example.com"));
        assertThat(bare.getPort(), is(0)); // that of the first host
        assertThat(bare.getCredentialsId(), is(nullValue())); // those of the first host
        assertThat(bare.getHostKeyPolicy(), is(nullValue())); // that of the first host
        EsxiSshHost own = esxi.getAdditionalHosts().get(1);
        assertThat(own.getPort(), is(2200));
        assertThat(own.getCredentialsId(), is("esxi-c"));
        assertThat(own.getHostKeyPolicy(), is(EsxiHostKeyPolicy.ACCEPT_ANY));
        assertThat(own.getHostKeyFingerprint(), is("SHA256:cccccccccccccccccccccccccccccccccccccccccc"));
        assertThat(esxi.isReplicateMasters(), is(true));
        assertThat(esxi.getRelayCompression(), is(EsxiRelay.Compression.GZIP));
        assertThat(esxi.getTransferIdleSeconds(), is(120));
        // the hosts that have no more are as they were, and do not make replicas
        assertThat(connectionOf(r, 0).getEsxiSsh().isReplicateMasters(), is(false));
        assertThat(connectionOf(r, 0).getEsxiSsh().getRelayCompression(), is(EsxiRelay.Compression.PIGZ));
        assertThat(connectionOf(r, 0).getEsxiSsh().getTransferIdleSeconds(), is(300));
        // a host that has none is on its own
        assertThat(connectionOf(r, 0).getEsxiSsh().getAdditionalHosts().isEmpty(), is(true));
    }

    @Test
    @ConfiguredWithCode("configuration-as-code-esxi-ssh.yml")
    void moreHostsAreExportedAndOnlyWhenThereAreSome(JenkinsConfiguredWithCodeRule r) throws Exception {
        String exported = exportedClouds();

        assertThat(exported, containsString("additionalHosts:"));
        assertThat(exported, containsString("host: \"esxi-b.example.com\""));
        assertThat(exported, containsString("host: \"esxi-c.example.com\""));
        assertThat(exported.split("additionalHosts:", -1).length - 1, is(1));
        // what is not the default is exported, and only where it is
        assertThat(exported.split("replicateMasters:", -1).length - 1, is(1));
        assertThat(exported, containsString("relayCompression: GZIP"));
        assertThat(exported, containsString("transferIdleSeconds: 120"));
        assertThat(exported.split("relayCompression:", -1).length - 1, is(1));
    }

    private static String exportedClouds() throws Exception {
        ConfiguratorRegistry registry = ConfiguratorRegistry.get();
        final CNode clouds = getJenkinsRoot(new ConfigurationContext(registry)).get("clouds");
        return toYamlString(clouds);
    }

    @Test
    @ConfiguredWithCode("configuration-as-code-esxi-ssh.yml")
    void theSettingsAreExportedInTheirGroups(JenkinsConfiguredWithCodeRule r) throws Exception {
        String exported = exportedClouds();

        assertThat(exported, containsString("backend:"));
        assertThat(exported, containsString("esxiSsh:"));
        assertThat(exported, containsString("vCenter:"));
        assertThat(exported, containsString("credentialsId: \"esxi-ssh\""));
        assertThat(exported, containsString("hostKeyPolicy: TRUST_FIRST_USE")); // enums are exported unquoted
        assertThat(exported, containsString("port: 2222"));
        assertThat(
                exported, containsString("hostKeyFingerprint: \"SHA256:abcdefghijklmnopqrstuvwxyz0123456789ABCDEFG\""));
        // three ESXi hosts (one of them a cluster), and two vCenters with settings (the one that is in the new layout,
        // and the one that was in
        // the old); the vCenter that has nothing but its host has no settings to write, and is still one when loaded
        assertThat(exported.split("esxiSsh:", -1).length - 1, is(3));
        assertThat(exported.split("vCenter:", -1).length - 1, is(2));
    }

    @Test
    @ConfiguredWithCode("configuration-as-code-esxi-ssh.yml")
    void theOldLayoutIsNotExportedAnymore(JenkinsConfiguredWithCodeRule r) throws Exception {
        String exported = exportedClouds();

        // The credentials of the vCenter in the new layout and of the one that was in the old are written alike:
        // both in the group of vCenter, which is indented deeper than the connection configuration itself
        List<Integer> indents = new ArrayList<>();
        String connectionIndent = null;
        for (String line : exported.split("\n")) {
            if (line.contains("vsConnectionConfig:")) {
                connectionIndent = line.substring(0, line.indexOf("vsConnectionConfig:"));
            }
            if (line.contains("credentialsId: \"vcenter\"") || line.contains("credentialsId: \"old-vcenter\"")) {
                indents.add(line.indexOf("credentialsId"));
            }
        }
        assertThat(indents.size(), is(2));
        assertThat(indents.get(0), is(indents.get(1)));
        // deeper than the properties of the connection configuration: vsHost, backend, then the group, then these
        assertThat(indents.get(0) > connectionIndent.length() + 4, is(true));
        assertThat(exported, not(containsString("credentialsId: \"old-vcenter\"\n          vsHost")));
    }
}
