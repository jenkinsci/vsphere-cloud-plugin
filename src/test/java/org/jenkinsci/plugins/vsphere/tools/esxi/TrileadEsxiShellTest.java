package org.jenkinsci.plugins.vsphere.tools.esxi;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.nullValue;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.vmware.vim25.mo.VirtualMachine;
import java.security.KeyPair;
import java.util.List;
import org.jenkinsci.plugins.vsphere.tools.VSphereException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * The real SSH code against a real SSH server (which runs the commands on the simulated host): logging in with a
 * password or with a private key, trusting the host key or not, and the way that commands end.
 */
class TrileadEsxiShellTest {

    private static final String VMX = "displayName = \"kube-master\"\nnumvcpus = \"2\"\n";

    private FakeEsxiHost host;
    private FakeEsxiSshServer server;
    private KeyPair userKey;

    @BeforeEach
    void setUp() throws Exception {
        host = new FakeEsxiHost();
        host.addVm(1, "kube-master", "datastore1", "kube-master/kube-master.vmx", VMX);
        userKey = TestKeys.rsa();
    }

    @AfterEach
    void tearDown() throws Exception {
        if (server != null) {
            server.close();
        }
    }

    private FakeEsxiSshServer start(String password, boolean keyLogin, boolean keyboardInteractiveOnly)
            throws Exception {
        server = new FakeEsxiSshServer(host, password, keyLogin ? userKey.getPublic() : null, keyboardInteractiveOnly);
        return server;
    }

    private EsxiSshSettings settings(EsxiSshAuth auth) {
        return new EsxiSshSettings("127.0.0.1", server.port(), auth)
                .withHostKeyPolicy(EsxiHostKeyPolicy.ACCEPT_ANY)
                .withConnectTimeoutSeconds(10)
                .withCommandTimeoutSeconds(10);
    }

    // -- logging in --

    @Test
    void logsInWithAPasswordAndRunsCommands() throws Exception {
        start("secret", false, false);

        try (TrileadEsxiShell shell = TrileadEsxiShell.connect(settings(EsxiSshAuth.password("root", "secret")))) {
            ShellResult result = shell.run("/bin/vim-cmd vmsvc/getallvms");

            assertThat(result.succeeded(), is(true));
            assertThat(result.getStdout(), containsString("kube-master"));
        }
    }

    @Test
    void logsInWithAPasswordThatIsOnlyAskedForByKeyboardInteractive() throws Exception {
        start("secret", false, true);

        try (TrileadEsxiShell shell = TrileadEsxiShell.connect(settings(EsxiSshAuth.password("root", "secret")))) {
            assertThat(shell.run("true").succeeded(), is(true));
        }
    }

    @Test
    void logsInWithAPrivateKey() throws Exception {
        start(null, true, false);

        EsxiSshAuth auth = EsxiSshAuth.privateKey("root", List.of(TestKeys.pem(userKey.getPrivate())), null);
        try (TrileadEsxiShell shell = TrileadEsxiShell.connect(settings(auth))) {
            assertThat(shell.run("true").succeeded(), is(true));
        }
    }

    @Test
    void logsInWithAPrivateKeyThatHasAPassphrase() throws Exception {
        start(null, true, false);

        EsxiSshAuth auth = EsxiSshAuth.privateKey(
                "root", List.of(TestKeys.encryptedPem(userKey.getPrivate(), "open sesame")), "open sesame");
        try (TrileadEsxiShell shell = TrileadEsxiShell.connect(settings(auth))) {
            assertThat(shell.run("true").succeeded(), is(true));
        }
    }

    @Test
    void theFirstOfSeveralKeysThatTheHostTakesIsUsed() throws Exception {
        start(null, true, false);

        EsxiSshAuth auth = EsxiSshAuth.privateKey(
                "root", List.of(TestKeys.pem(TestKeys.rsa().getPrivate()), TestKeys.pem(userKey.getPrivate())), null);
        try (TrileadEsxiShell shell = TrileadEsxiShell.connect(settings(auth))) {
            assertThat(shell.run("true").succeeded(), is(true));
        }
    }

