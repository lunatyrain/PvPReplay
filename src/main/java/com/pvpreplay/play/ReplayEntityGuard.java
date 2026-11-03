package com.pvpreplay.play;

import com.pvpreplay.PvPReplayPlugin;
import org.bukkit.entity.Entity;
import org.bukkit.event.Cancellable;
import org.bukkit.event.Event;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityExplodeEvent;
import org.bukkit.event.entity.ExplosionPrimeEvent;
import org.bukkit.event.entity.PotionSplashEvent;
import org.bukkit.event.entity.ProjectileHitEvent;
import org.bukkit.event.player.PlayerPickupArrowEvent;

/**
 * The replay's projectiles are real entities (only the viewer can see them). They must never hit, hurt, explode,
 * splash or be picked up in the real world, so every such event from one of them is cancelled.
 */
public final class ReplayEntityGuard implements Listener {
    private final PvPReplayPlugin plugin;

    public ReplayEntityGuard(PvPReplayPlugin plugin) {
        this.plugin = plugin;
    }

    /**
     * Replay experience orbs must not be picked up by real players or merge with real orbs. Those two events live in
     * Paper packages that have moved between versions, so they are looked up by name; whichever exists gets guarded.
     */
    public void registerOrbGuards() {
        guard(new String[]{"io.papermc.paper.event.player.PlayerPickupExperienceEvent",
                "com.destroystokyo.paper.event.player.PlayerPickupExperienceEvent"}, "getExperienceOrb");
        guard(new String[]{"io.papermc.paper.event.entity.ExperienceOrbMergeEvent",
                "com.destroystokyo.paper.event.entity.ExperienceOrbMergeEvent",
                "org.bukkit.event.entity.ExperienceOrbMergeEvent"}, "getMergeTarget", "getMergeSource");
    }

    @SuppressWarnings("unchecked")
    private void guard(String[] classNames, String... getters) {
        for (String name : classNames) {
            Class<?> raw;
            try {
                raw = Class.forName(name);
            } catch (ClassNotFoundException ex) {
                continue;
            }
            if (!Event.class.isAssignableFrom(raw) || !Cancellable.class.isAssignableFrom(raw)) continue;
            plugin.getServer().getPluginManager().registerEvent((Class<? extends Event>) raw, this, EventPriority.LOWEST,
                    (listener, event) -> {
                        if (!raw.isInstance(event)) return;
                        for (String g : getters) {
                            try {
                                Object o = raw.getMethod(g).invoke(event);
                                if (o instanceof Entity ent && NpcFactory.isReplayEntity(plugin, ent)) {
                                    ((Cancellable) event).setCancelled(true);
                                    return;
                                }
                            } catch (ReflectiveOperationException ignored) {
                                // this server version has no such getter
                            }
                        }
                    }, plugin);
            return;
        }
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onHit(ProjectileHitEvent e) {
        if (NpcFactory.isReplayEntity(plugin, e.getEntity())) e.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onDamage(EntityDamageByEntityEvent e) {
        if (NpcFactory.isReplayEntity(plugin, e.getDamager())) e.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onPrime(ExplosionPrimeEvent e) {
        if (NpcFactory.isReplayEntity(plugin, e.getEntity())) e.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onExplode(EntityExplodeEvent e) {
        if (NpcFactory.isReplayEntity(plugin, e.getEntity())) e.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onSplash(PotionSplashEvent e) {
        if (NpcFactory.isReplayEntity(plugin, e.getEntity())) e.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onPickup(PlayerPickupArrowEvent e) {
        if (NpcFactory.isReplayEntity(plugin, e.getArrow())) e.setCancelled(true);
    }
}
