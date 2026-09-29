package com.varlaam.pymc;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import io.papermc.paper.event.player.AsyncChatEvent;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.Location;
import org.bukkit.block.Block;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.EntityDeathEvent;
import org.bukkit.event.entity.EntityPickupItemEvent;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.player.PlayerBedEnterEvent;
import org.bukkit.event.player.PlayerChangedWorldEvent;
import org.bukkit.event.player.PlayerCommandPreprocessEvent;
import org.bukkit.event.player.PlayerDropItemEvent;
import org.bukkit.event.player.PlayerGameModeChangeEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerItemConsumeEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerLevelChangeEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerRespawnEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;

/**
 * Turns server events into mcmod.event(...) scripts, like Skript's "on join:". Handlers run at
 * MONITOR priority and only look at events that really happened; the scripts themselves run
 * off the main thread afterwards, so they can react to an event but not cancel it.
 * Keep EVENTS in sync with mcmod.EVENTS on the Python side.
 */
final class EventBridge implements Listener {
    static final List<String> EVENTS = List.of(
            "load", "join", "first_join", "quit", "chat", "command", "death", "respawn", "kill", "damage",
            "break", "place", "right_click", "left_click", "drop", "pickup", "consume", "bed_enter",
            "world_change", "level_change", "gamemode_change");

    private final PyMCPlugin plugin;

