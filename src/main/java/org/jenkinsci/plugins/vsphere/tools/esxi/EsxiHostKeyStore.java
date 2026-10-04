package org.jenkinsci.plugins.vsphere.tools.esxi;

import edu.umd.cs.findbugs.annotations.CheckForNull;

/**
 * Where the fingerprints of the host keys that were seen the first time are remembered, for
 * {@link EsxiHostKeyPolicy#TRUST_FIRST_USE}. Fingerprints are in the form {@code SHA256:...}.
 */
public interface EsxiHostKeyStore {

    /** The fingerprint remembered for the host, or null if there is none. */
    @CheckForNull
    String get(String host, int port);

    /**
     * Remembers the fingerprint for the host unless one is remembered already. This has to be atomic, so that
     * of several first connections at once, all end up trusting the same host key.
     *
     * @return what is remembered for the host afterwards: the fingerprint that was given if there was none
     */
    String rememberIfAbsent(String host, int port, String fingerprint);

    /**
     * A view of the store that does not remember anything: it answers as the store would, and, where the store
     * knows nothing, as if the fingerprint given had been remembered. For finding out whether a connection would
     * be trusted, without having any effect.
     */
    static EsxiHostKeyStore readOnly(EsxiHostKeyStore store) {
        return new EsxiHostKeyStore() {
            @Override
            public @CheckForNull String get(String host, int port) {
                return store.get(host, port);
            }

            @Override
            public String rememberIfAbsent(String host, int port, String fingerprint) {
                final String known = store.get(host, port);
                return known != null ? known : fingerprint;
            }
        };
    }
}
