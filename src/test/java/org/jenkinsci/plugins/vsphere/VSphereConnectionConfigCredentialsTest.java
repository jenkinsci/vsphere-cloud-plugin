package org.jenkinsci.plugins.vsphere;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.notNullValue;

import com.cloudbees.plugins.credentials.CredentialsScope;
import com.cloudbees.plugins.credentials.SystemCredentialsProvider;
import com.cloudbees.plugins.credentials.impl.UsernamePasswordCredentialsImpl;
import hudson.util.ListBoxModel;
import java.util.List;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;
import org.jvnet.hudson.test.Issue;
import org.jvnet.hudson.test.JenkinsRule;
import org.jvnet.hudson.test.junit.jupiter.WithJenkins;

@WithJenkins
@Issue("JENKINS-35534")
class VSphereConnectionConfigCredentialsTest {

    private static VSphereConnectionConfig.DescriptorImpl descriptor(JenkinsRule r) {
        return r.jenkins.getDescriptorByType(VSphereConnectionConfig.DescriptorImpl.class);
    }

    private static void addCredentials(String id) throws Exception {
        SystemCredentialsProvider.getInstance()
                .getCredentials()
                .add(new UsernamePasswordCredentialsImpl(CredentialsScope.GLOBAL, id, "vCenter " + id, "user", "pw"));
    }

    private static List<String> values(ListBoxModel items) {
        return items.stream().map(o -> o.value).collect(Collectors.toList());
    }

    @Test
    void dropdownListsTheUsableCredentialsAfterAnEmptyChoice(JenkinsRule r) throws Exception {
        addCredentials("vc-1");

        ListBoxModel items = descriptor(r).doFillCredentialsIdItems(null, "vcenter.example.com", "");

        assertThat(values(items), contains("", "vc-1"));
    }

    @Test
    void dropdownStillOffersTheCurrentValueWhenItIsNoLongerAvailable(JenkinsRule r) throws Exception {
        addCredentials("vc-1");

        ListBoxModel items = descriptor(r).doFillCredentialsIdItems(null, "vcenter.example.com", "deleted-id");

        // so that re-saving the configuration does not silently drop the setting
        assertThat(values(items), hasItem("deleted-id"));
        assertThat(values(items), hasItem("vc-1"));
    }

    @Test
    void connectionLooksTheConfiguredCredentialsUp(JenkinsRule r) throws Exception {
        addCredentials("vc-1");

        assertThat(
                VSphereConnectionConfig.DescriptorImpl.lookupCredentials("vc-1", "vcenter.example.com"),
                notNullValue());
        assertThat(
                VSphereConnectionConfig.DescriptorImpl.lookupCredentials("nope", "vcenter.example.com") == null,
                is(true));
    }
}
