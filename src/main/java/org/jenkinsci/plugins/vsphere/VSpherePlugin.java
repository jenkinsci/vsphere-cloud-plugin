package org.jenkinsci.plugins.vsphere;

import hudson.Extension;
import hudson.model.AbstractProject;
import hudson.tasks.BuildStepDescriptor;
import hudson.tasks.Builder;
import hudson.util.ListBoxModel;

import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;

import net.sf.json.JSONArray;
import net.sf.json.JSONException;
import net.sf.json.JSONObject;

import org.jenkinsci.plugins.vsphere.tools.VSphereException;
import org.kohsuke.stapler.StaplerRequest;

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

	/**
	 * Descriptor for {@link HelloWorldBuilder}.
	 * The class is marked as public so that it can be accessed from views.
	 *
	 * <p>
	 * See <tt>views/hudson/plugins/hello_world/HelloWorldBuilder/*.jelly</tt>
	 * for the actual HTML fragment for the configuration screen.
	 */
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

		/**
		 * To persist global configuration information,
		 * simply store it in a field and call save().
		 *
		 * <p>
		 * If you don't want fields to be persisted, use <tt>transient</tt>.
		 */
		private volatile List<Server> servers;

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

		@Override
		public boolean configure(StaplerRequest req, JSONObject formData) throws FormException {

			// To persist global configuration information,
			// set that to properties and call save().
			populateServers(formData);
			save();
			return true;
			//super.configure(req,formData);
		}

		private void populateServers(JSONObject formData){

			servers = new ArrayList<Server>();
			if (formData.isEmpty()) 
				return;

			JSONArray serverJSONObjectArray;
			try{
				serverJSONObjectArray = formData.getJSONArray("servers");
			}catch(JSONException jsone){
				serverJSONObjectArray = new JSONArray();
				serverJSONObjectArray.add(formData.getJSONObject("servers"));
			}

			for (int i=0, j=serverJSONObjectArray.size(); i<j; i++){
				servers.add(new Server(serverJSONObjectArray.getJSONObject(i)));
			}
		}

		public List<Server> getServers() {
			return servers;
		}

		public Server getServer(String name) throws VSphereException {
			for(Server server : servers)
				if(server.getName().equals(name))
					return server;

			throw new VSphereException("Server not found!");
		}

		public ListBoxModel doFillServerItems(){
			ListBoxModel select = new ListBoxModel(servers.size());

			for(Server server : servers){
				select.add(server.getName());
			}
			return select;
		}

		public void checkServerExistence(Server serverToFind) throws VSphereException {

			for (Server server : servers)
				if(server.getHash()==serverToFind.getHash())
					return;

			throw new VSphereException("Server does not exist in global config! Please re-save your job configuration.");
		}

		public static DescriptorImpl get() {
			return Builder.all().get(DescriptorImpl.class);
		}

		public static boolean allowDelete() {
			return ALLOW_VM_DELETE;
		}

		private static boolean ALLOW_VM_DELETE = true; //!Boolean.getBoolean(VSpherePlugin.class.getName()+".disableDelete");; 

		/*	public FormValidation doCheckServers(@QueryParameter ArrayList<Server> servers)
				throws IOException, ServletException {
					if (!servers.isEmpty()){

						//servers.
						return FormValidation.error("Please enter the clone name");

					return FormValidation.ok();
				}*/
		/*public Server getServerObjectFromVM(String serverName) throws Exception{

			for(Server server : servers){
				if(server.getName().equalsIgnoreCase(serverName)){
					return server;
				}
			}

			throw new Exception("Server not found!");
		}*/
	}
}