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
 
import com.cloudbees.plugins.credentials.common.StandardUsernameListBoxModel;
import com.cloudbees.plugins.credentials.common.StandardUsernamePasswordCredentials;
import com.cloudbees.plugins.credentials.domains.SchemeRequirement;
import hudson.Extension;
import hudson.model.Computer;
import hudson.model.Describable;
import hudson.model.Descriptor;
import hudson.model.ItemGroup;
import hudson.security.ACL;
import hudson.security.AccessControlled;
import hudson.util.ListBoxModel;
import java.util.List;
import java.util.logging.Logger;
import jenkins.model.Jenkins;
import org.kohsuke.stapler.AncestorInPath;
import org.kohsuke.stapler.DataBoundConstructor;

import static com.cloudbees.plugins.credentials.CredentialsProvider.lookupCredentials;
import hudson.model.Descriptor.FormException;
import hudson.model.Label;
import hudson.model.Node.Mode;
import hudson.model.TaskListener;
import hudson.model.labels.LabelAtom;
import hudson.plugins.sshslaves.SSHLauncher;
import hudson.slaves.NodeProperty;
import java.io.IOException;
import java.io.PrintStream;
import java.util.Set;
import java.util.UUID;
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
    private transient int templateInstanceCap;
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
                final List<? extends NodeProperty<?>> nodeProperties) {
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
    
    public int getNumberOfExceutors() {
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
    
    public vSphereCloudProvisionedSlave provision(TaskListener listener) throws VSphereException, FormException, IOException {
        final PrintStream logger = listener.getLogger();
        final VSphere vSphere = getParent().vSphereInstance();
        final UUID cloneUUID = UUID.randomUUID();
        final String cloneName = this.cloneNamePrefix + "_" + cloneUUID;
        
        vSphere.cloneVm(cloneName, this.masterImageName, this.linkedClone, this.resourcePool, this.cluster, this.datastore, logger);
        
        final String ip = vSphere.getIp(vSphere.getVmByName(cloneName), 1000);
        final SSHLauncher sshLauncher = new SSHLauncher(ip, 0, credentialsId, null, null, null, null, this.launchDelay, 3, 60);
        
        vSphere.disconnect();
//        final CloudSlaveRetentionStrategy strategy = new CloudSlaveRetentionStrategy();
//        strategy.TIMEOUT = TimeUnit2.MINUTES.toMillis(1);
        final RunOnceCloudRetentionStrategy strategy = new RunOnceCloudRetentionStrategy(2);
        return new vSphereCloudProvisionedSlave(cloneName, this.templateDescription, this.remoteFS, String.valueOf(this.numberOfExecutors), this.mode, this.labelString, sshLauncher, strategy, this.nodeProperties, this.parent.getVsDescription(), this.masterImageName, this.forceVMLaunch, this.waitForVMTools, snapshotName, String.valueOf(this.launchDelay), null, String.valueOf(this.limitedRunCount));
    }
    
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

        public ListBoxModel doFillCredentialsIdItems(@AncestorInPath ItemGroup context) {
            if(!(context instanceof AccessControlled ? (AccessControlled) context : Jenkins.getInstance()).hasPermission(Computer.CONFIGURE)) {
                return new ListBoxModel();
            }
            final List<StandardUsernamePasswordCredentials> credentials = lookupCredentials(StandardUsernamePasswordCredentials.class,context,ACL.SYSTEM,HTTP_SCHEME,HTTPS_SCHEME);
            return new StandardUsernameListBoxModel().withAll(credentials);
        }
    }
}
