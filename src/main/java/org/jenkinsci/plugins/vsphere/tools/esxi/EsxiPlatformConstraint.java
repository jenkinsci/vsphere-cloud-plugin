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

/**
 * Marks an exception as being about what a standalone ESXi host is, not about this plugin: the thing asked for
 * does not exist there (folders, clusters, distributed switches, customization specifications, choosing among
 * hosts, migration), as opposed to something that is merely not done yet. A caller that wants to tell the two
 * apart asks {@code e instanceof EsxiPlatformConstraint}.
 *
 * <p>Two exceptions carry it, as the operations either declare {@link org.jenkinsci.plugins.vsphere.tools.VSphereException}
 * or, being methods of the objects that stand in for the vSphere API, can only throw unchecked ones:
 * {@link EsxiConstraintException} and {@link EsxiConstraintUnsupportedOperationException}.
 */
public interface EsxiPlatformConstraint {}
