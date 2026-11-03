package com.pvpreplay.play;

import com.destroystokyo.paper.profile.ProfileProperty;
import com.pvpreplay.Compat;
import com.pvpreplay.PvPReplayPlugin;
import com.pvpreplay.data.PlayerInfo;
import net.kyori.adventure.text.Component;
import org.bukkit.Color;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.World;
import org.bukkit.entity.Entity;
import org.bukkit.entity.EntityType;
import org.bukkit.block.data.BlockData;
import org.bukkit.entity.BlockDisplay;
import org.bukkit.entity.ItemDisplay;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Display;
import org.bukkit.entity.TextDisplay;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.util.Transformation;
import org.joml.AxisAngle4f;
import org.joml.Vector3f;

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

    /**
     * Spawns the real projectile entity (arrow, trident, ender pearl, snowball, potion...) that was recorded, shown only
     * to the viewer, frozen in place (we move it ourselves every tick) and harmless. Types that cannot be spawned this
     * way fall back to a small item display.
     */
    public static Entity spawnProjectile(PvPReplayPlugin plugin, Location loc, String type, Player viewer) {
        Entity real = spawnRealProjectile(plugin, loc, type, viewer);
        if (real != null) return real;
        return spawnProjectileItem(plugin, loc, type, viewer);
    }

    private static Entity spawnRealProjectile(PvPReplayPlugin plugin, Location loc, String type, Player viewer) {
        World w = loc.getWorld();
        if (w == null) return null;
        if (type != null && type.startsWith("EXPERIENCE_ORB")) return spawnOrb(plugin, loc, type, viewer);
        final EntityType et;
        try {
            et = EntityType.valueOf(type);
        } catch (IllegalArgumentException ex) {
            return null;
        }
        // these need a shooter / target / flight time to behave, so they stay as item icons
        if (et == EntityType.FISHING_BOBBER || et == EntityType.FIREWORK_ROCKET || et == EntityType.SHULKER_BULLET) return null;
        Class<? extends Entity> cls = et.getEntityClass();
        if (cls == null || !org.bukkit.entity.Projectile.class.isAssignableFrom(cls)) return null;
        final NamespacedKey key = tagKey(plugin);
        try {
            Entity e = w.spawn(loc, cls, ent -> {
                ent.setVisibleByDefault(false);
                ent.setPersistent(false);
                ent.setInvulnerable(true);
                ent.setGravity(false);
                ent.setSilent(true);
                ent.setVelocity(new org.bukkit.util.Vector(0, 0, 0));
                ent.getPersistentDataContainer().set(key, PersistentDataType.BYTE, (byte) 1);
                if (ent instanceof org.bukkit.entity.AbstractArrow ar) {
                    ar.setPickupStatus(org.bukkit.entity.AbstractArrow.PickupStatus.DISALLOWED);
                    ar.setDamage(0);
                }
                if (ent instanceof org.bukkit.entity.ThrownPotion tp) {
                    tp.setItem(new ItemStack(et == EntityType.LINGERING_POTION ? Material.LINGERING_POTION : Material.SPLASH_POTION));
                }
            });
            if (e == null) return null;
            viewer.showEntity(plugin, e);
            return e;
        } catch (Throwable t) {
            return null; // another plugin cancelled the spawn, or this entity cannot be created like that
        }
    }

    /** A real experience orb ("EXPERIENCE_ORB:value"); its size comes from the recorded value. Harmless, viewer-only. */
    private static Entity spawnOrb(PvPReplayPlugin plugin, Location loc, String type, Player viewer) {
        World w = loc.getWorld();
        if (w == null) return null;
        int value = 3;
        int c = type.indexOf(':');
        if (c >= 0) {
            try {
                value = Math.max(1, Integer.parseInt(type.substring(c + 1)));
            } catch (NumberFormatException ignored) {
                // keep the default size
            }
        }
        final int xp = value;
        final NamespacedKey key = tagKey(plugin);
        try {
            Entity e = w.spawn(loc, org.bukkit.entity.ExperienceOrb.class, orb -> {
                orb.setVisibleByDefault(false);
                orb.setPersistent(false);
                orb.setInvulnerable(true);
                orb.setGravity(false);
                orb.setSilent(true);
                orb.setExperience(xp);
                orb.getPersistentDataContainer().set(key, PersistentDataType.BYTE, (byte) 1);
            });
            if (e == null) return null;
            viewer.showEntity(plugin, e);
            return e;
        } catch (Throwable t) {
            return null;
        }
    }

    /** Fallback: a small item display used when the real projectile cannot be spawned. */
    private static Entity spawnProjectileItem(PvPReplayPlugin plugin, Location loc, String type, Player viewer) {
        World w = loc.getWorld();
        if (w == null) return null;
        final NamespacedKey key = tagKey(plugin);
        final ItemStack item = new ItemStack(type != null && type.startsWith("EXPERIENCE_ORB")
                ? Material.EXPERIENCE_BOTTLE : materialFor(type));
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

    /** A real copy of a broken block that shrinks away in a few ticks (no outline), so breaking looks like breaking. */
    public static Entity spawnBreakGhost(PvPReplayPlugin plugin, Location loc, BlockData data, Player viewer) {
        World w = loc.getWorld();
        if (w == null) return null;
        final NamespacedKey key = tagKey(plugin);
        BlockDisplay d = w.spawn(loc, BlockDisplay.class, disp -> {
            disp.setBlock(data);
            disp.setVisibleByDefault(false);
            disp.setPersistent(false);
            disp.setBrightness(new Display.Brightness(15, 15));
            disp.getPersistentDataContainer().set(key, PersistentDataType.BYTE, (byte) 1);
        });
        if (d == null) return null;
        viewer.showEntity(plugin, d);
        // next tick, shrink toward the block's centre
        org.bukkit.Bukkit.getScheduler().runTaskLater(plugin, () -> {
            if (!d.isValid()) return;
            d.setInterpolationDelay(0);
            d.setInterpolationDuration(6);
            d.setTransformation(new Transformation(new Vector3f(0.5f, 0.5f, 0.5f), new AxisAngle4f(),
                    new Vector3f(0.02f, 0.02f, 0.02f), new AxisAngle4f()));
        }, 1L);
        return d;
    }

    // The floating inventory above a player's head is built from these three pieces. All of them face the viewer
    // (billboard) and are positioned relative to the same spot by their transformation, which is applied after the
    // billboard turn, so x/y/z are "right/up/toward the viewer" no matter where the viewer stands.

    /** A real item icon, drawn the way it looks in an inventory slot. */
    public static ItemDisplay spawnTagItem(PvPReplayPlugin plugin, Location loc, ItemStack item, Transformation t, Player viewer) {
        World w = loc.getWorld();
        if (w == null) return null;
        final NamespacedKey key = tagKey(plugin);
        ItemDisplay d = w.spawn(loc, ItemDisplay.class, disp -> {
            disp.setItemStack(item);
            disp.setItemDisplayTransform(ItemDisplay.ItemDisplayTransform.GUI);
            disp.setBillboard(Display.Billboard.CENTER);
            disp.setBrightness(new Display.Brightness(15, 15));
            disp.setTransformation(t);
            disp.setVisibleByDefault(false);
            disp.setPersistent(false);
            disp.getPersistentDataContainer().set(key, PersistentDataType.BYTE, (byte) 1);
        });
        if (d == null) return null;
        Compat.call(d, "setTeleportDuration", 1);
        viewer.showEntity(plugin, d);
        return d;
    }

    /** A flat coloured panel (the inventory background, or the highlight behind the held hotbar slot). */
    public static BlockDisplay spawnTagPanel(PvPReplayPlugin plugin, Location loc, Material material, Transformation t, Player viewer) {
        World w = loc.getWorld();
        if (w == null) return null;
        final NamespacedKey key = tagKey(plugin);
        BlockDisplay d = w.spawn(loc, BlockDisplay.class, disp -> {
            disp.setBlock(material.createBlockData());
            disp.setBillboard(Display.Billboard.CENTER);
            disp.setBrightness(new Display.Brightness(15, 15));
            disp.setTransformation(t);
            disp.setVisibleByDefault(false);
            disp.setPersistent(false);
            disp.getPersistentDataContainer().set(key, PersistentDataType.BYTE, (byte) 1);
        });
        if (d == null) return null;
        Compat.call(d, "setTeleportDuration", 1);
        viewer.showEntity(plugin, d);
        return d;
    }

    /** The small stack-size number in the corner of a slot, like in a normal inventory. */
    public static TextDisplay spawnTagCount(PvPReplayPlugin plugin, Location loc, int amount, Transformation t, Player viewer) {
        World w = loc.getWorld();
        if (w == null) return null;
        final NamespacedKey key = tagKey(plugin);
        TextDisplay d = w.spawn(loc, TextDisplay.class, disp -> {
            disp.text(Component.text(String.valueOf(amount)));
            disp.setShadowed(true);
            disp.setBackgroundColor(Color.fromARGB(0, 0, 0, 0));
            disp.setBillboard(Display.Billboard.CENTER);
            disp.setBrightness(new Display.Brightness(15, 15));
            disp.setTransformation(t);
            disp.setVisibleByDefault(false);
            disp.setPersistent(false);
            disp.getPersistentDataContainer().set(key, PersistentDataType.BYTE, (byte) 1);
        });
        if (d == null) return null;
        Compat.call(d, "setTeleportDuration", 1);
        viewer.showEntity(plugin, d);
        return d;
    }

    /** A shield floating in front of a player who is blocking. */
    public static Entity spawnShield(PvPReplayPlugin plugin, Location loc, Player viewer) {
        World w = loc.getWorld();
        if (w == null) return null;
        final NamespacedKey key = tagKey(plugin);
        ItemDisplay d = w.spawn(loc, ItemDisplay.class, disp -> {
            disp.setItemStack(new ItemStack(Material.SHIELD));
            disp.setVisibleByDefault(false);
            disp.setPersistent(false);
            disp.setTransformation(new Transformation(new Vector3f(), new AxisAngle4f(), new Vector3f(0.8f, 0.8f, 0.8f), new AxisAngle4f()));
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
