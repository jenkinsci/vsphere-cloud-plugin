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
import hudson.model.BuildListener;
import hudson.model.Item;
import hudson.model.AbstractBuild;
import hudson.model.Run;
import hudson.model.TaskListener;
import hudson.util.FormValidation;

import java.io.IOException;
import java.io.PrintStream;
import java.io.PrintWriter;
import java.io.StringWriter;

import edu.umd.cs.findbugs.annotations.NonNull;

import org.jenkinsci.plugins.vsphere.VSphereBuildStep;
import org.jenkinsci.plugins.vsphere.tools.VSphere;
import org.jenkinsci.plugins.vsphere.tools.VSphereException;
import org.jenkinsci.plugins.vsphere.tools.VSphereLogger;
import org.kohsuke.stapler.AncestorInPath;
import org.kohsuke.stapler.DataBoundConstructor;
import org.kohsuke.stapler.QueryParameter;
import org.kohsuke.stapler.interceptor.RequirePOST;

import com.vmware.vim25.mo.VirtualMachine;
import com.vmware.vim25.mo.VirtualMachineSnapshot;

public class Clone extends VSphereBuildStep {

    private static final int TIMEOUT_DEFAULT = 60;

    private final String sourceName;
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
    public Clone(String sourceName, String clone, boolean linkedClone,
                 String resourcePool, String cluster, String datastore, String folder,
                 boolean powerOn, Integer timeoutInSeconds, String customizationSpec) throws VSphereException {
        this.sourceName = sourceName;
        this.clone = clone;
        this.linkedClone = linkedClone;
        this.resourcePool=resourcePool;
        this.cluster=cluster;
        this.datastore=datastore;
        this.folder=folder;
        this.customizationSpec=customizationSpec;
        this.powerOn=powerOn;
        this.timeoutInSeconds = timeoutInSeconds;
    }

    public String getSourceName() {
        return sourceName;
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
    public void perform(@NonNull Run<?, ?> run, @NonNull FilePath filePath, @NonNull Launcher launcher, @NonNull TaskListener listener) throws InterruptedException, IOException {
        try {
            cloneFromSource(run, launcher, listener);
        } catch (Exception e) {
            throw new AbortException(e.getMessage());
        }
    }

    @Override
    public String getIP() {
        return IP;
    }

    @Override
    public boolean perform(final AbstractBuild<?, ?> build, final Launcher launcher, final BuildListener listener) throws AbortException {
        boolean retVal = false;
        try {
            retVal = cloneFromSource(build, launcher, listener);
        } catch (Exception e) {
            StringWriter sw = new StringWriter();
            PrintWriter pw = new PrintWriter(sw);
            e.printStackTrace(pw);
            VSphereLogger.vsLogger(listener.getLogger(), "Error cloning VM or template\n" + sw.toString());

            try {
                sw.close();
            } catch (Exception ex) {
                // ignore
            }
            throw new AbortException(e.getMessage());
        }
        return retVal;
    }

    private boolean cloneFromSource(final Run<?, ?> run, final Launcher launcher, final TaskListener listener) throws VSphereException {
        PrintStream jLogger = listener.getLogger();
        String expandedClone = clone;
        String expandedSource = sourceName;
        String expandedCluster = cluster;
        String expandedDatastore = datastore;
        String expandedFolder = folder;
        String expandedResourcePool = resourcePool;
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
            expandedSource = env.expand(sourceName);
            expandedCluster = env.expand(cluster);
            expandedDatastore = env.expand(datastore);
            expandedFolder = env.expand(folder);
            expandedResourcePool = env.expand(resourcePool);
            expandedCustomizationSpec = env.expand(customizationSpec);
        }
        vsphere.cloneVm(expandedClone, expandedSource, linkedClone, expandedResourcePool, expandedCluster,
                expandedDatastore, expandedFolder, powerOn, expandedCustomizationSpec, jLogger);
        final int timeoutInSecondsForGetIp = getTimeoutInSeconds();
        if (powerOn && timeoutInSecondsForGetIp>0) {
            VSphereLogger.vsLogger(jLogger, "Powering on VM \""+expandedClone+"\".  Waiting for its IP for the next "+timeoutInSecondsForGetIp+" seconds.");
            IP = vsphere.getIp(vsphere.getVmByName(expandedClone), timeoutInSecondsForGetIp);
        }
        VSphereLogger.vsLogger(jLogger, "\""+expandedClone+"\" successfully cloned " + (powerOn ? "and powered on" : "") + "!");

        return true;
    }

