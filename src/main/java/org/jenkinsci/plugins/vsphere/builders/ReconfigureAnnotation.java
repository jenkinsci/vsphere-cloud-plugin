/*
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
import org.jenkinsci.plugins.vsphere.tools.VSphereException;
import org.jenkinsci.plugins.vsphere.tools.VSphereLogger;
import org.kohsuke.stapler.DataBoundConstructor;
import org.kohsuke.stapler.DataBoundSetter;

import edu.umd.cs.findbugs.annotations.NonNull;
import java.io.IOException;
import java.io.PrintStream;

public class ReconfigureAnnotation extends ReconfigureStep {

    private String annotation;
    private boolean append;

    /** Constructor that takes all mandatory fields. */
    @DataBoundConstructor
    public ReconfigureAnnotation() {
    }

    @DataBoundSetter
    public void setAnnotation(String annotation) {
        this.annotation = annotation;
    }

    public String getAnnotation() {
        return annotation==null ? "" : annotation;
    }

    @DataBoundSetter
    public void setAppend(boolean append) {
        this.append = append;
    }

    public boolean getAppend() {
        return append;
    }

    @Override
    public void perform(@NonNull Run<?, ?> run, @NonNull FilePath filePath, @NonNull Launcher launcher, @NonNull TaskListener listener) throws InterruptedException, IOException {
        try {
            reconfigureAnnotation(run, launcher, listener);
        } catch (Exception e) {
            throw new AbortException(e.getMessage());
        }
    }

    @Override
    public boolean perform(final AbstractBuild<?, ?> build, final Launcher launcher, final BuildListener listener)  {
        boolean retVal = false;
        try {
            retVal = reconfigureAnnotation(build, launcher, listener);
        } catch (Exception e) {
            e.printStackTrace();
        }
        return retVal;
        //TODO throw AbortException instead of returning value
    }

    public boolean reconfigureAnnotation(final Run<?, ?> run, final Launcher launcher, final TaskListener listener) throws VSphereException  {

        final PrintStream jLogger = listener.getLogger();
        String expandedText = getAnnotation();
        final EnvVars env;
        try {
            env = run.getEnvironment(listener);
        } catch (Exception e) {
            throw new VSphereException(e);
        }
        if (run instanceof AbstractBuild) {
            env.overrideAll(((AbstractBuild) run).getBuildVariables()); // Add in matrix axes..
            expandedText = env.expand(expandedText);
        }

        VSphereLogger.vsLogger(jLogger, "Preparing reconfigure: Annotation");
        if (getAppend()) {
            final String currentTextOrNullIfEmpty = spec.getAnnotation();
            if ( currentTextOrNullIfEmpty!=null ) {
                expandedText = currentTextOrNullIfEmpty + expandedText;
            }
        }
        spec.setAnnotation(expandedText);
        VSphereLogger.vsLogger(jLogger, "Finished!");
        return true;
    }

    @Extension
    public static final class ReconfigureAnnotationDescriptor extends ReconfigureStepDescriptor {

        public ReconfigureAnnotationDescriptor() {
            load();
        }

        @Override
        public String getDisplayName() {
            return Messages.vm_title_ReconfigureAnnotation();
        }
    }
}
