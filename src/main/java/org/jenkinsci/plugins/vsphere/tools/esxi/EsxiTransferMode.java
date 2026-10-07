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

/** How the files of a replica get from one host to another. */
public enum EsxiTransferMode {
    /** Through the controller, which needs nothing of the hosts but the session that each has already. */
    RELAY,
    /** From host to host over SSH, with a key that is made for the copy and removed after: encrypted, and fast. */
    SSH_DIRECT,
    /** From host to host by {@code nc}, with the firewall opened for the copy: the fastest, and not encrypted. */
    NETCAT;

    /** What does the moving. */
    public EsxiRelay.Mover mover() {
        switch (this) {
            case SSH_DIRECT:
                return new EsxiSshDirect();
            case NETCAT:
                return new EsxiNetcat();
            default:
                return EsxiRelay.RELAY;
        }
    }
}
