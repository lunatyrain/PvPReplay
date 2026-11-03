package com.pvpreplay.record;

import org.bukkit.Bukkit;
import org.bukkit.inventory.Inventory;
import org.bukkit.plugin.Plugin;

import java.lang.reflect.Method;
import java.util.UUID;

/**
 * Reads a player's RankedSMPX "extra inventory" (/inv) if that plugin is installed. Done by reflection, so
 * PvPReplay needs no RankedSMPX at build time and simply records nothing when it is missing.
 */
public final class ExtraInv {
    private static boolean failed = false;
    private static Method getManager;
    private static Method getExtra;

    private ExtraInv() {
    }

    /** The player's extra inventory as RankedSMPX currently holds it, or null (not installed / no extra slots). */
    public static Inventory of(UUID id) {
        if (failed) return null;
        try {
            Plugin rs = Bukkit.getPluginManager().getPlugin("RankedSMPX");
            if (rs == null || !rs.isEnabled()) return null;
            if (getManager == null) {
                getManager = rs.getClass().getMethod("getInventoryManager");
                Object mgr = getManager.invoke(rs);
                getExtra = mgr.getClass().getMethod("getExtraInventory", UUID.class);
            }
            Object mgr = getManager.invoke(rs);
            if (mgr == null) return null;
            Object inv = getExtra.invoke(mgr, id);
            return inv instanceof Inventory i ? i : null;
        } catch (ReflectiveOperationException | RuntimeException | LinkageError e) {
            failed = true; // a different RankedSMPX version: stop trying
            Bukkit.getLogger().warning("[PvPReplay] Could not read RankedSMPX extra inventories (" + e + "); not recording them.");
            return null;
        }
    }
}
