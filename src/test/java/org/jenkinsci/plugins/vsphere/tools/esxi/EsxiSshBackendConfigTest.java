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
package org.jenkinsci.plugins.vsphere.tools.esxi;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.instanceOf;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.notNullValue;
import static org.hamcrest.Matchers.nullValue;
import static org.hamcrest.Matchers.sameInstance;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.cloudbees.jenkins.plugins.sshcredentials.impl.BasicSSHUserPrivateKey;
import com.cloudbees.plugins.credentials.CredentialsScope;
import com.cloudbees.plugins.credentials.SystemCredentialsProvider;
import com.cloudbees.plugins.credentials.impl.UsernamePasswordCredentialsImpl;
import hudson.util.FormValidation;
import java.security.KeyPair;
import java.util.List;
import org.htmlunit.html.DomElement;
import org.htmlunit.html.HtmlPage;
import org.htmlunit.html.HtmlSelect;
import org.jenkinsci.plugins.vsphere.EsxiSshBackendConfig;
import org.jenkinsci.plugins.vsphere.EsxiSshHost;
import org.jenkinsci.plugins.vsphere.VSphereConnectionConfig;
import org.jenkinsci.plugins.vsphere.VSphereConnectionConfig.BackendType;
import org.jenkinsci.plugins.vsphere.tools.VSphere;
import org.jenkinsci.plugins.vsphere.tools.VSphereException;
import org.jenkinsci.plugins.vsphere.vSphereCloud;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.jvnet.hudson.test.JenkinsRule;
import org.jvnet.hudson.test.junit.jupiter.WithJenkins;

/**
 * A cloud whose connection configuration is for a standalone ESXi host over SSH really works that way: the
 * credential is looked up (of either kind), the host key is trusted as configured (and the one seen first is
 * remembered in the configuration), and the connection is a {@link VSphere} like any other, also from the pool.
 */
@WithJenkins
class EsxiSshBackendConfigTest {

    private static final String VMX = "displayName = \"kube-master\"\nnumvcpus = \"2\"\n";

    private FakeEsxiHost host;
    private FakeEsxiSshServer server;

    @BeforeEach
    void setUp() {
        host = new FakeEsxiHost();
        host.addVm(1, "kube-master", "datastore1", "kube-master/kube-master.vmx", VMX);
    }

    @AfterEach
    void tearDown() throws Exception {
        if (server != null) {
            server.close();
        }
    }

    private static void addPasswordCredentials(String id) throws Exception {
        SystemCredentialsProvider.getInstance()
                .getCredentials()
                .add(new UsernamePasswordCredentialsImpl(
                        CredentialsScope.GLOBAL, id, "an ESXi host", "root", "secret"));
    }

    private static void addKeyCredentials(String id, KeyPair key) throws Exception {
        SystemCredentialsProvider.getInstance()
                .getCredentials()
                .add(new BasicSSHUserPrivateKey(
                        CredentialsScope.GLOBAL,
                        id,
                        "root",
                        new BasicSSHUserPrivateKey.DirectEntryPrivateKeySource(TestKeys.pem(key.getPrivate())),
                        null,
                        "an ESXi host"));
    }

    private vSphereCloud cloudWith(EsxiSshBackendConfig esxi) {
        VSphereConnectionConfig config = new VSphereConnectionConfig("127.0.0.1", null, null);
        config.setBackend(esxi);
        return new vSphereCloud(config, "ESXi", 0, 0, false, null);
    }

    private EsxiSshBackendConfig esxiFor(String credentialsId, EsxiHostKeyPolicy policy) {
        EsxiSshBackendConfig esxi = new EsxiSshBackendConfig(credentialsId);
        esxi.setPort(server.port());
        esxi.setHostKeyPolicy(policy);
        esxi.setConnectTimeoutSeconds(10);
        esxi.setCommandTimeoutSeconds(10);
        return esxi;
    }

