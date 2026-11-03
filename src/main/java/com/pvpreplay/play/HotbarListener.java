package com.pvpreplay.play;

import com.pvpreplay.PvPReplayPlugin;
import org.bukkit.entity.Player;
import org.bukkit.event.Event;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.entity.EntityPickupItemEvent;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.player.PlayerDropItemEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerSwapHandItemsEvent;
import org.bukkit.inventory.EquipmentSlot;

/** Handles clicks on the hotbar control items and keeps people who are watching a replay from changing anything. */
public final class HotbarListener implements Listener {
    private final PvPReplayPlugin plugin;

    public HotbarListener(PvPReplayPlugin plugin) {
        this.plugin = plugin;
    }

    private PlaybackSession hot(Player p) {
        PlaybackSession s = plugin.playback().get(p);
        return s != null && s.hotbarMode() ? s : null;
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onInteract(PlayerInteractEvent e) {
        PlaybackSession s = hot(e.getPlayer());
        if (s == null) return;
        e.setUseInteractedBlock(Event.Result.DENY);
        e.setUseItemInHand(Event.Result.DENY);
        e.setCancelled(true);
        if (e.getHand() != EquipmentSlot.HAND) return;
        Action a = e.getAction();
        if (a == Action.PHYSICAL) return;
        String id = Hotbar.idOf(plugin, e.getItem());
        if (id == null) return;
        s.onControl(id, a == Action.LEFT_CLICK_AIR || a == Action.LEFT_CLICK_BLOCK);
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onClick(InventoryClickEvent e) {
        if (e.getWhoClicked() instanceof Player p && plugin.playback().isViewing(p)) e.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onDrag(InventoryDragEvent e) {
        if (e.getWhoClicked() instanceof Player p && plugin.playback().isViewing(p)) e.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onDrop(PlayerDropItemEvent e) {
        if (hot(e.getPlayer()) != null) e.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onSwap(PlayerSwapHandItemsEvent e) {
        if (hot(e.getPlayer()) != null) e.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onBreak(BlockBreakEvent e) {
        if (hot(e.getPlayer()) != null) e.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onPlace(BlockPlaceEvent e) {
        if (hot(e.getPlayer()) != null) e.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onPickup(EntityPickupItemEvent e) {
        if (e.getEntity() instanceof Player p && plugin.playback().isViewing(p)) e.setCancelled(true);
    }
}
