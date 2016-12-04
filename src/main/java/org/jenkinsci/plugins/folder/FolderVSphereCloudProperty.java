package org.jenkinsci.plugins.folder;

import com.cloudbees.hudson.plugins.folder.AbstractFolder;
import com.cloudbees.hudson.plugins.folder.AbstractFolderProperty;
import com.cloudbees.hudson.plugins.folder.AbstractFolderPropertyDescriptor;
import hudson.Extension;
import hudson.util.FormValidation;
import org.apache.commons.lang.StringUtils;
import org.jenkinsci.plugins.VSphereCloud;
import org.jenkinsci.plugins.vsphere.VSphereConnectionConfig;
import org.jenkinsci.plugins.vsphere.tools.VSphere;
import org.kohsuke.stapler.DataBoundConstructor;
import org.kohsuke.stapler.QueryParameter;

import java.util.List;

/**
 * Created by igreenfi on 11/30/2016.
 */
public class FolderVSphereCloudProperty extends AbstractFolderProperty<AbstractFolder<?>> {

    public List<VSphereCloud> getVsphereClouds() {
        return clouds;
    }

    private List<VSphereCloud> clouds = null;

    public List<VSphereCloud> getClouds() {
        return clouds;
    }

    public void setClouds(List<VSphereCloud> clouds) {
        this.clouds = clouds;
    }

    @DataBoundConstructor
    public FolderVSphereCloudProperty(List<VSphereCloud> clouds) {
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
        public FormValidation doTestConnection(@QueryParameter String vsHost,
                                               @QueryParameter String vsDescription,
                                               @QueryParameter String credentialsId) {
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

                final VSphereConnectionConfig config = new VSphereConnectionConfig(vsHost, credentialsId);
                final String effectiveUsername = config.getUsername();
                final String effectivePassword = config.getPassword();

                if (StringUtils.isEmpty(effectiveUsername)) {
                    return FormValidation.error("Username is not specified");
                }

                if (StringUtils.isEmpty(effectivePassword)) {
                    return FormValidation.error("Password is not specified");
                }

                VSphere.connect(vsHost + "/sdk", effectiveUsername, effectivePassword).disconnect();

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
