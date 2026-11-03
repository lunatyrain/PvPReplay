package com.pvpreplay.play;

import com.destroystokyo.paper.profile.ProfileProperty;
import com.pvpreplay.Compat;
import com.pvpreplay.PvPReplayPlugin;
import com.pvpreplay.data.PlayerInfo;
import net.kyori.adventure.text.Component;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.World;
import org.bukkit.entity.Entity;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.ItemDisplay;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataType;

import java.util.Locale;

/**
 * Creates the fake players and projectiles shown during playback.
 * Everything is invisible to everyone except the one viewer, is never saved with the chunk, and is tagged so
 * leftovers (after a crash) get cleaned up.
 */
public final class NpcFactory {
    private NpcFactory() {
    }

    public static NamespacedKey tagKey(PvPReplayPlugin plugin) {
        return new NamespacedKey(plugin, "replay_npc");
    }

    private static boolean warnedSkin = false;
    private static boolean warnedType = false;

    /** Spawns a player-looking entity (a Mannequin; falls back to an armor stand on servers without it). */
    public static Entity spawnPlayer(PvPReplayPlugin plugin, Location loc, PlayerInfo info, Player viewer) {
        World w = loc.getWorld();
        if (w == null) return null;

        Class<? extends Entity> cls = null;
        try {
            cls = EntityType.valueOf("MANNEQUIN").getEntityClass();
        } catch (IllegalArgumentException ignored) {
            // older server
        }
        if (cls == null) {
            cls = EntityType.ARMOR_STAND.getEntityClass();
            if (!warnedType) {
                warnedType = true;
                plugin.getLogger().warning("This server has no Mannequin entity; replays will use armor stands instead.");
            }
        }
        if (cls == null) return null;

        final NamespacedKey key = tagKey(plugin);
        Entity e = w.spawn(loc, cls, ent -> {
            ent.setVisibleByDefault(false);
            ent.setPersistent(false);
            ent.setInvulnerable(true);
            ent.setGravity(false);
            ent.setSilent(true);
            ent.customName(Component.text(info.name()));
            ent.setCustomNameVisible(true);
            ent.getPersistentDataContainer().set(key, PersistentDataType.BYTE, (byte) 1);
            if (ent instanceof LivingEntity le) {
                le.setAI(false);
                le.setCollidable(false);
                le.setCanPickupItems(false);
                le.setRemoveWhenFarAway(false);
            }
        });
        if (e == null) return null;

        // Mannequin-only extras; silently skipped if the methods do not exist.
        Compat.call(e, "setImmovable", true);
        Compat.call(e, "setDescription", new Object[]{null});
        if (plugin.settings().useSkins) applySkin(plugin, e, info);

        viewer.showEntity(plugin, e);
        return e;
    }

    /** Sets the mannequin's skin via reflection (the profile API is new). Falls back to the default skin. */
    private static void applySkin(PvPReplayPlugin plugin, Entity e, PlayerInfo info) {
        try {
            Class<?> rp = Class.forName("io.papermc.paper.datacomponent.item.ResolvableProfile");
            Class<?> bc = Class.forName("io.papermc.paper.datacomponent.item.ResolvableProfile$Builder");
            Object builder = rp.getMethod("resolvableProfile").invoke(null);
            Compat.call(builder, "uuid", info.uuid());
            Compat.call(builder, "name", info.name());
            if (info.hasSkin()) {
                ProfileProperty prop = new ProfileProperty("textures", info.skinValue(),
                        info.skinSignature() == null || info.skinSignature().isEmpty() ? null : info.skinSignature());
                Compat.call(builder, "addProperty", prop);
            }
            Object profile = bc.getMethod("build").invoke(builder);
            if (!Compat.has(e, "setProfile", 1)) throw new IllegalStateException("no setProfile");
            Compat.call(e, "setProfile", profile);
        } catch (Throwable t) {
            if (!warnedSkin) {
                warnedSkin = true;
                plugin.getLogger().warning("Could not apply player skins to replay mannequins (" + t.getClass().getSimpleName()
                        + "); the default skin will be used. Set playback.use-skins: false to silence this.");
            }
        }
    }

    /** Spawns a small item display used for arrows, pearls etc. */
    public static Entity spawnProjectile(PvPReplayPlugin plugin, Location loc, String type, Player viewer) {
        World w = loc.getWorld();
        if (w == null) return null;
        final NamespacedKey key = tagKey(plugin);
        final ItemStack item = new ItemStack(materialFor(type));
        ItemDisplay d = w.spawn(loc, ItemDisplay.class, disp -> {
            disp.setItemStack(item);
            disp.setVisibleByDefault(false);
            disp.setPersistent(false);
            disp.getPersistentDataContainer().set(key, PersistentDataType.BYTE, (byte) 1);
        });
        if (d == null) return null;
        Compat.call(d, "setTeleportDuration", 1);
        viewer.showEntity(plugin, d);
        return d;
    }

    static Material materialFor(String type) {
        String t = type == null ? "" : type.toUpperCase(Locale.ROOT);
        String name = switch (t) {
            case "SMALL_FIREBALL", "FIREBALL", "DRAGON_FIREBALL" -> "FIRE_CHARGE";
            case "BREEZE_WIND_CHARGE" -> "WIND_CHARGE";
            case "FISHING_BOBBER" -> "FISHING_ROD";
            case "POTION", "SPLASH_POTION", "LINGERING_POTION" -> "SPLASH_POTION";
            case "THROWN_EXP_BOTTLE", "EXPERIENCE_BOTTLE" -> "EXPERIENCE_BOTTLE";
            default -> t;
        };
        Material m = Material.matchMaterial(name);
        return m == null || m.isAir() ? Material.SNOWBALL : m;
    }

    public static boolean isReplayEntity(PvPReplayPlugin plugin, Entity e) {
        return e.getPersistentDataContainer().has(tagKey(plugin), PersistentDataType.BYTE);
    }
}
