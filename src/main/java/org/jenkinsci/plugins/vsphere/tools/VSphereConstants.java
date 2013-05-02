package org.jenkinsci.plugins.vsphere.tools;

public final class VSphereConstants {

	private VSphereConstants(){}
	
	public static final int IP_MAX_TRIES = Integer.parseInt(Messages.VSphere_ip_max_tries());
	public static final int IP_MAX_SECONDS = Integer.parseInt(Messages.VSphere_ip_seconds());
}
