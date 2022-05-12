# Change Log

## Unreleased
A pre-release can be downloaded from https://ci.jenkins.io/job/Plugins/job/vsphere-cloud-plugin/job/master/

* Prepare for removal of JAXB and Java 11 requirement ([JENKINS-68477](https://issues.jenkins.io/browse/JENKINS-68477), [#131](https://github.com/jenkinsci/docker-plugin/pull/131)
* Stop using deprecated Util join() [#129](https://github.com/jenkinsci/docker-plugin/pull/129)

### [Version 2.26](https://github.com/jenkinsci/vsphere-cloud-plugin/releases/tag/vsphere-cloud-2.26)
_August 18th, 2021_:
* Prepare vSphere for core Guava upgrade ([JENKINS-66301](https://issues.jenkins.io/browse/JENKINS-66301), [PR#128](https://github.com/jenkinsci/vsphere-cloud-plugin/pull/128))

### [Version 2.25](https://github.com/jenkinsci/vsphere-cloud-plugin/releases/tag/vsphere-cloud-2.25)
_January 25th, 2021_:
* Bump minimum Jenkins core version to 2.190.1 ([PR#125](https://github.com/jenkinsci/vsphere-cloud-plugin/pull/125))
* Bugfix: agent was being left in isDisconnecting state ([JENKINS-64335](https://issues.jenkins.io/browse/JENKINS-64335), [PR#124](https://github.com/jenkinsci/vsphere-cloud-plugin/pull/124))
* Bugfix: Configure Clouds UI was broken in Jenkins >= 2.264 ([JENKINS-64357](https://issues.jenkins.io/browse/JENKINS-64357), [PR#126](https://github.com/jenkinsci/vsphere-cloud-plugin/pull/126))
* Update terminology in documentation [#words-matter](https://www.ibm.com/blogs/think/2020/08/words-matter-driving-thoughtful-change-toward-inclusive-language-in-technology/) ([PR#119](https://github.com/jenkinsci/vsphere-cloud-plugin/pull/119), [PR#120](https://github.com/jenkinsci/vsphere-cloud-plugin/pull/120))

### [Version 2.24](https://github.com/jenkinsci/vsphere-cloud-plugin/releases/tag/vsphere-cloud-2.24)
_September 21st, 2020_:
* Fix resource leak on disconnect ([PR#117](https://github.com/jenkinsci/vsphere-cloud-plugin/pull/117))
* Fix ability to manually create vSphere agent
([JENKINS-61702](https://issues.jenkins-ci.org/browse/JENKINS-61702),
[PR#118](https://github.com/jenkinsci/vsphere-cloud-plugin/pull/118)).

### [Version 2.23](https://github.com/jenkinsci/vsphere-cloud-plugin/releases/tag/vsphere-cloud-2.23)
_March 13th, 2020_:
* Fix broken credentials selection
([JENKINS-61131](https://issues.jenkins-ci.org/browse/JENKINS-61131),
[PR#116](https://github.com/jenkinsci/vsphere-cloud-plugin/pull/116)).

### [Version 2.22](https://github.com/jenkinsci/vsphere-cloud-plugin/releases/tag/vsphere-cloud-2.22)
_February 14th, 2020_:
* JCasC support
([JENKINS-60029](https://issues.jenkins-ci.org/browse/JENKINS-60029),
[PR#100](https://github.com/jenkinsci/vsphere-cloud-plugin/pull/100),
[PR#110](https://github.com/jenkinsci/vsphere-cloud-plugin/pull/110),
[PR#111](https://github.com/jenkinsci/vsphere-cloud-plugin/pull/111),
[PR#112](https://github.com/jenkinsci/vsphere-cloud-plugin/pull/112),
[PR#114](https://github.com/jenkinsci/vsphere-cloud-plugin/pull/114)).
* Fix accessor usage in CloudSelectorParameter
([PR#113](https://github.com/jenkinsci/vsphere-cloud-plugin/pull/113)).
* Move wiki documentation to GitHub.

### [Version 2.21](https://github.com/jenkinsci/vsphere-cloud-plugin/releases/tag/vsphere-cloud-2.21)
_September 24th, 2019:_
* Bugfix: Shutdown tick box
([JENKINS-58136](https://issues.jenkins-ci.org/browse/JENKINS-58136),
[PR#106](https://github.com/jenkinsci/vsphere-cloud-plugin/pull/106)).
* Bugfix: Fix NodeProperties support
([JENKINS-41428](https://issues.jenkins-ci.org/browse/JENKINS-41428),
[JENKINS-46375](https://issues.jenkins-ci.org/browse/JENKINS-46375),
[PR#105](https://github.com/jenkinsci/vsphere-cloud-plugin/pull/105),
[PR#108](https://github.com/jenkinsci/vsphere-cloud-plugin/pull/108)).
* State software license more clearly
([PR#109](https://github.com/jenkinsci/vsphere-cloud-plugin/pull/109)).

### [Version 2.20](https://github.com/jenkinsci/vsphere-cloud-plugin/releases/tag/vsphere-cloud-2.20)
_July 8th, 2019:_
* Bugfix: Cope with SSH Build Agents plugin version 1.30 onwards
([PR#104](https://github.com/jenkinsci/vsphere-cloud-plugin/pull/104)).
* Misc code tidying
([PR#102](https://github.com/jenkinsci/vsphere-cloud-plugin/pull/102)).

### [Version 2.19](https://github.com/jenkinsci/vsphere-cloud-plugin/releases/tag/vsphere-cloud-2.19)
_February 26th, 2019:_
* Enhancement: Graceful shutdown timeout is now customizable
([PR#97](https://github.com/jenkinsci/vsphere-cloud-plugin/pull/97)).
* Correct button text on Folder configuration
([PR#99](https://github.com/jenkinsci/vsphere-cloud-plugin/pull/99)).
* Improve appearance of agent WebUI page
([PR#101](https://github.com/jenkinsci/vsphere-cloud-plugin/pull/101)).

### [Version 2.18](https://github.com/jenkinsci/vsphere-cloud-plugin/releases/tag/vsphere-cloud-2.18)
_July 23rd, 2018:_
* Enhancement: Added "Reconnect and Revert" VM agent option
([PR#90](https://github.com/jenkinsci/vsphere-cloud-plugin/pull/90)).
* Enhancement: Improved localization support + code tidy-up for cloud agent retention
([PR#91](https://github.com/jenkinsci/vsphere-cloud-plugin/pull/91)).
* Enhancement: Don't hard-code a '-' into the VM name
([PR#92](https://github.com/jenkinsci/vsphere-cloud-plugin/pull/92),
[PR#93](https://github.com/jenkinsci/vsphere-cloud-plugin/pull/93)).
* Enhancement: Implement "Reconfigure Notes" build step
([PR#94](https://github.com/jenkinsci/vsphere-cloud-plugin/pull/94)).
* Enhancement: Show VM information (including any Notes) on Jenkins' .../computer/... page
([PR#95](https://github.com/jenkinsci/vsphere-cloud-plugin/pull/95)).

### [Version 2.17](https://github.com/jenkinsci/vsphere-cloud-plugin/releases/tag/vsphere-cloud-2.17)
_March 26th, 2018:_
* [Fix security issue](https://jenkins.io/security/advisory/2018-03-26/#SECURITY-504):
Enable SSL/TLS certificate validation by default.
* [Fix security issue](https://jenkins.io/security/advisory/2018-03-26/#SECURITY-745):
Properly implement access control and CSRF protection on form validation related URLs to prevent credentials capturing and denial of service.

### [Version 2.16](https://github.com/jenkinsci/vsphere-cloud-plugin/releases/tag/vsphere-cloud-2.16)
_September 7th, 2017:_
* BugFix folder configuration
([PR#72](https://github.com/jenkinsci/vsphere-cloud-plugin/pull/72)).
* BugFix build exception if VSPHERE\_IP is NULL
([JENKINS-36952](https://issues.jenkins-ci.org/browse/JENKINS-36952),
[PR#79](https://github.com/jenkinsci/vsphere-cloud-plugin/pull/79)).
* BugFix Retention strategy "until idle" configuration
([JENKINS-45786](https://issues.jenkins-ci.org/browse/JENKINS-45786),
[PR#82](https://github.com/jenkinsci/vsphere-cloud-plugin/pull/82)).
* BugFix Change version of yavijava from 6.0.04 to 6.0.05
([JENKINS-43962](https://issues.jenkins-ci.org/browse/JENKINS-43962),
[PR#71](https://github.com/jenkinsci/vsphere-cloud-plugin/pull/71)).
* Improve exception handling when cloning
([PR#70](https://github.com/jenkinsci/vsphere-cloud-plugin/pull/70)).
* Improve vSphere error handling
([JENKINS-44796](https://issues.jenkins-ci.org/browse/JENKINS-44796),
[PR#73](https://github.com/jenkinsci/vsphere-cloud-plugin/pull/73),
[PR#75](https://github.com/jenkinsci/vsphere-cloud-plugin/pull/75),
[PR#77](https://github.com/jenkinsci/vsphere-cloud-plugin/pull/77),
[PR#85](https://github.com/jenkinsci/vsphere-cloud-plugin/pull/85)).
* Improve logging
([PR#74](https://github.com/jenkinsci/vsphere-cloud-plugin/pull/74)).
* Enhancement: Added timeout to Clone and Deploy build steps
([PR#81](https://github.com/jenkinsci/vsphere-cloud-plugin/pull/81),
[PR#84](https://github.com/jenkinsci/vsphere-cloud-plugin/pull/84)).

### [Version 2.15](https://github.com/jenkinsci/vsphere-cloud-plugin/releases/tag/vsphere-cloud-2.15)
_January 2nd, 2017:_
* Folder parameter
([PR#61](https://github.com/jenkinsci/vsphere-cloud-plugin/pull/61)).
* Folder configuration support
([PR#62](https://github.com/jenkinsci/vsphere-cloud-plugin/pull/62)).
* Add new parameter ignoreIfNotExists
([PR#63](https://github.com/jenkinsci/vsphere-cloud-plugin/pull/63)).

### [Version 2.14](https://github.com/jenkinsci/vsphere-cloud-plugin/releases/tag/vsphere-cloud-2.14)
_October 27th, 2016:_
* Expose customization spec setting
([PR#60](https://github.com/jenkinsci/vsphere-cloud-plugin/pull/60)).
* Make vSphereCloudLauncher inherit from DelegatingComputerLauncher
[JENKINS-39232](https://issues.jenkins-ci.org/browse/JENKINS-39232)
([PR#59](https://github.com/jenkinsci/vsphere-cloud-plugin/pull/59)).
* Re-fix
[JENKINS-24661](https://issues.jenkins-ci.org/browse/JENKINS-24661)
([PR#58](https://github.com/jenkinsci/vsphere-cloud-plugin/pull/58)).
* Second part of
[JENKINS-20743](https://issues.jenkins-ci.org/browse/JENKINS-20743)
([PR#57](https://github.com/jenkinsci/vsphere-cloud-plugin/pull/57)).
* Implement
[JENKINS-20743](https://issues.jenkins-ci.org/browse/JENKINS-20743)
([PR#55](https://github.com/jenkinsci/vsphere-cloud-plugin/pull/55),
[PR#56](https://github.com/jenkinsci/vsphere-cloud-plugin/pull/56)).
* Implement
[JENKINS-38269](https://issues.jenkins-ci.org/browse/JENKINS-38269)
([PR#54](https://github.com/jenkinsci/vsphere-cloud-plugin/pull/54)).
* Enhance
[JENKINS-22437](https://issues.jenkins-ci.org/browse/JENKINS-22437)
([PR#53](https://github.com/jenkinsci/vsphere-cloud-plugin/pull/53)).
* Fix
[JENKINS-24605](https://issues.jenkins-ci.org/browse/JENKINS-24605)
and
[JENKINS-24661](https://issues.jenkins-ci.org/browse/JENKINS-24661)
([PR#52](https://github.com/jenkinsci/vsphere-cloud-plugin/pull/52)).
* Fix for
[JENKINS-38249](https://issues.jenkins-ci.org/browse/JENKINS-38249)
([PR#51](https://github.com/jenkinsci/vsphere-cloud-plugin/pull/51)).
* Bugfix
[JENKINS-36878](https://issues.jenkins-ci.org/browse/JENKINS-36878)
([PR#50](https://github.com/jenkinsci/vsphere-cloud-plugin/pull/50)).
* Implement
[JENKINS-22437](https://issues.jenkins-ci.org/browse/JENKINS-22437)
([PR#49](https://github.com/jenkinsci/vsphere-cloud-plugin/pull/49)).
* Fix
[JENKINS-36878](https://issues.jenkins-ci.org/browse/JENKINS-36878)
and
[JENKINS-32112](https://issues.jenkins-ci.org/browse/JENKINS-32112)
([PR#48](https://github.com/jenkinsci/vsphere-cloud-plugin/pull/48)).
* Fix for
[JENKINS-38030](https://issues.jenkins-ci.org/browse/JENKINS-38030)
([PR#47](https://github.com/jenkinsci/vsphere-cloud-plugin/pull/47)).
* Change version of yavijava that we fetch from 6.0.03 to 6.0.04
([PR#46](https://github.com/jenkinsci/vsphere-cloud-plugin/pull/46)).

### [Version 2.13](https://github.com/jenkinsci/vsphere-cloud-plugin/releases/tag/vsphere-cloud-2.13)
_June 7th, 2016:_
* Enabled Pipeline support
([PR#45](https://github.com/jenkinsci/vsphere-cloud-plugin/pull/45)).
* Handle disconnect and temporarily offline
([PR#44](https://github.com/jenkinsci/vsphere-cloud-plugin/pull/44)).

### [Version 2.12](https://github.com/jenkinsci/vsphere-cloud-plugin/releases/tag/vsphere-cloud-2.12)
_March 25th, 2016:_
* Allow SSH logins
([PR#43](https://github.com/jenkinsci/vsphere-cloud-plugin/pull/43)).

### [Version 2.11](https://github.com/jenkinsci/vsphere-cloud-plugin/releases/tag/vsphere-cloud-2.11)
_February 24th, 2016:_
* Fixed naming issues with templates
([PR#42](https://github.com/jenkinsci/vsphere-cloud-plugin/pull/42)).

### [Version 2.10](https://github.com/jenkinsci/vsphere-cloud-plugin/releases/tag/vsphere-cloud-2.10)
_January 26th, 2016:_
* Agent naming fixes
([PR#41](https://github.com/jenkinsci/vsphere-cloud-plugin/pull/41)).

### [Version 2.9](https://github.com/jenkinsci/vsphere-cloud-plugin/releases/tag/vsphere-cloud-2.9)
_December 30th, 2015:_
* Power state after deploy
([PR#40](https://github.com/jenkinsci/vsphere-cloud-plugin/pull/40)).
* Provided fixes
([PR#39](https://github.com/jenkinsci/vsphere-cloud-plugin/pull/39)).

### [Version 2.8](https://github.com/jenkinsci/vsphere-cloud-plugin/releases/tag/vsphere-cloud-2.8)
_December 11th, 2015:_
* Dynamic On-Demand agents
([PR#35](https://github.com/jenkinsci/vsphere-cloud-plugin/pull/35)).
* Set DataStore when reconfiguring disks
([PR#37](https://github.com/jenkinsci/vsphere-cloud-plugin/pull/37)).
* Distributed vSwitch Support
([PR#38](https://github.com/jenkinsci/vsphere-cloud-plugin/pull/38)).

### [Version 2.7](https://github.com/jenkinsci/vsphere-cloud-plugin/releases/tag/vsphere-cloud-2.7)
_November 20th, 2015:_
* Clone with resource pool
([PR#33](https://github.com/jenkinsci/vsphere-cloud-plugin/pull/33)).

### [Version 2.6](https://github.com/jenkinsci/vsphere-cloud-plugin/releases/tag/vsphere-cloud-2.6)
_June 10th, 2015:_
* Handle null GuestInfo during PowerOn
([PR#31](https://github.com/jenkinsci/vsphere-cloud-plugin/pull/31)).
* Additional time outs during PowerOn
([PR#32](https://github.com/jenkinsci/vsphere-cloud-plugin/pull/32)).

### [Version 2.5](https://github.com/jenkinsci/vsphere-cloud-plugin/releases/tag/vsphere-cloud-2.5)
_May 18th, 2015:_
* HelpDoc: Set correct name for ip address variable
([PR#27](https://github.com/jenkinsci/vsphere-cloud-plugin/pull/27)).
* ClusterFix: Don't assume that the cluster should exist
([PR#28](https://github.com/jenkinsci/vsphere-cloud-plugin/pull/28)).
* SelectableCloud: Cloud should be selectable
([PR#29](https://github.com/jenkinsci/vsphere-cloud-plugin/pull/29)).
* Logging: Log stack trace if exception message is null
([PR#30](https://github.com/jenkinsci/vsphere-cloud-plugin/pull/30)).

### [Version 2.4](https://github.com/jenkinsci/vsphere-cloud-plugin/releases/tag/vsphere-cloud-2.4)
_February 26th, 2015:_
* Disconnect from vSphere after performing actions
([PR#26](https://github.com/jenkinsci/vsphere-cloud-plugin/pull/26)).

### [Version 2.3](https://github.com/jenkinsci/vsphere-cloud-plugin/releases/tag/vsphere-cloud-2.3)
_February 3rd, 2015:_
* Add build step for exposing GuestInfo
([PR#22](https://github.com/jenkinsci/vsphere-cloud-plugin/pull/22)).
* Get datastores directly from cluster
([PR#23](https://github.com/jenkinsci/vsphere-cloud-plugin/pull/23)).
* VsphereSelection: Allow dynamic selection of vsphere cloud
([PR#24](https://github.com/jenkinsci/vsphere-cloud-plugin/pull/24)).
* Code optimization
([PR#25](https://github.com/jenkinsci/vsphere-cloud-plugin/pull/25)).

### [Version 2.2](https://github.com/jenkinsci/vsphere-cloud-plugin/releases/tag/vsphere-cloud-2.2)
_January 16th, 2015:_
* Added action: Change/Add disk
([PR#19](https://github.com/jenkinsci/vsphere-cloud-plugin/pull/19)).

### [Version 2.1](https://github.com/jenkinsci/vsphere-cloud-plugin/releases/tag/vsphere-cloud-2.1)
_January 6th, 2015:_
* Credential plugin fixes
([JENKINS-25588](https://issues.jenkins-ci.org/browse/JENKINS-25588) ).

### [Version 2.0](https://github.com/jenkinsci/vsphere-cloud-plugin/releases/tag/vsphere-cloud-2.0)
_December 24th, 2014:_
* Use Jenkins Credentials Plugin for username/password
([JENKINS-25588](https://issues.jenkins-ci.org/browse/JENKINS-25588)).
* Fix for missing DataStores

### [Version 1.1.12](https://github.com/jenkinsci/vsphere-cloud-plugin/releases/tag/vsphere-cloud-1.1.12)
_September 26th, 2014:_
* Possible fix for disconnect, shutdown, power on issue
* Added some new VM actions:
  * Clone from template/VM
  * Rename VM
  * Rename Snapshot
  * VM Reconfigure, to adjust CPU, RAM and NIC interfaces
* Added Datastore name to Clone/Deploy action

### [Version 1.1.11](https://github.com/jenkinsci/vsphere-cloud-plugin/releases/tag/vsphere-cloud-1.1.11)
_July 8th, 2014:_
* Allow deployment without a resource pool
([JENKINS-21647](https://issues.jenkins-ci.org/browse/JENKINS-21647)).
* Added a Graceful Shutdown to the Power Down build step.
* Additional fixes to handle multiple agent shutdowns and job runs on powered down agents.

### [Version 1.1.10](https://github.com/jenkinsci/vsphere-cloud-plugin/releases/tag/vsphere-cloud-1.1.10)
_May 15th, 2014:_
* Modified the previous fix for preventing jobs running on agents that had limited test runs enabled.
The original fix wasn't robust in terms of Jenkins restarts.
* **NOTE:**
A cleanup of the code revealed that the plugin was storing information in the agent configuration that it wasn't supposed to.
When this version of the plugin is installed, some old data may have been stored and Jenkins will note this on the Manage Jenkins page.
If the data is in regard to "isStarting" and "isDisconnecting", then it should be fine to delete that old data with no adverse effects.

### [Version 1.1.9](https://github.com/jenkinsci/vsphere-cloud-plugin/releases/tag/vsphere-cloud-1.1.9)
_May 12th, 2014:_
* Fixed problem where VM restarts due to limited test runs would result in the next build failing

### [Version 1.1.6](https://github.com/jenkinsci/vsphere-cloud-plugin/releases/tag/vsphere-cloud-1.1.6)
_April 25th, 2014:_
* Added new disconnect options and an error case where disconnects were being called too many times
([PR#12](https://github.com/jenkinsci/vsphere-cloud-plugin/pull/12)).

### [Version 1.1.5](https://github.com/jenkinsci/vsphere-cloud-plugin/releases/tag/vsphere-cloud-1.1.5)
_March 13th, 2014:_
* "VM cannot be started" repeatedly with vSphere plugin
([JENKINS-22025](https://issues.jenkins-ci.org/browse/JENKINS-22025)).
* Vsphere plugin keeps reseting the agent when agent is configured for on demand start
([JENKINS-21312](https://issues.jenkins-ci.org/browse/JENKINS-21312)).

### [Version 1.1.4](https://github.com/jenkinsci/vsphere-cloud-plugin/releases/tag/vsphere-cloud-1.1.4)
_January 30th, 2014:_
* When configuring a job, the "ServerName" drop-down now properly defaults to the saved value
([JENKINS-21580](https://issues.jenkins-ci.org/browse/JENKINS-21580)).

### [Version 1.1.3](https://github.com/jenkinsci/vsphere-cloud-plugin/releases/tag/vsphere-cloud-1.1.3)
_January 7th, 2014:_
* Add "Delete a Snapshot" build step
([JENKINS-20793](https://issues.jenkins-ci.org/browse/JENKINS-20793)).
* Update vijava dependency to 5.1.

### [Version 1.1.2](https://github.com/jenkinsci/vsphere-cloud-plugin/releases/tag/vsphere-cloud-1.1.2)
_November 26th, 2013:_
* Fixed a race-case type issue where Jenkins would disconnect an agent that was in the process of trying to connect.

### [Version 1.1.1](https://github.com/jenkinsci/vsphere-cloud-plugin/releases/tag/vsphere-cloud-1.1.1)
_November 6th, 2013:_
* Fixed an issue where certain exceptions, including those thrown during the initial connection to vSphere, were not logged to the console.
* Adding "Resume" to "Power-On" build step title.

### [Version 1.1.0](https://github.com/jenkinsci/vsphere-cloud-plugin/releases/tag/vsphere-cloud-1.1.0)
_October 4th, 2013:_
* Condensed all vSphere build steps into a single "vSphere Build Step" container
* Added more build steps; Made existing build steps more granular
([JENKINS-19702](https://issues.jenkins-ci.org/browse/JENKINS-19702)):
  * Convert VM to a template
  * Convert template to a VM
  * Delete VM
  * Deploy VM from template
  * Power On VM
  * Power Off VM
  * Revert to Snapshot
  * Suspend VM
  * Take Snapshot
* Deployment log now prints the name of created VM
([JENKINS-19436](https://issues.jenkins-ci.org/browse/JENKINS-19436)).
* Added configurable timeout to Power On Build Step
* More code cleanup, restructuring, and refactoring
* **Due to the restructuring of classes, this plugin's job configurations won't survive the upgrade from 1.0.x to 1.1.0 or above.**

### [Version 1.0.2](https://github.com/jenkinsci/vsphere-cloud-plugin/releases/tag/vsphere-cloud-1.0.2)
_August 28th, 2013:_
* Massive code cleanup, restructure, and refactoring.
* Help text has been overhauled to work better with Jenkins standards (localizable, linkable).

### [Version 1.0.1](https://github.com/jenkinsci/vsphere-cloud-plugin/releases/tag/vsphere-cloud-1.0.1)
_August 22nd, 2013:_
* Added build step functionality. The following build steps are now available:
  * Create VM from Template
  * Convert VM to template
  * Convert template to VM
  * Delete VM.
* Minor behind-the-scenes code cleanup and re-factoring. 

### [Version 0.10](https://github.com/jenkinsci/vsphere-cloud-plugin/releases/tag/vsphere-cloud-0.10)
_May 25th, 2012:_
* Fixed
[JENKINS-13722](https://issues.jenkins-ci.org/browse/JENKINS-13722).
* Undid all the agent launching logic - the logic plus
[JENKINS-13735](https://issues.jenkins-ci.org/browse/JENKINS-13735)
was resulting in agents that would never start.
Agent STILL never start in some cases, but once the Jenkins bug is fixed, it should begin working better.

### [Version 0.9](https://github.com/jenkinsci/vsphere-cloud-plugin/releases/tag/vsphere-cloud-0.9)
_May 7th, 2012:_
* Overhaul of the agent launching logic.  Multiple agents will be launched if unique.
* Log lines should print with new lines
([JENKINS-17323](https://issues.jenkins-ci.org/browse/JENKINS-17323)).

### [Version 0.8](https://github.com/jenkinsci/vsphere-cloud-plugin/releases/tag/vsphere-cloud-0.8)
_May 4th, 2012:_
* Fixed an NPE in some race cases involving agent startups
([JENKINS-13675](https://issues.jenkins-ci.org/browse/JENKINS-13675)).

### [Version 0.7](https://github.com/jenkinsci/vsphere-cloud-plugin/releases/tag/vsphere-cloud-0.7)
_May 2nd, 2012:_
* Re-release due to problems in the release process.

### [Version 0.6](https://github.com/jenkinsci/vsphere-cloud-plugin/releases/tag/vsphere-cloud-0.6) (release process failed)
* Bugfix: Test Connection fails if vSphere host contains trailing slash ('/') character
([JENKINS-12241](https://issues.jenkins-ci.org/browse/JENKINS-12241)).
* Cleaning VM before start next job in queue
([JENKINS-12163](https://issues.jenkins-ci.org/browse/JENKINS-12163)).
* Bugfix: Not perform "revert snapshot" for all vmwares of a label
([JENKINS-13537](https://issues.jenkins-ci.org/browse/JENKINS-13537)).

### [Version 0.5](https://github.com/jenkinsci/vsphere-cloud-plugin/releases/tag/vsphere-cloud-0.5)
_December 8th, 2011:_
* Added "Reset" to the disconnect behavior.  Will do a Reset VM when the agent disconnects.
* Added "Nothing" to the disconnect behavior.  Nothing at all will happen when the agent disconnects.
* Updated the VM Java API to the latest version.

### [Version 0.4](https://github.com/jenkinsci/vsphere-cloud-plugin/releases/tag/vsphere-cloud-0.4)
_October 8th, 2011:_
* Initial Release
