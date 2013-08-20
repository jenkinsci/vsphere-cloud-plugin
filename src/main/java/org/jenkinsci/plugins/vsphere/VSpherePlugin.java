package org.jenkinsci.plugins.vsphere;

import hudson.Extension;
import hudson.model.AbstractProject;
import hudson.model.Hudson;
import hudson.slaves.Cloud;
import hudson.tasks.BuildStepDescriptor;
import hudson.tasks.Builder;
import hudson.util.ListBoxModel;

import org.jenkinsci.plugins.vSphereCloud;
import org.jenkinsci.plugins.vsphere.tools.VSphere;
import org.jenkinsci.plugins.vsphere.tools.VSphereException;

/**
 * Descriptor for {@link VSpherePlugin}. Used as a singleton.
 * The class is marked as public so that it can be accessed from views.
 *
 * <p>
 * See <tt>src/main/resources/hudson/plugins/hello_world/HelloWorldBuilder/*.jelly</tt>
 * for the actual HTML fragment for the configuration screen.
 */
// This indicates to Jenkins that this is an implementation of an extension point.
@Extension
public class VSpherePlugin extends Builder {

	@Override
	public DescriptorImpl getDescriptor() {
		// see Descriptor javadoc for more about what a descriptor is.
		return (DescriptorImpl)super.getDescriptor();
	}

	// this annotation tells Hudson that this is the implementation of an extension point
	@Extension
	public static final class DescriptorImpl extends BuildStepDescriptor<Builder> {

		@Override
		public boolean isApplicable(Class<? extends AbstractProject> jobType) {
			// TODO Auto-generated method stub
			return false;
		}

		@Override
		public String getDisplayName() {
			// TODO Auto-generated method stub
			return null;
		}

		public DescriptorImpl () {
			//super();
			/*			ALLOW_VM_DELETE = false;
			FileInputStream propFile;
			try {
				System.out.println("DIRRRR"+System.getProperty("user.dir"));
				propFile = new FileInputStream( "myProperties.txt");

		        Properties p =
		            new Properties(System.getProperties());
		        p.load(propFile);

		        // set the system properties
		        System.setProperties(p);
		        // display new properties
		        System.getProperties().list(System.out);

		        ALLOW_VM_DELETE = !Boolean.getBoolean(VSpherePlugin.class.getName()+".disableDelete");
			} catch (Exception e) {
				// TODO Auto-generated catch block
				e.printStackTrace();
			}*/
			load();
		}

	    public vSphereCloud getVSphereCloud(String serverName) throws RuntimeException, VSphereException {
	        if (serverName != null){
	            for (Cloud cloud : Hudson.getInstance().clouds) {
	                if (cloud instanceof vSphereCloud && ((vSphereCloud) cloud).getVsDescription().equals(serverName)) {
	                    return (vSphereCloud) cloud;
	                }
	            }
	        }
	        vSphereCloud.Log("Could not find our vSphere Cloud instance!");
	        throw new RuntimeException("Could not find our vSphere Cloud instance!");
	    }

		public ListBoxModel doFillServerItems(){
			ListBoxModel select = new ListBoxModel();

			for (Cloud cloud : Hudson.getInstance().clouds) {
                if (cloud instanceof vSphereCloud ){
                	select.add( ((vSphereCloud) cloud).getVsDescription()  );
                }
            }
			
			return select;
		}

		public static DescriptorImpl get() {
			return Builder.all().get(DescriptorImpl.class);
		}

		public static boolean allowDelete() {
			return ALLOW_VM_DELETE;
		}

		private static boolean ALLOW_VM_DELETE = true; //!Boolean.getBoolean(VSpherePlugin.class.getName()+".disableDelete");; 
	}
}