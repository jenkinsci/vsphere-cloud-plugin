/*
 * Copyright 2015 ksmith.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.jenkinsci.plugins;

import static org.jenkinsci.plugins.vsphere.tools.PermissionUtils.throwUnlessUserHasPermissionToConfigureCloud;

import hudson.DescriptorExtensionList;
import hudson.EnvVars;
import hudson.Extension;
import hudson.Util;
import hudson.model.Describable;
import hudson.model.TaskListener;
import hudson.model.Descriptor;
import hudson.model.Descriptor.FormException;
import hudson.model.Label;
import hudson.model.Node.Mode;
import hudson.model.labels.LabelAtom;
import hudson.plugins.sshslaves.SSHLauncher;
import hudson.slaves.NodeProperty;
import hudson.slaves.NodePropertyDescriptor;
import hudson.slaves.CommandLauncher;
import hudson.slaves.ComputerLauncher;
import hudson.slaves.JNLPLauncher;
import hudson.slaves.RetentionStrategy;
import hudson.util.FormValidation;

import java.io.IOException;
import java.io.PrintStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.stream.Collectors;

import edu.umd.cs.findbugs.annotations.NonNull;

import jenkins.model.Jenkins;
import jenkins.slaves.JnlpSlaveAgentProtocol;

import org.jenkinsci.plugins.vsphere.RunOnceCloudRetentionStrategy;
import org.jenkinsci.plugins.vsphere.VSphereCloudRetentionStrategy;
import org.jenkinsci.plugins.vsphere.VSphereConnectionConfig;
import org.jenkinsci.plugins.vsphere.VSphereGuestInfoProperty;
import org.jenkinsci.plugins.vsphere.builders.Messages;
import org.jenkinsci.plugins.vsphere.tools.VSphere;
import org.jenkinsci.plugins.vsphere.tools.VSphereDuplicateException;
import org.jenkinsci.plugins.vsphere.tools.VSphereException;
import org.kohsuke.accmod.Restricted;
import org.kohsuke.accmod.restrictions.NoExternalUse;
import org.kohsuke.stapler.AncestorInPath;
import org.kohsuke.stapler.DataBoundConstructor;
import org.kohsuke.stapler.QueryParameter;
import org.kohsuke.stapler.interceptor.RequirePOST;

import com.cloudbees.hudson.plugins.folder.AbstractFolder;
import com.cloudbees.plugins.credentials.domains.SchemeRequirement;
import com.vmware.vim25.OptionValue;
import com.vmware.vim25.VirtualMachineConfigInfo;
import com.vmware.vim25.mo.VirtualMachine;

/**
 *
 * @author ksmith
 */
public class vSphereCloudSlaveTemplate implements Describable<vSphereCloudSlaveTemplate> {
    private static final Logger LOGGER = Logger.getLogger(vSphereCloudSlaveTemplate.class.getName());
    private static final String VSPHERE_ATTR_FOR_JENKINSURL = vSphereCloudSlaveTemplate.class.getSimpleName()
            + ".jenkinsUrl";

    protected static final SchemeRequirement HTTP_SCHEME = new SchemeRequirement("http");
    protected static final SchemeRequirement HTTPS_SCHEME = new SchemeRequirement("https");

    private int configVersion;
    private static final int CURRENT_CONFIG_VERSION = 1;
    private String cloneNamePrefix; // almost final
    private final String masterImageName;
    private Boolean useSnapshot; // almost final
    private final String snapshotName;
    private final boolean linkedClone;
    private final String cluster;
    private final String resourcePool;
    private final String datastore;
    private final String folder;
    private final String customizationSpec;
    private final String templateDescription;
    private int templateInstanceCap;
    private final int numberOfExecutors;
    private final String remoteFS;
    private final String labelString;
    private final Mode mode;
    private final boolean forceVMLaunch;
    private final boolean waitForVMTools;
    private final int launchDelay;
    private final int limitedRunCount;
    private final boolean saveFailure;
    private final String targetResourcePool;
    private final String targetHost;
    /**
     * Credentials from old configuration format. Credentials are now in the
     * {@link #launcher} configuration
     */
    @Deprecated()
    private transient final String credentialsId;
    private final List<? extends NodeProperty<?>> nodeProperties;
    private final List<? extends VSphereGuestInfoProperty> guestInfoProperties;
    private ComputerLauncher launcher;
    private RetentionStrategy<?> retentionStrategy;

