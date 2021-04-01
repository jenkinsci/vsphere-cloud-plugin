package org.jenkinsci.plugins.vsphere;

import hudson.Extension;
import hudson.Functions;
import hudson.model.TaskListener;
import hudson.model.AsyncPeriodicWork;
import hudson.slaves.AbstractCloudSlave;
import hudson.slaves.Cloud;
import hudson.model.Label;
import jenkins.model.Jenkins;
import org.jenkinsci.plugins.vSphereCloud;
import org.jenkinsci.plugins.vSphereCloudSlaveComputer;
import org.jenkinsci.plugins.vSphereCloudSlaveTemplate;

import java.io.IOException;
import java.util.List;
import java.util.logging.Level;
import java.util.logging.Logger;

import org.kohsuke.accmod.Restricted;
import org.kohsuke.accmod.restrictions.NoExternalUse;

/**
 * A {@link AsyncPeriodicWork} that reconciles nodes to meet template values.
 * <p>
 * The async work will check the number of deployed nodes and provision (or
 * delete) additional ones to meet template values. The check is happening every
 * 2 minutes.
 */
@Extension
@Restricted(NoExternalUse.class)
public final class VSphereNodeReconcileWork extends AsyncPeriodicWork {
    private static final Logger LOGGER = Logger.getLogger(VSphereNodeReconcileWork.class.getName());

    public VSphereNodeReconcileWork() {
        super("Vsphere nodes reconciliation");
    }

    @Override
    public long getRecurrencePeriod() {
        return Functions.getIsUnitTest() ? Long.MAX_VALUE : MIN * 2;
    }

    @Override
    public void execute(TaskListener listener) {
        for (Cloud cloud : Jenkins.getActiveInstance().clouds) {
            if (!(cloud instanceof vSphereCloud)) continue;
            for (vSphereCloudSlaveTemplate template : ((vSphereCloud) cloud).getTemplates()) {
                String templateLabel = template.getLabelString();
                Label label = Label.get(templateLabel);

                int instancesMin = template.getInstancesMin();
                List<vSphereCloudSlaveComputer> idleNodes = template.getIdleNodes();
                List<vSphereCloudSlaveComputer> runningNodes = template.getOnlineNodes();
                // Get max number of nodes that could be provisioned
                int globalMaxNodes = ((vSphereCloud) cloud).getInstanceCap();
                int templateMaxNodes = template.getTemplateInstanceCap();
                int maxNodes = Math.min(globalMaxNodes, templateMaxNodes);

                // if maxNumber is lower than instancesMin, we have to ignore instancesMin
                int toProvision = Math.min(instancesMin - idleNodes.size(), maxNodes - runningNodes.size());
                if (toProvision > 0) {
                    // provision desired number of nodes for this label
                    LOGGER.log(Level.INFO, "Pre-creating {0} instance(s) for template {1} in cloud {3}",
                            new Object[] { toProvision, templateLabel, cloud.name });
                    try {
                        cloud.provision(label, toProvision);
                    } catch (Throwable ex) {
                        LOGGER.log(Level.SEVERE, "Failed to pre-create instance from template {0}. Exception: {1}",
                                new Object[] { templateLabel, ex });
                    }
                } else if (toProvision < 0) {
                    int toDelete = Math.min(idleNodes.size(), Math.abs(toProvision));
                    for (int i = 0; i < toDelete; i++) {
                        AbstractCloudSlave node = idleNodes.get(i).getNode();
                        if (node == null) continue;
                        LOGGER.log(Level.INFO, "Found excessive instance. Terminating {0} node {1}.",
                                new Object[] { idleNodes.get(i).getName(), node });
                        try {
                            node.terminate();
                        } catch (InterruptedException | IOException e) {
                            LOGGER.log(Level.WARNING, e.getMessage());
                            // try to delete it later
                            continue;
                        }
                    }
                }
            }
        }
    }
}
