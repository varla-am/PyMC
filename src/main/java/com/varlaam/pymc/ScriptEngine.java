package com.varlaam.pymc;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.logging.Logger;
import java.util.stream.Stream;

import org.bukkit.command.CommandSender;

/** Something that can run PyMC scripts: python3 processes or GraalPy inside the JVM. */
interface ScriptEngine {

    /** What a script said about itself during discovery. */
    record Discovered(Path script, String usage) {}

    /** A single script's answer to "which command are you?". */
    record Registration(String command, String usage) {}

    String name();

    /** Runs one script in discover mode; null if it didn't register (the reason is logged). */
    Registration probe(Path script);

    /** Runs a script for one use of its command, off the main thread. */
    void run(String command, Path script, CommandSender sender, String[] args);

    void stopAll();

    Logger logger();

    /** Asks every script in the folder; returns command name -> script. */
    default Map<String, Discovered> discover(Path scriptsDir) {
        Map<String, Discovered> found = new TreeMap<>();
        List<Path> scripts;
        try (Stream<Path> files = Files.list(scriptsDir)) {
            scripts = files.filter(p -> p.getFileName().toString().endsWith(".py")).sorted().toList();
        } catch (IOException e) {
            logger().warning("Cannot read " + scriptsDir + ": " + e.getMessage());
            return found;
        }
        for (Path script : scripts) {
            String file = script.getFileName().toString();
            Registration reg = probe(script);
            if (reg == null) {
                continue;
            }
            if (found.containsKey(reg.command())) {
                logger().warning(file + " also wants /" + reg.command() + ", already taken by "
                        + found.get(reg.command()).script().getFileName() + ", skipped");
            } else {
                found.put(reg.command(), new Discovered(script, reg.usage() == null ? "/" + reg.command() : reg.usage()));
            }
        }
        return found;
    }
}
