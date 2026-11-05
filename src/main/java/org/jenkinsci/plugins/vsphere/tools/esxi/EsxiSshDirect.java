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
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;
import java.util.regex.Pattern;
import org.jenkinsci.plugins.vsphere.tools.VSphereException;
import org.jenkinsci.plugins.vsphere.tools.VSphereLogger;

/**
 * Copies files from one host to another over SSH, from the one to the other as they are, with the controller doing
 * nothing but waiting: {@code tar} on the source writes the stream, which {@code ssh} on the source sends to the
 * target, where it is unpacked. The bytes are encrypted, and travel once.
 *
 * <p>The hosts need not trust one another for good, nor does anything stay behind on them. For one copy:
 *
 * <ul>
 *   <li>the source makes a key pair of its own for the copy (an ECDSA one, as a host in FIPS mode has no other that
 *       it could make) in a folder in {@code /tmp}, that is removed after;
 *   <li>the target is told, by the controller, over the session that the controller has with it, to accept that key
 *       (as the user that the controller logs in as), and only for one command: unpacking the stream in the folder
 *       that the files are going to, with no terminal, no forwarding and nothing else ({@code restrict,command=}).
 *       The line is removed after, and so are the file and the folder that were made for it, if there were none;
 *   <li>the source is given the host key of the target, which the controller read over the session that it trusts, so
 *       the copy cannot be sent to a host that is not the target;
 *   <li>the firewall of the source lets it open SSH connections (the {@code sshClient} ruleset) for as long as copies
 *       need it, if it does not already.
 * </ul>
 *
 * <p>The source reaches the target at the address that the controller does.
 */
public final class EsxiSshDirect implements EsxiRelay.Mover {

    private static final String KEYGEN = "/usr/lib/vmware/openssh/bin/ssh-keygen";
    private static final Pattern WORD = Pattern.compile("[A-Za-z0-9._-]+");
    private static final Pattern ADDRESS = Pattern.compile("[A-Za-z0-9._:-]+");
    private static final long WATCH_POLL_MILLIS = 5_000L;
    private static final int OUTER_IDLE_SECONDS = 24 * 3600;

    /** The users of the key file of one user of one target, who share it. */
    private static final class Keys {
        int users;
        boolean madeDirectory;
        boolean madeFile;
    }

    private static final Map<String, Keys> KEYS = new HashMap<>();

    private final long pollMillis;

    public EsxiSshDirect() {
        this(WATCH_POLL_MILLIS);
    }

    EsxiSshDirect(long pollMillis) {
        this.pollMillis = pollMillis;
    }

    @Override
    public String describe() {
        return "directly from host to host over SSH";
    }

    private static void say(@CheckForNull PrintStream log, String message) {
        if (log != null) {
            VSphereLogger.vsLogger(log, message);
        }
    }

