package org.jenkinsci.plugins.vsphere.EsxiSshHost

f = namespace(lib.FormTagLib)
c = namespace(lib.CredentialsTagLib)

f.entry(title:_("ESXi host"), field:"host") {
    f.textbox(checkMethod:"post")
}

f.entry(title:_("SSH Port"), field:"port") {
    f.number(min:0, max:65535, step:1, checkMethod:"post")
}

f.entry(title:_("Credentials"), field:"credentialsId") {
    c.select()
}

f.entry(title:_("Fingerprint trust"), field:"hostKeyPolicy") {
    f.select(clazz:"esxi-host-key-policy", checkMethod:"post")
}

f.entry(title:_("Fingerprint"), field:"hostKeyFingerprint") {
    f.textbox(checkMethod:"post")
}

f.validateButton(title:_("Show fingerprint"), progress:_("Asking..."), method:"queryHostKey", with:"host,port")
f.validateButton(title:_("Test Connection"), progress:_("Testing..."), method:"testConnection", with:"host,credentialsId,port,hostKeyPolicy,hostKeyFingerprint")