    @Test
    void aWrongPasswordIsRefusedAndSaysSo() throws Exception {
        start("secret", false, false);

        VSphereException e = assertThrows(
                VSphereException.class,
                () -> TrileadEsxiShell.connect(settings(EsxiSshAuth.password("root", "wrong"))));

        assertThat(e.getMessage(), containsString("did not accept the password of root"));
        assertThat(e.getMessage(), not(containsString("wrong")));
    }

    @Test
    void aKeyTheHostDoesNotKnowIsRefused() throws Exception {
        start(null, true, false);

        EsxiSshAuth auth = EsxiSshAuth.privateKey(
                "root", List.of(TestKeys.pem(TestKeys.rsa().getPrivate())), null);
        VSphereException e = assertThrows(VSphereException.class, () -> TrileadEsxiShell.connect(settings(auth)));

        assertThat(e.getMessage(), containsString("did not accept the private key of root"));
    }

    @Test
    void aWrongPassphraseIsRefused() throws Exception {
        start(null, true, false);

        EsxiSshAuth auth = EsxiSshAuth.privateKey(
                "root", List.of(TestKeys.encryptedPem(userKey.getPrivate(), "open sesame")), "wrong");
        VSphereException e = assertThrows(VSphereException.class, () -> TrileadEsxiShell.connect(settings(auth)));

        assertThat(e.getMessage(), containsString("did not accept the private key of root"));
    }

    @Test
    void nothingToLogInWithIsReported() throws Exception {
        start("secret", false, false);

        VSphereException e = assertThrows(
                VSphereException.class,
                () -> TrileadEsxiShell.connect(settings(EsxiSshAuth.privateKey("root", List.of(), null))));

        assertThat(e.getMessage(), containsString("no private key"));
    }

    // -- trusting the host --

    @Test
    void aHostKeyWithTheExpectedFingerprintIsTrusted() throws Exception {
        start("secret", false, false);

        EsxiSshSettings pinned = new EsxiSshSettings("127.0.0.1", server.port(), EsxiSshAuth.password("root", "secret"))
                .withHostKeyFingerprint(server.hostKeySha256());
        try (TrileadEsxiShell shell = TrileadEsxiShell.connect(pinned)) {
            assertThat(shell.run("true").succeeded(), is(true));
        }
    }

    @Test
    void theFingerprintMayBeGivenWithoutThePrefix() throws Exception {
        start("secret", false, false);

        EsxiSshSettings pinned = new EsxiSshSettings("127.0.0.1", server.port(), EsxiSshAuth.password("root", "secret"))
                .withHostKeyFingerprint(server.hostKeySha256().substring("SHA256:".length()));
        try (TrileadEsxiShell shell = TrileadEsxiShell.connect(pinned)) {
            assertThat(shell.run("true").succeeded(), is(true));
        }
    }

    @Test
    void aHostKeyOfAnotherFingerprintIsNotTrustedEvenIfAnyIsAccepted() throws Exception {
        start("secret", false, false);

        EsxiSshSettings pinned = new EsxiSshSettings("127.0.0.1", server.port(), EsxiSshAuth.password("root", "secret"))
                .withHostKeyFingerprint("SHA256:AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA")
                .withHostKeyPolicy(EsxiHostKeyPolicy.ACCEPT_ANY);
        VSphereException e = assertThrows(VSphereException.class, () -> TrileadEsxiShell.connect(pinned));

        assertThat(e.getMessage(), containsString("is not trusted"));
        assertThat(e.getMessage(), containsString(server.hostKeySha256())); // so that it can be put in the settings
        assertThat(e.getMessage(), containsString("not the SHA256:AAAA"));
    }

    @Test
    void withoutAFingerprintOrPermissionToAcceptAnyTheHostIsNotTrusted() throws Exception {
        start("secret", false, false);

        EsxiSshSettings strict =
                new EsxiSshSettings("127.0.0.1", server.port(), EsxiSshAuth.password("root", "secret"));
        VSphereException e = assertThrows(VSphereException.class, () -> TrileadEsxiShell.connect(strict));

        assertThat(e.getMessage(), containsString("is not trusted"));
        assertThat(e.getMessage(), containsString(server.hostKeySha256()));
        assertThat(e.getMessage(), containsString("Put that fingerprint in the settings"));
    }

    // -- trusting the host key seen first --

