/*
 * Copyright 2014 Oleg Nenashev <o.v.nenashev@gmail.com>.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.jenkinsci.plugins.vsphere;

import static org.jenkinsci.plugins.vsphere.tools.PermissionUtils.throwUnlessUserHasPermissionToConfigureCloud;

import com.cloudbees.hudson.plugins.folder.AbstractFolder;
import com.cloudbees.plugins.credentials.CredentialsMatcher;
import com.cloudbees.plugins.credentials.CredentialsMatchers;
import com.cloudbees.plugins.credentials.CredentialsProvider;
import com.cloudbees.plugins.credentials.CredentialsScope;
import com.cloudbees.plugins.credentials.CredentialsStore;
import com.cloudbees.plugins.credentials.SystemCredentialsProvider;
import com.cloudbees.plugins.credentials.common.StandardCredentials;
import com.cloudbees.plugins.credentials.common.StandardListBoxModel;
import com.cloudbees.plugins.credentials.common.StandardUsernameCredentials;
import com.cloudbees.plugins.credentials.common.StandardUsernamePasswordCredentials;
import com.cloudbees.plugins.credentials.domains.Domain;
import com.cloudbees.plugins.credentials.domains.DomainRequirement;
import com.cloudbees.plugins.credentials.domains.HostnameRequirement;
import com.cloudbees.plugins.credentials.impl.UsernamePasswordCredentialsImpl;
import com.vmware.vim25.ws.ApacheHttpClient;
import com.vmware.vim25.ws.WSClient;
import edu.umd.cs.findbugs.annotations.CheckForNull;
import edu.umd.cs.findbugs.annotations.NonNull;
import hudson.Extension;
import hudson.Util;
import hudson.model.AbstractDescribableImpl;
import hudson.model.Descriptor;
import hudson.security.ACL;
import hudson.util.FormValidation;
import hudson.util.ListBoxModel;
import hudson.util.Secret;
import java.io.IOException;
import java.util.Collections;
import java.util.List;
import jenkins.model.Jenkins;
import org.apache.commons.lang3.StringUtils;
import org.jenkinsci.plugins.vSphereCloud;
import org.jenkinsci.plugins.vsphere.tools.VSphere;
import org.kohsuke.stapler.AncestorInPath;
import org.kohsuke.stapler.DataBoundConstructor;
import org.kohsuke.stapler.DataBoundSetter;
import org.kohsuke.stapler.QueryParameter;
import org.kohsuke.stapler.interceptor.RequirePOST;

/**
 *
 * @author Oleg Nenashev &lt;o.v.nenashev@gmail.com&gt;
 */
public class VSphereConnectionConfig extends AbstractDescribableImpl<VSphereConnectionConfig> {

    private final @CheckForNull String vsHost;
    private /*final*/ boolean allowUntrustedCertificate;
    private final @CheckForNull String credentialsId;

    private enum HttpClientClassName {
        ApacheHttpClientClass("ApacheHttpClient"),
        WSClientClass("WSClient");

        public final String name;

        private HttpClientClassName(String name) {
            this.name = name;
        }
    }

    private static Class<?> httpClientNameToClass(String httpClientClassName) {
        // May be null for configs deserialized from XML saved before this field existed
        if (HttpClientClassName.ApacheHttpClientClass.name.equals(httpClientClassName)) {
            return ApacheHttpClient.class;
        }
        return WSClient.class;
    }

    private static String httpClientClassToName(Class<?> httpClientClass) {
        if (httpClientClass == ApacheHttpClient.class) {
            return HttpClientClassName.ApacheHttpClientClass.name;
        }
        return HttpClientClassName.WSClientClass.name;
    }

    private String httpClientClassName;

    private static Class<?> httpClientClass;

    public static Class<?> setupGlobalHttpClientClass() {
        if (VSphereConnectionConfig.httpClientClass == null) {
            VSphereConnectionConfig.httpClientClass = WSClient.class;
            List<vSphereCloud> clouds = vSphereCloud.findAllVsphereClouds(null);
            if (!clouds.isEmpty()) {
                VSphereConnectionConfig firstConfig = clouds.get(0).getVsConnectionConfig();
                if (firstConfig != null) {
                    VSphereConnectionConfig.httpClientClass = httpClientNameToClass(firstConfig.httpClientClassName);
                }
            }
        }
        return VSphereConnectionConfig.httpClientClass;
    }

