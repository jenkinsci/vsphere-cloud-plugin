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

import com.cloudbees.plugins.credentials.CredentialsMatcher;
import com.cloudbees.plugins.credentials.CredentialsMatchers;
import com.cloudbees.plugins.credentials.CredentialsProvider;
import com.cloudbees.plugins.credentials.CredentialsScope;
import com.cloudbees.plugins.credentials.CredentialsStore;
import com.cloudbees.plugins.credentials.SystemCredentialsProvider;
import com.cloudbees.plugins.credentials.common.StandardCredentials;
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
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.logging.Level;
import java.util.logging.Logger;
import jenkins.model.Jenkins;
import org.jenkinsci.plugins.vSphereCloud;
import org.kohsuke.stapler.DataBoundConstructor;
import org.kohsuke.stapler.DataBoundSetter;
import org.kohsuke.stapler.QueryParameter;

/**
 *
 * @author Oleg Nenashev &lt;o.v.nenashev@gmail.com&gt;
 */
public class VSphereConnectionConfig extends AbstractDescribableImpl<VSphereConnectionConfig> {

    private static final Logger LOGGER = Logger.getLogger(VSphereConnectionConfig.class.getName());

    private final @CheckForNull String vsHost;

    /** What is specific to the way of connecting, which is never null once the configuration is complete. */
    private VSphereBackendConfig backend;

    // The settings of vCenter as they used to be, flat, in the saved configuration: they are moved into the backend
    // when it is loaded (see readResolve), and so are not in what is saved from then on.
    @Deprecated
    private @CheckForNull String credentialsId;

    @Deprecated
    private @CheckForNull Boolean allowUntrustedCertificate;

    @Deprecated
    private @CheckForNull String httpClientClassName;

    /** What the connection is made to, which decides which of the settings are in use. */
    public enum BackendType {
        /** vCenter (or an ESXi host whose API can be written to), through the vSphere Web Services API. */
        VCENTER,
        /** A standalone ESXi host, through SSH and its {@code vim-cmd}. */
        ESXI_SSH
    }

    enum HttpClientClassName {
        ApacheHttpClientClass("ApacheHttpClient"),
        WSClientClass("WSClient");

        public final String name;

        private HttpClientClassName(String name) {
            this.name = name;
        }
    }

    static Class<?> httpClientNameToClass(String httpClientClassName) {
        // May be null for configs deserialized from XML saved before this field existed
        if (HttpClientClassName.ApacheHttpClientClass.name.equals(httpClientClassName)) {
            return ApacheHttpClient.class;
        }
        return WSClient.class;
    }

    static String httpClientClassToName(Class<?> httpClientClass) {
        if (httpClientClass == ApacheHttpClient.class) {
            return HttpClientClassName.ApacheHttpClientClass.name;
        }
        return HttpClientClassName.WSClientClass.name;
    }

    private static Class<?> httpClientClass;

    public static Class<?> setupGlobalHttpClientClass() {
        if (VSphereConnectionConfig.httpClientClass == null) {
            VSphereConnectionConfig.httpClientClass = WSClient.class;
            List<vSphereCloud> clouds = vSphereCloud.findAllVsphereClouds(null);
            if (!clouds.isEmpty()) {
                VSphereConnectionConfig firstConfig = clouds.get(0).getVsConnectionConfig();
                final VCenterBackendConfig first = firstConfig == null ? null : firstConfig.getVCenter();
                if (first != null) {
                    VSphereConnectionConfig.httpClientClass = httpClientNameToClass(first.getHttpClientClassName());
                }
            }
        }
        return VSphereConnectionConfig.httpClientClass;
    }

    public static String currentHttpClientClassName() {
        return httpClientClassToName(setupGlobalHttpClientClass());
    }

    /** The HTTP client is one setting for the whole plugin: this makes it so for all the clouds that use vCenter. */
    static void applyHttpClientClassNameToAll(String httpClientClassName) {
        for (vSphereCloud cloud : vSphereCloud.findAllVsphereClouds(null)) {
            VSphereConnectionConfig config = cloud.getVsConnectionConfig();
            final VCenterBackendConfig vcenter = config == null ? null : config.getVCenter();
            if (vcenter != null) {
                vcenter.setHttpClientClassNameQuietly(httpClientClassName);
            }
        }
        VSphereConnectionConfig.httpClientClass = httpClientNameToClass(httpClientClassName);
    }

