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

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;
import org.jenkinsci.plugins.vsphere.tools.VSphereException;

/**
 * The few things that are done to the files of a datastore of an ESXi host, through its shell: read and write a
 * text file, make a directory, copy a file, list a directory, see whether a file can be read, and remove a whole
 * directory. Anything that is more than that (editing a {@code .vmx}, working out the disk chain) is done here, in
 * Java, on the text, so that what is run on the host stays small, and quoted.
 *
 * <p>Where anything is removed is checked, so that a bad name cannot take anything but a directory in a datastore.
 */
final class EsxiDatastoreFiles {

    private static final String VOLUMES = "/vmfs/volumes/";

    /** What the name of a VM or of a file made for one may be made of. */
    private static final Pattern SAFE_NAME = Pattern.compile("[A-Za-z0-9][A-Za-z0-9 ._#+=@()-]*");

    private final EsxiShell shell;

    EsxiDatastoreFiles(EsxiShell shell) {
        this.shell = shell;
    }

    /** The name of what is made on the host (a VM, its folder, its files), which cannot be anything that is not plain. */
    static String checkName(String what, String name) throws VSphereException {
        if (name == null || name.isEmpty()) {
            throw new VSphereException(what + " is not specified");
        }
        if (name.length() > 80 || !SAFE_NAME.matcher(name).matches() || name.contains("..")) {
            throw new VSphereException(what + " \"" + name + "\" cannot be used on an ESXi host over SSH: it has to"
                    + " start with a letter or digit, be at most 80 long, and have nothing but letters, digits,"
                    + " spaces and . _ # + = @ ( ) - in it");
        }
        return name;
    }

    /** True if the path is inside of a datastore, below a folder of it, with no way out of it. */
    static boolean isInsideADatastoreFolder(String path) {
        if (path == null || !path.startsWith(VOLUMES) || path.contains("..") || path.contains("//")) {
            return false;
        }
        final String[] below = path.substring(VOLUMES.length()).split("/");
        return below.length >= 2 && !below[0].isEmpty() && !below[1].isEmpty();
    }

    String read(String path) throws VSphereException {
        return shell.run("cat " + ShellQuote.quote(path)).stdoutOrThrow("Reading " + path);
    }

    void write(String path, String content) throws VSphereException {
        shell.run("printf '%s' " + ShellQuote.quote(content) + " > " + ShellQuote.quote(path))
                .stdoutOrThrow("Writing " + path);
    }

    /**
     * Replaces a file with new content, by writing the content next to it and moving that over it, so that a
     * failure on the way does not leave a half-written file in its place.
     */
    void replace(String path, String content) throws VSphereException {
        final String partial = path + ".jenkins-new";
        write(partial, content);
        final ShellResult moved = shell.run("mv -f " + ShellQuote.quote(partial) + " " + ShellQuote.quote(path));
        if (!moved.succeeded()) {
            shell.run("rm -f " + ShellQuote.quote(partial));
            moved.stdoutOrThrow("Replacing " + path);
        }
    }

    void mkdirs(String directory) throws VSphereException {
        shell.run("mkdir -p " + ShellQuote.quote(directory)).stdoutOrThrow("Making " + directory);
    }

    void copy(String from, String to) throws VSphereException {
        shell.run("cp " + ShellQuote.quote(from) + " " + ShellQuote.quote(to))
                .stdoutOrThrow("Copying " + from + " to " + to);
    }

    /** The names of what is in a directory. */
    List<String> list(String directory) throws VSphereException {
        final List<String> names = new ArrayList<>();
        for (String line : shell.run("ls -1 " + ShellQuote.quote(directory))
                .stdoutOrThrow("Listing " + directory)
                .split("\\R")) {
            if (!line.trim().isEmpty()) {
                names.add(line.trim());
            }
        }
        return names;
    }

    boolean exists(String path) throws VSphereException {
        return shell.run("test -e " + ShellQuote.quote(path)).succeeded();
    }

    /**
     * Whether the file can be read, which a disk that is in use by a VM that is running cannot (it is locked):
     * one byte of it is read.
     */
    boolean canRead(String path) throws VSphereException {
        return shell.run("dd if=" + ShellQuote.quote(path) + " of=/dev/null bs=1 count=1")
                .succeeded();
    }

    /** The descriptor of a disk, which fails with a message of its own if there is none. */
    VmdkDescriptor readDescriptor(String path) throws VSphereException {
        return VmdkDescriptor.parse(read(path));
    }

    /** Makes a disk of the size, thin or thick (lazily zeroed). */
    void createDisk(String path, long sizeKb, boolean thin) throws VSphereException {
        mkdirs(path.substring(0, path.lastIndexOf('/')));
        shell.run("vmkfstools -c " + ShellQuote.quote(sizeKb + "K") + " -d " + (thin ? "thin " : "zeroedthick ")
                        + ShellQuote.quote(path))
                .stdoutOrThrow("Making the disk " + path);
    }

    /** Makes a disk larger. */
    void extendDisk(String path, long sizeKb) throws VSphereException {
        shell.run("vmkfstools -X " + ShellQuote.quote(sizeKb + "K") + " " + ShellQuote.quote(path))
                .stdoutOrThrow("Making the disk " + path + " larger");
    }

    /** Deletes a disk, all the files of it. Only a disk in a datastore folder. */
    void deleteDisk(String path) throws VSphereException {
        if (!isInsideADatastoreFolder(path) || !path.endsWith(".vmdk")) {
            throw new VSphereException("Refusing to delete " + path + ": it is not a disk in a datastore folder");
        }
        shell.run("vmkfstools -U " + ShellQuote.quote(path)).stdoutOrThrow("Deleting the disk " + path);
    }

    /** What a path is really, with the links in it (a datastore is also there by its name) resolved. */
    String canonical(String path) throws VSphereException {
        final String resolved = shell.run("readlink -f " + ShellQuote.quote(path))
                .stdoutOrThrow("Resolving " + path)
                .trim();
        return resolved.isEmpty() ? path : resolved;
    }

    /** Removes a folder that was made for a VM, and what is in it; nothing that is not one is touched. */
    void removeFolder(String directory) throws VSphereException {
        if (!isInsideADatastoreFolder(directory)) {
            throw new VSphereException("Refusing to remove " + directory + ": it is not a folder in a datastore");
        }
        shell.run("rm -rf " + ShellQuote.quote(directory)).stdoutOrThrow("Removing " + directory);
    }

    /**
     * The space that a file takes on the datastore, in KB, as {@code du -k} says (not the size it presents: a thin or
     * sparse file takes what is written to it), or -1 if that could not be told.
     */
    long allocatedKb(String path) throws VSphereException {
        final ShellResult result = shell.run("du -k " + ShellQuote.quote(path));
        if (!result.succeeded()) {
            return -1;
        }
        final String[] columns = result.getStdout().trim().split("\\s+", 2);
        try {
            return Long.parseLong(columns[0]);
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    /** The shell that the files are reached through. */
    EsxiShell shell() {
        return shell;
    }

    /** Runs a command that is made up of quoted parts, which the caller made safe. */
    ShellResult run(String command) throws VSphereException {
        return shell.run(command);
    }
}