    private transient Set<LabelAtom> labelSet;
    protected transient vSphereCloud parent;

    @DataBoundConstructor
    public vSphereCloudSlaveTemplate(final String cloneNamePrefix,
                                     final String masterImageName,
                                     final Boolean useSnapshot,
                                     final String snapshotName,
                                     final boolean linkedClone,
                                     final String cluster,
                                     final String resourcePool,
                                     final String datastore,
                                     final String folder,
                                     final String customizationSpec,
                                     final String templateDescription,
                                     final int templateInstanceCap,
                                     final int numberOfExecutors,
                                     final String remoteFS,
                                     final String labelString,
                                     final Mode mode,
                                     final boolean forceVMLaunch,
                                     final boolean waitForVMTools,
                                     final int launchDelay,
                                     final int limitedRunCount,
                                     final boolean saveFailure,
                                     final String targetResourcePool,
                                     final String targetHost,
                                     final String credentialsId /*deprecated*/,
                                     final ComputerLauncher launcher,
                                     final RetentionStrategy<?> retentionStrategy,
                                     final List<? extends NodeProperty<?>> nodeProperties,
                                     final List<? extends VSphereGuestInfoProperty> guestInfoProperties) {
        this.configVersion = CURRENT_CONFIG_VERSION;
        this.cloneNamePrefix = cloneNamePrefix;
        this.masterImageName = masterImageName;
        this.snapshotName = snapshotName;
        this.useSnapshot = useSnapshot;
        this.linkedClone = linkedClone;
        this.cluster = cluster;
        this.resourcePool = resourcePool;
        this.datastore = datastore;
        this.folder = folder;
        this.customizationSpec = customizationSpec;
        this.templateDescription = templateDescription;
        this.templateInstanceCap = templateInstanceCap;
        this.numberOfExecutors = numberOfExecutors;
        this.remoteFS = remoteFS;
        this.labelString = labelString;
        this.mode = mode;
        this.forceVMLaunch = forceVMLaunch;
        this.waitForVMTools = waitForVMTools;
        this.launchDelay = launchDelay;
        this.limitedRunCount = limitedRunCount;
        this.saveFailure = saveFailure;
        this.targetResourcePool = targetResourcePool;
        this.targetHost = targetHost;
        this.credentialsId = credentialsId;
        this.nodeProperties = Util.fixNull(nodeProperties);
        this.guestInfoProperties = Util.fixNull(guestInfoProperties);
        this.launcher = launcher;
        this.retentionStrategy = retentionStrategy;
        readResolve();
    }

    public String getCloneNamePrefix() {
        return this.cloneNamePrefix;
    }

    public String getMasterImageName() {
        return this.masterImageName;
    }

    public boolean getUseSnapshot() {
        return useSnapshot.booleanValue();
    }

    public String getSnapshotName() {
        return this.snapshotName;
    }

    public boolean getLinkedClone() {
        return this.linkedClone;
    }

    public String getCluster() {
        return this.cluster;
    }

    public String getResourcePool() {
        return this.resourcePool;
    }

    public String getDatastore() {
        return this.datastore;
    }
    
    public String getFolder() {
        return this.folder;
    }

    public String getCustomizationSpec() {
        return this.customizationSpec;
    }

    public String getTemplateDescription() {
        return this.templateDescription;
    }