    static ListBoxModel httpClientItems() {
        return new ListBoxModel(
                new ListBoxModel.Option(
                        "Use HttpURLConnection to connect to the vSphere cloud",
                        HttpClientClassName.WSClientClass.name,
                        HttpClientClassName.WSClientClass.name.equals(currentHttpClientClassName())),
                new ListBoxModel.Option(
                        "Use CloseableHttpClient to connect to the vSphere cloud",
                        HttpClientClassName.ApacheHttpClientClass.name,
                        HttpClientClassName.ApacheHttpClientClass.name.equals(currentHttpClientClassName())));
    }

    /** For the form and for Configuration as Code: the host, and then what is specific to the way of connecting. */
    @DataBoundConstructor
    public VSphereConnectionConfig(String vsHost) {
        this.vsHost = vsHost;
        this.backend = new VCenterBackendConfig();
    }

    /** For vCenter: the host (as an https:// URL), the credentials and the HTTP client. */
    public VSphereConnectionConfig(String vsHost, String credentialsId, String httpClientClassName) {
        this(vsHost);
        final VCenterBackendConfig vcenter = (VCenterBackendConfig) backend;
        vcenter.setCredentialsId(credentialsId);
        vcenter.setHttpClientClassName(httpClientClassName);
    }

    /** Full constructor for internal use, initializes all fields */
    public VSphereConnectionConfig(String vsHost, boolean allowUntrustedCertificate, String credentialsId) {
        this(vsHost, credentialsId, null);
        ((VCenterBackendConfig) backend).setAllowUntrustedCertificate(allowUntrustedCertificate);
    }

    /**
     * The settings that used to be flat in this configuration, in what was saved before they were grouped by
     * the way of connecting, are those of vCenter: they become its group.
     */
    protected Object readResolve() {
        if (backend == null) {
            final VCenterBackendConfig vcenter = new VCenterBackendConfig();
            vcenter.setCredentialsId(credentialsId);
            vcenter.setAllowUntrustedCertificate(Boolean.TRUE.equals(allowUntrustedCertificate));
            vcenter.setHttpClientClassNameAsLoaded(httpClientClassName);
            backend = vcenter;
            LOGGER.log(Level.FINE, "Moved the settings of the connection to {0} into its vCenter group", vsHost);
        }
        credentialsId = null;
        allowUntrustedCertificate = null;
        httpClientClassName = null;
        return this;
    }

    public @CheckForNull String getVsHost() {
        return vsHost;
    }

    /** What is specific to the way of connecting. */
    public VSphereBackendConfig getBackend() {
        return backend;
    }

    @DataBoundSetter
    public void setBackend(@CheckForNull VSphereBackendConfig backend) {
        this.backend = backend == null ? new VCenterBackendConfig() : backend;
    }

    public BackendType getBackendType() {
        return backend.getBackendType();
    }

    /** The settings of connecting to vCenter, or null if the connection is of another kind. */
    public @CheckForNull VCenterBackendConfig getVCenter() {
        return backend instanceof VCenterBackendConfig ? (VCenterBackendConfig) backend : null;
    }

    /** The settings of connecting to a standalone ESXi host over SSH, or null if the connection is of another kind. */
    public @CheckForNull EsxiSshBackendConfig getEsxiSsh() {
        return backend instanceof EsxiSshBackendConfig ? (EsxiSshBackendConfig) backend : null;
    }

    private VCenterBackendConfig vCenterToSet(String what) {
        final VCenterBackendConfig vcenter = getVCenter();
        if (vcenter == null) {
            throw new IllegalArgumentException(what + " is a setting of the vCenter backend, but the backend of this"
                    + " connection configuration is " + backend.getBackendType()
                    + ": put it in the settings of the backend instead");
        }
        return vcenter;
    }

