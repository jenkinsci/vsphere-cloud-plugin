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

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.MessageDigest;
import java.security.PrivateKey;
import java.security.SecureRandom;
import java.util.Base64;
import javax.crypto.Cipher;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.SecretKeySpec;

/** RSA keys for the tests, and their private halves written as the PEM text that SSH clients read. */
final class TestKeys {

    private TestKeys() {}

    static KeyPair rsa() throws Exception {
        final KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
        generator.initialize(2048);
        return generator.generateKeyPair();
    }

    /** The traditional {@code -----BEGIN RSA PRIVATE KEY-----} format, not encrypted. */
    static String pem(PrivateKey key) throws Exception {
        return wrap(pkcs1(key), null);
    }

    /** The same, encrypted with a passphrase the way OpenSSL does it (AES-128-CBC, key from the MD5 of it). */
    static String encryptedPem(PrivateKey key, String passphrase) throws Exception {
        final byte[] iv = new byte[16];
        new SecureRandom().nextBytes(iv);
        final MessageDigest md5 = MessageDigest.getInstance("MD5");
        md5.update(passphrase.getBytes(StandardCharsets.UTF_8));
        md5.update(iv, 0, 8);
        final Cipher cipher = Cipher.getInstance("AES/CBC/PKCS5Padding");
        cipher.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(md5.digest(), "AES"), new IvParameterSpec(iv));
        final StringBuilder hex = new StringBuilder();
        for (byte b : iv) {
            hex.append(String.format("%02X", b & 0xff));
        }
        return wrap(cipher.doFinal(pkcs1(key)), "Proc-Type: 4,ENCRYPTED\nDEK-Info: AES-128-CBC," + hex + "\n\n");
    }

    private static String wrap(byte[] der, String headers) {
        final String body = Base64.getMimeEncoder(64, "\n".getBytes(StandardCharsets.US_ASCII))
                .encodeToString(der);
        return "-----BEGIN RSA PRIVATE KEY-----\n" + (headers == null ? "" : headers) + body
                + "\n-----END RSA PRIVATE KEY-----\n";
    }

    /** What is inside of the PKCS#8 wrapper that Java gives a private key in: SEQ { INT, SEQ { OID, NULL }, OCTET STRING }. */
    private static byte[] pkcs1(PrivateKey key) {
        final byte[] pkcs8 = key.getEncoded();
        int[] pos = {0};
        enter(pkcs8, pos, 0x30); // the outer sequence
        skip(pkcs8, pos, 0x02); // the version
        skip(pkcs8, pos, 0x30); // the algorithm
        final int length = enter(pkcs8, pos, 0x04); // the octet string around the key
        final ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.write(pkcs8, pos[0], length);
        return out.toByteArray();
    }

    /** Steps into a DER element of that type, returning the length of its content. */
    private static int enter(byte[] der, int[] pos, int tag) {
        if ((der[pos[0]++] & 0xff) != tag) {
            throw new IllegalStateException("Not the expected DER tag " + tag);
        }
        return length(der, pos);
    }

    /** Steps over a DER element of that type. */
    private static void skip(byte[] der, int[] pos, int tag) {
        pos[0] += 0;
        final int length = enter(der, pos, tag);
        pos[0] += length;
    }

    private static int length(byte[] der, int[] pos) {
        final int first = der[pos[0]++] & 0xff;
        if (first < 0x80) {
            return first;
        }
        int length = 0;
        for (int i = 0; i < (first & 0x7f); i++) {
            length = (length << 8) | (der[pos[0]++] & 0xff);
        }
        return length;
    }
}
