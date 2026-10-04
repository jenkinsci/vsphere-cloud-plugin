/*   Copyright 2026, Jim Klimov
 *
 *   Licensed under the Apache License, Version 2.0 (the "License");
 *   you may not use this file except in compliance with the License.
 *   You may obtain a copy of the License at
 *
 *       http://www.apache.org/licenses/LICENSE-2.0
 *
 *   Unless required by applicable law or agreed to in writing, software
 *   distributed under the License is distributed on an "AS IS" BASIS,
 *   WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *   See the License for the specific language governing permissions and
 *   limitations under the License.
 */
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
