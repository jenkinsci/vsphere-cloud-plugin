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

import hudson.cli.declarative.CLIMethod;
import hudson.model.Executor;
import hudson.model.Queue;
import hudson.model.Slave;
import hudson.slaves.OfflineCause;
import hudson.slaves.SlaveComputer;
import hudson.util.Futures;
import java.io.IOException;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.logging.Logger;
import jenkins.model.Jenkins;
import org.jenkinsci.plugins.vsphere.tools.VSphere;
import org.jenkinsci.plugins.vsphere.tools.VSphereException;
import org.kohsuke.stapler.HttpResponse;
import org.kohsuke.stapler.interceptor.RequirePOST;

/**
 *
 * @author ksmith
 */
public class vSphereCloudSlaveTemplateComputer extends SlaveComputer {
    public static final Logger logger = Logger.getLogger(vSphereCloudSlaveTemplateComputer.class.getName());
    private final vSphereCloudSlave vSlave;
    private final AtomicBoolean hasBegunDisconnecting = new AtomicBoolean(false);
    
    public vSphereCloudSlaveTemplateComputer(Slave slave) {
        super(slave);
        vSlave = (vSphereCloudSlave) slave;
    }
    
    @Override
    public void taskAccepted(Executor executor, Queue.Task task) {
        vSphereCloudSlave.removeAcceptedItem(task);
        if(this.countBusy() + 1 >= this.countExecutors()) {
            this.setAcceptingTasks(false);
        }
        super.taskAccepted(executor,task);
    }
    
    @Override
    public boolean isConnecting() {
        return (vSlave.slaveIsStarting == Boolean.TRUE) || super.isConnecting();
    }
    
    @Override
    public Future<?> disconnect(OfflineCause cause) {
        final Future<?> future;
        if(!this.hasBegunDisconnecting.get()) {
            if(this.getTerminatedBy().isEmpty()) {
                try {
                    Jenkins.getInstance().removeNode(vSlave);
                } catch (IOException ex) {
                    logger.throwing(vSphereCloudSlaveTemplateComputer.class.getName(), "disconnect", ex);
                }
            }
            future = Futures.precomputed(null);
        } else {
            future = super.disconnect(cause);
        }
        this.hasBegunDisconnecting.compareAndSet(false, true);
        return future;
    }
    
    @Override
    protected void onRemoved() {
        // Destroy instance
        //super.onRemoved(); // does nothing
        logger.info("onRemoved is being called on a template.");
        unProvision();
    }

    @CLIMethod(name="delete-node")
    @RequirePOST
    @Override
    public HttpResponse doDoDelete() throws IOException {
        final HttpResponse response = super.doDoDelete();
        logger.info("doDoDelete is being called on.");
        unProvision();
        return response;
    }
    
    protected void unProvision() {
        final vSphereCloudLauncher launcher = (vSphereCloudLauncher) getLauncher();
        if(launcher != null) {
            final vSphereCloud cloud = launcher.findOurVsInstance();
            
            if(cloud != null) {
                VSphere vSphere = null;
                try {
                    vSphere = cloud.vSphereInstance();
                    vSphere.destroyVm(nodeName, false);
                } catch (VSphereException ex) {
                    java.util.logging.Logger.getLogger(vSphereCloudSlaveComputer.class.getName()).log(java.util.logging.Level.SEVERE, null, ex);
                } finally {
                    if(vSphere != null) {
                        vSphere.disconnect();
                    }
                }
            }
        }        
    }
}
