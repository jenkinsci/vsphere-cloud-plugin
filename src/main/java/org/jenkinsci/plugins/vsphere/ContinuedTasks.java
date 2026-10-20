package org.jenkinsci.plugins.vsphere;

import hudson.model.Queue;
import org.jenkinsci.plugins.durabletask.executors.ContinuedTask;
import org.kohsuke.accmod.Restricted;
import org.kohsuke.accmod.restrictions.NoExternalUse;

/** Tells the tasks that Pipeline queued again after a restart. */
@Restricted(NoExternalUse.class)
public final class ContinuedTasks {
    private ContinuedTasks() {}

    /** The task goes on with work that started before Jenkins was restarted. */
    public static boolean isContinued(Queue.Task task) {
        return task instanceof ContinuedTask && ((ContinuedTask) task).isContinued();
    }
}
