/*   Copyright 2013, MANDIANT, Eric Lordahl
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

import static org.jenkinsci.plugins.vsphere.tools.PermissionUtils.throwUnlessUserHasPermissionToConfigureJob;

import com.cloudbees.hudson.plugins.folder.AbstractFolderProperty;
import com.cloudbees.hudson.plugins.folder.AbstractFolderPropertyDescriptor;
import com.cloudbees.hudson.plugins.folder.Folder;
import hudson.*;
import hudson.init.InitMilestone;
import hudson.init.Initializer;
import hudson.model.*;
import hudson.slaves.Cloud;
import hudson.tasks.BuildStepDescriptor;
import hudson.tasks.Builder;
import hudson.util.DescribableList;
import hudson.util.ListBoxModel;
import jenkins.model.Jenkins;
import jenkins.tasks.SimpleBuildStep;
import org.jenkinsci.plugins.vSphereCloud;
import org.jenkinsci.plugins.folder.FolderVSphereCloudProperty;
import org.jenkinsci.plugins.vsphere.VSphereBuildStep.VSphereBuildStepDescriptor;
import org.jenkinsci.plugins.vsphere.builders.Messages;
import org.jenkinsci.plugins.vsphere.tools.VSphere;
import org.jenkinsci.plugins.vsphere.tools.VSphereException;
import org.jenkinsci.plugins.vsphere.tools.VSphereLogger;
import org.kohsuke.stapler.AncestorInPath;
import org.kohsuke.stapler.DataBoundConstructor;
import org.kohsuke.stapler.Stapler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import edu.umd.cs.findbugs.annotations.NonNull;
import java.io.IOException;
import java.io.PrintStream;
import java.util.Collection;

public class VSphereBuildStepContainer extends Builder implements SimpleBuildStep {

    public static final String SELECTABLE_SERVER_NAME = "${VSPHERE_CLOUD_NAME}";

    private final VSphereBuildStep buildStep;
    private final String serverName;
    private final Integer serverHash;

    @DataBoundConstructor
    public VSphereBuildStepContainer(final VSphereBuildStep buildStep, final String serverName) throws VSphereException {
        this.buildStep = buildStep;
        this.serverName = serverName;
        if (!(SELECTABLE_SERVER_NAME.equals(serverName))) {
            this.serverHash = VSphereBuildStep.VSphereBuildStepDescriptor.getVSphereCloudByName(serverName, null).getHash();
        } else {
            this.serverHash = null;
        }
    }

    public String getServerName() {
        return serverName;
    }

    public VSphereBuildStep getBuildStep() {
        return buildStep;
    }

    @Override
    public void perform(@NonNull Run<?, ?> run, @NonNull FilePath filePath, @NonNull Launcher launcher, @NonNull TaskListener listener) throws InterruptedException, IOException {
        VSphere vsphere = null;
        try {
            String expandedServerName = serverName;
            if (run instanceof AbstractBuild) {
                EnvVars env = (run.getEnvironment(listener));
                env.overrideAll(((AbstractBuild) run).getBuildVariables()); // Add in matrix axes..
                expandedServerName = env.expand(serverName);
            }
            startLogs(listener.getLogger(), expandedServerName);
            //Need to ensure this server is same as one that was previously saved.
            //TODO - also need to improve logging here.

            // select by hash if we have one
            if (serverHash != null) {
                vsphere = VSphereBuildStep.VSphereBuildStepDescriptor.getVSphereCloudByHash(serverHash, run.getEnvironment(listener).get("JOB_NAME")).vSphereInstance();
            } else {
                vsphere = VSphereBuildStep.VSphereBuildStepDescriptor.getVSphereCloudByName(expandedServerName, run.getEnvironment(listener).get("JOB_NAME")).vSphereInstance();
            }

            buildStep.setVsphere(vsphere);
            if (run instanceof AbstractBuild) {
                buildStep.perform(((AbstractBuild) run), launcher, (BuildListener) listener);
            } else {
                buildStep.perform(run, filePath, launcher, listener);
            }

        } catch (Exception e) {
            throw new AbortException(e.getMessage());
        } finally {
            if (vsphere != null) {
                vsphere.disconnect();
            }
        }
    }

    private void startLogs(PrintStream logger, String serverName) {
        VSphereLogger.vsLogger(logger, "");
        VSphereLogger.vsLogger(logger,
                Messages.console_buildStepStart(buildStep.getDescriptor().getDisplayName()));
        VSphereLogger.vsLogger(logger,
                Messages.console_usingServerConfig(serverName));
    }

    @Extension
    public static final class VSphereBuildStepContainerDescriptor extends BuildStepDescriptor<Builder> {
        private static final Logger LOGGER = LoggerFactory.getLogger(VSphereBuildStepContainerDescriptor.class);

        @Initializer(before = InitMilestone.PLUGINS_STARTED)
        public static void addAliases() {
            Items.XSTREAM2.addCompatibilityAlias(
                    "org.jenkinsci.plugins.vsphere.builders.VSphereBuildStepContainer",
                    VSphereBuildStepContainer.class
            );
        }

        @Override
        public String getDisplayName() {
            return Messages.plugin_title_BuildStep();
        }

        public DescriptorExtensionList<VSphereBuildStep, VSphereBuildStepDescriptor> getBuildSteps() {
            return VSphereBuildStep.all();
        }

        @Override
        public boolean isApplicable(Class<? extends AbstractProject> jobType) {
            return true;
        }

        public ListBoxModel doFillServerNameItems(@AncestorInPath Item context) {
            throwUnlessUserHasPermissionToConfigureJob(context);

            boolean hasVsphereClouds = false;
            ListBoxModel select = new ListBoxModel();

            Folder prevFolder = null;

            try {
                String[] path = Stapler.getCurrentRequest().getRequestURI().split("/");
                for (String item : path) {

                    if (item.equals("job") || item.equals("jenkins"))
                        continue;

                    TopLevelItem topLevelItem = null;
                    if (prevFolder == null) {
                        topLevelItem = Jenkins.getInstance().getItem(item);
                    } else {
                        Collection<TopLevelItem> items = prevFolder.getItems();
                        for (TopLevelItem levelItem : items) {
                            if (levelItem.getName().endsWith(item)){
                                topLevelItem = levelItem;
                            }
                        }
                    }

                    if (topLevelItem != null && topLevelItem instanceof Folder) {
                        prevFolder = (Folder)topLevelItem;
                        hasVsphereClouds = extractCloudNames(hasVsphereClouds, select, prevFolder);
                    }
                }

                //adding try block to prevent page from not loading

                for (Cloud cloud : Jenkins.getInstance().clouds) {
                    if (cloud instanceof vSphereCloud) {
                        hasVsphereClouds = true;
                        select.add(((vSphereCloud) cloud).getVsDescription());
                    }
                }
                if (hasVsphereClouds) {
                    select.add(SELECTABLE_SERVER_NAME);
                }
            } catch (Exception e) {

                LOGGER.error(e.toString(), e);
            }

            return select;
        }

        private boolean extractCloudNames(boolean hasVsphereClouds, ListBoxModel select, Folder folder) {
            DescribableList<AbstractFolderProperty<?>, AbstractFolderPropertyDescriptor> properties = folder.getProperties();
            for (AbstractFolderProperty<?> property : properties) {
                if (property instanceof FolderVSphereCloudProperty) {

                    FolderVSphereCloudProperty vSphereCloudProperty = (FolderVSphereCloudProperty) property;
                    for (org.jenkinsci.plugins.vSphereCloud vSphereCloud : vSphereCloudProperty.getClouds()) {
                        select.add(vSphereCloud.getVsDescription());
                    }
                    hasVsphereClouds = true;
                }
            }
            return hasVsphereClouds;
        }
    }
}
