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

/**
 * How far to trust the host key that an ESXi host presents, when no fingerprint to expect is given (one that
 * is given always has to match, whatever the policy).
 */
public enum EsxiHostKeyPolicy {

    /** Trust only a host key with the configured fingerprint; with none configured, trust nothing. The safe default. */
    FINGERPRINT,

    /**
     * Trust whichever host key the host presents the first time, remember its fingerprint, and from then on
     * require that one: a changed host key (a reinstalled host, or somebody in between) is refused.
     */
    TRUST_FIRST_USE,

    /** Trust whichever host key is presented, every time. Not secure; for hosts that are only reached in a safe network. */
    ACCEPT_ANY
}