    @Extension
    public static final class CloneDescriptor extends VSphereBuildStepDescriptor {

        public CloneDescriptor() {
            load();
        }

        @Override
        public String getDisplayName() {
            return Messages.vm_title_Clone();
        }

        public static int getDefaultTimeoutInSeconds() {
            return TIMEOUT_DEFAULT;
        }

        public FormValidation doCheckSource(@QueryParameter String value) {
            if (value.length() == 0)
                return FormValidation.error("Please enter the sourceName name");
            return FormValidation.ok();
        }

        public FormValidation doCheckClone(@QueryParameter String value) {
            if (value.length() == 0)
                return FormValidation.error(Messages.validation_required("the clone name"));
            return FormValidation.ok();
        }

        @RequirePOST
        public FormValidation doCheckResourcePool(@AncestorInPath Item context,
                                                  @QueryParameter String value,
                                                  @QueryParameter String serverName,
                                                  @QueryParameter String sourceName) {
            throwUnlessUserHasPermissionToConfigureJob(context);
            try {
                if (serverName == null){
                    return FormValidation.error(Messages.validation_required("serverName"));
                }
                VSphere vsphere = getVSphereCloudByName(serverName, null).vSphereInstance();

                VirtualMachine virtualMachine = vsphere.getVmByName(sourceName);
                if (virtualMachine == null) {
                    return FormValidation.error("The source VM \""+sourceName+"\"was not found cannot check the configuration.");
                }
                if ((virtualMachine.getConfig().template) && (value.length() == 0)) {
                    return FormValidation.error(Messages.validation_required("the resource pool"));
                }
            } catch (VSphereException ve) {
                return FormValidation.error("Cannot connect to vsphere. "+ve.getMessage());
            }
            return FormValidation.ok();
        }

        public FormValidation doCheckCluster(@QueryParameter String value) {
            if (value.length() == 0)
                return FormValidation.error(Messages.validation_required("the cluster"));
            return FormValidation.ok();
        }

        public FormValidation doCheckCustomizationSpec(@QueryParameter String value) {
            return FormValidation.ok();
        }

        public FormValidation doCheckTimeoutInSeconds(@QueryParameter String value) {
            return FormValidation.validateNonNegativeInteger(value);
        }

        @RequirePOST
        public FormValidation doTestData(@AncestorInPath Item context,
                                         @QueryParameter String serverName,
                                         @QueryParameter String sourceName, @QueryParameter String clone,
                                         @QueryParameter String resourcePool, @QueryParameter String cluster,
                                         @QueryParameter String customizationSpec) {
            throwUnlessUserHasPermissionToConfigureJob(context);
            try {
                if (sourceName.length() == 0 || clone.length()==0 || serverName.length()==0
                        || cluster.length()==0 )
                    return FormValidation.error(Messages.validation_requiredValues());

                VSphere vsphere = getVSphereCloudByName(serverName, null).vSphereInstance();

                //TODO what if clone name is variable?
                VirtualMachine cloneVM = vsphere.getVmByName(clone);
                if (cloneVM != null)
                    return FormValidation.error(Messages.validation_exists("clone"));

                if (sourceName.indexOf('$') >= 0)
                    return FormValidation.warning(Messages.validation_buildParameter("sourceName"));

                VirtualMachine vm = vsphere.getVmByName(sourceName);
                if (vm == null)
                    return FormValidation.error(Messages.validation_notFound("sourceName"));

                VirtualMachineSnapshot snap = vm.getCurrentSnapShot();
                if (snap == null)
                    return FormValidation.error(Messages.validation_noSnapshots());

                if(customizationSpec != null && customizationSpec.length() > 0 &&
                        vsphere.getCustomizationSpecByName(customizationSpec) == null) {
                    return FormValidation.error(Messages.validation_notFound("customizationSpec"));
                }

                return FormValidation.ok(Messages.validation_success());
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        }
    }
}
