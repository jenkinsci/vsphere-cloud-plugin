/*   Copyright 2026, Jim Klimov
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
package org.jenkinsci.plugins.vsphere.tools.esxi;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.not;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.util.List;
import org.jenkinsci.plugins.vsphere.tools.VSphereException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Copying files from one host to another with nothing passing through the controller: over SSH with a key that is made
 * for the copy, and by nc through a port that is opened in the firewall for it; and what is left behind on the hosts.
 */
class EsxiDirectCopyTest {

    private static final String SOURCE = "/vmfs/volumes/ds1/master";
    private static final String TARGET = "/vmfs/volumes/ds2/replica";
    private static final String KEYS = "/etc/ssh/keys-jenkins/authorized_keys";

    private FakeEsxiHost from;
    private FakeEsxiHost to;
    private final ByteArrayOutputStream said = new ByteArrayOutputStream();
    private final PrintStream log = new PrintStream(said);

    @BeforeEach
    void setUp() {
        from = new FakeEsxiHost();
        from.address = "10.0.0.1";
        from.tools.add("ssh");
        from.tools.add("nc");
        from.addFile(SOURCE + "/master.vmdk", "# descriptor of the disk\n");
        from.addFile(SOURCE + "/master-flat.vmdk", "x".repeat(5000));
        to = new FakeEsxiHost();
        to.address = "10.0.0.2";
        to.tools.add("nc");
        to.addFile("/vmfs/volumes/ds2/.keep", "");
        from.peers.put("10.0.0.2", to);
        to.peers.put("10.0.0.1", from);
    }

    private EsxiRelay.Result copy(EsxiRelay.Mover mover) throws Exception {
        return EsxiRelay.copy(
                from,
                "a",
                to,
                "b",
                SOURCE,
                List.of("master.vmdk", "master-flat.vmdk"),
                TARGET,
                EsxiRelay.Compression.PIGZ,
                30,
                log,
                mover);
    }

    private void assertArrived() {
        assertThat(to.file(TARGET + "/master.vmdk"), is("# descriptor of the disk\n"));
        assertThat(to.file(TARGET + "/master-flat.vmdk"), is("x".repeat(5000)));
        assertThat(to.files.keySet().stream().anyMatch(f -> f.contains(".jenkins-incoming-")), is(false));
    }

    private void assertNothingLeftBehind() {
        assertThat(from.files.keySet().stream().anyMatch(f -> f.startsWith("/tmp/jenkins-xfer-")), is(false));
        assertThat(from.directories.stream().anyMatch(d -> d.startsWith("/tmp/jenkins-xfer-")), is(false));
        assertThat(to.hasFile(KEYS), is(false));
        assertThat(to.hasDirectory("/etc/ssh/keys-jenkins"), is(false));
        assertThat(from.rulesets.get("sshClient"), is(false));
        assertThat(from.processes.isEmpty(), is(true));
        assertThat(to.processes.isEmpty(), is(true));
    }

    // -- over SSH --

    @Test
    void filesAreSentOverSshWithAKeyThatIsMadeForTheCopyAndRemovedAfter() throws Exception {
        final EsxiRelay.Result result = copy(new EsxiSshDirect());

        assertArrived();
        assertNothingLeftBehind();
        assertThat(result.getBytes(), is(25L + 5000));
        // the target was told to accept the key for one command only: unpacking where the files go
        assertThat(
                to.commands.stream()
                        .anyMatch(c -> c.contains("restrict,command=")
                                && c.contains("pigz -d | tar xf - -C")
                                && c.contains(".jenkins-incoming-")),
                is(true));
        assertThat(from.commands.stream().anyMatch(c -> c.contains("-o StrictHostKeyChecking=yes")), is(true));
        assertThat(said.toString(), containsString("directly from host to host over SSH"));
        assertThat(said.toString(), containsString("Turned the firewall ruleset sshClient on"));
        assertThat(said.toString(), containsString("Turned the firewall ruleset sshClient off again"));
    }

