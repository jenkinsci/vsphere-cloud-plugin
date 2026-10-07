package org.jenkinsci.plugins.vsphere.VCenterBackendConfig

f = namespace(lib.FormTagLib)
c = namespace(lib.CredentialsTagLib)

f.entry(title:_("Change HTTP Client"), field:"httpClientClassName") {
    f.select()
}

f.entry(title:_("Disable SSL Check"), field:"allowUntrustedCertificate") {
    f.checkbox()
}

f.entry(title:_("Credentials"), field:"credentialsId") {
    c.select()
}

// "../vsHost" is the host, which is a setting of the connection configuration that this is part of
f.validateButton(title:_("Test Connection"), progress:_("Testing..."), method:"testConnection", with:"../vsHost,allowUntrustedCertificate,credentialsId")
