package com.pvpreplay.play;

import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;

/** Marks the read-only inventory window (inventory / extra inventory / ender chest of a replayed player). */
public final class ReplayInvHolder implements InventoryHolder {
    private final PlaybackSession session;
    private Inventory inventory;

    ReplayInvHolder(PlaybackSession session) {
        this.session = session;
    }

    PlaybackSession session() {
        return session;
    }

    void set(Inventory inventory) {
        this.inventory = inventory;
    }

    @Override
    public Inventory getInventory() {
        return inventory;
    }
}
