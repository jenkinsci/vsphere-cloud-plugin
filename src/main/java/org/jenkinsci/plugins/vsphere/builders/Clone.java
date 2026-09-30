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
import hudson.util.ListBoxModel;

import java.io.IOException;
import java.io.PrintStream;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

import edu.umd.cs.findbugs.annotations.NonNull;

import org.jenkinsci.plugins.vSphereCloud;
import org.jenkinsci.plugins.vsphere.VSphereBuildStep;
import org.jenkinsci.plugins.vsphere.tools.VSphere;
import org.jenkinsci.plugins.vsphere.tools.VSphereException;
import org.jenkinsci.plugins.vsphere.tools.VSphereHostSelection;
import org.jenkinsci.plugins.vsphere.tools.VSphereLogger;
import org.kohsuke.stapler.AncestorInPath;
import org.kohsuke.stapler.DataBoundConstructor;
import org.kohsuke.stapler.DataBoundSetter;
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

    /** Optionally used by {@code #linkedClone} setting or on its own,
     *  conflicts with {@code #namedSnapshot}. Is {@code null} by default. */
    private final Boolean useCurrentSnapshot;
    /** Optionally used by {@code #linkedClone} setting or on its own,
     *  conflicts with {@code #useCurrentSnapshot}. Is {@code null} by default. */
    private final String namedSnapshot;
    private final Map<String, String> extraConfigParameters;

    /** Optional; unset means unchanged legacy behaviour (vCenter's own default placement). */
    private String host;
    /** Optional; one of "", "LEAST_LOADED", "DRS_RECOMMENDED". Ignored when {@code host} is set. */
    private String hostSelectionMode;
    /** Optional allow-list restricting {@code hostSelectionMode}'s candidates. */
    private Set<String> hostSelectionCandidates;

    @DataBoundConstructor
    public Clone(String sourceName, String clone, boolean linkedClone,
                 String resourcePool, String cluster, String datastore, String folder,
                 boolean powerOn, Integer timeoutInSeconds, String customizationSpec,
                 Boolean useCurrentSnapshot, String namedSnapshot,
                 Map<String, String> extraConfigParameters) throws VSphereException {
        this.sourceName = sourceName;
        this.clone = clone;
        this.linkedClone = linkedClone;
        this.resourcePool = resourcePool;
        this.cluster = cluster;
        this.datastore = datastore;
        this.folder = folder;
        this.customizationSpec = customizationSpec;
        this.powerOn = powerOn;
        this.timeoutInSeconds = timeoutInSeconds;
        this.useCurrentSnapshot = useCurrentSnapshot;

        // Config form data may involve empty strings - treat them as null
        if (namedSnapshot == null || namedSnapshot.isEmpty()) {
            this.namedSnapshot = null;
        } else {
            this.namedSnapshot = namedSnapshot;
        }

        if (extraConfigParameters == null || extraConfigParameters.isEmpty()) {
            this.extraConfigParameters = null;
        } else {
            this.extraConfigParameters = extraConfigParameters;
        }
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

    public String getNamedSnapshot() {
        return namedSnapshot;
    }

    public boolean isUseCurrentSnapshot() {
        if (useCurrentSnapshot == null) {
            if (namedSnapshot == null) {
                // Hard-coded default in VSphere.cloneVm()
                // TOTHINK: Should this rely on linkedClone value?
                return true;
            }

            // Will use specified named snapshot
            return false;
        }

        // Caller had an explicit request
        // Note that if linkedClone==true, at least some snapshot must be used
        return useCurrentSnapshot;
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

    public Map<String, String> getExtraConfigParameters() {
        return extraConfigParameters;
    }

    public String getHost() {
        return host;
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
        this.hostSelectionCandidates = hostSelectionCandidates == null ? null : new LinkedHashSet<>(hostSelectionCandidates);
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
        String expandedNamedSnapshot = namedSnapshot;
        String expandedHost = host;
        Set<String> expandedHostSelectionCandidates = hostSelectionCandidates;
        Map<String, String> expandedExtraConfigParameters;
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
            if (namedSnapshot != null) {
                expandedNamedSnapshot = env.expand(namedSnapshot);
            }
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

        if (extraConfigParameters != null && !(extraConfigParameters.isEmpty())) {
            // Always pass a copy of the non-trivial original parameter map
            // (expanded or not), just in case, to protect caller's data.
            expandedExtraConfigParameters = new HashMap<String, String>();
            if (run instanceof AbstractBuild) {
                extraConfigParameters.forEach((k, v) -> expandedExtraConfigParameters.put(k, env.expand(v)));
            } else {
                expandedExtraConfigParameters.putAll(extraConfigParameters);
            }
        } else {
            // Only init to null here, due to lambda used in forEach() above
            expandedExtraConfigParameters = null;
        }

        final vSphereCloud sourceCloud = getSourceCloud();
        final String cloudDefaultHostSelectionMode = sourceCloud != null ? sourceCloud.getHostSelectionMode() : null;
        final Set<String> cloudDefaultHostSelectionCandidates = sourceCloud != null ? sourceCloud.getHostSelectionCandidates() : null;
        final String resolvedHostSelectionMode = VSphereHostSelection.resolveMode(cloudDefaultHostSelectionMode, hostSelectionMode);
        final Set<String> resolvedHostSelectionCandidates = VSphereHostSelection.resolveCandidates(cloudDefaultHostSelectionCandidates, expandedHostSelectionCandidates);

        vsphere.cloneOrDeployVm(expandedClone, expandedSource, linkedClone, expandedResourcePool, expandedCluster,
                expandedDatastore, expandedFolder, this.isUseCurrentSnapshot(), expandedNamedSnapshot,
                powerOn, expandedExtraConfigParameters, expandedCustomizationSpec,
                expandedHost, resolvedHostSelectionMode, resolvedHostSelectionCandidates, jLogger);

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
            VSphere vsphere = null;
            try {
                if (serverName == null){
                    return FormValidation.error(Messages.validation_required("serverName"));
                }
                vsphere = getVSphereCloudByName(serverName, null).vSphereInstance();

                VirtualMachine virtualMachine = vsphere.getVmByName(sourceName);
                if (virtualMachine == null) {
                    return FormValidation.error("The source VM \""+sourceName+"\"was not found cannot check the configuration.");
                }
                if ((virtualMachine.getConfig().template) && (value.length() == 0)) {
                    return FormValidation.error(Messages.validation_required("the resource pool"));
                }
            } catch (VSphereException ve) {
                return FormValidation.error("Cannot connect to vsphere. "+ve.getMessage());
            } finally {
                if (vsphere != null) {
                    vsphere.disconnect();
                }
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

        public ListBoxModel doFillHostSelectionModeItems() {
            ListBoxModel items = new ListBoxModel();
            items.add("(none - inherit the cloud's default)", "");
            items.add("Explicitly none (override the cloud's default)", VSphereHostSelection.HOST_SELECTION_MODE_NONE);
            items.add("Least loaded host (CPU/memory, no DRS license required)", "LEAST_LOADED");
            items.add("DRS recommendation (requires DRS enabled + licensed on the cluster)", "DRS_RECOMMENDED");
            return items;
        }

        public FormValidation doCheckTimeoutInSeconds(@QueryParameter String value) {
            return FormValidation.validateNonNegativeInteger(value);
        }

        @RequirePOST
        public FormValidation doTestData(@AncestorInPath Item context,
                                         @QueryParameter String serverName,
                                         @QueryParameter String sourceName, @QueryParameter String clone,
                                         @QueryParameter String resourcePool, @QueryParameter String cluster,
                                         @QueryParameter String customizationSpec,
                                         @QueryParameter Boolean linkedClone,
                                         @QueryParameter Boolean useCurrentSnapshot,
                                         @QueryParameter String namedSnapshot,
                                         @QueryParameter String host,
                                         @QueryParameter String hostSelectionCandidatesAsString) {
            // TODO? @QueryParameter Map<String, String> extraConfigParameters
            throwUnlessUserHasPermissionToConfigureJob(context);
            VSphere vsphere = null;
            try {
                if (sourceName.length() == 0 || clone.length()==0 || serverName.length()==0
                        || cluster.length()==0 )
                    return FormValidation.error(Messages.validation_requiredValues());

                vsphere = getVSphereCloudByName(serverName, null).vSphereInstance();

                //TODO what if clone name is variable?
                VirtualMachine cloneVM = vsphere.getVmByName(clone);
                if (cloneVM != null)
                    return FormValidation.error(Messages.validation_exists("clone"));

                if (sourceName.indexOf('$') >= 0)
                    return FormValidation.warning(Messages.validation_buildParameter("sourceName"));

                VirtualMachine vm = vsphere.getVmByName(sourceName);
                if (vm == null)
                    return FormValidation.error(Messages.validation_notFound("sourceName"));

                if (linkedClone || useCurrentSnapshot || (namedSnapshot != null && !(namedSnapshot.isEmpty()))) {
                    // Use-case (according to parameters) requires a snapshot
                    VirtualMachineSnapshot snap;
                    if (namedSnapshot == null || namedSnapshot.isEmpty()) {
                        // either useCurrentSnapshot or linkedClone is true
                        snap = vm.getCurrentSnapShot();
                    } else {
                        // namedSnapshot is non-trivial
                        if (useCurrentSnapshot)
                            return FormValidation.error(Messages.validation_useCurrentAndNamedSnapshots());
                        snap = vsphere.getSnapshotInTree(vm, namedSnapshot);
                    }
                    if (snap == null)
                        return FormValidation.error(Messages.validation_noSnapshots());
                }

                if(customizationSpec != null && customizationSpec.length() > 0 &&
                        vsphere.getCustomizationSpecByName(customizationSpec) == null) {
                    return FormValidation.error(Messages.validation_notFound("customizationSpec"));
                }

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
}
