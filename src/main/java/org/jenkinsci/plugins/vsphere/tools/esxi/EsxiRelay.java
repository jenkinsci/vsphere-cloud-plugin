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

import edu.umd.cs.findbugs.annotations.CheckForNull;
import java.io.FilterOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.io.PipedInputStream;
import java.io.PipedOutputStream;
import java.io.PrintStream;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicLong;
import org.jenkinsci.plugins.vsphere.tools.VSphereException;
import org.jenkinsci.plugins.vsphere.tools.VSphereLogger;

/**
 * Copies files from a datastore folder of one ESXi host to a folder of another through the controller, the relay:
 * {@code tar} on the source writes the files (compressed there, if that is wanted) to a stream that the controller
 * passes on, as it is, to {@code tar} on the target. It needs nothing of the hosts but the SSH session each of them
 * already has: no trust between the hosts, no firewall to open, nothing unencrypted. It is also the slowest way, as
 * the bytes make two trips, which is why they are compressed on the source.
 *
 * <p>The files are unpacked in a folder of their own next to where they are going, checked for their sizes, and only
 * then moved in place, so that a copy that fails halfway leaves nothing of itself where the files are looked for. As
 * {@code tar} carries the time stamps over, a file is as old on the target as on the source.
 */
public final class EsxiRelay {

    /** How the stream is compressed on the source and unpacked on the target. */
    public enum Compression {
        /** {@code pigz}, which is fast and uses all the cores; present on ESXi 7 and 8. */
        PIGZ("pigz"),
        /** {@code gzip}: the same format, slower. */
        GZIP("gzip"),
        /** {@code bzip2}: smaller, and slower yet. */
        BZIP2("bzip2"),
        /** Not compressed. */
        NONE(null);

        private final String tool;

        Compression(@CheckForNull String tool) {
            this.tool = tool;
        }

        @CheckForNull
        String getTool() {
            return tool;
        }

        /** The ones to try, from the one that is asked for: those that the hosts do not have are skipped. */
        List<Compression> fallbacks() {
            final List<Compression> order = new ArrayList<>();
            order.add(this);
            if (this != GZIP && this != NONE) {
                order.add(GZIP);
            }
            if (this != NONE) {
                order.add(NONE);
            }
            return order;
        }
    }

    /** How a copy went. */
    public static final class Result {
        private final int files;
        private final long bytes;
        private final long transferred;
        private final Compression compression;
        private final long millis;

        Result(int files, long bytes, long transferred, Compression compression, long millis) {
            this.files = files;
            this.bytes = bytes;
            this.transferred = transferred;
            this.compression = compression;
            this.millis = millis;
        }

        public int getFiles() {
            return files;
        }

        /** The size of the files. */
        public long getBytes() {
            return bytes;
        }

        /** What was sent through the controller, which is less than the size of the files if they compress. */
        public long getTransferred() {
            return transferred;
        }

        public Compression getCompression() {
            return compression;
        }

        public long getMillis() {
            return millis;
        }
    }

    private static final int PIPE_BYTES = 1024 * 1024;
    private static final long PROGRESS_EVERY_MILLIS = 30_000L;

    private EsxiRelay() {}

    private static void say(@CheckForNull PrintStream log, String message) {
        if (log != null) {
            VSphereLogger.vsLogger(log, message);
        }
    }

    private static boolean has(EsxiShell shell, String tool) throws VSphereException {
        return shell.run("which " + tool).succeeded();
    }

    /** The first of the compressions, from the one asked for, that both hosts can do. */
    static Compression choose(EsxiShell from, EsxiShell to, Compression asked, @CheckForNull PrintStream log)
            throws VSphereException {
        for (Compression candidate : asked.fallbacks()) {
            if (candidate.getTool() == null || (has(from, candidate.getTool()) && has(to, candidate.getTool()))) {
                if (candidate != asked) {
                    say(
                            log,
                            "The hosts do not both have " + asked.getTool() + "; compressing the copy with "
                                    + (candidate.getTool() == null ? "nothing" : candidate.getTool()) + " instead");
                }
                return candidate;
            }
        }
        return Compression.NONE;
    }

