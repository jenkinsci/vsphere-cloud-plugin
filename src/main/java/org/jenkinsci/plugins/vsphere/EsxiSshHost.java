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

import static org.jenkinsci.plugins.vsphere.tools.PermissionUtils.throwUnlessUserHasPermissionToConfigureCloud;

import com.cloudbees.hudson.plugins.folder.AbstractFolder;
import edu.umd.cs.findbugs.annotations.CheckForNull;
import hudson.Extension;
import hudson.Util;
import hudson.model.AbstractDescribableImpl;
import hudson.model.Descriptor;
import hudson.util.FormValidation;
import hudson.util.ListBoxModel;
import org.jenkinsci.Symbol;
import org.jenkinsci.plugins.vsphere.tools.esxi.EsxiHostKeyPolicy;
import org.jenkinsci.plugins.vsphere.tools.esxi.EsxiHostKeyStore;
import org.kohsuke.stapler.AncestorInPath;
import org.kohsuke.stapler.DataBoundConstructor;
import org.kohsuke.stapler.DataBoundSetter;
import org.kohsuke.stapler.QueryParameter;
import org.kohsuke.stapler.interceptor.RequirePOST;

/**
 * Another standalone ESXi host of a "poor man's cluster": the hosts of an {@link EsxiSshBackendConfig} (the first
 * one is the {@code vsHost} of the connection configuration, the others are these) are used together, and share
 * what the datastore they have in common holds. What is not set here is that of the first host: the port, the
 * credentials and how the host key is trusted. The fingerprint of the host key is always the host's own.
 */
public class EsxiSshHost extends AbstractDescribableImpl<EsxiSshHost> implements EsxiHostKeyStore {

    private final String host;
    private int port;
    private @CheckForNull String credentialsId;
    private @CheckForNull EsxiHostKeyPolicy hostKeyPolicy;
    private @CheckForNull String hostKeyFingerprint;

    /** Where the configuration of the cluster is, for saving what "trust the first host key seen" remembers. */
    private transient @CheckForNull EsxiSshBackendConfig owner;

    @DataBoundConstructor
    public EsxiSshHost(String host) {
        this.host = Util.fixEmptyAndTrim(host) == null ? "" : host.trim();
    }

    public String getHost() {
        return host;
    }

    /** The SSH port, or 0 for that of the first host. */
    public int getPort() {
        return port;
    }

    @DataBoundSetter
    public void setPort(int port) {
        this.port = Math.max(port, 0);
    }

    /** The credentials, or null for those of the first host. */
    public @CheckForNull String getCredentialsId() {
        return credentialsId;
    }

    @DataBoundSetter
    public void setCredentialsId(@CheckForNull String credentialsId) {
        this.credentialsId = Util.fixEmptyAndTrim(credentialsId);
    }

    /** How the host key is trusted, or null for the way it is for the first host. */
    public @CheckForNull EsxiHostKeyPolicy getHostKeyPolicy() {
        return hostKeyPolicy;
    }

    @DataBoundSetter
    public void setHostKeyPolicy(@CheckForNull EsxiHostKeyPolicy hostKeyPolicy) {
        this.hostKeyPolicy = hostKeyPolicy;
    }

    public synchronized @CheckForNull String getHostKeyFingerprint() {
        return hostKeyFingerprint;
    }

    @DataBoundSetter
    public synchronized void setHostKeyFingerprint(@CheckForNull String hostKeyFingerprint) {
        this.hostKeyFingerprint = Util.fixEmptyAndTrim(hostKeyFingerprint);
    }

    synchronized void setOwner(@CheckForNull EsxiSshBackendConfig owner) {
        this.owner = owner;
    }

    // -- the host key remembered by "trust the first one seen" --

    @Override
    public synchronized @CheckForNull String get(String host, int port) {
        return hostKeyFingerprint;
    }

