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

import static org.jenkinsci.plugins.vsphere.tools.PermissionUtils.throwUnlessUserHasPermissionToAccessJob;
import static org.jenkinsci.plugins.vsphere.tools.PermissionUtils.throwUnlessUserHasPermissionToConfigureJob;

import com.vmware.vim25.mo.VirtualMachine;
import edu.umd.cs.findbugs.annotations.NonNull;
import hudson.*;
import hudson.model.*;
import hudson.tasks.BuildStepMonitor;
import hudson.util.FormValidation;
import hudson.util.ListBoxModel;
import java.io.IOException;
import java.io.PrintStream;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import jenkins.tasks.SimpleBuildStep;
import org.jenkinsci.plugins.vSphereCloud;
import org.jenkinsci.plugins.vsphere.VSphereBuildStep;
import org.jenkinsci.plugins.vsphere.tools.HostSelectionOptions;
import org.jenkinsci.plugins.vsphere.tools.HostWeights;
import org.jenkinsci.plugins.vsphere.tools.VSphere;
import org.jenkinsci.plugins.vsphere.tools.VSphereException;
import org.jenkinsci.plugins.vsphere.tools.VSphereHostSelection;
import org.jenkinsci.plugins.vsphere.tools.VSphereLogger;
import org.jenkinsci.plugins.vsphere.tools.VmSize;
import org.kohsuke.stapler.AncestorInPath;
import org.kohsuke.stapler.DataBoundConstructor;
import org.kohsuke.stapler.DataBoundSetter;
import org.kohsuke.stapler.QueryParameter;
import org.kohsuke.stapler.interceptor.RequirePOST;

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

    /** Optional; unset means unchanged legacy behaviour (vCenter's own default placement). */
    private String host;
    /** Optional; one of "", "LEAST_LOADED", "DRS_RECOMMENDED". Ignored when {@code host} is set. */
    private String hostSelectionMode;
    /** Optional allow-list restricting {@code hostSelectionMode}'s candidates. */
    private Set<String> hostSelectionCandidates;

    /** Optional; vCPU count to create the VM with, in the same operation. Unset keeps the source's. */
    private String cpuCores;
    /** Optional; cores per socket to create the VM with. Unset keeps the source's. */
    private String coresPerSocket;
    /** Optional; CPU reservation in MHz to create the VM with. Unset means none. */
    private String cpuLimitMHz;
    /** Optional; memory size in MB to create the VM with. Unset keeps the source's. */
    private String memorySize;

    /** Opt-in: skip candidate hosts with fewer physical cores than the VM has vCPUs. */
    private Boolean hostSelectionRequireCores;
    /** Opt-in: skip candidate hosts with less physical RAM than the VM is configured with. */
    private Boolean hostSelectionRequireMemory;
    /** Opt-in: skip candidate hosts that do not have the VM's memory size free right now. */
    private Boolean hostSelectionRequireAvailableMemory;
    /**
     * Optional host weights for this call, same meaning as on the vSphere Cloud but as text (variables
     * allowed in build steps). If any of the four is set, they replace the cloud's weights as a whole
     * (blank ones count as 0); if none is, the cloud's apply.
     */
    private String hostWeightFreeCpuMhz;

    private String hostWeightFreeCpuPercent;
    private String hostWeightFreeMemoryMB;
    private String hostWeightFreeMemoryPercent;

    @DataBoundConstructor
    public Deploy(
            String template,
            String clone,
            boolean linkedClone,
            String resourcePool,
            String cluster,
            String datastore,
            String folder,
            String customizationSpec,
            Integer timeoutInSeconds,
            boolean powerOn)
            throws VSphereException {
        this.template = template;
        this.clone = clone;
        this.linkedClone = linkedClone;
        this.resourcePool = (resourcePool != null) ? resourcePool : "";
        this.cluster = cluster;
        this.datastore = datastore;
        this.folder = folder;
        this.customizationSpec = customizationSpec;
        this.powerOn = powerOn;
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
        if (timeoutInSeconds == null) {
            return TIMEOUT_DEFAULT;
        }
        return timeoutInSeconds.intValue();
    }

    public String getHost() {
        return host;
    }

    private boolean failOnNoAddress;

    /** Whether failing to obtain the VM's IP address within the timeout fails this step (default: only warn). */
    public boolean isFailOnNoAddress() {
        return failOnNoAddress;
    }

    @DataBoundSetter
    public void setFailOnNoAddress(boolean failOnNoAddress) {
        this.failOnNoAddress = failOnNoAddress;
    }

    @DataBoundSetter
    public void setHost(String host) {
        this.host = host;
    }

    public String getHostSelectionMode() {
        return hostSelectionMode;
    }

    @DataBoundSetter
    public void setHostSelectionMode(String hostSelectionMode) {
        this.hostSelectionMode = hostSelectionMode;
    }

    /** Canonical form, for pipeline/API/JCasC consumers. */
    public Set<String> getHostSelectionCandidates() {
        return hostSelectionCandidates;
    }

    /**
     * Takes a flat list of individual host names - the natural shape for a pipeline or
     * JCasC YAML caller that already has one. See {@link #setHostSelectionCandidatesAsString}
     * for the comma-separated-string equivalent (used by the classic UI textbox). Both
     * are kept as separate, concretely-typed properties rather than one that accepts
     * either shape: Jenkins' JCasC introspection resolves exactly one configurator per
     * property type, so a single {@code Object}-typed (or overloaded) setter is not
     * reliably usable from YAML, even though pipeline's looser binding tolerates it.
     */
    @DataBoundSetter
    public void setHostSelectionCandidates(Collection<String> hostSelectionCandidates) {
        this.hostSelectionCandidates =
                hostSelectionCandidates == null ? null : new LinkedHashSet<>(hostSelectionCandidates);
    }

    /**
     * For the classic config UI textbox, and pipeline/JCasC callers that prefer a plain
     * string. Blank means "inherit the cloud's default candidate list" (see {@link
     * org.jenkinsci.plugins.vSphereCloud#getHostSelectionCandidates()}); a single comma
     * explicitly overrides to "no restriction at this call site" - see {@link
     * VSphereHostSelection#toAllowListString}.
     */
    public String getHostSelectionCandidatesAsString() {
        return VSphereHostSelection.toAllowListString(hostSelectionCandidates);
    }

    @DataBoundSetter
    public void setHostSelectionCandidatesAsString(String hostSelectionCandidatesCsv) {
        this.hostSelectionCandidates = VSphereHostSelection.parseAllowListOrNull(hostSelectionCandidatesCsv);
    }

    /** Optional: number of vCPUs to create the VM with, as {@code ReconfigureCpu}'s; may use variables. */
    public String getCpuCores() {
        return cpuCores;
    }

    @DataBoundSetter
    public void setCpuCores(String cpuCores) {
        this.cpuCores = cpuCores;
    }

    /** Optional: cores per socket to create the VM with, as {@code ReconfigureCpu}'s; may use variables. */
    public String getCoresPerSocket() {
        return coresPerSocket;
    }

    @DataBoundSetter
    public void setCoresPerSocket(String coresPerSocket) {
        this.coresPerSocket = coresPerSocket;
    }

    /** Optional: CPU reservation in MHz to create the VM with, as {@code ReconfigureCpu}'s; may use variables. */
    public String getCpuLimitMHz() {
        return cpuLimitMHz;
    }

    @DataBoundSetter
    public void setCpuLimitMHz(String cpuLimitMHz) {
        this.cpuLimitMHz = cpuLimitMHz;
    }

    /** Optional: memory size in MB to create the VM with, as {@code ReconfigureMemory}'s; may use variables. */
    public String getMemorySize() {
        return memorySize;
    }

    @DataBoundSetter
    public void setMemorySize(String memorySize) {
        this.memorySize = memorySize;
    }

    /**
     * Opt-in override of the cloud's default: only consider hosts with at least as many
     * physical CPU cores as the VM has vCPUs. {@code null} (the default) inherits the cloud's
     * setting; {@code true}/{@code false} override it for this call site.
     */
    public Boolean getHostSelectionRequireCores() {
        return hostSelectionRequireCores;
    }

    @DataBoundSetter
    public void setHostSelectionRequireCores(Boolean hostSelectionRequireCores) {
        this.hostSelectionRequireCores = hostSelectionRequireCores;
    }

    /**
     * For the classic config UI, where an unset ("inherit") value has to survive a round trip as
     * an empty string; pipeline and JCasC callers should use {@link #getHostSelectionRequireCores}.
     */
    public String getHostSelectionRequireCoresAsString() {
        return HostSelectionOptions.triStateToString(hostSelectionRequireCores);
    }

    @DataBoundSetter
    public void setHostSelectionRequireCoresAsString(String hostSelectionRequireCoresAsString) {
        this.hostSelectionRequireCores = HostSelectionOptions.triStateFromString(hostSelectionRequireCoresAsString);
    }

    /**
     * Opt-in override of the cloud's default: only consider hosts with at least as much
     * physical RAM as the VM is configured with. {@code null} (the default) inherits the
     * cloud's setting; {@code true}/{@code false} override it for this call site.
     */
    public Boolean getHostSelectionRequireMemory() {
        return hostSelectionRequireMemory;
    }

    @DataBoundSetter
    public void setHostSelectionRequireMemory(Boolean hostSelectionRequireMemory) {
        this.hostSelectionRequireMemory = hostSelectionRequireMemory;
    }

    /**
     * For the classic config UI, where an unset ("inherit") value has to survive a round trip as
     * an empty string; pipeline and JCasC callers should use {@link #getHostSelectionRequireMemory}.
     */
    public String getHostSelectionRequireMemoryAsString() {
        return HostSelectionOptions.triStateToString(hostSelectionRequireMemory);
    }

    @DataBoundSetter
    public void setHostSelectionRequireMemoryAsString(String hostSelectionRequireMemoryAsString) {
        this.hostSelectionRequireMemory = HostSelectionOptions.triStateFromString(hostSelectionRequireMemoryAsString);
    }

    /**
     * Opt-in override of the cloud's default: only consider hosts that currently have at least as
     * much memory free as the VM is configured with, so it is not swapped by the hypervisor.
     * {@code null} (the default) inherits the cloud's setting; {@code true}/{@code false} override
     * it for this call site.
     */
    public Boolean getHostSelectionRequireAvailableMemory() {
        return hostSelectionRequireAvailableMemory;
    }

    @DataBoundSetter
    public void setHostSelectionRequireAvailableMemory(Boolean hostSelectionRequireAvailableMemory) {
        this.hostSelectionRequireAvailableMemory = hostSelectionRequireAvailableMemory;
    }

    /**
     * For the classic config UI, where an unset ("inherit") value has to survive a round trip as
     * an empty string; pipeline and JCasC callers should use {@link #getHostSelectionRequireAvailableMemory}.
     */
    public String getHostSelectionRequireAvailableMemoryAsString() {
        return HostSelectionOptions.triStateToString(hostSelectionRequireAvailableMemory);
    }

    @DataBoundSetter
    public void setHostSelectionRequireAvailableMemoryAsString(String hostSelectionRequireAvailableMemoryAsString) {
        this.hostSelectionRequireAvailableMemory =
                HostSelectionOptions.triStateFromString(hostSelectionRequireAvailableMemoryAsString);
    }

    public String getHostWeightFreeCpuMhz() {
        return hostWeightFreeCpuMhz;
    }

    @DataBoundSetter
    public void setHostWeightFreeCpuMhz(String hostWeightFreeCpuMhz) {
        this.hostWeightFreeCpuMhz = hostWeightFreeCpuMhz;
    }

    public String getHostWeightFreeCpuPercent() {
        return hostWeightFreeCpuPercent;
    }

    @DataBoundSetter
    public void setHostWeightFreeCpuPercent(String hostWeightFreeCpuPercent) {
        this.hostWeightFreeCpuPercent = hostWeightFreeCpuPercent;
    }

    public String getHostWeightFreeMemoryMB() {
        return hostWeightFreeMemoryMB;
    }

    @DataBoundSetter
    public void setHostWeightFreeMemoryMB(String hostWeightFreeMemoryMB) {
        this.hostWeightFreeMemoryMB = hostWeightFreeMemoryMB;
    }

    public String getHostWeightFreeMemoryPercent() {
        return hostWeightFreeMemoryPercent;
    }

    @DataBoundSetter
    public void setHostWeightFreeMemoryPercent(String hostWeightFreeMemoryPercent) {
        this.hostWeightFreeMemoryPercent = hostWeightFreeMemoryPercent;
    }

    @Override
    public String getIP() {
        return IP;
    }

    @Override
    public void perform(
            @NonNull Run<?, ?> run,
            @NonNull FilePath filePath,
            @NonNull Launcher launcher,
            @NonNull TaskListener listener)
            throws InterruptedException, IOException {
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
    public boolean perform(final AbstractBuild<?, ?> build, final Launcher launcher, final BuildListener listener) {
        boolean retVal = false;
        try {
            retVal = deployFromTemplate(build, launcher, listener);
        } catch (Exception e) {
            e.printStackTrace();
        }
        return retVal;
        // TODO throw AbortException instead of returning value
    }

    @Override
    public Action getProjectAction(AbstractProject<?, ?> abstractProject) {
        return null;
    }

    @Override
    public Collection<? extends Action> getProjectActions(AbstractProject<?, ?> abstractProject) {
        return Collections.emptyList();
    }

    @Override
    public BuildStepMonitor getRequiredMonitorService() {
        return null;
    }

    private boolean deployFromTemplate(final Run<?, ?> run, final Launcher launcher, final TaskListener listener)
            throws VSphereException {
        PrintStream jLogger = listener.getLogger();
        String expandedClone = clone;
        String expandedTemplate = template;
        String expandedCluster = cluster;
        String expandedDatastore = datastore;
        String expandedFolder = folder;
        String expandedCustomizationSpec = customizationSpec;
        String expandedHost = host;
        Set<String> expandedHostSelectionCandidates = hostSelectionCandidates;
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
            if (host != null) {
                expandedHost = env.expand(host);
            }
            if (hostSelectionCandidates != null) {
                expandedHostSelectionCandidates = new LinkedHashSet<>();
                for (String candidateHost : hostSelectionCandidates) {
                    expandedHostSelectionCandidates.add(env.expand(candidateHost));
                }
            }
        }

        String resourcePoolName;
        if ("".equals(resourcePool) || (resourcePool.length() == 0)) {
            // Not all installations are using resource pools. But there is always a hidden "Resources" resource
            // pool, even if not visible in the vSphere Client.
            resourcePoolName = "Resources";
        } else {
            resourcePoolName = env.expand(resourcePool);
        }

        final vSphereCloud sourceCloud = getSourceCloud();
        final String cloudDefaultHostSelectionMode = sourceCloud != null ? sourceCloud.getHostSelectionMode() : null;
        final Set<String> cloudDefaultHostSelectionCandidates =
                sourceCloud != null ? sourceCloud.getHostSelectionCandidates() : null;
        final String resolvedHostSelectionMode =
                VSphereHostSelection.resolveMode(cloudDefaultHostSelectionMode, hostSelectionMode);
        final Set<String> resolvedHostSelectionCandidates = VSphereHostSelection.resolveCandidates(
                cloudDefaultHostSelectionCandidates, expandedHostSelectionCandidates);
        final HostWeights weightsOverride = HostWeights.parseOverride(
                hostWeightFreeCpuMhz == null ? null : env.expand(hostWeightFreeCpuMhz),
                hostWeightFreeCpuPercent == null ? null : env.expand(hostWeightFreeCpuPercent),
                hostWeightFreeMemoryMB == null ? null : env.expand(hostWeightFreeMemoryMB),
                hostWeightFreeMemoryPercent == null ? null : env.expand(hostWeightFreeMemoryPercent));
        final HostSelectionOptions hostSelectionOptions = vSphereCloud.hostSelectionOptions(
                sourceCloud,
                hostSelectionRequireCores,
                hostSelectionRequireMemory,
                hostSelectionRequireAvailableMemory,
                weightsOverride);
        final VmSize vmSize = VmSize.of(
                cpuCores == null ? null : env.expand(cpuCores),
                coresPerSocket == null ? null : env.expand(coresPerSocket),
                cpuLimitMHz == null ? null : env.expand(cpuLimitMHz),
                memorySize == null ? null : env.expand(memorySize));

        vsphere.deployVm(
                expandedClone,
                expandedTemplate,
                linkedClone,
                resourcePoolName,
                expandedCluster,
                expandedDatastore,
                expandedFolder,
                powerOn,
                expandedCustomizationSpec,
                expandedHost,
                resolvedHostSelectionMode,
                resolvedHostSelectionCandidates,
                hostSelectionOptions,
                vmSize,
                jLogger);
        VSphereLogger.vsLogger(jLogger, "\"" + expandedClone + "\" successfully deployed!");
        if (!powerOn) {
            return true; // don't try to obtain IP if VM isn't being turned on.
        }
        final int timeoutInSecondsForGetIp = getTimeoutInSeconds();
        if (timeoutInSecondsForGetIp <= 0) {
            return true; // don't try to obtain IP if disabled
        }
        VSphereLogger.vsLogger(
                jLogger,
                "Trying to get the IP address of \"" + expandedClone + "\" for the next " + timeoutInSecondsForGetIp
                        + " seconds.");
        IP = vsphere.getIp(vsphere.getVmByName(expandedClone), timeoutInSecondsForGetIp);

        if (IP != null) {
            VSphereLogger.vsLogger(jLogger, "Successfully retrieved IP for \"" + expandedClone + "\" : " + IP);
            VSphereLogger.vsLogger(jLogger, "Exposing " + IP + " as environment variable VSPHERE_IP");

            if (run instanceof AbstractBuild) {
                VSphereEnvAction envAction = new VSphereEnvAction();
                envAction.add("VSPHERE_IP", IP);
                run.addAction(envAction);
            }
            return true;
        } else {
            final String message = "Timed out after waiting " + timeoutInSecondsForGetIp + " seconds to get IP for \""
                    + expandedClone + "\"";
            if (failOnNoAddress) {
                throw new VSphereException(message);
            }
            VSphereLogger.vsLogger(
                    jLogger, "Warning: " + message + " (not failing as \"Fail if no IP address\" is off)");
            return true;
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
            if (value.length() == 0) return FormValidation.error("Please enter the template name");
            return FormValidation.ok();
        }

        public FormValidation doCheckClone(@QueryParameter String value) {
            if (value.length() == 0) return FormValidation.error(Messages.validation_required("the clone name"));
            return FormValidation.ok();
        }

        public FormValidation doCheckResourcePool(@QueryParameter String value) {
            return FormValidation.ok();
        }

        public FormValidation doCheckCluster(@QueryParameter String value) {
            if (value.length() == 0) return FormValidation.error(Messages.validation_required("the cluster"));
            return FormValidation.ok();
        }

        public FormValidation doCheckTimeoutInSeconds(@QueryParameter String value) {
            return FormValidation.validateNonNegativeInteger(value);
        }

        public ListBoxModel doFillHostSelectionModeItems() {
            ListBoxModel items = new ListBoxModel();
            items.add("(none - inherit the cloud's default)", "");
            items.add("Explicitly none (override the cloud's default)", VSphereHostSelection.HOST_SELECTION_MODE_NONE);
            items.add("Least loaded host (CPU/memory, no DRS license required)", "LEAST_LOADED");
            items.add("DRS recommendation (requires DRS enabled + licensed on the cluster)", "DRS_RECOMMENDED");
            return items;
        }

        @RequirePOST
        public ListBoxModel doFillHostSelectionRequireCoresAsStringItems(@AncestorInPath Item context) {
            throwUnlessUserHasPermissionToAccessJob(context);
            return HostSelectionOptions.triStateItems();
        }

        @RequirePOST
        public ListBoxModel doFillHostSelectionRequireMemoryAsStringItems(@AncestorInPath Item context) {
            throwUnlessUserHasPermissionToAccessJob(context);
            return HostSelectionOptions.triStateItems();
        }

        @RequirePOST
        public ListBoxModel doFillHostSelectionRequireAvailableMemoryAsStringItems(@AncestorInPath Item context) {
            throwUnlessUserHasPermissionToAccessJob(context);
            return HostSelectionOptions.triStateItems();
        }

        @RequirePOST
        public FormValidation doTestData(
                @AncestorInPath Item context,
                @QueryParameter String serverName,
                @QueryParameter String template,
                @QueryParameter String clone,
                @QueryParameter String resourcePool,
                @QueryParameter String cluster,
                @QueryParameter String host,
                @QueryParameter String hostSelectionCandidatesAsString) {
            throwUnlessUserHasPermissionToConfigureJob(context);
            VSphere vsphere = null;
            try {
                if (template.length() == 0 || clone.length() == 0 || serverName.length() == 0 || cluster.length() == 0)
                    return FormValidation.error(Messages.validation_requiredValues());

                vsphere = getVSphereCloudByName(serverName, null).vSphereInstance();

                // TODO what if clone name is variable?
                VirtualMachine cloneVM = vsphere.getVmByName(clone);
                if (cloneVM != null) return FormValidation.error(Messages.validation_exists("clone"));

                if (template.indexOf('$') >= 0)
                    return FormValidation.warning(Messages.validation_buildParameter("template"));

                VirtualMachine vm = vsphere.getVmByName(template);
                if (vm == null) return FormValidation.error(Messages.validation_notFound("template"));

                if (!vm.getConfig().template) return FormValidation.error(Messages.validation_notActually("template"));

                if (host != null && !host.isEmpty() && !vsphere.hostExists(host)) {
                    return FormValidation.error(Messages.validation_notFound("host"));
                }

                if (hostSelectionCandidatesAsString != null && !hostSelectionCandidatesAsString.isEmpty()) {
                    for (String candidateHost : VSphereHostSelection.parseAllowList(hostSelectionCandidatesAsString)) {
                        if (!vsphere.hostExists(candidateHost)) {
                            return FormValidation.error("Candidate host \"" + candidateHost + "\" was not found.");
                        }
                    }
                }

                return FormValidation.ok(Messages.validation_success());
            } catch (Exception e) {
                throw new RuntimeException(e);
            } finally {
                if (vsphere != null) {
                    vsphere.disconnect();
                }
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
        private final transient Map<String, String> data = new HashMap<String, String>();

        private void add(String key, String val) {
            if (data == null) return;
            data.put(key, val);
        }

        @Override
        public void buildEnvVars(AbstractBuild<?, ?> build, EnvVars env) {
            if (data != null) env.putAll(data);
        }

        @Override
        public String getIconFileName() {
            return null;
        }

        @Override
        public String getDisplayName() {
            return null;
        }

        @Override
        public String getUrlName() {
            return null;
        }
    }
}