    @Test
    void severalHostsAreOneClusterThatEachOfThemIsPartOf(JenkinsRule r) throws Exception {
        final FakeEsxiHost second = new FakeEsxiHost();
        second.shareStorageWith(host);
        second.addVm(2, "on-second", "datastore1", "on-second/on-second.vmx", "displayName = \"on-second\"\n");
        server = new FakeEsxiSshServer(host, "secret", null, false);
        final FakeEsxiSshServer other = new FakeEsxiSshServer(second, "secret", null, false);
        try {
            addPasswordCredentials("esxi-password");
            EsxiSshBackendConfig esxi = esxiFor("esxi-password", EsxiHostKeyPolicy.ACCEPT_ANY);
            // what this host does not say is that of the first: the credentials and the way to trust the host key
            EsxiSshHost more = new EsxiSshHost("127.0.0.1");
            more.setPort(other.port());
            esxi.setAdditionalHosts(List.of(more));
            vSphereCloud cloud = cloudWith(esxi);

            VSphere vsphere = cloud.vSphereInstance();
            try {
                assertThat(vsphere, instanceOf(VSphereEsxiCluster.class));
                assertThat(vsphere.getVmByName("kube-master"), notNullValue());
                assertThat(vsphere.getVmByName("on-second"), notNullValue());
                assertThat(vsphere.countVms(), is(2));
            } finally {
                vsphere.disconnect();
            }
        } finally {
            other.close();
        }
    }

    @Test
    void anAdditionalHostThatDoesNotSayIsTestedWithTheCredentialsPortAndTrustOfTheFirstHost(JenkinsRule r)
            throws Exception {
        server = new FakeEsxiSshServer(host, "secret", null, false);
        addPasswordCredentials("esxi-password");

        // nothing of its own: not the credentials, nor the port (0), nor how the fingerprint is trusted
        FormValidation result = new EsxiSshHost.DescriptorImpl()
                .doTestConnection(
                        null,
                        "127.0.0.1",
                        "",
                        "0",
                        "",
                        "",
                        "esxi-password",
                        String.valueOf(server.port()),
                        EsxiHostKeyPolicy.ACCEPT_ANY.name());

        assertThat(result.kind, is(FormValidation.Kind.OK));
        assertThat(result.getMessage(), containsString("Logged in to ssh://root@127.0.0.1"));
        // and the fingerprint is asked for on the port of the first host as well
        assertThat(
                new EsxiSshHost.DescriptorImpl()
                        .doQueryHostKey(null, "127.0.0.1", "", String.valueOf(server.port()))
                        .kind,
                is(FormValidation.Kind.OK));
    }

    @Test
    void anAdditionalHostIsTestedInTheFormWithWhatTheFirstHostHasChosenAndNothingOfItIsSaved(JenkinsRule r)
            throws Exception {
        server = new FakeEsxiSshServer(host, "secret", null, false);
        addPasswordCredentials("esxi-password");
        EsxiSshBackendConfig esxi = new EsxiSshBackendConfig(null);
        esxi.setPort(server.port());
        esxi.setHostKeyPolicy(EsxiHostKeyPolicy.ACCEPT_ANY);
        // it says nothing but where it is, and the first host has no credentials yet
        esxi.setAdditionalHosts(List.of(new EsxiSshHost("127.0.0.1")));
        VSphereConnectionConfig config = new VSphereConnectionConfig("127.0.0.1");
        config.setBackend(esxi);
        vSphereCloud cloud = new vSphereCloud(config, "Two hosts", 0, 0, false, List.of());
        r.jenkins.clouds.add(cloud);

        try (JenkinsRule.WebClient wc = r.createWebClient()) {
            HtmlPage page = wc.goTo(cloud.getUrl() + "configure");
            wc.waitForBackgroundJavaScript(3000);
            DomElement button = (DomElement) page.getByXPath("//button[@data-validate-button-method='testConnection']")
                    .get(1);
            button.click();
            wc.waitForBackgroundJavaScript(15_000);
            assertThat(button.getParentNode().asNormalizedText(), containsString("The login was not tried"));

            // the credentials are chosen for the first host, in the form: the host that is added follows
            ((HtmlSelect) page.getByXPath("//select[contains(@class,'credentials-select') and @name='_.credentialsId']")
                            .get(0))
                    .setSelectedAttribute("esxi-password", true);
            button.click();
            wc.waitForBackgroundJavaScript(15_000);
            assertThat(button.getParentNode().asNormalizedText(), containsString("Logged in to ssh://root@127.0.0.1"));

            // and the copies of the settings are in the form, and not in what it saves
            r.submit(page.getFormByName("config"));
        }
        final vSphereCloud saved = (vSphereCloud) r.jenkins.getCloud(cloud.name);
        assertThat(saved.getVsConnectionConfig().getEsxiSsh().getCredentialsId(), is("esxi-password"));
        assertThat(
                saved.getVsConnectionConfig().getEsxiSsh().getAdditionalHosts().size(), is(1));
        assertThat(
                saved.getVsConnectionConfig()
                        .getEsxiSsh()
                        .getAdditionalHosts()
                        .get(0)
                        .getCredentialsId(),
                is((String) null));
        final String xml = jenkins.model.Jenkins.XSTREAM2.toXML(saved);
        assertThat(xml, not(containsString("firstCredentialsId")));
        assertThat(xml, not(containsString("firstPort")));
        assertThat(xml, not(containsString("firstHostKeyPolicy")));
        final String exported =
                r.jenkins.getRootDir().toPath().resolve("config.xml").toFile().exists()
                        ? new String(
                                java.nio.file.Files.readAllBytes(
                                        r.jenkins.getRootDir().toPath().resolve("config.xml")),
                                java.nio.charset.StandardCharsets.UTF_8)
                        : "";
        assertThat(exported, not(containsString("firstCredentialsId")));
    }

