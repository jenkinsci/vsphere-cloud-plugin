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

/** What a command run by an {@link EsxiShell} printed, and how it ended. */
public final class ShellResult {
    private final int exitCode;
    private final String stdout;
    private final String stderr;

    public ShellResult(int exitCode, String stdout, String stderr) {
        this.exitCode = exitCode;
        this.stdout = stdout == null ? "" : stdout;
        this.stderr = stderr == null ? "" : stderr;
    }

    public int getExitCode() {
        return exitCode;
    }

    public String getStdout() {
        return stdout;
    }

    public String getStderr() {
        return stderr;
    }

    public boolean succeeded() {
        return exitCode == 0;
    }

    /**
     * @return what the command printed, if it succeeded
     * @throws VSphereException with the host's own explanation, if it did not
     */
    public String stdoutOrThrow(String what) throws VSphereException {
        if (!succeeded()) {
            final String explanation = !stderr.trim().isEmpty() ? stderr.trim() : stdout.trim();
            throw new VSphereException(
                    what + " failed (exit code " + exitCode + ")" + (explanation.isEmpty() ? "" : ": " + explanation));
        }
        return stdout;
    }

    @Override
    public String toString() {
        return "exit " + exitCode + ", stdout=" + stdout + ", stderr=" + stderr;
    }
}