    public int getTemplateInstanceCap() {
        if(this.templateInstanceCap == Integer.MAX_VALUE) {
            return 0;
        }
        return this.templateInstanceCap;
    }

    public int getNumberOfExecutors() {
        return this.numberOfExecutors;
    }

    public String getRemoteFS() {
        return this.remoteFS;
    }

    public String getLabelString() {
        return this.labelString;
    }

    public Mode getMode() {
        return this.mode;
    }

    public boolean getForceVMLaunch() {
        return this.forceVMLaunch;
    }

    public boolean getWaitForVMTools() {
        return this.waitForVMTools;
    }

    public int getLaunchDelay() {
        return this.launchDelay;
    }

    public int getLimitedRunCount() {
        return this.limitedRunCount;
    }

    public boolean getSaveFailure() {
        return this.saveFailure;
    }

    public String getTargetResourcePool() {
        return this.targetResourcePool;
    }

    public String getTargetHost() {
        return this.targetHost;
    }

    /**
     * Gets the old (deprecated) credentialsId field.
     * 
     * @return the old, deprecated, credentialsId field.
     * @deprecated credentials are now in the {@link #getLauncher()} property.
     */
    @Deprecated()
    public String getCredentialsId() {
        return this.credentialsId;
    }

    public List<? extends NodeProperty<?>> getNodeProperties() {
        return this.nodeProperties;
    }

    public List<? extends VSphereGuestInfoProperty> getGuestInfoProperties() {
        return this.guestInfoProperties;
    }

    public Set<LabelAtom> getLabelSet() {
        return this.labelSet;
    }

    public vSphereCloud getParent() {
        return this.parent;
    }

    public ComputerLauncher getLauncher() {
        return this.launcher;
    }

    public RetentionStrategy<?> getRetentionStrategy() {
        return this.retentionStrategy;
    }

    protected Object readResolve() {
        this.labelSet = Label.parse(labelString);
        if(this.templateInstanceCap == 0) {
            this.templateInstanceCap = Integer.MAX_VALUE;
        }
        if ( this.useSnapshot == null ) {
            this.useSnapshot = Boolean.valueOf(this.snapshotName!=null);
        }
        /*
         * If we've upgraded from an earlier version of the plugin
         * where things were hard-coded instead of configurable
         * then we'll need to transfer the data across to the new
         * format.
         */
        if (this.launcher == null) {
            LOGGER.log(Level.CONFIG, "{0} loaded old configuration that had hard-coded SSHLauncher.", this);
            try {
                final String oldCredentialsIdOrNull = getCredentialsId();
                final String oldCredentialsId = oldCredentialsIdOrNull == null ? "" : oldCredentialsIdOrNull;
                // these were the old hard-coded settings
                this.launcher = new SSHLauncher(null, 0, oldCredentialsId, null, null, null, null, this.launchDelay, 3, 60, null);
                LOGGER.log(Level.CONFIG, " - now configured to use {0}(..., {1}, ...)", new Object[] {
                        this.launcher.getClass().getSimpleName(), oldCredentialsId });
            } catch (Exception ex) {
                LOGGER.log(Level.CONFIG, " - Failed to reconfigure launcher", ex);
            }
        }
        if (this.retentionStrategy == null) {
            LOGGER.log(Level.CONFIG, "{0} loaded old configuration that had hard-coded RunOnceCloudRetentionStrategy.",
                    this);
            try {
                // these were the old hard-coded settings
                final int oldTimeout = 2;
                this.retentionStrategy = new RunOnceCloudRetentionStrategy(oldTimeout);
                LOGGER.log(Level.CONFIG, " - now configured to use {0}({1})", new Object[] {
                        this.retentionStrategy.getClass().getSimpleName(), oldTimeout });
            } catch (Exception ex) {
                LOGGER.log(Level.CONFIG, " - Failed to reconfigure strategy", ex);
            }
        }
        // Earlier versions of the code may have stored configuration in a
        // different way but in the same kind of fields, so we need an explicit
        // versioning to know how to mutate the data.
        if (configVersion <= 0) {
            LOGGER.log(Level.CONFIG,
                    "{0} loaded old configuration that had hard-coded underscore at the end of the cloneNamePrefix.",
                    this);
            // In version one, the underscore was removed from the code so we
            // have to put it in the data (the user is then free to change it to
            // something else if they want to).
            this.cloneNamePrefix = this.cloneNamePrefix + "_";
            configVersion = 1;
        }
        // Note: Subsequent changes dependent on configVersion should go above
        // this line.
        if (configVersion < CURRENT_CONFIG_VERSION) {
            throw new IllegalStateException("Internal error: configVersion==" + configVersion
                    + " at end of readResolve method, but the current config version should be "
                    + CURRENT_CONFIG_VERSION
                    + ".  Either CURRENT_CONFIG_VERSION is incorrect or the readResolve method is not setting configVersion when it upgrades the data.");
        }
        if (configVersion > CURRENT_CONFIG_VERSION) {
            LOGGER.log(Level.WARNING,
                    "{0} was defined by a later version of the plugin "
                            + "(one that saved with configVersion={1}, whereas this version of the plugin is expecting {2}).  "
                            + "The code may not function as expected.",
                    new Object[]{
                            this,
                            configVersion,
                            CURRENT_CONFIG_VERSION
                    });
        }
        return this;
    }

