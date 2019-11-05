package org.jenkinsci.plugins.vsphere.tools;

import org.acegisecurity.AccessDeniedException;
import org.kohsuke.accmod.Restricted;
import org.kohsuke.accmod.restrictions.NoExternalUse;

import com.cloudbees.hudson.plugins.folder.AbstractFolder;

import hudson.model.Computer;
import hudson.model.Item;
import hudson.model.ItemGroup;
import hudson.security.AccessControlled;
import hudson.security.Permission;
import jenkins.model.Jenkins;

/**
 * Utility class for checking security permissions.
 */
public class PermissionUtils {
    private PermissionUtils() {
    }

    /**
     * Throws unless the user has permission to update this slave.
     * 
     * @param context
     *            The <code>@AncestorInPath</code> {@link ItemGroup} that
     *            contains the slave.
     * @throws AccessDeniedException
     *             if the user does not have permission.
     */
    @Restricted(NoExternalUse.class)
    public static void throwUnlessUserHasPermissionToConfigureSlave(final ItemGroup<?> context) {
        checkPermission(context, Computer.CONFIGURE);
    }

    /**
     * Throws unless the user has permission to update this cloud. For a cloud
     * defined within a {@link AbstractFolder}, a user must have permission to
     * configure the folder. For other (system global) clouds, the user must be
     * an administrator.
     * 
     * @param folderContextOrNull
     *            The <code>@AncestorInPath</code> {@link AbstractFolder}
     *            containing this cloud, or null if this is a global scope
     *            cloud.
     * @throws AccessDeniedException
     *             if the user does not have permission.
     */
    @Restricted(NoExternalUse.class)
    public static void throwUnlessUserHasPermissionToConfigureCloud(final AbstractFolder<?> folderContextOrNull) {
        checkPermission(folderContextOrNull, Item.CONFIGURE);
    }

    /**
     * Throws unless the user has permission to update this job. This is used to
     * police access to non-trivial build-step form validation and test methods
     * that are only used when reconfiguring a job.
     * 
     * @param context
     *            The <code>@AncestorInPath</code> {@link Item} of this job.
     * @throws AccessDeniedException
     *             if the user does not have permission.
     */
    @Restricted(NoExternalUse.class)
    public static void throwUnlessUserHasPermissionToConfigureJob(final Item context) {
        checkPermission(context, Item.CONFIGURE);
    }

    /**
     * Throws unless the user has permission to access this job. This is used to
     * police access to non-trivial build-step form validation and test methods
     * that could be useful when viewing or using a job.
     * 
     * @param context
     *            The <code>@AncestorInPath</code> {@link Item} of this job.
     * @throws AccessDeniedException
     *             if the user does not have permission.
     */
    @Restricted(NoExternalUse.class)
    public static void throwUnlessUserHasPermissionToAccessJob(final Item context) {
        checkPermission(context, Item.READ);
    }

    /**
     * Throws unless we have at least one of the specified permissions.
     * 
     * @param c
     *            Our context.
     * @param allowablePermission
     *            The first permission we will accept.
     */
    private static void checkPermission(final Object c, Permission allowablePermission) {
        final AccessControlled ac = c instanceof AccessControlled ? (AccessControlled) c : Jenkins.getInstance();
        ac.checkPermission(allowablePermission);
    }
}
