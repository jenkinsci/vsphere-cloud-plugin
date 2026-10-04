package org.jenkinsci.plugins.vsphere.tools.esxi;

import com.trilead.ssh2.ChannelCondition;
import com.trilead.ssh2.Connection;
import com.trilead.ssh2.Session;
import com.trilead.ssh2.StreamGobbler;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import org.jenkinsci.plugins.vsphere.tools.VSphereException;

/**
 * An {@link EsxiShell} over SSH, using the SSH client that Jenkins itself provides ("trilead"). A command runs
 * in a session of its own on the one connection.
 */
public final class TrileadEsxiShell implements EsxiShell {

    /** Output beyond this is dropped; nothing the backend runs prints more than a little. */
    private static final int MAX_OUTPUT_BYTES = 16 * 1024 * 1024;

    private static final long EXIT_STATUS_WAIT_MILLIS = 10_000L;

    private final EsxiSshSettings settings;
    private final Connection connection;

    private TrileadEsxiShell(EsxiSshSettings settings, Connection connection) {
        this.settings = settings;
        this.connection = connection;
    }

    /**
     * Connects to the host, checks its host key and logs in.
     *
     * @throws VSphereException if that did not work out, saying why: the host cannot be reached, its host key is
     *     not trusted (with what it presented), or the credentials were not accepted
     */
    public static TrileadEsxiShell connect(EsxiSshSettings settings) throws VSphereException {
        final Connection connection = new Connection(settings.getHost(), settings.getPort());
        final EsxiHostKeyVerifier verifier =
                new EsxiHostKeyVerifier(settings.getHostKeyFingerprint(), settings.isAcceptAnyHostKey());
        final int timeoutMillis = settings.getConnectTimeoutSeconds() * 1000;
        try {
            connection.connect(verifier, timeoutMillis, timeoutMillis);
        } catch (IOException e) {
            connection.close();
            throw new VSphereException(describeConnectFailure(settings, verifier, e), e);
        }
        try {
            settings.getAuth().authenticate(connection);
            if (!connection.isAuthenticationComplete()) {
                throw new VSphereException("Logging in to " + settings + " did not complete");
            }
        } catch (IOException e) {
            connection.close();
            throw new VSphereException("Logging in to " + settings + " failed: " + e.getMessage(), e);
        } catch (VSphereException e) {
            connection.close();
            throw new VSphereException(e.getMessage() + " (" + settings + ")", e);
        }
        return new TrileadEsxiShell(settings, connection);
    }

    private static String describeConnectFailure(
            EsxiSshSettings settings, EsxiHostKeyVerifier verifier, IOException cause) {
        if (verifier.wasRejected()) {
            final String expected = settings.getHostKeyFingerprint();
            return "The host key presented by " + settings.getHost() + " is not trusted: its "
                    + verifier.getPresentedAlgorithm() + " key has the fingerprint "
                    + verifier.getPresentedFingerprint()
                    + (expected == null || expected.trim().isEmpty()
                            ? ". Put that fingerprint in the settings to trust it, after checking that it is the"
                                    + " host's, or accept any host key (which is not secure)."
                            : ", not the " + expected + " that is expected.");
        }
        return "Could not connect to " + settings.getHost() + ":" + settings.getPort() + " over SSH: "
                + rootMessage(cause);
    }

    private static String rootMessage(Throwable t) {
        Throwable root = t;
        while (root.getCause() != null && root.getCause() != root) {
            root = root.getCause();
        }
        return root.getMessage() != null ? root.getMessage() : root.toString();
    }

    @Override
    public ShellResult run(String command) throws VSphereException {
        final Session session;
        try {
            session = connection.openSession();
        } catch (IOException | IllegalStateException e) {
            // the latter is what is thrown when the connection has been closed (or never was open)
            throw new VSphereException("Could not open an SSH session to " + settings + ": " + rootMessage(e), e);
        }
        try {
            session.execCommand(command);
            // The streams are drained in the background, so that a command printing a lot cannot stall
            final InputStream stdout = new StreamGobbler(session.getStdout());
            final InputStream stderr = new StreamGobbler(session.getStderr());
            final long timeoutMillis = settings.getCommandTimeoutSeconds() * 1000L;
            final int ended = session.waitForCondition(ChannelCondition.EOF | ChannelCondition.CLOSED, timeoutMillis);
            if ((ended & ChannelCondition.TIMEOUT) != 0) {
                throw new VSphereException("The command did not end within " + settings.getCommandTimeoutSeconds()
                        + " seconds on " + settings + ": " + abbreviate(command));
            }
            final String out = read(stdout);
            final String err = read(stderr);
            session.waitForCondition(
                    ChannelCondition.EXIT_STATUS | ChannelCondition.EXIT_SIGNAL | ChannelCondition.CLOSED,
                    EXIT_STATUS_WAIT_MILLIS);
            final Integer status = session.getExitStatus();
            // No exit status means that the command was killed or the connection went away: not a success
            return new ShellResult(status == null ? -1 : status, out, err);
        } catch (IOException e) {
            throw new VSphereException("Running a command on " + settings + " failed: " + rootMessage(e), e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new VSphereException("Interrupted while running a command on " + settings, e);
        } finally {
            session.close();
        }
    }

    private static String read(InputStream in) throws IOException {
        final ByteArrayOutputStream out = new ByteArrayOutputStream();
        final byte[] buffer = new byte[8192];
        boolean truncated = false;
        int n;
        while ((n = in.read(buffer)) != -1) {
            final int room = MAX_OUTPUT_BYTES - out.size();
            if (room > 0) {
                out.write(buffer, 0, Math.min(n, room));
            }
            truncated |= n > room;
        }
        return out.toString(StandardCharsets.UTF_8) + (truncated ? "\n[output truncated]\n" : "");
    }

    private static String abbreviate(String command) {
        return command.length() <= 120 ? command : command.substring(0, 117) + "...";
    }

    @Override
    public void close() {
        connection.close();
    }
}