    public vSphereCloudProvisionedSlave provision(final String cloneName, final TaskListener listener) throws VSphereException, FormException, IOException, InterruptedException {
        final PrintStream logger = listener.getLogger();
        final Map<String, String> resolvedExtraConfigParameters = calculateExtraConfigParameters(cloneName, listener);
        final VSphere vSphere = getParent().vSphereInstance();
        final vSphereCloudProvisionedSlave slave;
        try {
            slave = provision(cloneName, logger, resolvedExtraConfigParameters, vSphere);
        } finally {
            vSphere.disconnect();
        }
        return slave;
    }

    private vSphereCloudProvisionedSlave provision(final String cloneName, final PrintStream logger, final Map<String, String> resolvedExtraConfigParameters, final VSphere vSphere) throws VSphereException, FormException, IOException {
        final boolean POWER_ON = true;
        final boolean useCurrentSnapshot;
        final String snapshotToUse;
        if (getUseSnapshot()) {
            final String sn = getSnapshotName();
            if (sn != null && !sn.isEmpty()) {
                useCurrentSnapshot = false;
                snapshotToUse = sn;
            } else {
                useCurrentSnapshot = true;
                snapshotToUse = null;
            }
        } else {
            useCurrentSnapshot = false;
            snapshotToUse = null;
        }
        try {
            vSphere.cloneOrDeployVm(cloneName, this.masterImageName, this.linkedClone, this.resourcePool, this.cluster, this.datastore, this.folder, useCurrentSnapshot, snapshotToUse, POWER_ON, resolvedExtraConfigParameters, this.customizationSpec, logger);
            LOGGER.log(Level.FINE, "Created new VM {0} from image {1}", new Object[]{ cloneName, this.masterImageName });
        } catch (VSphereDuplicateException ex) {
            final String vmJenkinsUrl = findWhichJenkinsThisVMBelongsTo(vSphere, cloneName);
            if ( vmJenkinsUrl==null ) {
                LOGGER.log(Level.SEVERE, "VM {0} name clashes with one we wanted to use, but it wasn't started by this plugin.", cloneName );
                throw ex;
            }
            final String ourJenkinsUrl = Jenkins.getInstance().getRootUrl();
            if ( vmJenkinsUrl.equals(ourJenkinsUrl) ) {
                LOGGER.log(Level.INFO, "Found existing VM {0} that we started previously (and must have either lost track of it or failed to delete it).", cloneName );
            } else {
                LOGGER.log(Level.SEVERE, "VM {0} name clashes with one we wanted to use, but it doesn't belong to this Jenkins server: it belongs to {1}.  You MUST reconfigure one of these Jenkins servers to use a different naming strategy so that we no longer get clashes within vSphere host {2}. i.e. change the cloneNamePrefix on one/both to ensure uniqueness.", new Object[]{ cloneName, vmJenkinsUrl, this.getParent().getVsHost() } );
                throw ex;
            }
        } catch (VSphereException ex) {
            // if anything else went wrong, attempt to tidy up
            try {
                vSphere.destroyVm(cloneName, false);
            } catch (Exception logOnly) {
                LOGGER.log(Level.SEVERE,
                        "Unable to create and power-on new VM " + cloneName + " (cloned from image "
                                + this.masterImageName
                                + ") and, worse, bits of the VM may still exist as the attempt to delete the remains also failed.",
                        logOnly);
            }
            throw ex;
        }
        vSphereCloudProvisionedSlave slave = null;
        try {
            final ComputerLauncher configuredLauncher = determineLauncher(vSphere, cloneName);
            final RetentionStrategy<?> configuredStrategy = determineRetention();
            final String snapshotNameForLauncher = ""; /* we don't make the launcher do anything with snapshots because our clone won't be created with any */
            slave = new vSphereCloudProvisionedSlave(cloneName, getTemplateDescription(), getRemoteFS(),
                    String.valueOf(getNumberOfExecutors()), getMode(), getLabelString(), configuredLauncher,
                    configuredStrategy, makeCopyOfList(getNodeProperties()), getParent().getVsDescription(), cloneName,
                    getForceVMLaunch(), getWaitForVMTools(), snapshotNameForLauncher, String.valueOf(getLaunchDelay()),
                    null, String.valueOf(getLimitedRunCount()));
        } finally {
            // if anything went wrong, try to tidy up
            if( slave==null ) {
                LOGGER.log(Level.FINER, "Creation of slave failed after cloning VM: destroying clone {0}", cloneName);
                vSphere.destroyVm(cloneName, false);
            }
        }
        return slave;
    }