    private EsxiSshSettings firstUse(EsxiHostKeyStore store) {
        return new EsxiSshSettings("127.0.0.1", server.port(), EsxiSshAuth.password("root", "secret"))
                .withHostKeyPolicy(EsxiHostKeyPolicy.TRUST_FIRST_USE)
                .withHostKeyStore(store)
                .withConnectTimeoutSeconds(10);
    }

    @Test
    void theHostKeySeenFirstIsRememberedAndThenRequired() throws Exception {
        start("secret", false, false);
        InMemoryEsxiHostKeyStore store = new InMemoryEsxiHostKeyStore();
        assertThat(store.get("127.0.0.1", server.port()), is(nullValue()));

        try (TrileadEsxiShell shell = TrileadEsxiShell.connect(firstUse(store))) {
            assertThat(shell.run("true").succeeded(), is(true));
        }
        assertThat(store.get("127.0.0.1", server.port()), is(server.hostKeySha256()));

        // the second time it is the remembered one that has to match, and does
        try (TrileadEsxiShell shell = TrileadEsxiShell.connect(firstUse(store))) {
            assertThat(shell.run("true").succeeded(), is(true));
        }
    }

    @Test
    void aHostKeyThatChangedAfterTheFirstUseIsRefused() throws Exception {
        start("secret", false, false);
        InMemoryEsxiHostKeyStore store = new InMemoryEsxiHostKeyStore();
        store.rememberIfAbsent("127.0.0.1", server.port(), "SHA256:somethingElseThatWasSeenBefore");

        VSphereException e = assertThrows(VSphereException.class, () -> TrileadEsxiShell.connect(firstUse(store)));

        assertThat(e.getMessage(), containsString("has changed"));
        assertThat(e.getMessage(), containsString(server.hostKeySha256()));
        assertThat(e.getMessage(), containsString("SHA256:somethingElseThatWasSeenBefore"));
        assertThat(e.getMessage(), containsString("forget the remembered fingerprint"));
        // and what was remembered is not replaced by what is seen now
        assertThat(store.get("127.0.0.1", server.port()), is("SHA256:somethingElseThatWasSeenBefore"));
    }

    @Test
    void forgettingMakesTheNextConnectionAFirstOneAgain() throws Exception {
        start("secret", false, false);
        InMemoryEsxiHostKeyStore store = new InMemoryEsxiHostKeyStore();
        store.rememberIfAbsent("127.0.0.1", server.port(), "SHA256:old");

        store.forget("127.0.0.1", server.port());

        try (TrileadEsxiShell shell = TrileadEsxiShell.connect(firstUse(store))) {
            assertThat(shell.run("true").succeeded(), is(true));
        }
        assertThat(store.get("127.0.0.1", server.port()), is(server.hostKeySha256()));
    }

    @Test
    void aFingerprintThatIsGivenBeatsTrustingTheFirstOne() throws Exception {
        start("secret", false, false);
        InMemoryEsxiHostKeyStore store = new InMemoryEsxiHostKeyStore();

        VSphereException e = assertThrows(
                VSphereException.class,
                () -> TrileadEsxiShell.connect(
                        firstUse(store).withHostKeyFingerprint("SHA256:AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA")));

        assertThat(e.getMessage(), containsString("is not trusted"));
        // the host key that was refused is not remembered as the first one
        assertThat(store.get("127.0.0.1", server.port()), is(nullValue()));
    }

    @Test
    void ofSeveralFirstConnectionsAtOnceAllTrustTheSameKey() throws Exception {
        start("secret", false, false);
        InMemoryEsxiHostKeyStore store = new InMemoryEsxiHostKeyStore();

        String first = store.rememberIfAbsent("h", 22, "SHA256:one");
        String second = store.rememberIfAbsent("h", 22, "SHA256:two");

        assertThat(first, is("SHA256:one"));
        assertThat(second, is("SHA256:one"));
    }

    // -- asking the host which key it has --

    @Test
    void theHostKeyCanBeQueriedWithoutLoggingIn() throws Exception {
        start("secret", false, false);

        EsxiHostKeyInfo key = TrileadEsxiShell.queryHostKey("127.0.0.1", server.port(), 10);

        assertThat(key.getSha256(), is(server.hostKeySha256()));
        assertThat(key.getAlgorithm(), containsString("rsa"));
        assertThat(key.getMd5().matches("([0-9a-f]{2}:){15}[0-9a-f]{2}"), is(true));
        // no credentials were sent, and nothing was run on the host
        assertThat(server.authAttempts.get(), is(0));
        assertThat(host.commands.isEmpty(), is(true));
    }

