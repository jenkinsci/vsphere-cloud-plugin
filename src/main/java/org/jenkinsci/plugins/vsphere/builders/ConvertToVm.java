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

public class ConvertToVm extends VSphereBuildStep {

    private final String template;
    private final String resourcePool;
    private final String cluster;

    @DataBoundConstructor
    public ConvertToVm(String template, String resourcePool, String cluster) throws VSphereException {
        this.template = template;
        this.resourcePool = resourcePool;
        this.cluster = cluster;
    }

    public String getTemplate() {
        return template;
    }

    public String getCluster() {
        return cluster;
    }

    public String getResourcePool() {
        return resourcePool;
    }

    @Override
    public void perform(@NonNull Run<?, ?> run, @NonNull FilePath filePath, @NonNull Launcher launcher, @NonNull TaskListener listener) throws InterruptedException, IOException {
        try {
            convert(run, launcher, listener);
        } catch (Exception e) {
            throw new AbortException(e.getMessage());
        }
    }

    @Override
    public boolean perform(final AbstractBuild<?, ?> build, final Launcher launcher, final BuildListener listener)  {
        boolean retVal = false;
        try {
            retVal = convert(build, launcher, listener);
        } catch (Exception e) {
            e.printStackTrace();
        }
        return retVal;
        //TODO throw AbortException instead of returning value
    }

    private boolean convert(final Run<?, ?> run, final Launcher launcher, final TaskListener listener) throws VSphereException {
        PrintStream jLogger = listener.getLogger();
        VSphereLogger.vsLogger(jLogger, "Converting template to VM. Please wait ...");
        String expandedTemplate = template;
        String expandedCluster = cluster;
        String expandedResourcePool = resourcePool;
        EnvVars env;
        try {
            env = run.getEnvironment(listener);
        } catch (Exception e) {
            throw new VSphereException(e);
        }

        //TODO:  take in a comma delimited list and convert all
        if (run instanceof AbstractBuild) {
            env.overrideAll(((AbstractBuild) run).getBuildVariables()); // Add in matrix axes..
            expandedTemplate = env.expand(template);
            expandedCluster = env.expand(cluster);
            expandedResourcePool = env.expand(resourcePool);
        }

        vsphere.markAsVm(expandedTemplate, expandedResourcePool, expandedCluster);
        VSphereLogger.vsLogger(jLogger, "\""+expandedTemplate+"\" is a VM!");

        return true;
    }

    @Extension
    public static final class ConvertToVmDescriptor extends VSphereBuildStepDescriptor {

        public ConvertToVmDescriptor() {
            load();
        }

        @Override
        public String getDisplayName() {
            return Messages.vm_title_ConvertToVM();
        }

        public FormValidation doCheckTemplate(@QueryParameter String value) {
            if (value.length() == 0)
                return FormValidation.error(Messages.validation_required("the Template name"));
            return FormValidation.ok();
        }

        public FormValidation doCheckResourcePool(@QueryParameter String value) {
            if (value.length() == 0)
                return FormValidation.error(Messages.validation_required("the resource pool"));
            return FormValidation.ok();
        }

        public FormValidation doCheckCluster(@QueryParameter String value) {
            if (value.length() == 0)
                return FormValidation.error(Messages.validation_required("the cluster"));
            return FormValidation.ok();
        }

        @RequirePOST
        public FormValidation doTestData(@AncestorInPath Item context,
                                         @QueryParameter String serverName,
                                         @QueryParameter String template, @QueryParameter String resourcePool,
                                         @QueryParameter String cluster) {
            throwUnlessUserHasPermissionToConfigureJob(context);
            try {

                if (serverName.length() == 0 || template.length() == 0
                        || resourcePool.length() == 0 || cluster.length() == 0)
                    return FormValidation.error(Messages.validation_requiredValues());

                if (template.indexOf('$') >= 0)
                    return FormValidation.warning(Messages.validation_buildParameter("Template"));

                VSphere vsphere = getVSphereCloudByName(serverName).vSphereInstance();
                VirtualMachine vm = vsphere.getVmByName(template);
                if (vm == null)
                    return FormValidation.error(Messages.validation_notFound("template"));

                if(!vm.getConfig().template)
                    return FormValidation.error(Messages.validation_alreadySet("template", "VM"));

                return FormValidation.ok(Messages.validation_success());
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        }
    }
}