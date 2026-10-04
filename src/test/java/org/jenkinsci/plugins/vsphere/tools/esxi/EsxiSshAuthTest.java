package org.jenkinsci.plugins.vsphere.tools.esxi;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.is;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.cloudbees.jenkins.plugins.sshcredentials.impl.BasicSSHUserPrivateKey;
import com.cloudbees.plugins.credentials.CredentialsScope;
import com.cloudbees.plugins.credentials.common.StandardUsernameCredentials;
import com.cloudbees.plugins.credentials.impl.BaseStandardCredentials;
import com.cloudbees.plugins.credentials.impl.UsernamePasswordCredentialsImpl;
import java.security.KeyPair;
import org.jenkinsci.plugins.vsphere.tools.VSphereException;
import org.junit.jupiter.api.Test;
import org.jvnet.hudson.test.JenkinsRule;
import org.jvnet.hudson.test.junit.jupiter.WithJenkins;

/**
 * Both kinds of credential of the Credentials plugin that can be used to log in over SSH ("Username with
 * password" and "SSH Username with private key") really log in, and nothing else is taken.
 */
@WithJenkins
class EsxiSshAuthTest {

    private static EsxiSshSettings settings(FakeEsxiSshServer server, EsxiSshAuth auth) {
        return new EsxiSshSettings("127.0.0.1", server.port(), auth)
                .withHostKeyPolicy(EsxiHostKeyPolicy.ACCEPT_ANY)
                .withConnectTimeoutSeconds(10)
                .withCommandTimeoutSeconds(10);
    }

    @Test
    void aUsernameWithPasswordLogsIn(JenkinsRule r) throws Exception {
        try (FakeEsxiSshServer server = new FakeEsxiSshServer(new FakeEsxiHost(), "secret", null, false)) {
            StandardUsernameCredentials credentials = new UsernamePasswordCredentialsImpl(
                    CredentialsScope.GLOBAL, "esxi", "an ESXi host", "root", "secret");

            EsxiSshAuth auth = EsxiSshAuth.from(credentials);

            assertThat(auth.getUsername(), is("root"));
            try (TrileadEsxiShell shell = TrileadEsxiShell.connect(settings(server, auth))) {
                assertThat(shell.run("true").succeeded(), is(true));
            }
        }
    }

    @Test
    void anSshUsernameWithPrivateKeyLogsIn(JenkinsRule r) throws Exception {
        KeyPair key = TestKeys.rsa();
        try (FakeEsxiSshServer server = new FakeEsxiSshServer(new FakeEsxiHost(), null, key.getPublic(), false)) {
            StandardUsernameCredentials credentials = new BasicSSHUserPrivateKey(
                    CredentialsScope.GLOBAL,
                    "esxi-key",
                    "root",
                    new BasicSSHUserPrivateKey.DirectEntryPrivateKeySource(TestKeys.pem(key.getPrivate())),
                    null,
                    "an ESXi host");

            EsxiSshAuth auth = EsxiSshAuth.from(credentials);

            assertThat(auth.getUsername(), is("root"));
            try (TrileadEsxiShell shell = TrileadEsxiShell.connect(settings(server, auth))) {
                assertThat(shell.run("true").succeeded(), is(true));
            }
        }
    }

    @Test
    void anSshUsernameWithPrivateKeyAndPassphraseLogsIn(JenkinsRule r) throws Exception {
        KeyPair key = TestKeys.rsa();
        try (FakeEsxiSshServer server = new FakeEsxiSshServer(new FakeEsxiHost(), null, key.getPublic(), false)) {
            StandardUsernameCredentials credentials = new BasicSSHUserPrivateKey(
                    CredentialsScope.GLOBAL,
                    "esxi-key",
                    "root",
                    new BasicSSHUserPrivateKey.DirectEntryPrivateKeySource(
                            TestKeys.encryptedPem(key.getPrivate(), "open sesame")),
                    "open sesame",
                    "an ESXi host");

            try (TrileadEsxiShell shell = TrileadEsxiShell.connect(settings(server, EsxiSshAuth.from(credentials)))) {
                assertThat(shell.run("true").succeeded(), is(true));
            }
        }
    }

    @Test
    void otherKindsOfCredentialAreRefusedWithAnExplanation(JenkinsRule r) {
        StandardUsernameCredentials odd =
                new OddCredentials(CredentialsScope.GLOBAL, "odd", "something that is not a login");

        VSphereException e = assertThrows(VSphereException.class, () -> EsxiSshAuth.from(odd));

        assertThat(e.getMessage(), containsString("\"odd\" cannot be used to log in over SSH"));
        assertThat(e.getMessage(), containsString("username with password"));
    }

    /** A credential that has a user name, but is neither a password nor a key. */
    private static final class OddCredentials extends BaseStandardCredentials implements StandardUsernameCredentials {
        private static final long serialVersionUID = 1L;

        OddCredentials(CredentialsScope scope, String id, String description) {
            super(scope, id, description);
        }

        @Override
        public String getUsername() {
            return "root";
        }

        @Override
        public boolean isUsernameSecret() {
            return false;
        }
    }
}
