package org.jenkinsci.plugins.vsphere.builders;

import com.vmware.vim25.VirtualMachineConfigSpec;
import hudson.EnvVars;
import hudson.model.TaskListener;
import org.junit.jupiter.api.Test;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.notNullValue;
import static org.hamcrest.Matchers.nullValue;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Unit tests for the optional {@code cpuLimitMHz} CPU reservation setting on {@link ReconfigureCpu}.
 * Exercised via {@link ReconfigureCpu#perform(EnvVars, TaskListener)} -- the plain, Jenkins-free
 * entry point (used for template-time reconfiguration) -- against a bare
 * {@link VirtualMachineConfigSpec}, which (like the rest of the yavijava vim25 types used
 * throughout this codebase's tests) is a plain POJO needing no live vCenter/Jenkins connection.
 */
class ReconfigureCpuTest {

    private static ReconfigureCpu newStep() throws Exception {
        return new ReconfigureCpu("4", "2");
    }

    private static VirtualMachineConfigSpec reconfigure(ReconfigureCpu step, EnvVars env) throws Exception {
        VirtualMachineConfigSpec spec = new VirtualMachineConfigSpec();
        step.setVirtualMachineConfigSpec(spec);
        step.perform(env, TaskListener.NULL);
        return spec;
    }

    @Test
    void cpuLimitMHzDefaultsToNull() throws Exception {
        assertThat(newStep().getCpuLimitMHz(), nullValue());
    }

    @Test
    void cpuLimitMHzIsSettable() throws Exception {
        ReconfigureCpu step = newStep();
        step.setCpuLimitMHz("2000");
        assertThat(step.getCpuLimitMHz(), is("2000"));
    }

    @Test
    void noCpuAllocationIsSetWhenCpuLimitMHzIsNeverGiven() throws Exception {
        VirtualMachineConfigSpec spec = reconfigure(newStep(), new EnvVars());

        assertThat(spec.getCpuAllocation(), nullValue());
        // The rest of the reconfiguration still happens normally.
        assertThat(spec.getNumCPUs(), is(4));
        assertThat(spec.getNumCoresPerSocket(), is(2));
    }

    @Test
    void noCpuAllocationIsSetWhenCpuLimitMHzIsBlank() throws Exception {
        ReconfigureCpu step = newStep();
        step.setCpuLimitMHz("");

        VirtualMachineConfigSpec spec = reconfigure(step, new EnvVars());

        assertThat(spec.getCpuAllocation(), nullValue());
    }

    @Test
    void cpuAllocationReservationIsSetWhenCpuLimitMHzIsGiven() throws Exception {
        ReconfigureCpu step = newStep();
        step.setCpuLimitMHz("2000");

        VirtualMachineConfigSpec spec = reconfigure(step, new EnvVars());

        assertThat(spec.getCpuAllocation(), notNullValue());
        assertThat(spec.getCpuAllocation().getReservation(), is(2000L));
    }

    @Test
    void cpuLimitMHzIsExpandedFromEnvironmentVariables() throws Exception {
        ReconfigureCpu step = newStep();
        step.setCpuLimitMHz("${MY_CPU_LIMIT}");
        EnvVars env = new EnvVars();
        env.put("MY_CPU_LIMIT", "3000");

        VirtualMachineConfigSpec spec = reconfigure(step, env);

        assertThat(spec.getCpuAllocation().getReservation(), is(3000L));
    }

    @Test
    void nonIntegerCpuLimitMHzThrowsAtReconfigureTime() throws Exception {
        ReconfigureCpu step = newStep();
        step.setCpuLimitMHz("not-a-number");
        step.setVirtualMachineConfigSpec(new VirtualMachineConfigSpec());

        assertThrows(NumberFormatException.class, () -> step.perform(new EnvVars(), TaskListener.NULL));
    }
}
