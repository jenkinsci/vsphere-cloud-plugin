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
