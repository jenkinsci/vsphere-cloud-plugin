package org.jenkinsci.plugins.workflow;

import com.google.inject.Inject;
import hudson.*;
import hudson.model.Run;
import hudson.model.TaskListener;
import hudson.slaves.Cloud;
import hudson.util.ListBoxModel;
import jenkins.model.Jenkins;
import org.jenkinsci.plugins.vSphereCloud;
import org.jenkinsci.plugins.vsphere.VSphereBuildStep;
import org.jenkinsci.plugins.vsphere.VSphereBuildStepContainer;
import org.jenkinsci.plugins.vsphere.builders.*;
import org.jenkinsci.plugins.workflow.steps.AbstractStepDescriptorImpl;
import org.jenkinsci.plugins.workflow.steps.AbstractStepImpl;
import org.jenkinsci.plugins.workflow.steps.AbstractSynchronousNonBlockingStepExecution;
import org.jenkinsci.plugins.workflow.steps.StepContextParameter;
import org.kohsuke.stapler.DataBoundConstructor;
import org.kohsuke.stapler.DataBoundSetter;

import java.util.Map;

/**
 * The vSphere invocation step for the Jenkins workflow plugin.
 */
public class vSphereStep extends AbstractStepImpl {

    private String serverName;

    private VSphereBuildStep buildStep;

    public String getServerName() {
        return serverName;
    }

    public VSphereBuildStep getBuildStep() {
        return buildStep;
    }

    @DataBoundConstructor
    public vSphereStep() {
    }

    @DataBoundSetter
    public void setBuildStep(VSphereBuildStep buildStep) {
        this.buildStep = buildStep;
    }

    @DataBoundSetter
    public void setServerName(String serverName) {
        this.serverName = serverName;
    }

    @Extension
    public static final class DescriptorImpl extends AbstractStepDescriptorImpl {

        public DescriptorImpl() {
            super(vSphereExecution.class);
        }

        @Override
        public String getFunctionName() {
            return "vSphere";
        }

        @Override
        public String getDisplayName() {
            return "Invoke an vSphere action, exposing the VM IP under some actions";
        }

        public ListBoxModel doFillServerNameItems() {
            ListBoxModel select = new ListBoxModel();
            try {
                for (Cloud cloud : Jenkins.getInstance().clouds) {
                    if (cloud instanceof vSphereCloud) {
                        select.add(((vSphereCloud) cloud).getVsDescription());
                    }
                }
            } catch (Exception e) {
                e.printStackTrace();
            }
            return select;
        }

        public DescriptorExtensionList<VSphereBuildStep, VSphereBuildStep.VSphereBuildStepDescriptor> getBuildSteps() {
            return Jenkins.getInstance().getDescriptorList(VSphereBuildStep.class);
        }
    }

    public static final class vSphereExecution extends AbstractSynchronousNonBlockingStepExecution<String> {

        private static final long serialVersionUID = 1;

        private transient VSphereBuildStepContainer vSphereBSC;

        @Inject
        private transient vSphereStep step;

        @StepContextParameter
        private transient Run run;

        @StepContextParameter
        private transient FilePath filePath;

        @StepContextParameter
        private transient Launcher launcher;

        @StepContextParameter
        private transient TaskListener listener;

        @StepContextParameter
        private transient EnvVars envVars;

        @Override
        protected String run() throws Exception {
            String IP = "";

            vSphereBSC = new VSphereBuildStepContainer(step.getBuildStep(), step.getServerName());
            vSphereBSC.perform(run, filePath, launcher, listener);
            if (step.getBuildStep().getClass().toString().contains("PowerOn") ||
                    step.getBuildStep().getClass().toString().contains("Deploy") ||
                    step.getBuildStep().getClass().toString().contains("Clone") ||
                    step.getBuildStep().getClass().toString().contains("ExposeGuestInfo")) {
                IP = step.getBuildStep().getIP();
                envVars.put("VSPHERE_IP", IP);

                if (step.getBuildStep().getClass().toString().contains("ExposeGuestInfo")) {
                    Map<String, String> envVars = ((ExposeGuestInfo)step.getBuildStep()).getVars();
                    for (Map.Entry<String, String> envVar: envVars.entrySet()) {
                        envVars.put(envVar.getKey(), envVar.getValue());
                    }
                }
            }
            vSphereBSC = null;

            return IP;
        }
    }
}