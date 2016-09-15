/*
 * To change this template, choose Tools | Templates
 * and open the template in the editor.
 */
package org.jenkinsci.plugins;

import org.jenkinsci.plugins.vsphere.VSphereConnectionConfig;

import hudson.Extension;
import hudson.model.Computer;
import hudson.model.TaskListener;
import hudson.model.Descriptor;
import hudson.model.Descriptor.FormException;
import hudson.model.Label;
import hudson.model.Node;
import hudson.model.Slave;
import hudson.slaves.Cloud;
import hudson.slaves.NodeProvisioner.PlannedNode;
import hudson.slaves.SlaveComputer;
import hudson.util.FormValidation;
import hudson.util.StreamTaskListener;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.Future;
import java.util.logging.Level;

import javax.annotation.CheckForNull;

import jenkins.model.Jenkins;
import jenkins.slaves.iterators.api.NodeIterator;
import net.sf.json.JSONObject;

import org.apache.commons.lang.StringUtils;
import org.apache.commons.lang.builder.HashCodeBuilder;
import org.jenkinsci.plugins.vsphere.tools.CloudProvisioningAlgorithm;
import org.jenkinsci.plugins.vsphere.tools.CloudProvisioningRecord;
import org.jenkinsci.plugins.vsphere.tools.CloudProvisioningState;
import org.jenkinsci.plugins.vsphere.tools.VSphere;
import org.jenkinsci.plugins.vsphere.tools.VSphereException;
import org.kohsuke.stapler.DataBoundConstructor;
import org.kohsuke.stapler.QueryParameter;
import org.kohsuke.stapler.StaplerRequest;

