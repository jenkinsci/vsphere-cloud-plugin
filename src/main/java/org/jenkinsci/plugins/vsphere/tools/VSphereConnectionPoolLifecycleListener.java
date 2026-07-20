package org.jenkinsci.plugins.vsphere.tools;

import hudson.Extension;
import hudson.XmlFile;
import hudson.model.Saveable;
import hudson.model.listeners.SaveableListener;

/**
 * Keeps {@link VSphereConnectionPool} instances honest across an event that Jenkins core
 * has no dedicated "cloud replaced/removed" hook for: saving the Jenkins configuration
 * (e.g. the "Manage Jenkins &gt; Clouds" form, or a JCasC reload) replaces every
 * reconfigured {@code vSphereCloud} with a brand new instance; the old instance - and the
 * pool/background-thread/vCenter session it may still own - is simply dropped by Jenkins
 * core with no notification. Without this listener that old pool keeps running under its
 * previous settings indefinitely, which is very confusing to observe (it looks like
 * configuration changes are being ignored).
 */
public final class VSphereConnectionPoolLifecycleListener {

    private VSphereConnectionPoolLifecycleListener() {
    }

    @Extension
    public static final class ReapOnSave extends SaveableListener {
        @Override
        public void onChange(Saveable o, XmlFile file) {
            VSphereConnectionPoolRegistry.reapOrphans();
        }
    }
}
