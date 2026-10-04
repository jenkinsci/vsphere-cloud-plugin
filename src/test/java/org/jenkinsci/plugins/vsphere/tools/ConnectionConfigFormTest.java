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

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.instanceOf;
import static org.hamcrest.Matchers.is;

import com.cloudbees.plugins.credentials.CredentialsScope;
import com.cloudbees.plugins.credentials.SystemCredentialsProvider;
import com.cloudbees.plugins.credentials.impl.UsernamePasswordCredentialsImpl;
import java.util.ArrayList;
import java.util.List;
import org.htmlunit.html.HtmlForm;
import org.htmlunit.html.HtmlPage;
import org.htmlunit.html.HtmlSelect;
import org.jenkinsci.plugins.vSphereCloud;
import org.jenkinsci.plugins.vsphere.EsxiSshBackendConfig;
import org.jenkinsci.plugins.vsphere.VCenterBackendConfig;
import org.jenkinsci.plugins.vsphere.VSphereConnectionConfig;
import org.jenkinsci.plugins.vsphere.VSphereConnectionConfig.BackendType;
import org.jenkinsci.plugins.vsphere.tools.esxi.EsxiHostKeyPolicy;
import org.junit.jupiter.api.Test;
import org.jvnet.hudson.test.JenkinsRule;
import org.jvnet.hudson.test.junit.jupiter.WithJenkins;

/** The form of a cloud, with the host and the settings of the way of connecting to it. */
@WithJenkins
class ConnectionConfigFormTest {

    private static HtmlPage configurePage(JenkinsRule r, vSphereCloud cloud) throws Exception {
        try (JenkinsRule.WebClient wc = r.createWebClient()) {
            return wc.goTo(cloud.getUrl() + "configure");
        }
    }

    private static void submit(JenkinsRule r, vSphereCloud cloud) throws Exception {
        try (JenkinsRule.WebClient wc = r.createWebClient()) {
            HtmlForm form = wc.goTo(cloud.getUrl() + "configure").getFormByName("config");
            r.submit(form);
        }
    }

    private static void addCredentials(String id) throws Exception {
        SystemCredentialsProvider.getInstance()
                .getCredentials()
                .add(new UsernamePasswordCredentialsImpl(CredentialsScope.GLOBAL, id, "a host", "root", "secret"));
    }

    @Test
    void aVCenterSurvivesAFormRoundTrip(JenkinsRule r) throws Exception {
        addCredentials("vc-creds");
        VSphereConnectionConfig config = new VSphereConnectionConfig("https://vc.example.com", true, "vc-creds");
        vSphereCloud cloud = new vSphereCloud(config, "A vCenter", 0, 0, false, java.util.List.of());
        r.jenkins.clouds.add(cloud);

        submit(r, cloud);

        VSphereConnectionConfig saved = ((vSphereCloud) r.jenkins.getCloud(cloud.name)).getVsConnectionConfig();
        assertThat(saved.getBackendType(), is(BackendType.VCENTER));
        assertThat(saved.getVsHost(), is("https://vc.example.com"));
        assertThat(saved.getBackend(), instanceOf(VCenterBackendConfig.class));
        assertThat(saved.getVCenter().getAllowUntrustedCertificate(), is(true));
        assertThat(saved.getVCenter().getCredentialsId(), is("vc-creds"));
    }

    @Test
    void anEsxiHostSurvivesAFormRoundTrip(JenkinsRule r) throws Exception {
        addCredentials("esxi-creds");
        EsxiSshBackendConfig esxi = new EsxiSshBackendConfig("esxi-creds");
        esxi.setPort(2222);
        esxi.setHostKeyPolicy(EsxiHostKeyPolicy.TRUST_FIRST_USE);
        esxi.setHostKeyFingerprint("SHA256:abcdefghijklmnopqrstuvwxyz0123456789ABCDEFG");
        esxi.setConnectTimeoutSeconds(15);
        esxi.setCommandTimeoutSeconds(120);
        VSphereConnectionConfig config = new VSphereConnectionConfig("esxi.example.com");
        config.setBackend(esxi);
        vSphereCloud cloud = new vSphereCloud(config, "An ESXi host", 0, 0, false, java.util.List.of());
        r.jenkins.clouds.add(cloud);

        submit(r, cloud);

        VSphereConnectionConfig saved = ((vSphereCloud) r.jenkins.getCloud(cloud.name)).getVsConnectionConfig();
        assertThat(saved.getBackendType(), is(BackendType.ESXI_SSH));
        assertThat(saved.getVsHost(), is("esxi.example.com"));
        EsxiSshBackendConfig kept = saved.getEsxiSsh();
        assertThat(kept.getCredentialsId(), is("esxi-creds"));
        assertThat(kept.getPort(), is(2222));
        assertThat(kept.getHostKeyPolicy(), is(EsxiHostKeyPolicy.TRUST_FIRST_USE));
        assertThat(kept.getHostKeyFingerprint(), is("SHA256:abcdefghijklmnopqrstuvwxyz0123456789ABCDEFG"));
        assertThat(kept.getConnectTimeoutSeconds(), is(15));
        assertThat(kept.getCommandTimeoutSeconds(), is(120));
    }

