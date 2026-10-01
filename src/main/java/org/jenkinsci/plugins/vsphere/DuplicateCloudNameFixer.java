package org.jenkinsci.plugins.vsphere;

import hudson.init.InitMilestone;
import hudson.init.Initializer;
import hudson.slaves.Cloud;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.logging.Level;
import java.util.logging.Logger;
import jenkins.model.Jenkins;
import org.jenkinsci.plugins.vSphereCloud;

/**
 * Startup sanity check: Jenkins identifies a {@link Cloud} by its {@code name}, so two clouds sharing
 * one (as every vSphere cloud did historically, and as a hand-edited {@code config.xml} may again)
 * make URLs like {@code /manage/cloud/<name>/} and "Save" on the second one act on the first. The
 * first cloud with a name keeps it; later {@link vSphereCloud}s are renamed after their description.
 */
public final class DuplicateCloudNameFixer {

    private static final Logger LOGGER = Logger.getLogger(DuplicateCloudNameFixer.class.getName());

    private DuplicateCloudNameFixer() {}

    @Initializer(after = InitMilestone.SYSTEM_CONFIG_ADAPTED)
    public static void fixAtStartup() throws IOException {
        fix(Jenkins.get());
    }

    /**
     * Renames every {@link vSphereCloud} whose name is empty or already taken by an earlier cloud,
     * saves Jenkins if anything changed and raises an administrative notice.
     *
     * @return one human-readable line per rename performed
     */
    public static List<String> fix(Jenkins jenkins) throws IOException {
        Set<String> everyName = new HashSet<>();
        for (Cloud cloud : jenkins.clouds) {
            if (cloud.name != null) {
                everyName.add(cloud.name);
            }
        }
        Set<String> seen = new HashSet<>();
        List<String> renames = new ArrayList<>();
        for (Cloud cloud : jenkins.clouds) {
            if (cloud instanceof vSphereCloud) {
                String oldName = cloud.name;
                if (oldName == null || oldName.isEmpty() || seen.contains(oldName)) {
                    String newName = vSphereCloud.deriveCloudName(((vSphereCloud) cloud).getVsDescription(), everyName);
                    cloud.name = newName;
                    everyName.add(newName);
                    String message = String.format(
                            "vSphere cloud '%s' shared its name '%s' with another cloud; renamed to '%s'.",
                            ((vSphereCloud) cloud).getVsDescription(), oldName, newName);
                    LOGGER.log(Level.WARNING, message);
                    renames.add(message);
                }
            }
            seen.add(cloud.name);
        }
        if (!renames.isEmpty()) {
            jenkins.save();
            for (String message : renames) {
                DuplicateCloudNameMonitor.record(message);
            }
        }
        return renames;
    }
}