    @Test
    void theTestOfTheFirstHostInTheFormUsesItsOwnSettingsWhenThereAreMoreHosts(JenkinsRule r) throws Exception {
        server = new FakeEsxiSshServer(host, "secret", null, false);
        addPasswordCredentials("esxi-password");
        // the other host has fields of the same names (credentials, port, ...), which are not what the first host is
        // tested with
        EsxiSshBackendConfig esxi = new EsxiSshBackendConfig(null);
        esxi.setPort(server.port());
        esxi.setHostKeyPolicy(EsxiHostKeyPolicy.ACCEPT_ANY);
        esxi.setAdditionalHosts(List.of(new EsxiSshHost("127.0.0.1")));
        VSphereConnectionConfig config = new VSphereConnectionConfig("127.0.0.1");
        config.setBackend(esxi);
        vSphereCloud cloud = new vSphereCloud(config, "Two hosts", 0, 0, false, List.of());
        r.jenkins.clouds.add(cloud);

        try (JenkinsRule.WebClient wc = r.createWebClient()) {
            HtmlPage page = wc.goTo(cloud.getUrl() + "configure");
            wc.waitForBackgroundJavaScript(3000);
            // the credentials are chosen in the form, for the first host: the first select of them
            ((HtmlSelect) page.getByXPath("//select[contains(@class,'credentials-select') and @name='_.credentialsId']")
                            .get(0))
                    .setSelectedAttribute("esxi-password", true);
            // and the first of the buttons is that of the first host
            DomElement button = (DomElement) page.getByXPath("//button[@data-validate-button-method='testConnection']")
                    .get(0);
            button.click();
            wc.waitForBackgroundJavaScript(15_000);

            assertThat(button.getParentNode().asNormalizedText(), containsString("Logged in to ssh://root@127.0.0.1"));
        }
    }

    @Test
    void anAdditionalHostWithNoCredentialsIsTestedForItsFingerprintOnly(JenkinsRule r) throws Exception {
        server = new FakeEsxiSshServer(host, "secret", null, false);

        FormValidation result = new EsxiSshHost.DescriptorImpl()
                .doTestConnection(null, "127.0.0.1", "", String.valueOf(server.port()), "", "", "", "", "");

        assertThat(result.kind, is(FormValidation.Kind.WARNING));
        assertThat(result.getMessage(), containsString("The host presents a"));
        assertThat(result.getMessage(), containsString("The login was not tried"));
        assertThat(result.getMessage(), containsString("nor for the first host"));
    }

    @Test
    void anAdditionalHostWithNoCredentialsThatDoesNotAnswerIsAnError(JenkinsRule r) {
        FormValidation result =
                new EsxiSshHost.DescriptorImpl().doTestConnection(null, "127.0.0.1", "", "1", "", "", "", "", "");

        assertThat(result.kind, is(FormValidation.Kind.ERROR));
        assertThat(result.getMessage(), containsString("127.0.0.1:1"));
    }

