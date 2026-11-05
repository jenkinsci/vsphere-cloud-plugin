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

/** Everything needed to open an SSH session to an ESXi host: where, as whom, and how far to trust it. */
public final class EsxiSshSettings {

    public static final int DEFAULT_PORT = 22;
    public static final int DEFAULT_CONNECT_TIMEOUT_SECONDS = 30;
    public static final int DEFAULT_COMMAND_TIMEOUT_SECONDS = 600;

    private final String host;
    private final int port;
    private final EsxiSshAuth auth;
    private @CheckForNull String hostKeyFingerprint;
    private EsxiHostKeyPolicy hostKeyPolicy = EsxiHostKeyPolicy.FINGERPRINT;
    private EsxiHostKeyStore hostKeyStore = new InMemoryEsxiHostKeyStore();
    private int connectTimeoutSeconds = DEFAULT_CONNECT_TIMEOUT_SECONDS;
    private int commandTimeoutSeconds = DEFAULT_COMMAND_TIMEOUT_SECONDS;

    public EsxiSshSettings(String host, int port, EsxiSshAuth auth) {
        this.host = host;
        this.port = port <= 0 ? DEFAULT_PORT : port;
        this.auth = auth;
    }

    public String getHost() {
        return host;
    }

    public int getPort() {
        return port;
    }

    public EsxiSshAuth getAuth() {
        return auth;
    }

    public @CheckForNull String getHostKeyFingerprint() {
        return hostKeyFingerprint;
    }

    /**
     * Only a host with a host key of this fingerprint ({@code SHA256:...} or MD5 {@code ab:cd:...}) is trusted,
     * whatever the {@link #getHostKeyPolicy() policy}.
     */
    public EsxiSshSettings withHostKeyFingerprint(@CheckForNull String fingerprint) {
        this.hostKeyFingerprint = fingerprint;
        return this;
    }

    public EsxiHostKeyPolicy getHostKeyPolicy() {
        return hostKeyPolicy;
    }

    /** What to trust when there is no fingerprint to expect. */
    public EsxiSshSettings withHostKeyPolicy(EsxiHostKeyPolicy policy) {
        this.hostKeyPolicy = policy == null ? EsxiHostKeyPolicy.FINGERPRINT : policy;
        return this;
    }

    public EsxiHostKeyStore getHostKeyStore() {
        return hostKeyStore;
    }

    /**
     * Where {@link EsxiHostKeyPolicy#TRUST_FIRST_USE} remembers the host keys that it saw first. The default
     * only remembers them as long as these settings are around; something that outlives them is needed for the
     * trust to last.
     */
    public EsxiSshSettings withHostKeyStore(EsxiHostKeyStore store) {
        this.hostKeyStore = store;
        return this;
    }

    public int getConnectTimeoutSeconds() {
        return connectTimeoutSeconds;
    }

    public EsxiSshSettings withConnectTimeoutSeconds(int seconds) {
        this.connectTimeoutSeconds = seconds > 0 ? seconds : DEFAULT_CONNECT_TIMEOUT_SECONDS;
        return this;
    }

    public int getCommandTimeoutSeconds() {
        return commandTimeoutSeconds;
    }

    /** How long a single command may take before it is given up on. */
    public EsxiSshSettings withCommandTimeoutSeconds(int seconds) {
        this.commandTimeoutSeconds = seconds > 0 ? seconds : DEFAULT_COMMAND_TIMEOUT_SECONDS;
        return this;
    }

    @Override
    public String toString() {
        return "ssh://" + auth.getUsername() + "@" + host + ":" + port;
    }
}
