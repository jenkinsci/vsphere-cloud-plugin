package org.jenkinsci.plugins.vsphere.tools.esxi;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.instanceOf;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.nullValue;
import static org.hamcrest.Matchers.sameInstance;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.cloudbees.jenkins.plugins.sshcredentials.impl.BasicSSHUserPrivateKey;
import com.cloudbees.plugins.credentials.CredentialsScope;
import com.cloudbees.plugins.credentials.SystemCredentialsProvider;
import com.cloudbees.plugins.credentials.impl.UsernamePasswordCredentialsImpl;
import hudson.util.FormValidation;
import java.security.KeyPair;
import org.jenkinsci.plugins.vSphereCloud;
import org.jenkinsci.plugins.vsphere.EsxiSshBackendConfig;
import org.jenkinsci.plugins.vsphere.VSphereConnectionConfig;
import org.jenkinsci.plugins.vsphere.VSphereConnectionConfig.BackendType;
import org.jenkinsci.plugins.vsphere.tools.VSphere;
import org.jenkinsci.plugins.vsphere.tools.VSphereException;
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
        config.setEsxiSsh(esxi);
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
