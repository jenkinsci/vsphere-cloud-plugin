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
import java.io.PrintStream;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import org.jenkinsci.plugins.vsphere.tools.VSphereException;
import org.jenkinsci.plugins.vsphere.tools.VSphereLogger;

/**
 * Watches a folder on the target of a copy that goes from host to host, where the controller sees none of the bytes:
 * the files that are unpacked there grow as long as the copy is going on. If they do not for as long as the idle time
 * (and so nothing moves, though nothing has failed either), what was told to is done to end the copy.
 */
final class EsxiStallWatch implements AutoCloseable {

    private final AtomicBoolean stalled = new AtomicBoolean();
    private final Thread thread;

    /**
     * @param shell the target
     * @param directory the folder that the files are unpacked in
     * @param progress told the size of what is there, whenever it changes
     * @param onStall what ends the copy
     */
    EsxiStallWatch(
            EsxiShell shell,
            String directory,
            int idleSeconds,
            long pollMillis,
            AtomicLong progress,
            @CheckForNull PrintStream log,
            String toLabel,
            Runnable onStall) {
        thread = new Thread(
                () -> {
                    long last = -1;
                    long changed = System.nanoTime();
                    long reported = System.nanoTime();
                    try {
                        while (!Thread.currentThread().isInterrupted()) {
                            Thread.sleep(pollMillis);
                            final long size = sizeOf(shell, directory);
                            final long now = System.nanoTime();
                            if (size >= 0 && size != last) {
                                last = size;
                                changed = now;
                                progress.set(size);
                            }
                            if (log != null && now - reported > 30_000_000_000L && last >= 0) {
                                reported = now;
                                VSphereLogger.vsLogger(log, "Written " + last + " bytes so far on " + toLabel);
                            }
                            if (now - changed > idleSeconds * 1_000_000_000L) {
                                stalled.set(true);
                                onStall.run();
                                return;
                            }
                        }
                    } catch (InterruptedException e) {
                        // the copy is over
                    }
                },
                "esxi-stall-watch");
        thread.setDaemon(true);
        thread.start();
    }

    /** The bytes in the files of the folder, or -1 if that could not be told. */
    static long sizeOf(EsxiShell shell, String directory) {
        try {
            final ShellResult listing = shell.run("ls -lnA " + ShellQuote.quote(directory));
            if (!listing.succeeded()) {
                return -1;
            }
            long total = 0;
            for (String line : listing.getStdout().split("\\R")) {
                final String[] columns = line.trim().split("\\s+");
                if (columns.length >= 6 && columns[0].startsWith("-")) {
                    try {
                        total += Long.parseLong(columns[4]);
                    } catch (NumberFormatException e) {
                        // not a line of a file
                    }
                }
            }
            return total;
        } catch (VSphereException e) {
            return -1;
        }
    }

    boolean stalled() {
        return stalled.get();
    }

    @Override
    public void close() {
        thread.interrupt();
    }
}
