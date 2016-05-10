package org.jenkinsci.plugins.workflow;

import com.google.inject.Inject;
import hudson.EnvVars;
import hudson.Extension;
import hudson.FilePath;
import hudson.Launcher;
import hudson.model.Run;
import hudson.model.TaskListener;
import org.jenkinsci.plugins.vsphere.VSphereBuildStep;
import org.jenkinsci.plugins.vsphere.VSphereBuildStepContainer;
import org.jenkinsci.plugins.vsphere.builders.Deploy;
import org.jenkinsci.plugins.vsphere.builders.PowerOff;
import org.jenkinsci.plugins.vsphere.builders.PowerOn;
import org.jenkinsci.plugins.workflow.steps.AbstractStepDescriptorImpl;
import org.jenkinsci.plugins.workflow.steps.AbstractStepImpl;
import org.jenkinsci.plugins.workflow.steps.AbstractSynchronousNonBlockingStepExecution;
import org.jenkinsci.plugins.workflow.steps.StepContextParameter;
import org.kohsuke.stapler.DataBoundConstructor;
import org.kohsuke.stapler.DataBoundSetter;

/**
 * The vSphere invocation step for the Jenkins workflow plugin.
 */
public class vSphereStep extends AbstractStepImpl {

    private String action;
    private String serverName;
    private String vm;
    private String template;
    private String clone;
    private boolean linkedClone;
    private String cluster;
    private String datastore;
    private boolean powerOn;

    public String getAction() {
        return action;
    }

    public String getServerName() {
        return serverName;
    }

    public String getVm() {
        return vm;
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

    public String getDatastore() {
        return datastore;
    }

    public boolean isPowerOn() {
        return powerOn;
    }

    @DataBoundConstructor
    public vSphereStep() {
    }

    @DataBoundSetter
    public void setAction(String action) {
        this.action = action;
    }

    @DataBoundSetter
    public void setServerName(String serverName) {
        this.serverName = serverName;
    }

    @DataBoundSetter
    public void setTemplate(String template) {
        this.template = template;
    }

    @DataBoundSetter
    public void setClone(String clone) {
        this.clone = clone;
    }

    @DataBoundSetter
    public void setLinkedClone(Boolean linkedClone) {
        this.linkedClone = linkedClone;
    }

    @DataBoundSetter
    public void setCluster(String cluster) {
        this.cluster = cluster;
    }

    @DataBoundSetter
    public void setDatastore(String datastore){
        this.datastore = datastore;
    }

    @DataBoundSetter
    public void setPowerOn(Boolean powerOn) {
        this.powerOn = powerOn;
    }

    @DataBoundSetter
    public void setVm(String vm) {
        this.vm = vm;
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
            return "Invoke an vSphere action (deploy, powerOff, powerOn)";
        }

    }

    public static final class vSphereExecution extends AbstractSynchronousNonBlockingStepExecution<String> {

        private static final long serialVersionUID = 1;

        private VSphereBuildStep action;

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
            System.out.println(step.getAction());

            String IP = "";

            switch (step.getAction().toLowerCase()) {
                case "poweron":
                    action = new PowerOn(step.getVm(), 10);
                    break;
                case "poweroff":
                    action = new PowerOff(step.getVm(), true, true);
                    break;
                case "deploy":
                    action = new Deploy(step.getTemplate(), step.getClone(), step.isLinkedClone(), "", step.getCluster(), step.getDatastore(), step.isPowerOn());
                    break;
                default:
                    break;
            }
//            action.perform(run, filePath, launcher, listener);
            VSphereBuildStepContainer vSphereBSC = new VSphereBuildStepContainer(action, step.getServerName());
            vSphereBSC.perform(run, filePath, launcher, listener);
            if ("poweron".equals(step.getAction().toLowerCase()) ||
                    "deploy".equals(step.getAction().toLowerCase()) ||
                    "clone".equals(step.getAction().toLowerCase())) {
                IP = action.getIP();
                envVars.put("VSPHERE_IP", IP);
            }
            action = null;
            vSphereBSC = null;

            return IP;
        }
    }
}