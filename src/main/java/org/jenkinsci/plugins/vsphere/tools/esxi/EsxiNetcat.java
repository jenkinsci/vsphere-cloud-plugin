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

import edu.umd.cs.findbugs.annotations.CheckForNull;
import java.io.PrintStream;
import java.security.SecureRandom;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicLong;
import java.util.regex.Pattern;
import org.jenkinsci.plugins.vsphere.tools.VSphereException;
import org.jenkinsci.plugins.vsphere.tools.VSphereLogger;

/**
 * Copies files from one host to another through a TCP connection that {@code nc} makes from the source to the target:
 * the fastest way, as nothing is encrypted, and so it is only for a network that is trusted. The target listens on a
 * port that it picks at random, among those that nothing uses, and the firewall of both hosts is opened for that port
 * and no other, and for the address of the source, if it is known, and only while the copy goes on (see {@link
 * EsxiTransferFirewall}).
 *
 * <p>Whoever can connect to the port while it is open can send the target a stream to unpack in the folder that it
 * is waiting for, or read nothing: the target does not send anything. That is a window of the time that the copy takes,
 * for those that can reach the target and guess the port, which is why this is not the default. The stream is
 * compressed only, and anything on the way can read it.
 *
 * <p>The listener of {@code nc} has no time that it gives up waiting for a connection after, so it is the controller
 * that looks for the connection, and ends the listener if none comes; and on a copy that stops, the processes on
 * both sides are ended.
 */
public final class EsxiNetcat implements EsxiRelay.Mover {

    private static final Pattern WORD = Pattern.compile("[A-Za-z0-9._-]+");
    private static final Pattern IPV4 = Pattern.compile("\\d{1,3}(\\.\\d{1,3}){3}");
    private static final int FIRST_PORT = 49152;
    private static final int PORTS = 65535 - FIRST_PORT;
    private static final int OUTER_IDLE_SECONDS = 24 * 3600;
    private static final SecureRandom RANDOM = new SecureRandom();

    private final long pollMillis;
    private final int connectSeconds;

    public EsxiNetcat() {
        this(5_000L, 30);
    }

    EsxiNetcat(long pollMillis, int connectSeconds) {
        this.pollMillis = pollMillis;
        this.connectSeconds = connectSeconds;
    }

    @Override
    public String describe() {
        return "directly from host to host by nc, which is not encrypted";
    }

    private static void say(@CheckForNull PrintStream log, String message) {
        if (log != null) {
            VSphereLogger.vsLogger(log, message);
        }
    }

    @Override
    public void move(EsxiRelay.Job job) throws VSphereException {
        final EsxiEndpoint target = job.to.endpoint();
        final EsxiEndpoint source = job.from.endpoint();
        if (target == null) {
            throw new VSphereException("The address of " + job.toLabel + " is not known, so " + job.fromLabel
                    + " cannot be told where" + " to send the files");
        }
        if (!WORD.matcher(target.getHost()).matches()) {
            throw new VSphereException("The address of " + job.toLabel + " has characters that are not allowed");
        }
        for (EsxiShell shell : new EsxiShell[] {job.from, job.to}) {
            if (!shell.run("which nc").succeeded()) {
                throw new VSphereException((shell == job.from ? job.fromLabel : job.toLabel) + " has no nc");
            }
        }
        // the address of the source, to limit the firewall to and to send from, if it is one that can be told
        final String sourceAddress =
                source != null && IPV4.matcher(source.getHost()).matches() ? source.getHost() : null;
        if (sourceAddress == null) {
            say(
                    job.log,
                    "The address of " + job.fromLabel + " is not an IPv4 address, so the port is open to every address"
                            + " while the copy goes on");
        }

        for (int attempt = 1; ; attempt++) {
            try {
                once(job, target, sourceAddress);
                return;
            } catch (PortInUse e) {
                // a port that the host will not bind, though nothing is seen to use it: there are such
                if (attempt >= 5) {
                    throw new VSphereException(e.getMessage());
                }
                say(job.log, e.getMessage() + ": trying another port");
            }
        }
    }

    /** The listener could not take the port. */
    private static final class PortInUse extends VSphereException {
        private static final long serialVersionUID = 1L;

        PortInUse(String message) {
            super(message);
        }
    }

