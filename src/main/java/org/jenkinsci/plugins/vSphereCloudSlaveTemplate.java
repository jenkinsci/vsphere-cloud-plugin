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

import com.cloudbees.plugins.credentials.common.StandardUsernameCredentials;
import com.cloudbees.plugins.credentials.common.StandardUsernameListBoxModel;
import com.cloudbees.plugins.credentials.domains.SchemeRequirement;

import hudson.EnvVars;
import hudson.Extension;
import hudson.Util;
import hudson.model.Computer;
import hudson.model.Describable;
import hudson.model.Descriptor;
import hudson.model.ItemGroup;
import hudson.security.ACL;
import hudson.security.AccessControlled;
import hudson.util.FormValidation;
import hudson.util.ListBoxModel;

import java.util.List;
import java.util.logging.Level;
import java.util.logging.Logger;

import jenkins.model.Jenkins;

import org.kohsuke.stapler.AncestorInPath;
import org.kohsuke.stapler.DataBoundConstructor;
import org.kohsuke.stapler.QueryParameter;

import static com.cloudbees.plugins.credentials.CredentialsProvider.lookupCredentials;
import hudson.model.Descriptor.FormException;
import hudson.model.Label;
import hudson.model.Node.Mode;
import hudson.model.TaskListener;
import hudson.model.labels.LabelAtom;
import hudson.plugins.sshslaves.SSHLauncher;
import hudson.slaves.NodeProperty;
import hudson.slaves.ComputerLauncher;
import hudson.slaves.RetentionStrategy;

import java.io.IOException;
import java.io.PrintStream;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

import org.jenkinsci.plugins.vsphere.VSphereGuestInfoProperty;
import org.jenkinsci.plugins.vsphere.tools.CloudProvisioningState;
import org.jenkinsci.plugins.vsphere.tools.VSphere;
import org.jenkinsci.plugins.vsphere.tools.VSphereException;

/**
 *
 * @author ksmith
 */
public class vSphereCloudSlaveTemplate implements Describable<vSphereCloudSlaveTemplate> {
    private static final Logger LOGGER = Logger.getLogger(vSphereCloudSlaveTemplate.class.getName());

    protected static final SchemeRequirement HTTP_SCHEME = new SchemeRequirement("http");
    protected static final SchemeRequirement HTTPS_SCHEME = new SchemeRequirement("https");

    private final String cloneNamePrefix;
    private final String masterImageName;
    private final String snapshotName;
    private final boolean linkedClone;
    private final String cluster;
    private final String resourcePool;
    private final String datastore;
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
    private final String credentialsId;
    private final List<? extends NodeProperty<?>> nodeProperties;
    private final List<? extends VSphereGuestInfoProperty> guestInfoProperties;

    private transient Set<LabelAtom> labelSet;
    protected transient vSphereCloud parent;

    @DataBoundConstructor
    public vSphereCloudSlaveTemplate(final String cloneNamePrefix,
                final String masterImageName,
                final String snapshotName,
                final boolean linkedClone,
                final String cluster,
                final String resourcePool,
                final String datastore,
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
                final String credentialsId,
                final List<? extends NodeProperty<?>> nodeProperties,
                final List<? extends VSphereGuestInfoProperty> guestInfoProperties) {
        this.cloneNamePrefix = cloneNamePrefix;
        this.masterImageName = masterImageName;
        this.snapshotName = snapshotName;
        this.linkedClone = linkedClone;
        this.cluster = cluster;
        this.resourcePool = resourcePool;
        this.datastore = datastore;
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
        this.nodeProperties = nodeProperties;
        this.guestInfoProperties = guestInfoProperties;
        readResolve();
    }

    public String getCloneNamePrefix() {
        return this.cloneNamePrefix;
    }

