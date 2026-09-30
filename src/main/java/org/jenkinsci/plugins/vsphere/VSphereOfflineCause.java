package org.jenkinsci.plugins.vsphere;

import hudson.slaves.OfflineCause.SimpleOfflineCause;
import org.jvnet.localizer.Localizable;

/**
 * Offline because the plugin set it offline rather than anyone else.
 */
public class VSphereOfflineCause extends SimpleOfflineCause {
    public VSphereOfflineCause(Localizable description) {
        super(description);
    }
}
