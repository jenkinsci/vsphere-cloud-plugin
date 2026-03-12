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

import com.vmware.vim25.mo.VirtualMachine;
import hudson.*;
import hudson.model.*;
import hudson.util.FormValidation;
import org.jenkinsci.plugins.vsphere.VSphereBuildStep;
import org.jenkinsci.plugins.vsphere.tools.VSphere;
import org.jenkinsci.plugins.vsphere.tools.VSphereException;
import org.jenkinsci.plugins.vsphere.tools.VSphereLogger;
import org.kohsuke.stapler.AncestorInPath;
import org.kohsuke.stapler.DataBoundConstructor;
import org.kohsuke.stapler.QueryParameter;
import org.kohsuke.stapler.interceptor.RequirePOST;

import edu.umd.cs.findbugs.annotations.NonNull;

import java.io.IOException;
import java.io.PrintStream;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;

public class PowerOn extends VSphereBuildStep {

	private final String vm;    
	private final int timeoutInSeconds;
	private String IP;

	@DataBoundConstructor
	public PowerOn(final String vm, final int timeoutInSeconds) throws VSphereException {
		this.vm = vm;
		this.timeoutInSeconds = timeoutInSeconds;
	}

	public String getVm() {
		return vm;
	}

	public int getTimeoutInSeconds() {
		return timeoutInSeconds;
	}

	@Override
	public String getIP() {
		return IP;
	}

	@Override
	public void perform(@NonNull Run<?, ?> run, @NonNull FilePath filePath, @NonNull Launcher launcher, @NonNull TaskListener listener) throws InterruptedException, IOException {
		try {
			powerOn(run, launcher, listener);
		} catch (Exception e) {
			throw new AbortException(e.getMessage());
		}
	}

	@Override
	public boolean perform(final AbstractBuild<?, ?> build, Launcher launcher, final BuildListener listener) {
		boolean retVal = false;
		try {
			retVal = powerOn(build, launcher, listener);
		} catch (Exception e) {
			e.printStackTrace();
		}
		return retVal;
		//TODO throw AbortException instead of returning value
	}

	private boolean powerOn(final Run<?, ?> run, Launcher launcher, final TaskListener listener) throws VSphereException, IOException, InterruptedException {
		PrintStream jLogger = listener.getLogger();
		EnvVars env;
		String expandedVm = vm;

		env = run.getEnvironment(listener);

		if (run instanceof AbstractBuild) {
			env.overrideAll(((AbstractBuild)run).getBuildVariables()); // Add in matrix axes..
			expandedVm = env.expand(vm);
		}

        long startTimeNanos = System.nanoTime();
        vsphere.startVm(expandedVm, timeoutInSeconds);
        long elapsedTime = TimeUnit.SECONDS.convert(System.nanoTime() - startTimeNanos, TimeUnit.NANOSECONDS);

        int secondsToWaitForIp = (int) (timeoutInSeconds - elapsedTime);

		IP = vsphere.getIp(vsphere.getVmByName(expandedVm), secondsToWaitForIp);

		if(IP==null){
			VSphereLogger.vsLogger(jLogger, "Error: Timed out after waiting " + secondsToWaitForIp + " seconds to get IP for \""+expandedVm+"\" ");
			return false;
		}

		VSphereLogger.vsLogger(jLogger, "Successfully retrieved IP for \""+expandedVm+"\" : "+IP);

        // useful to tell user about the environment variable
        VSphereLogger.vsLogger(jLogger, "Exposing " + IP + " as environment variable VSPHERE_IP");

		if (run instanceof AbstractBuild) {
			VSphereEnvAction envAction = new VSphereEnvAction();
			envAction.add("VSPHERE_IP", IP);
			run.addAction(envAction);
		} else {
			env.put("VSPHERE_IP", IP);
		}

		return true;
	}

	@Extension
	public static class PowerOnDescriptor extends VSphereBuildStepDescriptor {

		@Override
		public String getDisplayName() {
			return Messages.vm_title_PowerOn();
		}

		public FormValidation doCheckTimeoutInSeconds(@QueryParameter String value) {
			if (value.length() == 0)
				return FormValidation.error(Messages.validation_required("Timeout"));

			if (!value.matches("\\d+"))
				return FormValidation.error(Messages.validation_positiveInteger("Timeout"));

			if (Integer.parseInt(value)>3600)
				return FormValidation.error(Messages.validation_maxValue(3600));

			return FormValidation.ok();
		}

		public FormValidation doCheckVm(@QueryParameter String value) {
			if (value.length() == 0)
				return FormValidation.error(Messages.validation_required("the VM name"));
			return FormValidation.ok();
		}

        @RequirePOST
		public FormValidation doTestData(@AncestorInPath Item context,
                @QueryParameter String serverName,
				@QueryParameter String vm) {
            throwUnlessUserHasPermissionToConfigureJob(context);
			try {

				if (vm.length() == 0 || serverName.length()==0)
					return FormValidation.error(Messages.validation_requiredValues());

				VSphere vsphere = getVSphereCloudByName(serverName, null).vSphereInstance();

				if (vm.indexOf('$') >= 0)
					return FormValidation.warning(Messages.validation_buildParameter("VM"));

				VirtualMachine vmObj = vsphere.getVmByName(vm);
				if ( vmObj == null)
					return FormValidation.error(Messages.validation_notFound("VM"));

				if (vmObj.getConfig().template)
					return FormValidation.error(Messages.validation_notActually("VM"));

				return FormValidation.ok(Messages.validation_success());
			} catch (Exception e) {
				throw new RuntimeException(e);
			}
		}
	}
	/**
	 * This class is used to inject the IP value into the build environment
	 * as a variable so that it can be used with other plugins.
	 * 
	 * @author Lordahl
	 */
	private static class VSphereEnvAction implements EnvironmentContributingAction {
		// Decided not to record this data in build.xml, so marked transient:
		private final transient Map<String,String> data = new HashMap<String,String>();

		private void add(String key, String val) {
			if (data==null) return;
			data.put(key, val);
		}

		@Override
		public void buildEnvVars(AbstractBuild<?,?> build, EnvVars env) {
			if (data!=null) env.putAll(data);
		}

		@Override
		public String getIconFileName() { return null; }
		@Override
		public String getDisplayName() { return null; }
		@Override
		public String getUrlName() { return null; }
	}
}
