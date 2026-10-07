package org.jenkinsci.plugins.vsphere.EsxiSshBackendConfig

f = namespace(lib.FormTagLib)
c = namespace(lib.CredentialsTagLib)
st = namespace("jelly:stapler")

// Asks for a confirmation when the fingerprint that is seen first is chosen to be trusted
st.adjunct(includes:"org.jenkinsci.plugins.vsphere.esxiFirstUse")

f.entry(title:_("SSH Port"), field:"port") {
    f.number(clazz:"required", min:1, max:65535, step:1, default:22, checkMethod:"post")
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

// These are here, before the settings of the other hosts (which have fields of the same names), so that what they
// look for by name is found in the settings above, which are the first host's, and not in those of another host.
// "vsHost" is the host, which is a setting of the connection configuration that this is part of (and is found by
// its plain name: the "../vsHost" form is not, from inside the section of a connection type)
f.validateButton(title:_("Show fingerprint"), progress:_("Asking..."), method:"queryHostKey", with:"vsHost,port,connectTimeoutSeconds")
f.validateButton(title:_("Test Connection"), progress:_("Testing..."), method:"testConnection", with:"vsHost,credentialsId,port,hostKeyPolicy,hostKeyFingerprint,connectTimeoutSeconds")

f.entry(title:_("More ESXi hosts"), field:"additionalHosts") {
    f.repeatableProperty(field:"additionalHosts", add:_("Add another ESXi host"))
}

f.advanced {
    f.entry(title:_("Make replicas of masters"), field:"replicateMasters") {
        f.checkbox()
    }
    f.entry(title:_("How copies go between hosts"), field:"transferMode") {
        f.select()
    }
    f.entry(title:_("Compression of copies between hosts"), field:"relayCompression") {
        f.select()
    }
    f.entry(title:_("Idle time of a transfer in seconds"), field:"transferIdleSeconds") {
        f.number(clazz:"required", min:1, step:1, default:300)
    }
    f.entry(title:_("Connect timeout in seconds"), field:"connectTimeoutSeconds") {
        f.number(clazz:"required", min:1, step:1, default:30)
    }
    f.entry(title:_("Time limit of a command in seconds"), field:"commandTimeoutSeconds") {
        f.number(clazz:"required", min:1, step:1, default:600)
    }
}
