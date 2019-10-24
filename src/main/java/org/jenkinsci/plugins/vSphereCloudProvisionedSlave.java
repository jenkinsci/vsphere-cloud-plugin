/*
 * To change this template, choose Tools | Templates
 * and open the template in the editor.
 */
package org.jenkinsci.plugins;

import static org.jenkinsci.plugins.vsphere.tools.PermissionUtils.throwUnlessUserHasPermissionToConfigureSlave;

import edu.umd.cs.findbugs.annotations.CheckForNull;
import edu.umd.cs.findbugs.annotations.NonNull;
import hudson.AbortException;
import hudson.Extension;
import hudson.model.DescriptorVisibilityFilter;
import hudson.model.TaskListener;
import hudson.model.Computer;
import hudson.model.Descriptor;
import hudson.model.ItemGroup;
import hudson.model.Descriptor.FormException;
import hudson.slaves.*;
import hudson.util.FormValidation;

import java.io.IOException;

import org.kohsuke.stapler.AncestorInPath;
import org.kohsuke.stapler.DataBoundConstructor;
import org.kohsuke.stapler.QueryParameter;
import org.kohsuke.stapler.interceptor.RequirePOST;

import com.vmware.vim25.mo.VirtualMachine;
import com.vmware.vim25.mo.VirtualMachineSnapshot;

import java.util.ArrayList;
import java.util.List;

import jenkins.model.Jenkins;

/**
 *
 * @author Admin
 */
public class vSphereCloudProvisionedSlave extends vSphereCloudSlave {
    @DataBoundConstructor
    public vSphereCloudProvisionedSlave(String name, String nodeDescription,
            String remoteFS, String numExecutors, Mode mode,
            String labelString, ComputerLauncher delegateLauncher,
            RetentionStrategy retentionStrategy,
            List<? extends NodeProperty<?>> nodeProperties,
            String vsDescription, String vmName,
            boolean launchSupportForced, boolean waitForVMTools,
            String snapName, String launchDelay, String idleOption,
            String LimitedTestRunCount)
            throws FormException, IOException {
        super(name, nodeDescription,
              remoteFS, numExecutors,
              mode, labelString,
              delegateLauncher, retentionStrategy,
              nodeProperties, vsDescription,
              vmName, launchSupportForced,
              waitForVMTools, snapName,
              launchDelay, idleOption,
              LimitedTestRunCount);
    }

    @Override
    protected void _terminate(TaskListener listener) throws IOException, InterruptedException {
        super._terminate(listener);
        try {
            final ComputerLauncher l = getLauncher();
            final vSphereCloud cloud = findOurVsInstance(l);
            if (cloud != null) {
                final String cloneName = this.getComputer().getName();
                cloud.provisionedSlaveHasTerminated(cloneName);
            } else {
                vSphereCloud.Log(listener, "%1s._terminate for vmName %2s failed as getLauncher() returned %3s",
                        getClass().getSimpleName(), getVmName(), l);
            }
        } catch (RuntimeException ex) {
            vSphereCloud.Log(listener, ex, "%1s._terminate for vmName %2s failed",
                    getClass().getSimpleName(), getVmName());
        }
    }

    @Extension
    public static class vSphereCloudComputerListener extends ComputerListener {

        @Override
        public void preLaunch(Computer c, TaskListener taskListener) throws IOException, InterruptedException {
            /* We may be called on any slave type so check that we should
             * be in here. */
            if (!(c.getNode() instanceof vSphereCloudProvisionedSlave)) {
                return;
            }

            vSphereCloudLauncher vsL = (vSphereCloudLauncher) ((SlaveComputer) c).getLauncher();
            vSphereCloud vsC = vsL.findOurVsInstance();
            if (!vsC.markVMOnline(c.getDisplayName(), vsL.getVmName())) {
                throw new AbortException("The vSphere cloud will not allow this slave to start at this time.");
            }
        }
    }

    @Extension
    public static class DescriptorVisibilityFilterImpl extends DescriptorVisibilityFilter {
        @Override
        public boolean filter(@CheckForNull Object context, @NonNull Descriptor descriptor) {
            return !(descriptor instanceof DescriptorImpl);
        }
    }

    @Extension
    public static final class DescriptorImpl extends SlaveDescriptor {

        public DescriptorImpl() {
            load();
        }

        @Override
        public String getDisplayName() {
            return "Slave created from a vSphere Cloud slave template";
        }

        @Override
        public boolean isInstantiable() {
            return false;
        }

        public List<vSphereCloud> getvSphereClouds() {
            List<vSphereCloud> result = new ArrayList<vSphereCloud>();
            for (Cloud cloud : Jenkins.getInstance().clouds) {
                if (cloud instanceof vSphereCloud) {
                    result.add((vSphereCloud) cloud);
                }
            }
            return result;
        }

        public vSphereCloud getSpecificvSphereCloud(String vsDescription)
                throws Exception {
            for (vSphereCloud vs : getvSphereClouds()) {
                if (vs.getVsDescription().equals(vsDescription)) {
                    return vs;
                }
            }
            throw new Exception("The vSphere Cloud doesn't exist");
        }

        public List<String> getIdleOptions() {
            List<String> options = new ArrayList<String>();
            options.add("Shutdown");
            options.add("Shutdown and Revert");
            options.add("Revert and Restart");
            options.add("Revert and Reset");
            options.add("Suspend");
            options.add("Reset");
            options.add("Reconnect and Revert");
            options.add("Nothing");
            return options;
        }

        public FormValidation doCheckLaunchDelay(@QueryParameter String value) {
            return FormValidation.validatePositiveInteger(value);
        }

        @RequirePOST
        public FormValidation doTestConnection(@AncestorInPath ItemGroup<?> context,
                @QueryParameter String vsDescription,
                @QueryParameter String vmName,
                @QueryParameter String snapName) {
            throwUnlessUserHasPermissionToConfigureSlave(context);
            try {
                vSphereCloud vsC = getSpecificvSphereCloud(vsDescription);
                VirtualMachine vm = vsC.vSphereInstance().getVmByName(vmName);
                if (vm == null) {
                    return FormValidation.error("Virtual Machine was not found");
                }
                if (!snapName.isEmpty()) {
                    VirtualMachineSnapshot snap = vsC.vSphereInstance().getSnapshotInTree(vm, snapName);
                    if (snap == null) {
                        return FormValidation.error("Virtual Machine snapshot was not found");
                    }
                }
                return FormValidation.ok("Virtual Machine found successfully");
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        }
    }
}
