package org.jenkinsci.plugins.vsphere.builders;

import com.vmware.vim25.GuestInfo;
import com.vmware.vim25.VirtualMachineToolsStatus;
import com.vmware.vim25.mo.VirtualMachine;

import hudson.EnvVars;
import hudson.Extension;
import hudson.Launcher;
import hudson.model.AbstractBuild;
import hudson.model.BuildListener;
import hudson.model.EnvironmentContributingAction;
import hudson.util.FormValidation;
import org.jenkinsci.plugins.vsphere.VSphereBuildStep;
import org.jenkinsci.plugins.vsphere.tools.VSphere;
import org.jenkinsci.plugins.vsphere.tools.VSphereException;
import org.jenkinsci.plugins.vsphere.tools.VSphereLogger;
import org.kohsuke.stapler.DataBoundConstructor;
import org.kohsuke.stapler.QueryParameter;

import javax.servlet.ServletException;

import java.io.IOException;
import java.io.PrintStream;
import java.util.HashMap;
import java.util.Map;

/**
 * Expose guest info for the named VM as environmental variables.
 * Information on variables can be found here.
 * https://www.vmware.com/support/developer/converter-sdk/conv55_apireference/vim.vm.GuestInfo.html
 */
public class ExposeGuestInfo extends VSphereBuildStep {

    private final String vm;
    private final String envVariablePrefix;

    @DataBoundConstructor
    public ExposeGuestInfo(final String vm, final String envVariablePrefix) throws VSphereException {
        this.vm = vm;
        this.envVariablePrefix = envVariablePrefix;
    }

    @Override
    public boolean perform(AbstractBuild<?, ?> build, Launcher launcher, BuildListener listener) throws Exception {
        PrintStream jLogger = listener.getLogger();
        EnvVars env;
        try {
            env = build.getEnvironment(listener);
        } catch (Exception e) {
            throw new VSphereException(e);
        }
        env.overrideAll(build.getBuildVariables()); // Add in matrix axes..
        String vmName = env.expand(vm);
        String envVariableName = env.expand(this.envVariablePrefix);

        VSphereLogger.vsLogger(jLogger, "Injecting guest info for VM \"" + vmName + "\" as environment variable \""
                + envVariableName + "\"");
        VirtualMachine vsphereVm = vsphere.getVmByName(vmName);
        VSphereEnvAction envAction = createGuestInfoEnvAction(vsphereVm);
        build.addAction(envAction);

        VSphereLogger.vsLogger(jLogger, "Successfully injected guest info for VM \"" + vmName + "\"");
        return true;
    }

    private VSphereEnvAction createGuestInfoEnvAction(VirtualMachine vsphereVm) {
        GuestInfo guestInfo = vsphereVm.getGuest();

        VSphereEnvAction envAction = new VSphereEnvAction();
        envAction.add(envVariablePrefix + "_" + "IP", guestInfo.getIpAddress());
        envAction.add(envVariablePrefix + "_" + "HostName", guestInfo.getHostName());

        VirtualMachineToolsStatus toolsStatus = guestInfo.getToolsStatus();
        // null check just be on the safe side
        envAction.add(envVariablePrefix + "_" + "ToolsStatus",
                toolsStatus != null ? toolsStatus.name() : VirtualMachineToolsStatus.toolsNotInstalled.name());
        envAction.add(envVariablePrefix + "_" + "ToolsRunningStatus", guestInfo.getToolsRunningStatus());
        envAction.add(envVariablePrefix + "_" + "ToolsVersion", guestInfo.getToolsVersion());
        envAction.add(envVariablePrefix + "_" + "ToolsVersionStatus", guestInfo.getToolsVersionStatus2());

        envAction.add(envVariablePrefix + "_" + "GuestState", guestInfo.getGuestState());
        envAction.add(envVariablePrefix + "_" + "GuestId", guestInfo.getGuestId());
        envAction.add(envVariablePrefix + "_" + "GuestFamily", guestInfo.getGuestFamily());
        envAction.add(envVariablePrefix + "_" + "GuestFullName", guestInfo.getGuestFullName());

        envAction.add(envVariablePrefix + "_" + "AppHeartbeatStatus", guestInfo.getAppHeartbeatStatus());
        envAction.add(envVariablePrefix + "_" + "InteractiveGuestOperationsReady",
                String.valueOf(guestInfo.getInteractiveGuestOperationsReady()));
        envAction.add(envVariablePrefix + "_" + "GuestOperationsReady",
                String.valueOf(guestInfo.getGuestOperationsReady()));
        return envAction;
    }

    @Extension
    public static class ExposeGuestInfoDescriptor extends VSphereBuildStepDescriptor {

        @Override
        public String getDisplayName() {
            return Messages.vm_title_ExposeGuestInfo();
        }

        public FormValidation doCheckVm(@QueryParameter String value)
                throws IOException, ServletException {

            if (value.length() == 0)
                return FormValidation.error(Messages.validation_required("the VM name"));
            return FormValidation.ok();
        }

        public FormValidation doCheckEnvVariablePrefix(@QueryParameter String value)
                throws IOException, ServletException {

            if (value.length() == 0)
                return FormValidation.error(Messages.validation_required("the environment variable prefix"));
            return FormValidation.ok();
        }

        public FormValidation doTestData(@QueryParameter String serverName,
                                         @QueryParameter String vm) {
            try {

                if (vm.length() == 0 || serverName.length()==0)
                    return FormValidation.error(Messages.validation_requiredValues());

                VSphere vsphere = getVSphereCloudByName(serverName).vSphereInstance();

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
     * Copied this private class from PowerOn as a way to add environment variables.
     * By rights I should put this somewhere as a public class than can be shared.
     * Not apparent though as to where it should go.
     *
     * @author Lordahl
     */
    private static class VSphereEnvAction implements EnvironmentContributingAction {
        // Decided not to record this data in build.xml, so marked transient:
        private transient Map<String,String> data = new HashMap<String,String>();

        private void add(String key, String val) {
            if (data==null) return;
            data.put(key, val);
        }

        public void buildEnvVars(AbstractBuild<?,?> build, EnvVars env) {
            if (data!=null) env.putAll(data);
        }

        public String getIconFileName() { return null; }
        public String getDisplayName() { return null; }
        public String getUrlName() { return null; }
    }
}