    private void once(EsxiRelay.Job job, EsxiEndpoint target, @CheckForNull String sourceAddress)
            throws VSphereException {
        int port = -1;
        for (int attempt = 0; attempt < 20 && port < 0; attempt++) {
            final int candidate = FIRST_PORT + RANDOM.nextInt(PORTS);
            if (!EsxiTransferFirewall.isTaken(job.to, candidate)
                    && !EsxiTransferFirewall.isTaken(job.from, candidate)) {
                port = candidate;
            }
        }
        if (port < 0) {
            throw new VSphereException(
                    "No port that nothing uses was found on " + job.toLabel + " and " + job.fromLabel);
        }

        // -d: the session of the command has no input, which would end a listener that reads its input as well (this
        // nc does not take -s together with -l either: the listener is on all of the addresses of the host)
        final String listen = "nc -d -l -w " + job.idleSeconds + " " + port;
        final String send = "nc -w " + job.idleSeconds + (sourceAddress == null ? "" : " -s " + sourceAddress) + " "
                + target.getHost() + " " + port;
        final ExecutorService workers = Executors.newFixedThreadPool(2, runnable -> {
            final Thread thread = new Thread(runnable, "esxi-netcat");
            thread.setDaemon(true);
            return thread;
        });
        try (EsxiTransferFirewall.Lease onTarget = EsxiTransferFirewall.open(job.to, port, sourceAddress, job.log);
                EsxiTransferFirewall.Lease onSource = EsxiTransferFirewall.open(job.from, port, null, job.log)) {
            say(
                    job.log,
                    "Sending the files from " + job.fromLabel + " to " + job.toLabel + " through the port " + port
                            + ", not encrypted");
            final Future<ShellResult> listening = workers.submit(
                    () -> job.to.stream(listen + " | " + job.unpackCommand, null, null, OUTER_IDLE_SECONDS));
            try {
                awaitListener(job, port, listening);
                final AtomicLong written = new AtomicLong();
                ShellResult sent = null;
                VSphereException sendFailure = null;
                final Future<ShellResult> sending = workers.submit(
                        () -> job.from.stream(job.packCommand + " | " + send, null, null, OUTER_IDLE_SECONDS));
                try (EsxiStallWatch watch = new EsxiStallWatch(
                        job.to, job.staging, job.idleSeconds, pollMillis, written, job.log, job.toLabel, () -> {
                            end(job.from, send, job.log);
                            end(job.to, listen, job.log);
                        })) {
                    awaitConnection(job, port, sending, listening);
                    try {
                        sent = EsxiRelay.wait(sending, "Sending the files from " + job.fromLabel);
                    } catch (VSphereException e) {
                        sendFailure = e;
                    }
                    if (watch.stalled()) {
                        throw new VSphereException("Nothing was written on " + job.toLabel + " for " + job.idleSeconds
                                + " seconds while " + job.fromLabel + " sent the files: the copy was ended");
                    }
                }
                finish(job, port, listening, sent, sendFailure);
            } finally {
                // a listener that no connection came to is waiting still
                if (!listening.isDone()) {
                    end(job.to, listen, job.log);
                }
                end(job.from, send, job.log);
            }
        } finally {
            workers.shutdownNow();
        }
        job.transferred.set(0);
    }

    /** Waits until the target listens on the port; fails if its listener ends instead. */
    private void awaitListener(EsxiRelay.Job job, int port, Future<ShellResult> listening) throws VSphereException {
        final long deadline = System.nanoTime() + connectSeconds * 1_000_000_000L;
        while (System.nanoTime() < deadline) {
            if (listening.isDone()) {
                final ShellResult ended = EsxiRelay.wait(listening, "Listening on " + job.toLabel);
                if (ended.getStderr().contains("Address already in use")) {
                    throw new PortInUse("The port " + port + " is in use on " + job.toLabel);
                }
                throw new VSphereException(
                        "Listening on port " + port + " on " + job.toLabel + " failed" + EsxiRelay.explanation(ended));
            }
            final ShellResult connections = job.to.run("esxcli network ip connection list");
            for (String line : connections.getStdout().split("\\R")) {
                if (line.contains(":" + port + " ") && line.contains("LISTEN")) {
                    return;
                }
            }
            pause(250);
        }
        throw new VSphereException(
                job.toLabel + " does not listen on port " + port + " after " + connectSeconds + " seconds");
    }

    /** Waits until the source has connected (or one of them has finished: a small copy is over before it is seen). */
    private void awaitConnection(
            EsxiRelay.Job job, int port, Future<ShellResult> sending, Future<ShellResult> listening)
            throws VSphereException {
        final long deadline = System.nanoTime() + connectSeconds * 1_000_000_000L;
        while (System.nanoTime() < deadline) {
            if (sending.isDone() || listening.isDone()) {
                return;
            }
            final ShellResult connections = job.to.run("esxcli network ip connection list");
            for (String line : connections.getStdout().split("\\R")) {
                if (line.contains(":" + port + " ") && line.contains("ESTABLISHED")) {
                    return;
                }
            }
            if (EsxiStallWatch.sizeOf(job.to, job.staging) > 0) {
                return;
            }
            pause(250);
        }
        throw new VSphereException(job.fromLabel + " did not connect to port " + port + " on " + job.toLabel + " in "
                + connectSeconds + " seconds: the firewall or the route between them may not let it");
    }

