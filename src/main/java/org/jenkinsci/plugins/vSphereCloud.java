/*
 * To change this template, choose Tools | Templates
 * and open the template in the editor.
 */
package org.jenkinsci.plugins;

import com.cloudbees.hudson.plugins.folder.AbstractFolderProperty;
import com.cloudbees.hudson.plugins.folder.AbstractFolderPropertyDescriptor;
import com.cloudbees.hudson.plugins.folder.Folder;
import hudson.Extension;
import hudson.model.*;
import hudson.model.Descriptor.FormException;
import hudson.slaves.Cloud;
import hudson.slaves.NodeProvisioner.PlannedNode;
import hudson.slaves.SlaveComputer;
import hudson.util.DescribableList;
import hudson.util.FormValidation;
import hudson.util.StreamTaskListener;
import jenkins.model.Jenkins;
import jenkins.slaves.iterators.api.NodeIterator;
import net.sf.json.JSONObject;
import org.apache.commons.lang.builder.HashCodeBuilder;
import org.jenkinsci.plugins.folder.FolderVSphereCloudProperty;
import org.jenkinsci.plugins.vsphere.VSphereConnectionConfig;
import org.jenkinsci.plugins.vsphere.tools.*;
import org.kohsuke.stapler.DataBoundConstructor;
import org.kohsuke.stapler.QueryParameter;
import org.kohsuke.stapler.Stapler;
import org.kohsuke.stapler.StaplerRequest;

import javax.annotation.CheckForNull;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.Future;
import java.util.logging.Level;

/**
 * @author Admin
 */
public class vSphereCloud extends Cloud {

    @Deprecated
    private transient String vsHost;
    private final String vsDescription;
    @Deprecated
    private transient String username;
    @Deprecated
    private transient String password;
    private final int maxOnlineSlaves;
    private
    @CheckForNull
    VSphereConnectionConfig vsConnectionConfig;

    private final int instanceCap;
    private final boolean useNoDelayProvisioner;
    private final List<? extends vSphereCloudSlaveTemplate> templates;

    private transient int currentOnlineSlaveCount = 0;
    private transient ConcurrentHashMap<String, String> currentOnline;
    private transient CloudProvisioningState templateState;

    private static final java.util.logging.Logger VSLOG = java.util.logging.Logger.getLogger("vsphere-cloud");

    private static void InternalLog(Slave slave, SlaveComputer slaveComputer, TaskListener listener, Throwable ex, Level logLevel, String format, Object... args) {
        if (!VSLOG.isLoggable(logLevel) && listener == null)
            return;
        String s = "";
        if (slave != null)
            s = String.format("[%s] ", slave.getNodeName());
        if (slaveComputer != null)
            s = String.format("[%s] ", slaveComputer.getName());
        s = s + String.format(format, args);
        if (listener != null) {
            listener.getLogger().print(s + "\n");
            if (ex != null) {
                listener.getLogger().print(ex.toString() + "\n");
                ex.printStackTrace(listener.getLogger());
            }
        }
        if (ex != null) {
            VSLOG.log(logLevel, s, ex);
        } else {
            VSLOG.log(logLevel, s);
        }
    }

    /** Logs an {@link Level#INFO} message (created with {@link String#format(String, Object...)}). */
    public static void Log(String format, Object... args) {
        InternalLog(null, null, null, null, Level.INFO, format, args);
    }

    /** Logs an {@link Level#SEVERE} message (created with {@link String#format(String, Object...)}) and a {@link Throwable} with stacktrace. */
    public static void Log(Throwable ex, String format, Object... args) {
        InternalLog(null, null, null, ex, Level.SEVERE, format, args);
    }

    /** Logs a {@link Level#INFO} message (created with {@link String#format(String, Object...)}). */
    public static void Log(TaskListener listener, String format, Object... args) {
        InternalLog(null, null, listener, null, Level.INFO, format, args);
    }

    /** Logs a {@link Level#SEVERE} message (created with {@link String#format(String, Object...)}) and a {@link Throwable} (with stacktrace). */
    public static void Log(TaskListener listener, Throwable ex, String format, Object... args) {
        InternalLog(null, null, listener, ex, Level.SEVERE, format, args);
    }

