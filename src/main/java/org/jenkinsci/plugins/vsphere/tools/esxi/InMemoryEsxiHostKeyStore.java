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
