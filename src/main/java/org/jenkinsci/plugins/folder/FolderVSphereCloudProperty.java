package org.jenkinsci.plugins.folder;

import static org.jenkinsci.plugins.vsphere.tools.PermissionUtils.throwUnlessUserHasPermissionToConfigureCloud;

import com.cloudbees.hudson.plugins.folder.AbstractFolder;
import com.cloudbees.hudson.plugins.folder.AbstractFolderProperty;
import com.cloudbees.hudson.plugins.folder.AbstractFolderPropertyDescriptor;
import hudson.Extension;
import hudson.util.FormValidation;
import org.apache.commons.lang.StringUtils;
import org.jenkinsci.plugins.vSphereCloud;
import org.jenkinsci.plugins.vsphere.VSphereConnectionConfig;
import org.jenkinsci.plugins.vsphere.tools.VSphere;
import org.kohsuke.stapler.AncestorInPath;
import org.kohsuke.stapler.DataBoundConstructor;
import org.kohsuke.stapler.QueryParameter;
import org.kohsuke.stapler.interceptor.RequirePOST;

import java.util.List;

/**
 * Created by igreenfi on 11/30/2016.
 */
public class FolderVSphereCloudProperty extends AbstractFolderProperty<AbstractFolder<?>> {

    public List<vSphereCloud> getVsphereClouds() {
        return clouds;
    }

    private List<vSphereCloud> clouds = null;

    public List<vSphereCloud> getClouds() {
        return clouds;
    }

    public void setClouds(List<vSphereCloud> clouds) {
        this.clouds = clouds;
    }

    @DataBoundConstructor
    public FolderVSphereCloudProperty(List<vSphereCloud> clouds) {
        this.clouds = clouds;
    }

    @Override
    public String toString() {
        final StringBuffer sb = new StringBuffer("FolderVSphereCloudProperty{");
        sb.append("clouds=").append(clouds);
        sb.append('}');
        return sb.toString();
    }

    @Extension()
    public static class DescriptorImpl extends AbstractFolderPropertyDescriptor {

        @Override
        public String getDisplayName() {
            return "vSphere Cloud";
        }

        /**
         * For UI.
         *
         * @param vsHost        From UI.
         * @param vsDescription From UI.
         * @param credentialsId From UI.
         * @return Result of the validation.
         */
        @RequirePOST
        public FormValidation doTestConnection(@AncestorInPath AbstractFolder<?> containingFolderOrNull,
                                               @QueryParameter String vsHost,
                                               @QueryParameter boolean allowUntrustedCertificate,
                                               @QueryParameter String vsDescription,
                                               @QueryParameter String credentialsId) {
            throwUnlessUserHasPermissionToConfigureCloud(containingFolderOrNull);
            try {
                /* We know that these objects are not null */
                if (vsHost.length() == 0) {
                    return FormValidation.error("vSphere Host is not specified");
                } else {
                    /* Perform other sanity checks. */
                    if (!vsHost.startsWith("https://")) {
                        return FormValidation.error("vSphere host must start with https://");
                    } else if (vsHost.endsWith("/")) {
                        return FormValidation.error("vSphere host name must NOT end with a trailing slash");
                    }
                }

                final VSphereConnectionConfig config = new VSphereConnectionConfig(vsHost, allowUntrustedCertificate, credentialsId);
                final String effectiveUsername = config.getUsername();
                final String effectivePassword = config.getPassword();

                if (StringUtils.isEmpty(effectiveUsername)) {
                    return FormValidation.error("Username is not specified");
                }

                if (StringUtils.isEmpty(effectivePassword)) {
                    return FormValidation.error("Password is not specified");
                }

                VSphere.connect(config).disconnect();

                return FormValidation.ok("Connected successfully");
            } catch (RuntimeException e) {
                throw e;
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        }

        public FormValidation doCheckMaxOnlineSlaves(@QueryParameter String maxOnlineSlaves) {
            return FormValidation.validateNonNegativeInteger(maxOnlineSlaves);
        }

        public FormValidation doCheckInstanceCap(@QueryParameter String instanceCap) {
            return FormValidation.validateNonNegativeInteger(instanceCap);
        }

    }
}
