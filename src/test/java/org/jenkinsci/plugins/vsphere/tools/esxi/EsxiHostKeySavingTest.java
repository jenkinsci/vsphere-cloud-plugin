/*   Copyright 2026, Jim Klimov
 *
 *   Licensed under the Apache License, Version 2.0 (the "License");
 *   you may not use this file except in compliance with the License.
 *   You may obtain a copy of the License at
 *
 *       http://www.apache.org/licenses/LICENSE-2.0
 *
 *   Unless required by applicable law or agreed to in writing, software
 *   distributed under the License is distributed on an "AS IS" BASIS,
 *   WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *   See the License for the specific language governing permissions and
 *   limitations under the License.
 */
package org.jenkinsci.plugins.vsphere.tools.esxi;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.not;

import com.cloudbees.hudson.plugins.folder.Folder;
import com.cloudbees.plugins.credentials.CredentialsScope;
import com.cloudbees.plugins.credentials.SystemCredentialsProvider;
import com.cloudbees.plugins.credentials.impl.UsernamePasswordCredentialsImpl;
import java.util.List;
import org.jenkinsci.plugins.folder.FolderVSphereCloudProperty;
import org.jenkinsci.plugins.vsphere.EsxiSshBackendConfig;
import org.jenkinsci.plugins.vsphere.VSphereConnectionConfig;
import org.jenkinsci.plugins.vsphere.vSphereCloud;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.jvnet.hudson.test.JenkinsRule;
import org.jvnet.hudson.test.junit.jupiter.WithJenkins;

/**
 * The host key that "trust the first one seen" remembers has to be saved to stay remembered: with the folder, for a
 * cloud that is defined in a folder, and with Jenkins for any other.
 */
@WithJenkins
class EsxiHostKeySavingTest {

    private FakeEsxiHost host;
    private FakeEsxiSshServer server;

    /** Starts the host and the credentials for it; Jenkins has to be there already. */
    private void start() throws Exception {
        host = new FakeEsxiHost();
        server = new FakeEsxiSshServer(host, "secret", null, false);
        SystemCredentialsProvider.getInstance()
                .getCredentials()
                .add(new UsernamePasswordCredentialsImpl(
                        CredentialsScope.GLOBAL, "esxi-password", "an ESXi host", "root", "secret"));
    }

    @AfterEach
    void tearDown() throws Exception {
        if (server != null) {
            server.close();
        }
    }

    /** What is saved of Jenkins itself, in its config.xml. */
    private static String jenkinsConfig(JenkinsRule r) throws java.io.IOException {
        return new hudson.XmlFile(new java.io.File(r.jenkins.getRootDir(), "config.xml")).asString();
    }

    private vSphereCloud firstUseCloud(String description) {
        EsxiSshBackendConfig esxi = new EsxiSshBackendConfig("esxi-password");
        esxi.setPort(server.port());
        esxi.setHostKeyPolicy(EsxiHostKeyPolicy.TRUST_FIRST_USE);
        esxi.setConnectTimeoutSeconds(10);
        esxi.setCommandTimeoutSeconds(10);
        VSphereConnectionConfig config = new VSphereConnectionConfig("127.0.0.1");
        config.setBackend(esxi);
        return new vSphereCloud(config, description, 0, 0, false, List.of());
    }

    @Test
    void forACloudOfJenkinsJenkinsIsSaved(JenkinsRule r) throws Exception {
        start();
        vSphereCloud cloud = firstUseCloud("Global ESXi");
        r.jenkins.clouds.add(cloud);
        assertThat(jenkinsConfig(r), not(containsString(server.hostKeySha256())));

        cloud.vSphereInstance().disconnect();

        assertThat(jenkinsConfig(r), containsString(server.hostKeySha256()));
    }

    @Test
    void forACloudInAFolderTheFolderIsSaved(JenkinsRule r) throws Exception {
        start();
        Folder folder = r.jenkins.createProject(Folder.class, "esxi-hosts");
        vSphereCloud cloud = firstUseCloud("ESXi of a folder");
        folder.addProperty(new FolderVSphereCloudProperty(List.of(cloud)));
        assertThat(folder.getConfigFile().asString(), not(containsString(server.hostKeySha256())));

        cloud.vSphereInstance().disconnect();

        assertThat(folder.getConfigFile().asString(), containsString(server.hostKeySha256()));
        assertThat(
                folder.getProperties()
                        .get(FolderVSphereCloudProperty.class)
                        .getClouds()
                        .get(0)
                        .getVsConnectionConfig()
                        .getEsxiSsh()
                        .getHostKeyFingerprint(),
                is(server.hostKeySha256()));
        // and not Jenkins, which has nothing of it
        assertThat(jenkinsConfig(r), not(containsString(server.hostKeySha256())));
    }

    @Test
    void theHostKeyIsSavedOnlyTheFirstTime(JenkinsRule r) throws Exception {
        start();
        vSphereCloud cloud = firstUseCloud("Global ESXi");
        r.jenkins.clouds.add(cloud);
        cloud.vSphereInstance().disconnect();
        String saved = jenkinsConfig(r);

        cloud.vSphereInstance().disconnect();

        assertThat(jenkinsConfig(r), is(saved));
    }
}