    /** The sizes of the files, by {@code ls -ln}, which fails if any is missing. */
    private static long[] sizes(EsxiShell shell, String directory, List<String> names, String what)
            throws VSphereException {
        final StringBuilder command = new StringBuilder("ls -ln");
        for (String name : names) {
            command.append(' ').append(ShellQuote.quote(directory + "/" + name));
        }
        final ShellResult listing = shell.run(command.toString());
        if (!listing.succeeded()) {
            throw new VSphereException(what + ": "
                    + (listing.getStderr().trim().isEmpty()
                            ? listing.getStdout().trim()
                            : listing.getStderr().trim()));
        }
        final long[] sizes = new long[names.size()];
        for (int i = 0; i < sizes.length; i++) {
            final String path = directory + "/" + names.get(i);
            long found = -1;
            for (String line : listing.getStdout().split("\\R")) {
                final String[] columns = line.trim().split("\\s+", 6);
                if (line.trim().endsWith(path) && columns.length >= 5) {
                    try {
                        found = Long.parseLong(columns[4]);
                    } catch (NumberFormatException e) {
                        // not a listing line
                    }
                }
            }
            if (found < 0) {
                throw new VSphereException(what + ": " + path + " is not there");
            }
            sizes[i] = found;
        }
        return sizes;
    }

    private static final class Counting extends FilterOutputStream {
        private final AtomicLong count;

        Counting(OutputStream out, AtomicLong count) {
            super(out);
            this.count = count;
        }

        @Override
        public void write(int b) throws IOException {
            out.write(b);
            count.incrementAndGet();
        }

        @Override
        public void write(byte[] b, int off, int len) throws IOException {
            out.write(b, off, len);
            count.addAndGet(len);
        }
    }