    @Test
    void queryingAnUnreachableHostFails() {
        VSphereException e =
                assertThrows(VSphereException.class, () -> TrileadEsxiShell.queryHostKey("127.0.0.1", 1, 5));

        assertThat(e.getMessage(), containsString("Could not connect to 127.0.0.1:1 over SSH"));
    }

    // -- testing the connection --

    @Test
    void aTestOfAWorkingConnectionSaysSo() throws Exception {
        start("secret", false, false);

        EsxiConnectionTestResult result = TrileadEsxiShell.test(settings(EsxiSshAuth.password("root", "secret")));

        assertThat(result.isOk(), is(true));
        assertThat(result.isHostKeyTrusted(), is(true));
        assertThat(result.isLoggedIn(), is(true));
        assertThat(result.getHostKey().getSha256(), is(server.hostKeySha256()));
        assertThat(result.getMessage(), containsString("Logged in to ssh://root@127.0.0.1"));
    }

    @Test
    void aTestWithoutAFingerprintShowsTheHostKeyToTrust() throws Exception {
        start("secret", false, false);
        EsxiSshSettings strict =
                new EsxiSshSettings("127.0.0.1", server.port(), EsxiSshAuth.password("root", "secret"));

        EsxiConnectionTestResult result = TrileadEsxiShell.test(strict);

        assertThat(result.isOk(), is(false));
        assertThat(result.isHostKeyTrusted(), is(false));
        assertThat(result.getHostKey().getSha256(), is(server.hostKeySha256()));
        assertThat(result.getMessage(), containsString(server.hostKeySha256()));
        // not trusted, so no login was tried
        assertThat(server.authAttempts.get(), is(0));
    }

    @Test
    void aTestOfFirstUseHasNoEffectAndSaysWhatWillHappen() throws Exception {
        start("secret", false, false);
        InMemoryEsxiHostKeyStore store = new InMemoryEsxiHostKeyStore();

        EsxiConnectionTestResult result = TrileadEsxiShell.test(firstUse(store));

        assertThat(result.isOk(), is(true));
        assertThat(result.getMessage(), containsString("going to be remembered"));
        assertThat(store.get("127.0.0.1", server.port()), is(nullValue()));
    }

    @Test
    void aTestSaysThatAChangedHostKeyIsNotTrusted() throws Exception {
        start("secret", false, false);
        InMemoryEsxiHostKeyStore store = new InMemoryEsxiHostKeyStore();
        store.rememberIfAbsent("127.0.0.1", server.port(), "SHA256:old");

        EsxiConnectionTestResult result = TrileadEsxiShell.test(firstUse(store));

        assertThat(result.isOk(), is(false));
        assertThat(result.isHostKeyTrusted(), is(false));
        assertThat(result.getMessage(), containsString("has changed"));
    }

    @Test
    void aTestWithAWrongPasswordTrustsTheHostKeyButDoesNotLogIn() throws Exception {
        start("secret", false, false);

        EsxiConnectionTestResult result = TrileadEsxiShell.test(settings(EsxiSshAuth.password("root", "wrong")));

        assertThat(result.isOk(), is(false));
        assertThat(result.isHostKeyTrusted(), is(true));
        assertThat(result.isLoggedIn(), is(false));
        assertThat(result.getMessage(), containsString("did not accept the password of root"));
    }

    @Test
    void aTestOfAnUnreachableHostSaysSo() {
        EsxiSshSettings nobody =
                new EsxiSshSettings("127.0.0.1", 1, EsxiSshAuth.password("root", "x")).withConnectTimeoutSeconds(5);

        EsxiConnectionTestResult result = TrileadEsxiShell.test(nobody);

        assertThat(result.isOk(), is(false));
        assertThat(result.getHostKey(), is(nullValue()));
        assertThat(result.getMessage(), containsString("Could not connect to 127.0.0.1:1"));
    }

