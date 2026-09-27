package com.varlaam.pymc;

import com.google.gson.JsonObject;
import org.bukkit.command.CommandSender;
import org.graalvm.polyglot.HostAccess;

/**
 * The only Java object a GraalPy script can touch (exposed as polyglot value "pymc_host").
 * The context uses HostAccess.EXPLICIT, so nothing but the @Export methods is visible.
 * It speaks the same JSON lines as the python3 process engine.
 */
public final class ScriptHost {
    private final Requests requests;
    private final CommandSender sender;
    private volatile ScriptEngine.Registration registration;

    /** sender == null means discovery: the script may only register its command. */
    ScriptHost(Requests requests, CommandSender sender) {
        this.requests = requests;
        this.sender = sender;
    }

    ScriptEngine.Registration registration() {
        return registration;
    }

    @HostAccess.Export
    public String request(String line) {
        if (sender == null) {
            return "{\"ok\":false,\"error\":\"not available while the server is discovering scripts\"}";
        }
        JsonObject reply = requests.answer(line, sender);
        return reply == null ? "" : reply.toString();
    }

    @HostAccess.Export
    public void send(String line) {
        JsonObject msg = Requests.parse(line);
        if (msg != null && "register".equals(Requests.str(msg, "type"))) {
            registration = new ScriptEngine.Registration(Requests.str(msg, "command"), Requests.str(msg, "usage"));
        }
    }
}
