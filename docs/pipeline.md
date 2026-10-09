# Using the vSphere plugin in pipelines

All operations available as freestyle build steps are also available as pipeline
steps.  Pipeline usage is in some ways more capable than the freestyle UI:
the pipeline DSL can express the full set of parameters that the plugin supports,
including parameters that the legacy freestyle job UI does not expose or has not
kept up with.

## Step syntax

The plugin registers a dedicated pipeline step called `vSphere` that can be used
in any scripted or declarative pipeline:

```groovy
vSphere(
    serverName: 'my-vcenter',
    buildStep: [$class: 'Clone',
                sourceName: 'linux-template',
                clone: 'build-vm-01',
                linkedClone: true,
                cluster: 'my-cluster',
                resourcePool: 'Resources',
                datastore: 'my-datastore',
                powerOn: true]
)
```

The `serverName` value must match the display name of a vSphere cloud configured
in Jenkins (see [jenkins-configuration.md](jenkins-configuration.md)).
It can also be set to the literal string `${VSPHERE_CLOUD_NAME}` to pick the
cloud from an environment variable at runtime.

### `step([$class: ...])` - the lower-level form

The same operation can be written using Jenkins' generic `step` wrapper:

```groovy
step([$class: 'VSphereBuildStepContainer',
      serverName: 'my-vcenter',
      buildStep: [$class: 'Clone', ...]])
```

**The `step([$class: ...])` form currently exposes more parameters than the
named `vSphere` function does.**

If you find that a parameter you need is not accepted by the `vSphere(...)` call,
try the `step` form instead.

PRs that extend the `vSphere` step to cover the full parameter surface are
welcome.

### Environment variable expansion

All string parameters support Jenkins environment variable expansion.
For example:

```groovy
vSphere(serverName: 'my-vcenter',
        buildStep: [$class: 'Clone',
                    sourceName: '${TEMPLATE_NAME}',
                    clone: 'build-${BUILD_NUMBER}',
                    ...])
```

## Environment variables set by steps

The following steps set the `VSPHERE_IP` environment variable to the IP address
of the target VM, provided the VM is powered on and an IP address becomes
available within `timeoutInSeconds`:

| Step | Condition |
|------|-----------|
| `Clone` | `powerOn: true` and timeout > 0 |
| `Deploy` | `powerOn: true` and timeout > 0 |
| `PowerOn` | always (when IP is obtained) |
| `ExposeGuestInfo` | when `waitForIp4: true` |

If no IP address arrives in time, `Clone`, `Deploy` and `PowerOn` only log a warning and carry on without
`VSPHERE_IP` being set, unless they are given `failOnNoAddress: true`, which fails the step.

`ExposeGuestInfo` additionally sets further environment variables named
`<envVariablePrefix>_<key>` for each guest info property read from the VM.

## Step reference

### Clone VM from template or VM

Clones an existing template or VM to a new VM. Example:

```groovy
buildStep: [$class: 'Clone',
            sourceName: 'linux-template',  // (required) source template or VM name
            clone: 'new-vm',               // (required) name for the new VM
            cluster: 'my-cluster',         // (required) vCenter cluster
            resourcePool: 'Resources',     // resource pool (use 'Resources' if none defined)
            datastore: 'my-datastore',     // datastore for the new VM (optional)
            folder: '',                    // vSphere folder path (optional)
            linkedClone: false,            // create a linked clone (requires a snapshot)
            powerOn: false,                // power on after cloning
            timeoutInSeconds: 60,          // seconds to wait for IP after power-on (0 = don't wait)
            failOnNoAddress: false,        // fail the step if no IP address arrives in time (default: only warn)
            customizationSpec: '',         // guest OS customization spec name (optional)
            useCurrentSnapshot: null,      // true = clone from current snapshot; false = don't use snapshot
            namedSnapshot: '',             // clone from this specific named snapshot (optional)
            extraConfigParameters: [:],    // extra VMX key-value pairs to set on the new VM (optional)
            cpuCores: '',                  // (optional) create the VM with this many vCPUs, in one step; blank keeps the source's
            coresPerSocket: '',            // (optional) cores per socket; blank keeps the source's
            cpuLimitMHz: '',               // (optional) CPU reservation in MHz; blank means none
            memorySize: '',                // (optional) create the VM with this much memory (MB); blank keeps the source's
            host: '',                      // (optional) pin the clone to this specific ESXi host; wins over hostSelectionMode
            hostSelectionMode: '',         // (optional) '', 'NONE', 'LEAST_LOADED', or 'DRS_RECOMMENDED' - see below
            hostSelectionCandidates: [],            // (optional) allow-list restricting hostSelectionMode's candidates
            hostSelectionRequireCores: false,       // (optional) true/false overrides the cloud's default; omit to inherit it. Skips hosts with fewer physical cores than the VM has vCPUs
            hostSelectionRequireMemory: false,      // (optional) true/false overrides the cloud's default; omit to inherit it. Skips hosts with less physical RAM than the VM is configured with
            hostSelectionRequireAvailableMemory: false,  // (optional) true/false overrides the cloud's default; omit to inherit it. Skips hosts without the VM's memory size free right now
            hostWeightFreeCpuMhz: '',       // (optional) host ranking weights for this call: all blank = use the cloud's;
            hostWeightFreeCpuPercent: '',   //   if any is set they replace the cloud's as a whole (blank = 0)
            hostWeightFreeMemoryMB: '',
            hostWeightFreeMemoryPercent: '',
            hostSelectionFolderFollowsHost: false  // (optional) true/false overrides the cloud's default; omit to inherit it. With no folder given, put the clone in its source's folder with the source's host name replaced by the clone's host
           ]
```

