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

import edu.umd.cs.findbugs.annotations.CheckForNull;

/** One line of {@code vim-cmd vmsvc/getallvms}: a VM registered on the host. */
public final class VmEntry {
    private final int id;
    private final String name;
    private final String vmxPath;
    private final String guestOs;
    private final String version;
    private final String annotation;

    VmEntry(int id, String name, String vmxPath, String guestOs, String version, String annotation) {
        this.id = id;
        this.name = name;
        this.vmxPath = vmxPath;
        this.guestOs = guestOs;
        this.version = version;
        this.annotation = annotation;
    }

    /** The same VM as the host that has the datastore under another name knows its files: {@code [name] path}. */
    VmEntry onDatastore(String datastoreName) {
        return new VmEntry(id, name, "[" + datastoreName + "] " + getVmxRelativePath(), guestOs, version, annotation);
    }

    /** The id the host knows the VM by, which is what the other {@code vim-cmd vmsvc/...} commands take. */
    public int getId() {
        return id;
    }

    public String getName() {
        return name;
    }

    /** The configuration file as {@code [datastore] folder/name.vmx}. */
    public String getVmxPath() {
        return vmxPath;
    }

    public @CheckForNull String getGuestOs() {
        return guestOs;
    }

    public @CheckForNull String getVersion() {
        return version;
    }

    public String getAnnotation() {
        return annotation;
    }

    /** The datastore part of {@link #getVmxPath()}. */
    public String getDatastore() {
        final int end = vmxPath.indexOf(']');
        return vmxPath.startsWith("[") && end > 0 ? vmxPath.substring(1, end) : "";
    }

    /** The path of the configuration file in the datastore, i.e. what follows {@code [datastore] }. */
    public String getVmxRelativePath() {
        final int end = vmxPath.indexOf(']');
        return end > 0 ? vmxPath.substring(end + 1).trim() : vmxPath;
    }

    /** Where the configuration file is in the file system of the host. */
    public String getVmxFileSystemPath() {
        return "/vmfs/volumes/" + getDatastore() + "/" + getVmxRelativePath();
    }

    @Override
    public String toString() {
        return "VM " + id + " \"" + name + "\" " + vmxPath;
    }
}
