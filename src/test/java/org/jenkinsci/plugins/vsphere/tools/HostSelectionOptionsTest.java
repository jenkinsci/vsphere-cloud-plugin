package org.jenkinsci.plugins.vsphere.tools;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;

import hudson.util.ListBoxModel;
import org.jenkinsci.plugins.vSphereCloud;
import org.jenkinsci.plugins.vsphere.VSphereConnectionConfig;
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
    void withoutACloudOnlyTheCallSiteCounts() {
        HostSelectionOptions options = vSphereCloud.hostSelectionOptions(null, true, null);
        assertThat(options.isRequireCores(), is(true));
        assertThat(options.isRequireMemory(), is(false));
    }

    @Test
    void triStateItemsStartWithInherit() {
        ListBoxModel items = HostSelectionOptions.triStateItems();
        assertThat(items.get(0).value, is(""));
        assertThat(items.get(1).value, is("true"));
        assertThat(items.get(2).value, is("false"));
    }
}
