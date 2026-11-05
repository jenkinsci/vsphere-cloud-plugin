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
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.jenkinsci.plugins.vsphere.tools.VSphereException;
import org.jenkinsci.plugins.vsphere.tools.VSphereLogger;

/**
 * The firewall of a host, opened for the ports that copies by {@code nc} are using, and only while they are. The
 * firewall of a host drops what no ruleset allows, in either direction, so the target needs a rule for the port that
 * it listens on, and the source one for the port that it connects to.
 *
 * <p>All copies to and from a host share one ruleset of our own, {@value #RULESET}, defined in a file of its own
 * ({@value #FILE}), with a pair of rules (inbound and outbound) for each port that is in use. It is changed when a
 * copy begins and when it ends, and when the last of them ends it is turned off, its file removed, and the addresses
 * it was limited to taken out (a host keeps those, in its configuration, for a ruleset of that name even after the
 * file is gone: which is why the name is the same each time, and not one per copy). The state of the host is probed
 * when the first copy begins, so that what is left of an earlier run that was stopped is not mistaken for something
 * to keep, and rules of a ruleset that is not ours are not touched.
 *
 * <p>When the address that a copy comes from is known, the ruleset is limited to the addresses of the copies that are
 * going on; if the address of any is not known, it is not limited.
 */
final class EsxiTransferFirewall {

    static final String RULESET = "jenkinsXfer";
    static final String FILE = "/etc/vmware/firewall/jenkins-xfer.xml";

    private static final Pattern PORT_IN_FILE = Pattern.compile("<port>(\\d+)</port>");

    /** What a copy holds while it needs its port open; closing it lets go. Does not throw. */
    static final class Lease implements AutoCloseable {
        private final Host host;
        private final EsxiShell shell;
        private final int port;
        private final PrintStream log;
        private boolean closed;

        private Lease(Host host, EsxiShell shell, int port, @CheckForNull PrintStream log) {
            this.host = host;
            this.shell = shell;
            this.port = port;
            this.log = log;
        }

        @Override
        public void close() {
            synchronized (host) {
                if (closed) {
                    return;
                }
                closed = true;
                host.ports.remove(port);
                try {
                    host.apply(shell, log);
                } catch (VSphereException e) {
                    say(
                            log,
                            "Could not close the port " + port + " in the firewall of the host: " + e.getMessage()
                                    + " (the ruleset " + RULESET + " is ours to remove)");
                }
            }
        }
    }

    /** What a host has of ours: the ports that are open, and for whom. */
    private static final class Host {
        /** Port to the address that it is open to, or an empty string if to all. */
        final Map<Integer, String> ports = new HashMap<>();
        /** The addresses that were added to the ruleset by us, to take out again. */
        final Set<String> added = new HashSet<>();

        boolean on;

        /** Brings the firewall of the host to what {@link #ports} says. */
        void apply(EsxiShell shell, @CheckForNull PrintStream log) throws VSphereException {
            if (ports.isEmpty()) {
                if (on) {
                    shell.run("esxcli network firewall ruleset set -r " + RULESET + " -e false");
                    unlimit(shell);
                    shell.run("rm -f " + FILE);
                    shell.run("esxcli network firewall refresh");
                    on = false;
                    say(log, "Closed the ports of the copies in the firewall of the host");
                }
                return;
            }
            final StringBuilder xml = new StringBuilder("<ConfigRoot><service id=\"0299\"><id>" + RULESET + "</id>");
            int rule = 0;
            for (Integer port : new java.util.TreeSet<>(ports.keySet())) {
                for (String direction : new String[] {"inbound", "outbound"}) {
                    xml.append(String.format(
                            "<rule id=\"%04d\"><direction>%s</direction><protocol>tcp</protocol>"
                                    + "<porttype>dst</porttype><port>%d</port></rule>",
                            rule++, direction, port));
                }
            }
            xml.append("<enabled>true</enabled><required>false</required></service></ConfigRoot>\n");
            new EsxiDatastoreFiles(shell).write(FILE, xml.toString());
            shell.run("esxcli network firewall refresh").stdoutOrThrow("Reloading the rules of the firewall");
            shell.run("esxcli network firewall ruleset set -r " + RULESET + " -e true")
                    .stdoutOrThrow("Turning the ruleset " + RULESET + " on");
            on = true;

            // limited to those that the copies come from, if all of them are known
            final Set<String> wanted = new LinkedHashSet<>(ports.values());
            if (wanted.contains("")) {
                unlimit(shell);
            } else {
                // the list of addresses can be changed only while the ruleset is not open to all
                shell.run("esxcli network firewall ruleset set -r " + RULESET + " --allowed-all false")
                        .stdoutOrThrow("Limiting the ruleset " + RULESET);
                for (String ip : wanted) {
                    if (!added.contains(ip)) {
                        shell.run("esxcli network firewall ruleset allowedip add -r " + RULESET + " -i " + ip)
                                .stdoutOrThrow("Limiting the ruleset " + RULESET + " to " + ip);
                        added.add(ip);
                    }
                }
                for (String ip : new HashSet<>(added)) {
                    if (!wanted.contains(ip)) {
                        shell.run("esxcli network firewall ruleset allowedip remove -r " + RULESET + " -i " + ip);
                        added.remove(ip);
                    }
                }
            }
        }

