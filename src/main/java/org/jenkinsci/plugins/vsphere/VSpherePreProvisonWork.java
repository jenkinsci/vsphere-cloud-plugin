package org.jenkinsci.plugins.vsphere;

import hudson.Extension;
import hudson.Functions;
import hudson.model.TaskListener;
import hudson.model.AsyncPeriodicWork;
import hudson.slaves.Cloud;
import jenkins.model.Jenkins;
import org.jenkinsci.plugins.vSphereCloud;
import org.jenkinsci.plugins.vSphereCloudSlaveTemplate;

import java.util.logging.Level;
import java.util.logging.Logger;

import org.kohsuke.accmod.Restricted;
import org.kohsuke.accmod.restrictions.NoExternalUse;

/**
 * A {@link AsyncPeriodicWork} that pre-provisions nodes to meet insntanceMin template value.
 * <p>
 * The async work will check the number of active nodes 
 * and provision additional ones to meet template values. 
 * 
 * The check is happening every 2 minutes.
 */
@Extension
@Restricted(NoExternalUse.class)
public final class VSpherePreProvisonWork extends AsyncPeriodicWork {
    private static final Logger LOGGER = Logger.getLogger(VSpherePreProvisonWork.class.getName());

    public VSpherePreProvisonWork() {
        super("Vsphere pre-provision check");
    }

    @Override
    public long getRecurrencePeriod() {
        return Functions.getIsUnitTest() ? Long.MAX_VALUE : MIN * 2;
    }

    @Override
    public void execute(TaskListener listener) {
        for (Cloud cloud : Jenkins.getActiveInstance().clouds) {
            if (!(cloud instanceof vSphereCloud)) continue;
            vSphereCloud vsCloud = (vSphereCloud) cloud;
            for (vSphereCloudSlaveTemplate template : vsCloud.getTemplates()) {
                if (template.getInstancesMin() > 0) {
                    LOGGER.log(Level.INFO, "Check if template (label=" + template.getLabelString() + ") has enough active nodes to meet instances Min value");
                    vsCloud.preProvisionNodes(template);
                }
            }
        }
    }
}
