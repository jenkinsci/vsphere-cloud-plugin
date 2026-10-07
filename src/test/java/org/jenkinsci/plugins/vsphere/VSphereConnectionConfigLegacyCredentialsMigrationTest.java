package org.jenkinsci.plugins.vsphere;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.notNullValue;
import static org.hamcrest.Matchers.nullValue;

import com.cloudbees.plugins.credentials.CredentialsProvider;
import com.cloudbees.plugins.credentials.common.StandardUsernamePasswordCredentials;
import hudson.util.Scrambler;
import jenkins.model.Jenkins;
import org.junit.jupiter.api.Test;
import org.jvnet.hudson.test.JenkinsRule;
import org.jvnet.hudson.test.junit.jupiter.WithJenkins;

/**
 * Covers the one-time migration of pre-credentials-plugin {@link vSphereCloud} configuration
 * (plain {@code vsHost}/{@code username}/{@code password} elements that may still be present in
 * an old server's config.xml, no longer reachable through the current jelly form) into a real
 * Jenkins {@link StandardUsernamePasswordCredentials}.
 */
@WithJenkins
class VSphereConnectionConfigLegacyCredentialsMigrationTest {

    @Test
    void noMigrationWhenUsernameIsBlank(JenkinsRule r) {
        assertThat(
                VSphereConnectionConfig.migrateLegacyCredentials("https://legacy.example.com", null, "secret"),
                nullValue());
        assertThat(
                VSphereConnectionConfig.migrateLegacyCredentials("https://legacy.example.com", "", "secret"),
                nullValue());
    }

    @Test
    void migratesLegacyUsernamePasswordIntoACredential(JenkinsRule r) {
        String credentialsId =
                VSphereConnectionConfig.migrateLegacyCredentials("https://legacy.example.com", "root", "hunter2");

        assertThat(credentialsId, notNullValue());

        StandardUsernamePasswordCredentials migrated = findCredentials(credentialsId);
        assertThat(migrated, notNullValue());
        assertThat(migrated.getUsername(), is("root"));
        assertThat(migrated.getPassword().getPlainText(), is("hunter2"));
    }

    @Test
    void migrationIsIdempotent(JenkinsRule r) {
        String firstId =
                VSphereConnectionConfig.migrateLegacyCredentials("https://idempotent.example.com", "alice", "s3cr3t");
        String secondId =
                VSphereConnectionConfig.migrateLegacyCredentials("https://idempotent.example.com", "alice", "s3cr3t");

        assertThat(secondId, is(firstId));

        long matching =
                CredentialsProvider.lookupCredentials(StandardUsernamePasswordCredentials.class, Jenkins.get()).stream()
                        .filter(c -> c.getId().equals(firstId))
                        .count();
        assertThat(matching, is(1L));
    }

    @Test
    void legacyConfigXmlMigratesOnLoad(JenkinsRule r) {
        String scrambledPassword = Scrambler.scramble("bob-secret");
        String xml = "<org.jenkinsci.plugins.vSphereCloud>\n"
                + "  <vsDescription>legacy-cloud</vsDescription>\n"
                + "  <maxOnlineSlaves>0</maxOnlineSlaves>\n"
                + "  <instanceCap>0</instanceCap>\n"
                + "  <useNoDelayProvisioner>false</useNoDelayProvisioner>\n"
                + "  <vsHost>https://xml-legacy.example.com</vsHost>\n"
                + "  <username>bob</username>\n"
                + "  <password>"
                + scrambledPassword + "</password>\n"
                + "</org.jenkinsci.plugins.vSphereCloud>";

        vSphereCloud cloud = (vSphereCloud) Jenkins.XSTREAM2.fromXML(xml);

        assertThat(cloud.getVsConnectionConfig(), notNullValue());
        assertThat(cloud.getVsConnectionConfig().getVCenter().getCredentialsId(), notNullValue());
        assertThat(cloud.getUsername(), is("bob"));
        assertThat(cloud.getPassword(), is("bob-secret"));
    }

    private static StandardUsernamePasswordCredentials findCredentials(String credentialsId) {
        return CredentialsProvider.lookupCredentials(StandardUsernamePasswordCredentials.class, Jenkins.get()).stream()
                .filter(c -> c.getId().equals(credentialsId))
                .findFirst()
                .orElse(null);
    }
}
