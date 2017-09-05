/*
 * To change this template, choose Tools | Templates
 * and open the template in the editor.
 */
package org.jenkinsci.plugins;
 
import hudson.Extension;
import hudson.model.TaskListener;
import hudson.model.Computer;
import hudson.model.Executor;
import hudson.model.Node;
import hudson.model.Run;
import hudson.model.listeners.RunListener;

import java.util.ArrayList;
import java.util.List;


/**
 *
 * @author Admin
 */
@Extension
public final class vSphereCloudRunListener extends RunListener<Run> {
    
    private final List<Run> LimitedRuns = new ArrayList<Run>();

    public vSphereCloudRunListener() {
    }
    
    @Override
    public void onStarted(Run r, TaskListener listener) {
        super.onStarted(r, listener);
        if (r != null) {
            Executor exec = r.getExecutor();
            if (exec != null) {
                Computer owner = exec.getOwner();
                Node node = owner.getNode();
                if ((node != null) && (node instanceof vSphereCloudSlave)) {
                    LimitedRuns.add(r);
                    vSphereCloudSlave s = (vSphereCloudSlave)node;
                    s.StartLimitedTestRun(r, listener);
                }
            }
        }
    }

    @Override
    public void onFinalized(Run r) {
        super.onFinalized(r);
        if (LimitedRuns.contains(r)) {
            LimitedRuns.remove(r);
            Executor executor = r.getExecutor();
            if(executor != null) {
                Node node = executor.getOwner().getNode();
                if (node instanceof vSphereCloudSlave) {
                    vSphereCloudSlave s = (vSphereCloudSlave)node;
                    s.EndLimitedTestRun(r);
                }                    
            }
        }
    }
}


