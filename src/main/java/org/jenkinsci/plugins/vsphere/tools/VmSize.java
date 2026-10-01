package org.jenkinsci.plugins.vsphere.tools;

import edu.umd.cs.findbugs.annotations.CheckForNull;

/** Parsing of the optional vCPU count / memory size a VM is to be created with. */
public final class VmSize {

    private VmSize() {}

    /**
     * Parses an optional, already variable-expanded positive whole number.
     *
     * @param what name of the setting for the error message
     * @return the number, or null if {@code value} is null or blank (meaning: keep the source's)
     * @throws VSphereException if it is set but is not a positive whole number
     */
    public static @CheckForNull Integer parseOptionalPositive(String what, @CheckForNull String value)
            throws VSphereException {
        if (value == null || value.trim().isEmpty()) {
            return null;
        }
        try {
            int parsed = Integer.parseInt(value.trim());
            if (parsed > 0) {
                return parsed;
            }
        } catch (NumberFormatException e) {
            // reported below
        }
        throw new VSphereException(what + " must be a positive whole number, but is \"" + value + "\"");
    }
}
