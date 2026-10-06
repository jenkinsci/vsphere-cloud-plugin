/*   Copyright 2026, Jim Klimov
 *   Copyright 2014 Oleg Nenashev <o.v.nenashev@gmail.com> (the credentials drop-down logic,
 *   adapted from VSphereConnectionConfig)
 *
 *   Licensed under the Apache License, Version 2.0 (the "License");
 *   you may not use this file except in compliance with the License.
 *   You may obtain a copy of the License at
 *
 *       http://www.apache.org/licenses/LICENSE-2.0
 *
 *   Unless required by applicable law or agreed to in writing, software
 *   distributed under the License is distributed on an "AS IS" BASIS,
 *   WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *   See the License for the specific language governing permissions and
 *   limitations under the License.
 */
package org.jenkinsci.plugins.vsphere;

import static org.jenkinsci.plugins.vsphere.tools.PermissionUtils.throwUnlessUserHasPermissionToConfigureCloud;

import com.cloudbees.hudson.plugins.folder.AbstractFolder;
import com.cloudbees.jenkins.plugins.sshcredentials.SSHUserPrivateKey;
import com.cloudbees.plugins.credentials.CredentialsMatcher;
import com.cloudbees.plugins.credentials.CredentialsMatchers;
import com.cloudbees.plugins.credentials.CredentialsProvider;
import com.cloudbees.plugins.credentials.common.StandardListBoxModel;
import com.cloudbees.plugins.credentials.common.StandardUsernameCredentials;
import com.cloudbees.plugins.credentials.common.StandardUsernamePasswordCredentials;
import com.cloudbees.plugins.credentials.domains.DomainRequirement;
import com.cloudbees.plugins.credentials.domains.HostnameRequirement;
import com.cloudbees.plugins.credentials.domains.SchemeRequirement;
import edu.umd.cs.findbugs.annotations.CheckForNull;
import hudson.Extension;
import hudson.Util;
import hudson.model.Descriptor;
import hudson.model.Item;
import hudson.security.ACL;
import hudson.security.ACLContext;
import hudson.util.FormValidation;
import hudson.util.ListBoxModel;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.logging.Level;
import java.util.logging.Logger;
import jenkins.model.Jenkins;
import org.jenkinsci.Symbol;
import org.jenkinsci.plugins.vsphere.folder.FolderVSphereCloudProperty;
import org.jenkinsci.plugins.vsphere.tools.VSphere;
import org.jenkinsci.plugins.vsphere.tools.VSphereException;
import org.jenkinsci.plugins.vsphere.tools.esxi.EsxiConnectionTestResult;
import org.jenkinsci.plugins.vsphere.tools.esxi.EsxiHostKeyInfo;
import org.jenkinsci.plugins.vsphere.tools.esxi.EsxiHostKeyPolicy;
import org.jenkinsci.plugins.vsphere.tools.esxi.EsxiHostKeyStore;
import org.jenkinsci.plugins.vsphere.tools.esxi.EsxiRelay;
import org.jenkinsci.plugins.vsphere.tools.esxi.EsxiSshAuth;
import org.jenkinsci.plugins.vsphere.tools.esxi.EsxiSshSettings;
import org.jenkinsci.plugins.vsphere.tools.esxi.EsxiTransferMode;
import org.jenkinsci.plugins.vsphere.tools.esxi.TrileadEsxiShell;
import org.jenkinsci.plugins.vsphere.tools.esxi.VSphereEsxiCluster;
import org.jenkinsci.plugins.vsphere.tools.esxi.VSphereEsxiSsh;
import org.kohsuke.stapler.AncestorInPath;
import org.kohsuke.stapler.DataBoundConstructor;
import org.kohsuke.stapler.DataBoundSetter;
import org.kohsuke.stapler.QueryParameter;
import org.kohsuke.stapler.interceptor.RequirePOST;

