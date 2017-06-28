package org.jenkinsci.plugins.vsphere.tools;

public class VSphereNotFoundException extends VSphereException {
    private final String resourceType;
    private final String resourceName;

    public VSphereNotFoundException(String resourceType, String resourceName) {
        this(resourceType, resourceName, (Throwable) null);
    }

    public VSphereNotFoundException(String resourceType, String resourceName, Throwable cause) {
        this(resourceType, resourceName, resourceType + " \"" + resourceName + "\" not found", cause);
    }

    public VSphereNotFoundException(String resourceType, String resourceName, String message) {
        this(resourceType, resourceName, message, null);
    }

    public VSphereNotFoundException(String resourceType, String resourceName, String message, Throwable cause) {
        super(message, cause);
        this.resourceType = resourceType;
        this.resourceName = resourceName;
    }
}
