/*   Copyright 2013, MANDIANT, Eric Lordahl
 *
 *   Licensed under the Apache License, Version 2.0 (the "License");
 *   you may not use this file except in compliance with the License.
 *   You may obtain a copy of the License at
 *
 *       http://www.apache.org/licenses/LICENSE-2.0
 *
 *   Unless required by applicable law or agreed to in writing, software
 *   distributed under the License is distributed on an "AS IS" BASIS,
 *   WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *   See the License for the specific language governing permissions and
 *   limitations under the License.
 */
package org.jenkinsci.plugins.vsphere.builders;

import com.vmware.vim25.VirtualDevice;
import com.vmware.vim25.VirtualMachineConfigSpec;
import com.vmware.vim25.mo.VirtualMachine;
import hudson.*;
import hudson.model.*;
import jenkins.model.Jenkins;
import org.jenkinsci.plugins.vsphere.tools.VSphere;
import org.jenkinsci.plugins.vsphere.tools.VSphereException;

import edu.umd.cs.findbugs.annotations.NonNull;
import java.io.IOException;
import java.util.List;

/**
 * Define a base class for all Reconfigure Acion steps.  All Reconfigure Action steps should extend
 * this class.
 */
public abstract class ReconfigureStep extends AbstractDescribableImpl<ReconfigureStep> implements ExtensionPoint {

    protected VirtualMachineConfigSpec spec;
    protected VirtualMachine vm;
	protected VSphere vsphere;

	public VSphere getVsphere() {
		return vsphere;
	}

	public void setVsphere(VSphere vsphere) {
		this.vsphere = vsphere;
	}

    public VirtualMachine getVM() {
        return this.vm;
    }

    public void setVM(VirtualMachine vm) {
        this.vm = vm;
    }

    public VirtualMachineConfigSpec getVirtualMachineConfigSpec() {
        return spec;
    }

    public void setVirtualMachineConfigSpec(VirtualMachineConfigSpec spec) {
        this.spec = spec;
    }

	public static List<ReconfigureStepDescriptor> all() {
        return Jenkins.getInstance().getDescriptorList(ReconfigureStep.class);
	}

	public abstract boolean perform(final AbstractBuild<?, ?> build, final Launcher launcher, final BuildListener listener) throws VSphereException;

    public abstract void perform(@NonNull Run<?, ?> run, FilePath filePath, @NonNull Launcher launcher, @NonNull TaskListener listener) throws InterruptedException, IOException;

    protected VirtualDevice findDeviceByLabel(VirtualDevice[] devices, String label) {
        for(VirtualDevice d : devices) {
            if(d.getDeviceInfo().getLabel().contentEquals(label)) {
                return d;
            }
        }
        return null;
    }

	public static abstract class ReconfigureStepDescriptor extends Descriptor<ReconfigureStep> {

		protected ReconfigureStepDescriptor() { }

		protected ReconfigureStepDescriptor(Class<? extends ReconfigureStep> clazz) {
			super(clazz);
		}
	}

    public static enum DeviceAction {

        ADD(Messages.vm_reconfigure_Add()) {

        },
        EDIT(Messages.vm_reconfigure_Edit()) {

        },
        REMOVE(Messages.vm_reconfigure_Remove()) {

        };

        final private String label;

        private DeviceAction(String label) {
            this.label = label;
        }

        public String getLabel() {
            return label;
        }

        public String __toString() {
            return getLabel();
        }
    }
}