    private static void pause(long millis) throws VSphereException {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new VSphereException("Interrupted while waiting for a copy");
        }
    }

    private static void finish(
            EsxiRelay.Job job,
            int port,
            Future<ShellResult> listening,
            @CheckForNull ShellResult sent,
            @CheckForNull VSphereException sendFailure)
            throws VSphereException {
        final boolean sendFailed = sendFailure != null
                || (sent != null && (!sent.succeeded() || sent.getStderr().contains("TAR-FAILED")));
        if (sendFailed && listening.isDone()) {
            // the target failed first, perhaps, and the source was cut off by it: that is what to tell
            try {
                final ShellResult heardEarly = listening.get();
                if (!heardEarly.succeeded()) {
                    throw new VSphereException(
                            "Writing the files on " + job.toLabel + " failed" + EsxiRelay.explanation(heardEarly));
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new VSphereException("Interrupted while waiting for a copy");
            } catch (ExecutionException e) {
                // the listener could not be run at all: the sender's failure is the one to tell
            }
        }
        if (sendFailed) {
            // no connection will come to a listener that the sender has given up on: it is ended by the caller
            if (sendFailure != null) {
                throw sendFailure;
            }
            if (sent.getStderr().contains("TAR-FAILED")) {
                throw new VSphereException(
                        "Reading the files on " + job.fromLabel + " failed" + EsxiRelay.explanation(sent));
            }
            final String text = sent.getStderr().replace("TAR-FAILED", "").trim();
            throw new VSphereException("Sending the files from " + job.fromLabel + " to " + job.toLabel
                    + " by nc failed" + (text.isEmpty() ? " (exit code " + sent.getExitCode() + ")" : ": " + text));
        }
        ShellResult heard = null;
        VSphereException listenFailure = null;
        try {
            // the listener ends when the sender has closed the connection
            heard = listening.get(30, java.util.concurrent.TimeUnit.SECONDS);
        } catch (java.util.concurrent.TimeoutException e) {
            listenFailure = new VSphereException(job.toLabel + " still listens on port " + port + " after the copy");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new VSphereException("Interrupted while waiting for a copy");
        } catch (ExecutionException e) {
            listenFailure = e.getCause() instanceof VSphereException
                    ? (VSphereException) e.getCause()
                    : new VSphereException("Writing the files on " + job.toLabel + " failed: " + e.getMessage(), e);
        }
        if (sendFailure != null && listenFailure != null) {
            throw sendFailure;
        }
        if (sendFailure != null) {
            throw sendFailure;
        }
        if (sent != null && sent.getStderr().contains("TAR-FAILED")) {
            throw new VSphereException(
                    "Reading the files on " + job.fromLabel + " failed" + EsxiRelay.explanation(sent));
        }
        if (sent != null && !sent.succeeded()) {
            final String text = sent.getStderr().replace("TAR-FAILED", "").trim();
            throw new VSphereException("Sending the files from " + job.fromLabel + " to " + job.toLabel
                    + " by nc failed" + (text.isEmpty() ? " (exit code " + sent.getExitCode() + ")" : ": " + text));
        }
        if (heard != null && !heard.succeeded()) {
            throw new VSphereException(
                    "Writing the files on " + job.toLabel + " failed" + EsxiRelay.explanation(heard));
        }
        if (listenFailure != null) {
            throw listenFailure;
        }
    }

    /** Ends the processes on the host that were started by the command (told by their command lines). */
    private static void end(EsxiShell shell, String command, @CheckForNull PrintStream log) {
        try {
            final ShellResult listed = shell.run("ps -c | grep " + ShellQuote.quote(command) + " | grep -v grep");
            final StringBuilder pids = new StringBuilder();
            for (String line : listed.getStdout().split("\\R")) {
                final String[] columns = line.trim().split("\\s+");
                if (columns.length > 0 && columns[0].matches("\\d+")) {
                    pids.append(' ').append(columns[0]);
                }
            }
            if (pids.length() > 0) {
                shell.run("kill" + pids);
                say(log, "Ended the processes of the copy that were left:" + pids);
            }
        } catch (VSphereException e) {
            say(log, "Could not end the processes of the copy: " + e.getMessage());
        }
    }
}
