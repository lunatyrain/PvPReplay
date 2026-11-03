package com.pvpreplay.play;

import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;

/** Marks the read-only inventory window opened by /replay inv. */
public final class ReplayInvHolder implements InventoryHolder {
    private Inventory inventory;

    void set(Inventory inventory) {
        this.inventory = inventory;
    }

    @Override
    public Inventory getInventory() {
        return inventory;
    }
}
