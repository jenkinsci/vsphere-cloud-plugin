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
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.nullValue;

import com.cloudbees.plugins.credentials.CredentialsScope;
import com.cloudbees.plugins.credentials.SystemCredentialsProvider;
import com.cloudbees.plugins.credentials.impl.UsernamePasswordCredentialsImpl;
import hudson.util.FormValidation;
import java.util.ArrayList;
import java.util.List;
import org.htmlunit.html.DomElement;
import org.htmlunit.html.HtmlButton;
import org.htmlunit.html.HtmlForm;
import org.htmlunit.html.HtmlInput;
import org.htmlunit.html.HtmlPage;
import org.htmlunit.html.HtmlRadioButtonInput;
import org.htmlunit.html.HtmlSelect;
import org.jenkinsci.plugins.vsphere.EsxiSshBackendConfig;
import org.jenkinsci.plugins.vsphere.EsxiSshHost;
import org.jenkinsci.plugins.vsphere.VCenterBackendConfig;
import org.jenkinsci.plugins.vsphere.VSphereConnectionConfig;
import org.jenkinsci.plugins.vsphere.VSphereConnectionConfig.BackendType;
import org.jenkinsci.plugins.vsphere.tools.esxi.EsxiHostKeyPolicy;
import org.jenkinsci.plugins.vsphere.vSphereCloud;
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
        assertThat(text, containsString("Standalone ESXi host(s) over SSH"));
        assertThat(text, containsString("SSH Port"));
        assertThat(text, containsString("Fingerprint trust"));
        assertThat(text, containsString("Fingerprint"));
        assertThat(text, containsString("Show fingerprint"));
        assertThat(text, containsString("Test Connection"));
    }

    /** What the validation button says after it is pressed (it asks the server, which answers in the page). */
    private static String pressed(JenkinsRule.WebClient wc, HtmlPage page, String method) throws Exception {
        final DomElement button =
                (DomElement) page.getByXPath("//button[@data-validate-button-method='" + method + "']")
                        .get(0);
        button.click();
        wc.waitForBackgroundJavaScript(10_000);
        return button.getParentNode().asNormalizedText().replace(String.valueOf((char) 10), " | ");
    }

    @Test
    void theButtonsOfANewEsxiCloudFindTheHostThatWasEntered(JenkinsRule r) throws Exception {
        try (JenkinsRule.WebClient wc = r.createWebClient()) {
            // the first page of a new cloud asks for its name and kind, the second has its settings
            HtmlPage page = wc.goTo("manage/cloud/new");
            page.getForms().stream()
                    .filter(f -> !f.getInputsByName("mode").isEmpty())
                    .findFirst()
                    .get()
                    .getInputByName("name")
                    .setValue("buttons");
            ((HtmlRadioButtonInput) page.getElementById("org.jenkinsci.plugins.vsphere.vSphereCloud")).setChecked(true);
            HtmlPage settings = (HtmlPage) ((HtmlButton) page.getElementById("ok")).click();
            wc.waitForBackgroundJavaScript(3000);
            // the settings of a connection type are there once it is chosen
            HtmlSelect type = (HtmlSelect) settings.getByXPath("//select[contains(@class,'dropdownList')]")
                    .get(0);
            type.setSelectedAttribute(type.getOptions().get(0), true);
            wc.waitForBackgroundJavaScript(2000);
            ((HtmlInput) settings.getByXPath("//input[@name='_.vsHost']").get(0)).setValue("127.0.0.1");
            // a port where nothing listens, to get an answer that tells the host that was asked about
            ((HtmlInput) settings.getByXPath("//input[@name='_.port']").get(0)).setValue("1");

            final String shown = pressed(wc, settings, "queryHostKey");
            final String tested = pressed(wc, settings, "testConnection");

            assertThat(shown, containsString("Could not connect to 127.0.0.1:1"));
            assertThat(tested, containsString("ESXi host 127.0.0.1"));
        }
    }

    @Test
    void theButtonsOfAnEsxiCloudFindItsHostToo(JenkinsRule r) throws Exception {
        EsxiSshBackendConfig esxi = new EsxiSshBackendConfig(null);
        esxi.setPort(1);
        VSphereConnectionConfig config = new VSphereConnectionConfig("127.0.0.1");
        config.setBackend(esxi);
        vSphereCloud cloud = new vSphereCloud(config, "An ESXi host", 0, 0, false, java.util.List.of());
        r.jenkins.clouds.add(cloud);

        try (JenkinsRule.WebClient wc = r.createWebClient()) {
            HtmlPage page = wc.goTo(cloud.getUrl() + "configure");

            assertThat(pressed(wc, page, "queryHostKey"), containsString("Could not connect to 127.0.0.1:1"));
        }
    }

    @Test
    void aPlainHostNameIsWarnedAboutForAVCenterOnlyAndAWrongOneIsRefused(JenkinsRule r) {
        VSphereConnectionConfig.DescriptorImpl descriptor =
                r.jenkins.getDescriptorByType(VSphereConnectionConfig.DescriptorImpl.class);

        // the port is there when a standalone ESXi host over SSH is chosen, and not when a vCenter is
        assertThat(descriptor.doCheckVsHost(null, "10.1.2.3", "22").kind, is(FormValidation.Kind.OK));
        assertThat(descriptor.doCheckVsHost(null, "esxi.example.com", "22").kind, is(FormValidation.Kind.OK));
        assertThat(descriptor.doCheckVsHost(null, "10.1.2.3", null).kind, is(FormValidation.Kind.WARNING));
        assertThat(descriptor.doCheckVsHost(null, "https://vc.example.com", null).kind, is(FormValidation.Kind.OK));
        assertThat(descriptor.doCheckVsHost(null, "https://vc.example.com/", null).kind, is(FormValidation.Kind.ERROR));
        assertThat(descriptor.doCheckVsHost(null, "ssh://esxi.example.com", "22").kind, is(FormValidation.Kind.ERROR));
        assertThat(descriptor.doCheckVsHost(null, "", null).kind, is(FormValidation.Kind.ERROR));
    }

    @Test
    void theWarningAboutAPlainHostNameFollowsTheConnectionTypeInTheForm(JenkinsRule r) throws Exception {
        try (JenkinsRule.WebClient wc = r.createWebClient()) {
            HtmlPage page = wc.goTo("manage/cloud/new");
            page.getForms().stream()
                    .filter(f -> !f.getInputsByName("mode").isEmpty())
                    .findFirst()
                    .get()
                    .getInputByName("name")
                    .setValue("warning");
            ((HtmlRadioButtonInput) page.getElementById("org.jenkinsci.plugins.vsphere.vSphereCloud")).setChecked(true);
            HtmlPage settings = (HtmlPage) ((HtmlButton) page.getElementById("ok")).click();
            wc.waitForBackgroundJavaScript(3000);
            HtmlSelect type = (HtmlSelect) settings.getByXPath("//select[contains(@class,'dropdownList')]")
                    .get(0);
            HtmlInput host =
                    (HtmlInput) settings.getByXPath("//input[@name='_.vsHost']").get(0);

            // a vCenter, which is what is chosen first
            type.setSelectedAttribute(type.getOptions().get(1), true);
            wc.waitForBackgroundJavaScript(2000);
            host.setValue("10.1.2.3");
            host.fireEvent("change");
            wc.waitForBackgroundJavaScript(5000);
            assertThat(settings.asNormalizedText(), containsString("Without https://"));

            // a standalone ESXi host: the same host name is fine, and it is checked again when the type changes
            type.setSelectedAttribute(type.getOptions().get(0), true);
            wc.waitForBackgroundJavaScript(5000);
            assertThat(settings.asNormalizedText(), not(containsString("Without https://")));

            type.setSelectedAttribute(type.getOptions().get(1), true);
            wc.waitForBackgroundJavaScript(5000);
            assertThat(settings.asNormalizedText(), containsString("Without https://"));
        }
    }

    @Test
    void anEsxiCloudThatIsOpenedHasNoWarningAboutItsPlainHostName(JenkinsRule r) throws Exception {
        EsxiSshBackendConfig esxi = new EsxiSshBackendConfig(null);
        VSphereConnectionConfig config = new VSphereConnectionConfig("10.1.2.3");
        config.setBackend(esxi);
        vSphereCloud cloud = new vSphereCloud(config, "An ESXi host", 0, 0, false, java.util.List.of());
        r.jenkins.clouds.add(cloud);

        try (JenkinsRule.WebClient wc = r.createWebClient()) {
            HtmlPage page = wc.goTo(cloud.getUrl() + "configure");
            wc.waitForBackgroundJavaScript(5000);

            assertThat(page.asNormalizedText(), not(containsString("Without https://")));
        }
    }

    @Test
    void anAdditionalHostLeftToTheDefaultsSurvivesAFormRoundTrip(JenkinsRule r) throws Exception {
        // no port, no credentials, "the same as for the first host" (which the form sends as an empty value)
        EsxiSshBackendConfig esxi = new EsxiSshBackendConfig(null);
        esxi.setAdditionalHosts(java.util.List.of(new EsxiSshHost("10.0.0.2")));
        VSphereConnectionConfig config = new VSphereConnectionConfig("10.0.0.1");
        config.setBackend(esxi);
        vSphereCloud cloud = new vSphereCloud(config, "Two hosts", 0, 0, false, java.util.List.of());
        r.jenkins.clouds.add(cloud);

        submit(r, cloud);

        EsxiSshHost kept = ((vSphereCloud) r.jenkins.getCloud(cloud.name))
                .getVsConnectionConfig()
                .getEsxiSsh()
                .getAdditionalHosts()
                .get(0);
        assertThat(kept.getHost(), is("10.0.0.2"));
        assertThat(kept.getHostKeyPolicy(), is(nullValue()));
        assertThat(kept.getPort(), is(0));
        assertThat(kept.getCredentialsId(), is(nullValue()));
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
