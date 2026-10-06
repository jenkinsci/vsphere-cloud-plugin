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

import hudson.init.InitMilestone;
import hudson.init.Initializer;
import hudson.model.Items;
import jenkins.model.Jenkins;

/**
 * The classes that used to be directly in the {@code org.jenkinsci.plugins} package are in {@code
 * org.jenkinsci.plugins.vsphere} now, and what Jenkins has stored under their old names (the clouds in {@code
 * config.xml}, the agents in {@code nodes/NAME/config.xml}, those of a folder in its {@code config.xml}) is still
 * read: the old names are aliases of the new classes. What is saved from then on has the new names.
 */
public final class LegacyClassNames {

    /** The classes that are stored, which had their names in {@code org.jenkinsci.plugins}. */
    static final Class<?>[] MOVED = {
        vSphereCloud.class,
        vSphereCloudSlave.class,
        vSphereCloudProvisionedSlave.class,
        vSphereCloudLauncher.class,
        vSphereCloudSlaveTemplate.class
    };

    private LegacyClassNames() {}

    /** The name that the class had before it was moved. */
    static String oldNameOf(Class<?> moved) {
        return "org.jenkinsci.plugins." + moved.getSimpleName();
    }

    @Initializer(before = InitMilestone.PLUGINS_STARTED)
    public static void addAliases() {
        for (Class<?> moved : MOVED) {
            // the configuration of Jenkins and its agents, and that of the items (those of folders)
            Jenkins.XSTREAM2.addCompatibilityAlias(oldNameOf(moved), moved);
            Items.XSTREAM2.addCompatibilityAlias(oldNameOf(moved), moved);
        }
    }
}
