package org.jenkinsci.plugins;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.nullValue;

import hudson.slaves.JNLPLauncher;
import hudson.slaves.RetentionStrategy;
import java.util.List;
import org.jenkinsci.plugins.vsphere.builders.ReconfigureCpu;
import org.jenkinsci.plugins.vsphere.builders.ReconfigureMemory;
import org.jenkinsci.plugins.vsphere.builders.ReconfigureStep;
import org.junit.jupiter.api.Test;

/**
 * A template's reconfigure steps resize the clone right after it is created, so host selection has
 * to be told the size the VM will end up with, not the master image's.
 */
class TemplateReconfiguredSizeTest {

    private static vSphereCloudSlaveTemplate templateWith(ReconfigureStep... steps) {
        return new vSphereCloudSlaveTemplate(
                "prefix",
                "",
                null,
                null,
                false,
                null,
                null,
                null,
                null,
                null,
                null,
                1,
                1,
                null,
                null,
                null,
                false,
                false,
                0,
                0,
                false,
                null,
                null,
                0,
                null,
                new JNLPLauncher(),
                RetentionStrategy.NOOP,
                null,
                null,
                List.of(steps));
    }

    @Test
    void sizeComesFromTheReconfigureSteps() throws Exception {
        vSphereCloudSlaveTemplate template =
                templateWith(new ReconfigureCpu("16", "4"), new ReconfigureMemory("65536"));
        assertThat(template.reconfiguredCpuCores(), is(16));
        assertThat(template.reconfiguredMemoryMB(), is(65536L));
    }

    @Test
    void noStepsMeansTheMasterImageSizeApplies() {
        vSphereCloudSlaveTemplate template = templateWith();
        assertThat(template.reconfiguredCpuCores(), nullValue());
        assertThat(template.reconfiguredMemoryMB(), nullValue());
    }

    @Test
    void valuesThatCannotBeResolvedAheadOfTimeAreIgnored() throws Exception {
        vSphereCloudSlaveTemplate template =
                templateWith(new ReconfigureCpu("${CPUS}", "1"), new ReconfigureMemory("lots"));
        assertThat(template.reconfiguredCpuCores(), nullValue());
        assertThat(template.reconfiguredMemoryMB(), nullValue());
    }

    @Test
    void onlyTheKindOfStepThatIsPresentCounts() throws Exception {
        vSphereCloudSlaveTemplate template = templateWith(new ReconfigureMemory("8192"));
        assertThat(template.reconfiguredCpuCores(), nullValue());
        assertThat(template.reconfiguredMemoryMB(), is(8192L));
    }
}
