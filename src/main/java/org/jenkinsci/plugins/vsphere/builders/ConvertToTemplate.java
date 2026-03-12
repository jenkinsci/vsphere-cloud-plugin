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
import java.text.SimpleDateFormat;
import java.util.Date;

import edu.umd.cs.findbugs.annotations.NonNull;

import org.jenkinsci.plugins.vsphere.VSphereBuildStep;
import org.jenkinsci.plugins.vsphere.tools.VSphere;
import org.jenkinsci.plugins.vsphere.tools.VSphereException;
import org.jenkinsci.plugins.vsphere.tools.VSphereLogger;
import org.kohsuke.stapler.AncestorInPath;
import org.kohsuke.stapler.DataBoundConstructor;
import org.kohsuke.stapler.QueryParameter;
import org.kohsuke.stapler.interceptor.RequirePOST;

public class ConvertToTemplate extends VSphereBuildStep {

    private final String vm;
    private final boolean force;

    @DataBoundConstructor
    public ConvertToTemplate(String vm, boolean force) throws VSphereException {
        this.force = force;
        this.vm = vm;
    }

    public String getVm() {
        return vm;
    }

    public boolean isForce() {
        return force;
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
        VSphereLogger.vsLogger(jLogger, "Converting VM to template. Please wait ...");
        String expandedVm = vm;
        EnvVars env;
        try {
            env = run.getEnvironment(listener);
        } catch (Exception e) {
            throw new VSphereException(e);
        }

        Date date = new Date();
        SimpleDateFormat df = new SimpleDateFormat("MMM dd, yyyy hh:mm:ss aaa");

        if (run instanceof AbstractBuild) {
            env.overrideAll(((AbstractBuild) run).getBuildVariables()); // Add in matrix axes..
            expandedVm = env.expand(vm);
        }

        vsphere.markAsTemplate(expandedVm, df.format(date), force);
        VSphereLogger.vsLogger(jLogger, "\""+expandedVm+"\" is now a template.");

        return true;
    }

    @Extension
    public static final class ConvertToTemplateDescriptor extends VSphereBuildStepDescriptor {

        public ConvertToTemplateDescriptor() {
            load();
        }

        /**
         * This human readable name is used in the configuration screen.
         */
        @Override
        public String getDisplayName() {
            return Messages.vm_title_ConvertToTemplate();
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
                if (serverName == null){
                    return FormValidation.error(Messages.validation_required("serverName"));
                }

                if (serverName.length() == 0 || vm.length() == 0)
                    return FormValidation.error(Messages.validation_requiredValues());

                if (vm.indexOf('$') >= 0)
                    return FormValidation.warning(Messages.validation_buildParameter("VM"));

                VSphere vsphere = getVSphereCloudByName(serverName, null).vSphereInstance();
                if (vsphere.getVmByName(vm) == null)
                    return FormValidation.error(Messages.validation_notFound("VM"));

                return FormValidation.ok(Messages.validation_success());
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        }
    }
}