    @Test
    void theMd5FingerprintIsAcceptedToo() {
        byte[] key = {1, 2, 3, 4, 5};

        assertThat(EsxiHostKeyVerifier.matches(EsxiHostKeyVerifier.md5(key), key), is(true));
        assertThat(EsxiHostKeyVerifier.matches("MD5:" + EsxiHostKeyVerifier.md5(key), key), is(true));
        assertThat(EsxiHostKeyVerifier.matches(EsxiHostKeyVerifier.md5(key).toUpperCase(), key), is(true));
        assertThat(EsxiHostKeyVerifier.matches(EsxiHostKeyVerifier.sha256(key), key), is(true));
        assertThat(EsxiHostKeyVerifier.matches("00:11:22:33:44:55:66:77:88:99:aa:bb:cc:dd:ee:ff", key), is(false));
        assertThat(EsxiHostKeyVerifier.matches("SHA256:nope", key), is(false));
    }

    @Test
    void anUnreachableHostIsReported() {
        EsxiSshSettings nobody = new EsxiSshSettings("127.0.0.1", 1, EsxiSshAuth.password("root", "x"))
                .withHostKeyPolicy(EsxiHostKeyPolicy.ACCEPT_ANY)
                .withConnectTimeoutSeconds(5);

        VSphereException e = assertThrows(VSphereException.class, () -> TrileadEsxiShell.connect(nobody));

        assertThat(e.getMessage(), containsString("Could not connect to 127.0.0.1:1 over SSH"));
    }

    // -- commands --

    @Test
    void whatACommandPrintsAndHowItEndsComesBack() throws Exception {
        start("secret", false, false);
        host.failing("power.on", "The operation is not allowed in the current state");

        try (TrileadEsxiShell shell = TrileadEsxiShell.connect(settings(EsxiSshAuth.password("root", "secret")))) {
            ShellResult failed = shell.run("/bin/vim-cmd vmsvc/power.on 1");
            assertThat(failed.getExitCode(), is(1));
            assertThat(failed.getStderr(), containsString("not allowed in the current state"));

            ShellResult printed = shell.run("/bin/vim-cmd vmsvc/power.getstate 1");
            assertThat(printed.getExitCode(), is(0));
            assertThat(printed.getStdout(), containsString("Powered off"));
        }
    }

    @Test
    void aCommandThatDoesNotEndInTimeIsGivenUp() throws Exception {
        start("secret", false, false);

        EsxiSshSettings quick = settings(EsxiSshAuth.password("root", "secret")).withCommandTimeoutSeconds(1);
        try (TrileadEsxiShell shell = TrileadEsxiShell.connect(quick)) {
            VSphereException e = assertThrows(VSphereException.class, () -> shell.run(FakeEsxiSshServer.HANG));

            assertThat(e.getMessage(), containsString("did not end within 1 seconds"));
            // and the connection is still good for the next command
            assertThat(shell.run("true").succeeded(), is(true));
        }
    }

    @Test
    void severalCommandsRunOnOneConnection() throws Exception {
        start("secret", false, false);

        try (TrileadEsxiShell shell = TrileadEsxiShell.connect(settings(EsxiSshAuth.password("root", "secret")))) {
            for (int i = 0; i < 20; i++) {
                assertThat(shell.run("/bin/vim-cmd vmsvc/power.getstate 1").succeeded(), is(true));
            }
        }
    }

    // -- the whole backend over SSH --

    @Test
    void theBackendWorksOverRealSsh() throws Exception {
        start("secret", false, false);

        try (TrileadEsxiShell shell = TrileadEsxiShell.connect(settings(EsxiSshAuth.password("root", "secret")))) {
            VSphereEsxiSsh esxi = new VSphereEsxiSsh(shell);

            VirtualMachine vm = esxi.getVmByName("kube-master");
            assertThat(vm.getConfig().getHardware().getNumCPU(), is(2));
            esxi.startVm("kube-master", 30);
            assertThat(host.vm(1).power, is("Powered on"));
            esxi.takeSnapshot("kube-master", "it's a \"snap\"; $(x)", "d", false);
            assertThat(host.vm(1).snapshots.get(0)[0], is("it's a \"snap\"; $(x)"));
            esxi.destroyVm("kube-master", true);
            assertThat(esxi.countVms(), is(0));
        }
    }

    @Test
    void closingEndsTheSession() throws Exception {
        start("secret", false, false);

        TrileadEsxiShell shell = TrileadEsxiShell.connect(settings(EsxiSshAuth.password("root", "secret")));
        shell.close();

        assertThrows(VSphereException.class, () -> shell.run("true"));
    }
}
