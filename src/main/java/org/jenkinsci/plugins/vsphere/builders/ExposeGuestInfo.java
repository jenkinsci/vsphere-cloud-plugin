package org.jenkinsci.plugins.vsphere.builders;

import static org.jenkinsci.plugins.vsphere.tools.PermissionUtils.throwUnlessUserHasPermissionToConfigureJob;

import com.vmware.vim25.GuestInfo;
import com.vmware.vim25.mo.VirtualMachine;

import hudson.*;
import hudson.model.*;
import hudson.tasks.BuildStepMonitor;
import hudson.util.FormValidation;
import jenkins.tasks.SimpleBuildStep;
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
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;

/**
 * Expose guest info for the named VM as environmental variables.
 * Information on variables can be found here.
 * https://www.vmware.com/support/developer/converter-sdk/conv55_apireference/vim.vm.GuestInfo.html
 */
public class ExposeGuestInfo extends VSphereBuildStep implements SimpleBuildStep {
    private static final List USABLE_CLASS_TYPES = Arrays.asList(String.class, boolean.class, Boolean.class, int.class, Integer.class);
    private static final Pattern ipv4Pattern = Pattern.compile("^(?:[0-9]{1,3}\\.){3}[0-9]{1,3}$");

    private final String vm;
    private final String envVariablePrefix;
    private final Boolean waitForIp4;
    private String resolvedEnvVariablePrefix = null;
    private String IP;
    private final Map<String, String> envVars = new HashMap<>();

    @DataBoundConstructor
    public ExposeGuestInfo(final String vm, final String envVariablePrefix, Boolean waitForIp4) throws VSphereException {
        this.vm = vm;
        this.envVariablePrefix = envVariablePrefix;
        this.waitForIp4 = waitForIp4;
    }

    public String getVm() {
        return vm;
    }

    public String getEnvVariablePrefix() {
        return envVariablePrefix;
    }

    @Override
    public String getIP() {
        return IP;
    }

    public Map<String, String> getVars() {
        return envVars;
    }

    @Override
    public void perform(@NonNull Run<?, ?> run, @NonNull FilePath filePath, @NonNull Launcher launcher, @NonNull TaskListener listener) throws InterruptedException, IOException {
        try {
            exposeInfo(run, launcher, listener);
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
            retVal = exposeInfo(build, launcher, listener);
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

    public boolean exposeInfo(Run<?, ?> run, Launcher launcher, TaskListener listener) throws Exception {
        PrintStream jLogger = listener.getLogger();
        String vmName = vm;
        EnvVars env;
        try {
            env = run.getEnvironment(listener);
        } catch (Exception e) {
            throw new VSphereException(e);
        }
        if (run instanceof AbstractBuild) {
            env.overrideAll(((AbstractBuild) run).getBuildVariables()); // Add in matrix axes..
            vmName = env.expand(vm);
            resolvedEnvVariablePrefix = env.expand(envVariablePrefix).replace("-", "_");
        }

        VSphereLogger.vsLogger(jLogger, "Exposing guest info for VM \"" + vmName + "\" as environment variables");

        VirtualMachine vsphereVm = vsphere.getVmByName(vmName);
        if (vsphereVm == null) {
            throw new RuntimeException(Messages.validation_notFound("vm " + vmName));
        }
        VSphereEnvAction envAction = createGuestInfoEnvAction(vsphereVm, jLogger);

        if (waitForIp4){
            String prefix = resolvedEnvVariablePrefix == null ? envVariablePrefix : resolvedEnvVariablePrefix;
            String machineIP = envAction.data.get(prefix + "_IpAddress");
            while (!ipv4Pattern.matcher(machineIP).find()) {
                try {
                    TimeUnit.SECONDS.sleep(30);
                } catch (InterruptedException e) {

                }
                envAction = createGuestInfoEnvAction(vsphere.getVmByName(vmName), jLogger);
                machineIP = envAction.data.get(prefix + "_IpAddress");
            }
        }

        run.addAction(envAction);

        VSphereLogger.vsLogger(jLogger, "Successfully exposed guest info for VM \"" + vmName + "\"");
        return true;
    }

    private VSphereEnvAction createGuestInfoEnvAction(VirtualMachine vsphereVm, PrintStream jLogger) throws InvocationTargetException,
            IllegalAccessException {
        GuestInfo guestInfo = vsphereVm.getGuest();

        VSphereEnvAction envAction = new VSphereEnvAction();

        String prefix = resolvedEnvVariablePrefix == null ? envVariablePrefix : resolvedEnvVariablePrefix;

        for (Method method : GuestInfo.class.getDeclaredMethods()) {
            if (!method.getName().startsWith("get") || method.getParameterTypes().length > 0) {
                continue;
            }

            String variableName = method.getName().substring(3);
            Class returnType = method.getReturnType();
            if (!USABLE_CLASS_TYPES.contains(returnType) && !returnType.isEnum()) {
                VSphereLogger.vsLogger(jLogger, "Skipped \"" + variableName
                        + "\" as it is of type " + returnType.toString());
                continue;
            }

            Object value = method.invoke(guestInfo);
            // don't add variable for null value
            if (value == null) {
                VSphereLogger.vsLogger(jLogger, "Skipped \"" + variableName + "\" as it is a null value");
                continue;
            }

            if ("IpAddress".equals(variableName)) {
                IP = String.valueOf(value);
            }
            envVars.put(prefix + "_" + variableName, String.valueOf(value));
            String environmentVariableName = prefix + "_" + variableName;
            String environmentVariableValue = String.valueOf(value);

            envAction.add(environmentVariableName, environmentVariableValue);
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

        public FormValidation doCheckVm(@QueryParameter String value) {
            if (value.length() == 0)
                return FormValidation.error(Messages.validation_required("the VM name"));
            return FormValidation.ok();
        }

        public FormValidation doCheckEnvVariablePrefix(@QueryParameter String value) {
            if (value.length() == 0)
                return FormValidation.error(Messages.validation_required("the environment variable prefix"));
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