    /** Logs a {@link Level#INFO} message (created with {@link String#format(String, Object...)}), prefixed by the {@link Slave} name. */
    public static void Log(Slave slave, String format, Object... args) {
        InternalLog(slave, null, null, null, Level.INFO, format, args);
    }

    /** Logs a {@link Level#SEVERE} message (created with {@link String#format(String, Object...)}) and a {@link Throwable} (with stacktrace), prefixed by the {@link Slave} name. */
    public static void Log(Slave slave, Throwable ex, String format, Object... args) {
        InternalLog(slave, null, null, ex, Level.SEVERE, format, args);
    }

    /** Logs a {@link Level#INFO} message (created with {@link String#format(String, Object...)}), prefixed by the {@link Slave} name. */
    public static void Log(Slave slave, TaskListener listener, String format, Object... args) {
        InternalLog(slave, null, listener, null, Level.INFO, format, args);
    }

    /** Logs a {@link Level#SEVERE} message (created with {@link String#format(String, Object...)}) and a {@link Throwable} (with stacktrace), prefixed by the {@link Slave} name. */
    public static void Log(Slave slave, TaskListener listener, Throwable ex, String format, Object... args) {
        InternalLog(slave, null, listener, ex, Level.SEVERE, format, args);
    }

    /** Logs a {@link Level#INFO} message (created with {@link String#format(String, Object...)}), prefixed by the {@link SlaveComputer} name. */
    public static void Log(SlaveComputer slave, TaskListener listener, String format, Object... args) {
        InternalLog(null, slave, listener, null, Level.INFO, format, args);
    }

    /** Logs a {@link Level#SEVERE} message (created with {@link String#format(String, Object...)}) and a {@link Throwable} (with stacktrace), prefixed by the {@link SlaveComputer} name. */
    public static void Log(SlaveComputer slave, TaskListener listener, Throwable ex, String format, Object... args) {
        InternalLog(null, slave, listener, ex, Level.SEVERE, format, args);
    }

    @Deprecated
    public vSphereCloud(String vsHost, String vsDescription,
                        String username, String password, int maxOnlineSlaves) {
        this(null, vsDescription, maxOnlineSlaves, 0, false, null);
    }

    @DataBoundConstructor
    public vSphereCloud(VSphereConnectionConfig vsConnectionConfig, String vsDescription, int maxOnlineSlaves, int instanceCap, boolean useNoDelayProvisioner, List<? extends vSphereCloudSlaveTemplate> templates) {
        super("vSphereCloud");
        this.vsDescription = vsDescription;
        this.maxOnlineSlaves = maxOnlineSlaves;
        this.vsConnectionConfig = vsConnectionConfig;
        if (templates == null) {
            this.templates = Collections.emptyList();
        } else {
            this.templates = templates;
        }

        if (instanceCap == 0) {
            this.instanceCap = Integer.MAX_VALUE;
        } else {
            this.instanceCap = instanceCap;
        }
        this.useNoDelayProvisioner = useNoDelayProvisioner;
        try {
            readResolve();
        } catch (IOException ioex) {
            //do nothing;
        }
        Log("STARTING VSPHERE CLOUD");
    }

    public Object readResolve() throws IOException {
        if (vsConnectionConfig == null) {
            vsConnectionConfig = new VSphereConnectionConfig(vsHost, null);
        }
        if (this.templates != null) {
            for (vSphereCloudSlaveTemplate template : templates) {
                template.parent = this;
            }
        }
        return this;
    }

    private void ensureLists() {
        if (currentOnline == null)
            currentOnline = new ConcurrentHashMap<String, String>();
        if (templateState == null) {
            /*
             * If Jenkins has just restarted, we may have existing slaves that
             * exist but aren't currently recorded in our non-persisted state,
             * so we need to discover them.
             */
            templateState = new CloudProvisioningState(this);
            for (final vSphereCloudProvisionedSlave n : NodeIterator.nodes(vSphereCloudProvisionedSlave.class)) {
                final String nodeName = n.getNodeName();
                final vSphereCloudSlaveTemplate template = getTemplateForVM(nodeName);
                if (template == null) continue;
                final CloudProvisioningRecord provisionable = templateState.getOrCreateRecord(template);
                templateState.provisioningStarted(provisionable, nodeName);
                templateState.provisionedSlaveNowActive(provisionable, nodeName);
            }
        }
    }

