package org.jenkinsci.plugins.vsphere.VSphereConnectionConfig

f = namespace(lib.FormTagLib)
c = namespace(lib.CredentialsTagLib)

f.entry(title:_("vSphere Host"), field:"vsHost") {
    f.textbox()
}

f.entry(title:_("Disable SSL Check"), field:"allowUntrustedCertificate") {
    f.checkbox()
}

f.entry(title:_("Credentials"), field:"credentialsId") {
    c.select()
}

f.validateButton(title:_("Test Connection"), progress:_("Testing..."), method:"testConnection", with:"vsHost,allowUntrustedCertificate,credentialsId")
