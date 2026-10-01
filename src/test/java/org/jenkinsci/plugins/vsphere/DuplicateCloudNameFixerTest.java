package org.jenkinsci.plugins.vsphere;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.empty;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.is;

import hudson.slaves.Cloud;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.stream.Collectors;
import org.jenkinsci.plugins.vSphereCloud;
import org.junit.jupiter.api.Test;
import org.jvnet.hudson.test.JenkinsRule;
import org.jvnet.hudson.test.junit.jupiter.WithJenkins;

/**
 * Covers the unique, read-only Jenkins-level cloud {@code name}: how it is derived from the
 * description, how the list of clouds displays a cloud, and that the startup sanity check renames
 * later duplicates (e.g. from a hand-edited {@code config.xml}) while keeping the first one intact.
 */
@WithJenkins
class DuplicateCloudNameFixerTest {

    private static vSphereCloud newCloud(String description) {
        return new vSphereCloud(
                new VSphereConnectionConfig("vcenter.example.com", "creds", null), description, 0, 0, false, null);
    }

    @Test
    void derivedNameIsUrlSafeAndUnique() {
        assertThat(
                vSphereCloud.deriveCloudName("New vcenter.domain.com", Collections.emptySet()),
                is("New-vcenter.domain.com"));
        assertThat(vSphereCloud.deriveCloudName("  /weird//  ", Collections.emptySet()), is("weird"));
        assertThat(vSphereCloud.deriveCloudName("", Collections.emptySet()), is("vSphereCloud"));
        assertThat(vSphereCloud.deriveCloudName(null, Collections.emptySet()), is("vSphereCloud"));
        assertThat(vSphereCloud.deriveCloudName("prod", new HashSet<>(List.of("prod", "prod-2"))), is("prod-3"));
    }

    @Test
    void displayNameShowsTypeAndDescription() {
        assertThat(newCloud("My vCenter").getDisplayName(), is("vSphere Cloud: My vCenter"));
    }

    @Test
    void newCloudsGetDistinctNamesAndSetNameOverridesIt(JenkinsRule r) {
        vSphereCloud first = newCloud("prod");
        r.jenkins.clouds.add(first);
        vSphereCloud second = newCloud("prod");
        assertThat(first.name, is("prod"));
        assertThat(second.name, is("prod-2"));

        second.setName("  custom ");
        assertThat(second.name, is("custom"));
        second.setName("");
        assertThat(second.name, is("custom"));
    }

    @Test
    void duplicatesAreRenamedAtStartupAndFirstOneKeepsItsName(JenkinsRule r) throws Exception {
        // Simulate legacy / hand-edited config.xml: every cloud carries the old fixed name.
        vSphereCloud a = newCloud("First vCenter");
        vSphereCloud b = newCloud("Second vCenter");
        vSphereCloud c = newCloud("Second vCenter");
        for (vSphereCloud cloud : List.of(a, b, c)) {
            cloud.name = "vSphereCloud";
            r.jenkins.clouds.add(cloud);
        }

        List<String> renames = DuplicateCloudNameFixer.fix(r.jenkins);

        assertThat(renames, hasSize(2));
        assertThat(a.name, is("vSphereCloud"));
        assertThat(b.name, is("Second-vCenter"));
        assertThat(c.name, is("Second-vCenter-2"));
        assertThat(
                r.jenkins.clouds.stream().map(x -> x.name).collect(Collectors.toList()),
                contains("vSphereCloud", "Second-vCenter", "Second-vCenter-2"));
        for (Cloud cloud : r.jenkins.clouds) {
            assertThat(r.jenkins.getCloud(cloud.name), is(cloud));
        }
        assertThat(
                r.jenkins
                        .getExtensionList(DuplicateCloudNameMonitor.class)
                        .get(0)
                        .getMessages(),
                hasSize(2));
    }

    @Test
    void nothingHappensWhenNamesAreAlreadyUnique(JenkinsRule r) throws Exception {
        r.jenkins.clouds.add(newCloud("one"));
        r.jenkins.clouds.add(newCloud("two"));
        assertThat(DuplicateCloudNameFixer.fix(r.jenkins), is(empty()));
    }
}
