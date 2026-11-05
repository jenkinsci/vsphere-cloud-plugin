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
import edu.umd.cs.findbugs.annotations.NonNull;
import hudson.*;
import hudson.model.*;
import hudson.util.FormValidation;
import java.io.IOException;
import java.io.PrintStream;
import java.util.ArrayList;
import java.util.List;
import jenkins.model.Jenkins;
import org.jenkinsci.plugins.vsphere.tools.VSphere;
import org.jenkinsci.plugins.vsphere.tools.VSphereException;
import org.jenkinsci.plugins.vsphere.tools.VSphereLogger;

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

    /** True if the value refers to a build variable such as {@code $RAM} or {@code ${RAM}}, expanded at build time. */
    static boolean refersToVariable(final String value) {
        return value != null && value.contains("$");
    }

    /**
     * Form validation for a numeric field that may also hold a build variable (JENKINS-31468).
     *
     * @param what the human-readable field name used in the messages
     * @param value the entered value
     * @param required whether an empty value is an error
     */
    static FormValidation checkPositiveIntegerOrVariable(
            final String what, final String value, final boolean required) {
        if (value == null || value.trim().isEmpty()) {
            return required ? FormValidation.error(Messages.validation_required(what)) : FormValidation.ok();
        }
        if (refersToVariable(value)) {
            // Can only be checked once the variable is expanded in a build
            return FormValidation.ok();
        }
        try {
            if (Long.parseLong(value.trim()) > 0) {
                return FormValidation.ok();
            }
        } catch (NumberFormatException e) {
            // fall through
        }
        return FormValidation.error(Messages.validation_positiveInteger(what));
    }

    /**
     * A stricter-than-necessary step may offer a more lenient variant of itself, to be used for one retry when
     * vCenter refuses the reconfiguration (e.g. a MAC address that cannot be set as a manual one,
     * JENKINS-34001). The variant is a new instance, so the step as configured is never modified.
     *
     * @return the variant to retry with, or null if this step has none
     */
    public ReconfigureStep fallbackVariant() {
        return null;
    }

    /** What to do with one step when building the reconfiguration. */
    @FunctionalInterface
    public interface StepAction {
        void perform(ReconfigureStep step) throws VSphereException, IOException, InterruptedException;
    }

    /** Sends a reconfiguration to vCenter. */
    @FunctionalInterface
    public interface SpecSubmitter {
        void submit(VirtualMachineConfigSpec spec) throws VSphereException;
    }

    /**
     * Builds one reconfiguration out of all the steps and submits it. If vCenter refuses it and some of the
     * steps have a more lenient {@link #fallbackVariant()}, builds it again with those and submits it once more.
     *
     * @param submit sends the built reconfiguration to vCenter
     * @param action performs a step, after it has been told which VM, vSphere and spec to work with
     */
    public static void reconfigureVm(
            final VSphere vsphere,
            final VirtualMachine vm,
            final List<? extends ReconfigureStep> steps,
            final StepAction action,
            final SpecSubmitter submit,
            final PrintStream log)
            throws VSphereException, IOException, InterruptedException {
        final VirtualMachineConfigSpec spec = new VirtualMachineConfigSpec();
        for (ReconfigureStep step : steps) {
            runStep(step, vsphere, vm, spec, action);
        }
        try {
            submit.submit(spec);
            return;
        } catch (VSphereException first) {
            final List<ReconfigureStep> retrySteps = new ArrayList<>();
            boolean anyFallback = false;
            for (ReconfigureStep step : steps) {
                final ReconfigureStep variant = step.fallbackVariant();
                anyFallback |= variant != null;
                retrySteps.add(variant != null ? variant : step);
            }
            if (!anyFallback) {
                throw first;
            }
            VSphereLogger.vsLogger(
                    log,
                    "The server refused the reconfiguration (" + first.getMessage()
                            + "); retrying once without declaring the MAC address as a manual one...");
            final VirtualMachineConfigSpec retrySpec = new VirtualMachineConfigSpec();
            for (ReconfigureStep step : retrySteps) {
                runStep(step, vsphere, vm, retrySpec, action);
            }
            try {
                submit.submit(retrySpec);
            } catch (VSphereException second) {
                second.addSuppressed(first);
                throw new VSphereException(
                        "Reconfiguration failed: " + first.getMessage()
                                + "; the retry without a manual MAC address setting failed too: "
                                + second.getMessage(),
                        second);
            }
        }
    }

    private static void runStep(
            final ReconfigureStep step,
            final VSphere vsphere,
            final VirtualMachine vm,
            final VirtualMachineConfigSpec spec,
            final StepAction action)
            throws VSphereException, IOException, InterruptedException {
        step.setVsphere(vsphere);
        step.setVM(vm);
        step.setVirtualMachineConfigSpec(spec);
        action.perform(step);
    }

    public static List<ReconfigureStepDescriptor> all() {
        return Jenkins.getInstance().getDescriptorList(ReconfigureStep.class);
    }

    /**
     * Flavour for freestyle builds, kept for callers that still use it: it does exactly what the
     * {@code Run} flavour does, so that a failure is reported by exception rather than swallowed (JENKINS-38472).
     */
    public boolean perform(final AbstractBuild<?, ?> build, final Launcher launcher, final BuildListener listener)
            throws InterruptedException, IOException {
        FilePath workspace = build.getWorkspace();
        if (workspace == null) {
            workspace = new FilePath(build.getRootDir());
        }
        perform((Run<?, ?>) build, workspace, launcher, listener);
        return true;
    }

    public abstract void perform(
            @NonNull Run<?, ?> run, FilePath filePath, @NonNull Launcher launcher, @NonNull TaskListener listener)
            throws InterruptedException, IOException;

    public abstract void perform(@NonNull EnvVars env, @NonNull TaskListener listener) throws VSphereException;

    protected VirtualDevice findDeviceByLabel(VirtualDevice[] devices, String label) {
        for (VirtualDevice d : devices) {
            if (d.getDeviceInfo().getLabel().contentEquals(label)) {
                return d;
            }
        }
        return null;
    }

    protected EnvVars extractEnvironment(final Run<?, ?> run, final TaskListener listener) throws VSphereException {
        try {
            EnvVars env = run.getEnvironment(listener);

            if (run instanceof AbstractBuild) {
                env.overrideAll(((AbstractBuild) run).getBuildVariables()); // Add in matrix axes..
            }

            return env;
        } catch (Exception e) {
            throw new VSphereException(e);
        }
    }

    public abstract static class ReconfigureStepDescriptor extends Descriptor<ReconfigureStep> {

        protected ReconfigureStepDescriptor() {}

        protected ReconfigureStepDescriptor(Class<? extends ReconfigureStep> clazz) {
            super(clazz);
        }
    }

    public static enum DeviceAction {
        ADD(Messages.vm_reconfigure_Add()) {},

        EDIT(Messages.vm_reconfigure_Edit()) {},

        REMOVE(Messages.vm_reconfigure_Remove()) {};

        private final String label;

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
