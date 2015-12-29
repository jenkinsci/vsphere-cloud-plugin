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
import java.util.concurrent.Callable;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.logging.Level;
import javax.annotation.CheckForNull;
import jenkins.model.Jenkins;
import jenkins.slaves.iterators.api.NodeIterator;

import net.sf.json.JSONObject;
import org.apache.commons.lang.StringUtils;

import org.apache.commons.lang.builder.HashCodeBuilder;
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
    
    private static java.util.logging.Logger VSLOG = java.util.logging.Logger.getLogger("vsphere-cloud");
    private static void InternalLog(Slave slave, SlaveComputer slaveComputer, TaskListener listener, String format, Object... args)
    {
        String s = "";
        if (slave != null)
            s = String.format("[%s] ", slave.getNodeName());
        if (slaveComputer != null)
            s = String.format("[%s] ", slaveComputer.getName());
        s = s + String.format(format, args);
        s = s + "\n";
        if (listener != null)
            listener.getLogger().print(s);
        VSLOG.log(Level.INFO, s);
    }
    public static void Log(String msg) {
        InternalLog(null, null, null, msg, null);
    }
    public static void Log(String format, Object... args) {
        InternalLog(null, null, null, format, args);
    }            
    public static void Log(TaskListener listener, String msg) {
        InternalLog(null, null, listener, msg, null);
    }
    public static void Log(TaskListener listener, String format, Object... args) {
        InternalLog(null, null, listener, format, args);
    }
    public static void Log(Slave slave, TaskListener listener, String msg) {
        InternalLog(slave, null, listener, msg, null);
    }
    public static void Log(Slave slave, TaskListener listener, String format, Object... args) {
        InternalLog(slave, null, listener, format, args);
    }
    public static void Log(SlaveComputer slave, TaskListener listener, String msg) {
        InternalLog(null, slave, listener, msg, null);
    }
    public static void Log(SlaveComputer slave, TaskListener listener, String format, Object... args) {
        InternalLog(null, slave, listener, format, args);
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
    
    protected void EnsureLists() {
        if (currentOnline == null)
            currentOnline = new ConcurrentHashMap<String, String>();
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
    
    public vSphereCloudSlaveTemplate getTemplate(final String template) {
        for(vSphereCloudSlaveTemplate t : this.templates) {
            if(t.getCloneNamePrefix().equals(template)) {
                return t;
            }
        }
        return null;
    }
    
    public vSphereCloudSlaveTemplate getTemplate(final Label label) {
        for(vSphereCloudSlaveTemplate t : this.templates) {
            if(t.getMode() == Node.Mode.NORMAL) {
                if(label == null || label.matches(t.getLabelSet())) {
                    return t;
                }
            } else if(t.getMode() == Node.Mode.EXCLUSIVE) {
                if(label != null && label.matches(t.getLabelSet())) {
                    return t;
                }
            }
        }
        return null;
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
    
    public VSphere vSphereInstance() throws VSphereException{
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
        return getTemplate(label) != null;
    }
        
    private boolean addProvisionedSlave(final String cloneNamePrefix, final int templateInstanceCap) throws VSphereException {
        final VSphere vSphere = vSphereInstance();
        final int totalVms = vSphere.countVms();
        final int totalVmsWithPrefix = vSphere.countVmsByPrefix(cloneNamePrefix);

        VSLOG.info("There are " + totalVms + " number of VMs in this bucket. The instance cap for the bucket is: " + this.instanceCap);
        VSLOG.info("There are " + totalVmsWithPrefix + " number of VMs with the prefix " + cloneNamePrefix + ". The instance cap for VMs with that prefix is: " + templateInstanceCap);
        if(totalVms >= this.instanceCap) {
            return false;
        }
        if(templateInstanceCap != 0 && totalVmsWithPrefix >= templateInstanceCap) {
            return false;
        }
        return true;
    }
    
    @Override
    public Collection<PlannedNode> provision(final Label label, int excessWorkload) {
        try {
            for(vSphereCloudSlave n : NodeIterator.nodes(vSphereCloudSlave.class)) {
                if(n.getComputer().isOffline() && label.matches(n.getAssignedLabels())) {
                    n.getComputer().tryReconnect();
                    excessWorkload -= n.getNumExecutors();
                }
            }
            VSLOG.log(Level.INFO, "Excess workload after pending provision instances: " + excessWorkload);
            
            final List<PlannedNode> plannedNodes = new ArrayList<PlannedNode>();
            final vSphereCloudSlaveTemplate template = getTemplate(label);
            final int templateInstanceCap = template.getTemplateInstanceCap() == 0 ? Integer.MAX_VALUE : template.getTemplateInstanceCap();

            while(excessWorkload > 0) {
                if(!addProvisionedSlave(template.getCloneNamePrefix(), templateInstanceCap)) {
                    break;
                }
                
                plannedNodes.add(new PlannedNode(template.getCloneNamePrefix(), Computer.threadPoolForRemoting.submit(new Callable<Node>() {
                    public Node call() throws Exception {
                        vSphereCloudProvisionedSlave slave = template.provision(StreamTaskListener.fromStdout());
                        return slave;
                    }
                }), template.getNumberOfExceutors()));
                
                excessWorkload -= template.getNumberOfExceutors();
            }
            
            return plannedNodes;
        } catch (Exception ex) {
            VSLOG.log(Level.WARNING, "Failed to count the # of live instances in VSphere",ex);
            return Collections.emptySet();
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
        EnsureLists();
        
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
        EnsureLists();

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
        EnsureLists();
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
         */
        public FormValidation doTestConnection(@QueryParameter String vsHost,
                @QueryParameter String vsDescription,
                @QueryParameter String credentialsId,
                @QueryParameter int maxOnlineSlaves) {
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
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        }
    }
}