    private <T> List<T> makeCopyOfList(List<? extends T> listOrNull) {
        final List<? extends T> originalList = Util.fixNull(listOrNull);
        final List<T> copyList = new ArrayList<T>(originalList.size());
        for( final T originalElement : originalList) {
            final T copyOfElement = makeCopy(originalElement);
            copyList.add(copyOfElement);
        }
        return copyList;
    }

    @SuppressWarnings("unchecked")
    private static <T> T makeCopy(final T original) {
        final String xml = Jenkins.XSTREAM.toXML(original);
        final Object copy = Jenkins.XSTREAM.fromXML(xml);
        return (T) copy;
    }

    private ComputerLauncher determineLauncher(final VSphere vSphere, final String cloneName) throws VSphereException {
        if (launcher instanceof JNLPLauncher) {
            return launcher;
        }
        if (launcher instanceof CommandLauncher) {
            return launcher;
        }
        if (launcher instanceof SSHLauncher) {
            final SSHLauncher sshLauncher = (SSHLauncher) launcher;
            LOGGER.log(Level.FINER, "Slave {0} uses SSHLauncher - obtaining IP address...", cloneName);
            final String ip = vSphere.getIp(vSphere.getVmByName(cloneName), 1000);
            LOGGER.log(Level.FINER, "Slave {0} has IP address {1}", new Object[] { cloneName, ip });
            final SSHLauncher launcherWithIPAddress = new SSHLauncher(ip, sshLauncher.getPort(),
                    sshLauncher.getCredentialsId(), sshLauncher.getJvmOptions(), sshLauncher.getJavaPath(),
                    sshLauncher.getPrefixStartSlaveCmd(), sshLauncher.getSuffixStartSlaveCmd(),
                    sshLauncher.getLaunchTimeoutSeconds(), sshLauncher.getMaxNumRetries(),
                    sshLauncher.getRetryWaitTime(), sshLauncher.getSshHostKeyVerificationStrategy());
            return launcherWithIPAddress;
        }
        throw new IllegalStateException("Unsupported launcher (" + launcher + ") in template configuration");
    }