    @Test
    void theKeysThatAreThereAreKeptAndTheCopyIsNotTheOneToLeave() throws Exception {
        to.addFile(KEYS, "ssh-rsa AAAAADMIN admin@somewhere\n");

        copy(new EsxiSshDirect());

        assertArrived();
        assertThat(to.file(KEYS), is("ssh-rsa AAAAADMIN admin@somewhere\n"));
        assertThat(to.hasDirectory("/etc/ssh/keys-jenkins"), is(true));
    }

    @Test
    void aFirewallRulesetThatWasOnIsLeftOn() throws Exception {
        from.rulesets.put("sshClient", true);

        copy(new EsxiSshDirect());

        assertArrived();
        assertThat(from.rulesets.get("sshClient"), is(true));
        assertThat(said.toString(), not(containsString("Turned the firewall ruleset sshClient on")));
    }

    @Test
    void aRulesetThatAnEarlierStoppedCopyLeftOnIsTurnedOffAfterThisOne() throws Exception {
        from.rulesets.put("sshClient", true);
        from.addFile("/tmp/.jenkins-firewall-sshClient", "");

        copy(new EsxiSshDirect());

        assertThat(from.rulesets.get("sshClient"), is(false));
        assertThat(from.hasFile("/tmp/.jenkins-firewall-sshClient"), is(false));
    }

    @Test
    void aSourceWithoutAnSshClientSaysSo() {
        from.tools.remove("ssh");

        final VSphereException e = assertThrows(VSphereException.class, () -> copy(new EsxiSshDirect()));

        assertThat(e.getMessage(), containsString("a has no ssh client"));
        assertThat(to.hasFile(TARGET + "/master.vmdk"), is(false));
    }

    @Test
    void aTargetWhoseAddressIsNotKnownCannotBeSentTo() {
        to.address = null;

        final VSphereException e = assertThrows(VSphereException.class, () -> copy(new EsxiSshDirect()));

        assertThat(e.getMessage(), containsString("The address of b is not known"));
    }

    @Test
    void aHostThatIsNotTheTargetByItsKeyIsNotSentTo() {
        // what the source reaches at the address is not the host that the controller asked for its key
        final FakeEsxiHost impostor = new FakeEsxiHost();
        impostor.files.put("/etc/ssh/ssh_host_rsa_key.pub", "ssh-rsa AAAAOTHERHOST root@elsewhere\n");
        from.peers.put("10.0.0.2", impostor);

        final VSphereException e = assertThrows(VSphereException.class, () -> copy(new EsxiSshDirect()));

        assertThat(e.getMessage(), containsString("Host key verification failed"));
        assertThat(to.hasFile(TARGET + "/master.vmdk"), is(false));
        assertNothingLeftBehind();
    }

    @Test
    void aTargetThatDoesNotTakeTheKeyIsToldAboutAndLeftAsItWas() {
        to.refuseKeys = true;

        final VSphereException e = assertThrows(VSphereException.class, () -> copy(new EsxiSshDirect()));

        assertThat(e.getMessage(), containsString("Permission denied"));
        assertNothingLeftBehind();
    }

    @Test
    void aSourceWhoseFirewallStaysShutIsToldAboutAndPutBack() {
        // the ruleset cannot be turned on, as there is no such ruleset
        from.rulesets.remove("sshClient");

        final VSphereException e = assertThrows(VSphereException.class, () -> copy(new EsxiSshDirect()));

        assertThat(e.getMessage(), containsString("sshClient"));
        assertThat(to.hasFile(KEYS), is(false));
    }

    @Test
    void aCopyThatDoesNotMoveIsEndedAndTheHostsAreLeftClean() {
        from.stallTransfers = true;

        final long started = System.currentTimeMillis();
        final VSphereException e = assertThrows(
                VSphereException.class,
                () -> EsxiRelay.copy(
                        from,
                        "a",
                        to,
                        "b",
                        SOURCE,
                        List.of("master.vmdk"),
                        TARGET,
                        EsxiRelay.Compression.NONE,
                        1,
                        log,
                        new EsxiSshDirect(50)));

        assertThat(e.getMessage(), containsString("Nothing was written on b for 1 seconds"));
        assertThat(System.currentTimeMillis() - started < 20_000, is(true));
        assertThat(said.toString(), containsString("Ended the processes of the copy that are stuck"));
        assertThat(to.hasFile(TARGET + "/master.vmdk"), is(false));
        assertNothingLeftBehind();
    }