    @Test
    void aHostThatIsDownDoesNotStopTheOthers(JenkinsRule r) throws Exception {
        server = new FakeEsxiSshServer(host, "secret", null, false);
        addPasswordCredentials("esxi-password");
        EsxiSshBackendConfig esxi = esxiFor("esxi-password", EsxiHostKeyPolicy.ACCEPT_ANY);
        EsxiSshHost down = new EsxiSshHost("127.0.0.1");
        down.setPort(1);
        esxi.setAdditionalHosts(List.of(down));

        VSphere vsphere = cloudWith(esxi).vSphereInstance();
        try {
            assertThat(vsphere, instanceOf(VSphereEsxiCluster.class));
            assertThat(vsphere.getVmByName("kube-master"), notNullValue());
        } finally {
            vsphere.disconnect();
        }
    }

    @Test
    void aHostAloneIsNotACluster(JenkinsRule r) throws Exception {
        server = new FakeEsxiSshServer(host, "secret", null, false);
        addPasswordCredentials("esxi-password");

        VSphere vsphere = cloudWith(esxiFor("esxi-password", EsxiHostKeyPolicy.ACCEPT_ANY))
                .vSphereInstance();
        try {
            assertThat(vsphere, instanceOf(VSphereEsxiSsh.class));
        } finally {
            vsphere.disconnect();
        }
    }

    @Test
    void aCloudConnectsToAnEsxiHostWithAPasswordCredential(JenkinsRule r) throws Exception {
        server = new FakeEsxiSshServer(host, "secret", null, false);
        addPasswordCredentials("esxi-password");
        vSphereCloud cloud = cloudWith(esxiFor("esxi-password", EsxiHostKeyPolicy.ACCEPT_ANY));

        assertThat(cloud.getVsConnectionConfig().getBackendType(), is(BackendType.ESXI_SSH));
        VSphere vsphere = cloud.vSphereInstance();
        try {
            assertThat(vsphere, instanceOf(VSphereEsxiSsh.class));
            assertThat(
                    vsphere.getVmByName("kube-master").getConfig().getHardware().getNumCPU(), is(2));
        } finally {
            vsphere.disconnect();
        }
    }

    @Test
    void aCloudConnectsToAnEsxiHostWithAPrivateKeyCredential(JenkinsRule r) throws Exception {
        KeyPair key = TestKeys.rsa();
        server = new FakeEsxiSshServer(host, null, key.getPublic(), false);
        addKeyCredentials("esxi-key", key);
        vSphereCloud cloud = cloudWith(esxiFor("esxi-key", EsxiHostKeyPolicy.ACCEPT_ANY));

        VSphere vsphere = cloud.vSphereInstance();
        try {
            assertThat(vsphere.countVms(), is(1));
        } finally {
            vsphere.disconnect();
        }
    }

    @Test
    void theHostKeySeenFirstIsRememberedInTheConfiguration(JenkinsRule r) throws Exception {
        server = new FakeEsxiSshServer(host, "secret", null, false);
        addPasswordCredentials("esxi-password");
        EsxiSshBackendConfig esxi = esxiFor("esxi-password", EsxiHostKeyPolicy.TRUST_FIRST_USE);
        vSphereCloud cloud = cloudWith(esxi);
        assertThat(esxi.getHostKeyFingerprint(), is(nullValue()));

        cloud.vSphereInstance().disconnect();

        // from then on this is the host key that is required
        assertThat(esxi.getHostKeyFingerprint(), is(server.hostKeySha256()));
        cloud.vSphereInstance().disconnect();
        esxi.setHostKeyFingerprint("SHA256:AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA");
        VSphereException e = assertThrows(VSphereException.class, cloud::vSphereInstance);
        assertThat(e.getMessage(), containsString("is not trusted"));
    }

    @Test
    void withNoHostKeyToTrustTheDefaultRefusesAndShowsTheFingerprint(JenkinsRule r) throws Exception {
        server = new FakeEsxiSshServer(host, "secret", null, false);
        addPasswordCredentials("esxi-password");
        vSphereCloud cloud = cloudWith(esxiFor("esxi-password", EsxiHostKeyPolicy.FINGERPRINT));

        VSphereException e = assertThrows(VSphereException.class, cloud::vSphereInstance);

        assertThat(e.getMessage(), containsString(server.hostKeySha256()));
    }

    @Test
    void aMissingCredentialIsReportedWithItsId(JenkinsRule r) throws Exception {
        server = new FakeEsxiSshServer(host, "secret", null, false);

        vSphereCloud nothing = cloudWith(esxiFor(null, EsxiHostKeyPolicy.ACCEPT_ANY));
        assertThat(
                assertThrows(VSphereException.class, nothing::vSphereInstance).getMessage(),
                containsString("SSH credentials are not specified"));

        vSphereCloud unknown = cloudWith(esxiFor("no-such-credential", EsxiHostKeyPolicy.ACCEPT_ANY));
        assertThat(
                assertThrows(VSphereException.class, unknown::vSphereInstance).getMessage(),
                containsString("no-such-credential"));
    }

