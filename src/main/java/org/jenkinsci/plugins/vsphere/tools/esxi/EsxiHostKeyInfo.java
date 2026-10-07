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
