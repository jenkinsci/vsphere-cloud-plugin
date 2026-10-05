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
import static org.hamcrest.Matchers.greaterThan;
import static org.hamcrest.Matchers.is;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.util.List;
import org.jenkinsci.plugins.vsphere.tools.VSphereException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** Copying files between two hosts through the controller: the streams, the compression, and what is left behind. */
class EsxiRelayTest {

    private static final String SOURCE = "/vmfs/volumes/ds1/master";
    private static final String TARGET = "/vmfs/volumes/ds2/replica";

    private FakeEsxiHost from;
    private FakeEsxiHost to;
    private final ByteArrayOutputStream said = new ByteArrayOutputStream();
    private final PrintStream log = new PrintStream(said);
    private FakeEsxiSshServer sourceServer;
    private FakeEsxiSshServer targetServer;

    @BeforeEach
    void setUp() {
        from = new FakeEsxiHost();
        from.addFile(SOURCE + "/master.vmdk", "# descriptor of the disk\n");
        from.addFile(SOURCE + "/master-flat.vmdk", "x".repeat(5000));
        from.addFile(SOURCE + "/with space.vmdk", "spaced");
        to = new FakeEsxiHost();
        to.addFile("/vmfs/volumes/ds2/.keep", "");
    }

    @AfterEach
    void tearDown() throws Exception {
        if (sourceServer != null) {
            sourceServer.close();
        }
        if (targetServer != null) {
            targetServer.close();
        }
    }

    private EsxiRelay.Result copy(EsxiRelay.Compression compression, String... names) throws Exception {
        return EsxiRelay.copy(from, "a", to, "b", SOURCE, List.of(names), TARGET, compression, 30, log);
    }

    private boolean stagingLeft() {
        return to.files.keySet().stream().anyMatch(f -> f.contains(".jenkins-incoming-"))
                || to.directories.stream().anyMatch(d -> d.contains(".jenkins-incoming-"));
    }

    @Test
    void copiesFilesAsTheyAreCompressedWithPigz() throws Exception {
        final EsxiRelay.Result result =
                copy(EsxiRelay.Compression.PIGZ, "master.vmdk", "master-flat.vmdk", "with space.vmdk");

        assertThat(to.file(TARGET + "/master.vmdk"), is(from.file(SOURCE + "/master.vmdk")));
        assertThat(to.file(TARGET + "/master-flat.vmdk"), is("x".repeat(5000)));
        assertThat(to.file(TARGET + "/with space.vmdk"), is("spaced"));
        assertThat(result.getFiles(), is(3));
        assertThat(result.getBytes(), is(25L + 5000 + 6));
        assertThat(result.getTransferred(), greaterThan(0L));
        assertThat(result.getCompression(), is(EsxiRelay.Compression.PIGZ));
        assertThat(stagingLeft(), is(false));
        assertThat(from.commands.stream().anyMatch(c -> c.contains("| pigz -1")), is(true));
        assertThat(to.commands.stream().anyMatch(c -> c.startsWith("pigz -d | tar xf - -C")), is(true));
        assertThat(said.toString(), containsString("Copying 3 file(s), 5031 bytes, from a to b"));
    }

    @Test
    void theSourceIsNotTouched() throws Exception {
        copy(EsxiRelay.Compression.GZIP, "master.vmdk");

        assertThat(from.file(SOURCE + "/master.vmdk"), is("# descriptor of the disk\n"));
        assertThat(
                from.files.keySet().stream()
                        .filter(f -> f.startsWith("/vmfs/volumes/ds2"))
                        .count(),
                is(0L));
    }

    @Test
    void aCopyThatIsNotCompressedWorksToo() throws Exception {
        copy(EsxiRelay.Compression.NONE, "master.vmdk");

        assertThat(to.file(TARGET + "/master.vmdk"), is("# descriptor of the disk\n"));
        assertThat(from.commands.stream().anyMatch(c -> c.contains(" -1")), is(false));
    }

    @Test
    void aCompressionThatTheHostsDoNotBothHaveFallsBackToTheNextOne() throws Exception {
        to.tools.remove("pigz");

        final EsxiRelay.Result result = copy(EsxiRelay.Compression.PIGZ, "master.vmdk");

        assertThat(result.getCompression(), is(EsxiRelay.Compression.GZIP));
        assertThat(said.toString(), containsString("do not both have pigz; compressing the copy with gzip instead"));
    }