    public String getMasterImageName() {
        return this.masterImageName;
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

    protected Object readResolve() {
        this.labelSet = Label.parse(labelString);

        if(this.templateInstanceCap == 0) {
            this.templateInstanceCap = Integer.MAX_VALUE;
        }
        return this;
    }

    public vSphereCloudProvisionedSlave provision(final CloudProvisioningState algorithm, final String cloneName, final TaskListener listener) throws VSphereException, FormException, IOException, InterruptedException {
        vSphereCloudProvisionedSlave slave = null;
        final PrintStream logger = listener.getLogger();
        final VSphere vSphere = getParent().vSphereInstance();
        final boolean POWER_ON = true;
        vSphere.cloneVm(cloneName, this.masterImageName, this.linkedClone, this.resourcePool, this.cluster, this.datastore, POWER_ON, logger);
        try {
            if( this.guestInfoProperties!=null && !this.guestInfoProperties.isEmpty()) {
                final Map<String, String> resolvedGuestInfoProperties = calculateGuestInfoProperties(cloneName, listener);
                if( !resolvedGuestInfoProperties.isEmpty() ) {
                    LOGGER.log(Level.FINE, "Provisioning slave {0} with guestinfo properties {1}", new Object[]{ cloneName, resolvedGuestInfoProperties });
                    vSphere.addGuestInfoVariable(cloneName, resolvedGuestInfoProperties);
                }
            }
            final ComputerLauncher configuredLauncher = determineLauncher(vSphere, cloneName);
            final RetentionStrategy<?> configuredStrategy = determineRetention();
            slave = new vSphereCloudProvisionedSlave(cloneName, this.templateDescription, this.remoteFS, String.valueOf(this.numberOfExecutors), this.mode, this.labelString, configuredLauncher, configuredStrategy, this.nodeProperties, this.parent.getVsDescription(), cloneName, this.forceVMLaunch, this.waitForVMTools, snapshotName, String.valueOf(this.launchDelay), null, String.valueOf(this.limitedRunCount));
        } finally {
            // if anything went wrong, try to tidy up
            if( slave==null ) {
                LOGGER.log(Level.FINER, "Creation of slave failed after cloning VM: destroying clone {0}", cloneName);
                vSphere.destroyVm(cloneName, false);
            }
        }
        vSphere.disconnect();
        return slave;
    }

    private ComputerLauncher determineLauncher(final VSphere vSphere, final String cloneName) throws VSphereException {
        LOGGER.log(Level.FINER, "Slave {0} uses SSHLauncher - obtaining IP address...", cloneName);
        final String ip = vSphere.getIp(vSphere.getVmByName(cloneName), 1000);
        LOGGER.log(Level.FINER, "Slave {0} has IP address {1}", new Object[] { cloneName, ip });
        final SSHLauncher launcher = new SSHLauncher(ip, 0, credentialsId, null, null, null, null, this.launchDelay, 3, 60);
        return launcher;
    }

    private RetentionStrategy<?> determineRetention() {
//      final CloudSlaveRetentionStrategy strategy = new CloudSlaveRetentionStrategy();
//      strategy.TIMEOUT = TimeUnit2.MINUTES.toMillis(1);
      final RunOnceCloudRetentionStrategy strategy = new RunOnceCloudRetentionStrategy(2);
      return strategy;
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

        public FormValidation doCheckTemplateInstanceCap(@QueryParameter String templateInstanceCap) {
            return FormValidation.validateNonNegativeInteger(templateInstanceCap);
        }

        public ListBoxModel doFillCredentialsIdItems(@AncestorInPath ItemGroup context) {
            if(!(context instanceof AccessControlled ? (AccessControlled) context : Jenkins.getInstance()).hasPermission(Computer.CONFIGURE)) {
                return new ListBoxModel();
            }
            final List<StandardUsernameCredentials> credentials = lookupCredentials(StandardUsernameCredentials.class, context, ACL.SYSTEM, HTTP_SCHEME, HTTPS_SCHEME);
            return new StandardUsernameListBoxModel().withAll(credentials);
        }
    }

    private Map<String, String> calculateGuestInfoProperties(final String cloneName, final TaskListener listener)
            throws IOException, InterruptedException {
        final EnvVars knownVariables = calculateVariablesForGuestInfo(cloneName, listener);
        final Map<String, String> resolvedGuestInfoProperties = new LinkedHashMap<String, String>();
        for( final VSphereGuestInfoProperty property : this.guestInfoProperties ) {
            final String name = property.getName();
            final String configuredValue = property.getValue();
            final String resolvedValue = Util.replaceMacro(configuredValue, knownVariables);
            resolvedGuestInfoProperties.put(name, resolvedValue);
        }
        return resolvedGuestInfoProperties;
    }

    private EnvVars calculateVariablesForGuestInfo(final String cloneName, final TaskListener listener)
            throws IOException, InterruptedException {
        final EnvVars knownVariables = new EnvVars();
        // Maintenance note: If you update this method, you must also update the
        // UI help page to match.
        final String jenkinsUrl = Jenkins.getActiveInstance().getRootUrl();
        if (jenkinsUrl != null) {
            addEnvVar(knownVariables, "JENKINS_URL", jenkinsUrl);
            addEnvVar(knownVariables, "HUDSON_URL", jenkinsUrl);
        }
        addEnvVars(knownVariables, listener, Jenkins.getInstance().getGlobalNodeProperties());
        addEnvVars(knownVariables, listener, this.nodeProperties);
        addEnvVar(knownVariables, "NODE_NAME", cloneName);
        addEnvVar(knownVariables, "NODE_LABELS", getLabelSet() == null ? null : Util.join(getLabelSet(), " "));
        addEnvVar(knownVariables, "cluster", this.cluster);
        addEnvVar(knownVariables, "datastore", this.datastore);
        addEnvVar(knownVariables, "labelString", this.labelString);
        addEnvVar(knownVariables, "masterImageName", this.masterImageName);
        addEnvVar(knownVariables, "remoteFS", this.remoteFS);
        addEnvVar(knownVariables, "snapshotName", this.snapshotName);
        addEnvVar(knownVariables, "targetHost", this.targetHost);
        addEnvVar(knownVariables, "templateDescription", this.templateDescription);
        return knownVariables;
    }

    private static void addEnvVars(final EnvVars vars, final TaskListener listener, final Iterable<? extends NodeProperty> nodeProperties) throws IOException, InterruptedException {
        if( nodeProperties!=null ) {
            for (final NodeProperty nodeProperty: nodeProperties) {
                nodeProperty.buildEnvVars(vars , listener);
            }
        }
    }

    private static void addEnvVar(final EnvVars vars, final String name, final Object valueOrNull) {
        vars.put(name, valueOrNull==null?"":valueOrNull.toString());
    }
}
