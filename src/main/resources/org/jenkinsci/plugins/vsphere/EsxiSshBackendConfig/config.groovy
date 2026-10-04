package org.jenkinsci.plugins.vsphere.EsxiSshBackendConfig

f = namespace(lib.FormTagLib)
c = namespace(lib.CredentialsTagLib)
st = namespace("jelly:stapler")

// Asks for a confirmation when the host key that is seen first is chosen to be trusted
st.adjunct(includes:"org.jenkinsci.plugins.vsphere.esxiFirstUse")

f.entry(title:_("SSH Port"), field:"port") {
    f.number(clazz:"required", min:1, max:65535, step:1, default:22)
}

f.entry(title:_("Credentials"), field:"credentialsId") {
    c.select()
}

f.entry(title:_("Trust the host key"), field:"hostKeyPolicy") {
    f.select(clazz:"esxi-host-key-policy")
}

f.entry(title:_("Host key fingerprint"), field:"hostKeyFingerprint") {
    f.textbox()
}

f.advanced {
    f.entry(title:_("Connect timeout in seconds"), field:"connectTimeoutSeconds") {
        f.number(clazz:"required", min:1, step:1, default:30)
    }
    f.entry(title:_("Time limit of a command in seconds"), field:"commandTimeoutSeconds") {
        f.number(clazz:"required", min:1, step:1, default:600)
    }
}

// "../vsHost" is the host, which is a setting of the connection configuration that this is part of
f.validateButton(title:_("Show host key"), progress:_("Asking..."), method:"queryHostKey", with:"../vsHost,port,connectTimeoutSeconds")
f.validateButton(title:_("Test Connection"), progress:_("Testing..."), method:"testConnection", with:"../vsHost,credentialsId,port,hostKeyPolicy,hostKeyFingerprint,connectTimeoutSeconds")