    @Test
    void withNoCompressionToolAtAllTheCopyIsNotCompressed() throws Exception {
        from.tools.clear();

        final EsxiRelay.Result result = copy(EsxiRelay.Compression.BZIP2, "master.vmdk");

        assertThat(result.getCompression(), is(EsxiRelay.Compression.NONE));
        assertThat(to.file(TARGET + "/master.vmdk"), is("# descriptor of the disk\n"));
    }

    @Test
    void aFileThatIsNotThereStopsTheCopyBeforeAnythingIsMade() {
        final VSphereException e = assertThrows(
                VSphereException.class, () -> copy(EsxiRelay.Compression.PIGZ, "master.vmdk", "nope.vmdk"));

        assertThat(e.getMessage(), containsString("nope.vmdk"));
        assertThat(to.hasFile(TARGET + "/master.vmdk"), is(false));
        assertThat(to.hasDirectory(TARGET), is(false));
    }

    @Test
    void namesAndFoldersThatAreNotPlainAreRefused() {
        for (String bad : new String[] {"../x", "a/b", "a'b", ".hidden"}) {
            assertThrows(VSphereException.class, () -> copy(EsxiRelay.Compression.PIGZ, bad));
        }
        assertThrows(
                VSphereException.class,
                () -> EsxiRelay.copy(
                        from,
                        "a",
                        to,
                        "b",
                        SOURCE,
                        List.of("master.vmdk"),
                        "/etc/vmware",
                        EsxiRelay.Compression.NONE,
                        30,
                        log));
        assertThrows(
                VSphereException.class,
                () -> EsxiRelay.copy(
                        from,
                        "a",
                        to,
                        "b",
                        "/vmfs/volumes/ds1/../..",
                        List.of("x"),
                        TARGET,
                        EsxiRelay.Compression.NONE,
                        30,
                        log));
        assertThrows(VSphereException.class, () -> copy(EsxiRelay.Compression.PIGZ));
        assertThat(to.files.keySet().stream().anyMatch(f -> f.startsWith(TARGET)), is(false));
    }

    @Test
    void aFailureOfTheSourceLeavesNothingWhereTheFilesWereGoingToBe() {
        from.failing("tar cf", "tar: write error: No space left on device");

        final VSphereException e =
                assertThrows(VSphereException.class, () -> copy(EsxiRelay.Compression.PIGZ, "master.vmdk"));

        assertThat(e.getMessage(), containsString("Reading the files on a failed"));
        assertThat(e.getMessage(), containsString("No space left on device"));
        assertThat(to.hasFile(TARGET + "/master.vmdk"), is(false));
        assertThat(stagingLeft(), is(false));
        assertThat(said.toString(), containsString("did not complete; nothing of it is left in " + TARGET));
    }

    @Test
    void aFailureOfTheTargetIsReportedAndCleanedUp() {
        to.failing("tar xf", "tar: can't write: Read-only file system");

        final VSphereException e =
                assertThrows(VSphereException.class, () -> copy(EsxiRelay.Compression.GZIP, "master.vmdk"));

        assertThat(e.getMessage(), containsString("Writing the files on b failed"));
        assertThat(e.getMessage(), containsString("Read-only file system"));
        assertThat(to.hasFile(TARGET + "/master.vmdk"), is(false));
        assertThat(stagingLeft(), is(false));
    }

