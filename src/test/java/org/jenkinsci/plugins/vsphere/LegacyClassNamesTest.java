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
package org.jenkinsci.plugins.vsphere;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.startsWith;

import hudson.model.Items;
import java.util.Map;
import jenkins.model.Jenkins;
import org.jenkinsci.plugins.vsphere.folder.FolderVSphereCloudProperty;
import org.jenkinsci.plugins.vsphere.workflow.vSphereStep;
import org.jenkinsci.plugins.workflow.steps.StepDescriptor;
import org.junit.jupiter.api.Test;
import org.jvnet.hudson.test.JenkinsRule;
import org.jvnet.hudson.test.junit.jupiter.WithJenkins;

/** What Jenkins stored under the names that the classes had before they were moved is still read. */
@WithJenkins
class LegacyClassNamesTest {

    @Test
    void theOldNamesOfTheStoredClassesAreAliasesInTheConfigurationOfJenkinsAndOfItems(JenkinsRule r) throws Exception {
        assertThat(LegacyClassNames.MOVED.size(), is(6));
        for (Map.Entry<String, Class<?>> moved : LegacyClassNames.MOVED.entrySet()) {
            assertThat(moved.getValue().getName(), startsWith("org.jenkinsci.plugins.vsphere."));
            assertThat(moved.getKey(), not(startsWith("org.jenkinsci.plugins.vsphere.")));
            assertThat(Jenkins.XSTREAM2.getMapper().realClass(moved.getKey()), is((Object) moved.getValue()));
            assertThat(Items.XSTREAM2.getMapper().realClass(moved.getKey()), is((Object) moved.getValue()));
        }
    }

    @Test
    void aFolderThatHasTheOldPropertyIsReadAsTheNewOne(JenkinsRule r) {
        final Object property = Items.XSTREAM2.fromXML(
                "<org.jenkinsci.plugins.folder.FolderVSphereCloudProperty><clouds/></org.jenkinsci.plugins.folder.FolderVSphereCloudProperty>");

        assertThat(property instanceof FolderVSphereCloudProperty, is(true));
        assertThat(
                Items.XSTREAM2.toXML(property),
                startsWith("<org.jenkinsci.plugins.vsphere.folder.FolderVSphereCloudProperty"));
    }

    @Test
    void theStepIsFoundByTheIdThatPipelineStoredInTheNodesOfOldBuilds(JenkinsRule r) {
        final String id = "org.jenkinsci.plugins.workflow.vSphereStep$DescriptorImpl";

        final StepDescriptor descriptor = StepDescriptor.byFunctionName("vSphere");

        assertThat(descriptor instanceof vSphereStep.DescriptorImpl, is(true));
        assertThat(descriptor.getId(), is(id));
        assertThat(Jenkins.get().getDescriptor(id), is((Object) descriptor));
        assertThat(StepDescriptor.all().stream().anyMatch(d -> id.equals(d.getId())), is(true));
        // and the address that the page of a job uses for its form checks leads to it too
        assertThat(Jenkins.get().getDescriptorByName(descriptor.getDescriptorUrl()), is((Object) descriptor));
    }

    @Test
    void whatIsSavedNowHasTheNewName(JenkinsRule r) {
        final String xml = Jenkins.XSTREAM2.toXML(new vSphereCloud(null, "old", 0, 0, false, null));

        assertThat(xml, startsWith("<org.jenkinsci.plugins.vsphere.vSphereCloud"));
    }
}
