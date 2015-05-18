/*   Copyright 2013, MANDIANT, Eric Lordahl
 *
 *   Licensed under the Apache License, Version 2.0 (the "License");
 *   you may not use this file except in compliance with the License.
 *   You may obtain a copy of the License at
 *
 *       http://www.apache.org/licenses/LICENSE-2.0
 *
 *   Unless required by applicable law or agreed to in writing, software
 *   distributed under the License is distributed on an "AS IS" BASIS,
 *   WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *   See the License for the specific language governing permissions and
 *   limitations under the License.
 */
package org.jenkinsci.plugins.vsphere.tools;

import java.io.PrintStream;

public class VSphereLogger {

	/**
	 * This is simply a wrapper method to clean up this class.  This method
	 * checks the verboseOutput flag and writes to the logger as appropriate.
	 * 
	 * @param logger - logger that should receive the information
	 */
	public static void vsLogger(PrintStream logger, String str){
		if(logger!=null){
			logger.println("["+Messages.VSphereLogger_title()+"] "+str);
		}
	}

    public static void vsLogger(PrintStream logger, Exception e){
        if(logger ==null) {
            return;
        }

        if (e.getMessage() != null) {
            logger.println("["+Messages.VSphereLogger_title()+"] Exception: " + e.getMessage());
        } else {
            logger.println("["+Messages.VSphereLogger_title()+"] Exception message was null, stack trace");
            e.printStackTrace(logger);
        }
    }
}
