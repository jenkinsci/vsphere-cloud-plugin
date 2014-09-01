package org.jenkinsci.plugins;

import hudson.Extension;
import hudson.Util;
import hudson.model.Computer;
import hudson.model.Descriptor;
import hudson.model.Node;
import hudson.model.Queue;
import hudson.slaves.OfflineCause;
import hudson.slaves.RetentionStrategy;
import java.util.Collections;
import java.util.HashMap;
import java.util.logging.Level;
import java.util.logging.Logger;
import jenkins.model.Jenkins;
import org.kohsuke.stapler.DataBoundConstructor;

public class vSphereDemandRetentionStrategy extends vSphereRetentionStrategy {

        private static final Logger logger = Logger.getLogger(Demand.class.getName());

        /**
         * The delay (in minutes) for which the slave must be in demand before
         * trying to launch it.
         */
        private final long inDemandDelay;

        /**
         * The delay (in minutes) for which the slave must be idle before taking
         * it offline.
         */
        private final long idleDelay;

        @DataBoundConstructor
        public vSphereDemandRetentionStrategy(long inDemandDelay, long idleDelay) {
            this.inDemandDelay = Math.max(0, inDemandDelay);
            this.idleDelay = Math.max(1, idleDelay);
        }
        
        public long getInDemandDelay() {
            return inDemandDelay;
        }

        /**
         * Getter for property 'idleDelay'.
         *         
         * @return Value for property 'idleDelay'.
         */
        public long getIdleDelay() {
            return idleDelay;
        }

        @Override
        public synchronized long check(vSphereCloudSlaveComputer c) {
            if (c.isOffline() && c.isLaunchSupported()) {
                final HashMap<Computer, Integer> availableComputers = new HashMap<Computer, Integer>();
                for (Computer o : Jenkins.getInstance().getComputers()) {
                    if ((o.isOnline() || o.isConnecting()) && o.isPartiallyIdle()) {
                        final int idleExecutors = o.countIdle();
                        if (idleExecutors > 0) {
                            availableComputers.put(o, idleExecutors);
                        }
                    }
                }

                boolean needComputer = false;
                long demandMilliseconds = 0;
                for (Queue.BuildableItem item : Queue.getInstance().getBuildableItems()) {
                    // can any of the currently idle executors take this task?
                    // assume the answer is no until we can find such an executor
                    boolean needExecutor = true;
                    for (Computer o : Collections.unmodifiableSet(availableComputers.keySet())) {
                        Node otherNode = o.getNode();
                        if (otherNode != null && otherNode.canTake(item) == null) {
                            needExecutor = false;
                            final int availableExecutors = availableComputers.remove(o);
                            if (availableExecutors > 1) {
                                availableComputers.put(o, availableExecutors - 1);
                            } else {
                                availableComputers.remove(o);
                            }
                            break;
                        }
                    }

                    // this 'item' cannot be built by any of the existing idle nodes, but it can be built by 'c'
                    Node checkedNode = c.getNode();
                    if (needExecutor && checkedNode != null && checkedNode.canTake(item) == null) {
                        demandMilliseconds = System.currentTimeMillis() - item.buildableStartMilliseconds;
                        needComputer = demandMilliseconds > inDemandDelay * 1000 * 60 /*MINS->MILLIS*/;
                        break;
                    }
                }

                if (needComputer) {
                    // we've been in demand for long enough
                    logger.log(Level.INFO, "Launching computer {0} as it has been in demand for {1}",
                            new Object[]{c.getName(), Util.getTimeSpanString(demandMilliseconds)});
                    c.connect(false);
                }
            } else if (c.isIdle()) {
                final long idleMilliseconds = System.currentTimeMillis() - c.getIdleStartMilliseconds();
                if (idleMilliseconds > idleDelay * 1000 * 60 /*MINS->MILLIS*/) {
                    // we've been idle for long enough
                    logger.log(Level.INFO, "Disconnecting computer {0} as it has been idle for {1}",
                            new Object[]{c.getName(), Util.getTimeSpanString(idleMilliseconds)});
                    disconnect(c);
                }
            }
            return 1;
        }

        @Extension
        public static class DescriptorImpl extends Descriptor<RetentionStrategy<?>> {

            @Override
            public String getDisplayName() {
                return "";
            }
        }
    }