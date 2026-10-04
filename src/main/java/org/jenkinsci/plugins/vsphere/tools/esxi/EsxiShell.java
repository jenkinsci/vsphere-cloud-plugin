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
