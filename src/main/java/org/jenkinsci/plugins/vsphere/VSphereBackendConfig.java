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

import hudson.DescriptorExtensionList;
import hudson.ExtensionPoint;
import hudson.model.AbstractDescribableImpl;
import hudson.model.Descriptor;
import jenkins.model.Jenkins;
import org.jenkinsci.plugins.vsphere.tools.VSphere;
import org.jenkinsci.plugins.vsphere.tools.VSphereException;

/**
 * What is specific to one way of connecting to a hypervisor, as part of a {@link VSphereConnectionConfig} (which
 * has what they share, the host): {@link VCenterBackendConfig} for vCenter through the vSphere API, and
 * {@link EsxiSshBackendConfig} for a standalone ESXi host over SSH. A configuration has exactly one, which is
 * chosen in the form, and is the {@code backend} of the connection configuration in Configuration as Code.
 */
public abstract class VSphereBackendConfig extends AbstractDescribableImpl<VSphereBackendConfig>
        implements ExtensionPoint {

    /** Connects to the host of the connection configuration that this is the backend of. */
    public abstract VSphere connect(VSphereConnectionConfig config) throws VSphereException;

    public abstract VSphereConnectionConfig.BackendType getBackendType();

    /** All the ways of connecting that there are. */
    public static DescriptorExtensionList<VSphereBackendConfig, Descriptor<VSphereBackendConfig>> all() {
        return Jenkins.get().getDescriptorList(VSphereBackendConfig.class);
    }
}
