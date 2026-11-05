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
package org.jenkinsci.plugins.vsphere;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.instanceOf;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.nullValue;
import static org.junit.jupiter.api.Assertions.assertThrows;

import jenkins.model.Jenkins;
import org.jenkinsci.plugins.vsphere.VSphereConnectionConfig.BackendType;
import org.junit.jupiter.api.Test;
import org.jvnet.hudson.test.JenkinsRule;
import org.jvnet.hudson.test.junit.jupiter.WithJenkins;

/**
 * What is saved of a connection configuration: the layout from before the settings were grouped by backend is
 * read as the group of vCenter, and what is saved from then on is in the new layout.
 */
@WithJenkins
class VSphereConnectionConfigLayoutTest {

    private static final String OLD_LAYOUT = String.join(
            "\n",
            "<org.jenkinsci.plugins.vsphere.VSphereConnectionConfig>",
            "  <vsHost>https://vc.example.com</vsHost>",
            "  <allowUntrustedCertificate>true</allowUntrustedCertificate>",
            "  <credentialsId>vc-creds</credentialsId>",
            "  <httpClientClassName>ApacheHttpClient</httpClientClassName>",
            "</org.jenkinsci.plugins.vsphere.VSphereConnectionConfig>");

    private static VSphereConnectionConfig load(String xml) {
        return (VSphereConnectionConfig) Jenkins.XSTREAM2.fromXML(xml);
    }

    @Test
    void theOldSavedLayoutBecomesTheGroupOfVCenter(JenkinsRule r) {
        VSphereConnectionConfig config = load(OLD_LAYOUT);

        assertThat(config.getBackendType(), is(BackendType.VCENTER));
        assertThat(config.getVsHost(), is("https://vc.example.com"));
        VCenterBackendConfig vcenter = config.getVCenter();
        assertThat(vcenter.getCredentialsId(), is("vc-creds"));
        assertThat(vcenter.getAllowUntrustedCertificate(), is(true));
        assertThat(vcenter.getHttpClientClassName(), is("ApacheHttpClient"));
    }

    @Test
    void anOldLayoutWithOnlyTheHostAndTheCredentialsIsStillOne(JenkinsRule r) {
        VSphereConnectionConfig config = load(String.join(
                "\n",
                "<org.jenkinsci.plugins.vsphere.VSphereConnectionConfig>",
                "  <vsHost>https://older.example.com</vsHost>",
                "  <credentialsId>older</credentialsId>",
                "</org.jenkinsci.plugins.vsphere.VSphereConnectionConfig>"));

        assertThat(config.getVCenter().getCredentialsId(), is("older"));
        assertThat(config.getVCenter().getAllowUntrustedCertificate(), is(false));
        assertThat(config.getVCenter().getHttpClientClassName(), is(nullValue()));
    }

    @Test
    void whatIsSavedAfterwardsIsInTheNewLayoutOnly(JenkinsRule r) {
        String saved = Jenkins.XSTREAM2.toXML(load(OLD_LAYOUT));

        assertThat(saved, containsString("<backend class=\"org.jenkinsci.plugins.vsphere.VCenterBackendConfig\">"));
        assertThat(saved, containsString("<credentialsId>vc-creds</credentialsId>"));
        // the credentials are written once, in the group, and not as a setting of the connection configuration
        assertThat(saved.split("<credentialsId>", -1).length - 1, is(1));
        assertThat(saved.split("<allowUntrustedCertificate>", -1).length - 1, is(1));
        assertThat(saved, not(containsString("\n  <credentialsId>")));
    }

    @Test
    void theNewLayoutSurvivesBeingSavedAndLoaded(JenkinsRule r) {
        VSphereConnectionConfig again = load(Jenkins.XSTREAM2.toXML(load(OLD_LAYOUT)));

        assertThat(again.getBackend(), instanceOf(VCenterBackendConfig.class));
        assertThat(again.getVCenter().getCredentialsId(), is("vc-creds"));
        assertThat(again.getVCenter().getAllowUntrustedCertificate(), is(true));
        assertThat(again.getVCenter().getHttpClientClassName(), is("ApacheHttpClient"));
    }

    @Test
    void anEsxiHostIsSavedAndLoadedWithItsGroup(JenkinsRule r) {
        VSphereConnectionConfig config = new VSphereConnectionConfig("esxi.example.com");
        EsxiSshBackendConfig esxi = new EsxiSshBackendConfig("esxi-creds");
        esxi.setPort(2222);
        esxi.setHostKeyFingerprint("SHA256:abc");
        config.setBackend(esxi);

        VSphereConnectionConfig again = load(Jenkins.XSTREAM2.toXML(config));

        assertThat(again.getBackendType(), is(BackendType.ESXI_SSH));
        assertThat(again.getEsxiSsh().getCredentialsId(), is("esxi-creds"));
        assertThat(again.getEsxiSsh().getPort(), is(2222));
        assertThat(again.getEsxiSsh().getHostKeyFingerprint(), is("SHA256:abc"));
        assertThat(again.getVCenter(), is(nullValue()));
    }

    @Test
    void theSettingsOfVCenterCannotBeMixedWithAnEsxiBackend(JenkinsRule r) {
        VSphereConnectionConfig config = new VSphereConnectionConfig("esxi.example.com");
        config.setBackend(new EsxiSshBackendConfig("esxi-creds"));

        IllegalArgumentException e = assertThrows(IllegalArgumentException.class, () -> config.setCredentialsId("x"));

        assertThat(e.getMessage(), containsString("is a setting of the vCenter backend"));
        assertThat(e.getMessage(), containsString("ESXI_SSH"));
    }

    @Test
    void aConfigurationWithoutABackendIsVCenter(JenkinsRule r) {
        VSphereConnectionConfig config = new VSphereConnectionConfig("https://vc.example.com");
        config.setBackend(null);

        assertThat(config.getBackendType(), is(BackendType.VCENTER));
        assertThat(config.getVCenter() != null, is(true));
    }

    @Test
    void theConstructorsOfBeforeStillMakeAVCenter(JenkinsRule r) {
        VSphereConnectionConfig config = new VSphereConnectionConfig("https://vc.example.com", true, "creds");

        assertThat(config.getBackendType(), is(BackendType.VCENTER));
        assertThat(config.getVCenter().getCredentialsId(), is("creds"));
        assertThat(config.getVCenter().getAllowUntrustedCertificate(), is(true));
        assertThat(config.getVsHost(), is("https://vc.example.com"));
    }
}
