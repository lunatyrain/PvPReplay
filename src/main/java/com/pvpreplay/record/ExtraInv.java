package com.pvpreplay.record;

import org.bukkit.Bukkit;
import org.bukkit.inventory.Inventory;
import org.bukkit.plugin.Plugin;

import java.lang.reflect.Method;
import java.util.UUID;

/**
 * Read-only bridge to the /extrainventory of RankedSMPX (found by reflection, so PvPReplay loads without it).
 * Replay slot numbers 41..94 hold the extra inventory (slots 0..40 are the normal player inventory).
 */
public final class ExtraInv {
    public static final int BASE = 41;
    public static final int MAX = 54;

    private static Object manager;
    private static Method has;
    private static Method get;
    private static long nextTry = 0L;

    private ExtraInv() {
    }

    private static void init() {
        if (get != null || System.currentTimeMillis() < nextTry) return;
        nextTry = System.currentTimeMillis() + 10_000L;
        try {
            Plugin pl = Bukkit.getPluginManager().getPlugin("RankedSMPX");
            if (pl == null || !pl.isEnabled()) return;
            Object m = pl.getClass().getMethod("getInventoryManager").invoke(pl);
            if (m == null) return;
            Method g = m.getClass().getMethod("getExtraInventory", UUID.class);
            Method h = null;
            try {
                h = m.getClass().getMethod("hasExtraInventory", UUID.class);
            } catch (NoSuchMethodException ignored) {
                // optional
            }
            manager = m;
            has = h;
            get = g;
        } catch (Throwable ignored) {
            // RankedSMPX missing or a different version: no extra inventory recording
        }
    }

    /** The live extra inventory of a player, or null when they have none / RankedSMPX is not installed. */
    public static Inventory of(UUID id) {
        init();
        if (get == null) return null;
        try {
            if (has != null && !Boolean.TRUE.equals(has.invoke(manager, id))) return null;
            Object o = get.invoke(manager, id);
            return o instanceof Inventory inv ? inv : null;
        } catch (Throwable t) {
            return null;
        }
    }
}
