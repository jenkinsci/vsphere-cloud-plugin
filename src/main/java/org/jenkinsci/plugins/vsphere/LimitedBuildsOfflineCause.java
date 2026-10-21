package org.jenkinsci.plugins.vsphere;

import org.jvnet.localizer.Localizable;

/** Offline because the agent has used all the builds it is allowed and is being recycled. */
public class LimitedBuildsOfflineCause extends VSphereOfflineCause {
    public LimitedBuildsOfflineCause() {
        this(Messages._agentRecycler_OfflineReason_LimitReached());
    }

    public LimitedBuildsOfflineCause(Localizable description) {
        super(description);
    }
}
