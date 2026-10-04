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

import com.trilead.ssh2.ServerHostKeyVerifier;
import edu.umd.cs.findbugs.annotations.CheckForNull;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Base64;

/**
 * Decides whether to trust the host key an ESXi host presents:
 *
 * <ul>
 *   <li>if a fingerprint to expect is given, only a host key with that fingerprint is trusted, whatever the
 *       policy;
 *   <li>otherwise, according to the {@link EsxiHostKeyPolicy}: the first host key seen is trusted and
 *       remembered, and from then on only it ({@link EsxiHostKeyPolicy#TRUST_FIRST_USE}); or every host key is
 *       ({@link EsxiHostKeyPolicy#ACCEPT_ANY}); or none ({@link EsxiHostKeyPolicy#FINGERPRINT}).
 * </ul>
 *
 * <p>Remembers what the host presented and, if it refused the host key, why, in words that say what to do.
 */
final class EsxiHostKeyVerifier implements ServerHostKeyVerifier {

    private final @CheckForNull String expectedFingerprint;
    private final EsxiHostKeyPolicy policy;
    private final EsxiHostKeyStore store;

    private volatile @CheckForNull EsxiHostKeyInfo presented;
    private volatile @CheckForNull String rejection;
    private volatile boolean learnedNow;

    EsxiHostKeyVerifier(@CheckForNull String expectedFingerprint, EsxiHostKeyPolicy policy, EsxiHostKeyStore store) {
        final String trimmed = expectedFingerprint == null ? "" : expectedFingerprint.trim();
        this.expectedFingerprint = trimmed.isEmpty() ? null : trimmed;
        this.policy = policy;
        this.store = store;
    }

    @Override
    public boolean verifyServerHostKey(String hostname, int port, String serverHostKeyAlgorithm, byte[] serverHostKey) {
        final EsxiHostKeyInfo info = new EsxiHostKeyInfo(serverHostKeyAlgorithm, serverHostKey);
        presented = info;
        rejection = null;
        learnedNow = false;
        final String who = "The host key presented by " + hostname;
        final String what = "its " + serverHostKeyAlgorithm + " key has the fingerprint " + info.getSha256();

        if (expectedFingerprint != null) {
            if (matches(expectedFingerprint, serverHostKey)) {
                return true;
            }
            rejection = who + " is not trusted: " + what + ", not the " + expectedFingerprint + " that is expected.";
            return false;
        }
        switch (policy) {
            case ACCEPT_ANY:
                return true;
            case TRUST_FIRST_USE:
                final boolean first = store.get(hostname, port) == null;
                final String known = store.rememberIfAbsent(hostname, port, info.getSha256());
                if (matches(known, serverHostKey)) {
                    learnedNow = first;
                    return true;
                }
                rejection = who + " has changed: " + what + ", but " + known + " was remembered when the host was"
                        + " first seen. If the host was reinstalled, or its key replaced on purpose, forget the"
                        + " remembered fingerprint to trust the new key.";
                return false;
            default:
                rejection = who + " is not trusted: " + what + ". Put that fingerprint in the settings to trust it,"
                        + " after checking that it is the host's; or trust the host key that is seen first, or any"
                        + " (which is not secure).";
                return false;
        }
    }

    boolean wasRejected() {
        return rejection != null;
    }

    /** Why the host key was refused, in words that say what to do about it; null if it was not. */
    @CheckForNull
    String getRejection() {
        return rejection;
    }

    @CheckForNull
    EsxiHostKeyInfo getPresented() {
        return presented;
    }

    /** True if the host key was the first one seen and has been remembered now. */
    boolean wasLearnedNow() {
        return learnedNow;
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
