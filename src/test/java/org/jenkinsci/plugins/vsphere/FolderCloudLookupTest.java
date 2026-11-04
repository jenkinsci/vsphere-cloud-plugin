package org.jenkinsci.plugins.vsphere;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.instanceOf;
import static org.hamcrest.Matchers.is;

import com.cloudbees.hudson.plugins.folder.Folder;
import hudson.model.FreeStyleProject;
import java.util.Collections;
import org.jenkinsci.plugins.folder.FolderVSphereCloudProperty;
import org.jenkinsci.plugins.vSphereCloud;
import org.jenkinsci.plugins.vsphere.builders.Delete;
import org.junit.jupiter.api.Test;
import org.jvnet.hudson.test.JenkinsRule;
import org.jvnet.hudson.test.junit.jupiter.WithJenkins;

/**
 * The clouds of a folder are found from the URL of the page, whose folder names are URL-encoded,
 * so they must also be found when the name of the folder has characters that get encoded.
 */
@WithJenkins
class FolderCloudLookupTest {

    private static final String FOLDER = "My Folder";
    private static final String CLOUD = "folder cloud";

    private static vSphereCloud cloud() {
        return new vSphereCloud(
                new VSphereConnectionConfig("vsHost", false, "credentialsId"),
                CLOUD,
                0,
                0,
                false,
                Collections.emptyList());
    }

    private static Folder folderWithCloud(JenkinsRule r) throws Exception {
        final Folder folder = r.jenkins.createProject(Folder.class, FOLDER);
        folder.getProperties().add(new FolderVSphereCloudProperty(Collections.singletonList(cloud())));
        return folder;
    }

    @Test
    void serverDropDownListsTheCloudOfAFolderWithASpaceInItsName(JenkinsRule r) throws Exception {
        final Folder folder = folderWithCloud(r);
        final FreeStyleProject project = folder.createProject(FreeStyleProject.class, "job");

        final String items = r.createWebClient()
                .goTo(
                        project.getUrl() + "descriptorByName/" + VSphereBuildStepContainer.class.getName()
                                + "/fillServerNameItems",
                        "application/json")
                .getWebResponse()
                .getContentAsString();

        assertThat(project.getUrl(), containsString("My%20Folder"));
        assertThat(items, containsString(CLOUD));
    }

    @Test
    void jobUsingTheCloudOfAFolderWithASpaceInItsNameCanBeSaved(JenkinsRule r) throws Exception {
        final Folder folder = folderWithCloud(r);
        final FreeStyleProject project = folder.createProject(FreeStyleProject.class, "job");
        // Outside of a web request the clouds of folders are not looked at, so the step is made with a twin
        // cloud of Jenkins: the step only keeps the hash of the cloud's description and host.
        final vSphereCloud twin = cloud();
        r.jenkins.clouds.add(twin);
        project.getBuildersList().add(new VSphereBuildStepContainer(new Delete("vm", false), CLOUD));
        r.jenkins.clouds.remove(twin);

        r.configRoundtrip(project);

        assertThat(project.getBuildersList().size(), is(1));
        assertThat(project.getBuildersList().get(0), instanceOf(VSphereBuildStepContainer.class));
        final VSphereBuildStepContainer step =
                (VSphereBuildStepContainer) project.getBuildersList().get(0);
        assertThat(step.getServerName(), is(CLOUD));
    }
}
