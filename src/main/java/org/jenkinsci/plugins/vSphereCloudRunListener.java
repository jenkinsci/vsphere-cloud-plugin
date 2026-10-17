/*
 * To change this template, choose Tools | Templates
 * and open the template in the editor.
 */
package org.jenkinsci.plugins;

import hudson.Extension;
import hudson.model.Computer;
import hudson.model.Executor;
import hudson.model.Node;
import hudson.model.Run;
import hudson.model.TaskListener;
import hudson.model.listeners.RunListener;

/**
 *
 * @author Admin
 */
@Extension
public final class vSphereCloudRunListener extends RunListener<Run> {

    public vSphereCloudRunListener() {}

    @Override
    public void onStarted(Run r, TaskListener listener) {
        super.onStarted(r, listener);
        if (r != null) {
            Executor exec = r.getExecutor();
            if (exec != null) {
                Computer owner = exec.getOwner();
                Node node = owner.getNode();
                if ((node != null) && (node instanceof vSphereCloudSlave)) {
                    vSphereCloudSlave s = (vSphereCloudSlave) node;
                    s.StartLimitedTestRun(r, listener);
                }
            }
        }
    }

    @Override
    public void onFinalized(Run r) {
        super.onFinalized(r);
        // A freestyle build is counted in onStarted(), a Pipeline run by vSphereCloudExecutorListener.
        vSphereCloudSlave.EndLimitedRun(r);
    }
}