    @Override
    public void move(EsxiRelay.Job job) throws VSphereException {
        final EsxiEndpoint target = job.to.endpoint();
        if (target == null) {
            throw new VSphereException("The address of " + job.toLabel + " is not known, so " + job.fromLabel
                    + " cannot be told where" + " to send the files");
        }
        if (!WORD.matcher(target.getUser()).matches()
                || !ADDRESS.matcher(target.getHost()).matches()) {
            throw new VSphereException(
                    "The user or the address of " + job.toLabel + " has characters that are not allowed in a copy");
        }
        if (job.staging.contains("\"") || job.staging.contains("\\") || job.staging.contains("\n")) {
            throw new VSphereException(job.staging + " has characters that are not allowed in a copy");
        }
        if (!job.from.run("which ssh").succeeded()) {
            throw new VSphereException(job.fromLabel + " has no ssh client to send the files with");
        }
        final EsxiDatastoreFiles source = new EsxiDatastoreFiles(job.from);
        final EsxiDatastoreFiles destination = new EsxiDatastoreFiles(job.to);
        String keygen = KEYGEN;
        if (!source.exists(KEYGEN)) {
            final ShellResult found = job.from.run("which ssh-keygen");
            if (!found.succeeded() || found.getStdout().trim().isEmpty()) {
                throw new VSphereException(job.fromLabel + " has no ssh-keygen to make the key of the copy with");
            }
            keygen = found.getStdout().trim();
        }

        // what the source is to know about the target: its key, as the target says it over the trusted session
        final String hostKey = hostKeyOf(destination, job.toLabel);
        final String[] hostKeyFields = hostKey.split("\\s+");

        final String id = "jenkins-xfer-" + UUID.randomUUID().toString().substring(0, 8);
        final String directory = "/tmp/" + id;
        final String keyFile = directory + "/key";
        final String knownHosts = directory + "/known";
        final String keysDirectory = "/etc/ssh/keys-" + target.getUser();
        final String keysFile = keysDirectory + "/authorized_keys";
        boolean keyInstalled = false;
        EsxiFirewallRuleset.Lease firewall = null;
        try {
            firewall = EsxiFirewallRuleset.acquire(job.from, "sshClient", job.log);

            job.from.run("mkdir " + directory).stdoutOrThrow("Making " + directory);
            job.from.run("chmod 700 " + directory).stdoutOrThrow("Making " + directory + " private");
            job.from
                    .run(ShellQuote.quote(keygen) + " -q -t ecdsa -b 256 -N '' -C " + id + " -f "
                            + ShellQuote.quote(keyFile))
                    .stdoutOrThrow("Making the key of the copy on " + job.fromLabel);
            final String[] publicKey = source.read(keyFile + ".pub").trim().split("\\s+");
            if (publicKey.length < 2) {
                throw new VSphereException("The key made on " + job.fromLabel + " is not one that can be told");
            }
            final String knownHost =
                    target.getPort() == 22 ? target.getHost() : "[" + target.getHost() + "]:" + target.getPort();
            source.write(knownHosts, knownHost + " " + hostKeyFields[0] + " " + hostKeyFields[1] + "\n");

            installKey(
                    destination,
                    target,
                    keysDirectory,
                    keysFile,
                    "restrict,command=\"" + job.unpackCommand + "\" " + publicKey[0] + " " + publicKey[1] + " " + id);
            keyInstalled = true;

            final String algorithms =
                    hostKeyFields[0].equals("ssh-rsa") ? "rsa-sha2-512,rsa-sha2-256,ssh-rsa" : hostKeyFields[0];
            final String send = "ssh -F /dev/null -T -i " + ShellQuote.quote(keyFile)
                    + " -o IdentitiesOnly=yes -o BatchMode=yes -o PasswordAuthentication=no"
                    + " -o StrictHostKeyChecking=yes -o UserKnownHostsFile=" + ShellQuote.quote(knownHosts)
                    + " -o GlobalKnownHostsFile=/dev/null -o HostKeyAlgorithms=" + algorithms
                    + " -o ConnectTimeout=20 -o ServerAliveInterval=15 -o ServerAliveCountMax=4 -o LogLevel=ERROR"
                    + " -p " + target.getPort() + " " + target.getUser() + "@" + target.getHost() + " true";
            final String command = job.packCommand + " | " + send;

            say(job.log, "Sending the files from " + job.fromLabel + " to " + target + " over SSH");
            final AtomicLong written = new AtomicLong();
            ShellResult result;
            try (EsxiStallWatch watch = new EsxiStallWatch(
                    job.to,
                    job.staging,
                    job.idleSeconds,
                    pollMillis,
                    written,
                    job.log,
                    job.toLabel,
                    () -> endCopy(job.from, id, job.log))) {
                try {
                    result = job.from.stream(command, null, null, OUTER_IDLE_SECONDS);
                } catch (VSphereException e) {
                    if (watch.stalled()) {
                        throw new VSphereException("Nothing was written on " + job.toLabel + " for " + job.idleSeconds
                                + " seconds while " + job.fromLabel + " sent the files: the copy was ended");
                    }
                    throw e;
                }
                if (watch.stalled()) {
                    throw new VSphereException("Nothing was written on " + job.toLabel + " for " + job.idleSeconds
                            + " seconds while " + job.fromLabel + " sent the files: the copy was ended");
                }
            }
            job.transferred.set(0);
            final String errors = result.getStderr().replace("TAR-FAILED", "").trim();
            if (result.getStderr().contains("TAR-FAILED")) {
                throw new VSphereException("Reading the files on " + job.fromLabel + " failed" + described(result));
            }
            if (!result.succeeded()) {
                throw new VSphereException("Sending the files from " + job.fromLabel + " to " + job.toLabel
                        + " over SSH failed"
                        + (result.getExitCode() == 255
                                ? ": " + (errors.isEmpty() ? "the connection failed" : errors)
                                : described(result)));
            }
        } finally {
            if (keyInstalled) {
                try {
                    removeKey(destination, target, keysDirectory, keysFile, id);
                } catch (VSphereException e) {
                    say(
                            job.log,
                            "Could not take the key of the copy out of " + keysFile + " on " + job.toLabel + ": "
                                    + e.getMessage() + "; the line that ends in " + id + " is to be removed");
                }
            }
            try {
                job.from.run("rm -rf " + directory);
            } catch (VSphereException e) {
                say(job.log, "Could not remove " + directory + " on " + job.fromLabel + ": " + e.getMessage());
            }
            if (firewall != null) {
                firewall.close();
            }
        }
    }

