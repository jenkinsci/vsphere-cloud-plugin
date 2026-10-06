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
import org.jenkinsci.plugins.vsphere.tools.VSphereException;
import org.jenkinsci.plugins.vsphere.tools.VSphereLogger;

/**
 * A ruleset of the firewall of a host (such as {@code sshClient}, which lets the host open SSH connections to others)
 * that is on for as long as copies need it, and that is put back as it was when the last of them is done.
 *
 * <p>Several copies at once share it: it is turned on when the first starts and off when the last ends, and only if
 * it was off to begin with. A ruleset that was on already is the administrator's, and stays on. If the controller was
 * stopped while it had turned one on, a marker file on the host says so, and the next copy that finds the ruleset on
 * with the marker takes it that it is ours to turn off.
 */
final class EsxiFirewallRuleset {

    /** What a copy holds while it needs the ruleset; closing it lets go. Does not throw. */
    static final class Lease implements AutoCloseable {
        private final EsxiShell shell;
        private final String name;
        private final Holder holder;
        private final String key;
        private final PrintStream log;
        private boolean closed;

        private Lease(EsxiShell shell, String name, Holder holder, String key, @CheckForNull PrintStream log) {
            this.shell = shell;
            this.name = name;
            this.holder = holder;
            this.key = key;
            this.log = log;
        }

        @Override
        public void close() {
            synchronized (holder) {
                if (closed) {
                    return;
                }
                closed = true;
                holder.users--;
                if (holder.users == 0 && holder.weTurnedItOn) {
                    try {
                        shell.run("esxcli network firewall ruleset set -r " + name + " -e false");
                        shell.run("rm -f " + marker(name));
                        say(log, "Turned the firewall ruleset " + name + " off again on " + key);
                    } catch (VSphereException e) {
                        say(
                                log,
                                "Could not turn the firewall ruleset " + name + " off on " + key + ": "
                                        + e.getMessage());
                    }
                    holder.weTurnedItOn = false;
                }
            }
        }
    }

    private static final class Holder {
        int users;
        boolean weTurnedItOn;
    }

    private static final Map<String, Holder> HOLDERS = new HashMap<>();

    private EsxiFirewallRuleset() {}

    private static void say(@CheckForNull PrintStream log, String message) {
        if (log != null) {
            VSphereLogger.vsLogger(log, message);
        }
    }

    private static String marker(String name) {
        return "/tmp/.jenkins-firewall-" + name;
    }

    /** Whom the ruleset of the host is shared with: those that reach the host the same way. */
    private static String keyOf(EsxiShell shell, String name) {
        final EsxiEndpoint endpoint = shell.endpoint();
        return (endpoint == null ? "shell@" + System.identityHashCode(shell) : endpoint.key()) + "/" + name;
    }

    /** Whether the ruleset is on, as {@code esxcli network firewall ruleset list} says. */
    private static boolean isOn(EsxiShell shell, String name) throws VSphereException {
        final String listing =
                shell.run("esxcli network firewall ruleset list").stdoutOrThrow("Listing the rulesets of the firewall");
        for (String line : listing.split("\\R")) {
            final String[] columns = line.trim().split("\\s+");
            if (columns.length == 2 && columns[0].equals(name)) {
                return columns[1].equalsIgnoreCase("true");
            }
        }
        throw new VSphereException("The host has no firewall ruleset " + name);
    }

    /**
     * Turns the ruleset on, if it is not, for as long as the lease is held.
     *
     * @param name the name of a ruleset that is a plain word
     */
    static Lease acquire(EsxiShell shell, String name, @CheckForNull PrintStream log) throws VSphereException {
        if (!name.matches("[A-Za-z0-9_-]+")) {
            throw new VSphereException(name + " is not the name of a firewall ruleset");
        }
        final String key = keyOf(shell, name);
        final Holder holder;
        synchronized (HOLDERS) {
            holder = HOLDERS.computeIfAbsent(key, k -> new Holder());
        }
        synchronized (holder) {
            if (holder.users == 0) {
                if (isOn(shell, name)) {
                    // on already: the administrator's, unless a copy of ours that was stopped left it so
                    holder.weTurnedItOn = shell.run("test -e " + marker(name)).succeeded();
                } else {
                    shell.run("esxcli network firewall ruleset set -r " + name + " -e true")
                            .stdoutOrThrow("Turning the firewall ruleset " + name + " on");
                    shell.run("touch " + marker(name));
                    holder.weTurnedItOn = true;
                    say(log, "Turned the firewall ruleset " + name + " on, on " + key);
                }
            }
            holder.users++;
            return new Lease(shell, name, holder, key, log);
        }
    }
}