    private RetentionStrategy<?> determineRetention() {
        if (retentionStrategy instanceof RunOnceCloudRetentionStrategy) {
            final RunOnceCloudRetentionStrategy templateStrategy = (RunOnceCloudRetentionStrategy) retentionStrategy;
            final RunOnceCloudRetentionStrategy cloneStrategy = new RunOnceCloudRetentionStrategy(
                    templateStrategy.getIdleMinutes());
            return cloneStrategy;
        }
        if (retentionStrategy instanceof VSphereCloudRetentionStrategy) {
            final VSphereCloudRetentionStrategy templateStrategy = (VSphereCloudRetentionStrategy) retentionStrategy;
            final VSphereCloudRetentionStrategy cloneStrategy = new VSphereCloudRetentionStrategy(
                    templateStrategy.getIdleMinutes());
            return cloneStrategy;
        }
        throw new IllegalStateException(
                "Unsupported retentionStrategy (" + retentionStrategy + ") in template configuration");
    }

    @SuppressWarnings("unchecked")
    @Override
    public Descriptor<vSphereCloudSlaveTemplate> getDescriptor() {
        return Jenkins.getInstance().getDescriptor(getClass());
    }

    @Extension
    public static final class DescriptorImpl extends Descriptor<vSphereCloudSlaveTemplate> {
        @Override
        public String getDisplayName() {
            return null;
        }

        public FormValidation doCheckCloneNamePrefix(@QueryParameter String cloneNamePrefix) {
            return FormValidation.validateRequired(cloneNamePrefix);
        }

        public FormValidation doCheckLimitedRunCount(@QueryParameter String limitedRunCount) {
            return FormValidation.validateNonNegativeInteger(limitedRunCount);
        }

        public FormValidation doCheckTemplateInstanceCap(@QueryParameter String templateInstanceCap) {
            return FormValidation.validateNonNegativeInteger(templateInstanceCap);
        }

        public FormValidation doCheckNumberOfExecutors(@QueryParameter String numberOfExecutors) {
            return FormValidation.validatePositiveInteger(numberOfExecutors);
        }

        public FormValidation doCheckLinkedClone(@QueryParameter boolean linkedClone, @QueryParameter boolean useSnapshot) {
            final boolean noSnapshot = !useSnapshot;
            if (linkedClone && noSnapshot) {
                return FormValidation.warning("Linked clones are based upon a snapshot.");
            }
            return FormValidation.ok();
        }

        public FormValidation doCheckLaunchDelay(@QueryParameter String launchDelay) {
            return FormValidation.validateNonNegativeInteger(launchDelay);
        }

        @RequirePOST
        public FormValidation doTestCloneParameters(@AncestorInPath AbstractFolder<?> containingFolderOrNull,
                @QueryParameter String vsHost,
                @QueryParameter boolean allowUntrustedCertificate,
                @QueryParameter String credentialsId, @QueryParameter String masterImageName,
                @QueryParameter boolean linkedClone, @QueryParameter boolean useSnapshot,
                @QueryParameter String snapshotName) {
            throwUnlessUserHasPermissionToConfigureCloud(containingFolderOrNull);
            try {
                final VSphereConnectionConfig config = new VSphereConnectionConfig(vsHost, allowUntrustedCertificate, credentialsId);
                final VSphere vsphere = VSphere.connect(config);
                try {
                    final VirtualMachine vm = vsphere.getVmByName(masterImageName);
                    if (vm == null) {
                        return FormValidation.error(Messages.validation_notFound("master image \"" + masterImageName
                                + "\""));
                    }
                    if (useSnapshot) {
                        if (snapshotName != null && !snapshotName.isEmpty()) {
                            final Object snapshot = vsphere.getSnapshotInTree(vm, snapshotName);
                            if (snapshot == null) {
                                return FormValidation.error(Messages.validation_notFound("snapshot \"" + snapshotName
                                        + "\""));
                            }
                        } else {
                            final Object snapshot = vm.getCurrentSnapShot();
                            if (snapshot == null) {
                                return FormValidation.error("No snapshots found.");
                            }
                        }
                    } else {
                        if (linkedClone) {
                            return FormValidation.warning("vSphere doesn't like creating linked clones without a snapshot");
                        }
                    }
                    return FormValidation.ok(Messages.validation_success());
                } finally {
                    vsphere.disconnect();
                }
            } catch (Exception e) {
                return FormValidation.error(e, "Problem validating");
            }
        }

