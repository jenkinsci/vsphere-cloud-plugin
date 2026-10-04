/*   Copyright 2013, MANDIANT, Eric Lordahl
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
package org.jenkinsci.plugins.vsphere.builders;

import static org.jenkinsci.plugins.vsphere.tools.PermissionUtils.throwUnlessUserHasPermissionToConfigureJob;

import com.vmware.vim25.VirtualHardware;
import com.vmware.vim25.VirtualMachineConfigInfo;
import com.vmware.vim25.mo.VirtualMachine;
import edu.umd.cs.findbugs.annotations.NonNull;
import hudson.*;
import hudson.model.Item;
import hudson.model.Run;
import hudson.model.TaskListener;
import hudson.util.FormValidation;
import jakarta.servlet.ServletException;
import java.io.IOException;
import java.io.PrintStream;
import org.jenkinsci.plugins.vsphere.tools.VSphereException;
import org.jenkinsci.plugins.vsphere.tools.VSphereLogger;
import org.kohsuke.stapler.AncestorInPath;
import org.kohsuke.stapler.DataBoundConstructor;
import org.kohsuke.stapler.QueryParameter;
import org.kohsuke.stapler.interceptor.RequirePOST;

public class ReconfigureMemory extends ReconfigureStep {

    private final String memorySize;

    @DataBoundConstructor
    public ReconfigureMemory(String memorySize) throws VSphereException {
        this.memorySize = memorySize;
    }

    public String getMemorySize() {
        return memorySize;
    }

    @Override
    public void perform(@NonNull EnvVars env, @NonNull TaskListener listener) throws VSphereException {
        reconfigureMemory(env, listener);
    }

    @Override
    public void perform(
            @NonNull Run<?, ?> run,
            @NonNull FilePath filePath,
            @NonNull Launcher launcher,
            @NonNull TaskListener listener)
            throws InterruptedException, IOException {
        try {
            reconfigureMemory(run, launcher, listener);
        } catch (Exception e) {
            throw new AbortException(e.getMessage());
        }
    }

    public boolean reconfigureMemory(final Run<?, ?> run, final Launcher launcher, final TaskListener listener)
            throws VSphereException {
        EnvVars env = extractEnvironment(run, listener);

        return reconfigureMemory(env, listener);
    }

    private boolean reconfigureMemory(final EnvVars env, final TaskListener listener) throws VSphereException {
        PrintStream jLogger = listener.getLogger();
        String expandedMemorySize = env.expand(memorySize);

        VSphereLogger.vsLogger(jLogger, "Preparing reconfigure: Memory");
        final Long newMemoryMB = Long.valueOf(expandedMemorySize);
        VSphereLogger.vsLogger(jLogger, describeChange(vm, currentMemoryMB(vm), newMemoryMB));
        spec.setMemoryMB(newMemoryMB);
        VSphereLogger.vsLogger(jLogger, "Finished!");
        return true;
    }

    /** The memory size (MB) the VM is configured with right now, or null if it cannot be told. */
    private static Integer currentMemoryMB(final VirtualMachine vm) {
        try {
            final VirtualMachineConfigInfo config = vm == null ? null : vm.getConfig();
            final VirtualHardware hardware = config == null ? null : config.getHardware();
            return hardware == null ? null : Integer.valueOf(hardware.getMemoryMB());
        } catch (RuntimeException e) {
            return null;
        }
    }

    /** The line logged for the change that is about to be requested. */
    static String describeChange(final VirtualMachine vm, final Integer previousMB, final long newMB) {
        String vmName = null;
        try {
            vmName = vm == null ? null : vm.getName();
        } catch (RuntimeException e) {
            // only used to make the message friendlier
        }
        return "Will set the memory of " + (vmName == null ? "the VM" : "VM \"" + vmName + "\"") + " to " + newMB
                + " MB (" + (previousMB == null ? "previous value unknown" : "currently " + previousMB + " MB") + ")";
    }

    @Extension
    public static final class ReconfigureMemoryDescriptor extends ReconfigureStepDescriptor {

        public ReconfigureMemoryDescriptor() {
            load();
        }

        @RequirePOST
        public FormValidation doCheckMemorySize(@AncestorInPath Item context, @QueryParameter String value)
                throws IOException, ServletException {
            throwUnlessUserHasPermissionToConfigureJob(context);

            return checkPositiveIntegerOrVariable("Memory Size", value, true);
        }

        @Override
        public String getDisplayName() {
            return Messages.vm_title_ReconfigureMemory();
        }

        @RequirePOST
        public FormValidation doTestData(@AncestorInPath Item context, @QueryParameter String memorySize) {
            throwUnlessUserHasPermissionToConfigureJob(context);
            try {
                return doCheckMemorySize(context, memorySize);
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        }
    }
}
