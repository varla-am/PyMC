package com.varlaam.pymc;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.logging.Logger;

import com.google.gson.JsonObject;
import org.bukkit.Bukkit;
import org.bukkit.command.CommandSender;
import org.bukkit.scheduler.BukkitTask;

/**
 * Runs each script as a python3 process. Protocol: one JSON object per line; the script
 * writes requests to stdout and reads replies from stdin, its stderr goes to the log.
 */
final class ProcessEngine implements ScriptEngine {
    private static final int DISCOVER_TIMEOUT_SECONDS = 10;

    private final PyMCPlugin plugin;
    private final Requests requests;
    private final String python;
    private final Path pythonDir;
    private final int timeoutSeconds;
    private final int maxRunning;
    private final Set<Process> running = ConcurrentHashMap.newKeySet();
    private final AtomicInteger starting = new AtomicInteger();
    private volatile String name;

    ProcessEngine(PyMCPlugin plugin, Requests requests, String python, Path pythonDir,
                  int timeoutSeconds, int maxRunning) {
        this.plugin = plugin;
        this.requests = requests;
        this.python = python;
        this.pythonDir = pythonDir;
        this.timeoutSeconds = Math.max(1, timeoutSeconds);
        this.maxRunning = Math.max(1, maxRunning);
    }

    @Override
    public String name() {
        // Asking python3 for its version starts a process; do it once, not on every /pymc version.
        if (name == null) {
            name = "Python " + PythonProbe.version(python) + " (" + python + ")";
        }
        return name;
    }

    @Override
    public Logger logger() {
        return plugin.getLogger();
    }

    @Override
    public Probe probe(Path script) {
        String file = script.getFileName().toString();
        try {
            Process process = start(script, Map.of("PYMC_MODE", "discover"));
            List<String> output = Collections.synchronizedList(new ArrayList<>());
            Thread stderr = pipeStderr(process, file, output);
            if (!process.waitFor(DISCOVER_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                process.destroyForcibly();
                return Probe.failed("did not reach mcmod.triggerCommand(...) or mcmod.event(...) within "
                        + DISCOVER_TIMEOUT_SECONDS + " s");
            }
            Registration reg = null;
            try (BufferedReader out = reader(process)) {
                String line;
                while ((line = out.readLine()) != null) {
                    Registration r = Registration.of(Requests.parse(line));
                    if (r != null) {
                        reg = r;
                    }
                }
            }
            stderr.join(1000);
            if (reg != null) {
                return Probe.ok(reg);
            }
            if (process.exitValue() != 0) {
                synchronized (output) {
                    return Probe.failed(ScriptEngine.describe(output, file));
                }
            }
            return Probe.failed("never calls mcmod.triggerCommand(...) or mcmod.event(...)");
        } catch (IOException e) {
            return Probe.failed("cannot start '" + python + "': " + e.getMessage());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return Probe.failed("interrupted");
        }
    }

    @Override
    public boolean start(Path script, CommandSender sender, Map<String, String> env) {
        String file = script.getFileName().toString();
        // Every use is a python3 process; without a cap, spamming a command forks without limit.
        if (starting.get() + running.size() >= maxRunning) {
            return false;
        }
        starting.incrementAndGet();
        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            Process process;
            try {
                process = start(script, env);
            } catch (IOException e) {
                starting.decrementAndGet();
                logger().severe("Cannot start '" + python + "' for " + file + ": " + e.getMessage());
                String command = env.get("PYMC_COMMAND");
                if (command != null) {
                    Bukkit.getScheduler().runTask(plugin, () -> sender.sendMessage("/" + command + " failed to start"));
                }
                return;
            }
            running.add(process);
            starting.decrementAndGet();
            pipeStderr(process, file, null);
            BukkitTask watchdog = Bukkit.getScheduler().runTaskLaterAsynchronously(plugin, () -> {
                if (process.isAlive()) {
                    process.destroyForcibly();
                    logger().warning(file + " ran longer than " + timeoutSeconds + " s and was stopped");
                }
            }, timeoutSeconds * 20L);

            try (BufferedReader in = reader(process);
                 BufferedWriter out = new BufferedWriter(
                         new OutputStreamWriter(process.getOutputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = in.readLine()) != null) {
                    JsonObject reply = requests.answer(line, sender);
                    if (reply != null) {
                        out.write(reply.toString());
                        out.newLine();
                        out.flush();
                    }
                }
            } catch (IOException e) {
                // The script exited or was stopped; nothing left to answer.
            } finally {
                running.remove(process);
                watchdog.cancel();
            }
        });
        return true;
    }

    @Override
    public void stopAll() {
        running.forEach(Process::destroyForcibly);
        running.clear();
    }

    private Process start(Path script, Map<String, String> modeEnv) throws IOException {
        ProcessBuilder builder = new ProcessBuilder(python, script.getFileName().toString());
        builder.directory(script.getParent().toFile());
        Map<String, String> env = builder.environment();
        env.putAll(modeEnv);
        String inherited = env.get("PYTHONPATH");
        env.put("PYTHONPATH", pythonDir.toAbsolutePath()
                + (inherited == null || inherited.isEmpty() ? "" : File.pathSeparator + inherited));
        env.put("PYTHONIOENCODING", "utf-8");
        env.put("PYTHONUNBUFFERED", "1");
        // Python 3.13+ colours tracebacks when FORCE_COLOR is set; the escape codes would end up
        // in chat and break the "line N:" parsing of errors.
        env.remove("FORCE_COLOR");
        env.put("PYTHON_COLORS", "0");
        env.put("NO_COLOR", "1");
        return builder.start();
    }

    /**
     * Script output and tracebacks go to the server console, prefixed with the file name.
     * If keep is given, the lines are also collected there (discovery reports errors from them).
     */
    private Thread pipeStderr(Process process, String file, List<String> keep) {
        Thread thread = new Thread(() -> {
            try (BufferedReader err = new BufferedReader(
                    new InputStreamReader(process.getErrorStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = err.readLine()) != null) {
                    logger().info("[" + file + "] " + line);
                    if (keep != null) {
                        keep.add(line);
                    }
                }
            } catch (IOException ignored) {
                // process ended
            }
        }, "PyMC-" + file);
        thread.setDaemon(true);
        thread.start();
        return thread;
    }

    private static BufferedReader reader(Process process) {
        return new BufferedReader(new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8));
    }
}