/**
 * What is needed to connect to a standalone ESXi host over SSH, instead of to vCenter through the vSphere API (see
 * {@link VSphereConnectionConfig#getEsxiSsh()}): the SSH port, the login (a "Username with password" or an "SSH
 * Username with private key" credential), how far to trust the host key, and time outs. The host itself is the
 * {@code vsHost} of the connection configuration, as a plain host name.
 *
 * <p>With the host key policy "trust the first one seen", the fingerprint of the host key that is seen first
 * is stored in {@link #getHostKeyFingerprint()}, from where it is required from then on; clearing it makes the next
 * connection a first one again.
 */
public class EsxiSshBackendConfig extends VSphereBackendConfig implements EsxiHostKeyStore {

    private static final Logger LOGGER = Logger.getLogger(EsxiSshBackendConfig.class.getName());

    static final int DEFAULT_TRANSFER_IDLE_SECONDS = 300;

    private @CheckForNull String credentialsId;
    private int port = EsxiSshSettings.DEFAULT_PORT;
    private EsxiHostKeyPolicy hostKeyPolicy = EsxiHostKeyPolicy.FINGERPRINT;
    private @CheckForNull String hostKeyFingerprint;
    private int connectTimeoutSeconds = EsxiSshSettings.DEFAULT_CONNECT_TIMEOUT_SECONDS;
    private int commandTimeoutSeconds = EsxiSshSettings.DEFAULT_COMMAND_TIMEOUT_SECONDS;
    private List<EsxiSshHost> additionalHosts = new ArrayList<>();
    private boolean replicateMasters;
    private EsxiRelay.Compression relayCompression = EsxiRelay.Compression.PIGZ;
    private EsxiTransferMode transferMode = EsxiTransferMode.RELAY;
    private int transferIdleSeconds = DEFAULT_TRANSFER_IDLE_SECONDS;

    @DataBoundConstructor
    public EsxiSshBackendConfig(@CheckForNull String credentialsId) {
        this.credentialsId = Util.fixEmptyAndTrim(credentialsId);
    }

    public @CheckForNull String getCredentialsId() {
        return credentialsId;
    }

    public int getPort() {
        return port;
    }

    @DataBoundSetter
    public void setPort(int port) {
        this.port = port > 0 ? port : EsxiSshSettings.DEFAULT_PORT;
    }

    public EsxiHostKeyPolicy getHostKeyPolicy() {
        return hostKeyPolicy;
    }

    @DataBoundSetter
    public void setHostKeyPolicy(@CheckForNull EsxiHostKeyPolicy hostKeyPolicy) {
        this.hostKeyPolicy = hostKeyPolicy == null ? EsxiHostKeyPolicy.FINGERPRINT : hostKeyPolicy;
    }

    public synchronized @CheckForNull String getHostKeyFingerprint() {
        return hostKeyFingerprint;
    }

    @DataBoundSetter
    public synchronized void setHostKeyFingerprint(@CheckForNull String hostKeyFingerprint) {
        this.hostKeyFingerprint = Util.fixEmptyAndTrim(hostKeyFingerprint);
    }

    public int getConnectTimeoutSeconds() {
        return connectTimeoutSeconds;
    }

    @DataBoundSetter
    public void setConnectTimeoutSeconds(int seconds) {
        this.connectTimeoutSeconds = seconds > 0 ? seconds : EsxiSshSettings.DEFAULT_CONNECT_TIMEOUT_SECONDS;
    }

    public int getCommandTimeoutSeconds() {
        return commandTimeoutSeconds;
    }

    @DataBoundSetter
    public void setCommandTimeoutSeconds(int seconds) {
        this.commandTimeoutSeconds = seconds > 0 ? seconds : EsxiSshSettings.DEFAULT_COMMAND_TIMEOUT_SECONDS;
    }

    /** The other hosts of the cluster, if there is one: the first host is the {@code vsHost} of the connection. */
    public List<EsxiSshHost> getAdditionalHosts() {
        return additionalHosts == null ? new ArrayList<>() : new ArrayList<>(additionalHosts);
    }

    @DataBoundSetter
    public void setAdditionalHosts(@CheckForNull List<EsxiSshHost> hosts) {
        this.additionalHosts = new ArrayList<>();
        if (hosts != null) {
            for (EsxiSshHost host : hosts) {
                if (host != null && !host.getHost().isEmpty()) {
                    host.setOwner(this);
                    this.additionalHosts.add(host);
                }
            }
        }
    }

