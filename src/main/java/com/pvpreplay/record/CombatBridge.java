package com.pvpreplay.record;

import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;

import java.lang.reflect.Method;
import java.util.UUID;

/** Asks the CombatTag plugin (by reflection) whether a player is currently combat tagged. */
final class CombatBridge {
    private Object manager;
    private Method byUuid;
    private Method byPlayer;
    private long nextTry = 0L;

    private void init() {
        if (manager != null || System.currentTimeMillis() < nextTry) return;
        nextTry = System.currentTimeMillis() + 10_000L;
        try {
            Plugin pl = Bukkit.getPluginManager().getPlugin("CombatTag");
            if (pl == null || !pl.isEnabled()) return;
            Object m = pl.getClass().getMethod("getCombatManager").invoke(pl);
            if (m == null) return;
            Method u = null, p = null;
            for (Method me : m.getClass().getDeclaredMethods()) {
                if (!me.getName().equals("isTagged") || me.getParameterCount() != 1) continue;
                if (me.getReturnType() != boolean.class) continue;
                Class<?> t = me.getParameterTypes()[0];
                me.setAccessible(true);
                if (t == UUID.class) u = me;
                else if (Player.class.isAssignableFrom(t)) p = me;
            }
            if (u == null && p == null) return;
            manager = m;
            byUuid = u;
            byPlayer = p;
        } catch (Throwable ignored) {
            // not installed / different version: caller falls back to its own timer
        }
    }

    /** True/false from CombatTag, or null if CombatTag is not usable. */
    Boolean tagged(Player pl) {
        init();
        if (manager == null) return null;
        try {
            Object r = byUuid != null ? byUuid.invoke(manager, pl.getUniqueId()) : byPlayer.invoke(manager, pl);
            return (Boolean) r;
        } catch (Throwable t) {
            return null;
        }
    }
}
