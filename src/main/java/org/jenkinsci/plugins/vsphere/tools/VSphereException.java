package org.jenkinsci.plugins.vsphere.tools;

public class VSphereException extends Exception {

	/**
	 * 
	 */
	private static final long serialVersionUID = -6133908887091288919L;


	public VSphereException() { 
		super(); 
	}

	public VSphereException(String message) {
		super(message); 
	}

	public VSphereException(String message, Throwable cause) {
		super(message, cause); 
	}
	
	public VSphereException(Throwable cause) {
		super(cause);
	}
}
