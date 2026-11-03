package com.pvpreplay.record;

import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.plugin.Plugin;

import java.lang.reflect.Method;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Reads a player's RankedSMPX "extra inventory" (/inv) if that plugin is installed. Done by reflection, so
 * PvPReplay needs no RankedSMPX at build time and simply records nothing when it is missing.
 *
 * RankedSMPX only keeps a player's extra inventory in memory after that player has opened /inv since joining.
 * Until then getExtraInventory(uuid) returns null even though the player owns items in it, so for those players
 * we load a detached copy from RankedSMPX's storage (loadInventory(uuid, inventory)) and cache it briefly.
 */
public final class ExtraInv {
    private static final long CACHE_MS = 5_000L;

    private static boolean failed = false;
    private static Method getManager;
    private static Method getExtra;
    private static Method getSlots;   // InventoryManager#getExtraSlots(Player)
    private static Method loadInto;   // InventoryManager#loadInventory(UUID, Inventory)

    private record Cached(Inventory inv, long at) {
    }

    private static final Map<UUID, Cached> cache = new HashMap<>();

    private ExtraInv() {
    }

    /** The player's extra inventory (live if RankedSMPX holds it, else a stored copy), or null (not installed / no extra slots). */
    public static Inventory of(Player p) {
        if (failed) return null;
        try {
            Plugin rs = Bukkit.getPluginManager().getPlugin("RankedSMPX");
            if (rs == null || !rs.isEnabled()) return null;
            if (getManager == null) {
                getManager = rs.getClass().getMethod("getInventoryManager");
                Object mgr = getManager.invoke(rs);
                getExtra = mgr.getClass().getMethod("getExtraInventory", UUID.class);
                try {
                    getSlots = mgr.getClass().getMethod("getExtraSlots", Player.class);
                    loadInto = mgr.getClass().getMethod("loadInventory", UUID.class, Inventory.class);
                } catch (NoSuchMethodException ignored) {
                    getSlots = null; // older version: only the live inventory can be read
                    loadInto = null;
                }
            }
            Object mgr = getManager.invoke(rs);
            if (mgr == null) return null;

            UUID id = p.getUniqueId();
            Object live = getExtra.invoke(mgr, id);
            if (live instanceof Inventory i) {
                cache.remove(id);
                return i;
            }
            if (getSlots == null || loadInto == null) return null;

            long now = System.currentTimeMillis();
            Cached c = cache.get(id);
            if (c != null && now - c.at < CACHE_MS) return c.inv;

            int slots = (Integer) getSlots.invoke(mgr, p);
            Inventory copy = null;
            if (slots > 0) {
                int size = Math.min(54, (slots + 8) / 9 * 9);
                copy = Bukkit.createInventory(null, size);
                loadInto.invoke(mgr, id, copy);
            }
            cache.put(id, new Cached(copy, now));
            return copy;
        } catch (ReflectiveOperationException | RuntimeException | LinkageError e) {
            failed = true; // a different RankedSMPX version: stop trying
            Bukkit.getLogger().warning("[PvPReplay] Could not read RankedSMPX extra inventories (" + e + "); not recording them.");
            return null;
        }
    }

    /** Forget a player's cached copy (on quit). */
    public static void forget(UUID id) {
        cache.remove(id);
    }
}
