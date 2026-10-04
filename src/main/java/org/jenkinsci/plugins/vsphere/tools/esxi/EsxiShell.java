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
 * A way to run commands in the shell of a standalone ESXi host (normally over SSH), which is what the
 * {@link VSphereEsxiSsh} backend does everything through. A seam, so that all of the logic can be tested against
 * recorded command output.
 */
public interface EsxiShell extends AutoCloseable {

    /**
     * Runs a command line in the shell of the host and waits for it to end.
     *
     * @return what the command printed and its exit code; a non-zero exit code is not an error here
     * @throws VSphereException if the command could not be run at all (e.g. the connection is gone)
     */
    ShellResult run(String command) throws VSphereException;

    /** Ends the session. Does not throw. */
    @Override
    void close();
}
