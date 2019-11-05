package org.jenkinsci.plugins.vsphere.parameters;

import static org.jenkinsci.plugins.vsphere.tools.PermissionUtils.throwUnlessUserHasPermissionToAccessJob;

import hudson.Extension;
import hudson.model.Item;
import hudson.model.ParameterValue;
import hudson.model.SimpleParameterDefinition;
import hudson.model.StringParameterValue;
import hudson.util.ListBoxModel;
import net.sf.json.JSONObject;
import org.jenkinsci.plugins.vSphereCloud;
import org.kohsuke.stapler.AncestorInPath;
import org.kohsuke.stapler.DataBoundConstructor;
import org.kohsuke.stapler.StaplerRequest;
import org.kohsuke.stapler.export.Exported;

import java.util.List;

/**
 * Parameter used for selecting a vSphere cloud from a dropdown box.
 */
public class CloudSelectorParameter extends SimpleParameterDefinition {

    @DataBoundConstructor
    public CloudSelectorParameter() {
        super("VSPHERE_CLOUD_NAME", "Vsphere Cloud Selector");
    }

    @Override
    public StringParameterValue getDefaultParameterValue() {
        List<String> cloudNames = vSphereCloud.findAllVsphereCloudNames();
        return new StringParameterValue(getName(), cloudNames.get(0), getDescription());
    }

    private StringParameterValue checkValue(StringParameterValue value) {
        List<String> cloudNames = vSphereCloud.findAllVsphereCloudNames();
        String check = String.valueOf(value.getValue());
        if (!cloudNames.contains(check)) {
            throw new IllegalArgumentException("No vsphere cloud with name: " + check);
        }
        return value;
    }

    @Override
    public ParameterValue createValue(StaplerRequest req, JSONObject jo) {
        StringParameterValue value = req.bindJSON(StringParameterValue.class, jo);
        value.setDescription(getDescription());
        return checkValue(value);
    }

    @Override
    public StringParameterValue createValue(String value) {
        return checkValue(new StringParameterValue(getName(), value, getDescription()));
    }

    @Exported
    public List<String> getCloudNames() {
        return vSphereCloud.findAllVsphereCloudNames();
    }

    @Extension
    public static class DescriptorImpl extends ParameterDescriptor {

        public ListBoxModel doFillCloudNameItems(@AncestorInPath Item context) {
            throwUnlessUserHasPermissionToAccessJob(context);
            ListBoxModel items = new ListBoxModel();
            for (String cloudName : vSphereCloud.findAllVsphereCloudNames()) {
                items.add(cloudName);
            }
            return items;
        }

        @Override
        public String getDisplayName() {
            return Messages.VSphere_title_vSphereCloudSelectorParameter();
        }
    }
}