    /** Whether a clone may be made on a host that does not see its master, of a replica that is made there. */
    public boolean isReplicateMasters() {
        return replicateMasters;
    }

    @DataBoundSetter
    public void setReplicateMasters(boolean replicateMasters) {
        this.replicateMasters = replicateMasters;
    }

    /** How the files of a replica are compressed on their way between hosts. */
    public EsxiRelay.Compression getRelayCompression() {
        return relayCompression == null ? EsxiRelay.Compression.PIGZ : relayCompression;
    }

    @DataBoundSetter
    public void setRelayCompression(@CheckForNull EsxiRelay.Compression compression) {
        this.relayCompression = compression == null ? EsxiRelay.Compression.PIGZ : compression;
    }

    /** How the files of a replica get from one host to another. */
    public EsxiTransferMode getTransferMode() {
        return transferMode == null ? EsxiTransferMode.RELAY : transferMode;
    }

    @DataBoundSetter
    public void setTransferMode(@CheckForNull EsxiTransferMode mode) {
        this.transferMode = mode == null ? EsxiTransferMode.RELAY : mode;
    }

    /** How long nothing may move in a transfer between hosts, or in the long command of a copy, before it is given up on. */
    public int getTransferIdleSeconds() {
        return transferIdleSeconds > 0 ? transferIdleSeconds : DEFAULT_TRANSFER_IDLE_SECONDS;
    }

    @DataBoundSetter
    public void setTransferIdleSeconds(int seconds) {
        this.transferIdleSeconds = seconds > 0 ? seconds : DEFAULT_TRANSFER_IDLE_SECONDS;
    }

    protected Object readResolve() {
        setAdditionalHosts(additionalHosts);
        return this;
    }

    // -- the host key remembered by "trust the first one seen" --

    @Override
    public synchronized @CheckForNull String get(String host, int port) {
        return hostKeyFingerprint;
    }

    @Override
    public synchronized String rememberIfAbsent(String host, int port, String fingerprint) {
        if (hostKeyFingerprint == null) {
            hostKeyFingerprint = fingerprint;
            LOGGER.log(
                    Level.INFO, "Remembering the fingerprint {0} of {1}:{2}", new Object[] {fingerprint, host, port});
            saveWhatHoldsThisConfiguration(host);
        }
        return hostKeyFingerprint;
    }

    /**
     * Saves where this configuration is kept, so that the host key that was remembered stays so: the folder, for
     * a cloud that is defined in a folder, otherwise Jenkins itself.
     */
    void saveRemembered(String host) {
        saveWhatHoldsThisConfiguration(host);
    }

    private void saveWhatHoldsThisConfiguration(String host) {
        try (ACLContext ignored = ACL.as2(ACL.SYSTEM2)) {
            for (AbstractFolder<?> folder : Jenkins.get().getAllItems(AbstractFolder.class)) {
                final FolderVSphereCloudProperty property =
                        folder.getProperties().get(FolderVSphereCloudProperty.class);
                if (property == null || property.getClouds() == null) {
                    continue;
                }
                for (vSphereCloud cloud : property.getClouds()) {
                    final VSphereConnectionConfig config = cloud.getVsConnectionConfig();
                    if (config != null && config.getBackend() == this) {
                        folder.save();
                        LOGGER.log(Level.INFO, "Saved the folder {0} that holds the cloud for {1}", new Object[] {
                            folder.getFullName(), host
                        });
                        return;
                    }
                }
            }
            Jenkins.get().save();
        } catch (IOException e) {
            // It is in use for as long as this configuration lives, and saved with the next change of it
            LOGGER.log(Level.WARNING, "Could not save the fingerprint that was remembered for " + host, e);
        }
    }

    // -- what the forms of this and of the other hosts share --

