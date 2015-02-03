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
import javax.annotation.CheckForNull;
import javax.annotation.Nonnull;
import jenkins.model.Jenkins;
import org.kohsuke.stapler.DataBoundConstructor;
import org.kohsuke.stapler.QueryParameter;

/**
 *
 * @author Oleg Nenashev <o.v.nenashev@gmail.com>
 */
public class VSphereConnectionConfig extends AbstractDescribableImpl<VSphereConnectionConfig> {
    
    private final @CheckForNull String vsHost;
    private final @CheckForNull String credentialsId;
    
    @DataBoundConstructor
    public VSphereConnectionConfig(String vsHost, String credentialsId) {
        this.vsHost = vsHost;
        this.credentialsId = credentialsId;
    }

    public @CheckForNull String getVsHost() {
        return vsHost;
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
        
        public ListBoxModel doFillCredentialsIdItems( @QueryParameter String vsHost) {
            final Jenkins instance = Jenkins.getInstance(); 
            if (instance != null && instance.hasPermission(Jenkins.ADMINISTER)) {
                return new StandardListBoxModel().withEmptySelection().withMatching(
                    CREDENTIALS_MATCHER, CredentialsProvider.lookupCredentials(StandardCredentials.class,
                    instance, ACL.SYSTEM, getDomainRequirement(vsHost))
                );
            } else {
                return new StandardListBoxModel();
            }              
        }

        public FormValidation doCheckCredentialsId(@QueryParameter String vsHost,
                                                   @QueryParameter String value) {
            final Jenkins instance = Jenkins.getInstance(); 
            if (instance != null && instance.hasPermission(Jenkins.ADMINISTER)) {
                // nop
            } else {
                return FormValidation.ok();
            }

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

        // Support on login/password authentication
        private static final CredentialsMatcher CREDENTIALS_MATCHER = CredentialsMatchers.anyOf(
                CredentialsMatchers.instanceOf(StandardUsernamePasswordCredentials.class)
        );
        
        private static @Nonnull DomainRequirement getDomainRequirement(String hostname) {
            return new HostnameRequirement(hostname);
        }
        
        public static @CheckForNull StandardCredentials lookupCredentials
                        (@CheckForNull String credentialsId, @Nonnull String vsHost) {
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
