package com.pvpreplay.play;

import com.pvpreplay.PvPReplayPlugin;
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