    public static String getHttpClientClassName() {
        return httpClientClassToName(setupGlobalHttpClientClass());
    }

    @DataBoundSetter
    public void setHttpClientClassName(String httpClientClassName) {
        this.httpClientClassName = (httpClientClassName == null) ? getHttpClientClassName() : httpClientClassName;
        for (vSphereCloud cloud : vSphereCloud.findAllVsphereClouds(null)) {
            VSphereConnectionConfig config = cloud.getVsConnectionConfig();
            if (config != null) {
                config.httpClientClassName = this.httpClientClassName;
            }
        }
        VSphereConnectionConfig.httpClientClass = httpClientNameToClass(this.httpClientClassName);
    }

    @DataBoundConstructor
    public VSphereConnectionConfig(String vsHost, String credentialsId, String httpClientClassName) {
        this.vsHost = vsHost;
        this.credentialsId = credentialsId;
        setHttpClientClassName(httpClientClassName);
    }

    /** Full constructor for internal use, initializes all fields */
    public VSphereConnectionConfig(String vsHost, boolean allowUntrustedCertificate, String credentialsId) {
        this(vsHost, credentialsId, null);
        setAllowUntrustedCertificate(allowUntrustedCertificate);
    }

    public @CheckForNull String getVsHost() {
        return vsHost;
    }

    @DataBoundSetter
    public void setAllowUntrustedCertificate(boolean allowUntrustedCertificate) {
        this.allowUntrustedCertificate = allowUntrustedCertificate;
    }

    public boolean getAllowUntrustedCertificate() {
        return allowUntrustedCertificate;
    }

    public @CheckForNull String getCredentialsId() {
        return credentialsId;
    }

    public @CheckForNull StandardCredentials getCredentials() {
        if (vsHost == null) {
            return null;
        }
        return DescriptorImpl.lookupCredentials(credentialsId, vsHost);
    }

    public @CheckForNull String getPassword() {
        StandardCredentials credentials = getCredentials();

        if (credentials instanceof StandardUsernamePasswordCredentials) {
            final Secret password = ((StandardUsernamePasswordCredentials) credentials).getPassword();
            return Secret.toString(password);
        }
        return null;
    }

    public @CheckForNull String getUsername() {
        StandardCredentials credentials = getCredentials();

        if (credentials instanceof StandardUsernameCredentials) {
            return ((StandardUsernameCredentials) credentials).getUsername();
        }
        return null;
    }

    /**
     * One-time migration for {@link vSphereCloud} instances still persisted in the legacy XML format
     * (plain {@code vsHost}/{@code username}/{@code password} elements predating this class), so they
     * keep working without the admin having to manually create a credential. Creates - or reuses, if
     * this already ran on a previous load - a {@link UsernamePasswordCredentialsImpl} in the
     * Jenkins-global credentials store and returns its id; returns {@code null} (leaving
     * {@code credentialsId} unset) if there is no legacy username to migrate or no credentials store
     * is available yet.
     */
    public static @CheckForNull String migrateLegacyCredentials(
            @CheckForNull String vsHost, @CheckForNull String username, @CheckForNull String password) {
        username = Util.fixEmptyAndTrim(username);
        if (username == null) {
            return null;
        }
        Jenkins jenkins = Jenkins.getInstanceOrNull();
        if (jenkins == null) {
            return null;
        }

        String effectiveVsHost = Util.fixNull(vsHost);
        String credentialsId = "vsphere-cloud-migrated-" + Util.getDigestOf(effectiveVsHost + ":" + username);

        if (DescriptorImpl.lookupCredentials(credentialsId, effectiveVsHost) != null) {
            // Already migrated on a previous load (config.xml may not have been re-saved since).
            return credentialsId;
        }

        CredentialsStore store = null;
        for (CredentialsStore candidate : CredentialsProvider.lookupStores(jenkins)) {
            if (candidate.getProvider() instanceof SystemCredentialsProvider.ProviderImpl) {
                store = candidate;
                break;
            }
        }
        if (store == null) {
            return null;
        }

        try {
            store.addCredentials(
                    Domain.global(),
                    new UsernamePasswordCredentialsImpl(
                            CredentialsScope.SYSTEM,
                            credentialsId,
                            "Migrated from vSphereCloud legacy username/password"
                                    + (effectiveVsHost.isEmpty() ? "" : " (" + effectiveVsHost + ")"),
                            username,
                            Util.fixNull(password)));
            return credentialsId;
        } catch (Descriptor.FormException | IOException e) {
            vSphereCloud.Log(
                    e, "Failed to migrate legacy vSphereCloud username/password credentials into a Jenkins credential");
            return null;
        }
    }