    @Override
    public synchronized String rememberIfAbsent(String host, int port, String fingerprint) {
        if (hostKeyFingerprint == null) {
            hostKeyFingerprint = fingerprint;
            if (owner != null) {
                owner.saveRemembered(host);
            }
        }
        return hostKeyFingerprint;
    }

    @Override
    public String toString() {
        return host;
    }

    @Extension
    @Symbol("esxiHost")
    public static class DescriptorImpl extends Descriptor<EsxiSshHost> {

        @Override
        public String getDisplayName() {
            return "Another ESXi host";
        }

        @RequirePOST
        public FormValidation doCheckHost(
                @AncestorInPath AbstractFolder<?> containingFolderOrNull, @QueryParameter String value) {
            throwUnlessUserHasPermissionToConfigureCloud(containingFolderOrNull);
            return Util.fixEmptyAndTrim(value) == null
                    ? FormValidation.error("The host name or address is needed")
                    : FormValidation.ok();
        }

        @RequirePOST
        public FormValidation doCheckPort(
                @AncestorInPath AbstractFolder<?> containingFolderOrNull, @QueryParameter String value) {
            throwUnlessUserHasPermissionToConfigureCloud(containingFolderOrNull);
            return Util.fixEmptyAndTrim(value) == null || "0".equals(value.trim())
                    ? FormValidation.ok()
                    : FormValidation.validatePositiveInteger(value);
        }

        @RequirePOST
        public ListBoxModel doFillCredentialsIdItems(
                @AncestorInPath AbstractFolder<?> containingFolderOrNull,
                @QueryParameter String host,
                @QueryParameter String credentialsId) {
            throwUnlessUserHasPermissionToConfigureCloud(containingFolderOrNull);
            return EsxiSshBackendConfig.credentialsItems(containingFolderOrNull, host, credentialsId);
        }

        @RequirePOST
        public ListBoxModel doFillHostKeyPolicyItems(@AncestorInPath AbstractFolder<?> containingFolderOrNull) {
            throwUnlessUserHasPermissionToConfigureCloud(containingFolderOrNull);
            final ListBoxModel items = new ListBoxModel();
            items.add("The same as for the first host", "");
            items.addAll(EsxiSshBackendConfig.hostKeyPolicyItems());
            return items;
        }

        @RequirePOST
        public FormValidation doCheckHostKeyFingerprint(
                @AncestorInPath AbstractFolder<?> containingFolderOrNull,
                @QueryParameter String value,
                @QueryParameter String hostKeyPolicy) {
            throwUnlessUserHasPermissionToConfigureCloud(containingFolderOrNull);
            return EsxiSshBackendConfig.checkFingerprint(value, hostKeyPolicy);
        }

        /** Finds out which host key the host presents, without logging in. */
        @RequirePOST
        public FormValidation doQueryHostKey(
                @AncestorInPath AbstractFolder<?> containingFolderOrNull,
                @QueryParameter String host,
                @QueryParameter String port) {
            throwUnlessUserHasPermissionToConfigureCloud(containingFolderOrNull);
            return EsxiSshBackendConfig.queryHostKey(host, port, null);
        }

        /** Tests the connection with what is given here (the credentials have to be given, there is no first host). */
        @RequirePOST
        public FormValidation doTestConnection(
                @AncestorInPath AbstractFolder<?> containingFolderOrNull,
                @QueryParameter String host,
                @QueryParameter String credentialsId,
                @QueryParameter String port,
                @QueryParameter String hostKeyPolicy,
                @QueryParameter String hostKeyFingerprint) {
            throwUnlessUserHasPermissionToConfigureCloud(containingFolderOrNull);
            if (Util.fixEmptyAndTrim(credentialsId) == null) {
                return FormValidation.error("Choose credentials to test with: this form cannot see those of the first"
                        + " host, which this host uses when none are chosen");
            }
            return EsxiSshBackendConfig.testConnection(
                    host, credentialsId, port, hostKeyPolicy, hostKeyFingerprint, null);
        }
    }
}
