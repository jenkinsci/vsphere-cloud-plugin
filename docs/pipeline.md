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
            customizationSpec: '',         // guest OS customization spec name (optional)
            useCurrentSnapshot: null,      // true = clone from current snapshot; false = don't use snapshot
            namedSnapshot: '',             // clone from this specific named snapshot (optional)
            extraConfigParameters: [:]     // extra VMX key-value pairs to set on the new VM (optional)
           ]
```

The `datastore` option can be useful if your templates live on different storage (slower/cheaper) than production VMs, so run-time instances should not appear "near" their origin.

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
            customizationSpec: ''
           ]
```

---

### Power-On / Resume VM

Powers on a stopped or suspended VM and waits for its IP address.

```groovy
buildStep: [$class: 'PowerOn',
            vm: 'my-vm',              // (required) VM name
            timeoutInSeconds: 60      // (required) max seconds to wait for IP (max 3600)
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
            newDescription: ''
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

```groovy
buildStep: [$class: 'Reconfigure',
            vm: 'my-vm',
            reconfigureSteps: [
                [$class: 'ReconfigureCpu',
                 cpuCores: '4',
                 coresPerSocket: '2'],
                [$class: 'ReconfigureMemory',
                 memorySize: '8192'],        // megabytes
                [$class: 'ReconfigureDisk',
                 diskSize: '51200',          // megabytes
                 datastore: 'my-datastore'],
                [$class: 'ReconfigureNetworkAdapters',
                 deviceAction: 'EDIT',       // ADD, EDIT, or REMOVE
                 deviceLabel: 'Network adapter 1',
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
