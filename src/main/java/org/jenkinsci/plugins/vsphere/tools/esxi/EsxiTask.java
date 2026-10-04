package org.jenkinsci.plugins.vsphere.tools.esxi;

import com.vmware.vim25.LocalizedMethodFault;
import com.vmware.vim25.ManagedObjectReference;
import com.vmware.vim25.TaskInfo;
import com.vmware.vim25.TaskInfoState;
import com.vmware.vim25.mo.Task;
import edu.umd.cs.findbugs.annotations.CheckForNull;
import java.util.concurrent.atomic.AtomicLong;

/**
 * What the operations on a VM of a standalone ESXi host return where vCenter would return a task. The host is
 * asked to do the thing straight away (the {@code vim-cmd} commands wait until they are done), so the task is
 * finished from the start: it either succeeded or has the host's explanation of what went wrong.
 */
public final class EsxiTask extends Task {

    private static final AtomicLong COUNTER = new AtomicLong();

    private final TaskInfo info;

    EsxiTask(String description, @CheckForNull String errorMessage) {
        super(null, reference());
        info = new TaskInfo();
        info.setKey(getMOR().getVal());
        info.setDescriptionId(description);
        if (errorMessage == null) {
            info.setState(TaskInfoState.success);
        } else {
            info.setState(TaskInfoState.error);
            final LocalizedMethodFault fault = new LocalizedMethodFault();
            fault.setLocalizedMessage(errorMessage);
            info.setError(fault);
        }
    }

    private static ManagedObjectReference reference() {
        final ManagedObjectReference mor = new ManagedObjectReference();
        mor.setType("Task");
        mor.setVal("esxi-ssh-task-" + COUNTER.incrementAndGet());
        return mor;
    }

    @Override
    public TaskInfo getTaskInfo() {
        return info;
    }

    @Override
    public String waitForTask() {
        return info.getState().toString();
    }

    @Override
    public String waitForTask(long maxWaitMillis) {
        return waitForTask();
    }

    @Override
    public String waitForTask(int runningDelayInMillisecond, int queuedDelayInMillisecond) {
        return waitForTask();
    }

    @Override
    public String waitForTask(int runningDelayInMillisecond, int queuedDelayInMillisecond, long maxWaitMillis) {
        return waitForTask();
    }

    /** The default one asks the (missing) connection for its URL. */
    @Override
    public String toString() {
        return "task " + info.getKey() + " (" + info.getState() + ")";
    }

    @Override
    public void cancelTask() {
        // nothing is running
    }
}