    @Test
    void twoCopiesAtOnceShareTheKeysAndTheLastOneCleansUp() throws Exception {
        final Thread other = new Thread(() -> {
            try {
                EsxiRelay.copy(
                        from,
                        "a",
                        to,
                        "b",
                        SOURCE,
                        List.of("master.vmdk"),
                        "/vmfs/volumes/ds2/replica2",
                        EsxiRelay.Compression.PIGZ,
                        30,
                        log,
                        new EsxiSshDirect());
            } catch (Exception e) {
                throw new AssertionError(e);
            }
        });
        other.start();
        copy(new EsxiSshDirect());
        other.join(20_000);

        assertArrived();
        assertThat(to.hasFile("/vmfs/volumes/ds2/replica2/master.vmdk"), is(true));
        assertNothingLeftBehind();
    }

    // -- by nc --

    private void assertFirewallClosed() {
        assertThat(from.rulesets.get("jenkinsXfer"), is(false));
        assertThat(to.rulesets.get("jenkinsXfer"), is(false));
        assertThat(from.hasFile(EsxiTransferFirewall.FILE), is(false));
        assertThat(to.hasFile(EsxiTransferFirewall.FILE), is(false));
        assertThat(from.allowedAll.get("jenkinsXfer"), is(true));
        assertThat(to.allowedAll.get("jenkinsXfer"), is(true));
        assertThat(to.allowedIps.get("jenkinsXfer").isEmpty(), is(true));
        assertThat(from.processes.isEmpty(), is(true));
        assertThat(to.processes.isEmpty(), is(true));
        assertThat(to.listeners.isEmpty(), is(true));
    }

    @Test
    void filesAreSentByNcThroughAPortThatIsOpenedForTheCopyAndClosedAfter() throws Exception {
        final EsxiRelay.Result result = copy(new EsxiNetcat());

        assertArrived();
        assertFirewallClosed();
        assertThat(result.getBytes(), is(25L + 5000));
        assertThat(
                to.commands.stream().anyMatch(c -> c.matches("nc -d -l -w 30 \\d+ \\| pigz -d \\| tar xf - -C .*")),
                is(true));
        assertThat(from.commands.stream().anyMatch(c -> c.contains(" | nc -w 30 -s 10.0.0.1 10.0.0.2 ")), is(true));
        assertThat(said.toString(), containsString("not encrypted"));
        assertThat(said.toString(), containsString("Opened the port"));
    }

    @Test
    void theFirewallIsOpenedForTheSourceAndThePortOnly() throws Exception {
        // what the target's firewall holds while the copy is going on is what its ruleset's file says
        final String[] seen = new String[2];
        from.files.put("/etc/vmware/firewall/other.xml", "");
        final Thread watcher = new Thread(() -> {
            for (int i = 0; i < 2000 && seen[0] == null; i++) {
                if (!to.listeners.isEmpty() && to.hasFile(EsxiTransferFirewall.FILE)) {
                    seen[0] = to.file(EsxiTransferFirewall.FILE);
                    seen[1] = String.join(",", to.allowedIps.getOrDefault("jenkinsXfer", java.util.Set.of()));
                }
                try {
                    Thread.sleep(2);
                } catch (InterruptedException e) {
                    return;
                }
            }
        });
        watcher.start();
        copy(new EsxiNetcat());
        watcher.join(5000);

        assertThat(seen[0], containsString("<direction>inbound</direction>"));
        assertThat(seen[0], containsString("<direction>outbound</direction>"));
        assertThat(seen[0], containsString("<id>jenkinsXfer</id>"));
        assertThat(seen[1], is("10.0.0.1"));
    }

