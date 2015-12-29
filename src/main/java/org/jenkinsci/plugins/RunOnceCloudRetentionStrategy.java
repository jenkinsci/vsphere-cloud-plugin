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

package org.jenkinsci.plugins;

import hudson.model.Computer;
import hudson.model.Descriptor;
import hudson.model.Executor;
import hudson.model.ExecutorListener;
import hudson.model.Queue;
import hudson.slaves.AbstractCloudComputer;
import hudson.slaves.AbstractCloudSlave;
import hudson.slaves.CloudRetentionStrategy;
import hudson.slaves.RetentionStrategy;
import hudson.util.TimeUnit2;
import java.io.IOException;
import java.util.logging.Level;
import java.util.logging.Logger;
import org.kohsuke.accmod.Restricted;
import org.kohsuke.accmod.restrictions.NoExternalUse;
import org.kohsuke.stapler.DataBoundConstructor;

/**
 *
 * @author ksmith
 */
public class RunOnceCloudRetentionStrategy extends CloudRetentionStrategy implements ExecutorListener {
    
    public static final Logger logger = Logger.getLogger(RunOnceCloudRetentionStrategy.class.getName());
    
    private int idleMinutes = 10;
    private transient boolean terminating;
    
    @DataBoundConstructor
    public RunOnceCloudRetentionStrategy(int idleMinutes) {
        super(idleMinutes);
        this.idleMinutes = idleMinutes;
    }
    
    public int getIdleMinutes() {
        return idleMinutes;
    }
    
    @Override
    public long check(final AbstractCloudComputer c) {
        if(c.isIdle() && !disabled) {
            final long idleMilliseconds = System.currentTimeMillis() - c.getIdleStartMilliseconds();
            if(idleMilliseconds > TimeUnit2.MINUTES.toMillis(idleMinutes)) {
                logger.log(Level.FINE, "Disconnecting {0}", c.getName());
                done(c);
            }
        }
        return 1;
    }
    
    @Override
    public void taskAccepted(final Executor executor, final Queue.Task task) {
        
    }
    
    @Override
    public void taskCompleted(final Executor executor, final Queue.Task task, final long durationMS) {
        done(executor);
    }
    
    @Override
    public void taskCompletedWithProblems(final Executor executor, final Queue.Task task, final long durationMS, final Throwable problems) {
        done(executor);
    }
    
    private void done(final Executor executor) {
        final AbstractCloudComputer<?> c = (AbstractCloudComputer) executor.getOwner();
        final Queue.Executable exec = executor.getCurrentExecutable();
        logger.log(Level.FINE, "terminating {0} since {1} seems to be finished", new Object[]{c.getName(),exec});
        done(c);
    }
    
    private void done(final AbstractCloudComputer<?> c) {
        c.setAcceptingTasks(false);
        synchronized(this) {
            if(terminating) {
                return;
            }
            terminating = true;
        }
        Computer.threadPoolForRemoting.submit(new Runnable() {
            @Override
            public void run() {
                Queue.withLock(new Runnable() {
                    @Override
                    public void run() {
                        try {
                            AbstractCloudSlave node = c.getNode();
                            if(node != null) {
                                node.terminate();
                            }
                        } catch(InterruptedException e) {
                            logger.log(Level.WARNING, "Failed to terminate " + c.getName(), e);
                            synchronized(RunOnceCloudRetentionStrategy.this) {
                                terminating = false;
                            }
                        } catch(IOException e) { 
                            logger.log(Level.WARNING, "Failed to terminate " + c.getName(), e);
                            synchronized(RunOnceCloudRetentionStrategy.this) {
                                terminating = false;
                            }
                        }
                    }
                });
            }
        });
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
            return "Run Once Cloud Retention Strategy";
        }
    }
}   

