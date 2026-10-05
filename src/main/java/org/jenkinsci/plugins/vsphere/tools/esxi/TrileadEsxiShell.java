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

import com.trilead.ssh2.ChannelCondition;
import com.trilead.ssh2.Connection;
import com.trilead.ssh2.Session;
import com.trilead.ssh2.StreamGobbler;
import edu.umd.cs.findbugs.annotations.CheckForNull;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
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
        return connect(settings, settings.getHostKeyStore());
    }

    private static TrileadEsxiShell connect(EsxiSshSettings settings, EsxiHostKeyStore hostKeyStore)
            throws VSphereException {
        final Connection connection = new Connection(settings.getHost(), settings.getPort());
        final EsxiHostKeyVerifier verifier =
                new EsxiHostKeyVerifier(settings.getHostKeyFingerprint(), settings.getHostKeyPolicy(), hostKeyStore);
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
            return verifier.getRejection();
        }
        return "Could not connect to " + settings.getHost() + ":" + settings.getPort() + " over SSH: "
                + rootMessage(cause);
    }

    /**
     * Finds out which host key a host presents, without trusting it and without sending any credentials: the
     * connection is ended as soon as the host key is known. This is how the fingerprint to expect can be learned.
     *
     * @throws VSphereException if the host could not be reached
     */
    public static EsxiHostKeyInfo queryHostKey(String host, int port, int timeoutSeconds) throws VSphereException {
        final Connection connection = new Connection(host, port <= 0 ? EsxiSshSettings.DEFAULT_PORT : port);
        final AtomicReference<EsxiHostKeyInfo> seen = new AtomicReference<>();
        final int timeoutMillis =
                (timeoutSeconds > 0 ? timeoutSeconds : EsxiSshSettings.DEFAULT_CONNECT_TIMEOUT_SECONDS) * 1000;
        try {
            connection.connect(
                    (hostname, p, algorithm, key) -> {
                        seen.set(new EsxiHostKeyInfo(algorithm, key));
                        return true;
                    },
                    timeoutMillis,
                    timeoutMillis);
        } catch (IOException e) {
            if (seen.get() == null) {
                throw new VSphereException(
                        "Could not connect to " + host + ":" + port + " over SSH: " + rootMessage(e), e);
            }
            // the host key was seen, which is all that is wanted
        } finally {
            connection.close();
        }
        return seen.get();
    }

    /**
     * Tests the connection to a host as it is configured, without any effect (nothing is remembered, not even
     * a first host key): which host key does it present, would that be trusted, and does the login work.
     * Never throws; what went wrong is in the result.
     */
    public static EsxiConnectionTestResult test(EsxiSshSettings settings) {
        final EsxiHostKeyInfo hostKey;
        try {
            hostKey = queryHostKey(settings.getHost(), settings.getPort(), settings.getConnectTimeoutSeconds());
        } catch (VSphereException e) {
            return new EsxiConnectionTestResult(null, false, false, null, e.getMessage());
        }

        final EsxiHostKeyStore readOnlyStore = EsxiHostKeyStore.readOnly(settings.getHostKeyStore());
        final EsxiHostKeyVerifier verifier =
                new EsxiHostKeyVerifier(settings.getHostKeyFingerprint(), settings.getHostKeyPolicy(), readOnlyStore);
        final boolean trusted = verifier.verifyServerHostKey(
                settings.getHost(), settings.getPort(), hostKey.getAlgorithm(), hostKey.getKey());
        if (!trusted) {
            return new EsxiConnectionTestResult(hostKey, false, false, null, verifier.getRejection());
        }
        final String given = settings.getHostKeyFingerprint();
        final boolean willBeRemembered = given == null || given.trim().isEmpty()
                ? settings.getHostKeyPolicy() == EsxiHostKeyPolicy.TRUST_FIRST_USE
                        && settings.getHostKeyStore().get(settings.getHost(), settings.getPort()) == null
                : false;
        final String keyNote = "The host presents a " + hostKey
                + (willBeRemembered
                        ? ", which is going to be remembered and then required, as it is the first one seen."
                        : ".");

        try (TrileadEsxiShell shell = connect(settings, readOnlyStore)) {
            return new EsxiConnectionTestResult(
                    hostKey, true, true, serverVersion(shell), "Logged in to " + settings + ". " + keyNote);
        } catch (VSphereException e) {
            return new EsxiConnectionTestResult(hostKey, true, false, null, e.getMessage() + ". " + keyNote);
        }
    }

    /** What the host calls itself ({@code vmware -v}), if it can be asked. */
    private static @CheckForNull String serverVersion(EsxiShell shell) {
        try {
            final ShellResult result = shell.run("vmware -v");
            final String line = result.getStdout().trim().split("\\R")[0].trim();
            return result.succeeded() && !line.isEmpty() ? line : null;
        } catch (VSphereException e) {
            return null;
        }
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

    @Override
    public ShellResult stream(
            String command, @CheckForNull InputStream stdin, @CheckForNull OutputStream stdout, int idleTimeoutSeconds)
            throws VSphereException {
        final Session session;
        try {
            session = connection.openSession();
        } catch (IOException | IllegalStateException e) {
            throw new VSphereException("Could not open an SSH session to " + settings + ": " + rootMessage(e), e);
        }
        final AtomicLong lastMoved = new AtomicLong(System.nanoTime());
        final AtomicBoolean idle = new AtomicBoolean();
        final AtomicReference<Throwable> pumpFailure = new AtomicReference<>();
        final long idleNanos = TimeUnit.SECONDS.toNanos(Math.max(1, idleTimeoutSeconds));
        final Thread watchdog = new Thread(
                () -> {
                    try {
                        while (!Thread.currentThread().isInterrupted()) {
                            Thread.sleep(250);
                            if (System.nanoTime() - lastMoved.get() > idleNanos) {
                                idle.set(true);
                                session.close();
                                return;
                            }
                        }
                    } catch (InterruptedException e) {
                        // the command is over
                    }
                },
                "esxi-stream-watchdog");
        watchdog.setDaemon(true);
        Thread feeder = null;
        try {
            session.execCommand(command);
            final InputStream errors = new StreamGobbler(session.getStderr());
            watchdog.start();
            if (stdin != null) {
                feeder = new Thread(
                        () -> {
                            try (OutputStream toCommand = session.getStdin()) {
                                final byte[] buffer = new byte[64 * 1024];
                                int n;
                                while ((n = stdin.read(buffer)) != -1) {
                                    toCommand.write(buffer, 0, n);
                                    lastMoved.set(System.nanoTime());
                                }
                            } catch (IOException e) {
                                pumpFailure.compareAndSet(null, e);
                            }
                        },
                        "esxi-stream-feeder");
                feeder.setDaemon(true);
                feeder.start();
            } else {
                session.getStdin().close();
            }
            final InputStream fromCommand = session.getStdout();
            final byte[] buffer = new byte[64 * 1024];
            int n;
            while ((n = fromCommand.read(buffer)) != -1) {
                if (stdout != null) {
                    stdout.write(buffer, 0, n);
                }
                lastMoved.set(System.nanoTime());
            }
            if (stdout != null) {
                stdout.flush();
            }
            if (idle.get()) {
                throw new IOException("idle");
            }
            session.waitForCondition(
                    ChannelCondition.EXIT_STATUS | ChannelCondition.EXIT_SIGNAL | ChannelCondition.CLOSED,
                    EXIT_STATUS_WAIT_MILLIS);
            final String err = read(errors);
            final Integer status = session.getExitStatus();
            if (pumpFailure.get() != null && (status == null || status == 0)) {
                throw new IOException(pumpFailure.get());
            }
            return new ShellResult(status == null ? -1 : status, "", err);
        } catch (IOException e) {
            if (idle.get()) {
                throw new VSphereException("Nothing moved for " + idleTimeoutSeconds + " seconds in a command on "
                        + settings + ": " + abbreviate(command));
            }
            throw new VSphereException("Streaming data in a command on " + settings + " failed: " + rootMessage(e), e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new VSphereException("Interrupted while streaming data in a command on " + settings, e);
        } finally {
            watchdog.interrupt();
            session.close();
            if (feeder != null) {
                feeder.interrupt();
            }
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
