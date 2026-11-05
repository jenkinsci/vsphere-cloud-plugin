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

import com.vmware.vim25.mo.VirtualMachine;
import edu.umd.cs.findbugs.annotations.CheckForNull;
import java.io.PrintStream;

public class VSphereLogger {

    /**
     * This is simply a wrapper method to clean up this class.  This method
     * checks the verboseOutput flag and writes to the logger as appropriate.
     *
     * @param logger - logger that should receive the information
     * @param str - The text to be logged.
     */
    public static void vsLogger(PrintStream logger, String str) {
        if (logger != null) {
            logger.println("[" + Messages.VSphereLogger_title() + "] " + str);
        }
    }

    /** A name of a VM (or another thing) as the log has it: in double quotes, and any punctuation after them. */
    public static String quoted(String name) {
        return "\"" + name + "\"";
    }

    /**
     * Where the VM is, to follow its name in the log: {@code " on esxi8"}, or nothing if that is not known.
     *
     * @param vm the VM, or null (as one that was not found is)
     */
    public static String onHost(VSphere vsphere, @CheckForNull VirtualMachine vm) {
        final String host = vm == null ? null : vsphere.hostNameOf(vm);
        return host == null || host.isEmpty() ? "" : " on " + host;
    }

    /** The same for a VM that is looked up by its name here; a lookup that fails is left for what is done next to report. */
    public static String onHost(VSphere vsphere, String vmName) {
        try {
            return onHost(vsphere, vsphere.getVmByName(vmName));
        } catch (Exception e) {
            return "";
        }
    }

    public static void vsLogger(PrintStream logger, Exception e) {
        if (logger == null) {
            return;
        }

        if (e.getMessage() != null && (!(e instanceof RuntimeException) && e.getCause() == null)) {
            logger.println("[" + Messages.VSphereLogger_title() + "] " + e.getMessage());
        } else {
            logger.println("[" + Messages.VSphereLogger_title() + "] Exception");
            e.printStackTrace(logger);
        }
    }
}