    @Test
    void theConnectionPoolWorksWithAnEsxiHost(JenkinsRule r) throws Exception {
        server = new FakeEsxiSshServer(host, "secret", null, false);
        addPasswordCredentials("esxi-password");
        vSphereCloud cloud = cloudWith(esxiFor("esxi-password", EsxiHostKeyPolicy.ACCEPT_ANY));
        cloud.setUseConnectionPool(true);

        VSphere first = cloud.vSphereInstance();
        first.disconnect(); // goes back to the pool: the session is not ended
        assertThat(first.isSessionAlive(), is(true));
        VSphere second = cloud.vSphereInstance();

        assertThat(second, sameInstance(first));
        assertThat(second.countVms(), is(1));
    }

    // -- the actions of the form --

    @Test
    void testingTheConnectionFromTheFormShowsTheHostKeyToTrust(JenkinsRule r) throws Exception {
        server = new FakeEsxiSshServer(host, "secret", null, false);
        addPasswordCredentials("esxi-password");
        EsxiSshBackendConfig.DescriptorImpl descriptor =
                r.jenkins.getDescriptorByType(EsxiSshBackendConfig.DescriptorImpl.class);
        String port = Integer.toString(server.port());

        FormValidation untrusted =
                descriptor.doTestConnection(null, "127.0.0.1", "esxi-password", port, "FINGERPRINT", "", "10");
        assertThat(untrusted.kind, is(FormValidation.Kind.WARNING));
        assertThat(untrusted.getMessage(), containsString(server.hostKeySha256()));

        FormValidation trusted = descriptor.doTestConnection(
                null, "127.0.0.1", "esxi-password", port, "FINGERPRINT", server.hostKeySha256(), "10");
        assertThat(trusted.kind, is(FormValidation.Kind.OK));
        assertThat(trusted.getMessage(), containsString("Logged in to ssh://root@127.0.0.1"));

        FormValidation firstUse =
                descriptor.doTestConnection(null, "127.0.0.1", "esxi-password", port, "TRUST_FIRST_USE", "", "10");
        assertThat(firstUse.kind, is(FormValidation.Kind.OK));
        assertThat(firstUse.getMessage(), containsString("going to be remembered"));

        FormValidation wrongLogin =
                descriptor.doTestConnection(null, "127.0.0.1", "no-such-credential", port, "ACCEPT_ANY", "", "10");
        assertThat(wrongLogin.kind, is(FormValidation.Kind.ERROR));
    }

    @Test
    void theHostKeyCanBeQueriedFromTheFormWithoutCredentials(JenkinsRule r) throws Exception {
        server = new FakeEsxiSshServer(host, "secret", null, false);
        EsxiSshBackendConfig.DescriptorImpl descriptor =
                r.jenkins.getDescriptorByType(EsxiSshBackendConfig.DescriptorImpl.class);

        FormValidation key = descriptor.doQueryHostKey(null, "127.0.0.1", Integer.toString(server.port()), "10");

        assertThat(key.kind, is(FormValidation.Kind.OK));
        assertThat(key.getMessage(), containsString(server.hostKeySha256()));
        assertThat(server.authAttempts.get(), is(0));
        assertThat(descriptor.doQueryHostKey(null, "", "22", "10").kind, is(FormValidation.Kind.ERROR));
    }

    @Test
    void theCredentialsOfBothKindsAreOffered(JenkinsRule r) throws Exception {
        addPasswordCredentials("esxi-password");
        addKeyCredentials("esxi-key", TestKeys.rsa());
        EsxiSshBackendConfig.DescriptorImpl descriptor =
                r.jenkins.getDescriptorByType(EsxiSshBackendConfig.DescriptorImpl.class);

        java.util.List<String> offered = new java.util.ArrayList<>();
        descriptor.doFillCredentialsIdItems(null, "127.0.0.1", "").forEach(o -> offered.add(o.value));

        assertThat(offered.contains("esxi-password"), is(true));
        assertThat(offered.contains("esxi-key"), is(true));
    }
}
