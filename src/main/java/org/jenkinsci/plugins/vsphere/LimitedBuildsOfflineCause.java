package org.jenkinsci.plugins.vsphere;

import org.jvnet.localizer.Localizable;
import org.kohsuke.accmod.Restricted;
import org.kohsuke.accmod.restrictions.NoExternalUse;

/**
 * Offline because the agent has used all the builds it is allowed and is being recycled. It is saved with the agent
 * while that lasts, so the name of the class is not to be changed.
 */
@Restricted(NoExternalUse.class)
public class LimitedBuildsOfflineCause extends VSphereOfflineCause {
    public LimitedBuildsOfflineCause() {
        this(Messages._agentRecycler_OfflineReason_LimitReached());
    }

    public LimitedBuildsOfflineCause(Localizable description) {
        super(description);
    }
}
