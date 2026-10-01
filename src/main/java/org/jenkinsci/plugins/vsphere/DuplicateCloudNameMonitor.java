package org.jenkinsci.plugins.vsphere;

import hudson.Extension;
import hudson.ExtensionList;
import hudson.model.AdministrativeMonitor;
import java.io.IOException;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import jenkins.model.Jenkins;
import org.kohsuke.stapler.HttpResponses;
import org.kohsuke.stapler.interceptor.RequirePOST;
import org.kohsuke.stapler.verb.POST;

/**
 * Tells administrators that {@link DuplicateCloudNameFixer} had to rename vSphere clouds at startup
 * because several clouds shared one {@code name} (typically after hand-editing {@code config.xml},
 * or from before the plugin generated unique names). The renames are already saved to
 * {@code config.xml}, so the notice is kept in memory only and does not return after a restart.
 */
@Extension
public class DuplicateCloudNameMonitor extends AdministrativeMonitor {

    private final List<String> messages = new CopyOnWriteArrayList<>();

    @Override
    public String getDisplayName() {
        return "vSphere clouds with duplicate names were renamed";
    }

    @Override
    public boolean isActivated() {
        return !messages.isEmpty();
    }

    public List<String> getMessages() {
        return Collections.unmodifiableList(messages);
    }

    static void record(String message) {
        ExtensionList.lookupSingleton(DuplicateCloudNameMonitor.class).messages.add(message);
    }

    @POST
    @RequirePOST
    public org.kohsuke.stapler.HttpResponse doDismiss() throws IOException {
        Jenkins.get().checkPermission(Jenkins.ADMINISTER);
        messages.clear();
        return HttpResponses.redirectViaContextPath("manage");
    }
}
