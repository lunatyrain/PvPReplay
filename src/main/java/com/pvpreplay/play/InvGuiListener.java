package com.pvpreplay.play;

import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;

/** The replay inventory window and the players menu are look-only: nothing can be taken, moved or put in. */
public final class InvGuiListener implements Listener {

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onClick(InventoryClickEvent e) {
        var holder = e.getInventory().getHolder();
        if (holder instanceof ReplayInvHolder ih) {
            e.setCancelled(true);
            if (e.getClickedInventory() == e.getInventory()) ih.session().invClick(e.getSlot());
        } else if (holder instanceof ReplayPlayersHolder ph) {
            e.setCancelled(true);
            // only clicks on the menu itself count, not on the viewer's own inventory below it
            if (e.getClickedInventory() == e.getInventory()) ph.session().menuClick(e.getSlot(), e.isRightClick());
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onDrag(InventoryDragEvent e) {
        var holder = e.getInventory().getHolder();
        if (holder instanceof ReplayInvHolder || holder instanceof ReplayPlayersHolder) e.setCancelled(true);
    }
}
