package org.jenkinsci.plugins.vsphere.tools.esxi;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.PublicKey;
import java.util.List;
import org.apache.sshd.common.config.keys.KeyUtils;
import org.apache.sshd.common.keyprovider.KeyPairProvider;
import org.apache.sshd.server.SshServer;
import org.apache.sshd.server.auth.keyboard.DefaultKeyboardInteractiveAuthenticator;
import org.apache.sshd.server.auth.keyboard.UserAuthKeyboardInteractiveFactory;
import org.apache.sshd.server.command.AbstractCommandSupport;
import org.jenkinsci.plugins.vsphere.tools.VSphereException;

/**
 * An SSH server on a free local port in front of a {@link FakeEsxiHost}: it runs each command that a client asks
 * for on the simulated host. It takes the one user it is told of, with a password and/or a public key, so that
 * the real SSH code can be tested end to end, with either kind of credential.
 */
final class FakeEsxiSshServer implements AutoCloseable {

    static final String USER = "root";

    /** How many times a client tried to log in (with a password or a key), to see that none did. */
    final java.util.concurrent.atomic.AtomicInteger authAttempts = new java.util.concurrent.atomic.AtomicInteger();

    private final SshServer server;
    private final KeyPair hostKey;

    /** A command that makes the simulated host hang, for the tests of time outs. */
    static final String HANG = "sleep-for-a-long-time";

    /**
     * @param password what the user logs in with, or null for no password login
     * @param authorizedKey the key the user may log in with, or null for no key login
     * @param keyboardInteractiveOnly if true, the server takes a password only when asked for in the way of the
     *     keyboard-interactive method, as some hosts do
     */
    FakeEsxiSshServer(FakeEsxiHost host, String password, PublicKey authorizedKey, boolean keyboardInteractiveOnly)
            throws Exception {
        hostKey = TestKeys.rsa();
        server = SshServer.setUpDefaultServer();
        server.setHost("127.0.0.1");
        server.setPort(0);
        server.setKeyPairProvider(KeyPairProvider.wrap(hostKey));
        server.setPasswordAuthenticator(
                password == null
                        ? null
                        : (user, given, session) -> {
                            authAttempts.incrementAndGet();
                            return USER.equals(user) && password.equals(given);
                        });
        server.setPublickeyAuthenticator(
                authorizedKey == null
                        ? null
                        : (user, key, session) -> {
                            authAttempts.incrementAndGet();
                            return USER.equals(user) && KeyUtils.compareKeys(authorizedKey, key);
                        });
        if (keyboardInteractiveOnly) {
            server.setKeyboardInteractiveAuthenticator(DefaultKeyboardInteractiveAuthenticator.INSTANCE);
            server.setUserAuthFactories(List.of(UserAuthKeyboardInteractiveFactory.INSTANCE));
        }
        server.setCommandFactory((channel, command) -> new AbstractCommandSupport(command, null) {
            @Override
            public void run() {
                try {
                    if (getCommand().equals(HANG)) {
                        Thread.sleep(30_000);
                    }
                    final ShellResult result = host.run(getCommand());
                    getOutputStream().write(result.getStdout().getBytes(StandardCharsets.UTF_8));
                    getOutputStream().flush();
                    getErrorStream().write(result.getStderr().getBytes(StandardCharsets.UTF_8));
                    getErrorStream().flush();
                    onExit(result.getExitCode());
                } catch (InterruptedException | IOException | VSphereException | RuntimeException | AssertionError e) {
                    onExit(255, String.valueOf(e.getMessage()));
                }
            }
        });
        server.start();
    }

    int port() {
        return server.getPort();
    }

    /** The fingerprint of the host key of the server, as OpenSSH shows it. */
    String hostKeySha256() {
        return KeyUtils.getFingerPrint(hostKey.getPublic());
    }

    @Override
    public void close() throws IOException {
        server.stop(true);
    }
}