/**
 *
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
    private @CheckForNull VSphereConnectionConfig vsConnectionConfig;

    private final int instanceCap;
    private final List<? extends vSphereCloudSlaveTemplate> templates;

    private transient int currentOnlineSlaveCount = 0;
    private transient ConcurrentHashMap<String, String> currentOnline;
    private transient CloudProvisioningState templateState;

    private static java.util.logging.Logger VSLOG = java.util.logging.Logger.getLogger("vsphere-cloud");
    private static void InternalLog(Slave slave, SlaveComputer slaveComputer, TaskListener listener, Throwable ex, String format, Object... args)
    {
        final Level logLevel = Level.INFO;
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
            if ( ex!=null ) {
                listener.getLogger().print(ex.toString() + "\n");
                ex.printStackTrace(listener.getLogger());
            }
        }
        if ( ex!=null ) {
            VSLOG.log(logLevel, s, ex);
        } else {
            VSLOG.log(logLevel, s);
        }
    }
    public static void Log(String format, Object... args) {
        InternalLog(null, null, null, null, format, args);
    }
    public static void Log(Throwable ex, String format, Object... args) {
        InternalLog(null, null, null, ex, format, args);
    }
    public static void Log(TaskListener listener, String format, Object... args) {
        InternalLog(null, null, listener, null, format, args);
    }
    public static void Log(TaskListener listener, Throwable ex, String format, Object... args) {
        InternalLog(null, null, listener, ex, format, args);
    }
    public static void Log(Slave slave, String format, Object... args) {
        InternalLog(slave, null, null, null, format, args);
    }
    public static void Log(Slave slave, Throwable ex, String format, Object... args) {
        InternalLog(slave, null, null, ex, format, args);
    }
    public static void Log(Slave slave, TaskListener listener, String format, Object... args) {
        InternalLog(slave, null, listener, null, format, args);
    }
    public static void Log(Slave slave, TaskListener listener, Throwable ex, String format, Object... args) {
        InternalLog(slave, null, listener, ex, format, args);
    }
    public static void Log(SlaveComputer slave, TaskListener listener, String format, Object... args) {
        InternalLog(null, slave, listener, null, format, args);
    }
    public static void Log(SlaveComputer slave, TaskListener listener, Throwable ex, String format, Object... args) {
        InternalLog(null, slave, listener, ex, format, args);
    }

    @Deprecated
    public vSphereCloud(String vsHost, String vsDescription,
            String username, String password, int maxOnlineSlaves) {
        this(null , vsDescription, maxOnlineSlaves,0,null);
    }

    @DataBoundConstructor
    public vSphereCloud(VSphereConnectionConfig vsConnectionConfig, String vsDescription, int maxOnlineSlaves, int instanceCap, List<? extends vSphereCloudSlaveTemplate> templates) {
        super("vSphereCloud");
        this.vsDescription = vsDescription;
        this.maxOnlineSlaves = maxOnlineSlaves;
        this.vsConnectionConfig = vsConnectionConfig;
        if(templates == null) {
            this.templates = Collections.emptyList();
        } else {
            this.templates = templates;
        }

        if(instanceCap == 0) {
            this.instanceCap = Integer.MAX_VALUE;
        } else {
            this.instanceCap = instanceCap;
        }
        try {
            readResolve();
        } catch(IOException ioex) {
            //do nothing;
        }
        Log("STARTING VSPHERE CLOUD");
    }

    public Object readResolve() throws IOException {
        if (vsConnectionConfig == null) {
            vsConnectionConfig = new VSphereConnectionConfig(vsHost, null);
        }
        if(this.templates != null) {
            for(vSphereCloudSlaveTemplate template : templates) {
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
                if (template != null) {
                    final CloudProvisioningRecord provisionable = templateState.getOrCreateRecord(template);
                    templateState.provisioningStarted(provisionable, nodeName);
                    templateState.provisionedSlaveNowActive(provisionable, nodeName);
                }
            }
        }
    }

    public int getMaxOnlineSlaves() {
        return maxOnlineSlaves;
    }

    public int getInstanceCap() {
        return this.instanceCap;
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
        if(this.templates==null)
            return Collections.emptyList();
        List<vSphereCloudSlaveTemplate> matchingTemplates = new ArrayList<vSphereCloudSlaveTemplate>();
        for(vSphereCloudSlaveTemplate t : this.templates) {
            if(t.getMode() == Node.Mode.NORMAL) {
                if(label == null || label.matches(t.getLabelSet())) {
                    matchingTemplates.add(t);
                }
            } else if(t.getMode() == Node.Mode.EXCLUSIVE) {
                if(label != null && label.matches(t.getLabelSet())) {
                    matchingTemplates.add(t);
                }
            }
        }
        return matchingTemplates;
    }

    public @CheckForNull String getPassword() {
        return vsConnectionConfig != null ? vsConnectionConfig.getPassword() : null;
    }

    public @CheckForNull String getUsername() {
        return vsConnectionConfig != null ? vsConnectionConfig.getUsername() : null;
    }

    public String getVsDescription() {
        return vsDescription;
    }

    public @CheckForNull String getVsHost() {
        return vsConnectionConfig != null ? vsConnectionConfig.getVsHost(): null;
    }

    public @CheckForNull VSphereConnectionConfig getVsConnectionConfig() {
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
        final String effectiveVsHost = getVsHost();
        if (effectiveVsHost == null) {
            throw new VSphereException("vSphere host is not specified");
        }
        final String effectiveUserName = getUsername();
        if (effectiveUserName == null) {
            throw new VSphereException("vSphere username is not specified");
        }

        return VSphere.connect(effectiveVsHost + "/sdk", effectiveUserName, getPassword());
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
            for(vSphereCloudSlave n : NodeIterator.nodes(vSphereCloudSlave.class)) {
                if( n instanceof vSphereCloudProvisionedSlave) {
                    continue; // ignore cloud slaves
                }
                if(n.getComputer().isOffline() && label.matches(n.getAssignedLabels())) {
                    n.getComputer().tryReconnect();
                    numberOfvSphereCloudSlaves++;
                    numberOfvSphereCloudSlaveExecutors += n.getNumExecutors();
                }
            }
            excessWorkloadSoFar -= numberOfvSphereCloudSlaveExecutors;
            if (excessWorkloadSoFar <= 0) {
                VSLOG.log(Level.INFO, methodCallDescription + ": " + numberOfvSphereCloudSlaves + " existing slaves (="
                        + numberOfvSphereCloudSlaveExecutors + " executors): Workload is satisifed by bringing those online.");
                return Collections.emptySet();
            }
            // If we've got this far then our static slaves are insufficient to meet
            // demand and we should consider creating new slaves.
            synchronized(this) {
                ensureLists();
            }
            final List<PlannedNode> plannedNodes = new ArrayList<PlannedNode>();
            synchronized(templateState) {
                templateState.pruneUnwantedRecords();
                Integer maxSlavesToProvisionBeforeCloudCapHit = calculateMaxAdditionalSlavesPermitted();
                if (maxSlavesToProvisionBeforeCloudCapHit!=null && maxSlavesToProvisionBeforeCloudCapHit<=0) {
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
                    if (whatWeShouldSpinUp==null) {
                        break; // out of capacity due to template instance cap
                    }
                    final PlannedNode plannedNode = VSpherePlannedNode.createInstance(templateState, whatWeShouldSpinUp);
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
     * This is called by {@link vSphereCloudProvisionedSlave} instances once
     * they terminate, so we can take note of their passing and then destroy the
     * VM itself.
     * 
     * @param cloneName
     *            The name of the VM that's just terminated.
     */
    void provisionedSlaveHasTerminated(final String cloneName) {
        synchronized(this) {
            ensureLists();
        }
        VSLOG.log(Level.FINER, "provisionedSlaveHasTerminated({0}): recording in our runtime state...", cloneName);
        // once we're done, remove our cached record.
        synchronized(templateState) {
            templateState.provisionedSlaveNowTerminated(cloneName);
        }
        VSLOG.log(Level.FINER, "provisionedSlaveHasTerminated({0}): destroying VM...", cloneName);
        VSphere vSphere = null;
        try {
            vSphere = vSphereInstance();
            vSphere.destroyVm(cloneName, false);
            VSLOG.log(Level.FINER, "provisionedSlaveHasTerminated({0}): VM deleted", cloneName);
        } catch (VSphereException ex) {
            VSLOG.log(Level.SEVERE, "provisionedSlaveHasTerminated({0}): Exception while trying to destroy VM", ex);
        } finally {
            if (vSphere != null) {
                vSphere.disconnect();
            }
        }
    }

    static class VSpherePlannedNode extends PlannedNode {
        private VSpherePlannedNode(String displayName, Future<Node> future, int numExecutors) {
            super(displayName, future, numExecutors);
        }

        public static VSpherePlannedNode createInstance(final CloudProvisioningState algorithm,
                final CloudProvisioningRecord whatWeShouldSpinUp) {
            final vSphereCloudSlaveTemplate template = whatWeShouldSpinUp.getTemplate();
            final String cloneNamePrefix = template.getCloneNamePrefix();
            final int numberOfExecutors = template.getNumberOfExecutors();
            final UUID cloneUUID = UUID.randomUUID();
            final String nodeName = cloneNamePrefix + "_" + cloneUUID;
            final Callable<Node> provisionNodeCallable = new Callable<Node>() {
                public Node call() throws Exception {
                    try {
                        final Node newNode = provisionNewNode(algorithm, whatWeShouldSpinUp, nodeName);
                        VSLOG.log(Level.INFO, "Provisioned new slave " + nodeName);
                        synchronized (algorithm) {
                            algorithm.provisionedSlaveNowActive(whatWeShouldSpinUp, nodeName);
                        }
                        return newNode;
                    } catch (Exception ex) {
                        VSLOG.log(Level.WARNING, "Failed to provision new slave " + nodeName, ex);
                        synchronized (algorithm) {
                            algorithm.provisioningEndedInError(whatWeShouldSpinUp, nodeName);
                        }
                        throw ex;
                    }
                }
            };
            algorithm.provisioningStarted(whatWeShouldSpinUp, nodeName);
            final Future<Node> provisionNodeTask = Computer.threadPoolForRemoting.submit(provisionNodeCallable);
            final VSpherePlannedNode result = new VSpherePlannedNode(nodeName, provisionNodeTask, numberOfExecutors);
            return result;
        }

        private static Node provisionNewNode(final CloudProvisioningState algorithm, final CloudProvisioningRecord whatWeShouldSpinUp, final String cloneName)
                throws VSphereException, FormException, IOException, InterruptedException {
            final vSphereCloudSlaveTemplate template = whatWeShouldSpinUp.getTemplate();
            final vSphereCloudProvisionedSlave slave = template.provision(algorithm, cloneName, StreamTaskListener.fromStdout());
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

    public static List<vSphereCloud> findAllVsphereClouds() {
        List<vSphereCloud> vSphereClouds = new ArrayList<vSphereCloud>();
        for (Cloud cloud : Jenkins.getInstance().clouds) {
            if (cloud instanceof vSphereCloud) {
                vSphereClouds.add((vSphereCloud) cloud);
            }
        }
        return vSphereClouds;
    }

    public static List<String> finaAllVsphereCloudNames() {
        List<String> cloudNames = new ArrayList<String>();
        for (vSphereCloud vSphereCloud : findAllVsphereClouds()) {
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

        public final @Deprecated ConcurrentMap<String, vSphereCloud> hypervisors = new ConcurrentHashMap<String, vSphereCloud>();
        private @Deprecated String vsHost;
        private @Deprecated String username;
        private @Deprecated String password;
        private @Deprecated int maxOnlineSlaves;

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

        /**
         * For UI.
         * @param vsHost From UI.
         * @param vsDescription From UI.
         * @param credentialsId From UI.
         * @return Result of the validation.
         */
        public FormValidation doTestConnection(@QueryParameter String vsHost,
                @QueryParameter String vsDescription,
                @QueryParameter String credentialsId) {
            try {
                /* We know that these objects are not null */
                if (vsHost.length() == 0) {
                    return FormValidation.error("vSphere Host is not specified");
                } else {
                    /* Perform other sanity checks. */
                    if (!vsHost.startsWith("https://")) {
                        return FormValidation.error("vSphere host must start with https://");
                    }
                    else if (vsHost.endsWith("/")) {
                        return FormValidation.error("vSphere host name must NOT end with a trailing slash");
                    }
                }

                final VSphereConnectionConfig config = new VSphereConnectionConfig(vsHost, credentialsId);
                final String effectiveUsername = config.getUsername();
                final String effectivePassword = config.getPassword();

                if (StringUtils.isEmpty(effectiveUsername)) {
                    return FormValidation.error("Username is not specified");
                }

                if (StringUtils.isEmpty(effectivePassword)) {
                    return FormValidation.error("Password is not specified");
                }

                VSphere.connect(vsHost + "/sdk", effectiveUsername, effectivePassword).disconnect();

                return FormValidation.ok("Connected successfully");
            } catch (RuntimeException e) {
                throw e;
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        }

        public FormValidation doCheckMaxOnlineSlaves(@QueryParameter String maxOnlineSlaves) {
            return FormValidation.validateNonNegativeInteger(maxOnlineSlaves);
        }

        public FormValidation doCheckInstanceCap(@QueryParameter String instanceCap) {
            return FormValidation.validateNonNegativeInteger(instanceCap);
        }
    }
}
