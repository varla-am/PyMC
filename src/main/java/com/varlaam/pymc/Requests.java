package com.varlaam.pymc;

import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;

/**
 * Answers script requests (console, player, broadcast, reply, data). Both engines send the
 * same JSON lines here; the Bukkit work always happens on the main thread.
 */
final class Requests {
    private static final Gson GSON = new Gson();
    private static final int REQUEST_TIMEOUT_SECONDS = 10;

    private final PyMCPlugin plugin;

    Requests(PyMCPlugin plugin) {
        this.plugin = plugin;
    }

    /** Handles one request line; null for messages that need no reply. */
    JsonObject answer(String line, CommandSender sender) {
        JsonObject msg = parse(line);
        if (msg == null || !msg.has("id")) {
            return null;
        }
        JsonObject reply = new JsonObject();
        reply.add("id", msg.get("id"));
        try {
            Object result = Bukkit.getScheduler()
                    .callSyncMethod(plugin, () -> handle(msg, sender))
                    .get(REQUEST_TIMEOUT_SECONDS, TimeUnit.SECONDS);
            reply.addProperty("ok", true);
            reply.add("result", GSON.toJsonTree(result));
        } catch (ExecutionException e) {
            Throwable cause = e.getCause();
            reply.addProperty("ok", false);
            reply.addProperty("error", cause.getMessage() != null ? cause.getMessage() : cause.toString());
        } catch (Exception e) {
            reply.addProperty("ok", false);
            reply.addProperty("error", e.toString());
        }
        return reply;
    }

    /** Runs on the main thread. */
    private Object handle(JsonObject msg, CommandSender sender) {
        String type = str(msg, "type");
        return switch (type == null ? "" : type) {
            case "console" -> Bukkit.dispatchCommand(Bukkit.getConsoleSender(), str(msg, "command"));
            case "player" -> {
                String name = str(msg, "player");
                Player player = Bukkit.getPlayerExact(name);
                if (player == null) {
                    throw new IllegalArgumentException(name + " is not online");
                }
                yield player.performCommand(str(msg, "command"));
            }
            case "broadcast" -> Bukkit.broadcast(
                    LegacyComponentSerializer.legacyAmpersand().deserialize(str(msg, "message")));
            case "data" -> data(str(msg, "field"), msg);
            case "reply" -> {
                sender.sendMessage(LegacyComponentSerializer.legacyAmpersand().deserialize(str(msg, "message")));
                yield true;
            }
            default -> throw new IllegalArgumentException("unknown request type: " + type);
        };
    }

    /** mcmod.data: live server information. Runs on the main thread. */
    private Object data(String field, JsonObject msg) {
        return switch (field == null ? "" : field) {
            case "players" -> Bukkit.getOnlinePlayers().stream().map(Requests::playerInfo).toList();
            case "player" -> {
                String name = str(msg, "name");
                Player player = name == null ? null : Bukkit.getPlayerExact(name);
                yield player == null ? null : playerInfo(player);
            }
            case "player_count" -> Bukkit.getOnlinePlayers().size();
            case "max_players" -> Bukkit.getMaxPlayers();
            case "version" -> Bukkit.getMinecraftVersion();
            case "modloader" -> Bukkit.getName();
            case "server_version" -> Bukkit.getVersion();
            case "plugins" -> Arrays.stream(Bukkit.getPluginManager().getPlugins())
                    .map(Requests::pluginInfo).toList();
            case "worlds" -> Bukkit.getWorlds().stream().map(World::getName).toList();
            case "motd" -> PlainTextComponentSerializer.plainText().serialize(Bukkit.motd());
            case "tps" -> Arrays.stream(Bukkit.getTPS()).map(t -> Math.round(t * 100) / 100.0).boxed().toList();
            case "online_mode" -> Bukkit.getOnlineMode();
            default -> throw new IllegalArgumentException("unknown data field: " + field);
        };
    }

    private static Map<String, Object> playerInfo(Player p) {
        Location at = p.getLocation();
        Map<String, Object> info = new LinkedHashMap<>();
        info.put("name", p.getName());
        info.put("uuid", p.getUniqueId().toString());
        info.put("display_name", PlainTextComponentSerializer.plainText().serialize(p.displayName()));
        info.put("world", at.getWorld() == null ? null : at.getWorld().getName());
        info.put("x", Math.round(at.getX() * 100) / 100.0);
        info.put("y", Math.round(at.getY() * 100) / 100.0);
        info.put("z", Math.round(at.getZ() * 100) / 100.0);
        info.put("health", p.getHealth());
        info.put("food", p.getFoodLevel());
        info.put("level", p.getLevel());
        info.put("gamemode", p.getGameMode().name().toLowerCase());
        info.put("ping", p.getPing());
        info.put("op", p.isOp());
        return info;
    }

    private static Map<String, Object> pluginInfo(Plugin p) {
        Map<String, Object> info = new LinkedHashMap<>();
        info.put("name", p.getName());
        info.put("version", p.getPluginMeta().getVersion());
        info.put("enabled", p.isEnabled());
        return info;
    }

    static JsonObject parse(String line) {
        try {
            return JsonParser.parseString(line).getAsJsonObject();
        } catch (RuntimeException e) {
            return null;
        }
    }

    static String str(JsonObject msg, String key) {
        return msg.has(key) && !msg.get(key).isJsonNull() ? msg.get(key).getAsString() : null;
    }
}
