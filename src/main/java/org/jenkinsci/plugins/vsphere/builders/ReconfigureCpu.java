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

import hudson.*;
import hudson.model.AbstractBuild;
import hudson.model.BuildListener;
import hudson.model.Item;
import hudson.model.Run;
import hudson.model.TaskListener;
import hudson.util.FormValidation;
import org.jenkinsci.plugins.vsphere.tools.VSphereException;
import org.jenkinsci.plugins.vsphere.tools.VSphereLogger;
import org.kohsuke.stapler.AncestorInPath;
import org.kohsuke.stapler.DataBoundConstructor;
import org.kohsuke.stapler.DataBoundSetter;
import org.kohsuke.stapler.QueryParameter;
import org.kohsuke.stapler.interceptor.RequirePOST;

import com.vmware.vim25.ResourceAllocationInfo;

import edu.umd.cs.findbugs.annotations.NonNull;
import jakarta.servlet.ServletException;
import java.io.IOException;
import java.io.PrintStream;

import static org.jenkinsci.plugins.vsphere.tools.PermissionUtils.throwUnlessUserHasPermissionToConfigureJob;

public class ReconfigureCpu extends ReconfigureStep {

    private final String cpuCores;
    private final String coresPerSocket;
    /* almost final */ private String cpuLimitMHz;

	@DataBoundConstructor
	public ReconfigureCpu(String cpuCores, String coresPerSocket) throws VSphereException {
		this.cpuCores = cpuCores;
        this.coresPerSocket = coresPerSocket;
	}

	public String getCpuCores() {
		return cpuCores;
	}

    public String getCoresPerSocket() {
        return coresPerSocket;
    }

    public String getCpuLimitMHz() {
        return cpuLimitMHz;
    }

    /** Optional CPU MHz reservation; leave unset to preserve the previous default (no reservation). */
    @DataBoundSetter
    public void setCpuLimitMHz(String cpuLimitMHz) {
        this.cpuLimitMHz = cpuLimitMHz;
    }

    @Override
    public void perform(@NonNull EnvVars env, @NonNull TaskListener listener) throws VSphereException {
        reconfigureCPU(env, listener);
    }

    @Override
    public void perform(@NonNull Run<?, ?> run, @NonNull FilePath filePath, @NonNull Launcher launcher, @NonNull TaskListener listener) throws InterruptedException, IOException {
        try {
            reconfigureCPU(run, launcher, listener);
        } catch (Exception e) {
            throw new AbortException(e.getMessage());
        }
    }

    @Override
    public boolean perform(final AbstractBuild<?, ?> build, final Launcher launcher, final BuildListener listener)  {
        boolean retVal = false;
        try {
            retVal = reconfigureCPU(build, launcher, listener);
        } catch (Exception e) {
            e.printStackTrace();
        }
        return retVal;
        //TODO throw AbortException instead of returning value
    }

    public boolean reconfigureCPU(final Run<?, ?> run, final Launcher launcher, final TaskListener listener) throws VSphereException  {
        EnvVars env = extractEnvironment(run, listener);

        return reconfigureCPU(env, listener);
    }

    private boolean reconfigureCPU(final EnvVars env, final TaskListener listener) throws VSphereException  {
        PrintStream jLogger = listener.getLogger();
        String expandedCPUCores = env.expand(cpuCores);
        String expandedCoresPerSocket = env.expand(coresPerSocket);
        String expandedCpuLimitMHz = cpuLimitMHz == null ? null : env.expand(cpuLimitMHz);

        VSphereLogger.vsLogger(jLogger, "Preparing reconfigure: CPU");
        spec.setNumCPUs(Integer.valueOf(expandedCPUCores));
        spec.setNumCoresPerSocket(Integer.valueOf(expandedCoresPerSocket));

        // Only set an allocation at all if the user actually asked for a reservation --
        // otherwise preserve the previous default behavior (no CPU reservation/limit).
        if (expandedCpuLimitMHz != null && !expandedCpuLimitMHz.isEmpty()) {
            ResourceAllocationInfo resAllInfo = new ResourceAllocationInfo();
            resAllInfo.setReservation((long) Integer.parseInt(expandedCpuLimitMHz));
            spec.setCpuAllocation(resAllInfo);
        }

        VSphereLogger.vsLogger(jLogger, "Finished!");
        return true;
    }

	@Extension
	public static final class ReconfigureCpuDescriptor extends ReconfigureStepDescriptor {

		public ReconfigureCpuDescriptor() {
			load();
		}

        @RequirePOST
        public FormValidation doCheckCpuCores(@AncestorInPath Item context, @QueryParameter String value)
                throws IOException, ServletException {
            throwUnlessUserHasPermissionToConfigureJob(context);

            if (value.length() == 0)
                return FormValidation.error(Messages.validation_required("CPU Cores"));
            return FormValidation.ok();
        }

        @RequirePOST
        public FormValidation doCheckCoresPerSocket(@AncestorInPath Item context, @QueryParameter String value)
                throws IOException, ServletException {
            throwUnlessUserHasPermissionToConfigureJob(context);

            if (value.length() == 0)
                return FormValidation.error(Messages.validation_required("Cores per socket"));
            return FormValidation.ok();
        }

        @RequirePOST
        public FormValidation doCheckCpuLimitMHz(@AncestorInPath Item context, @QueryParameter String value)
                throws IOException, ServletException {
            throwUnlessUserHasPermissionToConfigureJob(context);

            // Optional field: a blank value just means "no CPU reservation", which is fine.
            if (value == null || value.isEmpty()) {
                return FormValidation.ok();
            }
            try {
                if (Integer.parseInt(value) < 0) {
                    return FormValidation.error(Messages.validation_positiveInteger(value));
                }
            } catch (NumberFormatException e) {
                return FormValidation.error(Messages.validation_positiveInteger(value));
            }
            return FormValidation.ok();
        }

		@Override
		public String getDisplayName() {
			return Messages.vm_title_ReconfigureCpu();
		}

		@RequirePOST
		public FormValidation doTestData(@AncestorInPath Item context, @QueryParameter String cpuCores,
				@QueryParameter String coresPerSocket) {
			throwUnlessUserHasPermissionToConfigureJob(context);
			try {
                if (Integer.valueOf(coresPerSocket) > Integer.valueOf(cpuCores)) {
                    return FormValidation.error(Messages.validation_maxValue(Integer.valueOf(cpuCores)+1));
                }

                return FormValidation.ok();

			} catch (Exception e) {
				throw new RuntimeException(e);
			}
		}
	}
}
