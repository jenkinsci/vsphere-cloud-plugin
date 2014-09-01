package org.jenkinsci.plugins;

import hudson.DescriptorExtensionList;
import hudson.model.Computer;
import hudson.model.Descriptor;
import hudson.slaves.OfflineCause;
import hudson.slaves.RetentionStrategy;
import hudson.slaves.SlaveComputer;
import jenkins.model.Jenkins;

public abstract class vSphereRetentionStrategy extends RetentionStrategy<vSphereCloudSlaveComputer> {
    
    public static DescriptorExtensionList<RetentionStrategy<?>,Descriptor<RetentionStrategy<?>>> all() {
        return (DescriptorExtensionList) Jenkins.getInstance().getDescriptorList(RetentionStrategy.class);
    }

    protected void disconnect(vSphereCloudSlaveComputer slaveComputer) {
        
        try {
            if (slaveComputer != null) {
                vSphereCloud.Log("Disconnecting the slave agent on %s due to retention strategy", slaveComputer.getName());

                slaveComputer.setTemporarilyOffline(true, new OfflineCause.ByCLI("vSphere Plugin marking the slave as offline due to retention strategy."));
                slaveComputer.waitUntilOffline();
                vSphereCloudLauncher vSphereLauncher = (vSphereCloudLauncher) slaveComputer.getNode().getLauncher();
                vSphereLauncher.postDisconnectVSphereActions((SlaveComputer) slaveComputer, null);
                slaveComputer.setTemporarilyOffline(false, new OfflineCause.ByCLI("vSphere Plugin marking the slave as online due to retention strategy."));

            } else {
                vSphereCloud.Log("Attempting to shutdown slave due to retention strategy, but cannot determine slave");
            }
        } catch (NullPointerException ex) {
            vSphereCloud.Log("NullPointerException thrown while retrieving the slave agent: %s", ex.getMessage());
        } catch (InterruptedException ex) {
            vSphereCloud.Log("InterruptedException thrown while marking the slave as online or offline: %s", ex.getMessage());
        }
    }

}
