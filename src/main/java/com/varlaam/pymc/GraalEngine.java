package com.varlaam.pymc;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.logging.Logger;

import org.bukkit.Bukkit;
import org.bukkit.command.CommandSender;
import org.graalvm.polyglot.Context;
import org.graalvm.polyglot.Engine;
import org.graalvm.polyglot.EnvironmentAccess;
import org.graalvm.polyglot.HostAccess;
import org.graalvm.polyglot.PolyglotAccess;
import org.graalvm.polyglot.PolyglotException;
import org.graalvm.polyglot.Source;
import org.graalvm.polyglot.SourceSection;
import org.graalvm.polyglot.io.IOAccess;

/**
 * Runs scripts with GraalPy inside the server's JVM, so no python3 is needed on the machine.
 * Every run gets a fresh Context, isolated like a process; all contexts share one Engine,
 * so code is parsed once and later runs start faster. Only loaded when the GraalPy
 * libraries are on the classpath (see PyMCLoader), and only created via reflection.
 */
final class GraalEngine implements ScriptEngine {
    private static final int DISCOVER_TIMEOUT_SECONDS = 10;

    private static Engine shared;
    private static final ScheduledExecutorService WATCHDOGS = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "PyMC-GraalPy-watchdog");
        t.setDaemon(true);
        return t;
    });

    private final PyMCPlugin plugin;
    private final Requests requests;
    private final Path pythonDir;
    private final int timeoutSeconds;
    private final int maxRunning;
    private final Set<Context> running = ConcurrentHashMap.newKeySet();
    private final AtomicInteger starting = new AtomicInteger();

    GraalEngine(PyMCPlugin plugin, Requests requests, Path pythonDir, int timeoutSeconds, int maxRunning) {
        this.plugin = plugin;
        this.requests = requests;
        this.pythonDir = pythonDir;
        this.timeoutSeconds = Math.max(1, timeoutSeconds);
        this.maxRunning = Math.max(1, maxRunning);
        synchronized (GraalEngine.class) {
            if (shared == null) {
                // Hosts run a stock JDK without JVMCI: GraalPy interprets, which is fine for scripts.
                shared = Engine.newBuilder("python").option("engine.WarnInterpreterOnly", "false").build();
            }
        }
    }

    /** Called once when the plugin is disabled. */
    static synchronized void shutdown() {
        if (shared != null) {
            shared.close(true);
            shared = null;
        }
    }

    @Override
    public String name() {
        return "GraalPy " + shared.getLanguages().get("python").getVersion() + " (inside the JVM)";
    }

    @Override
    public Logger logger() {
        return plugin.getLogger();
    }

    @Override
    public Probe probe(Path script) {
        String file = script.getFileName().toString();
        ScriptHost host = new ScriptHost(requests, null);
        LogStream output = new LogStream(file);
        try (Context ctx = newContext(script, Map.of("PYMC_MODE", "discover"), host, output)) {
            ScheduledFuture<?> dog = WATCHDOGS.schedule(() -> ctx.close(true), DISCOVER_TIMEOUT_SECONDS, TimeUnit.SECONDS);
            try {
                exec(ctx, script);
            } catch (PolyglotException e) {
                if (e.isCancelled()) {
                    return Probe.failed("did not reach mcmod.triggerCommand(...) or mcmod.event(...) within "
                            + DISCOVER_TIMEOUT_SECONDS + " s");
                }
                if (!e.isExit()) {
                    logTraceback(file, e);
                    if (host.registration() == null) {
                        return Probe.failed(where(file, e) + e.getMessage());
                    }
                } else if (e.getExitStatus() != 0 && host.registration() == null) {
                    return Probe.failed(ScriptEngine.describe(output.recent(), file));
                }
            } finally {
                dog.cancel(false);
            }
        } catch (IOException | RuntimeException e) {
            return Probe.failed("cannot run with GraalPy: " + e);
        }
        if (host.registration() == null) {
            return Probe.failed("never calls mcmod.triggerCommand(...) or mcmod.event(...)");
        }
        return Probe.ok(host.registration());
    }

    @Override
    public boolean start(Path script, CommandSender sender, Map<String, String> env) {
        String file = script.getFileName().toString();
        if (starting.get() + running.size() >= maxRunning) {
            return false;
        }
        starting.incrementAndGet();
        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            ScriptHost host = new ScriptHost(requests, sender);
            Context ctx;
            try {
                ctx = newContext(script, env, host, new LogStream(file));
            } catch (RuntimeException e) {
                starting.decrementAndGet();
                logger().severe("Cannot start GraalPy for " + file + ": " + e);
                return;
            }
            running.add(ctx);
            starting.decrementAndGet();
            ScheduledFuture<?> dog = WATCHDOGS.schedule(() -> {
                if (running.contains(ctx)) {
                    logger().warning(file + " ran longer than " + timeoutSeconds + " s and was stopped");
                    ctx.close(true);
                }
            }, timeoutSeconds, TimeUnit.SECONDS);
            try {
                exec(ctx, script);
            } catch (PolyglotException e) {
                if (!e.isExit() && !e.isCancelled()) {
                    logTraceback(file, e);
                }
            } catch (IOException e) {
                logger().severe("Cannot read " + file + ": " + e.getMessage());
            } catch (IllegalStateException e) {
                // closed by stopAll() or the watchdog
            } finally {
                dog.cancel(false);
                running.remove(ctx);
                try {
                    ctx.close();
                } catch (RuntimeException ignored) {
                    // already cancelled
                }
            }
        });
        return true;
    }

    @Override
    public void stopAll() {
        for (Context ctx : running) {
            try {
                ctx.close(true);
            } catch (RuntimeException ignored) {
                // already closed
            }
        }
        running.clear();
    }

    private Context newContext(Path script, Map<String, String> env, ScriptHost host, LogStream output) {
        Path scriptsDir = script.toAbsolutePath().getParent();
        Context ctx = Context.newBuilder("python")
                .engine(shared)
                .allowIO(IOAccess.ALL)
                .allowHostAccess(HostAccess.EXPLICIT)
                .allowPolyglotAccess(PolyglotAccess.ALL)
                .allowCreateThread(true)
                .allowEnvironmentAccess(EnvironmentAccess.NONE)
                .environment(env)
                .currentWorkingDirectory(scriptsDir)
                .option("python.PythonPath", pythonDir.toAbsolutePath() + File.pathSeparator + scriptsDir)
                .out(output)
                .err(output)
                .build();
        ctx.getPolyglotBindings().putMember("pymc_host", host);
        return ctx;
    }

    private static void exec(Context ctx, Path script) throws IOException {
        ctx.eval(Source.newBuilder("python", script.toFile()).build());
    }

    /** "line 3: " - where in the script itself an exception happened, if GraalPy knows. */
    private static String where(String file, PolyglotException e) {
        if (e.isSyntaxError() && e.getSourceLocation() != null) {
            return "line " + e.getSourceLocation().getStartLine() + ": ";
        }
        for (PolyglotException.StackFrame frame : e.getPolyglotStackTrace()) {
            SourceSection at = frame.getSourceLocation();
            if (frame.isGuestFrame() && at != null && file.equals(at.getSource().getName())) {
                return "line " + at.getStartLine() + ": ";
            }
        }
        return "";
    }

    /** Formats a GraalPy exception like a CPython traceback, one log line per line. */
    private void logTraceback(String file, PolyglotException e) {
        List<PolyglotException.StackFrame> frames = new ArrayList<>();
        for (PolyglotException.StackFrame frame : e.getPolyglotStackTrace()) {
            if (frame.isGuestFrame() && frame.getSourceLocation() != null) {
                frames.add(frame);
            }
        }
        Collections.reverse(frames);
        logger().info("[" + file + "] Traceback (most recent call last):");
        for (PolyglotException.StackFrame frame : frames) {
            SourceSection at = frame.getSourceLocation();
            String path = at.getSource().getPath() != null ? at.getSource().getPath() : at.getSource().getName();
            logger().info("[" + file + "]   File \"" + path + "\", line " + at.getStartLine() + ", in " + frame.getRootName());
        }
        logger().info("[" + file + "] " + e.getMessage());
    }

    /** A context's stdout/stderr, logged line by line with the script name; keeps the last lines. */
    private final class LogStream extends OutputStream {
        private static final int KEEP = 50;
        private final String file;
        private final ByteArrayOutputStream line = new ByteArrayOutputStream();
        private final Deque<String> recent = new ArrayDeque<>();

        LogStream(String file) {
            this.file = file;
        }

        @Override
        public synchronized void write(int b) {
            if (b == '\n') {
                emit();
            } else {
                line.write(b);
            }
        }

        @Override
        public synchronized void write(byte[] b, int off, int len) {
            for (int i = off; i < off + len; i++) {
                write(b[i]);
            }
        }

        synchronized List<String> recent() {
            if (line.size() > 0) {
                emit();
            }
            return new ArrayList<>(recent);
        }

        @Override
        public synchronized void close() {
            if (line.size() > 0) {
                emit();
            }
        }

        private void emit() {
            String text = line.toString(StandardCharsets.UTF_8);
            line.reset();
            text = text.endsWith("\r") ? text.substring(0, text.length() - 1) : text;
            recent.addLast(text);
            if (recent.size() > KEEP) {
                recent.removeFirst();
            }
            logger().info("[" + file + "] " + text);
        }
    }
}