    static ListBoxModel hostKeyPolicyItems() {
        final ListBoxModel items = new ListBoxModel();
        items.add("Only the fingerprint given below", EsxiHostKeyPolicy.FINGERPRINT.name());
        items.add(
                "The fingerprint seen first (remembered below, and required from then on)",
                EsxiHostKeyPolicy.TRUST_FIRST_USE.name());
        items.add("Any fingerprint (not secure)", EsxiHostKeyPolicy.ACCEPT_ANY.name());
        return items;
    }

    static ListBoxModel credentialsItems(
            @CheckForNull AbstractFolder<?> containingFolderOrNull, @CheckForNull String host, String credentialsId) {
        final StandardListBoxModel result = new StandardListBoxModel();
        // Only those who may use credentials get to see which ones exist; everybody else just sees the
        // value that is currently configured.
        final boolean mayListCredentials = containingFolderOrNull == null
                ? Jenkins.get().hasPermission(Jenkins.ADMINISTER)
                : containingFolderOrNull.hasPermission(CredentialsProvider.USE_ITEM)
                        || containingFolderOrNull.hasPermission(Item.EXTENDED_READ);
        if (!mayListCredentials) {
            return result.includeCurrentValue(credentialsId);
        }
        return result.includeEmptyValue()
                .includeMatchingAs(
                        ACL.SYSTEM2,
                        Jenkins.get(),
                        StandardUsernameCredentials.class,
                        domainRequirements(Util.fixEmptyAndTrim(host)),
                        CREDENTIALS_MATCHER)
                .includeCurrentValue(credentialsId);
    }

    static FormValidation checkFingerprint(@CheckForNull String value, @CheckForNull String hostKeyPolicy) {
        final String fingerprint = Util.fixEmptyAndTrim(value);
        if (fingerprint == null) {
            return EsxiHostKeyPolicy.ACCEPT_ANY.name().equals(hostKeyPolicy)
                            || EsxiHostKeyPolicy.TRUST_FIRST_USE.name().equals(hostKeyPolicy)
                    ? FormValidation.ok()
                    : FormValidation.warning(
                            "Without a fingerprint, no host is trusted: use \"Show fingerprint\" or \"Test Connection\""
                                    + " to see which one the host presents");
        }
        if (!fingerprint.startsWith("SHA256:") && !fingerprint.matches("(?i)(MD5:)?([0-9a-f]{2}:){15}[0-9a-f]{2}")) {
            return FormValidation.warning(
                    "Expected SHA256:... (as ssh-keygen -l shows it) or an MD5 fingerprint" + " like 00:11:22:...");
        }
        return FormValidation.ok();
    }

    static FormValidation queryHostKey(
            @CheckForNull String vsHost, @CheckForNull String port, @CheckForNull String connectTimeoutSeconds) {
        final String host = Util.fixEmptyAndTrim(vsHost);
        if (host == null) {
            return FormValidation.error("The ESXi host is not specified");
        }
        try {
            final EsxiHostKeyInfo key =
                    TrileadEsxiShell.queryHostKey(host, parse(port, 0), parse(connectTimeoutSeconds, 0));
            return FormValidation.ok("The host presents a " + key.getAlgorithm() + " key, with the fingerprints "
                    + key.getSha256() + " and " + key.getMd5());
        } catch (VSphereException e) {
            return FormValidation.error(e.getMessage());
        }
    }