    private static String described(ShellResult result) {
        return EsxiRelay.explanation(result);
    }

    /** The host key of the host, as it has it: the ECDSA one, or else the RSA or the Ed25519. */
    private static String hostKeyOf(EsxiDatastoreFiles host, String label) throws VSphereException {
        for (String type : new String[] {"ecdsa", "rsa", "ed25519"}) {
            final String path = "/etc/ssh/ssh_host_" + type + "_key.pub";
            final ShellResult read = host.run("cat " + path);
            if (read.succeeded() && read.getStdout().trim().split("\\s+").length >= 2) {
                return read.getStdout().trim();
            }
        }
        throw new VSphereException(label + " has no host key to tell it by");
    }

    /** Ends what the source is sending: the processes that have the copy's name in their command. */
    private static void endCopy(EsxiShell source, String id, @CheckForNull PrintStream log) {
        try {
            final ShellResult listed = source.run("ps -c | grep " + id + " | grep -v grep");
            final StringBuilder pids = new StringBuilder();
            for (String line : listed.getStdout().split("\\R")) {
                final String[] columns = line.trim().split("\\s+");
                if (columns.length > 0 && columns[0].matches("\\d+")) {
                    pids.append(' ').append(columns[0]);
                }
            }
            if (pids.length() > 0) {
                source.run("kill" + pids);
                say(log, "Ended the processes of the copy that are stuck:" + pids);
            }
        } catch (VSphereException e) {
            say(log, "Could not end the stuck copy: " + e.getMessage());
        }
    }

    // -- the key on the target --

    private static String keysKey(EsxiEndpoint target) {
        return target.key() + "/" + target.getUser();
    }

    /** Adds the line to the keys that the user is accepted with, making the file (and its folder) if there is none. */
    private static void installKey(
            EsxiDatastoreFiles host, EsxiEndpoint target, String directory, String file, String line)
            throws VSphereException {
        final Keys keys;
        synchronized (KEYS) {
            keys = KEYS.computeIfAbsent(keysKey(target), k -> new Keys());
        }
        synchronized (keys) {
            if (keys.users == 0) {
                keys.madeDirectory = !host.exists(directory);
                keys.madeFile = !host.exists(file);
            }
            if (keys.madeDirectory && keys.users == 0) {
                host.run("mkdir " + ShellQuote.quote(directory)).stdoutOrThrow("Making " + directory);
                host.run("chmod 755 " + ShellQuote.quote(directory)).stdoutOrThrow("Setting the mode of " + directory);
            }
            final String old = keys.madeFile && keys.users == 0 ? "" : host.read(file);
            host.write(file, old + (old.isEmpty() || old.endsWith("\n") ? "" : "\n") + line + "\n");
            if (keys.madeFile && keys.users == 0 && !target.getUser().equals("root")) {
                host.run("chmod 644 " + ShellQuote.quote(file)).stdoutOrThrow("Setting the mode of " + file);
            }
            if (!target.getUser().equals("root")) {
                // a user other than root is read from as that user, so it has to be able to
                host.run("chmod a+rx " + ShellQuote.quote(directory));
                host.run("chmod a+r " + ShellQuote.quote(file));
            }
            keys.users++;
        }
    }

    /** Takes the line of the copy out, and the file and its folder if they were made for the copies. */
    private static void removeKey(
            EsxiDatastoreFiles host, EsxiEndpoint target, String directory, String file, String id)
            throws VSphereException {
        final Keys keys;
        synchronized (KEYS) {
            keys = KEYS.get(keysKey(target));
        }
        if (keys == null) {
            return;
        }
        synchronized (keys) {
            keys.users--;
            final StringBuilder kept = new StringBuilder();
            if (host.exists(file)) {
                for (String line : host.read(file).split("\\R")) {
                    if (!line.trim().isEmpty() && !line.contains(id)) {
                        kept.append(line).append('\n');
                    }
                }
            }
            if (keys.users == 0 && keys.madeFile && kept.length() == 0) {
                host.run("rm -f " + ShellQuote.quote(file));
                if (keys.madeDirectory) {
                    host.run("rmdir " + ShellQuote.quote(directory));
                }
            } else {
                host.write(file, kept.toString());
            }
        }
    }
}