    @Test
    void aFileThatArrivesShortIsNotMovedInPlace() {
        // the target says that it is done, but what it has is not what was sent
        final EsxiShell lying = new EsxiShell() {
            @Override
            public ShellResult run(String command) throws VSphereException {
                final ShellResult result = to.run(command);
                return command.startsWith("ls -ln") && command.contains(".jenkins-incoming-")
                        ? new ShellResult(0, result.getStdout().replaceAll("\\s\\d+ Oct", " 1 Oct"), "")
                        : result;
            }

            @Override
            public ShellResult stream(
                    String command, java.io.InputStream stdin, java.io.OutputStream stdout, int idleTimeoutSeconds)
                    throws VSphereException {
                return to.stream(command, stdin, stdout, idleTimeoutSeconds);
            }

            @Override
            public void close() {}
        };

        final VSphereException e = assertThrows(
                VSphereException.class,
                () -> EsxiRelay.copy(
                        from,
                        "a",
                        lying,
                        "b",
                        SOURCE,
                        List.of("master.vmdk"),
                        TARGET,
                        EsxiRelay.Compression.NONE,
                        30,
                        log));

        assertThat(e.getMessage(), containsString("has 1 bytes, not the 25 that it has on a"));
        assertThat(to.hasFile(TARGET + "/master.vmdk"), is(false));
    }

    @Test
    void aFileThatIsThereIsReplaced() throws Exception {
        to.addFile(TARGET + "/master.vmdk", "old");

        copy(EsxiRelay.Compression.PIGZ, "master.vmdk");

        assertThat(to.file(TARGET + "/master.vmdk"), is("# descriptor of the disk\n"));
    }

    // -- over SSH, which is where the streams are real --

    private EsxiShell ssh(FakeEsxiSshServer server) throws Exception {
        return TrileadEsxiShell.connect(
                new EsxiSshSettings("127.0.0.1", server.port(), EsxiSshAuth.password("root", "secret"))
                        .withHostKeyPolicy(EsxiHostKeyPolicy.ACCEPT_ANY)
                        .withConnectTimeoutSeconds(10)
                        .withCommandTimeoutSeconds(10));
    }

    @Test
    void copiesBetweenTwoHostsOverSsh() throws Exception {
        sourceServer = new FakeEsxiSshServer(from, "secret", null, false);
        targetServer = new FakeEsxiSshServer(to, "secret", null, false);
        from.addFile(SOURCE + "/big-flat.vmdk", "0123456789".repeat(300_000)); // 3 MB, many buffers
        try (EsxiShell a = ssh(sourceServer);
                EsxiShell b = ssh(targetServer)) {
            final EsxiRelay.Result result = EsxiRelay.copy(
                    a,
                    "a",
                    b,
                    "b",
                    SOURCE,
                    List.of("master.vmdk", "big-flat.vmdk"),
                    TARGET,
                    EsxiRelay.Compression.PIGZ,
                    30,
                    log);

            assertThat(result.getFiles(), is(2));
        }

        assertThat(to.file(TARGET + "/big-flat.vmdk").length(), is(3_000_000));
        assertThat(to.file(TARGET + "/master.vmdk"), is("# descriptor of the disk\n"));
        assertThat(stagingLeft(), is(false));
    }

    @Test
    void aFailureOverSshIsReportedToo() throws Exception {
        sourceServer = new FakeEsxiSshServer(from, "secret", null, false);
        targetServer = new FakeEsxiSshServer(to, "secret", null, false);
        from.failing("tar cf", "tar: read error");
        try (EsxiShell a = ssh(sourceServer);
                EsxiShell b = ssh(targetServer)) {
            final VSphereException e = assertThrows(
                    VSphereException.class,
                    () -> EsxiRelay.copy(
                            a,
                            "a",
                            b,
                            "b",
                            SOURCE,
                            List.of("master.vmdk"),
                            TARGET,
                            EsxiRelay.Compression.NONE,
                            30,
                            log));

            assertThat(e.getMessage(), containsString("Reading the files on a failed"));
        }
        assertThat(to.hasFile(TARGET + "/master.vmdk"), is(false));
    }

    @Test
    void aCommandThatMovesNothingIsGivenUpOnAfterTheIdleTime() throws Exception {
        sourceServer = new FakeEsxiSshServer(from, "secret", null, false);
        try (EsxiShell a = ssh(sourceServer)) {
            final long started = System.nanoTime();

            final VSphereException e = assertThrows(
                    VSphereException.class,
                    () -> a.stream(FakeEsxiSshServer.HANG, null, new ByteArrayOutputStream(), 1));

            assertThat(e.getMessage(), containsString("Nothing moved for 1 seconds"));
            assertThat((System.nanoTime() - started) / 1_000_000_000L < 10, is(true));
        }
    }
}
