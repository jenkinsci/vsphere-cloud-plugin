# Standalone ESXi hosts over SSH

Besides vCenter, which is reached through the vSphere API, the plugin can work with a
**standalone ESXi host over SSH**: it logs in to the host and runs `vim-cmd` there. This is for
ESXi hosts that have no vCenter, and whose own API either cannot be written to (as with the free
license) or is too old to use.

Which of the two a cloud uses is its **Connection type**, chosen in the form of the cloud, and
what the other settings are depends on it. A cloud that does not say is a vCenter, as always.

## What can be done this way

| Operation                                                    | ESXi over SSH |
|--------------------------------------------------------------|---------------|
| Find VMs by name, count them, read their hardware and state   | yes           |
| Power on, power off (also gracefully), suspend                | yes           |
| IP address of a VM (needs VMware Tools in it)                 | yes           |
| Take a snapshot                                               | yes           |
| Delete a VM                                                   | yes           |
| Clone and deploy VMs, reconfigure VMs, revert to / delete / rename a snapshot | not yet |
| Whatever needs vCenter: folders, clusters, templates, customization specs, distributed switches, choosing a host | no |

What is not available says so with a message, when it is used.

## Prepare the ESXi host

SSH is disabled on an ESXi host by default, and has to be enabled for this to work:

1. In the web interface of the host, go to **Host** &rarr; **Manage** &rarr; **Services**.
2. Select **TSM-SSH**.
3. In its **Actions** menu choose **Policy** &rarr; **Start and stop with host**, so that SSH is
   available again after the host has been restarted.
4. In the same menu choose **Start**, so that it is available now.

The user that Jenkins logs in as has to be allowed to log in over SSH and to run `vim-cmd`, which
normally means `root`.

## Set up the cloud

In the form of the cloud choose the **Connection type** "Standalone ESXi host over SSH", and enter:

* **vSphere Host**: the plain host name or IP address of the ESXi host, without `https://`.
* **SSH Port**: if it is not 22.
* **Credentials**: either kind of credential that can log in over SSH:
  * a *Username with password*. Some hosts only take a password when it is asked for by the
    *keyboard-interactive* method, which is tried when it is not taken as such.
  * an *SSH Username with private key*, with its passphrase if it has one.
* **Trust the host key**, and its **fingerprint**: see below.

The **Test Connection** button checks the whole of it, without changing anything: which host key
the host presents, whether that is trusted with these settings, whether the login works, and which
ESXi it is (such as `VMware ESXi 7.0.3 build-20036589`).

### Trusting the host key

So that it is the ESXi host that Jenkins talks to, and not something in between, its host key is
checked, as `ssh` does. There are three ways to say what to trust:

* **Only the host key with the fingerprint given** (the default, and the safest). Nothing is
  trusted until the fingerprint of the host's host key is entered. Press **Show host key** (it
  asks the host which key it presents, without logging in) or **Test Connection**, check that the
  fingerprint is that of the host (for example with
  `ssh-keygen -l -f /etc/ssh/ssh_host_rsa_key.pub` on the host), and enter it. It can be
  `SHA256:...` as `ssh-keygen -l` shows it, or an older MD5 one (`00:11:22:...`).
* **The host key seen first.** The host key that the host presents the first time is trusted and
  its fingerprint is remembered, in the same setting as above; from then on only that one is
  trusted, and a changed host key is refused, with both fingerprints in the message. It is only
  as safe as the first connection is. To trust a changed host key on purpose (a reinstalled host),
  clear the fingerprint.

  **This makes Jenkins save its configuration by itself** at the first connection, to keep the
  fingerprint: the configuration of the *folder*, for a cloud that is defined in a folder,
  otherwise the configuration of Jenkins. The form warns about it, and asks for a confirmation
  when this is chosen.
* **Any host key.** Not secure; for hosts in a network that is safe.

A fingerprint that is given is always required to match, whichever of these is chosen.

### Configuration as Code

```yaml
jenkins:
  clouds:
    - vSphere:
        vsDescription: "Standalone ESXi"
        vsConnectionConfig:
          vsHost: "esxi7.example.com"
          backend:
            esxiSsh:
              credentialsId: "esxi-ssh"          # a password or an SSH key credential
              port: 22
              hostKeyPolicy: FINGERPRINT         # or TRUST_FIRST_USE, or ACCEPT_ANY
              hostKeyFingerprint: "SHA256:..."
              connectTimeoutSeconds: 30
              commandTimeoutSeconds: 600
```

## The layout of the settings of a connection

The settings that are specific to the way of connecting are grouped under `backend` (a
`vCenter` or an `esxiSsh` group), next to the `vsHost` that they share. For vCenter this is
what used to be written flat in the connection configuration:

```yaml
# before
vsConnectionConfig:
  vsHost: "https://vcenter.example.com"
  credentialsId: "vcenter"
  allowUntrustedCertificate: true
  httpClientClassName: "ApacheHttpClient"

# now
vsConnectionConfig:
  vsHost: "https://vcenter.example.com"
  backend:
    vCenter:
      credentialsId: "vcenter"
      allowUntrustedCertificate: true
      httpClientClassName: "ApacheHttpClient"
```

**Nothing has to be changed for this**: the layout from before is still understood and is
converted.

* A saved configuration (`config.xml`) in the old layout is read as the `vCenter` group, and is
  saved in the new layout the next time Jenkins saves its configuration.
* Configuration as Code in the old layout is still accepted, as the settings of vCenter. What
  Configuration as Code *exports* is in the new layout, so that is also the way to convert a
  file: export the configuration, and use that. The old keys cannot be mixed with the settings of
  an ESXi host, which says so.
* The HTTP client is one setting for the whole plugin, as it was: it is the same for all the
  clouds that use vCenter.
* A vCenter that has only its host (and so only default settings) is exported as
  `backend: "vCenter"`; the HTTP client is not written any more unless it is set.
