package org.jenkinsci.plugins.vsphere.tools;

import java.io.PrintStream;

public class VSphereLogger {
	
	private boolean verboseOutput;
	private static VSphereLogger logger = null;;
	
	private VSphereLogger(){
		this.verboseOutput = false;
	}
	
	public static VSphereLogger getVSphereLogger(){
		
		if(logger==null){
			logger = new VSphereLogger();
		}
		
		return logger; 
	}
	
	public void setVerboseOutput(boolean verboseOutput) {
		this.verboseOutput = verboseOutput;
	}
	
	/**
	 * This is simply a wrapper method to clean up this class.  This method
	 * checks the verboseOutput flag and writes to the logger as appropriate.
	 * 
	 * @param logger - logger that should receive the information
	 * @param log - string to be sent to the stream
	 */
	public void verboseLogger(PrintStream logger, String str){
		verboseLogger(logger, str, false);
	}
	
	/**
	 * This is simply a wrapper method to clean up this class.  This method
	 * checks the verboseOutput flag and writes to the logger as appropriate.
	 * 
	 * @param logger - logger that should receive the information
	 * @param log - string to be sent to the stream
	 * @param force - forces the output the stream, overwriting the verboseOutput flag.
	 */
	public void verboseLogger(PrintStream logger, String str, boolean force){
		if(logger!=null &&
				(verboseOutput || force)){
			logger.println("["+Messages.VSphereLogger_title()+"] "+str);
		}
	}
}
