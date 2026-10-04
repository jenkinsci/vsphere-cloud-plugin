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

    @Override
    public String toString() {
        return String.join("\n", lines) + (lines.isEmpty() ? "" : "\n");
    }
}
