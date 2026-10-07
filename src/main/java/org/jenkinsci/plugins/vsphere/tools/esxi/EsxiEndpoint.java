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

import java.util.Objects;

/**
 * Where a host is reached, and as whom: what another host needs to reach it too, for copying files to it directly. It
 * is the address as the controller has it, so that is also where the other hosts must be able to find it.
 */
public final class EsxiEndpoint {
    private final String host;
    private final int port;
    private final String user;

    public EsxiEndpoint(String host, int port, String user) {
        this.host = host;
        this.port = port;
        this.user = user;
    }

    public String getHost() {
        return host;
    }

    public int getPort() {
        return port;
    }

    public String getUser() {
        return user;
    }

    /** Tells the host apart from the others, in what is shared between the copies that are made. */
    String key() {
        return host + ":" + port;
    }

    @Override
    public boolean equals(Object o) {
        return o instanceof EsxiEndpoint
                && ((EsxiEndpoint) o).key().equals(key())
                && ((EsxiEndpoint) o).user.equals(user);
    }

    @Override
    public int hashCode() {
        return Objects.hash(host, port, user);
    }

    @Override
    public String toString() {
        return user + "@" + host + ":" + port;
    }
}