        public static List<Descriptor<ComputerLauncher>> getLauncherDescriptors() {
            final List<String> supportedLaunchers = Arrays.asList(
                    SSHLauncher.class.getName(),
                    CommandLauncher.class.getName(),
                    JNLPLauncher.class.getName()
            );
            final List<Descriptor<ComputerLauncher>> knownLaunchers = Jenkins.getInstance().getDescriptorList(ComputerLauncher.class);
            final List<Descriptor<ComputerLauncher>> result = new ArrayList<>(knownLaunchers.size());
            for (final Descriptor<ComputerLauncher> knownLauncher : knownLaunchers) {
                if(supportedLaunchers.contains(knownLauncher.getId())) {
                    result.add(knownLauncher);
                }
            }
            return result;
        }

        public static List<Descriptor<RetentionStrategy<?>>> getRetentionStrategyDescriptors() {
            final List<Descriptor<RetentionStrategy<?>>> result = new ArrayList<>();
            result.add(RunOnceCloudRetentionStrategy.DESCRIPTOR);
            result.add(VSphereCloudRetentionStrategy.DESCRIPTOR);
            return result;
        }

        /**
         * Returns the list of {@link NodePropertyDescriptor} appropriate for the
         * {@link vSphereCloudSlave}s that are created from this template.
         *
         * @return the filtered list
         */
        @SuppressWarnings("unchecked")
        @NonNull
        @Restricted(NoExternalUse.class) // used by Jelly EL only
        public List<NodePropertyDescriptor> getNodePropertiesDescriptors() {
            List<NodePropertyDescriptor> result = new ArrayList<>();
            DescriptorExtensionList<NodeProperty<?>, NodePropertyDescriptor> list = NodeProperty.all();
            for (NodePropertyDescriptor npd : list) {
                if (npd.isApplicable(vSphereCloudSlave.class)) {
                    result.add(npd);
                }
            }
            return result;
        }
    }

    private static String findWhichJenkinsThisVMBelongsTo(final VSphere vSphere, String cloneName) {
        final VirtualMachine vm;
        try {
            vm = vSphere.getVmByName(cloneName);
        } catch (VSphereException e) {
            LOGGER.log(Level.WARNING, "findWhichJenkinsThisVMBelongsTo(vSphere,\""+cloneName+"\") failed to getVmByName.", e );
            return null;
        }
        final VirtualMachineConfigInfo config = vm.getConfig();
        if (config == null) {
            // TODO: If this happens, it causes JENKINS-54521
            LOGGER.log(Level.WARNING, "findWhichJenkinsThisVMBelongsTo(vSphere,\""+cloneName+"\") failed to getConfig." );
            return null;
        }
        final OptionValue[] extraConfigs = config.getExtraConfig();
        if (extraConfigs == null) {
            LOGGER.log(Level.WARNING, "findWhichJenkinsThisVMBelongsTo(vSphere,\""+cloneName+"\") failed to getExtraConfig." );
            return null;
        }
        String vmJenkinsUrl = null;
        for (final OptionValue ec : extraConfigs) {
            final String configName = ec.getKey();
            final Object valueObject = ec.getValue();
            final String configValue = valueObject == null ? null : valueObject.toString();
            if (VSPHERE_ATTR_FOR_JENKINSURL.equals(configName)) {
                vmJenkinsUrl = configValue;
            }
        }
        return vmJenkinsUrl;
    }

