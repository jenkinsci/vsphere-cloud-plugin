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
    private boolean acceptAnyHostKey;
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

    /** Only a host with a host key of this fingerprint ({@code SHA256:...} or MD5 {@code ab:cd:...}) is trusted. */
    public EsxiSshSettings withHostKeyFingerprint(@CheckForNull String fingerprint) {
        this.hostKeyFingerprint = fingerprint;
        return this;
    }

    public boolean isAcceptAnyHostKey() {
        return acceptAnyHostKey;
    }

    /** Trust whichever host key the host presents, when there is no fingerprint to expect. Not secure. */
    public EsxiSshSettings withAcceptAnyHostKey(boolean acceptAnyHostKey) {
        this.acceptAnyHostKey = acceptAnyHostKey;
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
