package org.jenkinsci.plugins.vsphere;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.not;

import com.cloudbees.hudson.plugins.folder.Folder;
import hudson.model.FreeStyleBuild;
import hudson.model.FreeStyleProject;
import hudson.model.Node.Mode;
import hudson.slaves.JNLPLauncher;
import hudson.slaves.RetentionStrategy;
import hudson.util.FormValidation;
import java.net.URL;
import java.util.Collections;
import jenkins.model.Jenkins;
import org.htmlunit.HttpMethod;
import org.htmlunit.WebRequest;
import org.jenkinsci.plugins.folder.FolderVSphereCloudProperty;
import org.jenkinsci.plugins.vSphereCloud;
import org.jenkinsci.plugins.vSphereCloudSlave;
import org.junit.jupiter.api.Test;
import org.jvnet.hudson.test.JenkinsRule;
import org.jvnet.hudson.test.MockAuthorizationStrategy;
import org.jvnet.hudson.test.junit.jupiter.WithJenkins;

/** The "Reset usage counts" action of a cloud. */
@WithJenkins
class ResetUsagesTest {

    private static vSphereCloud cloud(JenkinsRule r, String description) {
        final vSphereCloud cloud = new vSphereCloud(
                new VSphereConnectionConfig("vsHost", false, "credentialsId"),
                description,
                100,
                0,
                false,
                Collections.emptyList());
        r.jenkins.clouds.add(cloud);
        return cloud;
    }

    /** An agent of the cloud with that description, that has done {@code uses} of its builds. */
    private static void agentWithUsage(JenkinsRule r, String description, String name, int uses) throws Exception {
        r.jenkins.addNode(new vSphereCloudSlave(
                name,
                "",
                "/home/jenkins",
                "1",
                Mode.NORMAL,
                "",
                new JNLPLauncher(),
                RetentionStrategy.NOOP,
                Collections.emptyList(),
                description,
                "vm-" + name,
                false,
                false,
                "",
                "60",
                "Nothing",
                "10"));
        final FreeStyleProject project = r.createFreeStyleProject();
        for (int i = 0; i < uses; i++) {
            final FreeStyleBuild build = r.buildAndAssertSuccess(project);
            AgentUsageRegistry.get().acquire(name, 10, build, () -> true);
        }
    }

    private static String resetUrl(String cloudId) {
        return "descriptorByName/org.jenkinsci.plugins.vSphereCloud/resetUsages?name=" + cloudId;
    }

    private static vSphereCloud.DescriptorImpl descriptor() {
        return Jenkins.get().getDescriptorByType(vSphereCloud.DescriptorImpl.class);
    }

    @Test
    void resettingTheUsageOfACloudResetsItsAgentsOnly(JenkinsRule r) throws Exception {
        final vSphereCloud first = cloud(r, "first cloud");
        cloud(r, "second cloud");
        agentWithUsage(r, "first cloud", "a", 2);
        agentWithUsage(r, "first cloud", "b", 1);
        agentWithUsage(r, "second cloud", "c", 3);

        final FormValidation result = descriptor().doResetUsages(first.name);

        assertThat(result.kind, is(FormValidation.Kind.OK));
        assertThat(result.getMessage(), containsString("2 agent(s)"));
        assertThat(AgentUsageRegistry.get().count("a"), is(0));
        assertThat(AgentUsageRegistry.get().count("b"), is(0));
        assertThat(AgentUsageRegistry.get().count("c"), is(3));
    }

    @Test
    void theButtonIsOnlyOnTheConfigurationOfCloudsOfJenkins(JenkinsRule r) throws Exception {
        final vSphereCloud global = cloud(r, "global cloud");
        final Folder folder = r.jenkins.createProject(Folder.class, "folder");
        final vSphereCloud inFolder = new vSphereCloud(
                new VSphereConnectionConfig("vsHost", false, "credentialsId"),
                "folder cloud",
                100,
                0,
                false,
                Collections.emptyList());
        folder.getProperties().add(new FolderVSphereCloudProperty(Collections.singletonList(inFolder)));
        // Cloud IDs are only kept apart among the clouds of Jenkins, so a cloud of Jenkins can get the ID of this one.
        final vSphereCloud sameId = cloud(r, "folder cloud");
        assertThat(sameId.name, is(inFolder.name));
        final JenkinsRule.WebClient webClient = r.createWebClient();

        final String globalPage = webClient
                .goTo("manage/cloud/" + global.name + "/configure")
                .getWebResponse()
                .getContentAsString();
        final String folderPage =
                webClient.goTo("job/folder/configure").getWebResponse().getContentAsString();

        assertThat(globalPage, containsString("Reset usage counts"));
        // The action looks for the cloud among those of Jenkins, so on a cloud of a folder it could only fail.
        assertThat(folderPage, containsString("folder cloud"));
        assertThat(folderPage, not(containsString("Reset usage counts")));
    }

    @Test
    void anUnknownCloudIdIsAnError(JenkinsRule r) throws Exception {
        assertThat(descriptor().doResetUsages("no-such-cloud").kind, is(FormValidation.Kind.ERROR));
    }

    @Test
    void aGetRequestIsRejected(JenkinsRule r) throws Exception {
        final vSphereCloud cloud = cloud(r, "first cloud");

        // A POST-only action is simply not there for a GET.
        r.createWebClient().assertFails(resetUrl(cloud.name), 404);
    }

    @Test
    void onlyAnAdministratorCanReset(JenkinsRule r) throws Exception {
        final vSphereCloud cloud = cloud(r, "first cloud");
        agentWithUsage(r, "first cloud", "a", 2);
        r.jenkins.setSecurityRealm(r.createDummySecurityRealm());
        r.jenkins.setAuthorizationStrategy(new MockAuthorizationStrategy()
                .grant(Jenkins.READ)
                .everywhere()
                .toEveryone()
                .grant(Jenkins.ADMINISTER)
                .everywhere()
                .to("admin"));

        final JenkinsRule.WebClient user = r.createWebClient().withBasicCredentials("user");
        user.setThrowExceptionOnFailingStatusCode(false);
        final WebRequest asUser = new WebRequest(new URL(r.getURL(), resetUrl(cloud.name)), HttpMethod.POST);
        assertThat(user.getPage(user.addCrumb(asUser)).getWebResponse().getStatusCode(), is(403));
        assertThat(AgentUsageRegistry.get().count("a"), is(2));

        final JenkinsRule.WebClient admin = r.createWebClient().withBasicCredentials("admin");
        final WebRequest asAdmin = new WebRequest(new URL(r.getURL(), resetUrl(cloud.name)), HttpMethod.POST);
        assertThat(admin.getPage(admin.addCrumb(asAdmin)).getWebResponse().getStatusCode(), is(200));
        assertThat(AgentUsageRegistry.get().count("a"), is(0));
    }
}
