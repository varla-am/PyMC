package com.varlaam.pymc;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.bukkit.command.Command;
import org.bukkit.command.CommandMap;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;

/**
 * mcmod: finds Python scripts in plugins/PyMC/scripts, asks each which command it answers to,
 * and registers those commands. Scripts run on python3 or, if there is none, on GraalPy.
 */
public final class PyMCPlugin extends JavaPlugin {
    private static final String FALLBACK_PREFIX = "pymc";
    private static final List<String> PYTHON_FILES =
            List.of("__init__.py", "_bridge.py", "mcmod.py", "mccommand.py");
    private static final List<String> EXAMPLE_SCRIPTS = List.of("hello.py", "anobc.py");

    private final Map<String, ScriptCommand> commands = new LinkedHashMap<>();
    private Requests requests;
    private ScriptEngine engine;
    private Path scriptsDir;
    private Path pythonDir;
    private boolean reloading;
    private boolean graalUsed;

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
        loadScripts();
    }

    @Override
    public void onDisable() {
        if (engine != null) {
            engine.stopAll();
        }
        unregisterAll();
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
        apply(fresh, fresh == null ? Map.of() : fresh.discover(scriptsDir));
    }

    /**
     * /pymc reload: scripts are asked off the main thread, so one that dawdles before
     * triggerCommand can't freeze the server; the swap itself happens on the main thread.
     */
    private void reloadScripts(CommandSender sender) {
        if (reloading) {
            sender.sendMessage("PyMC: a reload is already running");
            return;
        }
        reloading = true;
        reloadConfig();
        ScriptEngine fresh = newEngine();
        sender.sendMessage("PyMC: reloading scripts...");
        getServer().getScheduler().runTaskAsynchronously(this, () -> {
            Map<String, ScriptEngine.Discovered> found = fresh == null ? Map.of() : fresh.discover(scriptsDir);
            getServer().getScheduler().runTask(this, () -> {
                reloading = false;
                apply(fresh, found);
                sender.sendMessage("PyMC: loaded " + commands.size() + " script command(s)");
            });
        });
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

    /** Replaces the registered commands with a freshly discovered set. */
    private void apply(ScriptEngine fresh, Map<String, ScriptEngine.Discovered> found) {
        unregisterAll();
        if (engine != null) {
            engine.stopAll();
        }
        engine = fresh;

        CommandMap map = getServer().getCommandMap();
        for (Map.Entry<String, ScriptEngine.Discovered> entry : found.entrySet()) {
            ScriptEngine.Discovered script = entry.getValue();
            ScriptCommand command = new ScriptCommand(entry.getKey(), script.script(), script.usage(), engine);
            if (!map.register(FALLBACK_PREFIX, command)) {
                getLogger().warning("/" + entry.getKey() + " already belongs to another plugin, use /"
                        + FALLBACK_PREFIX + ":" + entry.getKey());
            }
            commands.put(entry.getKey(), command);
        }
        getServer().getOnlinePlayers().forEach(Player::updateCommands);
        getLogger().info("Loaded " + commands.size() + " script command(s)"
                + (commands.isEmpty() ? "" : ": /" + String.join(", /", commands.keySet())));
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

    /** /pymc reload | list - registered by hand, Paper plugins have no plugin.yml commands. */
    private final class AdminCommand extends Command {
        AdminCommand() {
            super("pymc", "Manage PyMC scripts", "/pymc <reload|list>", List.of());
            setPermission("pymc.admin");
        }

        @Override
        public boolean execute(CommandSender sender, String label, String[] args) {
            if (!testPermission(sender)) {
                return true;
            }
            String sub = args.length == 1 ? args[0].toLowerCase() : "";
            switch (sub) {
                case "reload" -> reloadScripts(sender);
                case "list" -> {
                    if (engine == null) {
                        sender.sendMessage("PyMC: scripts are disabled, no engine could start - see the server log");
                        return true;
                    }
                    sender.sendMessage("PyMC engine: " + engine.name());
                    if (commands.isEmpty()) {
                        sender.sendMessage("PyMC: no scripts in " + scriptsDir);
                    } else {
                        commands.forEach((name, cmd) ->
                                sender.sendMessage(cmd.getUsage() + "  ->  " + cmd.script().getFileName()));
                    }
                }
                default -> sender.sendMessage("Usage: " + getUsage());
            }
            return true;
        }

        @Override
        public List<String> tabComplete(CommandSender sender, String alias, String[] args) {
            return args.length == 1 ? List.of("reload", "list") : List.of();
        }
    }
}
