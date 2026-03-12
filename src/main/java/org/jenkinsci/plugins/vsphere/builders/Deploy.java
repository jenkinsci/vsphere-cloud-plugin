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

import hudson.*;
import hudson.model.*;
import hudson.tasks.BuildStepMonitor;
import hudson.util.FormValidation;

import java.io.IOException;
import java.io.PrintStream;
import java.util.Collection;
import java.util.HashMap;
import java.util.Map;

import edu.umd.cs.findbugs.annotations.NonNull;

import jenkins.tasks.SimpleBuildStep;
import org.jenkinsci.plugins.vsphere.VSphereBuildStep;
import org.jenkinsci.plugins.vsphere.tools.VSphere;
import org.jenkinsci.plugins.vsphere.tools.VSphereException;
import org.jenkinsci.plugins.vsphere.tools.VSphereLogger;
import org.kohsuke.stapler.AncestorInPath;
import org.kohsuke.stapler.DataBoundConstructor;
import org.kohsuke.stapler.QueryParameter;
import org.kohsuke.stapler.interceptor.RequirePOST;

import com.vmware.vim25.mo.VirtualMachine;

public class Deploy extends VSphereBuildStep implements SimpleBuildStep {

    private static final int TIMEOUT_DEFAULT = 60;

    private final String template;
    private final String clone;
    private final boolean linkedClone;
    private final String resourcePool;
    private final String cluster;
    private final String datastore;
    private final String folder;
    private final String customizationSpec;
    private final boolean powerOn;
    /** null means use default, zero or negative means don't even try at all. */
    private final Integer timeoutInSeconds;
    private String IP;

    @DataBoundConstructor
    public Deploy(String template, String clone, boolean linkedClone,
            String resourcePool, String cluster, String datastore, String folder,
            String customizationSpec, Integer timeoutInSeconds, boolean powerOn) throws VSphereException {
        this.template = template;
        this.clone = clone;
        this.linkedClone = linkedClone;
        this.resourcePool= (resourcePool != null) ? resourcePool : "";
        this.cluster=cluster;
        this.datastore=datastore;
        this.folder=folder;
        this.customizationSpec=customizationSpec;
        this.powerOn=powerOn;
        this.timeoutInSeconds = timeoutInSeconds;
    }

    public String getTemplate() {
        return template;
    }

    public String getClone() {
        return clone;
    }

    public boolean isLinkedClone() {
        return linkedClone;
    }

    public String getCluster() {
        return cluster;
    }

    public String getResourcePool() {
        return resourcePool;
    }

    public String getDatastore() {
        return datastore;
    }

    public String getFolder() {
        return folder;
    }

    public String getCustomizationSpec() {
        return customizationSpec;
    }

    public boolean isPowerOn() {
        return powerOn;
    }

    public int getTimeoutInSeconds() {
        if (timeoutInSeconds==null) {
            return TIMEOUT_DEFAULT;
        }
        return timeoutInSeconds.intValue();
    }

    @Override
    public String getIP() {
        return IP;
    }

    @Override
    public void perform(@NonNull Run<?, ?> run, @NonNull FilePath filePath, @NonNull Launcher launcher, @NonNull TaskListener listener) throws InterruptedException, IOException {
        try {
            deployFromTemplate(run, launcher, listener);
        } catch (Exception e) {
            throw new AbortException(e.getMessage());
        }
    }

    @Override
    public boolean prebuild(AbstractBuild<?, ?> abstractBuild, BuildListener buildListener) {
        return false;
    }

    @Override
    public boolean perform(final AbstractBuild<?, ?> build, final Launcher launcher, final BuildListener listener)  {
        boolean retVal = false;
        try {
            retVal = deployFromTemplate(build, launcher, listener);
        } catch (Exception e) {
            e.printStackTrace();
        }
        return retVal;
        //TODO throw AbortException instead of returning value
    }

    @Override
    public Action getProjectAction(AbstractProject<?, ?> abstractProject) {
        return null;
    }

    @Override
    public Collection<? extends Action> getProjectActions(AbstractProject<?, ?> abstractProject) {
        return null;
    }

    @Override
    public BuildStepMonitor getRequiredMonitorService() {
        return null;
    }