    public int getMaxOnlineSlaves() {
        return maxOnlineSlaves;
    }

    public int getInstanceCap() {
        return this.instanceCap;
    }

    public boolean getUseNoDelayProvisioner() {
        return useNoDelayProvisioner;
    }

    public List<? extends vSphereCloudSlaveTemplate> getTemplates() {
        return this.templates;
    }

    private vSphereCloudSlaveTemplate getTemplateForVM(final String vmName) {
        if (this.templates == null || vmName == null)
            return null;
        for (final vSphereCloudSlaveTemplate t : this.templates) {
            final String cloneNamePrefix = t.getCloneNamePrefix();
            if (cloneNamePrefix != null && vmName.startsWith(cloneNamePrefix)) {
                return t;
            }
        }
        return null;
    }

    private List<vSphereCloudSlaveTemplate> getTemplates(final Label label) {
        if (this.templates == null)
            return Collections.emptyList();
        List<vSphereCloudSlaveTemplate> matchingTemplates = new ArrayList<vSphereCloudSlaveTemplate>();
        for (vSphereCloudSlaveTemplate t : this.templates) {
            if (t.getMode() == Node.Mode.NORMAL) {
                if (label == null || label.matches(t.getLabelSet())) {
                    matchingTemplates.add(t);
                }
            } else if (t.getMode() == Node.Mode.EXCLUSIVE) {
                if (label != null && label.matches(t.getLabelSet())) {
                    matchingTemplates.add(t);
                }
            }
        }
        return matchingTemplates;
    }

    public
    @CheckForNull
    String getPassword() {
        return vsConnectionConfig != null ? vsConnectionConfig.getPassword() : null;
    }

    public
    @CheckForNull
    String getUsername() {
        return vsConnectionConfig != null ? vsConnectionConfig.getUsername() : null;
    }

    public String getVsDescription() {
        return vsDescription;
    }

    public
    @CheckForNull
    String getVsHost() {
        return vsConnectionConfig != null ? vsConnectionConfig.getVsHost() : null;
    }

    public boolean getAllowUntrustedCertificate() {
        return vsConnectionConfig != null ? vsConnectionConfig.getAllowUntrustedCertificate() : false;
    }

    public
    @CheckForNull
    VSphereConnectionConfig getVsConnectionConfig() {
        return vsConnectionConfig;
    }

    public final int getHash() {
        return new HashCodeBuilder(67, 89).
                append(getVsDescription()).
                append(getVsHost()).
                toHashCode();
    }

    public VSphere vSphereInstance() throws VSphereException {
        // TODO: validate configs
        final VSphereConnectionConfig connectionConfig = getVsConnectionConfig();
        if (connectionConfig == null) {
            throw new VSphereException("vSphere connection configuration is not specified");
        }
        final String effectiveVsHost = connectionConfig.getVsHost();
        if (effectiveVsHost == null) {
            throw new VSphereException("vSphere host is not specified");
        }
        final String effectiveUserName = connectionConfig.getUsername();
        if (effectiveUserName == null) {
            throw new VSphereException("vSphere username is not specified");
        }

        return VSphere.connect(connectionConfig);
    }

    @Override
    public boolean canProvision(Label label) {
        return !getTemplates(label).isEmpty();
    }

    private Integer calculateMaxAdditionalSlavesPermitted() {
        if (this.instanceCap == 0 || this.instanceCap == Integer.MAX_VALUE) {
            return null;
        }
        final int totalVms = templateState.countNodes();
        final int maxSlavesToProvision = this.instanceCap - totalVms;
        final boolean thereIsNoRoom = maxSlavesToProvision <= 0;
        VSLOG.info("There are " + totalVms + " VMs in this cloud. The instance cap for the cloud is "
                + this.instanceCap + ", so we " + (thereIsNoRoom ? "are full" : "have room for more"));
        return Integer.valueOf(maxSlavesToProvision);
    }

