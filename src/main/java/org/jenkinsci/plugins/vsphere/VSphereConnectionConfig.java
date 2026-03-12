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
import com.cloudbees.plugins.credentials.common.StandardCredentials;
import com.cloudbees.plugins.credentials.common.StandardListBoxModel;
import com.cloudbees.plugins.credentials.common.StandardUsernameCredentials;
import com.cloudbees.plugins.credentials.common.StandardUsernamePasswordCredentials;
import com.cloudbees.plugins.credentials.domains.DomainRequirement;
import com.cloudbees.plugins.credentials.domains.HostnameRequirement;
import hudson.Extension;
import hudson.Util;
import hudson.model.AbstractDescribableImpl;
import hudson.model.Descriptor;
import hudson.security.ACL;
import hudson.util.FormValidation;
import hudson.util.ListBoxModel;
import hudson.util.Secret;
import java.util.Collections;
import edu.umd.cs.findbugs.annotations.CheckForNull;
import edu.umd.cs.findbugs.annotations.NonNull;
import jenkins.model.Jenkins;

import org.apache.commons.lang.StringUtils;
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
    
    @DataBoundConstructor
    public VSphereConnectionConfig(String vsHost, String credentialsId) {
        this.vsHost = vsHost;
        this.credentialsId = credentialsId;
    }

    /** Full constructor for internal use, initializes all fields */
    public VSphereConnectionConfig(String vsHost, boolean allowUntrustedCertificate, String credentialsId) {
        this(vsHost, credentialsId);
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
        return DescriptorImpl.lookupCredentials(credentialsId, vsHost);
    }
    
    public @CheckForNull String getPassword() {
        StandardCredentials credentials = getCredentials();
        
        if (credentials instanceof StandardUsernamePasswordCredentials) {
            final Secret password = ((StandardUsernamePasswordCredentials)credentials).getPassword();
            return Secret.toString(password);
        }
        return null;
    }
    
    public @CheckForNull String getUsername() {
        StandardCredentials credentials = getCredentials();
        
        if (credentials instanceof StandardUsernameCredentials) {
            return ((StandardUsernameCredentials)credentials).getUsername();
        }
        return null;
    }
      
    @Extension
    public static class DescriptorImpl extends Descriptor<VSphereConnectionConfig> {

        @Override
        public String getDisplayName() {
            return "N/A";
        }

        public FormValidation doCheckVsHost(@QueryParameter String value) {
            if (value!=null && value.length() != 0) {
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

        public ListBoxModel doFillCredentialsIdItems(@AncestorInPath AbstractFolder<?> containingFolderOrNull,
                @QueryParameter String vsHost) {
            throwUnlessUserHasPermissionToConfigureCloud(containingFolderOrNull);
            return new StandardListBoxModel().includeEmptyValue()
                .includeMatchingAs(ACL.SYSTEM, Jenkins.getInstance(), StandardCredentials.class,
                    Collections.singletonList(getDomainRequirement(vsHost)), CREDENTIALS_MATCHER);
        }

        public FormValidation doCheckCredentialsId(@AncestorInPath AbstractFolder<?> containingFolderOrNull,
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
        public FormValidation doTestConnection(@AncestorInPath AbstractFolder<?> containingFolderOrNull,
                                               @QueryParameter String vsHost,
                                               @QueryParameter boolean allowUntrustedCertificate,
                                               @QueryParameter String credentialsId) {
            throwUnlessUserHasPermissionToConfigureCloud(containingFolderOrNull);
            try {
                final VSphereConnectionConfig config = new VSphereConnectionConfig(vsHost, allowUntrustedCertificate, credentialsId);
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
        private static final CredentialsMatcher CREDENTIALS_MATCHER = CredentialsMatchers.anyOf(
                CredentialsMatchers.instanceOf(StandardUsernamePasswordCredentials.class)
        );
        
        private static @NonNull DomainRequirement getDomainRequirement(String hostname) {
            return new HostnameRequirement(hostname);
        }
        
        public static @CheckForNull StandardCredentials lookupCredentials
                        (@CheckForNull String credentialsId, @NonNull String vsHost) {
            final Jenkins instance = Jenkins.getInstance();            
            if (instance != null && credentialsId != null) {
                return CredentialsMatchers.firstOrNull(
                        CredentialsProvider.lookupCredentials(StandardCredentials.class, instance, 
                                ACL.SYSTEM, getDomainRequirement(vsHost)),
                        CredentialsMatchers.withId(credentialsId));
            }
            return null;
        } 
    }
}
