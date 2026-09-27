package com.varlaam.pymc;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import io.papermc.paper.plugin.loader.PluginClasspathBuilder;
import io.papermc.paper.plugin.loader.PluginLoader;
import io.papermc.paper.plugin.loader.library.impl.MavenLibraryResolver;
import org.eclipse.aether.artifact.DefaultArtifact;
import org.eclipse.aether.graph.Dependency;
import org.eclipse.aether.repository.RemoteRepository;

/**
 * Runs before the plugin loads and decides whether GraalPy is needed. If there is a usable
 * python3 (or engine: python), nothing is downloaded. Otherwise Paper fetches GraalPy from
 * Maven Central into the server's libraries/ folder - about 150 MB, once.
 */
public final class PyMCLoader implements PluginLoader {
    static final String GRAALPY_VERSION = "25.4.4.1.1";
    private static final List<String> GRAALPY = List.of(
            "org.graalvm.polyglot:polyglot",
            "org.graalvm.python:python-language",
            "org.graalvm.python:python-resources",
            "org.graalvm.truffle:truffle-runtime");

    @Override
    public void classloader(PluginClasspathBuilder builder) {
        Path config = builder.getContext().getDataDirectory().resolve("config.yml");
        String engine = setting(config, "engine", "auto").toLowerCase();
        String python = setting(config, "python", "python3");

        boolean needGraal = switch (engine) {
            case "graalpy" -> true;
            case "python" -> false;
            default -> !PythonProbe.usable(PythonProbe.version(python));
        };
        if (!needGraal) {
            return;
        }
        builder.getContext().getLogger().info("No usable Python 3.9+ here (or engine: graalpy) - loading GraalPy "
                + GRAALPY_VERSION + ". The first start downloads about 150 MB.");
        MavenLibraryResolver resolver = new MavenLibraryResolver();
        resolver.addRepository(new RemoteRepository.Builder("central", "default",
                MavenLibraryResolver.MAVEN_CENTRAL_DEFAULT_MIRROR).build());
        for (String artifact : GRAALPY) {
            resolver.addDependency(new Dependency(new DefaultArtifact(artifact + ":" + GRAALPY_VERSION), null));
        }
        builder.addLibrary(resolver);
    }

    /** A top-level "key: value" from config.yml, read by hand: Bukkit's config isn't loaded yet. */
    private static String setting(Path config, String key, String fallback) {
        try {
            Pattern line = Pattern.compile("^" + Pattern.quote(key) + ":\\s*['\"]?([^'\"#\\s]+)");
            for (String l : Files.readAllLines(config)) {
                Matcher m = line.matcher(l);
                if (m.find()) {
                    return m.group(1);
                }
            }
        } catch (IOException e) {
            // no config yet: first start
        }
        return fallback;
    }
}
