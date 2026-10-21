package org.jenkinsci.plugins;

import hudson.Extension;
import hudson.model.Executor;
import hudson.model.ExecutorListener;
import hudson.model.Job;
import hudson.model.Node;
import hudson.model.Queue;
import hudson.model.Run;
import org.jenkinsci.plugins.vsphere.AgentRecycler;
import org.jenkinsci.plugins.vsphere.AgentUsageRegistry;
import org.jenkinsci.plugins.vsphere.ContinuedTasks;

/**
 * Counts the Pipeline runs that use a {@link vSphereCloudSlave}, which {@link vSphereCloudRunListener} cannot see:
 * the {@code Run} itself is owned by a flyweight executor on the controller and only the {@code Queue.Task} of each
 * {@code node {}} block is executed on the agent. The matching end is {@link vSphereCloudRunListener#onFinalized}.
 */
@Extension
public final class vSphereCloudExecutorListener implements ExecutorListener {

    /** An agent that has used all its builds may have just become free. */
    @Override
    public void taskCompleted(Executor executor, Queue.Task task, long durationMS) {
        lookAtTheAgent(executor);
    }

    @Override
    public void taskCompletedWithProblems(Executor executor, Queue.Task task, long durationMS, Throwable problems) {
        lookAtTheAgent(executor);
    }

    private static void lookAtTheAgent(Executor executor) {
        final Node node = executor.getOwner().getNode();
        if (node instanceof vSphereCloudSlave
                && AgentUsageRegistry.get()
                        .isRetiring(node.getNodeName(), ((vSphereCloudSlave) node).getLimitedTestRunCount())) {
            AgentRecycler.get().scheduleCheck(node.getNodeName());
        }
    }

    @Override
    public void taskStarted(Executor executor, Queue.Task task) {
        // A Job has a Run that vSphereCloudRunListener already counts.
        if (task instanceof Job) {
            return;
        }
        // A block that Pipeline queued again after a restart belongs to a run that was counted before it.
        if (ContinuedTasks.isContinued(task)) {
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
