package org.jenkinsci.plugins;

import hudson.Extension;
import hudson.model.Executor;
import hudson.model.ExecutorListener;
import hudson.model.Job;
import hudson.model.Node;
import hudson.model.Queue;
import hudson.model.Run;

/**
 * Counts the Pipeline runs that use a {@link vSphereCloudSlave}, which {@link vSphereCloudRunListener} cannot see:
 * the {@code Run} itself is owned by a flyweight executor on the controller and only the {@code Queue.Task} of each
 * {@code node {}} block is executed on the agent. The matching end is {@link vSphereCloudRunListener#onFinalized}.
 */
@Extension
public final class vSphereCloudExecutorListener implements ExecutorListener {

    @Override
    public void taskStarted(Executor executor, Queue.Task task) {
        // A Job has a Run that vSphereCloudRunListener already counts.
        if (task instanceof Job) {
            return;
        }
        final Node node = executor.getOwner().getNode();
        if (!(node instanceof vSphereCloudSlave)) {
            return;
        }
        final Queue.Executable pipelineRun = task.getOwnerExecutable();
        if (pipelineRun instanceof Run) {
            ((vSphereCloudSlave) node).StartLimitedPipelineRun(executor, (Run) pipelineRun);
        }
    }
}
