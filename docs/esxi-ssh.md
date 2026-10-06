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

| Operation                                                     | ESXi over SSH |
|---------------------------------------------------------------|---------------|
| Find VMs by name, count them, read their hardware and state   | yes           |
| Power on, power off (also gracefully), suspend                | yes           |
| IP address of a VM (needs VMware Tools in it)                 | yes           |
| Take a snapshot                                               | yes           |
| Delete a VM                                                   | yes           |
| Clone and deploy VMs, linked or full ([see below](#cloning))  | yes           |
| Reconfigure CPUs, memory, reservations and limits, annotation, extra configuration, network adapters, disks and their controllers (SCSI, SATA, NVMe; IDE for disks); rename a VM ([see below](#reconfiguring)) | yes (VM powered off) |
| List datastores and resource pools, tell which ones hold a VM, tell whether a name is the host's own | yes |
| Convert a VM to a template and back, with a mark in the `.vmx` ([see below](#templates)) | yes (VM powered off) |
| Revert to a snapshot, delete one                              | yes           |
| Rename a snapshot (in the `.vmsd` file, VM powered off)       | yes           |
| Whatever needs vCenter: folders, clusters, customization specs, distributed switches, choosing a host, moving a VM to another host | no |

What is not available says so with a message, when it is used.

## Prepare the ESXi host

SSH is disabled on an ESXi host by default, and has to be enabled for this to work:

1. In the web-UI interface of the host, go to **Host** / **Manage** / **Services**.
2. Select **TSM-SSH**.
3. In its **Actions** menu (or right mouse button context menu on the line) choose
   **Policy** / **Start and stop with host**, so that SSH is available again after
   the host has been restarted.
4. In the same menu choose **Start**, so that it is available now.

The user that Jenkins logs in as has to be allowed to log in over SSH and to run
`vim-cmd`, `esxcli` and other commands, which normally means `root`.

To add a separate user account with similar (or constrained) privileges:

1. In the web-UI interface of the host, go to **Host** / **Manage** / **Security & users**.
2. Press **Add user** and enter at least the fields marked required: **User name**,
   **Password** and its confirmation, as well as **Enable shell access** checkbox.
3. In the main **Host** page open the **Actions** menu (or right mouse button
   context menu on the Host line in left Navigator panel) and select **Permissions**.
4. **Add user** (for a role on this host), enter the name and either select the
   individual permissions from the list below, or a predefined role from the
   drop-down to the right, e.g. "Administrator".
5. Press **Add user** button below to confirm, and **Close** the permissions window.

## Set up the cloud

In the form of the cloud choose the **Connection type** "Standalone ESXi host(s) over SSH", and enter:

* **vSphere Host**: the plain host name or IP address of the ESXi host, without `https://` (the form warns about
  that when the connection type is vCenter, not for this one).
  * Note: This Jenkins plugin supports file transfer operations e.g. for VM cloning
    between independent ESXi hosts which do not share a VMFS or NFS storage location.
    This requires the entered host name or IP address to be resolvable and accessible
    by the ESXi host machines doing such communications. The firewall permissions for
    outgoing (SSH Client) traffic can be persistently enabled by the administrator,
    or would be raised and shut by the plugin during transfers. Alternatively, the
    Jenkins controller that this plugin runs on may be used to relay information
    between ESXi hosts which can not communicate directly.
* **SSH Port**: if it is not 22.
* **Credentials**: either kind of credential that can log in over SSH:
  * a *Username with password*. Some hosts only take a password when it is asked for by the
    *keyboard-interactive* method, which is tried when it is not taken as such.
  * an *SSH Username with private key*, with its passphrase if it has one.
* **Fingerprint trust**, and the **Fingerprint** itself: see below. (The fingerprint is the short name of the
  SSH host key of the host, as `ssh-keygen -l` shows it; it is what is shown, entered and compared here.)

The **Test Connection** button checks the whole of it, without changing anything: which fingerprint
the host presents, whether that is trusted with these settings, whether the login works, and which
ESXi it is (such as `VMware ESXi 7.0.3 build-20036589`).

### Trusting a host by its fingerprint

So that it is the ESXi host that Jenkins talks to, and not something in between, the fingerprint of its
SSH host key is checked, as `ssh` does. There are three ways to say what to trust:

* **Only the fingerprint given** (the default, and the safest). Nothing is
  trusted until the fingerprint of the host is entered. Press **Show fingerprint** (it
  asks the host which one it presents, without logging in) or **Test Connection**, check that the
  fingerprint is that of the host (for example with
  `ssh-keygen -l -f /etc/ssh/ssh_host_rsa_key.pub` on the host), and enter it. It can be
  `SHA256:...` as `ssh-keygen -l` shows it, or an older MD5 one (`00:11:22:...`).
* **The fingerprint seen first.** The fingerprint that the host presents the first time is trusted and
  remembered, in the same setting as above; from then on only that one is
  trusted, and a changed one is refused, with both fingerprints in the message. It is only
  as safe as the first connection is. To trust a changed fingerprint on purpose (a reinstalled host),
  clear the setting.

  **This makes Jenkins save its configuration by itself** at the first connection, to keep the
  fingerprint: the configuration of the *folder*, for a cloud that is defined in a folder,
  otherwise the configuration of Jenkins. The form warns about it, and asks for a confirmation
  when this is chosen.
* **Any fingerprint.** Not secure; for hosts in a network that is safe.

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
a host (or a host selection mode) are refused where they are given (with an exception that is an `EsxiPlatformConstraint`, see below),
and a cluster or a VM folder is ignored, with a note in the log. The name of a clone (and of the datastore) can have letters, digits, spaces and
`. _ # + = @ ( ) -` in it, and has to start with a letter or digit.

### Resource pools

The *resource pool* of a clone (or deployment) is a resource pool of the host: the clone is
registered in it. One that the host does not have is made, as the `esxi-linked-clone` scripts did
(below the top pool, with expandable reservations and normal shares); the name has to be plain, as
for a clone. "Resources" or nothing means the top pool, which every host has. Pools are listed in
`/etc/vmware/hostd/pools.xml`, and made and removed with `vim-cmd hostsvc/rsrc/...`; the backend can
list them, find one, make one and remove one, and a VM can tell which one it is in (the host is
asked for `resourcePool` in the configuration of the VM, and one it does not name is the top pool).

### What is not there on a standalone host

What a standalone host has no counterpart for (folders, clusters, customization specifications,
distributed switches, choosing among hosts, moving a VM to another host) is refused with an
exception that implements `org.jenkinsci.plugins.vsphere.tools.esxi.EsxiPlatformConstraint`, so that
code can tell, with `e instanceof EsxiPlatformConstraint`, that it is the platform and not a missing
feature. There are two classes that do: `EsxiConstraintException` (a `VSphereException`) and
`EsxiConstraintUnsupportedOperationException` (an `UnsupportedOperationException`, for the objects
that stand in for the vSphere API). Anything else that is refused is an ordinary failure.

## Copying files between hosts: the relay

For hosts that do not share a datastore, files of a datastore folder can be copied from one host to
another **through the controller** (`EsxiRelay`): `tar` on the source writes the files, compressed
there, to a stream that Jenkins passes on, as it is, to `tar` on the target. It needs nothing of the
hosts but the SSH session that each of them already has: no trust between the hosts, no firewall to
open, nothing unencrypted. It is the slowest way, as the bytes make two trips, which is why they are
compressed on the source. It is the default way of [replicas](#replicas-of-a-master-on-a-host-that-does-not-see-it);
the two ways [below](#faster-ways-direct-copies-over-ssh-or-by-netcat) are faster.

* **Compression** is `pigz` (the default; it is on ESXi 7 and 8), `gzip`, `bzip2` or none. If a host
  does not have the one that is asked for, the next is used, ending with none, and the log says so.
* **Time**: there is no limit to how long a copy takes, as files can be large. A copy is given up
  on when **nothing has moved, in either direction, for the idle time**.
* **What is left behind**: the files are unpacked in a folder of their own next to where they are going
  (`.jenkins-incoming-...`), checked for their sizes, and only then moved in place; a copy that fails
  leaves nothing in the folder, and the folder is removed. A file that is there already is replaced.
* **Order and names**: the files are sent smallest first, checksum files (`.sha256`, `.md5`, ...) before all, as they
  are the likeliest to get across before a session is cut or a disk is full, so that what is cut short is the
  last and the biggest, and a comparison finds a file that is missing or short next to a checksum that is there.
  Each file is moved in place under a name of its own, `<name>.__WRITING__`, and renamed to `<name>` when it is
  there whole (after the sizes were checked); what is left of one that failed is removed. The same goes for the
  local copies of files that the host makes (`cp`, to `<name>.__WRITING__`, then `mv`) and for the files that are
  replaced (a `.vmx`, a `.vmsd`). A `.__WRITING__` file that you find was left by something that was stopped
  hard, and can be removed. What `vmkfstools` writes (the disks that it clones, exports or imports) is written by
  it under the final name, as it cannot be told another.
* **Time stamps** are carried over by `tar`, so that a file is as old on the target as on the source.
* Only plain names in folders of datastores are accepted.

### Faster ways: direct copies over SSH or by netcat

The setting **How copies go between hosts** (`transferMode`: `RELAY`, the default, `SSH_DIRECT` or `NETCAT`) has the
source host send to the target itself, so that the bytes travel once and the controller only waits. Everything
else is as with the relay: the compression, the idle time, the staging folder, the checks of the sizes (and, for
replicas, of the checksums), and a copy that fails leaves nothing behind. Measured between an ESXi 8.0.1 and an
ESXi 7.0.3 host, for 300 MB that do not compress: through the controller 34 s, over SSH 21 s, by netcat 25 s (the
compression is what takes the time, then).

The source reaches the target **at the address that is set for it here**, so that has to be one that the hosts
reach one another at, not only the controller. The hosts need `ssh` (or `nc`) and the tools of the relay; they
need not trust one another beforehand.

* **`SSH_DIRECT`** is encrypted. For each copy:
  * the source makes a key pair of its own in a folder in `/tmp` (an ECDSA one, as a host in FIPS mode, as ESXi
    is, cannot make others), which is removed after;
  * the controller adds the public key to `/etc/ssh/keys-<user>/authorized_keys` **on the target**, for the user
    that Jenkins logs in as, with `restrict,command="..."`: the key is good for one command only, unpacking the
    stream in the folder that the files are going to, with no terminal and no forwarding. The line is removed
    after, and so are the file and the folder, if they were not there. Lines that were there stay. (The folder and
    the file are made readable to all if the user is not `root`, as the SSH server reads them as that user. A
    copy that is stopped halfway leaves the line, which ends in `jenkins-xfer-...`, to be removed by hand.)
  * the source is given the **host key of the target**, which the controller read over the session that it trusts,
    in a file of its own, and is told to accept no other: a copy cannot be sent to a host that is not the target.
  * the firewall of the source must let it open SSH connections, which the `sshClient` ruleset does: it is
    turned on for as long as copies need it (if it was off), and off again by the last of them. If it was on, it
    stays on. A marker in `/tmp` tells a ruleset that a copy of ours that was stopped left on, from one that the
    administrator turned on.
* **`NETCAT`** is **not encrypted**: use it in a network that you trust, and for files that may be seen on it. The
  target runs `nc` listening on a port that is picked at random above 49151 and that nothing uses; the source
  connects to it. The firewall is opened for that port, on both hosts, and for the address of the source (when
  that is an IPv4 address; else for all) **only while the copy goes on**: the ruleset `jenkinsXfer`, with its own
  file `/etc/vmware/firewall/jenkins-xfer.xml`, shared by the copies that are going on at once on a host, with
  the rules of a pair of ports each, and turned off and removed by the last one. (A host keeps the settings of a
  ruleset of that name, such as the addresses that it is limited to, even after its file is gone, which is why the
  name is always the same; they are put back as they were.) Two copies do not use the same port: a second
  attempt for a port that a copy has says `Channel is still handling an earlier transfer`. A port that the host
  will not bind is given up for another. As `nc` does not time out on waiting for a connection, the controller
  looks for it (30 s), and ends the listener and the sender (by `kill`, found by the `nc` command) if none
  comes, or if nothing is written for the idle time. Whoever can reach the port while it is open can send the
  target a stream to unpack in the staging folder: moved in place only if its files are of the sizes that were
  expected, but not otherwise checked. Several Jenkins controllers using the same host at once do not know of
  one another's ports.

If a direct copy cannot be done (no `ssh` or `nc` on a host, a firewall in the way, a name that the source cannot
resolve), the copy fails, with the message of what failed: change the setting back to `RELAY` for the hosts that
cannot do it.

## Replicas of a master on a host that does not see it

A host can only clone a master whose files it can read. For a host that cannot (it has no datastore
in common with the master's), a **replica** of the master can be made on it, and clones made of
that. A replica is a VM of its own, always powered off:

* It has the disks of the master, **as they were at a snapshot** (or as they are now, if the master
  is not running), each as one thin disk, and **one snapshot**, `jenkins-replica-base`, which is what
  linked clones of it are made of. Being made of a snapshot of its own, a linked clone can be made of
  *any* snapshot of the master this way.
* **How a disk is copied depends on how much of it is written**, which the host says with `du -k`
  (the space that its files take), against the size that the disk presents in its descriptor. On the
  NFS datastore of the test hosts, the 4 GB disk of a VM that had only been set up took 1 KB, a 512 MB
  memory file 8.6 MB.
  * A disk that is **mostly written** (at least half) and is not a change of another disk is sent **as it
    is**: its descriptor and its extents, through the relay, with no extra pass. The extents are checked
    by their sizes (and by the compression, which checks itself), the small descriptor by its checksum.
  * A disk that is **mostly not written**, or is a **change of other disks** (a snapshot's), or of which
    that cannot be told, is **exported on the source host in a sparse format** (`vmkfstools -d
    2gbsparse`), which holds only what is written, flattening the disks it is a change of; the export is
    sent through the relay, its **checksums** (`cksum`) are compared on both hosts, and it is **imported
    as a thin disk** (`vmkfstools -d thin`).
  * On a file system that **compresses** (a ZFS dataset behind NFS), `du` shows what the server stores,
    which is less than has to be read: it is a guide to what is copied, not a promise. A mistaken choice
    costs time (or an extra pass), not correctness, as both ways are checked.
* A replica **never changes**. Its name is `jenkins-replica-<master>-<digest>`, the digest being of where
  the master is (its datastore's UUID and path), the snapshot, and the `CID` of each disk (which a disk
  file changes when it is written). A master that has changed gets a **new replica**, and the old one
  stays (for the clones that are made of it, which the check above protects too); remove the ones that are not wanted.
  A replica that is there is used again, without looking at the master's data, only at its disks' `CID`s.
* Where the replica is from is kept in its `.vmx`: `jenkins.replica.source`, `.state`, `.stamp` (the CIDs)
  and `.created`.
* Two builds that want the same replica make it once: the other waits, and says so in its log.
* What is made of a replica that fails (the folder, the VM, the export on the source) is removed.
* A replica is a copy of the data: it takes the space of what is written on the disks, on the datastore of
  the target host that has the most room (or the one asked for).
* **Room is checked first.** What is written on the disks (plus 5% and 64 MB) has to fit in the datastore of the
  target, and, for the disks that are exported, in that of the master, where the export is made, for a
  short time; if not, nothing is started, and the message says how much is needed and how much is free.
  If a host cannot say, the copy goes on, and the log says that the room could not be told.

## Linked clones and their master

A linked clone is a change of the disks of its master, which it names by their path; the host keeps
no list of the clones of a VM. Removing a snapshot of the master (or all of them) merges disks, which
changes or deletes the files that clones rely on, and so does deleting the master, deleting one of
its disks, or making one larger. A clone that is running keeps its parents locked, so the host
refuses; **a clone that is not running is broken without a word.**

So the plugin looks before it does any of those: it finds the VMs that have a disk with a disk of the
master as a parent (anywhere up the chain), and **refuses to go on while there are any**, naming
them. Delete the clones first. (The same goes for a master on its own host, with no cluster.)

Where it looks, on each host that can be asked (all the hosts of a cluster that are up):

1. the VMs that are registered with the host;
2. and, in case they are not registered anywhere that can be asked, every `.vmx` file in the folders
   of the datastores that the host has, so that a clone that is registered with a host that is down
   is found all the same **if it is on a datastore that a host which is up has too** (a shared one).

What it **cannot** see: a clone whose definition is on a datastore that no host that can be asked has,
such as the local datastore of a host that is down, or one that is registered nowhere and is
elsewhere on a datastore (not in a folder of its own at the top). The message that refuses says which
hosts could not be asked. And it looks at VMs and disks as the hosts say they are, so a
clone that was copied away by hand, with its parents' paths changed, or a master whose disks are
used by a clone through some other name of the datastore, is not recognised.

The check can be turned off by starting Jenkins with
`-Dorg.jenkinsci.plugins.vsphere.tools.esxi.protectLinkedCloneParents=false`.

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
* **Reservations, limits and shares** of CPU (MHz) and memory (MB): the `sched.cpu.*` and
  `sched.mem.*` settings. The CPU limit of the reconfigure CPU step is a reservation.
* **Disks and their controllers**, which `vmkfstools` manages (it makes disks, makes them larger and
  deletes them; it is not only for datastores). A disk is added in a folder of a datastore (made
  if needed), thick (lazily zeroed) unless the request says thin, and the datastore is one that
  the host lists; an existing disk file can be attached instead of making one. A disk can be made
  larger but not smaller. A disk that has snapshots is neither made larger nor deleted: remove the
  snapshots first. Removing a disk deletes its files, unless the request only detaches it. The
  files are made (or enlarged) before the `.vmx` is changed, and those that were made are deleted
  again if that fails; the files of a removed disk are deleted after it has been changed. A SCSI
  controller (LSI Logic, LSI Logic SAS, BusLogic, VMware Paravirtual) can be added, or removed if no
  disk is on it, and so can a SATA (AHCI) or NVMe controller. Disks go on any of the four buses
  (`scsiN`, `ideN`, `sataN`, `nvmeN`), with the units that the bus has (SCSI 0 to 15 without 7, IDE
  0 and 1, SATA 0 to 29, NVMe 0 to 14). A VM always has its two IDE controllers: they cannot be added
  or removed, only disks put on them, and the host does not power on a VM with an IDE slave (unit 1) and
  no master (unit 0), so that is refused. The disk step of the plugin has a **Disk bus** (SCSI, the default, IDE, SATA or NVMe), on vCenter as on
  a host: it uses the first controller of that kind with a free unit, and adds a SCSI, SATA or NVMe controller by
  itself when there is none (it cannot for IDE, which has two controllers of two units).
  Mind that `IDE(1:0)`, and often `IDE(0:0)` too, is taken by a CD-ROM (an ISO image, emulation or
  passthrough of a drive) in many VMs, which counts as a device on that unit: the step then finds IDE full or
  puts the disk on the unit that is free. Where a VM needs more disks than IDE has room for, use SCSI, SATA or
  NVMe, which most guests can also attach the CD-ROM to (SATA is common), or have no CD-ROM at all. Disks are found by
  monikers such as `SATA(0:1)` or `NVME(0:0)` too.
* **Not available**: distributed switches, and devices other than network adapters, disks and their
  controllers.
* There is no command to consolidate disks apart from removing snapshots, which consolidates what
  they held; asking for a consolidation is therefore taken as done.

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

## Keeping the SSH connections: the connection pool

The settings of the connection pool of a cloud (**Use vSphere connection pool**, and its health check, session
age, uses and idle timeout, in the advanced settings of the cloud) apply to this connection type too: the pool then
keeps one SSH session to the host (to each of the hosts, for several) instead of logging in for every operation.
A login to an ESXi host takes 0.6 to 0.75 seconds (measured on ESXi 7.0.3 and 8.0.1, over a LAN), and a step of a job
makes one, so a pool saves that for each step, for each host of the cloud.

* The pool checks a session that was not used for 10 seconds when it hands it out (it runs a command that does nothing),
  and logs in again if it does not answer: a session over SSH may have been ended by the host, a firewall or a restart
  without telling, which is not so with a vCenter session. Without this a cloud with one host would fail until the
  health check found it, if there is one. With several hosts, a host that has gone away is left out and tried again.
* **Idle timeout** is the one to set (say 300 seconds): the sessions are not kept for a Jenkins that has nothing to do.
  The **health check** is not needed for the above, but finds a dead session before it is asked for. **Max age** and
  **max uses** are for when you want the sessions renewed anyway.
* The settings are those of the cloud, for all its hosts, and are not saved with the connection type: they are not
  shown only for vCenter.

## Several ESXi hosts: a poor man's cluster

A cloud can use more than one ESXi host. The `vsHost` is the first one, and the others are listed
as **More ESXi hosts** (`additionalHosts` in Configuration as Code). What a host does not say is
that of the first: the port, the credentials, how its fingerprint is trusted. Its fingerprint is always
its own, and so is the one that "the fingerprint seen first" remembers (the configuration is saved for
it the same way).

In the form, **Show fingerprint** of such a host works with the host alone. **Test Connection** of a host that has
no credentials of its own cannot try the login, as its form cannot see the credentials of the first host, which
the host uses then: it checks that the host answers and shows its fingerprint, and says that the login was not tried.
Choose credentials for that host to try the login as well.

```yaml
vsConnectionConfig:
  vsHost: "esxi-a.example.com"
  backend:
    esxiSsh:
      credentialsId: "esxi-ssh"
      additionalHosts:
        - host: "esxi-b.example.com"
        - host: "esxi-c.example.com"
          port: 2200
          credentialsId: "esxi-c"
          hostKeyPolicy: ACCEPT_ANY
```

This is **not vSphere's DRS, nor vMotion**: the hosts are independent, the plugin does not move VMs,
and the load of a host is not measured. What it does:

* **VMs are looked up on all the hosts** that can be reached, and whatever is done to a VM (power,
  snapshots, reconfiguring, deleting, ...) is done by the host it is registered on. A name that is
  registered on more than one host is found on the first one, as the hosts are listed.
* **A clone is made on one of the hosts that can see the files of its master**, which they can if they
  have a datastore in common: an NFS datastore, or VMFS on shared storage. The volume is the same one
  if it has the same **UUID** (an NFS share that is mounted from the same server and path gets the same
  one on each host), whatever each host labels it; the clone is made through the label of the host that
  makes it. The clone's files are written there, the clone is
  registered with the host that made it, and the master need not be registered with that host: the
  host reads it from the shared datastore. A linked clone shares the disks of the master by their
  path on the datastore (by the UUID that the datastore really has), so the datastore has to be the same one.
* **Which host?** If the *host* of the step is given (as it is configured, or the name the host
  calls itself by), that one, if it can be used. Otherwise, of the hosts that can see the master (and
  the datastore the clone is asked to be on), and are in the list of *host selection candidates*, if there
  is one: by the **host selection mode**:
  * none, or `FEWEST_RUNNING_VMS`: the host with the fewest VMs that are on, then the fewest that
    are registered, then the first as configured;
  * `LEAST_LOADED`: the hosts are ranked by what they say is used of their CPU and memory
    (`vim-cmd hostsvc/hostsummary`, whose `quickStats` are a few seconds old), by the weights of the host
    selection options, as the hosts of a vCenter cluster are: by default the lower of free CPU and
    free memory, as a share of what the host has. A host that is in maintenance mode is not used, and
    the options to require enough cores, RAM or free RAM for the VM (the size of the master, or the one
    that was announced) drop the hosts that cannot hold it. Where no host says how busy it is, the
    count of VMs that are on decides;
  * `DRS_RECOMMENDED`: a standalone host has no DRS, so it is ranked as `LEAST_LOADED`;
  * `NONE`: the clone stays with the host of the master.
* **A host that cannot be reached is left out**, and tried again after half a minute, or when the
  connection is checked (the connection pool does that each time it hands the connection out).
  The cluster is up as long as one host is. All the sessions are part of the one connection that the
  pool keeps for a cloud, so a restart of that connection (its age, its number of uses, a change of
  configuration) restarts them all. A master that is registered only on a host that is down is not
  found (the clone fails as it does for a VM that does not exist), though the hosts that are up could
  see its files.
* **Datastores** are those of all the hosts; one that several have under the same name is listed once.
* **Hosts of different versions.** A host takes a VM of a virtual hardware version that it does not have for
  invalid: it registers it, and does not list it (the clone of a master from ESXi 8, hardware version 20, on an
  ESXi 7.0.3 host, which has 19 at most, was seen so). A clone or replica that is made for a host is therefore given
  the newest version that host has (from its version, by a table of what VMware documents: ESXi 6.0 11, 6.5 13,
  6.7 14/15, 7.0 17 to 19, 8.0 20/21), if the master's is newer, and the log says so; a host whose version is not in
  the table is not changed. A guest mostly does not mind, but it is a change of the hardware of the copy: mind it for
  a guest that uses what the newer version added.
* **A full clone** (on any host) is not started if the datastore has less room than what is written on the disks of its
  master (plus 5% and 64 MB): it would fail halfway, after the copy has taken its time. A linked clone is a small
  delta, and is not checked.

**Hosts that do not share a datastore with the master** can be used too, with the setting
**Make replicas of masters** (`replicateMasters`, off by default so that nothing is copied that was not
asked for): a clone that is to be made on such a host, one that is asked for or that the host selection
picks, is made of a **replica** of the master that is made on that host the first time ([see
replicas](#replicas-of-a-master-on-a-host-that-does-not-see-it)). The replica is made through the controller (or [directly](#faster-ways-direct-copies-over-ssh-or-by-netcat), if
`transferMode` says so), with the
compression that is set (`relayCompression`: `PIGZ`, the default, `GZIP`, `BZIP2` or `NONE`) and no time limit, only
`transferIdleSeconds` (300) of nothing moving. Host selection does not weigh the cost of making a replica: to keep
clones of a big master off hosts that have none yet, name the host or the candidates.

What it does not do: moving a VM to another host (cold migration is possible by hand: power it off,
unregister it on one host, register its `.vmx` on the other, from the same datastore), or
keeping replicas up to date (a master that has changed gets a new replica, and the old ones stay until
they are removed).

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