    @Override
    public Collection<PlannedNode> provision(final Label label, int excessWorkload) {
        final String methodCallDescription = "provision(" + label + "," + excessWorkload + ")";
        try {
            int excessWorkloadSoFar = excessWorkload;
            // First we see what our static slaves can do for us.
            int numberOfvSphereCloudSlaves = 0;
            int numberOfvSphereCloudSlaveExecutors = 0;
            for (vSphereCloudSlave n : NodeIterator.nodes(vSphereCloudSlave.class)) {
                if (n instanceof vSphereCloudProvisionedSlave) {
                    continue; // ignore cloud slaves
                }
                if (n.getComputer().isOffline() && label.matches(n.getAssignedLabels())) {
                    n.getComputer().tryReconnect();
                    numberOfvSphereCloudSlaves++;
                    numberOfvSphereCloudSlaveExecutors += n.getNumExecutors();
                }
            }
            excessWorkloadSoFar -= numberOfvSphereCloudSlaveExecutors;
            if (excessWorkloadSoFar <= 0) {
                VSLOG.log(Level.INFO, methodCallDescription + ": " + numberOfvSphereCloudSlaves + " existing slaves (="
                        + numberOfvSphereCloudSlaveExecutors + " executors): Workload is satisfied by bringing those online.");
                return Collections.emptySet();
            }
            // If we've got this far then our static slaves are insufficient to meet
            // demand and we should consider creating new slaves.
            synchronized (this) {
                ensureLists();
            }
            retryVMdeletionIfNecessary(Math.max(excessWorkload, 2));
            final List<PlannedNode> plannedNodes = new ArrayList<PlannedNode>();
            synchronized (templateState) {
                templateState.pruneUnwantedRecords();
                Integer maxSlavesToProvisionBeforeCloudCapHit = calculateMaxAdditionalSlavesPermitted();
                if (maxSlavesToProvisionBeforeCloudCapHit != null && maxSlavesToProvisionBeforeCloudCapHit <= 0) {
                    return Collections.emptySet(); // no capacity due to cloud instance cap
                }
                final List<vSphereCloudSlaveTemplate> templates = getTemplates(label);
                final List<CloudProvisioningRecord> whatWeCouldUse = templateState.calculateProvisionableTemplates(templates);
                VSLOG.log(Level.INFO, methodCallDescription + ": " + numberOfvSphereCloudSlaves + " existing slaves (="
                        + numberOfvSphereCloudSlaveExecutors + " executors), templates available are " + whatWeCouldUse);
                while (excessWorkloadSoFar > 0) {
                    if (maxSlavesToProvisionBeforeCloudCapHit != null) {
                        final int intValue = maxSlavesToProvisionBeforeCloudCapHit.intValue();
                        if (intValue <= 0) {
                            break; // out of capacity due to cloud instance cap
                        }
                        maxSlavesToProvisionBeforeCloudCapHit = Integer.valueOf(intValue - 1);
                    }
                    final CloudProvisioningRecord whatWeShouldSpinUp = CloudProvisioningAlgorithm.findTemplateWithMostFreeCapacity(whatWeCouldUse);
                    if (whatWeShouldSpinUp == null) {
                        break; // out of capacity due to template instance cap
                    }
                    final String nodeName = CloudProvisioningAlgorithm.findUnusedName(whatWeShouldSpinUp);
                    final PlannedNode plannedNode = VSpherePlannedNode.createInstance(templateState, nodeName, whatWeShouldSpinUp);
                    plannedNodes.add(plannedNode);
                    excessWorkloadSoFar -= plannedNode.numExecutors;
                }
            }
            VSLOG.log(Level.INFO, methodCallDescription + ": Provisioning " + plannedNodes.size()
                    + " new =" + plannedNodes);
            return plannedNodes;
        } catch (Exception ex) {
            VSLOG.log(Level.WARNING, methodCallDescription + ": Failed.", ex);
            return Collections.emptySet();
        }
    }

