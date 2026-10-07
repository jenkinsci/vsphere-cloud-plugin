package org.jenkinsci.plugins.vsphere.VSphereConnectionConfig

f = namespace(lib.FormTagLib)
st = namespace("jelly:stapler")

// Checks the host again when the connection type changes
st.adjunct(includes:"org.jenkinsci.plugins.vsphere.vsHostRecheck")

f.entry(title:_("vSphere Host"), field:"vsHost") {
    f.textbox(checkMethod:"post")
}

// What the rest of the settings are depends on how the host is connected to: each way has its own
f.dropdownDescriptorSelector(
    title:_("Connection type"),
    field:"backend",
    descriptors:descriptor.backendDescriptors,
    default:descriptor.defaultBackendDescriptor)
