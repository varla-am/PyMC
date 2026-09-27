package com.varlaam.pymc;

import java.nio.file.Path;
import java.util.List;

import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;

/** A server command whose body is a Python script. */
final class ScriptCommand extends Command {
    private final Path script;
    private final ScriptEngine runner;

    ScriptCommand(String name, Path script, String usage, ScriptEngine runner) {
        super(name, "PyMC script " + script.getFileName(), usage, List.of());
        this.script = script;
        this.runner = runner;
    }

    Path script() {
        return script;
    }

    @Override
    public boolean execute(CommandSender sender, String label, String[] args) {
        runner.run(getName(), script, sender, args);
        return true;
    }
}
