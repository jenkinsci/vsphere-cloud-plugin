package org.jenkinsci.plugins.vsphere.tools;

public class VSphereDuplicateException extends VSphereException {
    private final String resourceType;
    private final String resourceName;

    public VSphereDuplicateException(String resourceType, String resourceName) {
        this(resourceType, resourceName, null);
    }

    public VSphereDuplicateException(String resourceType, String resourceName, Throwable cause) {
        super(resourceType + " \"" + resourceName + "\" already exists", cause);
        this.resourceType = resourceType;
        this.resourceName = resourceName;
    }
}
