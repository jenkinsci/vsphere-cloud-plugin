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
import hudson.model.Run;
import hudson.model.TaskListener;
import hudson.util.FormValidation;
import org.jenkinsci.plugins.vsphere.tools.VSphereException;
import org.jenkinsci.plugins.vsphere.tools.VSphereLogger;
import org.kohsuke.stapler.DataBoundConstructor;
import org.kohsuke.stapler.QueryParameter;

import edu.umd.cs.findbugs.annotations.NonNull;
import javax.servlet.ServletException;
import java.io.IOException;
import java.io.PrintStream;

public class ReconfigureCpu extends ReconfigureStep {

    private final String cpuCores;
    private final String coresPerSocket;

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

    public boolean reconfigureCPU (final Run<?, ?> run, final Launcher launcher, final TaskListener listener) throws VSphereException  {

        PrintStream jLogger = listener.getLogger();
        String expandedCPUCores = cpuCores;
        String expandedCoresPerSocket = coresPerSocket;
        EnvVars env;
        try {
            env = run.getEnvironment(listener);
        } catch (Exception e) {
            throw new VSphereException(e);
        }

        if (run instanceof AbstractBuild) {
            env.overrideAll(((AbstractBuild) run).getBuildVariables()); // Add in matrix axes..
            expandedCPUCores = env.expand(cpuCores);
            expandedCoresPerSocket = env.expand(coresPerSocket);
        }

        VSphereLogger.vsLogger(jLogger, "Preparing reconfigure: CPU");
        spec.setNumCPUs(Integer.valueOf(expandedCPUCores));
        spec.setNumCoresPerSocket(Integer.valueOf(expandedCoresPerSocket));

        VSphereLogger.vsLogger(jLogger, "Finished!");
        return true;
	}


	@Extension
	public static final class ReconfigureCpuDescriptor extends ReconfigureStepDescriptor {

		public ReconfigureCpuDescriptor() {
			load();
		}

        public FormValidation doCheckCpuCores(@QueryParameter String value)
                throws IOException, ServletException {

            if (value.length() == 0)
                return FormValidation.error(Messages.validation_required("CPU Cores"));
            return FormValidation.ok();
        }

        public FormValidation doCheckCoresPerSocket(@QueryParameter String value)
                throws IOException, ServletException {

            if (value.length() == 0)
                return FormValidation.error(Messages.validation_required("Cores per socket"));
            return FormValidation.ok();
        }

		@Override
		public String getDisplayName() {
			return Messages.vm_title_ReconfigureCpu();
		}

		public FormValidation doTestData(@QueryParameter String cpuCores, @QueryParameter String coresPerSocket) {
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
