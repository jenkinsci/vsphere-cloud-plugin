package org.jenkinsci.plugins.vsphere.tools.esxi;

import edu.umd.cs.findbugs.annotations.CheckForNull;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/** An {@link EsxiHostKeyStore} that keeps the fingerprints in memory, for as long as it lives. */
public final class InMemoryEsxiHostKeyStore implements EsxiHostKeyStore {

    private final ConcurrentMap<String, String> fingerprints = new ConcurrentHashMap<>();

    private static String key(String host, int port) {
        return host.toLowerCase() + ":" + port;
    }

    @Override
    public @CheckForNull String get(String host, int port) {
        return fingerprints.get(key(host, port));
    }

    @Override
    public String rememberIfAbsent(String host, int port, String fingerprint) {
        final String known = fingerprints.putIfAbsent(key(host, port), fingerprint);
        return known != null ? known : fingerprint;
    }

    /** Forgets what is remembered for the host, so that the next connection is a first one again. */
    public void forget(String host, int port) {
        fingerprints.remove(key(host, port));
    }
}
