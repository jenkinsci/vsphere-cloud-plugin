package org.jenkinsci.plugins.vsphere.tools;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;

import hudson.util.ListBoxModel;
import org.jenkinsci.plugins.vsphere.VSphereConnectionConfig;
import org.jenkinsci.plugins.vsphere.vSphereCloud;
import org.junit.jupiter.api.Test;
import org.jvnet.hudson.test.junit.jupiter.WithJenkins;

/** The cloud-wide host size requirements are a default that a call site can override either way. */
@WithJenkins
class HostSelectionOptionsTest {

    private static vSphereCloud cloud(boolean cores, boolean memory) {
        vSphereCloud cloud = new vSphereCloud(
                new VSphereConnectionConfig("vcenter.example.com", "creds", null), "test", 0, 0, false, null);
        cloud.setHostSelectionRequireCores(cores);
        cloud.setHostSelectionRequireMemory(memory);
        return cloud;
    }

    @Test
    void unsetCallSiteInheritsTheCloudDefault() {
        HostSelectionOptions on = vSphereCloud.hostSelectionOptions(cloud(true, true), null, null);
        assertThat(on.isRequireCores(), is(true));
        assertThat(on.isRequireMemory(), is(true));
        HostSelectionOptions off = vSphereCloud.hostSelectionOptions(cloud(false, false), null, null);
        assertThat(off.isRequireCores(), is(false));
        assertThat(off.isRequireMemory(), is(false));
    }

    @Test
    void explicitCallSiteValueOverridesTheCloudDefaultEitherWay() {
        HostSelectionOptions turnedOff = vSphereCloud.hostSelectionOptions(cloud(true, true), false, null);
        assertThat(turnedOff.isRequireCores(), is(false));
        assertThat(turnedOff.isRequireMemory(), is(true));
        HostSelectionOptions turnedOn = vSphereCloud.hostSelectionOptions(cloud(false, false), null, true);
        assertThat(turnedOn.isRequireCores(), is(false));
        assertThat(turnedOn.isRequireMemory(), is(true));
    }

    @Test
    void availableMemoryRequirementInheritsAndOverridesLikeTheOthers() {
        vSphereCloud on = cloud(false, false);
        on.setHostSelectionRequireAvailableMemory(true);
        assertThat(vSphereCloud.hostSelectionOptions(on, null, null, null).isRequireAvailableMemory(), is(true));
        assertThat(vSphereCloud.hostSelectionOptions(on, null, null, false).isRequireAvailableMemory(), is(false));
        vSphereCloud off = cloud(false, false);
        assertThat(vSphereCloud.hostSelectionOptions(off, null, null, null).isRequireAvailableMemory(), is(false));
        assertThat(vSphereCloud.hostSelectionOptions(off, null, null, true).isRequireAvailableMemory(), is(true));
        assertThat(vSphereCloud.hostSelectionOptions(null, null, null, true).isRequireAvailableMemory(), is(true));
        // the older three-argument form leaves it to the cloud's default
        assertThat(vSphereCloud.hostSelectionOptions(on, null, null).isRequireAvailableMemory(), is(true));
    }

    @Test
    void withoutACloudOnlyTheCallSiteCounts() {
        HostSelectionOptions options = vSphereCloud.hostSelectionOptions(null, true, null);
        assertThat(options.isRequireCores(), is(true));
        assertThat(options.isRequireMemory(), is(false));
    }

    @Test
    void triStateStringFormKeepsUnsetDistinctFromFalse() {
        assertThat(HostSelectionOptions.triStateToString(null), is(""));
        assertThat(HostSelectionOptions.triStateToString(true), is("true"));
        assertThat(HostSelectionOptions.triStateToString(false), is("false"));
        assertThat(HostSelectionOptions.triStateFromString(""), is((Boolean) null));
        assertThat(HostSelectionOptions.triStateFromString(null), is((Boolean) null));
        assertThat(HostSelectionOptions.triStateFromString("nonsense"), is((Boolean) null));
        assertThat(HostSelectionOptions.triStateFromString("TRUE"), is(true));
        assertThat(HostSelectionOptions.triStateFromString(" false "), is(false));
    }

    @Test
    void cloudWeightsReachTheOptions() {
        vSphereCloud cloud = cloud(false, false);
        cloud.setHostWeightFreeCpuMhz(5);
        cloud.setHostWeightFreeMemoryPercent(7);
        HostWeights weights =
                vSphereCloud.hostSelectionOptions(cloud, null, null).getWeights();
        assertThat(weights.getFreeCpuMhz(), is(5d));
        assertThat(weights.getFreeMemoryPercent(), is(7d));
        assertThat(weights.getFreeCpuPercent(), is(0d));
        assertThat(
                vSphereCloud.hostSelectionOptions(null, null, null).getWeights().isDefault(), is(true));
    }

    @Test
    void callSiteWeightsReplaceTheCloudsAsAWholeAndAbsentOnesInheritThem() throws Exception {
        vSphereCloud cloud = cloud(false, false);
        cloud.setHostWeightFreeCpuMhz(5);
        cloud.setHostWeightFreeMemoryPercent(7);

        HostWeights inherited =
                vSphereCloud.hostSelectionOptions(cloud, null, null, null, null).getWeights();
        assertThat(inherited.getFreeCpuMhz(), is(5d));
        assertThat(inherited.getFreeMemoryPercent(), is(7d));

        HostWeights replaced = vSphereCloud
                .hostSelectionOptions(cloud, null, null, null, HostWeights.parseOverride(null, "2", null, null))
                .getWeights();
        assertThat(replaced.getFreeCpuMhz(), is(0d));
        assertThat(replaced.getFreeCpuPercent(), is(2d));
        assertThat(replaced.getFreeMemoryPercent(), is(0d));

        HostWeights optedOut = vSphereCloud
                .hostSelectionOptions(cloud, null, null, null, HostWeights.parseOverride("0", "0", "0", "0"))
                .getWeights();
        assertThat(optedOut.isDefault(), is(true));
    }

    @Test
    void triStateItemsStartWithInherit() {
        ListBoxModel items = HostSelectionOptions.triStateItems();
        assertThat(items.get(0).value, is(""));
        assertThat(items.get(1).value, is("true"));
        assertThat(items.get(2).value, is("false"));
    }
}
