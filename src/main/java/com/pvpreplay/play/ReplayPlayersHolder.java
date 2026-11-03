package com.pvpreplay.play;

import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;

/** Marks the "Players" menu: one player head per person in the replay, click one to teleport to them. */
public final class ReplayPlayersHolder implements InventoryHolder {
    private final PlaybackSession session;
    private final int[] pidAt;
    private Inventory inventory;

    ReplayPlayersHolder(PlaybackSession session, int size) {
        this.session = session;
        this.pidAt = new int[size];
        java.util.Arrays.fill(pidAt, -1);
    }

    void set(Inventory inventory) {
        this.inventory = inventory;
    }

    void bind(int slot, int pid) {
        if (slot >= 0 && slot < pidAt.length) pidAt[slot] = pid;
    }

    /** The replay player shown in that slot, or -1 for an empty slot. */
    int pidAt(int slot) {
        return slot >= 0 && slot < pidAt.length ? pidAt[slot] : -1;
    }

    PlaybackSession session() {
        return session;
    }

    @Override
    public Inventory getInventory() {
        return inventory;
    }
}