    static FormValidation testConnection(
            @CheckForNull String vsHost,
            @CheckForNull String credentialsId,
            @CheckForNull String port,
            @CheckForNull String hostKeyPolicy,
            @CheckForNull String hostKeyFingerprint,
            @CheckForNull String connectTimeoutSeconds) {
        final EsxiSshBackendConfig config = new EsxiSshBackendConfig(credentialsId);
        config.setPort(parse(port, 0));
        config.setHostKeyFingerprint(hostKeyFingerprint);
        config.setConnectTimeoutSeconds(parse(connectTimeoutSeconds, 0));
        try {
            config.setHostKeyPolicy(
                    hostKeyPolicy == null ? EsxiHostKeyPolicy.FINGERPRINT : EsxiHostKeyPolicy.valueOf(hostKeyPolicy));
        } catch (IllegalArgumentException e) {
            config.setHostKeyPolicy(EsxiHostKeyPolicy.FINGERPRINT);
        }
        final EsxiConnectionTestResult result;
        try {
            result = TrileadEsxiShell.test(config.toSettings(Util.fixEmptyAndTrim(vsHost)));
        } catch (VSphereException e) {
            return FormValidation.error(e.getMessage());
        }
        final String version = result.getServerVersion() == null ? "" : " (" + result.getServerVersion() + ")";
        if (result.isOk()) {
            return FormValidation.ok(result.getMessage() + version);
        }
        // A host key that is not trusted yet is shown as such, with the fingerprint to put in the settings
        return result.getHostKey() != null && !result.isHostKeyTrusted()
                ? FormValidation.warning(result.getMessage())
                : FormValidation.error(result.getMessage());
    }

    private static int parse(@CheckForNull String number, int defaultValue) {
        try {
            return number == null ? defaultValue : Integer.parseInt(number.trim());
        } catch (NumberFormatException e) {
            return defaultValue;
        }
    }

    // -- connecting --

    private static List<DomainRequirement> domainRequirements(@CheckForNull String host) {
        final List<DomainRequirement> requirements = new ArrayList<>();
        requirements.add(new SchemeRequirement("ssh"));
        if (host != null && !host.isEmpty()) {
            requirements.add(new HostnameRequirement(host));
        }
        return requirements;
    }

    /** The credential ("Username with password" or "SSH Username with private key") that the login is made with. */
    static @CheckForNull StandardUsernameCredentials lookupCredentials(
            @CheckForNull String credentialsId, @CheckForNull String host) {
        if (credentialsId == null) {
            return null;
        }
        return CredentialsMatchers.firstOrNull(
                CredentialsProvider.lookupCredentialsInItemGroup(
                        StandardUsernameCredentials.class, Jenkins.get(), ACL.SYSTEM2, domainRequirements(host)),
                CredentialsMatchers.allOf(CredentialsMatchers.withId(credentialsId), CREDENTIALS_MATCHER));
    }

    /** What an SSH login can be made with. */
    private static final CredentialsMatcher CREDENTIALS_MATCHER = CredentialsMatchers.anyOf(
            CredentialsMatchers.instanceOf(StandardUsernamePasswordCredentials.class),
            CredentialsMatchers.instanceOf(SSHUserPrivateKey.class));

    /** The login, as the credential says. */
    EsxiSshAuth resolveAuth(@CheckForNull String host) throws VSphereException {
        if (credentialsId == null) {
            throw new VSphereException("SSH credentials are not specified for ESXi host " + host);
        }
        final StandardUsernameCredentials credentials = lookupCredentials(credentialsId, host);
        if (credentials == null) {
            throw new VSphereException("Cannot find a username with password, or SSH username with private key,"
                    + " credential with id " + credentialsId + " for ESXi host " + host);
        }
        return EsxiSshAuth.from(credentials);
    }

    /** Everything that is needed for the SSH session to the host. */
    EsxiSshSettings toSettings(@CheckForNull String host) throws VSphereException {
        if (host == null || host.isEmpty()) {
            throw new VSphereException("ESXi host is not specified");
        }
        return new EsxiSshSettings(host, port, resolveAuth(host))
                .withHostKeyFingerprint(getHostKeyFingerprint())
                .withHostKeyPolicy(hostKeyPolicy)
                .withHostKeyStore(this)
                .withConnectTimeoutSeconds(connectTimeoutSeconds)
                .withCommandTimeoutSeconds(commandTimeoutSeconds);
    }

    /** Connects to the ESXi host over SSH. */
    public VSphere connect(@CheckForNull String host) throws VSphereException {
        return new VSphereEsxiSsh(TrileadEsxiShell.connect(toSettings(host)));
    }