The `datastore` option can be useful if your templates live on different storage (slower/cheaper) than production VMs, so run-time instances should not appear "near" their origin.

`host`, `hostSelectionMode` and `hostSelectionCandidates` are all optional. Left blank,
`hostSelectionMode`/`hostSelectionCandidates` **inherit whatever default is configured on
the vSphere Cloud itself** (see
["vSphere Cloud Configuration"](jenkins-configuration.md#vsphere-cloud-configuration));
if the cloud has no default either, this is unchanged legacy behaviour (vCenter's own
default placement). See
["Controlling which ESXi host a clone lands on"](jenkins-configuration.md#controlling-which-esxi-host-a-clone-lands-on)
for the full explanation of each mode and when to prefer one over another (it mostly
comes down to whether your vSphere edition/license has DRS, and whether the account
Jenkins connects with can write to every host in the cluster).

Set `hostSelectionMode: 'NONE'` to explicitly disable host selection for just this step,
even if the cloud has a default mode configured - the cloud-wide default only applies
when this field is left blank, not set to `'NONE'`.

`hostSelectionCandidates` takes a list (`['esx01.example.com', 'esx02.example.com']`). If
you'd rather build a comma-separated string, set `hostSelectionCandidatesAsString` instead
(`'esx01.example.com, esx02.example.com'`) - use only one of the two; both end up stored
the same way. Leaving both blank inherits the cloud's default candidate list; setting
`hostSelectionCandidatesAsString: ','` (a single comma) explicitly overrides to "no
restriction" for just this step, the same way `'NONE'` does for the mode.

The `useCurrentSnapshot` and `namedSnapshot` are mutually exclusive.

- If neither is specified, the current snapshot is used automatically (as expected per legacy behavior); at least one snapshot of the original VM/template snapshot must exist.
  * when `linkedClone: false` -- a "deep clone" is created (copying and consolidating all disk images so the new VM is fully standalone)
  * when `linkedClone: true` -- a "shallow clone" is creates, where the current snapshot of the origin is used as the base for new VM (and such snapshot should not be removed while any clones depend on it)
- When `useCurrentSnapshot=false` is explicit (and no `namedSnapshot` nor `linkedClone` options are passed), it copies the current state of the original VM/template (also tested successfully with a source VM that had no snapshots)
- When both `useCurrentSnapshot=false` and `linkedClone=true` (and no `namedSnapshot` is passed), it fails (as expected)
- When both `useCurrentSnapshot=true` is explicit and a `namedSnapshot` is passed, it fails (as expected)

`namedSnapshot` and `extraConfigParameters` were added in
[PR #137](https://github.com/jenkinsci/vsphere-cloud-plugin/pull/137);
you can see some examples of pipeline inputs and logged outputs in that ticket.

`extraConfigParameters` values are subject to environment variable expansion.

---

### Deploy VM from template

Creates a VM from a template that has at least one snapshot.
The new VM lands in the same folder and storage as the template.

```groovy
buildStep: [$class: 'Deploy',
            template: 'linux-template',  // (required) template name
            clone: 'new-vm',             // (required) name for the new VM
            cluster: 'my-cluster',
            resourcePool: 'Resources',
            datastore: 'my-datastore',
            folder: '',
            linkedClone: false,
            powerOn: false,
            timeoutInSeconds: 60,
            failOnNoAddress: false,      // fail the step if no IP address arrives in time (default: only warn)
            customizationSpec: '',
            cpuCores: '',                // (optional) same meaning as on the Clone step
            coresPerSocket: '',          // (optional) same meaning as on the Clone step
            cpuLimitMHz: '',             // (optional) same meaning as on the Clone step
            memorySize: '',              // (optional) same meaning as on the Clone step
            host: '',                    // (optional) same meaning as on the Clone step
            hostSelectionMode: '',        // (optional) '', 'LEAST_LOADED', or 'DRS_RECOMMENDED'
            hostSelectionCandidates: [],           // (optional) allow-list, or use hostSelectionCandidatesAsString for a CSV string
            hostSelectionRequireCores: false,      // (optional) same meaning as on the Clone step
            hostSelectionRequireMemory: false      // (optional) same meaning as on the Clone step
           ]
```

---

### Power-On / Resume VM

Powers on a stopped or suspended VM and waits for its IP address.

```groovy
buildStep: [$class: 'PowerOn',
            vm: 'my-vm',              // (required) VM name
            timeoutInSeconds: 60,     // (required) max seconds to wait for IP (max 3600)
            failOnNoAddress: false    // fail the step if no IP address arrives in time (default: only warn)
           ]
```

---

### Power-Off VM

Powers off a running VM.

```groovy
buildStep: [$class: 'PowerOff',
            vm: 'my-vm',                    // (required)
            evenIfSuspended: false,          // shut down even when VM is suspended
            shutdownGracefully: false,       // attempt graceful shutdown via VMware Tools
            ignoreIfNotExists: false,        // succeed silently if VM is not found
            gracefulShutdownTimeout: 180     // seconds to wait for graceful shutdown
           ]
```

---

### Suspend VM

```groovy
buildStep: [$class: 'SuspendVm',
            vm: 'my-vm'   // (required)
           ]
```

---

### Delete VM

Deletes a VM permanently.
Templates are not deleted by this step.

```groovy
buildStep: [$class: 'Delete',
            vm: 'my-vm',          // (required)
            failOnNoExist: false  // fail the build if the VM is not found
           ]
```

**WARNING: this is irreversible and requires no confirmation.**

---

### Convert VM to Template

Marks a powered-off VM as a template.

```groovy
buildStep: [$class: 'ConvertToTemplate',
            vm: 'my-vm',  // (required)
            force: false  // power off the VM first if it is still running
           ]
```

---

### Convert Template to VM

Converts a template back into a VM.

```groovy
buildStep: [$class: 'ConvertToVm',
            template: 'linux-template',  // (required) template name
            resourcePool: 'Resources',
            cluster: 'my-cluster'
           ]
```

---

### Rename VM or template

```groovy
buildStep: [$class: 'Rename',
            oldName: 'current-name',  // (required)
            newName: 'new-name'       // (required)
           ]
```

---

### Take Snapshot

```groovy
buildStep: [$class: 'TakeSnapshot',
            vm: 'my-vm',                  // (required)
            snapshotName: 'before-patch', // (required)
            description: '',
            includeMemory: false
           ]
```

---

### Revert to Snapshot

```groovy
buildStep: [$class: 'RevertToSnapshot',
            vm: 'my-vm',                  // (required)
            snapshotName: 'before-patch'  // (required)
           ]
```

---

### Rename Snapshot

```groovy
buildStep: [$class: 'RenameSnapshot',
            vm: 'my-vm',
            oldName: 'before-patch',
            newName: 'patched-2024-01',
            newDescription: '',
            failOnNoExist: true  // default; set false to succeed when the snapshot is absent
           ]
```

---

### Delete Snapshot

```groovy
buildStep: [$class: 'DeleteSnapshot',
            vm: 'my-vm',
            snapshotName: 'before-patch',
            consolidate: false,    // consolidate all VM disks after deletion
            failOnNoExist: false
           ]
```

**WARNING: this is irreversible and requires no confirmation.**

---

### Expose Guest Info

Reads VMware guest info properties from the VM and exposes them as environment
variables in the build.

```groovy
buildStep: [$class: 'ExposeGuestInfo',
            vm: 'my-vm',
            envVariablePrefix: 'VSPHERE',  // variables are named <prefix>_<key>
            waitForIp4: false              // wait until an IPv4 address is assigned
           ]
```

---

### Reconfigure VM

Selectively reconfigures a VM.
Multiple reconfiguration sub-steps can be combined in a single call.

Some operations which act on physical instances of a resource (network adapters, disks) accept a `deviceLabel` or `deviceNumber` option to disambiguate the request, and a `deviceAction` (`ADD`, `REMOVE`, `EDIT`) option. Operations which just change the amount of identical resource units (memory, processors) or metadata (annotations) just accept named options with corresponding new values to be set.

* For Network Adapters, the `deviceLabel` can be also seen in vCenter UI, e.g. "Network adapter 1" for the first attached NIC. It is not known at this time whether these labels can be assigned by the sysadmin in any manner. The `deviceNumber` allows to pass the (best-effort) position of that NIC in the list of network adapters vCenter itself returns for the VM -- not a raw PCI slot number, which is shared with unrelated devices (storage/USB/video controllers etc.) and so that would not correspond to "the Nth NIC". The `deviceNumber` is **one-based** (`'1'` is the first NIC), likely matching how vSphere itself numbers things in UI labels like "Network adapter 1" -- it is NOT a zero-based array index. For any operations dealing with a specific NIC, inside or outside the VM, it is recommended to use the MAC address rather than device number or name.
* For Disks, the `deviceLabel` is typically derived from the disk image file name, e.g. `[LUN1] kube15/kube15_1.vmdk` can use the label `kube15_1`. This may be in fact clumsy, as file name suffixes may depend on amount of disks and their snapshot history, or assigned by the sysadmin (`boot-disk.vmdk` via vCenter UI or other means). The `deviceLabel` can also match a vCenter-assigned UI label like "Hard disk 1", or a controller moniker exactly as vSphere itself displays it, e.g. `SCSI(0:2)` or `IDE(1:0)` (controller bus number : unit number, both zero-based -- this is vSphere's own hardware addressing, unrelated to `deviceNumber` below) -- useful to target a disk on IDE, or on a specific one of several SCSI controllers, without depending on file naming at all. The `deviceNumber` alternative is simpler still: it is the position of the disk in the list of disks vCenter itself returns for the VM (not an address on any particular controller). The `deviceNumber` is **one-based**, likely matching how vSphere itself numbers things in UI labels like "Hard disk 1" -- so `deviceNumber: '1'` always means "the first disk", `'2'` the second, and so on, regardless of which controller(s) they are attached to.

```groovy
buildStep: [$class: 'Reconfigure',
            vm: 'my-vm',
            reconfigureSteps: [
                [$class: 'ReconfigureCpu',
                 cpuCores: '4',
                 coresPerSocket: '2',
                 cpuLimitMHz: '2000'], // optional CPU reservation in MHz; omit for no reservation
                [$class: 'ReconfigureMemory',
                 memorySize: '8192'],        // megabytes
                [$class: 'ReconfigureDisk',
                 diskSize: '50',             // gigabytes; adds a new disk named "my-vm_1" by default
                 datastore: 'my-datastore'],
                [$class: 'ReconfigureDisk',
                 deviceLabel: 'kube15_data', // optional; names the new disk's file instead of the default
                                             // "<vm>_<N>" scheme (fails if that name is already taken)
                 diskSize: '50',
                 datastore: 'my-datastore'],
                [$class: 'ReconfigureDisk',
                 deviceAction: 'EDIT',       // ADD (default), EDIT, or REMOVE
                 deviceLabel: 'kube15_0',    // vSphere device label ("Hard disk 1") or disk file base name;
                                             // may be omitted for EDIT if the VM has only one disk,
                                             // but is always required for REMOVE
                                             // NOTE: you should subsequently follow your OS procedures
                                             // to take advantage of the added disk space
                 diskSize: '200'],           // gigabytes; must be >= the disk's current size
                [$class: 'ReconfigureDisk',
                 deviceAction: 'EDIT',
                 deviceNumber: '1',          // alternative to deviceLabel (mutually exclusive); ONE-based
                                             // ('1' = first disk, likely matching "Hard disk 1" in the UI)
                                             // position in vCenter's own list of the VM's disks (not an
                                             // address on any particular controller)
                 diskSize: '200'],
                [$class: 'ReconfigureDisk',
                 deviceAction: 'EDIT',
                 deviceLabel: 'SCSI(1:2)',  // controller moniker: pins an exact SCSI/IDE bus:unit address,
                                             // exactly as vSphere itself displays it, e.g. for a VM with
                                             // several SCSI controllers or an IDE-attached disk
                 diskSize: '200'],
                [$class: 'ReconfigureDisk',
                 deviceAction: 'REMOVE',     // detaches the disk AND deletes its backing file (DESTRUCTIVE)
                 deviceLabel: 'kube15_data'],
                [$class: 'ReconfigureNetworkAdapters',
                 deviceAction: 'EDIT',       // ADD, EDIT, or REMOVE
                 deviceLabel: 'Network adapter 1',
                 // deviceNumber: '1',       // alternative to deviceLabel (mutually exclusive); ONE-based
                                             // ('1' = first NIC, likely matching "Network adapter 1" in the UI)
                                             // index among the VM's network adapters only (not a raw
                                             // PCI slot number); only valid for EDIT/REMOVE, not ADD
                 macAddress: '',
                 standardSwitch: true,
                 portGroup: 'VM Network',
                 distributedSwitch: false,
                 distributedPortGroup: '',
                 distributedPortId: ''],
                [$class: 'ReconfigureAnnotation',
                 annotation: 'built by Jenkins',
                 append: false]
            ]
           ]
```

## Examples

### Clone from a named snapshot

Based on example originally posted in [PR #137](https://github.com/jenkinsci/vsphere-cloud-plugin/pull/137):

```groovy
vSphere(
    serverName: 'my-vcenter',
    buildStep: [$class: 'Clone',
                sourceName: 'linux-kube-template',
                clone: 'kube0',
                cluster: 'my-cluster',
                resourcePool: 'Resources',
                datastore: 'my-datastore',
                linkedClone: true,
                namedSnapshot: 'before K8s install',
                extraConfigParameters: ['guestinfo.Foo': 'BAR'],
                powerOn: true,
                timeoutInSeconds: 120]
)
echo "VM IP: ${env.VSPHERE_IP}"
```

### Spread clones across a cluster instead of piling onto one host

Without any of the fields below, every clone lands wherever vCenter's own default
placement decides - in practice, often the same host the template is registered on.
The `hostSelectionMode` field lets the plugin (or vCenter's own DRS) spread clones out
instead.

```groovy
// No DRS license needed: the plugin ranks candidate hosts by current CPU/memory
// usage itself and picks the least loaded one.
vSphere(
    serverName: 'my-vcenter',
    buildStep: [$class: 'Clone',
                sourceName: 'linux-template',
                clone: "build-${env.BUILD_NUMBER}",
                cluster: 'my-cluster',
                resourcePool: 'Resources',
                hostSelectionMode: 'LEAST_LOADED',
                // Only consider hosts this service account can actually provision on.
                // A list, or a comma-separated string via hostSelectionCandidatesAsString,
                // both work equally well:
                hostSelectionCandidates: ['esx01.example.com', 'esx02.example.com', 'esx03.example.com'],
                // hostSelectionCandidatesAsString: 'esx01.example.com, esx02.example.com, esx03.example.com',
                powerOn: true]
)
```

```groovy
// If your cluster has DRS enabled and licensed (vSphere Enterprise Plus or
// equivalent), you can defer to vCenter's own placement recommendation instead,
// which also takes affinity/anti-affinity/HA/storage policies into account:
vSphere(
    serverName: 'my-vcenter',
    buildStep: [$class: 'Clone',
                sourceName: 'linux-template',
                clone: "build-${env.BUILD_NUMBER}",
                cluster: 'my-cluster',
                resourcePool: 'Resources',
                hostSelectionMode: 'DRS_RECOMMENDED',
                hostSelectionCandidates: ['esx01.example.com', 'esx02.example.com', 'esx03.example.com'],
                powerOn: true]
)
// If DRS is unavailable/unlicensed/disabled on the cluster, this automatically
// falls back to the same least-loaded-host behaviour as above (a warning is
// logged), rather than failing the build.
```

```groovy
// Or just pin every clone to one specific host, e.g. for a small/test lab:
vSphere(
    serverName: 'my-vcenter',
    buildStep: [$class: 'Clone',
                sourceName: 'linux-template',
                clone: "build-${env.BUILD_NUMBER}",
                cluster: 'my-cluster',
                resourcePool: 'Resources',
                host: 'esx-lab-01.example.com',
                powerOn: true]
)
```

```groovy
// If most pipelines against 'my-vcenter' should use the same placement policy, set it
// once on the vSphere Cloud itself (Jenkins "Configure System" -> Advanced...) instead
// of repeating hostSelectionMode/hostSelectionCandidates on every call. A step can then
// leave both blank to inherit that cloud-wide default:
vSphere(
    serverName: 'my-vcenter',
    buildStep: [$class: 'Clone',
                sourceName: 'linux-template',
                clone: "build-${env.BUILD_NUMBER}",
                cluster: 'my-cluster',
                resourcePool: 'Resources',
                // hostSelectionMode and hostSelectionCandidates both left unset here:
                // this step inherits whatever the cloud has configured as its default.
                powerOn: true]
)

// ...and a step that needs to keep the old, unrestricted placement behaviour even
// though the cloud has a default configured can opt out explicitly:
vSphere(
    serverName: 'my-vcenter',
    buildStep: [$class: 'Clone',
                sourceName: 'special-template',
                clone: "special-${env.BUILD_NUMBER}",
                cluster: 'my-cluster',
                resourcePool: 'Resources',
                hostSelectionMode: 'NONE', // overrides the cloud's default for this step only
                powerOn: true]
)
```

See
["Controlling which ESXi host a clone lands on"](jenkins-configuration.md#controlling-which-esxi-host-a-clone-lands-on)
for the full explanation of these three fields, including why one deployment might
prefer a different mode than another (VMware edition/license, whether DRS is enabled,
and whether the automation account can write to every host).

### Full VM lifecycle

```groovy
vSphere(serverName: 'my-vcenter',
        buildStep: [$class: 'Clone',
                    sourceName: 'build-template',
                    clone: "build-${env.BUILD_NUMBER}",
                    cluster: 'my-cluster',
                    resourcePool: 'Resources',
                    datastore: 'fast-ssd',
                    powerOn: true,
                    timeoutInSeconds: 120])

def vmIp = env.VSPHERE_IP

try {
    // ... do work on vmIp ...
} finally {
    vSphere(serverName: 'my-vcenter',
            buildStep: [$class: 'Delete',
                        vm: "build-${env.BUILD_NUMBER}",
                        failOnNoExist: false])
}
```

### Build a fresh template

```groovy
// Power off the source VM
vSphere(serverName: 'my-vcenter',
        buildStep: [$class: 'PowerOff',
                    vm: 'linux-base',
                    shutdownGracefully: true,
                    gracefulShutdownTimeout: 60])

// Snapshot it for future clones
vSphere(serverName: 'my-vcenter',
        buildStep: [$class: 'TakeSnapshot',
                    vm: 'linux-base',
                    snapshotName: "snap-${env.BUILD_NUMBER}",
                    description: "Jenkins build ${env.BUILD_NUMBER}",
                    includeMemory: false])

// Convert to a template for safe consumption
vSphere(serverName: 'my-vcenter',
        buildStep: [$class: 'ConvertToTemplate',
                    vm: 'linux-base',
                    force: false])
```

### Reconfigure a VM before use

```groovy
vSphere(serverName: 'my-vcenter',
        buildStep: [$class: 'Reconfigure',
                    vm: 'build-runner',
                    reconfigureSteps: [
                        [$class: 'ReconfigureCpu',
                         cpuCores: '8',
                         coresPerSocket: '4'],
                        [$class: 'ReconfigureMemory',
                         memorySize: '16384']
                    ]])

vSphere(serverName: 'my-vcenter',
        buildStep: [$class: 'PowerOn',
                    vm: 'build-runner',
                    timeoutInSeconds: 120])

echo "Runner IP: ${env.VSPHERE_IP}"
```

## FYI: How the jenkins.io steps reference is generated

You may have seen the auto-generated reference at
[jenkins.io/doc/pipeline/steps/vsphere-cloud](https://www.jenkins.io/doc/pipeline/steps/vsphere-cloud/)
which is produced by the Jenkins documentation toolchain at release time.

It reads each class that implements `Step` or `SimpleBuildStep`, lists the fields
declared with `@DataBoundConstructor`/`@DataBoundSetter`, and uses the
`help-*.html` files from a plugin's resources directory for field descriptions.

Because none of the vSphere build step classes carry a `@Symbol` annotation,
they all require the `$class` form (`[$class: 'Clone', ...]`) rather than a
shorter named-function syntax.

Adding `@Symbol` to individual step classes would allow a cleaner DSL -- PRs
are welcome.
