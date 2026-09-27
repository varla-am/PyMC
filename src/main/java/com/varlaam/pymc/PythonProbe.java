package com.varlaam.pymc;

import java.nio.charset.StandardCharsets;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Is there a usable Python 3.9+ on this machine? Used by the loader and the plugin. */
final class PythonProbe {
    private static final Pattern VERSION = Pattern.compile("^(\\d+)\\.(\\d+)$");

    private PythonProbe() {}

    /** "3.13" or null if the interpreter can't be started. */
    static String version(String python) {
        try {
            Process p = new ProcessBuilder(python, "-c", "import sys; print('%d.%d' % sys.version_info[:2])")
                    .redirectErrorStream(true).start();
            if (!p.waitFor(5, TimeUnit.SECONDS)) {
                p.destroyForcibly();
                return null;
            }
            String out = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8).trim();
            return p.exitValue() == 0 ? out : null;
        } catch (Exception e) {
            return null;
        }
    }

    static boolean usable(String version) {
        if (version == null) {
            return false;
        }
        Matcher m = VERSION.matcher(version);
        return m.matches() && (Integer.parseInt(m.group(1)) > 3
                || Integer.parseInt(m.group(1)) == 3 && Integer.parseInt(m.group(2)) >= 9);
    }
}
