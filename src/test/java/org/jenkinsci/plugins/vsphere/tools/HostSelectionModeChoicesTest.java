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
package org.jenkinsci.plugins.vsphere.tools;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.is;

import hudson.util.ListBoxModel;
import java.util.List;
import java.util.stream.Collectors;
import org.htmlunit.html.HtmlPage;
import org.htmlunit.html.HtmlSelect;
import org.jenkinsci.plugins.vsphere.EsxiSshBackendConfig;
import org.jenkinsci.plugins.vsphere.VSphereConnectionConfig;
import org.jenkinsci.plugins.vsphere.builders.Clone;
import org.jenkinsci.plugins.vsphere.builders.Deploy;
import org.jenkinsci.plugins.vsphere.vSphereCloud;
import org.junit.jupiter.api.Test;
import org.jvnet.hudson.test.JenkinsRule;
import org.jvnet.hudson.test.junit.jupiter.WithJenkins;

/** DRS is offered for a vCenter, and the fewest running VMs for standalone ESXi hosts: each only where it is of use. */
@WithJenkins
class HostSelectionModeChoicesTest {

    private static List<String> values(ListBoxModel items) {
        return items.stream().map(o -> o.value).collect(Collectors.toList());
    }

    @Test
    void eachTypeOfConnectionIsOfferedTheModesThatItHasUseFor() {
        assertThat(
                values(VSphereHostSelection.modeItems("none", true, VSphereConnectionConfig.BackendType.VCENTER)),
                contains("", "NONE", "LEAST_LOADED", "DRS_RECOMMENDED"));
        assertThat(
                values(VSphereHostSelection.modeItems("none", true, VSphereConnectionConfig.BackendType.ESXI_SSH)),
                contains("", "NONE", "LEAST_LOADED", "FEWEST_RUNNING_VMS"));
        // the cloud itself has no "explicitly none", being what that overrides; and not knowing, everything is there
        assertThat(
                values(VSphereHostSelection.modeItems("none", false, null)),
                contains("", "LEAST_LOADED", "DRS_RECOMMENDED", "FEWEST_RUNNING_VMS"));
    }

    @Test
    void theStepsOfACloudAreOfferedWhatItsTypeOfConnectionHasUseFor(JenkinsRule r) throws Exception {
        final VSphereConnectionConfig esxiConnection = new VSphereConnectionConfig("127.0.0.1", null, null);
        esxiConnection.setBackend(new EsxiSshBackendConfig(null));
        r.jenkins.clouds.add(new vSphereCloud(esxiConnection, "esxi-cloud", 0, 0, false, null));
        r.jenkins.clouds.add(new vSphereCloud(
                new VSphereConnectionConfig("https://vcenter.example.com", "creds", null),
                "vcenter-cloud",
                0,
                0,
                false,
                null));

        final Clone.CloneDescriptor clone = r.jenkins.getDescriptorByType(Clone.CloneDescriptor.class);
        final Deploy.DeployDescriptor deploy = r.jenkins.getDescriptorByType(Deploy.DeployDescriptor.class);

        assertThat(
                values(clone.doFillHostSelectionModeItems(null, "esxi-cloud")),
                contains("", "NONE", "LEAST_LOADED", "FEWEST_RUNNING_VMS"));
        assertThat(
                values(deploy.doFillHostSelectionModeItems(null, "esxi-cloud")),
                contains("", "NONE", "LEAST_LOADED", "FEWEST_RUNNING_VMS"));
        assertThat(
                values(clone.doFillHostSelectionModeItems(null, "vcenter-cloud")),
                contains("", "NONE", "LEAST_LOADED", "DRS_RECOMMENDED"));
        // a cloud that is not chosen yet, or not there: all of them
        assertThat(values(clone.doFillHostSelectionModeItems(null, "")).size(), is(5));
        assertThat(values(clone.doFillHostSelectionModeItems(null, "nope")).size(), is(5));
    }

    /** The values of the options of the first mode choice of the cloud's form that are not hidden. */
    private List<String> shownOnTheFormOf(JenkinsRule r, vSphereCloud cloud) throws Exception {
        try (JenkinsRule.WebClient wc = r.createWebClient()) {
            final HtmlPage page = wc.goTo(cloud.getUrl() + "configure");
            // the options are put in by the page, and hidden when it sees which type of connection is chosen
            wc.waitForBackgroundJavaScript(3000);
            Thread.sleep(1000);
            wc.waitForBackgroundJavaScript(3000);
            final HtmlSelect select = (HtmlSelect) page.getElementsByName("_.hostSelectionMode").stream()
                    .filter(e -> e instanceof HtmlSelect)
                    .findFirst()
                    .get();
            return select.getOptions().stream()
                    .filter(o -> !o.hasAttribute("hidden") && !o.isDisabled())
                    .map(o -> o.getValueAttribute())
                    .collect(Collectors.toList());
        }
    }

    @Test
    void theFormOfAnEsxiCloudHidesDrsAndTheFormOfAVcenterHidesTheFewestRunningVms(JenkinsRule r) throws Exception {
        final VSphereConnectionConfig esxiConnection = new VSphereConnectionConfig("127.0.0.1", null, null);
        esxiConnection.setBackend(new EsxiSshBackendConfig(null));
        final vSphereCloud esxi = new vSphereCloud(esxiConnection, "esxi-cloud", 0, 0, false, null);
        final vSphereCloud vcenter = new vSphereCloud(
                new VSphereConnectionConfig("https://vcenter.example.com", "creds", null),
                "vcenter-cloud",
                0,
                0,
                false,
                null);
        r.jenkins.clouds.add(esxi);
        r.jenkins.clouds.add(vcenter);

        assertThat(shownOnTheFormOf(r, esxi), contains("", "LEAST_LOADED", "FEWEST_RUNNING_VMS"));
        assertThat(shownOnTheFormOf(r, vcenter), contains("", "LEAST_LOADED", "DRS_RECOMMENDED"));
    }

    @Test
    void aModeThatIsChosenAlreadyIsNotHiddenSoThatTheFormDoesNotChangeIt(JenkinsRule r) throws Exception {
        final VSphereConnectionConfig esxiConnection = new VSphereConnectionConfig("127.0.0.1", null, null);
        esxiConnection.setBackend(new EsxiSshBackendConfig(null));
        final vSphereCloud esxi = new vSphereCloud(esxiConnection, "esxi-cloud", 0, 0, false, null);
        esxi.setHostSelectionMode("DRS_RECOMMENDED");
        r.jenkins.clouds.add(esxi);

        assertThat(shownOnTheFormOf(r, esxi), contains("", "LEAST_LOADED", "DRS_RECOMMENDED", "FEWEST_RUNNING_VMS"));
    }
}
