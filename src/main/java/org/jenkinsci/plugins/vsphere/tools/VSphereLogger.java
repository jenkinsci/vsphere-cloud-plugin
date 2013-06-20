package org.jenkinsci.plugins.vsphere.tools;

import java.io.PrintStream;

public class VSphereLogger {
	
	/**
	 * This is simply a wrapper method to clean up this class.  This method
	 * checks the verboseOutput flag and writes to the logger as appropriate.
	 * 
	 * @param logger - logger that should receive the information
	 * @param log - string to be sent to the stream
	 * @param force - forces the output the stream, overwriting the verboseOutput flag.
	 */
	public static void vsLogger(PrintStream logger, String str){
		if(logger!=null){
			logger.println("["+Messages.VSphereLogger_title()+"] "+str);
		}
	}
}