    private Map<String, String> calculateExtraConfigParameters(final String cloneName, final TaskListener listener)
            throws IOException, InterruptedException {
        final EnvVars knownVariables = calculateVariablesForGuestInfo(cloneName, listener);
        final Map<String, String> result = new LinkedHashMap<String, String>();
        final String jenkinsUrl = Jenkins.getInstance().getRootUrl();
        if (jenkinsUrl != null) {
            result.put(VSPHERE_ATTR_FOR_JENKINSURL, jenkinsUrl);
        }
        List<? extends VSphereGuestInfoProperty> guestInfoConfig = this.guestInfoProperties;
        if (guestInfoConfig == null) {
            guestInfoConfig = Collections.emptyList();
        }
        for (final VSphereGuestInfoProperty property : guestInfoConfig) {
            final String name = property.getName();
            final String configuredValue = property.getValue();
            final String resolvedValue = Util.replaceMacro(configuredValue, knownVariables);
            result.put("guestinfo." + name, resolvedValue);
        }
        return result;
    }

    private EnvVars calculateVariablesForGuestInfo(final String cloneName, final TaskListener listener)
            throws IOException, InterruptedException {
        final EnvVars knownVariables = new EnvVars();
        // Maintenance note: If you update this method, you must also update the
        // UI help page to match.
        final String jenkinsUrl = Jenkins.getInstance().getRootUrl();
        if (jenkinsUrl != null) {
            addEnvVar(knownVariables, "JENKINS_URL", jenkinsUrl);
            addEnvVar(knownVariables, "HUDSON_URL", jenkinsUrl);
        }
        final String slaveSecret = JnlpSlaveAgentProtocol.SLAVE_SECRET.mac(cloneName);
        if (slaveSecret != null) {
            addEnvVar(knownVariables, "JNLP_SECRET", slaveSecret);
        }
        addEnvVars(knownVariables, listener, Jenkins.getInstance().getGlobalNodeProperties());
        addEnvVars(knownVariables, listener, this.nodeProperties);
        addEnvVar(knownVariables, "NODE_NAME", cloneName);
        addEnvVar(knownVariables, "NODE_LABELS", getLabelSet() == null ? null : getLabelSet().stream().map(Object::toString).collect(Collectors.joining(" ")));
        addEnvVar(knownVariables, "cluster", this.cluster);
        addEnvVar(knownVariables, "datastore", this.datastore);
        addEnvVar(knownVariables, "folder", this.folder);
        addEnvVar(knownVariables, "customizationSpec", this.customizationSpec);
        addEnvVar(knownVariables, "labelString", this.labelString);
        addEnvVar(knownVariables, "masterImageName", this.masterImageName);
        addEnvVar(knownVariables, "remoteFS", this.remoteFS);
        addEnvVar(knownVariables, "snapshotName", this.snapshotName);
        addEnvVar(knownVariables, "targetHost", this.targetHost);
        addEnvVar(knownVariables, "templateDescription", this.templateDescription);
        return knownVariables;
    }

    private static void addEnvVars(final EnvVars vars, final TaskListener listener, final Iterable<? extends NodeProperty<?>> nodeProperties) throws IOException, InterruptedException {
        if( nodeProperties!=null ) {
            for (final NodeProperty<?> nodeProperty: nodeProperties) {
                nodeProperty.buildEnvVars(vars , listener);
            }
        }
    }

    private static void addEnvVar(final EnvVars vars, final String name, final Object valueOrNull) {
        vars.put(name, valueOrNull==null?"":valueOrNull.toString());
    }
}