    EventBridge(PyMCPlugin plugin) {
        this.plugin = plugin;
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onJoin(PlayerJoinEvent e) {
        Player p = e.getPlayer();
        boolean first = !p.hasPlayedBefore();
        if (!plugin.listens("join") && !(first && plugin.listens("first_join"))) {
            return;
        }
        Map<String, Object> data = player(p);
        data.put("message", plain(e.joinMessage()));
        data.put("first_join", first);
        plugin.fire("join", p, data);
        if (first) {
            plugin.fire("first_join", p, data);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onQuit(PlayerQuitEvent e) {
        if (!plugin.listens("quit")) {
            return;
        }
        Map<String, Object> data = player(e.getPlayer());
        data.put("message", plain(e.quitMessage()));
        plugin.fire("quit", e.getPlayer(), data);
    }

    /** Paper fires chat off the main thread; fire() only schedules, so that is fine. */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onChat(AsyncChatEvent e) {
        if (!plugin.listens("chat")) {
            return;
        }
        Map<String, Object> data = player(e.getPlayer());
        data.put("message", plain(e.message()));
        plugin.fire("chat", e.getPlayer(), data);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onCommand(PlayerCommandPreprocessEvent e) {
        if (!plugin.listens("command")) {
            return;
        }
        String line = e.getMessage().startsWith("/") ? e.getMessage().substring(1) : e.getMessage();
        Map<String, Object> data = player(e.getPlayer());
        data.put("command", line.split(" ", 2)[0].toLowerCase());
        data.put("line", line);
        plugin.fire("command", e.getPlayer(), data);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onDeath(PlayerDeathEvent e) {
        if (!plugin.listens("death")) {
            return;
        }
        Player p = e.getEntity();
        Map<String, Object> data = player(p);
        data.put("message", plain(e.deathMessage()));
        data.put("killer", p.getKiller() == null ? null : p.getKiller().getName());
        EntityDamageEvent last = p.getLastDamageCause();
        data.put("cause", last == null ? null : last.getCause().name().toLowerCase());
        plugin.fire("death", p, data);
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onRespawn(PlayerRespawnEvent e) {
        if (!plugin.listens("respawn")) {
            return;
        }
        Map<String, Object> data = player(e.getPlayer());
        at(data, e.getRespawnLocation());
        plugin.fire("respawn", e.getPlayer(), data);
    }

    /** A player killed any entity, other players included. */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onKill(EntityDeathEvent e) {
        Player killer = e.getEntity().getKiller();
        if (killer == null || !plugin.listens("kill")) {
            return;
        }
        Map<String, Object> data = player(killer);
        data.put("victim", type(e.getEntity()));
        data.put("victim_name", e.getEntity() instanceof Player v ? v.getName() : plain(e.getEntity().customName()));
        at(data, e.getEntity().getLocation());
        plugin.fire("kill", killer, data);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onDamage(EntityDamageEvent e) {
        if (!(e.getEntity() instanceof Player p) || !plugin.listens("damage")) {
            return;
        }
        Map<String, Object> data = player(p);
        data.put("cause", e.getCause().name().toLowerCase());
        data.put("damage", Math.round(e.getFinalDamage() * 100) / 100.0);
        Entity attacker = e instanceof EntityDamageByEntityEvent by ? by.getDamager() : null;
        data.put("attacker", attacker instanceof Player a ? a.getName() : attacker == null ? null : type(attacker));
        plugin.fire("damage", p, data);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBreak(BlockBreakEvent e) {
        if (!plugin.listens("break")) {
            return;
        }
        Map<String, Object> data = player(e.getPlayer());
        block(data, e.getBlock());
        plugin.fire("break", e.getPlayer(), data);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPlace(BlockPlaceEvent e) {
        if (!plugin.listens("place")) {
            return;
        }
        Map<String, Object> data = player(e.getPlayer());
        block(data, e.getBlockPlaced());
        plugin.fire("place", e.getPlayer(), data);
    }

    /**
     * Not ignoreCancelled: Bukkit marks a click into the air as cancelled before anyone sees it.
     * Only the main hand counts, otherwise every right click would fire twice.
     */
    @EventHandler(priority = EventPriority.MONITOR)
    public void onClick(PlayerInteractEvent e) {
        if (e.getHand() != EquipmentSlot.HAND) {
            return;
        }
        Action action = e.getAction();
        String name = action == Action.RIGHT_CLICK_AIR || action == Action.RIGHT_CLICK_BLOCK ? "right_click"
                : action == Action.LEFT_CLICK_AIR || action == Action.LEFT_CLICK_BLOCK ? "left_click" : null;
        if (name == null || !plugin.listens(name)) {
            return;
        }
        Map<String, Object> data = player(e.getPlayer());
        data.put("item", item(e.getItem()));
        if (e.getClickedBlock() != null) {
            block(data, e.getClickedBlock());
        } else {
            data.put("block", null);
        }
        plugin.fire(name, e.getPlayer(), data);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onDrop(PlayerDropItemEvent e) {
        if (!plugin.listens("drop")) {
            return;
        }
        Map<String, Object> data = player(e.getPlayer());
        ItemStack stack = e.getItemDrop().getItemStack();
        data.put("item", item(stack));
        data.put("amount", stack.getAmount());
        plugin.fire("drop", e.getPlayer(), data);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPickup(EntityPickupItemEvent e) {
        if (!(e.getEntity() instanceof Player p) || !plugin.listens("pickup")) {
            return;
        }
        Map<String, Object> data = player(p);
        ItemStack stack = e.getItem().getItemStack();
        data.put("item", item(stack));
        data.put("amount", stack.getAmount());
        plugin.fire("pickup", p, data);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onConsume(PlayerItemConsumeEvent e) {
        if (!plugin.listens("consume")) {
            return;
        }
        Map<String, Object> data = player(e.getPlayer());
        data.put("item", item(e.getItem()));
        plugin.fire("consume", e.getPlayer(), data);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBedEnter(PlayerBedEnterEvent e) {
        if (e.getBedEnterResult() != PlayerBedEnterEvent.BedEnterResult.OK || !plugin.listens("bed_enter")) {
            return;
        }
        Map<String, Object> data = player(e.getPlayer());
        plugin.fire("bed_enter", e.getPlayer(), data);
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onWorldChange(PlayerChangedWorldEvent e) {
        if (!plugin.listens("world_change")) {
            return;
        }
        Map<String, Object> data = player(e.getPlayer());
        data.put("from", e.getFrom().getName());
        data.put("to", e.getPlayer().getWorld().getName());
        plugin.fire("world_change", e.getPlayer(), data);
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onLevelChange(PlayerLevelChangeEvent e) {
        if (!plugin.listens("level_change")) {
            return;
        }
        Map<String, Object> data = player(e.getPlayer());
        data.put("old_level", e.getOldLevel());
        data.put("new_level", e.getNewLevel());
        plugin.fire("level_change", e.getPlayer(), data);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onGameModeChange(PlayerGameModeChangeEvent e) {
        if (!plugin.listens("gamemode_change")) {
            return;
        }
        Map<String, Object> data = player(e.getPlayer());
        data.put("old_gamemode", e.getPlayer().getGameMode().name().toLowerCase());
        data.put("gamemode", e.getNewGameMode().name().toLowerCase());
        plugin.fire("gamemode_change", e.getPlayer(), data);
    }

    private static Map<String, Object> player(Player p) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("player", p.getName());
        return data;
    }

    private static void block(Map<String, Object> data, Block b) {
        data.put("block", b.getType().name().toLowerCase());
        at(data, b.getLocation());
    }

    private static void at(Map<String, Object> data, Location at) {
        data.put("world", at.getWorld() == null ? null : at.getWorld().getName());
        data.put("x", Math.round(at.getX() * 100) / 100.0);
        data.put("y", Math.round(at.getY() * 100) / 100.0);
        data.put("z", Math.round(at.getZ() * 100) / 100.0);
    }

    private static String item(ItemStack stack) {
        return stack == null || stack.getType().isAir() ? null : stack.getType().name().toLowerCase();
    }

    private static String type(Entity entity) {
        return entity.getType().name().toLowerCase();
    }

    private static String plain(Component text) {
        return text == null ? null : PlainTextComponentSerializer.plainText().serialize(text);
    }
}