    /**
     * Has another go at deleting VMs we failed to delete earlier. It's possible
     * that we were unable to talk to vSphere (or some other failure happened)
     * when we decided to delete some VMs. We remember this sort of thing so we
     * can retry later - this is where we use this information.
     * 
     * @param maxToRetryDeletionOn
     *            The maximum number of VMs to try to remove this time around.
     *            Can be {@link Integer#MAX_VALUE} for unlimited.
     */
    private void retryVMdeletionIfNecessary(final int maxToRetryDeletionOn) {
        if (templateState == null) {
            VSLOG.log(Level.INFO, "retryVMdeletionIfNecessary({0}): templateState==null", maxToRetryDeletionOn);
            return;
        }
        // find all candidates and trim down the list
        final List<String> unwantedVMsThatNeedDeleting = templateState.getUnwantedVMsThatNeedDeleting();
        final int numberToAttemptToRetryThisTime = Math.min(maxToRetryDeletionOn, unwantedVMsThatNeedDeleting.size());
        final List<String> nodeNamesToRetryDeletion = unwantedVMsThatNeedDeleting.subList(0,
                numberToAttemptToRetryThisTime);
        // now queue their deletion
        synchronized (templateState) {
            for (final String nodeName : nodeNamesToRetryDeletion) {
                final Boolean isOkToDelete = templateState.isOkToDeleteUnwantedVM(nodeName);
                if (isOkToDelete == Boolean.TRUE) {
                    final Runnable task = new Runnable() {
                        @Override
                        public void run() {
                            attemptDeletionOfSlave("retryVMdeletionIfNecessary(" + nodeName + ")", nodeName);
                        }
                    };
                    VSLOG.log(Level.INFO, "retryVMdeletionIfNecessary({0}): scheduling deletion of {1}", new Object[] { maxToRetryDeletionOn, nodeName });
                    Computer.threadPoolForRemoting.submit(task);
                } else {
                    VSLOG.log(Level.FINER,
                            "retryVMdeletionIfNecessary({0}): not going to try deleting {1} as isOkToDeleteUnwantedVM({1})=={2}",
                            new Object[]{ maxToRetryDeletionOn, nodeName, isOkToDelete });
                }
            }
        }
    }

    /**
     * This is called by {@link vSphereCloudProvisionedSlave} instances once
     * they terminate, so we can take note of their passing and then destroy the
     * VM itself.
     *
     * @param cloneName The name of the VM that's just terminated.
     */
    void provisionedSlaveHasTerminated(final String cloneName) {
        synchronized (this) {
            ensureLists();
        }
        VSLOG.log(Level.FINER, "provisionedSlaveHasTerminated({0}): recording in our runtime state...", cloneName);
        synchronized (templateState) {
            templateState.provisionedSlaveNowUnwanted(cloneName, true);
        }
        // Deletion can take a long time, so we run it asynchronously because,
        // at the point where we're called here, we've locked the remoting queue
        // so Jenkins is largely crippled until we return.
        // JENKINS-42187 describes the problem (for docker).
        final Runnable task = new Runnable() {
            @Override
            public void run() {
                attemptDeletionOfSlave("provisionedSlaveHasTerminated(" + cloneName + ")", cloneName);
            }
        };
        VSLOG.log(Level.INFO, "provisionedSlaveHasTerminated({0}): scheduling deletion of {0}", cloneName);
        Computer.threadPoolForRemoting.submit(task);
        // We also take this opportunity to see if we've got any other slaves
        // that need deleting, and deal with at most one of those
        // (asynchronously) as well.
        retryVMdeletionIfNecessary(1);
    }

