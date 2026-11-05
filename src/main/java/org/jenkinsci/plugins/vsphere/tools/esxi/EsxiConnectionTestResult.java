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

/**
 * What a test of the connection to an ESXi host found out: whether it can be reached, which host key it
 * presents (so that the fingerprint can be taken from it), whether that is trusted under the settings, and
 * whether the login works.
 */
public final class EsxiConnectionTestResult {
    private final @CheckForNull EsxiHostKeyInfo hostKey;
    private final boolean hostKeyTrusted;
    private final boolean loggedIn;
    private final @CheckForNull String serverVersion;
    private final String message;

    EsxiConnectionTestResult(
            @CheckForNull EsxiHostKeyInfo hostKey,
            boolean hostKeyTrusted,
            boolean loggedIn,
            @CheckForNull String serverVersion,
            String message) {
        this.hostKey = hostKey;
        this.hostKeyTrusted = hostKeyTrusted;
        this.loggedIn = loggedIn;
        this.serverVersion = serverVersion;
        this.message = message;
    }

    /** The host key that the host presents, or null if it could not be reached. */
    public @CheckForNull EsxiHostKeyInfo getHostKey() {
        return hostKey;
    }

    /** True if the host key is one that would be trusted under the settings. */
    public boolean isHostKeyTrusted() {
        return hostKeyTrusted;
    }

    public boolean isLoggedIn() {
        return loggedIn;
    }

    /** What the host says it is, e.g. "VMware ESXi 7.0.3 build-12345", if it could be asked. */
    public @CheckForNull String getServerVersion() {
        return serverVersion;
    }

    /** What was found out, in words. */
    public String getMessage() {
        return message;
    }

    /** True if everything is in order: the host key is trusted and the login works. */
    public boolean isOk() {
        return hostKeyTrusted && loggedIn;
    }

    @Override
    public String toString() {
        return (isOk() ? "OK: " : "NOT OK: ") + message;
    }
}
