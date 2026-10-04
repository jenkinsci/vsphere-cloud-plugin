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
