/*
 * Copyright 2015 ksmith.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.jenkinsci.plugins.vsphere;

import hudson.model.ExecutorListener;
import hudson.model.Computer;
import hudson.model.Descriptor;
import hudson.model.Executor;
import hudson.model.Queue;
import hudson.slaves.AbstractCloudComputer;
import hudson.slaves.AbstractCloudSlave;
import hudson.slaves.CloudRetentionStrategy;
import hudson.slaves.EphemeralNode;
import hudson.slaves.RetentionStrategy;
import hudson.util.FormValidation;
import hudson.util.TimeUnit2;

import java.io.IOException;
import java.util.HashSet;
import java.util.Set;
import java.util.logging.Level;
import java.util.logging.Logger;

import org.kohsuke.accmod.Restricted;
import org.kohsuke.accmod.restrictions.NoExternalUse;
import org.kohsuke.stapler.DataBoundConstructor;
import org.kohsuke.stapler.QueryParameter;

/**
 *
 * @author ksmith
 */
public class RunOnceCloudRetentionStrategy extends CloudRetentionStrategy implements ExecutorListener {

    private static final Logger LOGGER = Logger.getLogger(RunOnceCloudRetentionStrategy.class.getName());

    private final int idleMinutes;

    @DataBoundConstructor
    public RunOnceCloudRetentionStrategy(int idleMinutes) {
        super(idleMinutes);
        this.idleMinutes = idleMinutes;
    }

    public int getIdleMinutes() {
        return idleMinutes;
    }

    @SuppressWarnings("rawtypes")
    @Override
    public long check(final AbstractCloudComputer c) {
        if (c.isIdle() && !disabled) {
            final long idleMilliseconds = System.currentTimeMillis() - c.getIdleStartMilliseconds();
            if (idleMilliseconds > TimeUnit2.MINUTES.toMillis(idleMinutes)) {
                LOGGER.log(
                        Level.FINE,
                        "Disconnecting {0} because it has been idle for more than {1} minutes (has been idle for {2}ms)",
                        new Object[] { c.getName(), idleMinutes, idleMilliseconds });
                done(c);
            }
        }
        return 1; // re-check in 1 minute
    }

    @SuppressWarnings("rawtypes")
    @Override
    public void start(AbstractCloudComputer c) {
        if (c.getNode() instanceof EphemeralNode) {
            throw new IllegalStateException("May not use " + RunOnceCloudRetentionStrategy.class.getSimpleName()
                    + " on an " + EphemeralNode.class.getSimpleName() + ": " + c);
        }
        super.start(c);
    }

    @Override
    public void taskAccepted(final Executor executor, final Queue.Task task) {
    }

    @Override
    public void taskCompleted(final Executor executor, final Queue.Task task, final long durationMS) {
        done(executor);
    }

    @Override
    public void taskCompletedWithProblems(final Executor executor, final Queue.Task task, final long durationMS,
            final Throwable problems) {
        done(executor);
    }

    private void done(final Executor executor) {
        final AbstractCloudComputer<?> c = (AbstractCloudComputer<?>) executor.getOwner();
        final Queue.Executable exec = executor.getCurrentExecutable();
        LOGGER.log(Level.FINE, "terminating {0} since {1} seems to be finished", new Object[] { c.getName(), exec });
        done(c);
    }

    private void done(final AbstractCloudComputer<?> c) {
        c.setAcceptingTasks(false);
        final String cname = c.getName();
        synchronized (this) {
            if (isBeingTerminated(c)) {
                LOGGER.log(Level.FINER, "Termination of {0} is already in progress.", cname);
                return;
            }
            LOGGER.log(Level.FINER, "Initiating termination of {0}.", cname);
            setBeingTerminated(c);
        }
        Computer.threadPoolForRemoting.submit(new Runnable() {
            @Override
            public void run() {
                Queue.withLock(new Runnable() {
                    @Override
                    public void run() {
                        try {
                            AbstractCloudSlave node = c.getNode();
                            if (node != null) {
                                LOGGER.log(Level.FINER, "Terminating {0} node {1}.", new Object[] { cname, node });
                                node.terminate();
                            } else {
                                LOGGER.log(Level.FINER,
                                        "Not terminating {0} as its corresponding node has already been removed.",
                                        cname);
                            }
                        } catch (InterruptedException e) {
                            LOGGER.log(Level.WARNING, "Failed to terminate " + cname, e);
                            synchronized (RunOnceCloudRetentionStrategy.this) {
                                clearBeingTerminated(c);
                            }
                        } catch (IOException e) {
                            LOGGER.log(Level.WARNING, "Failed to terminate " + cname, e);
                            synchronized (RunOnceCloudRetentionStrategy.this) {
                                clearBeingTerminated(c);
                            }
                        }
                    }
                });
            }
        });
    }

    /**
     * One {@link RunOnceCloudRetentionStrategy} can be shared across multiple
     * slaves, so we need to track who's being terminated individually instead
     * of having a single boolean.
     */
    private transient Set<AbstractCloudComputer<?>> beingTerminated;

    private boolean isBeingTerminated(AbstractCloudComputer<?> c) {
        if (beingTerminated == null) {
            return false;
        }
        return beingTerminated.contains(c);
    }

    private void setBeingTerminated(AbstractCloudComputer<?> c) {
        if (beingTerminated == null) {
            beingTerminated = new HashSet<AbstractCloudComputer<?>>();
        }
        beingTerminated.add(c);
    }

    private void clearBeingTerminated(AbstractCloudComputer<?> c) {
        if (beingTerminated == null) {
            return;
        }
        beingTerminated.remove(c);
    }

    @Override
    public DescriptorImpl getDescriptor() {
        return DESCRIPTOR;
    }

    @Restricted(NoExternalUse.class)
    public static final DescriptorImpl DESCRIPTOR = new DescriptorImpl();

    public static final class DescriptorImpl extends Descriptor<RetentionStrategy<?>> {
        @Override
        public String getDisplayName() {
            return "vSphere Run-Once Retention Strategy";
        }
    }
}
