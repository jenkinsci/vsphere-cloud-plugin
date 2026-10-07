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
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * The shares that are mounted read-only, from {@code esxcli storage nfs list} (and {@code nfs41}): a table whose
 * columns are as wide as the dashes under their titles say, with a {@code Volume Name} and a {@code Read-Only} column.
 */
final class EsxiNfsTable {

    private EsxiNfsTable() {}

    /** The names of the volumes that are read-only; none if the text is not such a table. */
    static Set<String> readOnlyVolumes(String output) {
        final Set<String> names = new HashSet<>();
        final String[] lines = output.split("\\R");
        for (int i = 1; i < lines.length; i++) {
            if (!lines[i].startsWith("---") || !lines[i - 1].startsWith("Volume Name")) {
                continue;
            }
            final List<int[]> spans = new ArrayList<>();
            final String rule = lines[i];
            for (int c = 0; c < rule.length(); c++) {
                if (rule.charAt(c) == '-' && (c == 0 || rule.charAt(c - 1) != '-')) {
                    int end = c;
                    while (end < rule.length() && rule.charAt(end) == '-') {
                        end++;
                    }
                    spans.add(new int[] {c, end});
                }
            }
            final String titles = lines[i - 1];
            int readOnly = -1;
            for (int c = 0; c < spans.size(); c++) {
                final int from = spans.get(c)[0];
                final int to = c == spans.size() - 1 ? titles.length() : spans.get(c)[1];
                if (from < titles.length()
                        && titles.substring(from, Math.min(to, titles.length()))
                                .trim()
                                .equals("Read-Only")) {
                    readOnly = c;
                }
            }
            if (readOnly < 0) {
                return names;
            }
            for (int r = i + 1; r < lines.length; r++) {
                final String line = lines[r];
                if (line.trim().isEmpty()) {
                    continue;
                }
                final String name = cell(line, spans.get(0)[0], spans.get(0)[1]);
                final int last = readOnly == spans.size() - 1 ? line.length() : spans.get(readOnly)[1];
                if (cell(line, spans.get(readOnly)[0], last).equalsIgnoreCase("true")) {
                    names.add(name);
                }
            }
            return names;
        }
        return names;
    }

    private static String cell(String line, int from, int to) {
        final int start = Math.min(from, line.length());
        return line.substring(start, Math.min(to, line.length())).trim();
    }
}
