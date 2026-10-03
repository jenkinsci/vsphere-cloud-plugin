package org.jenkinsci.plugins.vsphere.builders;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.instanceOf;
import static org.hamcrest.Matchers.is;

import hudson.model.FreeStyleProject;
import java.util.List;
import org.jenkinsci.plugins.vSphereCloud;
import org.jenkinsci.plugins.vsphere.VSphereBuildStepContainer;
import org.jenkinsci.plugins.vsphere.VSphereConnectionConfig;
import org.junit.jupiter.api.Test;
import org.jvnet.hudson.test.Issue;
import org.jvnet.hudson.test.JenkinsRule;
import org.jvnet.hudson.test.junit.jupiter.WithJenkins;

@WithJenkins
class ReconfigureDiskFreestyleTest {

    @Test
    @Issue("JENKINS-66937")
    void datastoreSurvivesFreestyleConfigRoundTrip(JenkinsRule r) throws Exception {
        r.jenkins.clouds.add(new vSphereCloud(
                new VSphereConnectionConfig("vcenter.example.com", false, "creds"), "some-server", 0, 0, false, null));

        ReconfigureDisk disk = new ReconfigureDisk("20", "my-datastore");
        disk.setDeviceAction(ReconfigureDisk.DeviceAction.ADD);

        FreeStyleProject p = r.createFreeStyleProject();
        p.getBuildersList()
                .add(new VSphereBuildStepContainer(new Reconfigure("some-vm", List.of(disk)), "some-server"));

        // Same path as saving and re-opening the job configuration page in the UI
        r.configRoundtrip(p);

        Reconfigure saved =
                (Reconfigure) ((VSphereBuildStepContainer) p.getBuildersList().get(0)).getBuildStep();
        assertThat(saved.getReconfigureSteps().get(0), instanceOf(ReconfigureDisk.class));
        ReconfigureDisk savedDisk =
                (ReconfigureDisk) saved.getReconfigureSteps().get(0);
        assertThat(savedDisk.getDatastore(), is("my-datastore"));
        assertThat(savedDisk.getDiskSize(), is("20"));
    }
}
