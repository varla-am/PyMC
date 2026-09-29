package com.varlaam.pymc;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;

import org.bukkit.command.Command;
import org.bukkit.command.CommandMap;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;

/**
 * mcmod: finds Python scripts in plugins/PyMC/scripts, asks each which command or events it
 * answers to, and registers them. Scripts run on python3 or, if there is none, on GraalPy.
 */
public final class PyMCPlugin extends JavaPlugin {
    private static final String FALLBACK_PREFIX = "pymc";
    private static final List<String> PYTHON_FILES =
            List.of("__init__.py", "_bridge.py", "mcmod.py", "mccommand.py");
    private static final List<String> EXAMPLE_SCRIPTS = List.of("hello.py", "anobc.py", "welcome.py");
    private static final String RELEASE = "Alpha 3.0.0";

    private final Map<String, ScriptCommand> commands = new LinkedHashMap<>();
    /** Every script's last discovery answer, by file; /pymc reload <script> replaces one entry. */
    private final Map<Path, ScriptEngine.Probe> probes = new TreeMap<>();
    /** event -> scripts listening to it; replaced whole, read from any thread. */
    private volatile Map<String, List<Path>> events = Map.of();
    private Requests requests;
    private volatile ScriptEngine engine;
    private Path scriptsDir;
    private Path pythonDir;
    private boolean reloading;
    private boolean graalUsed;
    private volatile long lastBusyWarning;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        scriptsDir = getDataFolder().toPath().resolve("scripts");
        pythonDir = getDataFolder().toPath().resolve("python");
        requests = new Requests(this);
        try {
            installPythonPackage();
            installExampleScripts();
        } catch (IOException e) {
            getLogger().severe("Could not prepare " + getDataFolder() + ": " + e.getMessage());
            getServer().getPluginManager().disablePlugin(this);
            return;
        }
        getServer().getCommandMap().register(FALLBACK_PREFIX, new AdminCommand());
        getServer().getPluginManager().registerEvents(new EventBridge(this), this);
        loadScripts();
    }

    @Override
    public void onDisable() {
        if (engine != null) {
            engine.stopAll();
        }
        unregisterAll();
        events = Map.of();
        if (graalUsed) {
            try {
                Class.forName("com.varlaam.pymc.GraalEngine").getDeclaredMethod("shutdown").invoke(null);
            } catch (ReflectiveOperationException ignored) {
                // nothing to shut down
            }
        }
    }

    /** Startup: blocking is fine, commands must exist before players join. */
    private void loadScripts() {
        reloadConfig();
        ScriptEngine fresh = newEngine();
        swapEngine(fresh);
        apply(fresh == null ? Map.of() : fresh.discover(scriptsDir));
        fireLoad(null);
    }

    /**
     * /pymc reload: scripts are asked off the main thread, so one that dawdles before its
     * declaration can't freeze the server; the swap itself happens on the main thread.
     */
    private void reloadScripts(CommandSender sender) {
        if (reloading) {
            say(sender, "&cPyMC: a reload is already running");
            return;
        }
        reloading = true;
        reloadConfig();
        ScriptEngine fresh = newEngine();
        say(sender, "&7PyMC: reloading and checking scripts...");
        getServer().getScheduler().runTaskAsynchronously(this, () -> {
            Map<Path, ScriptEngine.Probe> found = fresh == null ? Map.of() : fresh.discover(scriptsDir);
            getServer().getScheduler().runTask(this, () -> {
                reloading = false;
                swapEngine(fresh);
                Map<Path, String> problems = apply(found);
                if (fresh == null) {
                    say(sender, "&cPyMC: scripts are disabled, no engine could start - see the server log");
                    return;
                }
                say(sender, "&aPyMC: loaded " + commands.size() + " command(s) and "
                        + eventScripts() + " event script(s)");
                report(sender, problems.values().stream().toList(), found.size());
                fireLoad(null);
            });
        });
    }

    /**
     * /pymc reload &lt;script&gt;: checks one script for errors and swaps in only its command or events;
     * everything else keeps running. A deleted script is unloaded.
     */
    private void reloadScript(CommandSender sender, String name) {
        if (engine == null) {
            say(sender, "&cPyMC: scripts are disabled, no engine could start - see the server log");
            return;
        }
        if (reloading) {
            say(sender, "&cPyMC: a reload is already running");
            return;
        }
        Path script = findScript(name);
        if (script == null) {
            say(sender, "&cPyMC: no script " + name + " in " + scriptsDir);
            return;
        }
        String file = script.getFileName().toString();
        boolean exists = Files.isRegularFile(script);
        reloading = true;
        ScriptEngine current = engine;
        say(sender, "&7PyMC: " + (exists ? "checking " : "unloading ") + file + "...");
        getServer().getScheduler().runTaskAsynchronously(this, () -> {
            ScriptEngine.Probe probe = exists ? current.probe(script) : null;
            getServer().getScheduler().runTask(this, () -> {
                reloading = false;
                if (engine != current) {
                    say(sender, "&cPyMC: the scripts were reloaded meanwhile, try again");
                    return;
                }
                Map<Path, ScriptEngine.Probe> next = new TreeMap<>(probes);
                if (probe == null) {
                    next.remove(script);
                } else {
                    next.put(script, probe);
                }
                Map<Path, String> problems = apply(next);
                if (probe == null) {
                    say(sender, "&aPyMC: " + file + " is gone, unloaded");
                    return;
                }
                String problem = problems.get(script);
                if (problem != null) {
                    say(sender, "&cPyMC: " + problem);
                    say(sender, "&7The old version of " + file + " is unloaded; fix it and reload again.");
                    return;
                }
                ScriptEngine.Registration reg = probe.registration();
                say(sender, "&aPyMC: " + file + " has no errors, loaded: " + (reg.command() != null
                        ? commands.get(reg.command()).getUsage()
                        : "on " + String.join(", ", reg.events())));
                fireLoad(script);
            });
        });
    }

    /** A script in the scripts folder by name, ".py" optional; also a known one that was deleted. */
    private Path findScript(String name) {
        String file = name.endsWith(".py") ? name : name + ".py";
        for (Path script : ScriptEngine.scripts(scriptsDir, getLogger())) {
            if (script.getFileName().toString().equals(file)) {
                return script;
            }
        }
        for (Path known : probes.keySet()) {
            if (known.getFileName().toString().equals(file)) {
                return known;
            }
        }
        return null;
    }

    private void report(CommandSender sender, List<String> problems, int scripts) {
        if (problems.isEmpty()) {
            say(sender, "&aPyMC: " + scripts + " script(s) checked, no errors");
            return;
        }
        say(sender, "&cPyMC: " + problems.size() + " of " + scripts + " script(s) have errors and were skipped:");
        problems.forEach(p -> say(sender, "&c  " + p));
    }

    /** engine: auto | python | graalpy (config.yml). null if nothing can run scripts. */
    private ScriptEngine newEngine() {
        String mode = getConfig().getString("engine", "auto").toLowerCase();
        String python = getConfig().getString("python", "python3");
        int timeout = getConfig().getInt("script-timeout", 30);
        int maxRunning = getConfig().getInt("max-running-scripts", 8);
        boolean pythonOk = PythonProbe.usable(PythonProbe.version(python));
        boolean graalOk = graalAvailable();

        ScriptEngine chosen = null;
        switch (mode) {
            case "python" -> {
                if (!pythonOk) {
                    getLogger().severe("engine: python, but '" + python + "' is not a working Python 3.9+");
                } else {
                    chosen = new ProcessEngine(this, requests, python, pythonDir, timeout, maxRunning);
                }
            }
            case "graalpy" -> {
                if (!graalOk) {
                    getLogger().severe("engine: graalpy, but the GraalPy libraries did not load - see the log above");
                } else {
                    chosen = graal(timeout, maxRunning);
                }
            }
            default -> {
                if (pythonOk) {
                    chosen = new ProcessEngine(this, requests, python, pythonDir, timeout, maxRunning);
                } else if (graalOk) {
                    chosen = graal(timeout, maxRunning);
                } else {
                    getLogger().severe("No Python 3.9+ ('" + python + "') and GraalPy could not be loaded;"
                            + " scripts are disabled");
                }
            }
        }
        if (chosen != null) {
            getLogger().info("Running scripts with " + chosen.name());
        }
        return chosen;
    }

    private boolean graalAvailable() {
        try {
            Class.forName("org.graalvm.polyglot.Context", false, getClassLoader());
            return true;
        } catch (ClassNotFoundException e) {
            return false;
        }
    }

    /** GraalEngine is created via reflection so its GraalPy imports are never touched without GraalPy. */
    private ScriptEngine graal(int timeout, int maxRunning) {
        try {
            graalUsed = true;
            return (ScriptEngine) Class.forName("com.varlaam.pymc.GraalEngine")
                    .getDeclaredConstructor(PyMCPlugin.class, Requests.class, Path.class, int.class, int.class)
                    .newInstance(this, requests, pythonDir, timeout, maxRunning);
        } catch (ReflectiveOperationException | LinkageError e) {
            getLogger().severe("Could not start GraalPy: " + e);
            return null;
        }
    }

    private void swapEngine(ScriptEngine fresh) {
        if (engine != null) {
            engine.stopAll();
        }
        engine = fresh;
    }

    /**
     * Registers what the scripts declared, replacing the previous set. Returns the scripts
     * that could not be loaded, with the reason (also logged).
     */
    private Map<Path, String> apply(Map<Path, ScriptEngine.Probe> found) {
        unregisterAll();
        probes.clear();
        probes.putAll(found);

        Map<Path, String> problems = new LinkedHashMap<>();
        Map<String, List<Path>> byEvent = new TreeMap<>();
        CommandMap map = getServer().getCommandMap();
        for (Map.Entry<Path, ScriptEngine.Probe> entry : probes.entrySet()) {
            Path script = entry.getKey();
            String file = script.getFileName().toString();
            ScriptEngine.Probe probe = entry.getValue();
            ScriptEngine.Registration reg = probe.registration();
            if (probe.problem() != null) {
                problems.put(script, file + ": " + probe.problem());
            } else if (reg.command() != null) {
                ScriptCommand taken = commands.get(reg.command());
                if (taken != null) {
                    problems.put(script, file + ": /" + reg.command() + " is already taken by "
                            + taken.script().getFileName());
                    continue;
                }
                String usage = reg.usage() == null ? "/" + reg.command() : reg.usage();
                ScriptCommand command = new ScriptCommand(reg.command(), script, usage, engine);
                if (!map.register(FALLBACK_PREFIX, command)) {
                    getLogger().warning("/" + reg.command() + " already belongs to another plugin, use /"
                            + FALLBACK_PREFIX + ":" + reg.command());
                }
                commands.put(reg.command(), command);
            } else {
                List<String> unknown = reg.events().stream().filter(e -> !EventBridge.EVENTS.contains(e)).toList();
                if (reg.events().isEmpty() || !unknown.isEmpty()) {
                    problems.put(script, file + ": " + (unknown.isEmpty() ? "mcmod.event() names no event"
                            : "unknown event " + String.join(", ", unknown))
                            + " - known: " + String.join(", ", EventBridge.EVENTS));
                    continue;
                }
                for (String event : reg.events()) {
                    byEvent.computeIfAbsent(event, e -> new ArrayList<>()).add(script);
                }
            }
        }
        Map<String, List<Path>> frozen = new TreeMap<>();
        byEvent.forEach((event, scripts) -> frozen.put(event, List.copyOf(scripts)));
        events = Collections.unmodifiableMap(frozen);

        getServer().getOnlinePlayers().forEach(Player::updateCommands);
        problems.values().forEach(p -> getLogger().warning("Skipped " + p));
        getLogger().info("Loaded " + commands.size() + " script command(s)"
                + (commands.isEmpty() ? "" : ": /" + String.join(", /", commands.keySet()))
                + ", " + eventScripts() + " event script(s)"
                + (events.isEmpty() ? "" : " on " + String.join(", ", events.keySet())));
        return problems;
    }

    private long eventScripts() {
        return events.values().stream().flatMap(List::stream).distinct().count();
    }

    /** True if some script listens to this event. Safe from any thread. */
    boolean listens(String event) {
        return events.containsKey(event);
    }

    /** Starts every script that listens to this event. Safe from any thread (chat is async). */
    void fire(String event, CommandSender sender, Map<String, Object> data) {
        List<Path> scripts = events.get(event);
        ScriptEngine current = engine;
        if (scripts == null || current == null) {
            return;
        }
        Map<String, String> env = ScriptEngine.eventEnv(event, sender, data);
        for (Path script : scripts) {
            if (!current.start(script, sender, env)) {
                long now = System.currentTimeMillis();
                if (now - lastBusyWarning > 30_000) {
                    lastBusyWarning = now;
                    getLogger().warning("Too many scripts running, skipped " + event + " for "
                            + script.getFileName() + " (max-running-scripts in config.yml)");
                }
            }
        }
    }

    /** "load" runs once the scripts are loaded; only for one script after /pymc reload &lt;script&gt;. */
    private void fireLoad(Path only) {
        for (Path script : events.getOrDefault("load", List.of())) {
            if (only == null || only.equals(script)) {
                engine.start(script, getServer().getConsoleSender(),
                        ScriptEngine.eventEnv("load", getServer().getConsoleSender(), Map.of()));
            }
        }
    }

    private static void say(CommandSender sender, String message) {
        sender.sendMessage(LegacyComponentSerializer.legacyAmpersand().deserialize(message));
    }

    private void unregisterAll() {
        CommandMap map = getServer().getCommandMap();
        Map<String, Command> known = map.getKnownCommands();
        for (ScriptCommand command : commands.values()) {
            command.unregister(map);
            known.values().removeIf(c -> c == command);
        }
        commands.clear();
    }

    /** The pymc package ships inside the jar and is refreshed on every start. */
    private void installPythonPackage() throws IOException {
        Path target = pythonDir.resolve("pymc");
        Files.createDirectories(target);
        for (String file : PYTHON_FILES) {
            copyResource("python/pymc/" + file, target.resolve(file));
        }
    }

    /** Examples are only written into an empty scripts folder, never over your own. */
    private void installExampleScripts() throws IOException {
        if (Files.isDirectory(scriptsDir)) {
            return;
        }
        Files.createDirectories(scriptsDir);
        for (String file : EXAMPLE_SCRIPTS) {
            copyResource("scripts/" + file, scriptsDir.resolve(file));
        }
    }

    private void copyResource(String resource, Path target) throws IOException {
        try (InputStream in = getResource(resource)) {
            if (in == null) {
                throw new IOException(resource + " is missing from the plugin jar");
            }
            Files.copy(in, target, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    /** /pymc reload [script] | list | version - registered by hand, Paper plugins have no plugin.yml commands. */
    private final class AdminCommand extends Command {
        AdminCommand() {
            super("pymc", "Manage PyMC scripts", "/pymc <reload [script]|list|version>", List.of());
            setPermission("pymc.admin");
        }

        @Override
        public boolean execute(CommandSender sender, String label, String[] args) {
            if (!testPermission(sender)) {
                return true;
            }
            String sub = args.length >= 1 ? args[0].toLowerCase() : "";
            switch (sub) {
                case "reload" -> {
                    if (args.length == 1) {
                        reloadScripts(sender);
                    } else {
                        reloadScript(sender, String.join(" ", Arrays.copyOfRange(args, 1, args.length)));
                    }
                }
                case "list" -> list(sender);
                case "version" -> version(sender);
                default -> sender.sendMessage("Usage: " + getUsage());
            }
            return true;
        }

        private void list(CommandSender sender) {
            if (engine == null) {
                say(sender, "&cPyMC: scripts are disabled, no engine could start - see the server log");
                return;
            }
            sender.sendMessage("PyMC engine: " + engine.name());
            if (commands.isEmpty() && events.isEmpty()) {
                sender.sendMessage("PyMC: no scripts in " + scriptsDir);
            }
            commands.forEach((name, cmd) ->
                    sender.sendMessage(cmd.getUsage() + "  ->  " + cmd.script().getFileName()));
            events.forEach((event, scripts) -> scripts.forEach(script ->
                    sender.sendMessage("on " + event + "  ->  " + script.getFileName())));
            probes.forEach((script, probe) -> {
                if (probe.problem() != null) {
                    say(sender, "&c" + script.getFileName() + ": " + probe.problem());
                }
            });
        }

        private void version(CommandSender sender) {
            say(sender, "&aPyMC &f" + getPluginMeta().getVersion() + " &7(" + RELEASE + ")");
            sender.sendMessage("Engine: " + (engine == null ? "none, scripts are disabled" : engine.name()));
            sender.sendMessage("Server: " + getServer().getName() + " " + getServer().getMinecraftVersion()
                    + " (" + getServer().getVersion() + ")");
            sender.sendMessage("Java: " + System.getProperty("java.version"));
        }

        @Override
        public List<String> tabComplete(CommandSender sender, String alias, String[] args) {
            if (args.length == 1) {
                return List.of("reload", "list", "version").stream()
                        .filter(s -> s.startsWith(args[0].toLowerCase())).toList();
            }
            if (args.length == 2 && args[0].equalsIgnoreCase("reload")) {
                return ScriptEngine.scripts(scriptsDir, getLogger()).stream()
                        .map(p -> p.getFileName().toString())
                        .filter(f -> f.startsWith(args[1]))
                        .toList();
            }
            return List.of();
        }
    }
}
