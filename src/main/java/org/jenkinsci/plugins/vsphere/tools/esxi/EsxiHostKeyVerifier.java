package org.jenkinsci.plugins.vsphere.tools.esxi;

import com.trilead.ssh2.ServerHostKeyVerifier;
import edu.umd.cs.findbugs.annotations.CheckForNull;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Base64;

/**
 * Decides whether to trust the host key an ESXi host presents: when a fingerprint to expect is given, only a
 * host key with that fingerprint is, and otherwise every host key is, if told to accept any. Remembers what the
 * host presented, so that a refusal can say what to put in the settings to trust it.
 */
final class EsxiHostKeyVerifier implements ServerHostKeyVerifier {

    private final @CheckForNull String expectedFingerprint;
    private final boolean acceptAny;

    private volatile @CheckForNull String presentedFingerprint;
    private volatile @CheckForNull String presentedAlgorithm;
    private volatile boolean rejected;

    EsxiHostKeyVerifier(@CheckForNull String expectedFingerprint, boolean acceptAny) {
        final String trimmed = expectedFingerprint == null ? "" : expectedFingerprint.trim();
        this.expectedFingerprint = trimmed.isEmpty() ? null : trimmed;
        this.acceptAny = acceptAny;
    }

    @Override
    public boolean verifyServerHostKey(String hostname, int port, String serverHostKeyAlgorithm, byte[] serverHostKey) {
        presentedFingerprint = sha256(serverHostKey);
        presentedAlgorithm = serverHostKeyAlgorithm;
        final boolean trusted = expectedFingerprint != null ? matches(expectedFingerprint, serverHostKey) : acceptAny;
        rejected = !trusted;
        return trusted;
    }

    boolean wasRejected() {
        return rejected;
    }

    @CheckForNull
    String getPresentedFingerprint() {
        return presentedFingerprint;
    }

    @CheckForNull
    String getPresentedAlgorithm() {
        return presentedAlgorithm;
    }

    /** True if the fingerprint, in the form {@code SHA256:...} (or without the prefix) or MD5 {@code ab:cd:...}, is the key's. */
    static boolean matches(String fingerprint, byte[] hostKey) {
        final String wanted = fingerprint.trim();
        if (wanted.regionMatches(true, 0, "MD5:", 0, 4) || wanted.indexOf(':') > 0 && !wanted.startsWith("SHA256:")) {
            final String hex = wanted.regionMatches(true, 0, "MD5:", 0, 4) ? wanted.substring(4) : wanted;
            return md5(hostKey).equalsIgnoreCase(hex.trim());
        }
        final String sha = sha256(hostKey);
        return sha.equals(wanted) || sha.substring("SHA256:".length()).equals(wanted);
    }

    /** The fingerprint that OpenSSH shows: {@code SHA256:} and the unpadded base64 of the SHA-256 of the key. */
    static String sha256(byte[] hostKey) {
        return "SHA256:" + Base64.getEncoder().withoutPadding().encodeToString(digest("SHA-256", hostKey));
    }

    /** The older fingerprint: the MD5 of the key in hex, in pairs separated by colons. */
    static String md5(byte[] hostKey) {
        final byte[] digest = digest("MD5", hostKey);
        final StringBuilder out = new StringBuilder();
        for (int i = 0; i < digest.length; i++) {
            if (i > 0) {
                out.append(':');
            }
            out.append(String.format("%02x", digest[i] & 0xff));
        }
        return out.toString();
    }

    private static byte[] digest(String algorithm, byte[] data) {
        try {
            return MessageDigest.getInstance(algorithm).digest(data);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(algorithm + " is always available", e);
        }
    }
}
