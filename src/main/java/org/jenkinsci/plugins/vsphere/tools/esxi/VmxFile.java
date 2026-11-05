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
 * The text of a VM's {@code .vmx} configuration file: lines of {@code key = "value"}. Keys are matched without
 * regard to case, as the hypervisor does; lines that are not settings (comments, blanks) are kept as they were.
 */
public final class VmxFile {

    private static final Pattern SETTING = Pattern.compile("^\\s*([^=\\s#][^=]*?)\\s*=\\s*\"(.*)\"\\s*$");

    private final List<String> lines = new ArrayList<>();

    private VmxFile() {}

    public static VmxFile parse(@CheckForNull String text) {
        final VmxFile vmx = new VmxFile();
        if (text != null && !text.isEmpty()) {
            vmx.lines.addAll(List.of(text.split("\\R", -1)));
            if (!vmx.lines.isEmpty() && vmx.lines.get(vmx.lines.size() - 1).isEmpty()) {
                vmx.lines.remove(vmx.lines.size() - 1);
            }
        }
        return vmx;
    }

    private static @CheckForNull Matcher settingOf(String line) {
        final Matcher m = SETTING.matcher(line);
        return m.matches() ? m : null;
    }

    /** The value of a setting, or null if there is none. */
    public @CheckForNull String get(String key) {
        for (String line : lines) {
            final Matcher m = settingOf(line);
            if (m != null && m.group(1).equalsIgnoreCase(key)) {
                return m.group(2);
            }
        }
        return null;
    }

    public String get(String key, String defaultValue) {
        final String value = get(key);
        return value == null ? defaultValue : value;
    }

    public boolean getBoolean(String key) {
        return "true".equalsIgnoreCase(get(key));
    }

    /** The value of a number setting, or the default if it is missing or not a number. */
    public int getInt(String key, int defaultValue) {
        final String value = get(key);
        if (value == null) {
            return defaultValue;
        }
        try {
            return Integer.parseInt(value.trim());
        } catch (NumberFormatException e) {
            return defaultValue;
        }
    }

    /** Sets a setting, replacing it where it is, or adding it at the end. */
    public void put(String key, String value) {
        final String line = key + " = \"" + value + "\"";
        for (int i = 0; i < lines.size(); i++) {
            final Matcher m = settingOf(lines.get(i));
            if (m != null && m.group(1).equalsIgnoreCase(key)) {
                lines.set(i, line);
                return;
            }
        }
        lines.add(line);
    }

    /** Removes a setting; true if it was there. */
    public boolean remove(String key) {
        for (int i = 0; i < lines.size(); i++) {
            final Matcher m = settingOf(lines.get(i));
            if (m != null && m.group(1).equalsIgnoreCase(key)) {
                lines.remove(i);
                return true;
            }
        }
        return false;
    }

    /** The keys of all the settings, in file order, in the case they are written in. */
    public List<String> keys() {
        final List<String> keys = new ArrayList<>();
        for (String line : lines) {
            final Matcher m = settingOf(line);
            if (m != null) {
                keys.add(m.group(1));
            }
        }
        return keys;
    }

    /** True if some setting has a key that starts with the prefix followed by a dot, e.g. {@code ethernet0}. */
    public boolean hasSettingsUnder(String prefix) {
        final String dotted = prefix.toLowerCase() + ".";
        for (String key : keys()) {
            if (key.toLowerCase().startsWith(dotted)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Makes text fit in the value of a setting: what has a meaning in the file is written as {@code |} and two
     * hex digits ({@code |22} for a quote, {@code |0A} for a line break, {@code |7C} for the bar itself).
     */
    public static String escape(String text) {
        final StringBuilder out = new StringBuilder();
        for (char c : text.toCharArray()) {
            switch (c) {
                case '|':
                    out.append("|7C");
                    break;
                case '"':
                    out.append("|22");
                    break;
                case '\n':
                    out.append("|0A");
                    break;
                case '\r':
                    out.append("|0D");
                    break;
                default:
                    out.append(c);
            }
        }
        return out.toString();
    }

    /** The text that a value of a setting stands for, see {@link #escape(String)}. */
    public static String unescape(String value) {
        final StringBuilder out = new StringBuilder();
        for (int i = 0; i < value.length(); i++) {
            final char c = value.charAt(i);
            if (c == '|' && i + 2 < value.length() && isHex(value.charAt(i + 1)) && isHex(value.charAt(i + 2))) {
                out.append((char) Integer.parseInt(value.substring(i + 1, i + 3), 16));
                i += 2;
            } else {
                out.append(c);
            }
        }
        return out.toString();
    }

    private static boolean isHex(char c) {
        return Character.digit(c, 16) >= 0;
    }

    @Override
    public String toString() {
        return String.join("\n", lines) + (lines.isEmpty() ? "" : "\n");
    }
}
