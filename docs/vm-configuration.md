# Virtual Machine configuration for cloud usage

Most of the vSphere plugin "cloud" functionality centers around the cloning of VMs and/or Templates
with the objective of having those VMs appear as nodes (agents) on Jenkins and then be available to perform builds.
In order for this to happen, the VMs either have to connect to Jenkins,
or have to allow Jenkins to connect to them,
just like any other Jenkins node
(see [Distributed builds](https://wiki.jenkins.io/display/JA/Distributed+builds)
for more details).
Failure to set up the VM correctly will mean that,
while the plugin is able to start and stop the VMs,
they won't be any use to Jenkins and won't run any builds.

There are two common methods by which this can be achieved,
and both require the VM/Template to have been configured with the requisite software on it.

### Launch agents via SSH

After the VM has booted up (and after the VMware tools have been detected),
the Jenkins master will connect to the node.

This requires that you set up your VM to run sshd,
you ensure that it has Java installed,
and that Jenkins has network connectivity to the VM's first IP address.
This means that you won't be able to get away with using a host-only network on vSphere for your VMs; you'll need them to have IP addresses that Jenkins can ping.

See
[the SSH Build Agents plugin](https://plugins.jenkins.io/ssh-slaves)
for further details on that configuration options are available
...but make sure that everything you set in Jenkins has matching functionality in your VM.

### Launch agent via Java Web Start

With a "Java Web Start" (aka JNLP) method, the Jenkins master does not connect to the node;
it relies on the node connecting to the master.

This requires that you configure your VM to start the Jenkins slave.jar process such that it to connects to the master.
This means that you can use a non-routable network for your VMs
(they only need NAT access to the Jenkins master's IP address;
the Jenkins master doesn't need to be able to connect to them)
but, unless you wish to hard-code knowledge of your Jenkins master into your VM,
you should use the GuestInfo properties to pass the relevant information to the VM,
and have the VM automatically read that information on startup prior to connecting to Jenkins.
Exactly how you do this is up to you,
but the plugin can pass all the information your VM should need to vSphere in the VM's GuestInfo properties
... if you ask it to do so.
The help for the "GuestInfo properties" section
(in the [Jenkins configuration](jenkins-configuration.md))
provides details of how to read the properties at runtime.
...but make sure that everything you set in Jenkins has matching functionality in your VM.

e.g. If you're using a \*nix OS with upstart,
you could run the process from upstart by having a script read the GuestInfo properties,
download slave.jar, then run java -jar slave.jar with the appropriate arguments.

e.g. If you're using Windows, you could have the machine auto-login to itself,
start a desktop session and have a .CMD script in the StartUp folder that achieves the same thing.
This example script,
[JenkinsSlaveStartup.cmd](attachments/JenkinsSlaveStartup.cmd),
requires 3 GuestInfo properties to be set:
* `JNLPURL` should be set to value `${JENKINS_URL}computer/${NODE_NAME}/slave-agent.jnlp`,
* `SLAVE_HOME` should be set to `${remoteFS}` and
* `SLAVE_SECRET` should be set to `${JNLP_SECRET}`.

...with other GuestInfo properties also being looked for and acted on (see the comments in the script for details).

If you're using Windows VMs then this script makes a good starting point.

### General note

For short-lived VMs,
e.g. ones that are spun up "on demand" and destroyed after a short period,
it's very important to disable "automatic updates" on your VM guest operating systems,
otherwise these will interfere with the builds you're running.

You don't want your builds to run 10x slower than normal and then die due to the VM rebooting,
just because the guest OS has decided to download a new version of Minecraft, the latest maps of Seattle, and then reboot.