    /**
     * Copies the files, in the folder of the source, to the folder of the target (made if it is not there; a file
     * that is there already is replaced).
     *
     * @param names plain names of files in {@code sourceDir}
     * @param idleSeconds how long nothing may move, in either session, before the copy is given up on
     */
    public static Result copy(
            EsxiShell from,
            String fromLabel,
            EsxiShell to,
            String toLabel,
            String sourceDir,
            List<String> names,
            String targetDir,
            Compression requested,
            int idleSeconds,
            @CheckForNull PrintStream log)
            throws VSphereException {
        if (names.isEmpty()) {
            throw new VSphereException("There are no files to copy");
        }
        for (String name : names) {
            EsxiDatastoreFiles.checkName("The name of a file to copy", name);
        }
        for (String dir : new String[] {sourceDir, targetDir}) {
            if (!EsxiDatastoreFiles.isInsideADatastoreFolder(dir)) {
                throw new VSphereException(dir + " is not a folder in a datastore");
            }
        }
        final long started = System.currentTimeMillis();
        final long[] sizes = sizes(from, sourceDir, names, "Cannot copy from " + fromLabel);
        long total = 0;
        for (long size : sizes) {
            total += size;
        }
        final Compression compression = choose(from, to, requested, log);
        final String tool = compression.getTool();

        final EsxiDatastoreFiles targetFiles = new EsxiDatastoreFiles(to);
        final String staging =
                targetDir + "/.jenkins-incoming-" + UUID.randomUUID().toString().substring(0, 8);
        targetFiles.mkdirs(staging);

        final StringBuilder pack = new StringBuilder("tar cf - -C ").append(ShellQuote.quote(sourceDir));
        for (String name : names) {
            pack.append(' ').append(ShellQuote.quote(name));
        }
        // A pipeline ends with the status of its last command, so that of tar is told by what it prints
        final String packCommand = "(" + pack + " || echo TAR-FAILED >&2)" + (tool == null ? "" : " | " + tool + " -1");
        final String unpackCommand = (tool == null ? "" : tool + " -d | ") + "tar xf - -C " + ShellQuote.quote(staging);

        say(
                log,
                "Copying " + names.size() + " file(s), " + total + " bytes, from " + fromLabel + " to " + toLabel
                        + " through the controller, " + (tool == null ? "not compressed" : "compressed with " + tool));

        final AtomicLong transferred = new AtomicLong();
        final PipedInputStream pipeIn = new PipedInputStream(PIPE_BYTES);
        final ExecutorService workers = Executors.newFixedThreadPool(3, runnable -> {
            final Thread thread = new Thread(runnable, "esxi-relay");
            thread.setDaemon(true);
            return thread;
        });
        boolean copied = false;
        try {
            final PipedOutputStream pipeOut = new PipedOutputStream(pipeIn);
            final Future<ShellResult> packing = workers.submit(() -> {
                try (OutputStream sink = new Counting(pipeOut, transferred)) {
                    return from.stream(packCommand, null, sink, idleSeconds);
                } finally {
                    pipeOut.close();
                }
            });
            final Future<ShellResult> unpacking = workers.submit(() -> {
                try {
                    return to.stream(unpackCommand, pipeIn, null, idleSeconds);
                } finally {
                    pipeIn.close();
                }
            });
            final Future<?> progress = workers.submit(() -> {
                try {
                    while (!Thread.currentThread().isInterrupted()) {
                        Thread.sleep(PROGRESS_EVERY_MILLIS);
                        say(log, "Copied " + transferred.get() + " bytes of the stream so far");
                    }
                } catch (InterruptedException e) {
                    // over
                }
                return null;
            });
            try {
                ShellResult packed = null;
                ShellResult unpacked = null;
                VSphereException packingFailure = null;
                VSphereException unpackingFailure = null;
                try {
                    packed = wait(packing, "Reading the files on " + fromLabel);
                } catch (VSphereException e) {
                    packingFailure = e;
                }
                try {
                    unpacked = wait(unpacking, "Writing the files on " + toLabel);
                } catch (VSphereException e) {
                    unpackingFailure = e;
                }
                if (packingFailure != null && unpackingFailure != null) {
                    // when one end fails the other is cut off, and says only that: the cause is the first to fail
                    final boolean cutOff =
                            String.valueOf(packingFailure.getMessage()).contains("Pipe");
                    throw cutOff ? unpackingFailure : packingFailure;
                }
                if (packingFailure != null) {
                    throw packingFailure;
                }
                if (unpackingFailure != null) {
                    throw unpackingFailure;
                }
                if (packed == null || unpacked == null) {
                    throw new IllegalStateException("A copy that did not fail has no outcome");
                }
                // A source that was cut off by a target that failed says only that its pipe is closed
                final boolean cutOff = packed.getStderr().contains("Pipe closed")
                        || packed.getStderr().contains("Broken pipe")
                        || packed.getExitCode() == 141;
                if (!unpacked.succeeded() && cutOff) {
                    throw new VSphereException("Writing the files on " + toLabel + " failed" + explanation(unpacked));
                }
                if (!packed.succeeded() || packed.getStderr().contains("TAR-FAILED")) {
                    throw new VSphereException("Reading the files on " + fromLabel + " failed" + explanation(packed));
                }
                if (!unpacked.succeeded()) {
                    throw new VSphereException("Writing the files on " + toLabel + " failed" + explanation(unpacked));
                }
            } finally {
                progress.cancel(true);
            }

            // The files that arrived are the files that were sent, if they are as large
            final long[] arrived = sizes(to, staging, names, "The copy to " + toLabel + " is not complete");
            for (int i = 0; i < arrived.length; i++) {
                if (arrived[i] != sizes[i]) {
                    throw new VSphereException("The copy of " + names.get(i) + " to " + toLabel + " has " + arrived[i]
                            + " bytes, not the " + sizes[i] + " that it has on " + fromLabel);
                }
            }
            for (String name : names) {
                to.run("mv -f " + ShellQuote.quote(staging + "/" + name) + " "
                                + ShellQuote.quote(targetDir + "/" + name))
                        .stdoutOrThrow("Moving " + name + " in place on " + toLabel);
            }
            copied = true;
        } catch (IOException e) {
            throw new VSphereException(
                    "Copying files from " + fromLabel + " to " + toLabel + " failed: " + e.getMessage(), e);
        } finally {
            workers.shutdownNow();
            try {
                pipeIn.close();
            } catch (IOException e) {
                // nothing is reading it any more
            }
            try {
                targetFiles.removeFolder(staging);
            } catch (VSphereException e) {
                say(log, "Could not remove " + staging + " on " + toLabel + ": " + e.getMessage());
            }
            if (!copied) {
                say(log, "The copy to " + toLabel + " did not complete; nothing of it is left in " + targetDir);
            }
        }
        final Result result =
                new Result(names.size(), total, transferred.get(), compression, System.currentTimeMillis() - started);
        say(
                log,
                "Copied " + names.size() + " file(s), " + total + " bytes, sending " + transferred.get() + " bytes"
                        + " in " + result.getMillis() / 1000 + " seconds");
        return result;
    }

    private static String explanation(ShellResult result) {
        final String text = result.getStderr().replace("TAR-FAILED", "").trim();
        return " (exit code " + result.getExitCode() + (text.isEmpty() ? "" : ": " + text) + ")";
    }

    private static ShellResult wait(Future<ShellResult> work, String what) throws VSphereException {
        try {
            return work.get();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new VSphereException(what + " was interrupted", e);
        } catch (ExecutionException e) {
            final Throwable cause = e.getCause() == null ? e : e.getCause();
            if (cause instanceof VSphereException) {
                throw (VSphereException) cause;
            }
            throw new VSphereException(what + " failed: " + cause.getMessage(), cause);
        }
    }
}
