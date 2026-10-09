package org.jenkinsci.plugins.vsphere.tools;

import edu.umd.cs.findbugs.annotations.CheckForNull;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Works out the inventory folder for a clone from the folder of its source, for inventories that group
 * VMs in per-host folders: the source's folder path has an element named after the source's host, and
 * the clone's folder is the same path with that element replaced by the name of the clone's host.
 */
public final class HostFolderPath {

    private HostFolderPath() {}

    /**
     * Returns {@code sourceFolderPath} with the first element naming {@code fromHost} replaced by one naming
     * {@code toHost}, or null if no element names {@code fromHost}. An element names a host if it equals the
     * host's name, or its short name (the part before the first dot), or is the full name of a host given by
     * its short name, ignoring case; the replacement is the short name of {@code toHost} where the element
     * was a short name, else {@code toHost} as given.
     */
    public static @CheckForNull List<String> rewrite(
            List<String> sourceFolderPath, @CheckForNull String fromHost, @CheckForNull String toHost) {
        if (fromHost == null || fromHost.isEmpty() || toHost == null || toHost.isEmpty()) {
            return null;
        }
        for (int i = 0; i < sourceFolderPath.size(); i++) {
            final String element = sourceFolderPath.get(i);
            final String replacement;
            if (element.equalsIgnoreCase(fromHost)) {
                replacement = toHost;
            } else if (element.equalsIgnoreCase(shortName(fromHost))) {
                replacement = shortName(toHost);
            } else if (shortName(element).equalsIgnoreCase(fromHost)) {
                replacement = toHost;
            } else {
                continue;
            }
            final List<String> result = new ArrayList<>(sourceFolderPath);
            result.set(i, replacement);
            return result;
        }
        return null;
    }

    private static String shortName(String host) {
        final int dot = host.indexOf('.');
        // An IP address has no short name
        if (dot <= 0 || host.matches("[0-9.]+") || host.toLowerCase(Locale.ROOT).contains(":")) {
            return host;
        }
        return host.substring(0, dot);
    }
}
