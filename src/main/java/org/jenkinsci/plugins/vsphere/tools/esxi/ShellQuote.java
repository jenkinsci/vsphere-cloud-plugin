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

import org.jenkinsci.plugins.vsphere.tools.VSphereException;

/**
 * Makes values safe to put in a command line that runs as an administrator on a hypervisor. Anything that
 * comes from a job parameter (names, descriptions, ...) has to go through here; identifiers that come from the
 * host itself are checked to be plain numbers instead.
 */
final class ShellQuote {

    private ShellQuote() {}

    /** The text as one word for a POSIX shell, whatever it contains. */
    static String quote(String value) throws VSphereException {
        if (value == null) {
            throw new VSphereException("A value to put in a shell command is missing");
        }
        if (value.indexOf('\0') >= 0) {
            throw new VSphereException("A value to put in a shell command contains a NUL character");
        }
        // A quote cannot be put inside single quotes: close them, add an escaped quote, and open them again
        return "'" + value.replace("'", "'\\''") + "'";
    }

    /** A VM id as the host reported it: digits only, so that it can go into a command as it is. */
    static String id(int vmId) throws VSphereException {
        if (vmId <= 0) {
            throw new VSphereException("Not a valid VM id: " + vmId);
        }
        return Integer.toString(vmId);
    }
}
