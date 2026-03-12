package org.jenkinsci.plugins.vsphere;

import org.jvnet.localizer.Localizable;

import hudson.slaves.OfflineCause.SimpleOfflineCause;

/**
 * Offline because the plugin set it offline rather than anyone else.
 */
public class VSphereOfflineCause extends SimpleOfflineCause {
    public VSphereOfflineCause(Localizable description) {
        super(description);
    }
}