    /** What is needed for the SSH session to another host of the cluster: what it does not say is that of the first. */
    EsxiSshSettings toSettings(EsxiSshHost other) throws VSphereException {
        final String id = other.getCredentialsId() != null ? other.getCredentialsId() : credentialsId;
        if (id == null) {
            throw new VSphereException("SSH credentials are not specified for ESXi host " + other.getHost());
        }
        final StandardUsernameCredentials credentials = lookupCredentials(id, other.getHost());
        if (credentials == null) {
            throw new VSphereException("Cannot find a username with password, or SSH username with private key,"
                    + " credential with id " + id + " for ESXi host " + other.getHost());
        }
        return new EsxiSshSettings(
                        other.getHost(), other.getPort() > 0 ? other.getPort() : port, EsxiSshAuth.from(credentials))
                .withHostKeyFingerprint(other.getHostKeyFingerprint())
                .withHostKeyPolicy(other.getHostKeyPolicy() != null ? other.getHostKeyPolicy() : hostKeyPolicy)
                .withHostKeyStore(other)
                .withConnectTimeoutSeconds(connectTimeoutSeconds)
                .withCommandTimeoutSeconds(commandTimeoutSeconds);
    }

    /**
     * A connection to the host of the connection configuration, or, if there are other hosts configured, to all of
     * them as one cluster (see {@link VSphereEsxiCluster}). Hosts that cannot be reached are left out, to be
     * tried again later, as long as one can.
     */
    @Override
    public VSphere connect(VSphereConnectionConfig config) throws VSphereException {
        if (additionalHosts == null || additionalHosts.isEmpty()) {
            return connect(config.getVsHost());
        }
        final List<VSphereEsxiCluster.Connector> connectors = new ArrayList<>();
        final String first = config.getVsHost();
        connectors.add(new VSphereEsxiCluster.Connector() {
            @Override
            public String label() {
                return first == null ? "" : first;
            }

            @Override
            public VSphereEsxiSsh connect() throws VSphereException {
                return new VSphereEsxiSsh(TrileadEsxiShell.connect(toSettings(first)));
            }
        });
        for (EsxiSshHost other : additionalHosts) {
            connectors.add(new VSphereEsxiCluster.Connector() {
                @Override
                public String label() {
                    return other.getHost();
                }

                @Override
                public VSphereEsxiSsh connect() throws VSphereException {
                    return new VSphereEsxiSsh(TrileadEsxiShell.connect(toSettings(other)));
                }
            });
        }
        return new VSphereEsxiCluster(
                connectors,
                new VSphereEsxiCluster.Options(
                        replicateMasters,
                        getRelayCompression(),
                        getTransferIdleSeconds(),
                        getTransferMode().mover()));
    }

    @Override
    public VSphereConnectionConfig.BackendType getBackendType() {
        return VSphereConnectionConfig.BackendType.ESXI_SSH;
    }

    @Extension
    @Symbol("esxiSsh")
    public static class DescriptorImpl extends Descriptor<VSphereBackendConfig> {

        @Override
        public String getDisplayName() {
            return "Standalone ESXi host(s) over SSH";
        }

        @RequirePOST
        public ListBoxModel doFillHostKeyPolicyItems(@AncestorInPath AbstractFolder<?> containingFolderOrNull) {
            throwUnlessUserHasPermissionToConfigureCloud(containingFolderOrNull);
            return hostKeyPolicyItems();
        }

        @RequirePOST
        public ListBoxModel doFillTransferModeItems(@AncestorInPath AbstractFolder<?> containingFolderOrNull) {
            throwUnlessUserHasPermissionToConfigureCloud(containingFolderOrNull);
            final ListBoxModel items = new ListBoxModel();
            items.add(
                    "Through the controller (the default; needs nothing of the hosts)", EsxiTransferMode.RELAY.name());
            items.add("Directly over SSH, with a key made for each copy", EsxiTransferMode.SSH_DIRECT.name());
            items.add("Directly by netcat (fast, and not encrypted)", EsxiTransferMode.NETCAT.name());
            return items;
        }

