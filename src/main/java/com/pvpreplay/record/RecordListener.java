package com.pvpreplay.record;

import com.pvpreplay.PvPReplayPlugin;
import com.pvpreplay.Settings;
import com.pvpreplay.play.NpcFactory;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.GameMode;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.entity.ProjectileLaunchEvent;
import io.papermc.paper.event.player.AsyncChatEvent;
import org.bukkit.Bukkit;
import org.bukkit.event.entity.EntityPickupItemEvent;
import org.bukkit.event.player.PlayerAnimationEvent;
import org.bukkit.event.player.PlayerCommandPreprocessEvent;
import org.bukkit.event.player.PlayerDropItemEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.world.EntitiesLoadEvent;
import org.bukkit.persistence.PersistentDataType;

import java.util.List;

/** Feeds Bukkit events into the recorder and triggers automatic saves. */
public final class RecordListener implements Listener {
    private final PvPReplayPlugin plugin;
    private final Settings st;

    public RecordListener(PvPReplayPlugin plugin) {
        this.plugin = plugin;
        this.st = plugin.settings();
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent e) {
        Player p = e.getPlayer();
        plugin.recorder().refresh(p);
        // If the server crashed while someone was watching a replay, give them their game mode back.
        String saved = p.getPersistentDataContainer().get(plugin.viewKey(), PersistentDataType.STRING);
        if (saved != null && plugin.playback().get(p) == null) {
            try {
                p.setGameMode(GameMode.valueOf(saved));
            } catch (IllegalArgumentException ignored) {
                // keep whatever they have
            }
            p.getPersistentDataContainer().remove(plugin.viewKey());
        }
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent e) {
        plugin.playback().stop(e.getPlayer(), true);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onAnimation(PlayerAnimationEvent e) {
        boolean off = e.getAnimationType().name().equals("OFF_ARM_SWING");
        plugin.recorder().onSwing(e.getPlayer(), off);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onDamage(EntityDamageEvent e) {
        if (e.getEntity() instanceof Player p) plugin.recorder().onDamage(p, e);
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onDeath(PlayerDeathEvent e) {
        Player victim = e.getEntity();
        String msg = "";
        if (e.deathMessage() != null) {
            msg = PlainTextComponentSerializer.plainText().serialize(e.deathMessage());
        }
        plugin.recorder().onDeath(victim, msg);

        if (st.autoSavePvpDeath) {
            Player killer = victim.getKiller();
            if (killer != null && !killer.equals(victim)) {
                plugin.service().trigger("PvP: " + killer.getName() + " killed " + victim.getName(),
                        List.of(victim, killer), st.preSeconds, st.postSeconds,
                        "death:" + victim.getUniqueId(), st.autoSaveCooldown, null);
            }
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onLaunch(ProjectileLaunchEvent e) {
        plugin.recorder().onProjectile(e.getEntity());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBreak(BlockBreakEvent e) {
        plugin.recorder().onBlock(e.getPlayer(), e.getBlock(), "minecraft:air");
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPlace(BlockPlaceEvent e) {
        plugin.recorder().onBlock(e.getPlayer(), e.getBlockPlaced(), e.getBlockPlaced().getBlockData().getAsString());
    }

    /** Chat arrives on an async thread, so hand it to the main thread before touching the recorder. */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onChat(AsyncChatEvent e) {
        if (!st.recordChat) return;
        final Player p = e.getPlayer();
        final String text = PlainTextComponentSerializer.plainText().serialize(e.message());
        Bukkit.getScheduler().runTask(plugin, () -> plugin.recorder().onChat(p, text));
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onCommand(PlayerCommandPreprocessEvent e) {
        plugin.recorder().onCommand(e.getPlayer(), e.getMessage());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onDrop(PlayerDropItemEvent e) {
        plugin.recorder().onItem(e.getPlayer(), false, e.getItemDrop().getItemStack());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPickup(EntityPickupItemEvent e) {
        if (e.getEntity() instanceof Player p) plugin.recorder().onItem(p, true, e.getItem().getItemStack());
    }

    /** Remove replay mannequins left behind by a crash. */
    @EventHandler
    public void onEntitiesLoad(EntitiesLoadEvent e) {
        for (Entity ent : e.getEntities()) {
            if (NpcFactory.isReplayEntity(plugin, ent)) ent.remove();
        }
    }
}
