package org.jenkinsci.plugins.vsphere.tools.esxi;

/** What an ESXi host presented as its host key: the kind of key, and its fingerprints. */
public final class EsxiHostKeyInfo {
    private final String algorithm;
    private final byte[] key;
    private final String sha256;
    private final String md5;

    EsxiHostKeyInfo(String algorithm, byte[] key) {
        this.algorithm = algorithm;
        this.key = key.clone();
        this.sha256 = EsxiHostKeyVerifier.sha256(key);
        this.md5 = EsxiHostKeyVerifier.md5(key);
    }

    /** The kind of key, such as {@code ssh-rsa} or {@code ssh-ed25519}. */
    public String getAlgorithm() {
        return algorithm;
    }

    /** The fingerprint in the form OpenSSH shows, {@code SHA256:...}. */
    public String getSha256() {
        return sha256;
    }

    /** The older fingerprint, {@code ab:cd:...}. */
    public String getMd5() {
        return md5;
    }

    byte[] getKey() {
        return key.clone();
    }

    @Override
    public String toString() {
        return algorithm + " " + sha256;
    }
}