        @RequirePOST
        public ListBoxModel doFillRelayCompressionItems(@AncestorInPath AbstractFolder<?> containingFolderOrNull) {
            throwUnlessUserHasPermissionToConfigureCloud(containingFolderOrNull);
            final ListBoxModel items = new ListBoxModel();
            items.add("pigz (fast; the default)", EsxiRelay.Compression.PIGZ.name());
            items.add("gzip", EsxiRelay.Compression.GZIP.name());
            items.add("bzip2 (smaller, slower)", EsxiRelay.Compression.BZIP2.name());
            items.add("None", EsxiRelay.Compression.NONE.name());
            return items;
        }

        @RequirePOST
        public ListBoxModel doFillCredentialsIdItems(
                @AncestorInPath AbstractFolder<?> containingFolderOrNull,
                @QueryParameter String vsHost,
                @QueryParameter String credentialsId) {
            throwUnlessUserHasPermissionToConfigureCloud(containingFolderOrNull);
            return credentialsItems(containingFolderOrNull, vsHost, credentialsId);
        }

        @RequirePOST
        public FormValidation doCheckCredentialsId(
                @AncestorInPath AbstractFolder<?> containingFolderOrNull, @QueryParameter String value) {
            throwUnlessUserHasPermissionToConfigureCloud(containingFolderOrNull);
            return Util.fixEmptyAndTrim(value) == null
                    ? FormValidation.error("Choose a username with password, or SSH username with private key")
                    : FormValidation.ok();
        }

        @RequirePOST
        public FormValidation doCheckPort(
                @AncestorInPath AbstractFolder<?> containingFolderOrNull, @QueryParameter String value) {
            throwUnlessUserHasPermissionToConfigureCloud(containingFolderOrNull);
            return FormValidation.validatePositiveInteger(value);
        }

        @RequirePOST
        public FormValidation doCheckHostKeyFingerprint(
                @AncestorInPath AbstractFolder<?> containingFolderOrNull,
                @QueryParameter String value,
                @QueryParameter String hostKeyPolicy) {
            throwUnlessUserHasPermissionToConfigureCloud(containingFolderOrNull);
            return checkFingerprint(value, hostKeyPolicy);
        }

        @RequirePOST
        public FormValidation doCheckHostKeyPolicy(
                @AncestorInPath AbstractFolder<?> containingFolderOrNull, @QueryParameter String value) {
            throwUnlessUserHasPermissionToConfigureCloud(containingFolderOrNull);
            if (EsxiHostKeyPolicy.ACCEPT_ANY.name().equals(value)) {
                return FormValidation.warning("Warning: This is not secure.");
            }
            if (EsxiHostKeyPolicy.TRUST_FIRST_USE.name().equals(value)) {
                return FormValidation.warning("Warning: at the first connection, Jenkins saves its configuration by"
                        + " itself to remember the fingerprint: the one of the folder if this cloud is in a folder,"
                        + " otherwise the one of Jenkins. And it is only as safe as that first connection is.");
            }
            return FormValidation.ok();
        }

        /** Finds out which host key the host presents, without logging in. */
        @RequirePOST
        public FormValidation doQueryHostKey(
                @AncestorInPath AbstractFolder<?> containingFolderOrNull,
                @QueryParameter String vsHost,
                @QueryParameter String port,
                @QueryParameter String connectTimeoutSeconds) {
            throwUnlessUserHasPermissionToConfigureCloud(containingFolderOrNull);
            return queryHostKey(vsHost, port, connectTimeoutSeconds);
        }

        /** Tests the connection as it is configured, without any effect: the host key, whether it is trusted, the login. */
        @RequirePOST
        public FormValidation doTestConnection(
                @AncestorInPath AbstractFolder<?> containingFolderOrNull,
                @QueryParameter String vsHost,
                @QueryParameter String credentialsId,
                @QueryParameter String port,
                @QueryParameter String hostKeyPolicy,
                @QueryParameter String hostKeyFingerprint,
                @QueryParameter String connectTimeoutSeconds) {
            throwUnlessUserHasPermissionToConfigureCloud(containingFolderOrNull);
            return testConnection(
                    vsHost, credentialsId, port, hostKeyPolicy, hostKeyFingerprint, connectTimeoutSeconds);
        }
    }
}