    @Test
    void theFormOfAnEsxiHostHasWhatIsNeededToSetItUp(JenkinsRule r) throws Exception {
        EsxiSshBackendConfig esxi = new EsxiSshBackendConfig(null);
        VSphereConnectionConfig config = new VSphereConnectionConfig("esxi.example.com");
        config.setBackend(esxi);
        vSphereCloud cloud = new vSphereCloud(config, "An ESXi host", 0, 0, false, java.util.List.of());
        r.jenkins.clouds.add(cloud);

        String text = configurePage(r, cloud).asNormalizedText();

        assertThat(text, containsString("Connection type"));
        assertThat(text, containsString("Standalone ESXi host over SSH"));
        assertThat(text, containsString("SSH Port"));
        assertThat(text, containsString("Trust the host key"));
        assertThat(text, containsString("Host key fingerprint"));
        assertThat(text, containsString("Show host key"));
        assertThat(text, containsString("Test Connection"));
    }

    private static vSphereCloud esxiCloud(JenkinsRule r, EsxiHostKeyPolicy policy) {
        EsxiSshBackendConfig esxi = new EsxiSshBackendConfig(null);
        esxi.setHostKeyPolicy(policy);
        VSphereConnectionConfig config = new VSphereConnectionConfig("esxi.example.com");
        config.setBackend(esxi);
        vSphereCloud cloud = new vSphereCloud(config, "An ESXi host", 0, 0, false, java.util.List.of());
        r.jenkins.clouds.add(cloud);
        return cloud;
    }

    private static String selectedPolicy(HtmlSelect select) {
        return select.getSelectedOptions().get(0).getValueAttribute();
    }

    @Test
    void choosingTheHostKeySeenFirstAsksForAConfirmationAndCanBeDeclined(JenkinsRule r) throws Exception {
        vSphereCloud cloud = esxiCloud(r, EsxiHostKeyPolicy.FINGERPRINT);
        List<String> asked = new ArrayList<>();
        try (JenkinsRule.WebClient wc = r.createWebClient()) {
            wc.setConfirmHandler((page, message) -> {
                asked.add(message);
                return false; // "no"
            });
            HtmlSelect select =
                    (HtmlSelect) wc.goTo(cloud.getUrl() + "configure").getElementByName("_.hostKeyPolicy");
            assertThat(selectedPolicy(select), is("FINGERPRINT"));

            select.setSelectedAttribute(select.getOptionByValue("TRUST_FIRST_USE"), true);

            assertThat(asked.size(), is(1));
            assertThat(asked.get(0), containsString("save its configuration by itself"));
            assertThat(asked.get(0), containsString("the configuration of the folder"));
            // declined, so it stays as it was
            assertThat(selectedPolicy(select), is("FINGERPRINT"));
        }
    }

    @Test
    void choosingTheHostKeySeenFirstCanBeConfirmed(JenkinsRule r) throws Exception {
        vSphereCloud cloud = esxiCloud(r, EsxiHostKeyPolicy.FINGERPRINT);
        List<String> asked = new ArrayList<>();
        try (JenkinsRule.WebClient wc = r.createWebClient()) {
            wc.setConfirmHandler((page, message) -> {
                asked.add(message);
                return true; // "yes"
            });
            HtmlSelect select =
                    (HtmlSelect) wc.goTo(cloud.getUrl() + "configure").getElementByName("_.hostKeyPolicy");

            select.setSelectedAttribute(select.getOptionByValue("TRUST_FIRST_USE"), true);

            assertThat(asked.size(), is(1));
            assertThat(selectedPolicy(select), is("TRUST_FIRST_USE"));
            // and the other choices do not ask
            select.setSelectedAttribute(select.getOptionByValue("FINGERPRINT"), true);
            select.setSelectedAttribute(select.getOptionByValue("ACCEPT_ANY"), true);
            assertThat(asked.size(), is(1));
            assertThat(selectedPolicy(select), is("ACCEPT_ANY"));
        }
    }

    @Test
    void theFirstUseConfirmationIsSavedOnlyIfConfirmed(JenkinsRule r) throws Exception {
        vSphereCloud cloud = esxiCloud(r, EsxiHostKeyPolicy.FINGERPRINT);
        try (JenkinsRule.WebClient wc = r.createWebClient()) {
            wc.setConfirmHandler((page, message) -> false);
            HtmlPage page = wc.goTo(cloud.getUrl() + "configure");
            HtmlSelect select = (HtmlSelect) page.getElementByName("_.hostKeyPolicy");
            select.setSelectedAttribute(select.getOptionByValue("TRUST_FIRST_USE"), true);
            r.submit(page.getFormByName("config"));
        }

        EsxiSshBackendConfig saved = ((vSphereCloud) r.jenkins.getCloud(cloud.name))
                .getVsConnectionConfig()
                .getEsxiSsh();
        assertThat(saved.getHostKeyPolicy(), is(EsxiHostKeyPolicy.FINGERPRINT));
    }

    @Test
    void theFormOfAVCenterKeepsItsSettings(JenkinsRule r) throws Exception {
        VSphereConnectionConfig config = new VSphereConnectionConfig("https://vc.example.com", false, null);
        vSphereCloud cloud = new vSphereCloud(config, "A vCenter", 0, 0, false, java.util.List.of());
        r.jenkins.clouds.add(cloud);

        String text = configurePage(r, cloud).asNormalizedText();

        assertThat(text, containsString("Connection type"));
        assertThat(text, containsString("vCenter, through the vSphere API"));
        assertThat(text, containsString("Disable SSL Check"));
        assertThat(text, containsString("Change HTTP Client"));
        assertThat(text, containsString("Test Connection"));
    }
}
