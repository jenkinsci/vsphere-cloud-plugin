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
    c.select(onchange="""{
            var self = this.targetElement ? this.targetElement : this;
            var r = findPreviousFormItem(self,'url');
            r.onchange(r);
            self = null;
            r = null;
    }""" /* workaround for JENKINS-19124 */)
}

f.validateButton(title:_("Test Connection"), progress:_("Testing..."), method:"testConnection", with:"vsHost,allowUntrustedCertificate,credentialsId")
