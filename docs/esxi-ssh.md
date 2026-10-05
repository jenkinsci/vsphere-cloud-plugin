# Standalone ESXi hosts over SSH

Beside vCenter, which is reached through the vSphere SOAP API, the plugin can work with a
**standalone ESXi host over SSH**: it logs in to the host and runs `vim-cmd` there. This is for
ESXi hosts that have no vCenter, and whose own API either cannot be written to (as with the free
license) or is too old to use. Broadcom does still publish free editions without official support
nor advanced features such as vCenter, vModion, DRS, HA etc., but with a constrained vSphere Host
Client for Web-GUI and console/SSH logins for scripting, "ideal for home labs or dev/test
environments". As of 2026, [VMWare ESXi 8.0U3e](https://knowledge.broadcom.com/external/article/399823/vmware-esxi-80-update-3e-now-available-a.html)
version is available for download with a support site login. Older versions may also be available,
but at this time there seem to be no ESXi 9.x free versions published.

The Jenkins vSphere Cloud plugin offers limited and best-effort (as far as `vim-cmd`, `esxcli`
and `vmkfstools` activity, outputs and exit codes are stable across versions) support for ESXi
servers over SSH with a mix of official commands and direct `*.vmx`, `*.vmsd`, `*.vmdk` metadata
file editing, to offer a common Jenkins interface and data model to both implementations and add
some features, such as VM cloning from a snapshot, that are not directly supported by the free
ESXi version but are technically possible.

WARNING: As this code is based on educated guesses from a few available versions, and relies on
unsupported hacks to do its job, there are no guarantees that it won't eat your data or lose VMs.
You are strongly encouraged to first test this with a scratch instance of VMWare ESXi (which you
can and may install for free, also in a VM with at least SATA drivers and VMWare-emulated NIC
and video drivers). If you are using this for a Jenkins agent farm, it may be prudent to have
the ESXi server and its storage location (e.g. a dedicated directory shared over NFS from LAN
or physical host, if this ESXi is a VM itself) dedicated to this role, so any data loss is cheap
and easy to recover from (a few template VM backups would be it)!

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
| Clone and deploy VMs, linked or full ([see below](#cloning))   | yes           |
| Reconfigure CPUs, memory, annotation, extra configuration, network adapters; rename a VM ([see below](#reconfiguring)) | yes (VM powered off) |
| Convert a VM to a template and back, with a mark in the `.vmx` ([see below](#templates)) | yes (VM powered off) |
| Revert to a snapshot, delete one                              | yes           |
| Rename a snapshot (in the `.vmsd` file, VM powered off)       | yes           |
| Whatever needs vCenter: folders, clusters, customization specs, distributed switches, choosing a host, moving a VM to another host | no |

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

## Cloning

An ESXi host cannot clone a VM by itself, so the plugin does what the `esxi-linked-clone` scripts
do, on the files of the datastore: copies what makes the clone, writes its own `.vmx`, registers
it with the host, and starts it if asked to. Cloning and deploying a VM (also what the cloud
templates do) work this way, with the "master" VM as the source.

* A **linked clone** shares the data of its master. For each disk of the master it takes the
  newest snapshot disk that can be read (the small "delta" with the changes since the snapshot),
  copies that into the folder of the clone, and makes the copy a change of the master's disk by
  its parent; the large data is not copied. So **the master has to have a snapshot**, and the
  clones are made of the state it had then. A master that is **running** has its newest disk in
  use, which cannot be copied: the snapshot disk before it is used, so take a snapshot of the
  master, and, if it is to run, a second one, so that the first one stays as it is. A linked
  clone has to be on the datastore of its master. As with any linked clone, the master must not
  be deleted, nor its snapshots removed, while clones of it exist.
* A **full clone** copies the disks (thin provisioned, with `vmkfstools`), which takes time, as long as
  the copy takes: the time limit of a command (an advanced setting) may need to be raised for
  large disks. It may be on any datastore.
* The `.vmx` of the clone has its own name, and nothing of the identity of the master: UUIDs, MAC
  addresses (new ones are made when it starts) and guest information are not carried over. Where
  the clone is asked to have another size (CPUs, cores per socket, memory), or extra
  configuration parameters, they are put in it.
* The clone is made in a folder of its own, named after it, in the datastore. If anything goes
  wrong, what was made is removed again.

**Cloning a named snapshot**: a full clone can be made of any snapshot (the disks that it froze
are copied, with what they are changes of). A linked clone can only be made of the *newest*
snapshot: it shares the disk that the snapshot froze, and needs a change of it that has no changes
of its own, which only the newest snapshot has. The disks of a snapshot are read from the `.vmsd`
file of the master.

What a standalone host does not have is not available: a customization specification, choosing
a host (or a host selection mode) are refused where they are given, and a cluster, a VM folder or a resource pool is ignored, with a
note in the log. The name of a clone (and of the datastore) can have letters, digits, spaces and
`. _ # + = @ ( ) -` in it, and has to start with a letter or digit.

## Templates

The steps *Convert to template* and *Convert to VM* work, but **what a "template" is differs from
vCenter**. A standalone host has no kind of VM that is a template: it is a VM like any other, and
the plugin marks it as one by putting `template = "TRUE"` in its `.vmx` file (and takes the mark
out for *Convert to VM*).

* The mark is a convention of this plugin, **not enforced by the host**: the VM stays registered
  where it is, with its disks, snapshots and its place in the inventory, and can still be started
  from the web interface of the host, or by anything else that does not look at the mark. The
  plugin does not start a VM that has it (it says that the VM represents a template).
* As on vCenter, the VM has to be powered off (or the step is told to force that, and then it is).
* A VM deployed from a template is a normal VM: the mark is not copied. Deploying is a full copy
  of the disks, as it is for any master that has no snapshot ([see cloning](#cloning)).
* The resource pool and the cluster of *Convert to VM* are not used: there is no cluster, and the
  VM stays in the resource pool it is in.

## Snapshots

Snapshots are taken, reverted to and deleted with `vim-cmd` (the host knows them by number; the
plugin looks the number up by the name). A snapshot taken while the VM was running (with its
memory) powers the VM on when it is reverted to, as it does on vCenter. A snapshot is deleted
without its children, and its disks are consolidated by the host. `vim-cmd` has no command to
rename a snapshot, so that is done in the `.vmsd` file of the VM, where the host keeps the names
(the VM has to be powered off; the host is told to read the file again, and if it does not show the
new name afterwards, the file is put back as it was). (On an ESXi 7 host, asking `snapshot.remove` to remove the
children too was seen to leave them in place; the plugin never asks for that.)

## Reconfiguring

The reconfigure steps and the renaming of a VM change the `.vmx` file of the VM, and have the host
read it again. A running VM would write its own settings over the file, so **the VM has to be
powered off** (a running VM is refused, with a message saying so).

* **CPUs, cores per socket, memory, annotation, extra configuration parameters** (an empty value
  removes the parameter), and the **name** of the VM (the same restrictions as for the name of a
  clone).
* **Network adapters**: add (the first free slot, up to 10), edit (the MAC address, the port group)
  and remove. The port group has to be one of a standard switch of the host; if the host does not
  say which it has, the name is taken as it is. A MAC address that is set is a static one: the host
  only takes one in the range `00:50:56:00:00:00` to `00:50:56:3F:FF:FF` unless the VM also has
  `ethernet0.checkMACAddress = "FALSE"` (as an extra configuration parameter, for the adapter in question).
* **Not available**: disks (adding, growing, removing), reservations and limits of CPU and memory,
  distributed switches.

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
