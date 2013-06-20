/*
 * To change this template, choose Tools | Templates
 * and open the template in the editor.
 */
package org.jenkinsci.plugins;

import hudson.Extension;
import hudson.Util;
import hudson.model.TaskListener;
import hudson.model.Descriptor;
import hudson.model.Label;
import hudson.model.Slave;
import hudson.slaves.Cloud;
import hudson.slaves.NodeProvisioner.PlannedNode;
import hudson.slaves.SlaveComputer;
import hudson.util.FormValidation;
import hudson.util.Scrambler;

import java.util.Collection;
import java.util.Collections;
import java.util.Hashtable;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.logging.Level;

import net.sf.json.JSONObject;

import org.jenkinsci.plugins.vsphere.tools.VSphere;
import org.jenkinsci.plugins.vsphere.tools.VSphereException;
import org.kohsuke.stapler.DataBoundConstructor;
import org.kohsuke.stapler.QueryParameter;
import org.kohsuke.stapler.StaplerRequest;

import com.vmware.vim25.ManagedObjectReference;
import com.vmware.vim25.VirtualMachineSnapshotInfo;
import com.vmware.vim25.VirtualMachineSnapshotTree;
import com.vmware.vim25.mo.VirtualMachine;
import com.vmware.vim25.mo.VirtualMachineSnapshot;

/**
 *
 * @author Admin
 */
public class vSphereCloud extends Cloud {

    private final String vsHost;
    private final String vsDescription;
    private final String username;
    private final String password;
    private final int maxOnlineSlaves;    
    private transient int currentOnlineSlaveCount = 0;
    private transient Hashtable<String, String> currentOnline;
    
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

    @DataBoundConstructor
    public vSphereCloud(String vsHost, String vsDescription,
            String username, String password, int maxOnlineSlaves) {
        super("vSphereCloud");
        this.vsHost = vsHost;
        this.vsDescription = vsDescription;
        this.username = username;
        this.password = Scrambler.scramble(Util.fixEmptyAndTrim(password));
        this.maxOnlineSlaves = maxOnlineSlaves;
        
        Log("STARTTING VSPHERE CLOUD");
    }
    
    protected void EnsureLists() {
        if (currentOnline == null)
            currentOnline = new Hashtable<String, String>();
    }

    public int getMaxOnlineSlaves() {
        return maxOnlineSlaves;
    }

    public String getPassword() {
        return Scrambler.descramble(password);
    }

    public String getUsername() {
        return username;
    }

    public String getVsDescription() {
        return vsDescription;
    }

    public String getVsHost() {
        return vsHost;
    }
    
    public VSphere vSphereInstance() throws VSphereException{
    	return VSphere.connect(vsHost + "/sdk", username, getPassword());
    }

    @Override
    public boolean canProvision(Label label) {
        return false;
    }

    @Override
    public Collection<PlannedNode> provision(Label label, int excessWorkload) {
        return Collections.emptySet();
    }

    @Override
    public String toString() {
        final StringBuilder sb = new StringBuilder();
        sb.append("vSphereCloud");
        sb.append("{Host='").append(vsHost).append('\'');
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
        if (currentOnline.containsValue(vmName))
            return Boolean.FALSE;
        
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

    public VirtualMachineSnapshot getSnapshotInTree(
            VirtualMachine vm, String snapName) throws Exception {
        if (vm == null || snapName == null) {
            return null;
        }

        VirtualMachineSnapshotInfo info = vm.getSnapshot();
        if (info != null)
        {
            VirtualMachineSnapshotTree[] snapTree = 
                    info.getRootSnapshotList();
            if (snapTree != null) {
                ManagedObjectReference mor = vSphereInstance().findSnapshotInTree(
                        snapTree, snapName);
                if (mor != null) {
                    return new VirtualMachineSnapshot(
                            vm.getServerConnection(), mor);
                }
            }
        }
        else
        {
            throw new Exception("No snapshots exist or unable to access the snapshot array");
        }            
        return null;
    }

    @Override
    public DescriptorImpl getDescriptor() {
        return (DescriptorImpl) super.getDescriptor();
    }

    @Extension
    public static final class DescriptorImpl extends Descriptor<Cloud> {

        public final ConcurrentMap<String, vSphereCloud> hypervisors = new ConcurrentHashMap<String, vSphereCloud>();
        private String vsHost;
        private String username;
        private String password;
        private int maxOnlineSlaves;

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
                @QueryParameter String username,
                @QueryParameter String password,
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

                if (username.length() == 0) {
                    return FormValidation.error("Username is not specified");
                }

                if (password.length() == 0) {
                    return FormValidation.error("Password is not specified");
                }

                VSphere.connect(vsHost + "/sdk", username, password);
                
                return FormValidation.ok("Connected successfully");
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        }
    }
}