    private void attemptDeletionOfSlave(final String why, final String cloneName) {
        VSLOG.log(Level.FINER, "{0}: destroying VM {1}...", new Object[]{ why, cloneName });
        VSphere vSphere = null;
        boolean successfullyDeleted = false;
        try {
            vSphere = vSphereInstance();
            // Note: This can block indefinitely - it only completes when
            // vSphere tells us the deletion has completed, and if vSphere has
            // issues (e.g. a node failure) during that process then the
            // deletion task can hang for ages.
            vSphere.destroyVm(cloneName, false);
            successfullyDeleted = true;
            VSLOG.log(Level.FINER, "{0}: VM {1} destroyed.", new Object[]{ why, cloneName });
            vSphere.disconnect();
            vSphere = null;
        } catch (VSphereException ex) {
            VSLOG.log(Level.SEVERE, why + ": Exception while trying to destroy VM " + cloneName, ex);
        } finally {
            synchronized (templateState) {
                if (successfullyDeleted) {
                    templateState.unwantedSlaveNowDeleted(cloneName);
                } else {
                    templateState.unwantedSlaveNotDeleted(cloneName);
                }
            }
            if (vSphere != null) {
                vSphere.disconnect();
            }
        }
    }

    static class VSpherePlannedNode extends PlannedNode {
        private VSpherePlannedNode(String displayName, Future<Node> future, int numExecutors) {
            super(displayName, future, numExecutors);
        }

        public static VSpherePlannedNode createInstance(final CloudProvisioningState templateState,
                                                        final String nodeName,
                                                        final CloudProvisioningRecord whatWeShouldSpinUp) {
            final vSphereCloudSlaveTemplate template = whatWeShouldSpinUp.getTemplate();
            final int numberOfExecutors = template.getNumberOfExecutors();
            final Callable<Node> provisionNodeCallable = new Callable<Node>() {
                @Override
                public Node call() throws Exception {
                    try {
                        final Node newNode = provisionNewNode(whatWeShouldSpinUp, nodeName);
                        VSLOG.log(Level.INFO, "Provisioned new slave " + nodeName);
                        synchronized (templateState) {
                            templateState.provisionedSlaveNowActive(whatWeShouldSpinUp, nodeName);
                        }
                        return newNode;
                    } catch (Exception ex) {
                        VSLOG.log(Level.WARNING, "Failed to provision new slave " + nodeName, ex);
                        synchronized (templateState) {
                            templateState.provisioningEndedInError(whatWeShouldSpinUp, nodeName);
                        }
                        throw ex;
                    }
                }
            };
            templateState.provisioningStarted(whatWeShouldSpinUp, nodeName);
            final Future<Node> provisionNodeTask = Computer.threadPoolForRemoting.submit(provisionNodeCallable);
            final VSpherePlannedNode result = new VSpherePlannedNode(nodeName, provisionNodeTask, numberOfExecutors);
            return result;
        }

        private static Node provisionNewNode(final CloudProvisioningRecord whatWeShouldSpinUp, final String cloneName)
                throws VSphereException, FormException, IOException, InterruptedException {
            final vSphereCloudSlaveTemplate template = whatWeShouldSpinUp.getTemplate();
            final vSphereCloudProvisionedSlave slave = template.provision(cloneName, StreamTaskListener.fromStdout());
            // ensure Jenkins knows about us before we forget what we're doing,
            // otherwise it'll just ask for more.
            Jenkins.getInstance().addNode(slave);
            return slave;
        }

        @Override
        public String toString() {
            return displayName;
        }
    }

    @Override
    public String toString() {
        final StringBuilder sb = new StringBuilder();
        sb.append("vSphereCloud");
        sb.append("{Host='").append(getVsHost()).append('\'');
        sb.append(", Description='").append(vsDescription).append('\'');
        sb.append('}');
        return sb.toString();
    }

    public synchronized Boolean canMarkVMOnline(String slaveName, String vmName) {
        ensureLists();

        // Don't allow more than max.
        if ((maxOnlineSlaves > 0) && (currentOnline.size() == maxOnlineSlaves))
            return Boolean.FALSE;

        // Don't allow two slaves to the same VM to fire up.
        // With templates the vmName will be the same.  So first verify if the slave is from a template
        if (currentOnline.containsValue(vmName))
            return Boolean.FALSE;
        // TODO: what we want here is to validate the instance cap of both the cloud and the template (if the slave is created from a template);

        // Don't allow two instances of the same slave, although Jenkins will
        // probably not encounter this.
        if (currentOnline.containsKey(slaveName))
            return Boolean.FALSE;

        return Boolean.TRUE;
    }

