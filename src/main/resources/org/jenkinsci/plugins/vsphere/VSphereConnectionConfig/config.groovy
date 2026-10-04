package org.jenkinsci.plugins.vsphere.VSphereConnectionConfig

f = namespace(lib.FormTagLib)

f.entry(title:_("vSphere Host"), field:"vsHost") {
    f.textbox()
}

// What the rest of the settings are depends on how the host is connected to: each way has its own
f.dropdownDescriptorSelector(
    title:_("Connection type"),
    field:"backend",
    descriptors:descriptor.backendDescriptors,
    default:descriptor.defaultBackendDescriptor)