        /** Takes the addresses that were added out, and opens the ruleset to all, as it is when it is not ours. */
        private void unlimit(EsxiShell shell) throws VSphereException {
            shell.run("esxcli network firewall ruleset set -r " + RULESET + " --allowed-all false");
            for (String ip : added) {
                shell.run("esxcli network firewall ruleset allowedip remove -r " + RULESET + " -i " + ip);
            }
            added.clear();
            shell.run("esxcli network firewall ruleset set -r " + RULESET + " --allowed-all true")
                    .stdoutOrThrow("Opening the ruleset " + RULESET + " to all");
        }
    }

    private static final Map<String, Host> HOSTS = new HashMap<>();

    private EsxiTransferFirewall() {}

    private static void say(@CheckForNull PrintStream log, String message) {
        if (log != null) {
            VSphereLogger.vsLogger(log, message);
        }
    }

    private static Host hostOf(EsxiShell shell) {
        final EsxiEndpoint endpoint = shell.endpoint();
        final String key = endpoint == null ? "shell@" + System.identityHashCode(shell) : endpoint.key();
        synchronized (HOSTS) {
            return HOSTS.computeIfAbsent(key, k -> new Host());
        }
    }

    /** True if the port is taken on the host: by a copy of ours, or by anything that is open or listening there. */
    static boolean isTaken(EsxiShell shell, int port) throws VSphereException {
        final Host host = hostOf(shell);
        synchronized (host) {
            if (host.ports.containsKey(port)) {
                return true;
            }
        }
        final ShellResult connections = shell.run("esxcli network ip connection list");
        if (connections.succeeded()) {
            final Pattern local = Pattern.compile("\\S+:" + port + "\\s");
            for (String line : connections.getStdout().split("\\R")) {
                // protocol, receive queue, send queue, local address, foreign address, state...
                final String[] columns = line.trim().split("\\s+");
                if (columns.length > 3 && local.matcher(columns[3] + " ").matches()) {
                    return true;
                }
            }
        }
        return false;
    }

    /**
     * Opens the port, both ways, for as long as the lease is held.
     *
     * @param peer the address that the copy comes from or goes to, or null if that is not known
     */
    static Lease open(EsxiShell shell, int port, @CheckForNull String peer, @CheckForNull PrintStream log)
            throws VSphereException {
        final Host host = hostOf(shell);
        synchronized (host) {
            if (host.ports.containsKey(port)) {
                throw new VSphereException("Channel is still handling an earlier transfer (port " + port + ")");
            }
            if (host.ports.isEmpty() && !host.on) {
                // what an earlier run that was stopped left is ours, and is replaced
                final ShellResult left = shell.run("cat " + FILE);
                if (left.succeeded()) {
                    final Matcher found = PORT_IN_FILE.matcher(left.getStdout());
                    if (found.find()) {
                        say(
                                log,
                                "The firewall of the host has rules of an earlier copy that did not end: replacing them");
                    }
                }
            }
            host.ports.put(port, peer == null ? "" : peer);
            try {
                host.apply(shell, log);
            } catch (VSphereException e) {
                host.ports.remove(port);
                try {
                    host.apply(shell, log);
                } catch (VSphereException again) {
                    // the first failure is the one to tell
                }
                throw e;
            }
            say(log, "Opened the port " + port + " in the firewall of the host (ruleset " + RULESET + ")");
            return new Lease(host, shell, port, log);
        }
    }
}
