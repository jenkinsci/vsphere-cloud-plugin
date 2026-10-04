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
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The text file that describes a virtual disk ({@code name.vmdk}): which files hold its data (the extents), and,
 * for the disk of a snapshot, which disk it is a change of (the parent). Only what is needed to build a linked
 * clone out of it is understood; the rest of the text is kept as it is.
 */
final class VmdkDescriptor {

    private static final Pattern PARENT = Pattern.compile("(?m)^(parentFileNameHint\\s*=\\s*\")([^\"]*)(\")");
    private static final Pattern EXTENT = Pattern.compile("(?m)^(RW|RDONLY|NOACCESS)\\s+\\d+\\s+\\w+\\s+\"([^\"]+)\"");

    private final String text;

    private VmdkDescriptor(String text) {
        this.text = text;
    }

    static VmdkDescriptor parse(String text) {
        return new VmdkDescriptor(text);
    }

    /** The disk that this one is a change of, as the descriptor names it; null for a disk that is not a snapshot's. */
    @CheckForNull
    String getParentHint() {
        final Matcher m = PARENT.matcher(text);
        return m.find() && !m.group(2).isEmpty() ? m.group(2) : null;
    }

    boolean isSnapshotDisk() {
        return getParentHint() != null;
    }

    /** The files that hold the data of the disk, as the descriptor names them. */
    List<String> getExtentFiles() {
        final List<String> extents = new ArrayList<>();
        final Matcher m = EXTENT.matcher(text);
        while (m.find()) {
            extents.add(m.group(2));
        }
        return extents;
    }

    /** The same descriptor, as a change of another disk. */
    VmdkDescriptor withParentHint(String parent) {
        final Matcher m = PARENT.matcher(text);
        if (!m.find()) {
            return this;
        }
        return new VmdkDescriptor(m.replaceFirst(Matcher.quoteReplacement(m.group(1) + parent + m.group(3))));
    }

    @Override
    public String toString() {
        return text;
    }
}
