package com.pvpreplay.play;

import com.pvpreplay.PvPReplayPlugin;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;

import java.util.List;
import java.util.Locale;

/** The control items in the viewer's hotbar, and safe storage of the viewer's real inventory while watching. */
public final class Hotbar {
    private Hotbar() {
    }

    static final String[] IDS = {"rewind", "toggle", "forward", "speed", "fight", "players", "inv", "extra", "stop"};

    private static Component name(String text, NamedTextColor c) {
        return Component.text(text, c).decoration(TextDecoration.ITALIC, false).decoration(TextDecoration.BOLD, true);
    }

    private static Component lore(String text) {
        return Component.text(text, NamedTextColor.GRAY).decoration(TextDecoration.ITALIC, false);
    }

    static ItemStack item(PvPReplayPlugin pl, String id, PlaybackSession s) {
        Material m;
        Component n;
        List<Component> l;
        switch (id) {
            case "rewind" -> {
                m = Material.SPECTRAL_ARROW;
                n = name("Rewind", NamedTextColor.AQUA);
                l = List.of(lore("Right-click: back 10s"), lore("Left-click: back 5s"));
            }
            case "toggle" -> {
                boolean paused = s.isPaused();
                m = paused ? Material.LIME_DYE : Material.YELLOW_DYE;
                n = name(paused ? "Play" : "Pause", paused ? NamedTextColor.GREEN : NamedTextColor.YELLOW);
                l = List.of(lore("Click: pause / play"));
            }
            case "forward" -> {
                m = Material.ARROW;
                n = name("Forward", NamedTextColor.AQUA);
                l = List.of(lore("Right-click: forward 10s"), lore("Left-click: forward 5s"));
            }
            case "speed" -> {
                m = Material.FEATHER;
                String sp = String.format(Locale.ROOT, "%.2f", s.speed()).replaceAll("0+$", "").replaceAll("\\.$", "");
                n = name("Speed: x" + sp, NamedTextColor.GOLD);
                l = List.of(lore("Right-click: faster"), lore("Left-click: slower"));
            }
            case "fight" -> {
                m = Material.IRON_SWORD;
                n = name("Fights", NamedTextColor.GREEN);
                l = List.of(lore("Right-click: next fight"), lore("Left-click: previous fight"));
            }
            case "players" -> {
                m = Material.COMPASS;
                n = name("Players", NamedTextColor.LIGHT_PURPLE);
                l = List.of(lore("Right-click: follow next player"), lore("Left-click: free camera"));
            }
            case "inv" -> {
                m = Material.CHEST;
                n = name("Inventory", NamedTextColor.LIGHT_PURPLE);
                l = List.of(lore("Opens the inventory of the player"), lore("you follow (or the nearest one)"));
            }
            case "extra" -> {
                m = Material.ENDER_CHEST;
                n = name("Extra Inventory", NamedTextColor.DARK_PURPLE);
                l = List.of(lore("Opens the /extrainventory of the player"), lore("you follow (or the nearest one)"));
            }
            default -> {
                m = Material.BARRIER;
                n = name("Stop", NamedTextColor.RED);
                l = List.of(lore("Stop watching and go back"));
            }
        }
        String texKey = id.equals("toggle") ? (s.isPaused() ? "play" : "pause") : id;
        String tex = pl.settings().hotbarHeads.get(texKey);
        if (tex != null) m = Material.PLAYER_HEAD;
        ItemStack it = new ItemStack(m);
        ItemMeta meta = it.getItemMeta();
        if (tex != null && meta instanceof org.bukkit.inventory.meta.SkullMeta sm) {
            try {
                com.destroystokyo.paper.profile.PlayerProfile prof = org.bukkit.Bukkit.createProfile(
                        java.util.UUID.nameUUIDFromBytes(("pvpreplay:" + texKey).getBytes(java.nio.charset.StandardCharsets.UTF_8)), "pvpreplay");
                prof.setProperty(new com.destroystokyo.paper.profile.ProfileProperty("textures", tex));
                sm.setPlayerProfile(prof);
            } catch (Throwable t) {
                pl.getLogger().warning("Bad head texture for hotbar item '" + texKey + "': " + t);
            }
        }
        meta.displayName(n);
        meta.lore(l);
        meta.getPersistentDataContainer().set(pl.ctlKey(), PersistentDataType.STRING, id);
        it.setItemMeta(meta);
        return it;
    }

    /** Puts (or refreshes) the nine control items in slots 0-8. */
    static void install(PvPReplayPlugin pl, Player v, PlaybackSession s) {
        for (int i = 0; i < IDS.length; i++) v.getInventory().setItem(i, item(pl, IDS[i], s));
    }

    static String idOf(PvPReplayPlugin pl, ItemStack it) {
        if (it == null || !it.hasItemMeta()) return null;
        return it.getItemMeta().getPersistentDataContainer().get(pl.ctlKey(), PersistentDataType.STRING);
    }

    /** Stores the viewer's inventory (also on the player itself, in case the server crashes), then empties it. */
    static ItemStack[] saveInventory(PvPReplayPlugin pl, Player v) {
        ItemStack[] contents = v.getInventory().getContents();
        try {
            v.getPersistentDataContainer().set(pl.invKey(), PersistentDataType.BYTE_ARRAY,
                    ItemStack.serializeItemsAsBytes(contents));
        } catch (Throwable t) {
            pl.getLogger().warning("Could not back up the inventory of " + v.getName() + ": " + t);
        }
        v.getInventory().clear();
        return contents;
    }

    /** Gives the real inventory back. {@code mem} may be null (then the backup on the player is used). */
    static void restore(PvPReplayPlugin pl, Player v, ItemStack[] mem) {
        ItemStack[] c = mem;
        if (c == null) {
            byte[] b = v.getPersistentDataContainer().get(pl.invKey(), PersistentDataType.BYTE_ARRAY);
            if (b != null) {
                try {
                    c = ItemStack.deserializeItemsFromBytes(b);
                } catch (Throwable t) {
                    pl.getLogger().warning("Could not read the inventory backup of " + v.getName() + ": " + t);
                }
            }
        }
        if (c != null) v.getInventory().setContents(c);
        v.getPersistentDataContainer().remove(pl.invKey());
    }

    /** After a crash or kick: if the player still has a stored inventory, put it back. */
    public static void recover(PvPReplayPlugin pl, Player v) {
        if (v.getPersistentDataContainer().has(pl.invKey(), PersistentDataType.BYTE_ARRAY)) {
            v.getInventory().clear();
            restore(pl, v, null);
        }
    }
}