    // The next three setters (and the getters after them) are the layout from before the settings were grouped by
    // the way of connecting (now the vCenter group of the backend, where they are exported to), and are still
    // understood, as the settings of vCenter, so that what was written or exported in that layout can be imported.
    // They are write-only on purpose: Configuration as Code needs a getter to take them as attributes, and what
    // the getters give is always "nothing", so that the old layout is never exported again. They are not marked
    // as deprecated, as Configuration as Code refuses deprecated attributes by default. What the settings are is
    // read from the vCenter group: see getVCenter().

    /** The old layout of {@code vCenter: allowUntrustedCertificate: ...}. */
    @DataBoundSetter
    public void setAllowUntrustedCertificate(boolean allowUntrustedCertificate) {
        vCenterToSet("allowUntrustedCertificate").setAllowUntrustedCertificate(allowUntrustedCertificate);
    }

    /** The old layout of {@code vCenter: credentialsId: ...}. */
    @DataBoundSetter
    public void setCredentialsId(@CheckForNull String credentialsId) {
        vCenterToSet("credentialsId").setCredentialsId(credentialsId);
    }

    /** The old layout of {@code vCenter: httpClientClassName: ...}. */
    @DataBoundSetter
    public void setHttpClientClassName(@CheckForNull String httpClientClassName) {
        vCenterToSet("httpClientClassName").setHttpClientClassName(httpClientClassName);
    }

    /** Write-only, see above: always null. The credentials are in {@link #getVCenter()}. */
    public @CheckForNull String getCredentialsId() {
        return null;
    }

    /** Write-only, see above: always false. The setting is in {@link #getVCenter()}. */
    public boolean getAllowUntrustedCertificate() {
        return false;
    }

    /** Write-only, see above: always null. The setting is in {@link #getVCenter()}. */
    public @CheckForNull String getHttpClientClassName() {
        return null;
    }

    public @CheckForNull StandardCredentials getCredentials() {
        if (vsHost == null) {
            return null;
        }
        final VCenterBackendConfig vcenter = getVCenter();
        return DescriptorImpl.lookupCredentials(vcenter == null ? null : vcenter.getCredentialsId(), vsHost);
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

        /** The ways of connecting that the form offers. */
        public Collection<Descriptor<VSphereBackendConfig>> getBackendDescriptors() {
            return VSphereBackendConfig.all();
        }

        /** What is chosen for a configuration that has none yet. */
        public Descriptor<VSphereBackendConfig> getDefaultBackendDescriptor() {
            return Jenkins.get().getDescriptor(VCenterBackendConfig.class);
        }

        public FormValidation doCheckVsHost(@QueryParameter String value) {
            if (value != null && value.length() != 0) {
                if (value.endsWith("/")) {
                    return FormValidation.error("vSphere host name must NOT end with a trailing slash");
                }
                if (!value.startsWith("https://")) {
                    if (value.contains("://")) {
                        return FormValidation.error("vSphere host must start with https:// (for vCenter), or be"
                                + " a plain host name (for a standalone ESXi host over SSH)");
                    }
                    return FormValidation.warning("Without https:// this can only be a standalone ESXi host,"
                            + " reached over SSH; vCenter needs an https:// URL");
                }
            }
            return FormValidation.validateRequired(value);
        }

        // Support on login/password authentication
        static final CredentialsMatcher CREDENTIALS_MATCHER =
                CredentialsMatchers.anyOf(CredentialsMatchers.instanceOf(StandardUsernamePasswordCredentials.class));

        static @NonNull DomainRequirement domainRequirement(String hostname) {
            return new HostnameRequirement(hostname);
        }

        public static @CheckForNull StandardCredentials lookupCredentials(
                @CheckForNull String credentialsId, @NonNull String vsHost) {
            final Jenkins instance = Jenkins.getInstance();
            if (instance != null && credentialsId != null) {
                return CredentialsMatchers.firstOrNull(
                        CredentialsProvider.lookupCredentialsInItemGroup(
                                StandardCredentials.class,
                                instance,
                                ACL.SYSTEM2,
                                Collections.singletonList(domainRequirement(vsHost))),
                        CredentialsMatchers.withId(credentialsId));
            }
            return null;
        }
    }
}
