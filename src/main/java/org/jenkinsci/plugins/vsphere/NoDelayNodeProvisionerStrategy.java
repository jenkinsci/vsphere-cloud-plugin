package org.jenkinsci.plugins.vsphere;

import edu.umd.cs.findbugs.annotations.NonNull;
import hudson.Extension;
import hudson.model.Label;
import hudson.model.LoadStatistics;
import hudson.slaves.Cloud;
import hudson.slaves.CloudProvisioningListener;
import hudson.slaves.NodeProvisioner;
import jenkins.model.Jenkins;
import org.jenkinsci.plugins.vSphereCloud;

import java.util.Collection;
import java.util.logging.Level;
import java.util.logging.Logger;


/**
 * A {@link NodeProvisioner.Strategy} that immediately provisions capacity to meet demand.
 * <p>
 * The default node provisioner strategy in Jenkins is conservative and only provisions new nodes under sustained
 * demand.  The implicit assumption is that busy executors will become free.  This does not happen when using
 * {@link RunOnceCloudRetentionStrategy} or when limiting job runs to 1.  As a result, the default strategy
 * unnecessarily causes delays starting new jobs.
 * <p>
 * This strategy works with @{link vSphereCloud}, and is enabled on a per-cloud basis.
 * <p>
 * Adapted from {@link NodeProvisioner.StandardStrategyImpl} at version 2.235.5.
 */
@Extension(ordinal = 100)
public class NoDelayNodeProvisionerStrategy extends NodeProvisioner.Strategy {

    private static final Logger LOGGER = Logger.getLogger(NoDelayNodeProvisionerStrategy.class.getName());

    @NonNull
    @Override
    public NodeProvisioner.StrategyDecision apply(@NonNull NodeProvisioner.StrategyState state) {

        LoadStatistics.LoadStatisticsSnapshot snapshot = state.getSnapshot();

        int currentDemand = snapshot.getQueueLength();  // Jobs waiting for executors

        int availableCapacity = snapshot.getAvailableExecutors()  // Idle executors
                + snapshot.getConnectingExecutors()      // Connecting executors
                + state.getPlannedCapacitySnapshot()     // Previously provisioned executors
                + state.getAdditionalPlannedCapacity();  // Executors provisioned by another strategy

        int excessWorkload = currentDemand - availableCapacity;  // Number of needed executors

        if (excessWorkload > 0) {
            LOGGER.log(Level.FINE, "Excess workload {0,number,integer} detected. "
                            + "(demand={1,number,integer}, available={2,number,integer}, "
                            + "online={3,number,integer}, connecting={4,number,integer}, "
                            + "planned={5,number,integer})",
                    new Object[]{
                            excessWorkload, currentDemand, snapshot.getAvailableExecutors(),
                            snapshot.getOnlineExecutors(), snapshot.getConnectingExecutors(),
                            state.getPlannedCapacitySnapshot() + state.getAdditionalPlannedCapacity()
                    });

            CLOUD:
            for (Cloud c : Jenkins.getActiveInstance().clouds) {
                // Only apply strategy on vSphere clouds
                if (!(c instanceof vSphereCloud)) {
                    continue;
                }

                // Only apply strategy if enabled for this cloud
                if (!((vSphereCloud) c).getUseNoDelayProvisioner()) {
                    continue;
                }

                // Skip clouds that cannot provision this label
                if (!c.canProvision(state.getLabel())) {
                    continue;
                }

                // Extension point to block provisioning
                for (CloudProvisioningListener cl : CloudProvisioningListener.all()) {
                    if (cl.canProvision(c, state.getLabel(), excessWorkload) != null) {
                        // Cloud listener blocked provisioning on this cloud, try next cloud
                        continue CLOUD;
                    }
                }

                // Provision
                Collection<NodeProvisioner.PlannedNode> plannedNodes = c.provision(state.getLabel(), excessWorkload);

                // Notify listeners
                fireOnStarted(c, state.getLabel(), plannedNodes);

                // Update state
                for (NodeProvisioner.PlannedNode plannedNode : plannedNodes) {
                    excessWorkload -= plannedNode.numExecutors;
                    LOGGER.log(Level.INFO, "Started provisioning {0} from {1} with {2,number,integer} "
                                    + "executors. Remaining excess workload: {3,number,integer}",
                            new Object[]{plannedNode.displayName, c.name, plannedNode.numExecutors, excessWorkload});
                }
                state.recordPendingLaunches(plannedNodes);

                if (excessWorkload <= 0) {
                    // Enough capacity has been provisioned, skip remaining clouds
                    break;
                }
            }
        }

        // If more capacity is needed, let another strategy / cloud provider satisfy the demand
        if (excessWorkload > 0) {
            return NodeProvisioner.StrategyDecision.CONSULT_REMAINING_STRATEGIES;
        } else {
            return NodeProvisioner.StrategyDecision.PROVISIONING_COMPLETED;
        }
    }

    private static void fireOnStarted(final Cloud cloud, final Label label,
                                      final Collection<NodeProvisioner.PlannedNode> plannedNodes) {
        for (CloudProvisioningListener cl : CloudProvisioningListener.all()) {
            try {
                cl.onStarted(cloud, label, plannedNodes);
            } catch (Error e) {
                throw e;
            } catch (Throwable e) {
                LOGGER.log(Level.SEVERE, "Unexpected uncaught exception encountered while "
                        + "processing onStarted() listener call in " + cl + " for label "
                        + label.toString(), e);
            }
        }
    }
}
