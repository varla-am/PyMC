package com.varlaam.pymc;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.logging.Logger;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import org.bukkit.Bukkit;
import org.bukkit.command.CommandSender;
import org.bukkit.scheduler.BukkitTask;

/**
 * Runs each script as a python3 process. Protocol: one JSON object per line; the script
 * writes requests to stdout and reads replies from stdin, its stderr goes to the log.
 */
final class ProcessEngine implements ScriptEngine {
    private static final Gson GSON = new Gson();
    private static final int DISCOVER_TIMEOUT_SECONDS = 10;

    private final PyMCPlugin plugin;
    private final Requests requests;
    private final String python;
    private final Path pythonDir;
    private final int timeoutSeconds;
    private final int maxRunning;
    private final Set<Process> running = ConcurrentHashMap.newKeySet();
    private final AtomicInteger starting = new AtomicInteger();

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
        return "Python " + PythonProbe.version(python) + " (" + python + ")";
    }

    @Override
    public Logger logger() {
        return plugin.getLogger();
    }

    @Override
    public Registration probe(Path script) {
        String file = script.getFileName().toString();
        try {
            Process process = start(script, Map.of("PYMC_MODE", "discover"));
            pipeStderr(process, file);
            if (!process.waitFor(DISCOVER_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                process.destroyForcibly();
                logger().warning(file + " did not reach mcmod.triggerCommand(...) within "
                        + DISCOVER_TIMEOUT_SECONDS + " s, skipped");
                return null;
            }
            Registration reg = null;
            try (BufferedReader out = reader(process)) {
                String line;
                while ((line = out.readLine()) != null) {
                    JsonObject msg = Requests.parse(line);
                    if (msg != null && "register".equals(Requests.str(msg, "type"))) {
                        reg = new Registration(Requests.str(msg, "command"), Requests.str(msg, "usage"));
                    }
                }
            }
            if (reg == null) {
                logger().warning(file + " never calls mcmod.triggerCommand(...), skipped");
            }
            return reg;
        } catch (IOException e) {
            logger().severe("Cannot start '" + python + "' for " + file + ": " + e.getMessage());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        return null;
    }

    @Override
    public void run(String command, Path script, CommandSender sender, String[] args) {
        String file = script.getFileName().toString();
        // Every use is a python3 process; without a cap, spamming a command forks without limit.
        if (starting.get() + running.size() >= maxRunning) {
            sender.sendMessage("Too many PyMC scripts are running, try again in a moment");
            return;
        }
        starting.incrementAndGet();
        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            Process process;
            try {
                process = start(script, Map.of(
                        "PYMC_MODE", "run",
                        "PYMC_COMMAND", command,
                        "PYMC_SENDER", sender.getName(),
                        "PYMC_ARGS", GSON.toJson(args)));
            } catch (IOException e) {
                starting.decrementAndGet();
                logger().severe("Cannot start '" + python + "' for " + file + ": " + e.getMessage());
                Bukkit.getScheduler().runTask(plugin, () -> sender.sendMessage("/" + command + " failed to start"));
                return;
            }
            running.add(process);
            starting.decrementAndGet();
            pipeStderr(process, file);
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
        return builder.start();
    }

    /** Script output and tracebacks go to the server console, prefixed with the file name. */
    private void pipeStderr(Process process, String file) {
        Thread thread = new Thread(() -> {
            try (BufferedReader err = new BufferedReader(
                    new InputStreamReader(process.getErrorStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = err.readLine()) != null) {
                    logger().info("[" + file + "] " + line);
                }
            } catch (IOException ignored) {
                // process ended
            }
        }, "PyMC-" + file);
        thread.setDaemon(true);
        thread.start();
    }

    private static BufferedReader reader(Process process) {
        return new BufferedReader(new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8));
    }
}
