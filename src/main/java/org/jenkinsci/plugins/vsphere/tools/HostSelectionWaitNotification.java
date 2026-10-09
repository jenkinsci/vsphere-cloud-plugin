package org.jenkinsci.plugins.vsphere.tools;

import edu.umd.cs.findbugs.annotations.CheckForNull;
import groovy.lang.Closure;

/**
 * Adapts a pipeline's {@code hostSelectionWaitNotification} to the {@link HostSelectionOptions.Listener}
 * that host selection tells. The closure is called with two arguments: the message that was just logged
 * (a {@code String}) and the {@link HostSelectionWaitReason}.
 *
 * <p>Only a plain Groovy closure can be called, not one written in the Pipeline script itself, as that is
 * executed by the script's own interpreter and cannot be run from the thread of a step.
 */
public final class HostSelectionWaitNotification {

    private HostSelectionWaitNotification() {}

    /**
     * @param closure what the pipeline passed; null for none
     * @return the listener calling it, or null if there is none
     * @throws VSphereException if it is not a closure
     */
    public static @CheckForNull HostSelectionOptions.Listener of(@CheckForNull Object closure) throws VSphereException {
        if (closure == null) {
            return null;
        }
        if (!(closure instanceof Closure)) {
            throw new VSphereException("hostSelectionWaitNotification must be a closure taking (String message,"
                    + " HostSelectionWaitReason reason), but is "
                    + closure.getClass().getName());
        }
        // A closure written in a Pipeline script can only run on the script's own thread, between steps; called
        // from the thread a step does its work in, it merely throws, which would fail the build the first time
        // a notification is due. Say so now instead.
        if (closure.getClass().getName().startsWith("org.jenkinsci.plugins.workflow.cps.")) {
            throw new VSphereException("hostSelectionWaitNotification is a Pipeline (CPS) closure, which cannot be"
                    + " called from inside the vSphere step. Pass a plain Groovy closure instead, e.g. one returned"
                    + " by a @NonCPS method: it can use Java/Groovy APIs (send mail, post to a webhook, ...) but"
                    + " not Pipeline steps such as echo or slackSend.");
        }
        final Closure<?> c = (Closure<?>) closure;
        return (message, reason) -> c.call(message, reason);
    }
}