    public synchronized Boolean markVMOnline(String slaveName, String vmName) {
        ensureLists();

        // If the combination is already in the list, it's good.
        if (currentOnline.containsKey(slaveName) && currentOnline.get(slaveName).equals(vmName))
            return Boolean.TRUE;

        if (!canMarkVMOnline(slaveName, vmName))
            return Boolean.FALSE;

        currentOnline.put(slaveName, vmName);
        currentOnlineSlaveCount++;

        return Boolean.TRUE;
    }

    public synchronized void markVMOffline(String slaveName, String vmName) {
        ensureLists();
        if (currentOnline.remove(slaveName) != null)
            currentOnlineSlaveCount--;
    }

    public static List<vSphereCloud> findAllVsphereClouds(String jobName) {
        List<vSphereCloud> vSphereClouds = new ArrayList<vSphereCloud>();

        String[] path = new String[0];
        Folder prevFolder = null;

        if (Stapler.getCurrentRequest() != null){
            path = Stapler.getCurrentRequest().getRequestURI().split("/");
        } else if (jobName != null) {
            path = jobName.split("/");
        }

        for (String item : path) {

            if (item.equals("job") || item.equals("jenkins"))
                continue;

            TopLevelItem topLevelItem = null;
            if (prevFolder == null) {
                topLevelItem = Jenkins.getInstance().getItem(item);
            } else {
                Collection<TopLevelItem> items = prevFolder.getItems();
                for (TopLevelItem levelItem : items) {
                    if (levelItem.getName().endsWith(item)){
                        topLevelItem = levelItem;
                    }
                }
            }
            if (topLevelItem != null && topLevelItem instanceof Folder) {
                extractClouds(vSphereClouds, (Folder) topLevelItem);
            }
        }

        for (Cloud cloud : Jenkins.getInstance().clouds) {
            if (cloud instanceof vSphereCloud) {
                vSphereClouds.add((vSphereCloud) cloud);
            }
        }
        return vSphereClouds;
    }

    private static void extractClouds(List<vSphereCloud> vSphereClouds, Folder folder) {
        DescribableList<AbstractFolderProperty<?>, AbstractFolderPropertyDescriptor> properties = folder.getProperties();
        for (AbstractFolderProperty<?> property : properties) {
            if (property instanceof FolderVSphereCloudProperty) {
                vSphereClouds.addAll(((FolderVSphereCloudProperty) property).getVsphereClouds());
            }
        }
    }

    public static List<String> findAllVsphereCloudNames() {
        List<String> cloudNames = new ArrayList<String>();
        for (org.jenkinsci.plugins.vSphereCloud vSphereCloud : findAllVsphereClouds(null)) {
            cloudNames.add(vSphereCloud.getVsDescription());
        }
        return cloudNames;
    }

    @Override
    public DescriptorImpl getDescriptor() {
        return (DescriptorImpl) super.getDescriptor();
    }

    @Extension
    public static final class DescriptorImpl extends Descriptor<Cloud> {

        public final
        @Deprecated
        ConcurrentMap<String, vSphereCloud> hypervisors = new ConcurrentHashMap<String, vSphereCloud>();
        private
        @Deprecated
        String vsHost;
        private
        @Deprecated
        String username;
        private
        @Deprecated
        String password;
        private
        @Deprecated
        int maxOnlineSlaves;

        @Override
        public String getDisplayName() {
            return "vSphere Cloud";
        }

        @Override
        public boolean configure(StaplerRequest req, JSONObject o)
                throws FormException {
            vsHost = o.getString("vsHost");
            username = o.getString("username");
            password = o.getString("password");
            maxOnlineSlaves = o.getInt("maxOnlineSlaves");
            save();
            return super.configure(req, o);
        }

        public FormValidation doCheckVsDescription(@QueryParameter String value) {
            return FormValidation.validateRequired(value);
        }

        public FormValidation doCheckMaxOnlineSlaves(@QueryParameter String value) {
            return FormValidation.validateNonNegativeInteger(value);
        }

        public FormValidation doCheckInstanceCap(@QueryParameter String value) {
            return FormValidation.validateNonNegativeInteger(value);
        }
    }
}
