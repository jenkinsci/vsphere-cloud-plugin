/*
 * To change this template, choose Tools | Templates
 * and open the template in the editor.
 */
package org.jenkinsci.plugins;

import hudson.model.Node;
import hudson.model.Run;
import hudson.model.TaskListener;
import hudson.model.listeners.RunListener;
import hudson.Extension;
import hudson.model.AbstractBuild;
import java.util.ArrayList;
import java.util.List;


/**
 *
 * @author Admin
 */
@Extension
public final class vSphereCloudRunListener extends RunListener<Run> {
    
    private List<Run> LimitedRuns = new ArrayList<Run>();

    public vSphereCloudRunListener() {
    }
    
    @Override
    public void onStarted(Run r, TaskListener listener) {
        super.onStarted(r, listener);
        Node node = r.getExecutor().getOwner().getNode();
        if (node instanceof vSphereCloudSlave) {
            LimitedRuns.add(r);
            vSphereCloudSlave s = (vSphereCloudSlave)node;
            s.StartLimitedTestRun(r, listener);
        }        
    }

    @Override
    public void onFinalized(Run r) {
        super.onFinalized(r);
        if (LimitedRuns.contains(r)) {
            LimitedRuns.remove(r);
            Node node = r.getExecutor().getOwner().getNode();
            if (node instanceof vSphereCloudSlave) {
                vSphereCloudSlave s = (vSphereCloudSlave)node;
                s.EndLimitedTestRun(r);
            }                    
        }
    }
    
    
}


