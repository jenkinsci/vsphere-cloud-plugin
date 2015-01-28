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
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
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

    public String getVm() {
        return vm;
    }

    public String getEnvVariablePrefix() {
        return envVariablePrefix;
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

        VSphereLogger.vsLogger(jLogger, "Injecting guest info for VM \"" + vmName + "\" as environment variables");

        VirtualMachine vsphereVm = vsphere.getVmByName(vmName);
        VSphereEnvAction envAction = createGuestInfoEnvAction(vsphereVm, jLogger);
        build.addAction(envAction);

        VSphereLogger.vsLogger(jLogger, "Successfully injected guest info for VM \"" + vmName + "\"");
        return true;
    }

    private VSphereEnvAction createGuestInfoEnvAction(VirtualMachine vsphereVm, PrintStream jLogger) throws InvocationTargetException,
            IllegalAccessException {
        GuestInfo guestInfo = vsphereVm.getGuest();

        VSphereEnvAction envAction = new VSphereEnvAction();

        List USABLE_CLASS_TYPES = Arrays.asList(String.class, boolean.class,
                Boolean.class, int.class, Integer.class);


        for (Method method : GuestInfo.class.getDeclaredMethods()) {
            if (!method.getName().startsWith("get") || method.getParameterTypes().length > 0) {
                continue;
            }

            String environmentVariableName = method.getName().substring(3);
            Class returnType = method.getReturnType();
            if (!USABLE_CLASS_TYPES.contains(returnType) && !returnType.isEnum()) {
                VSphereLogger.vsLogger(jLogger, "Skipped \"" + environmentVariableName
                        + "\" as it is of type " + returnType.toString());
                continue;
            }

            Object value = method.invoke(guestInfo);
            // don't add variable for null value
            if (value == null) {
                VSphereLogger.vsLogger(jLogger, "Skipped \"" + environmentVariableName + "\" as it is a null value");
                continue;
            }

            String environmentVariableValue = String.valueOf(value);
            envAction.add(envVariablePrefix + "_" + environmentVariableName, environmentVariableValue);
            VSphereLogger.vsLogger(jLogger, "Added environmental variable \"" + environmentVariableName
                    + "\" with a value of \"" + environmentVariableValue + "\"");
        }

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