    @Test
    void aPortThatTheHostWillNotBindIsGivenUpForAnother() throws Exception {
        to.refuseBinds.set(2);

        copy(new EsxiNetcat());

        assertArrived();
        assertFirewallClosed();
        assertThat(said.toString(), containsString("is in use on b: trying another port"));
    }

    @Test
    void ifNoPortCanBeBoundTheCopyFailsAndTheFirewallIsClosed() {
        to.refuseBinds.set(100);

        final VSphereException e = assertThrows(VSphereException.class, () -> copy(new EsxiNetcat()));

        assertThat(e.getMessage(), containsString("is in use on b"));
        assertFirewallClosed();
    }

    @Test
    void aSourceThatCannotReachTheTargetIsToldAboutAndTheListenerIsEnded() {
        from.peers.clear();

        final VSphereException e = assertThrows(VSphereException.class, () -> copy(new EsxiNetcat()));

        assertThat(e.getMessage(), containsString("by nc failed"));
        assertThat(e.getMessage(), containsString("timed out"));
        assertFirewallClosed();
        assertThat(to.hasFile(TARGET + "/master.vmdk"), is(false));
    }

    @Test
    void aSourceWithoutNcIsToldAbout() {
        from.tools.remove("nc");

        final VSphereException e = assertThrows(VSphereException.class, () -> copy(new EsxiNetcat()));

        assertThat(e.getMessage(), containsString("a has no nc"));
    }

    @Test
    void aCopyByNcThatDoesNotMoveIsEnded() {
        from.stallTransfers = true;

        final VSphereException e = assertThrows(
                VSphereException.class,
                () -> EsxiRelay.copy(
                        from,
                        "a",
                        to,
                        "b",
                        SOURCE,
                        List.of("master.vmdk"),
                        TARGET,
                        EsxiRelay.Compression.NONE,
                        1,
                        log,
                        new EsxiNetcat(50, 20)));

        assertThat(e.getMessage(), containsString("Nothing was written on b for 1 seconds"));
        assertFirewallClosed();
    }

    @Test
    void twoCopiesAtOnceUseOnePortEachAndTheFirewallIsClosedByTheLast() throws Exception {
        final Thread other = new Thread(() -> {
            try {
                EsxiRelay.copy(
                        from,
                        "a",
                        to,
                        "b",
                        SOURCE,
                        List.of("master.vmdk"),
                        "/vmfs/volumes/ds2/replica2",
                        EsxiRelay.Compression.PIGZ,
                        30,
                        log,
                        new EsxiNetcat());
            } catch (Exception e) {
                throw new AssertionError(e);
            }
        });
        other.start();
        copy(new EsxiNetcat());
        other.join(20_000);

        assertArrived();
        assertThat(to.hasFile("/vmfs/volumes/ds2/replica2/master.vmdk"), is(true));
        assertFirewallClosed();
    }

    @Test
    void aPortThatAnEarlierCopyHasIsNotUsedAgain() throws Exception {
        try (EsxiTransferFirewall.Lease first = EsxiTransferFirewall.open(to, 50000, "10.0.0.1", log)) {
            final VSphereException e =
                    assertThrows(VSphereException.class, () -> EsxiTransferFirewall.open(to, 50000, "10.0.0.1", log));
            assertThat(e.getMessage(), containsString("Channel is still handling an earlier transfer"));
            assertThat(EsxiTransferFirewall.isTaken(to, 50000), is(true));
        }
        assertThat(EsxiTransferFirewall.isTaken(to, 50000), is(false));
    }

    // -- the way is chosen by the mode --

    @Test
    void theModesMakeTheirMovers() {
        assertThat(EsxiTransferMode.RELAY.mover(), is(EsxiRelay.RELAY));
        assertThat(EsxiTransferMode.SSH_DIRECT.mover() instanceof EsxiSshDirect, is(true));
        assertThat(EsxiTransferMode.NETCAT.mover() instanceof EsxiNetcat, is(true));
    }
}