    private boolean deployFromTemplate(final Run<?, ?> run, final Launcher launcher, final TaskListener listener) throws VSphereException {
        PrintStream jLogger = listener.getLogger();
        String expandedClone = clone;
        String expandedTemplate = template;
        String expandedCluster = cluster;
        String expandedDatastore = datastore;
                String expandedFolder = folder;
                String expandedCustomizationSpec = customizationSpec;
        EnvVars env;
        try {
            env = run.getEnvironment(listener);
        } catch (Exception e) {
            throw new VSphereException(e);
        }

        if (run instanceof AbstractBuild) {
            env.overrideAll(((AbstractBuild) run).getBuildVariables()); // Add in matrix axes..
            expandedClone = env.expand(clone);
            expandedTemplate = env.expand(template);
            expandedCluster = env.expand(cluster);
            expandedDatastore = env.expand(datastore);
                        expandedFolder = env.expand(folder);
                        expandedCustomizationSpec = env.expand(customizationSpec);
        }

        String resourcePoolName;
        if ("".equals(resourcePool) || (resourcePool.length() == 0)) {
            // Not all installations are using resource pools. But there is always a hidden "Resources" resource
            // pool, even if not visible in the vSphere Client.
            resourcePoolName = "Resources";
        } else {
            resourcePoolName = env.expand(resourcePool);
        }

        vsphere.deployVm(expandedClone, expandedTemplate, linkedClone, resourcePoolName, expandedCluster, expandedDatastore, expandedFolder, powerOn, expandedCustomizationSpec, jLogger);
        VSphereLogger.vsLogger(jLogger, "\""+expandedClone+"\" successfully deployed!");
        if (!powerOn) {
            return true; // don't try to obtain IP if VM isn't being turned on.
        }
        final int timeoutInSecondsForGetIp = getTimeoutInSeconds();
        if (timeoutInSecondsForGetIp<=0) {
            return true; // don't try to obtain IP if disabled
        }
        VSphereLogger.vsLogger(jLogger, "Trying to get the IP address of \""+expandedClone+"\" for the next "+timeoutInSecondsForGetIp+" seconds.");
        IP = vsphere.getIp(vsphere.getVmByName(expandedClone), timeoutInSecondsForGetIp);

        if (IP!=null) {
            VSphereLogger.vsLogger(jLogger, "Successfully retrieved IP for \"" + expandedClone + "\" : " + IP);
            VSphereLogger.vsLogger(jLogger, "Exposing " + IP + " as environment variable VSPHERE_IP");

            if (run instanceof AbstractBuild) {
                VSphereEnvAction envAction = new VSphereEnvAction();
                envAction.add("VSPHERE_IP", IP);
                run.addAction(envAction);
            }
            return true;
        } else {
            VSphereLogger.vsLogger(jLogger, "Error: Timed out after waiting "+timeoutInSecondsForGetIp+" seconds to get IP for \""+expandedClone+"\" ");
            return false;
        }
    }

    @Extension
    public static final class DeployDescriptor extends VSphereBuildStepDescriptor {

        public DeployDescriptor() {
            load();
        }

        @Override
        public String getDisplayName() {
            return Messages.vm_title_Deploy();
        }

        public static int getDefaultTimeoutInSeconds() {
            return TIMEOUT_DEFAULT;
        }

        public FormValidation doCheckTemplate(@QueryParameter String value) {
            if (value.length() == 0)
                return FormValidation.error("Please enter the template name");
            return FormValidation.ok();
        }

        public FormValidation doCheckClone(@QueryParameter String value) {
            if (value.length() == 0)
                return FormValidation.error(Messages.validation_required("the clone name"));
            return FormValidation.ok();
        }

        public FormValidation doCheckResourcePool(@QueryParameter String value) {
            return FormValidation.ok();
        }

        public FormValidation doCheckCluster(@QueryParameter String value) {
            if (value.length() == 0)
                return FormValidation.error(Messages.validation_required("the cluster"));
            return FormValidation.ok();
        }

        public FormValidation doCheckTimeoutInSeconds(@QueryParameter String value) {
            return FormValidation.validateNonNegativeInteger(value);
        }

        @RequirePOST
        public FormValidation doTestData(@AncestorInPath Item context,
                @QueryParameter String serverName,
                @QueryParameter String template, @QueryParameter String clone,
                @QueryParameter String resourcePool, @QueryParameter String cluster) {
            throwUnlessUserHasPermissionToConfigureJob(context);
            try {
                if (template.length() == 0 || clone.length()==0 || serverName.length()==0
                        || cluster.length()==0 )
                    return FormValidation.error(Messages.validation_requiredValues());

                VSphere vsphere = getVSphereCloudByName(serverName, null).vSphereInstance();

                //TODO what if clone name is variable?
                VirtualMachine cloneVM = vsphere.getVmByName(clone);
                if (cloneVM != null)
                    return FormValidation.error(Messages.validation_exists("clone"));

                if (template.indexOf('$') >= 0)
                    return FormValidation.warning(Messages.validation_buildParameter("template"));

                VirtualMachine vm = vsphere.getVmByName(template);
                if (vm == null)
                    return FormValidation.error(Messages.validation_notFound("template"));

                if(!vm.getConfig().template)
                    return FormValidation.error(Messages.validation_notActually("template"));

                return FormValidation.ok(Messages.validation_success());
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        }
    }

    /**
     * This class is used to inject the IP value into the build environment
     * as a variable so that it can be used with other plugins. (Copied from PowerOn builder)
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