    @Extension
    public static class DescriptorImpl extends Descriptor<VSphereConnectionConfig> {

        @Override
        public String getDisplayName() {
            return "N/A";
        }

        @RequirePOST
        public ListBoxModel doFillHttpClientClassNameItems(@AncestorInPath AbstractFolder<?> containingFolderOrNull) {
            throwUnlessUserHasPermissionToConfigureCloud(containingFolderOrNull);

            ListBoxModel items = new ListBoxModel(
                    new ListBoxModel.Option(
                            "Use HttpURLConnection to connect to the vSphere cloud",
                            VSphereConnectionConfig.HttpClientClassName.WSClientClass.name,
                            VSphereConnectionConfig.HttpClientClassName.WSClientClass.name.equals(
                                    getHttpClientClassName())),
                    new ListBoxModel.Option(
                            "Use CloseableHttpClient to connect to the vSphere cloud",
                            VSphereConnectionConfig.HttpClientClassName.ApacheHttpClientClass.name,
                            VSphereConnectionConfig.HttpClientClassName.ApacheHttpClientClass.name.equals(
                                    getHttpClientClassName())));
            return items;
        }

        public FormValidation doCheckVsHost(@QueryParameter String value) {
            if (value != null && value.length() != 0) {
                if (!value.startsWith("https://")) {
                    return FormValidation.error("vSphere host must start with https://");
                }
                if (value.endsWith("/")) {
                    return FormValidation.error("vSphere host name must NOT end with a trailing slash");
                }
            }
            return FormValidation.validateRequired(value);
        }

        public FormValidation doCheckAllowUntrustedCertificate(@QueryParameter boolean value) {
            if (value) {
                return FormValidation.warning("Warning: This is not secure.");
            }
            return FormValidation.ok();
        }

        @RequirePOST
        public ListBoxModel doFillCredentialsIdItems(
                @AncestorInPath AbstractFolder<?> containingFolderOrNull, @QueryParameter String vsHost) {
            throwUnlessUserHasPermissionToConfigureCloud(containingFolderOrNull);
            return new StandardListBoxModel()
                    .includeEmptyValue()
                    .includeMatchingAs(
                            ACL.SYSTEM,
                            Jenkins.getInstance(),
                            StandardCredentials.class,
                            Collections.singletonList(getDomainRequirement(vsHost)),
                            CREDENTIALS_MATCHER);
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

            final StandardCredentials credentials = lookupCredentials(value, vsHost);
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

        // Support on login/password authentication
        private static final CredentialsMatcher CREDENTIALS_MATCHER =
                CredentialsMatchers.anyOf(CredentialsMatchers.instanceOf(StandardUsernamePasswordCredentials.class));

        private static @NonNull DomainRequirement getDomainRequirement(String hostname) {
            return new HostnameRequirement(hostname);
        }

        public static @CheckForNull StandardCredentials lookupCredentials(
                @CheckForNull String credentialsId, @NonNull String vsHost) {
            final Jenkins instance = Jenkins.getInstance();
            if (instance != null && credentialsId != null) {
                return CredentialsMatchers.firstOrNull(
                        CredentialsProvider.lookupCredentials(
                                StandardCredentials.class, instance, ACL.SYSTEM, getDomainRequirement(vsHost)),
                        CredentialsMatchers.withId(credentialsId));
            }
            return null;
        }
    }
}
