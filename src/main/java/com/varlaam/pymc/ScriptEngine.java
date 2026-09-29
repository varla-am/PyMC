package com.varlaam.pymc;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.logging.Logger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import com.google.gson.Gson;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import org.bukkit.command.CommandSender;

/** Something that can run PyMC scripts: python3 processes or GraalPy inside the JVM. */
interface ScriptEngine {
    Gson GSON = new Gson();
    Pattern TRACEBACK_LINE = Pattern.compile("^\\s*File \"(.+)\", line (\\d+)");

    /** What a script declared during discovery: a command with its usage line, or events. */
    record Registration(String command, String usage, List<String> events) {

        /** The "register" message a script sends from mcmod.triggerCommand / mcmod.event. */
        static Registration of(JsonObject msg) {
            if (msg == null || !"register".equals(Requests.str(msg, "type"))) {
                return null;
            }
            List<String> events = new ArrayList<>();
            if (msg.has("events") && msg.get("events").isJsonArray()) {
                for (JsonElement e : msg.getAsJsonArray("events")) {
                    events.add(e.getAsString());
                }
            }
            return new Registration(Requests.str(msg, "command"), Requests.str(msg, "usage"), List.copyOf(events));
        }
    }

    /** One script's answer to "what do you trigger on?"; problem says why it can't be loaded. */
    record Probe(Registration registration, String problem) {
        static Probe ok(Registration registration) {
            return new Probe(registration, null);
        }

        static Probe failed(String problem) {
            return new Probe(null, problem);
        }
    }

    String name();

    /** Runs one script in discover mode and reports what it declared or what went wrong. */
    Probe probe(Path script);

    /** Starts a script off the main thread; false if too many scripts are running already. */
    boolean start(Path script, CommandSender sender, Map<String, String> env);

    void stopAll();

    Logger logger();

    /** Asks every script in the folder; script -> its answer, sorted by file name. */
    default Map<Path, Probe> discover(Path scriptsDir) {
        Map<Path, Probe> found = new TreeMap<>();
        for (Path script : scripts(scriptsDir, logger())) {
            found.put(script, probe(script));
        }
        return found;
    }

    static List<Path> scripts(Path scriptsDir, Logger logger) {
        try (Stream<Path> files = Files.list(scriptsDir)) {
            return files.filter(p -> p.getFileName().toString().endsWith(".py") && Files.isRegularFile(p))
                    .sorted().toList();
        } catch (IOException e) {
            logger.warning("Cannot read " + scriptsDir + ": " + e.getMessage());
            return List.of();
        }
    }

    /** Environment for one use of a script's command. */
    static Map<String, String> commandEnv(String command, CommandSender sender, String[] args) {
        return Map.of(
                "PYMC_MODE", "run",
                "PYMC_COMMAND", command,
                "PYMC_SENDER", sender.getName(),
                "PYMC_ARGS", GSON.toJson(args));
    }

    /** Environment for one event a script listens to; data becomes mcmod.event(...)'s result. */
    static Map<String, String> eventEnv(String event, CommandSender sender, Map<String, Object> data) {
        return Map.of(
                "PYMC_MODE", "run",
                "PYMC_EVENT", event,
                "PYMC_EVENT_DATA", GSON.toJson(data),
                "PYMC_SENDER", sender.getName());
    }

    /**
     * Turns what a failed script printed into one line for the sender: "line 3: SyntaxError: ...".
     * The line number is the last traceback entry that points into the script itself.
     */
    static String describe(List<String> output, String file) {
        String line = null;
        String message = null;
        for (String l : output) {
            Matcher m = TRACEBACK_LINE.matcher(l);
            if (m.find() && (m.group(1).equals(file) || m.group(1).endsWith("/" + file)
                    || m.group(1).endsWith("\\" + file))) {
                line = m.group(2);
            }
            if (!l.isBlank() && !Character.isWhitespace(l.charAt(0))) {
                message = l.trim();
            }
        }
        if (message == null) {
            message = "the script stopped with an error, see the server log";
        }
        return line == null ? message : "line " + line + ": " + message;
    }
}
