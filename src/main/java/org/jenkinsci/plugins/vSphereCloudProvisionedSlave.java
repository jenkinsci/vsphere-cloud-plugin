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
            /* We may be called on any agent type so check that we should
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
    public static final class DescriptorImpl extends vSphereCloudSlave.DescriptorImpl {
        @Override
        public String getDisplayName() {
            return super.getDisplayName()+", auto-provisioned by Jenkins from cloud template";
        }

        @Override
        public boolean isInstantiable() {
            /*
             * This type of agent can't be directly created by the user through the UI.
             * The user defines a vSphere agent template and _that_ then creates these "on demand".
             */
            return false;
        }
    }
}
