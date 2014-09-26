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

import com.vmware.vim25.ResourceAllocationInfo;
import com.vmware.vim25.SharesInfo;
import com.vmware.vim25.VirtualDevice;
import com.vmware.vim25.VirtualEthernetCard;
import hudson.EnvVars;
import hudson.Extension;
import hudson.Launcher;
import hudson.model.AbstractBuild;
import hudson.model.BuildListener;
import hudson.util.FormValidation;
import org.jenkinsci.plugins.vsphere.tools.VSphereException;
import org.jenkinsci.plugins.vsphere.tools.VSphereLogger;
import org.kohsuke.stapler.DataBoundConstructor;
import org.kohsuke.stapler.QueryParameter;

import javax.servlet.ServletException;
import java.io.IOException;
import java.io.PrintStream;

public class ReconfigureMemory extends ReconfigureStep {

    private final String memorySize;

	@DataBoundConstructor
	public ReconfigureMemory(String memorySize) throws VSphereException {
		this.memorySize = memorySize;
	}

	public String getMemorySize() {
		return memorySize;
	}

	public boolean perform(final AbstractBuild<?, ?> build, final Launcher launcher, final BuildListener listener) throws VSphereException  {

        PrintStream jLogger = listener.getLogger();
        EnvVars env;
        try {
            env = build.getEnvironment(listener);
        } catch (Exception e) {
            throw new VSphereException(e);
        }
        env.overrideAll(build.getBuildVariables());
        String expandedMemorySize = env.expand(memorySize);

        VSphereLogger.vsLogger(jLogger, "Preparing reconfigure: Memory");
        spec.setMemoryMB(Long.valueOf(expandedMemorySize));
        VSphereLogger.vsLogger(jLogger, "Finished!");
        return true;
	}

	@Extension
	public static final class ReconfigureMemoryDescriptor extends ReconfigureStepDescriptor {

		public ReconfigureMemoryDescriptor() {
			load();
		}

        public FormValidation doCheckMemorySize(@QueryParameter String value)
                throws IOException, ServletException {

            if (value.length() == 0)
                return FormValidation.error(Messages.validation_required("Memory Size"));
            return FormValidation.ok();
        }

		@Override
		public String getDisplayName() {
			return Messages.vm_title_ReconfigureMemory();
		}

		public FormValidation doTestData(@QueryParameter String memorySize) {
			try {
				return doCheckMemorySize(memorySize);
			} catch (Exception e) {
				throw new RuntimeException(e);
			}
		}
	}
}
