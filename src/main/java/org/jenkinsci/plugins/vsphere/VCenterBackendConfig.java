/*   Copyright 2026, Jim Klimov
 *   Copyright 2014 Oleg Nenashev <o.v.nenashev@gmail.com> (the settings and the form actions that were moved here
 *   from VSphereConnectionConfig)
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
import com.cloudbees.plugins.credentials.CredentialsProvider;
import com.cloudbees.plugins.credentials.common.StandardCredentials;
import com.cloudbees.plugins.credentials.common.StandardListBoxModel;
import edu.umd.cs.findbugs.annotations.CheckForNull;
import hudson.Extension;
import hudson.Util;
import hudson.model.Descriptor;
import hudson.model.Item;
import hudson.security.ACL;
import hudson.util.FormValidation;
import hudson.util.ListBoxModel;
import java.util.Collections;
import jenkins.model.Jenkins;
import org.apache.commons.lang3.StringUtils;
import org.jenkinsci.Symbol;
import org.jenkinsci.plugins.vsphere.tools.VSphere;
import org.jenkinsci.plugins.vsphere.tools.VSphereException;
import org.jenkinsci.plugins.vsphere.tools.VSphereYavijava;
import org.kohsuke.stapler.AncestorInPath;
import org.kohsuke.stapler.DataBoundConstructor;
import org.kohsuke.stapler.DataBoundSetter;
import org.kohsuke.stapler.QueryParameter;
import org.kohsuke.stapler.interceptor.RequirePOST;

/**
 * Connecting to vCenter (or to an ESXi host whose API can be written to) through the vSphere Web Services API:
 * the credentials (a "Username with password"), whether to be strict about the certificate of the server, and
 * which HTTP client to use. The server is the {@code vsHost} of the connection configuration, as an
 * {@code https://} URL.
 */
public class VCenterBackendConfig extends VSphereBackendConfig {

    private @CheckForNull String credentialsId;
    private boolean allowUntrustedCertificate;
    private @CheckForNull String httpClientClassName;

    @DataBoundConstructor
    public VCenterBackendConfig() {}

    public VCenterBackendConfig(@CheckForNull String credentialsId, boolean allowUntrustedCertificate) {
        this.credentialsId = credentialsId;
        this.allowUntrustedCertificate = allowUntrustedCertificate;
    }

    public @CheckForNull String getCredentialsId() {
        return credentialsId;
    }

    @DataBoundSetter
    public void setCredentialsId(@CheckForNull String credentialsId) {
        this.credentialsId = Util.fixEmptyAndTrim(credentialsId);
    }

    public boolean getAllowUntrustedCertificate() {
        return allowUntrustedCertificate;
    }

    @DataBoundSetter
    public void setAllowUntrustedCertificate(boolean allowUntrustedCertificate) {
        this.allowUntrustedCertificate = allowUntrustedCertificate;
    }

    /** The HTTP client, as {@code WSClient} or {@code ApacheHttpClient}; null for the default one. */
    public @CheckForNull String getHttpClientClassName() {
        return httpClientClassName;
    }

    /**
     * Which HTTP client to use. This is one setting for the whole plugin: it is set for all the clouds at once,
     * and when it is null, it is what it was.
     */
    @DataBoundSetter
    public void setHttpClientClassName(@CheckForNull String httpClientClassName) {
        this.httpClientClassName = (httpClientClassName == null)
                ? VSphereConnectionConfig.currentHttpClientClassName()
                : httpClientClassName;
        VSphereConnectionConfig.applyHttpClientClassNameToAll(this.httpClientClassName);
    }

    /** For the configuration that is loaded: only takes the value, with no effect on others. */
    void setHttpClientClassNameAsLoaded(@CheckForNull String httpClientClassName) {
        this.httpClientClassName = httpClientClassName;
    }

    /** For the setting that is applied to all the clouds: only takes the value, as it is. */
    void setHttpClientClassNameQuietly(String httpClientClassName) {
        this.httpClientClassName = httpClientClassName;
    }

    @Override
    public VSphere connect(VSphereConnectionConfig config) throws VSphereException {
        return VSphereYavijava.connect(config);
    }

    @Override
    public VSphereConnectionConfig.BackendType getBackendType() {
        return VSphereConnectionConfig.BackendType.VCENTER;
    }

