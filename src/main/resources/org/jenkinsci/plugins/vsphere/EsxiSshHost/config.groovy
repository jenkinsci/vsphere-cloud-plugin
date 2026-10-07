package org.jenkinsci.plugins.vsphere.EsxiSshHost

f = namespace(lib.FormTagLib)
c = namespace(lib.CredentialsTagLib)
st = namespace("jelly:stapler")

// Keeps copies of some settings of the first host in hidden fields next to these, which is where the buttons below can see them
st.adjunct(includes:"org.jenkinsci.plugins.vsphere.esxiHostMirror")

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

// What a host does not say is that of the first host, whose fields the buttons cannot see from here, so their values are in
// these fields (made by esxiHostMirror.js, and not part of what is saved: the host has no property of these names)
input(type:"hidden", name:"_.firstCredentialsId", "class":"esxi-mirror", "data-source":"credentialsId")
input(type:"hidden", name:"_.firstPort", "class":"esxi-mirror", "data-source":"port")
input(type:"hidden", name:"_.firstHostKeyPolicy", "class":"esxi-mirror", "data-source":"hostKeyPolicy")

f.validateButton(title:_("Show fingerprint"), progress:_("Asking..."), method:"queryHostKey", with:"host,port,firstPort")
f.validateButton(title:_("Test Connection"), progress:_("Testing..."), method:"testConnection", with:"host,credentialsId,port,hostKeyPolicy,hostKeyFingerprint,firstCredentialsId,firstPort,firstHostKeyPolicy")