    @Extension
    @Symbol("vCenter")
    public static class DescriptorImpl extends Descriptor<VSphereBackendConfig> {

        @Override
        public String getDisplayName() {
            return "vCenter, through the vSphere API";
        }

        @RequirePOST
        public ListBoxModel doFillHttpClientClassNameItems(@AncestorInPath AbstractFolder<?> containingFolderOrNull) {
            throwUnlessUserHasPermissionToConfigureCloud(containingFolderOrNull);
            return VSphereConnectionConfig.httpClientItems();
        }

        public FormValidation doCheckAllowUntrustedCertificate(@QueryParameter boolean value) {
            if (value) {
                return FormValidation.warning("Warning: This is not secure.");
            }
            return FormValidation.ok();
        }

        @RequirePOST
        public ListBoxModel doFillCredentialsIdItems(
                @AncestorInPath AbstractFolder<?> containingFolderOrNull,
                @QueryParameter String vsHost,
                @QueryParameter String credentialsId) {
            throwUnlessUserHasPermissionToConfigureCloud(containingFolderOrNull);
            final StandardListBoxModel result = new StandardListBoxModel();
            // Only those who may use credentials get to see which ones exist; everybody else just sees the
            // value that is currently configured.
            final boolean mayListCredentials = containingFolderOrNull == null
                    ? Jenkins.get().hasPermission(Jenkins.ADMINISTER)
                    : containingFolderOrNull.hasPermission(CredentialsProvider.USE_ITEM)
                            || containingFolderOrNull.hasPermission(Item.EXTENDED_READ);
            if (!mayListCredentials) {
                return result.includeCurrentValue(credentialsId);
            }
            // Credentials are looked up from the root when connecting (see lookupCredentials), so that is where
            // they are listed from.
            return result.includeEmptyValue()
                    .includeMatchingAs(
                            ACL.SYSTEM2,
                            Jenkins.get(),
                            StandardCredentials.class,
                            Collections.singletonList(VSphereConnectionConfig.DescriptorImpl.domainRequirement(vsHost)),
                            VSphereConnectionConfig.DescriptorImpl.CREDENTIALS_MATCHER)
                    .includeCurrentValue(credentialsId);
        }

        @RequirePOST
        public FormValidation doCheckCredentialsId(
                @AncestorInPath AbstractFolder<?> containingFolderOrNull,
                @QueryParameter String vsHost,
                @QueryParameter String value) {
            throwUnlessUserHasPermissionToConfigureCloud(containingFolderOrNull);

            value = Util.fixEmptyAndTrim(value);
            if (value == null) {
                return FormValidation.ok();
            }

            vsHost = Util.fixEmptyAndTrim(vsHost);
            if (vsHost == null) {
                return FormValidation.warning("Cannot validate credentials. Host is not set");
            }

            final StandardCredentials credentials =
                    VSphereConnectionConfig.DescriptorImpl.lookupCredentials(value, vsHost);
            if (credentials == null) {
                return FormValidation.warning("Cannot find any credentials with id " + value);
            }

            return FormValidation.ok();
        }

        /**
         * For UI.
         *
         * @param vsHost        From UI.
         * @param credentialsId From UI.
         * @return Result of the validation.
         */
        @RequirePOST
        public FormValidation doTestConnection(
                @AncestorInPath AbstractFolder<?> containingFolderOrNull,
                @QueryParameter String vsHost,
                @QueryParameter boolean allowUntrustedCertificate,
                @QueryParameter String credentialsId) {
            throwUnlessUserHasPermissionToConfigureCloud(containingFolderOrNull);
            try {
                final VSphereConnectionConfig config =
                        new VSphereConnectionConfig(vsHost, allowUntrustedCertificate, credentialsId);
                final String effectiveUsername = config.getUsername();
                final String effectivePassword = config.getPassword();

                if (StringUtils.isEmpty(effectiveUsername)) {
                    return FormValidation.error("Username is not specified");
                }

                if (effectivePassword == null) {
                    return FormValidation.error("Password is not specified");
                }

                VSphere.connect(config).disconnect();

                return FormValidation.ok("Connected successfully");
            } catch (Exception e) {
                return FormValidation.error(e, "Failed to connect");
            }
        }
    }
}
