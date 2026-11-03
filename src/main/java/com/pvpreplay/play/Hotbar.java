package com.pvpreplay.play;

import com.pvpreplay.PvPReplayPlugin;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.GameMode;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemFlag;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.inventory.meta.SkullMeta;
import org.bukkit.persistence.PersistentDataType;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.logging.Level;

/**
 * The control hotbar a viewer gets while watching in "hotbar mode", and the safe keeping of their real inventory:
 * it is written to disk before it is replaced and given back when the replay stops (or at next login after a crash).
 */
public final class Hotbar {
    public static final String PLAY = "play", BACK = "back", FWD = "fwd", SPEED = "speed", PLAYERS = "players",
            INV = "inv", INSPECT = "inspect", NOCLIP = "noclip", EXIT = "exit";

    private Hotbar() {
    }

    // ------------------------------------------------------------------ items

    private static ItemStack make(PvPReplayPlugin plugin, String id, Material m, String name, NamedTextColor color,
                                  String... lore) {
        ItemStack it = new ItemStack(m);
        ItemMeta meta = it.getItemMeta();
        meta.displayName(Component.text(name, color, TextDecoration.BOLD).decoration(TextDecoration.ITALIC, false));
        List<Component> l = new ArrayList<>();
        for (String s : lore) l.add(Component.text(s, NamedTextColor.GRAY).decoration(TextDecoration.ITALIC, false));
        meta.lore(l);
        meta.addItemFlags(ItemFlag.HIDE_ATTRIBUTES, ItemFlag.HIDE_ADDITIONAL_TOOLTIP);
        meta.getPersistentDataContainer().set(plugin.ctlKey(), PersistentDataType.STRING, id);
        it.setItemMeta(meta);
        return it;
    }

    private static ItemStack playPause(PvPReplayPlugin plugin, boolean paused) {
        return paused
                ? make(plugin, PLAY, Material.LIME_DYE, "Play", NamedTextColor.GREEN, "Click to resume.")
                : make(plugin, PLAY, Material.RED_DYE, "Pause", NamedTextColor.RED, "Click to pause.");
    }

    private static ItemStack speedItem(PvPReplayPlugin plugin, double speed) {
        String s = speed == Math.rint(speed) ? String.valueOf((int) speed) : String.valueOf(speed);
        return make(plugin, SPEED, Material.CLOCK, "Speed: " + s + "x", NamedTextColor.GOLD,
                "Right-click: faster", "Left-click: slower");
    }

    private static ItemStack playersItem(PvPReplayPlugin plugin, Player viewer) {
        ItemStack it = make(plugin, PLAYERS, Material.PLAYER_HEAD, "Players", NamedTextColor.AQUA,
                "Right-click: open the player menu.", "Click a head to teleport to that player.");
        if (it.getItemMeta() instanceof SkullMeta sm) {
            try {
                sm.setOwningPlayer(viewer);
                it.setItemMeta(sm);
            } catch (RuntimeException ignored) {
                // plain head
            }
        }
        return it;
    }

    /** Fills the hotbar of a viewer who has just been put in hotbar mode (their own items are already saved). */
    public static void give(PvPReplayPlugin plugin, Player p, boolean paused, double speed) {
        var inv = p.getInventory();
        inv.clear();
        inv.setItem(0, make(plugin, BACK, Material.ARROW, "Back", NamedTextColor.YELLOW,
                "Right-click: back 10 seconds", "Left-click: back 5 seconds"));
        inv.setItem(1, playPause(plugin, paused));
        inv.setItem(2, make(plugin, FWD, Material.SPECTRAL_ARROW, "Forward", NamedTextColor.YELLOW,
                "Right-click: forward 10 seconds", "Left-click: forward 5 seconds"));
        inv.setItem(3, speedItem(plugin, speed));
        inv.setItem(4, playersItem(plugin, p));
        inv.setItem(5, make(plugin, INV, Material.CHEST, "Inventories", NamedTextColor.LIGHT_PURPLE,
                "Right-click: look inside a player's inventory,",
                "extra inventory and ender chest.", "Uses the player you follow, or asks which."));
        inv.setItem(6, make(plugin, INSPECT, Material.SPYGLASS, "Inspect", NamedTextColor.GREEN,
                "Look at a player, then:", "Left-click: inventory", "Right-click: ender chest",
                "Sneak + right-click: extra inventory"));
        inv.setItem(7, make(plugin, NOCLIP, Material.ENDER_EYE, "Noclip mode", NamedTextColor.LIGHT_PURPLE,
                "Spectator mode: fly through blocks.", "Spectator hides the hotbar. Double-tap sneak", "(or /replay hotbar) to get it back."));
        inv.setItem(8, make(plugin, EXIT, Material.BARRIER, "Stop replay", NamedTextColor.DARK_RED,
                "Click to stop watching."));
        inv.setHeldItemSlot(4);
    }

    /** Updates the two buttons that change (play/pause and speed). */
    public static void update(PvPReplayPlugin plugin, Player p, boolean paused, double speed) {
        p.getInventory().setItem(1, playPause(plugin, paused));
        p.getInventory().setItem(3, speedItem(plugin, speed));
    }

    /** The control id of an item, or null if it is not one of ours. */
    public static String idOf(PvPReplayPlugin plugin, ItemStack item) {
        if (item == null || !item.hasItemMeta()) return null;
        return item.getItemMeta().getPersistentDataContainer().get(plugin.ctlKey(), PersistentDataType.STRING);
    }

    /** Takes our control items out of the inventory (used when switching to spectator). */
    public static void removeItems(PvPReplayPlugin plugin, Player p) {
        var inv = p.getInventory();
        for (int i = 0; i < inv.getSize(); i++) {
            if (idOf(plugin, inv.getItem(i)) != null) inv.setItem(i, null);
        }
    }

    private static boolean hasControlItems(PvPReplayPlugin plugin, Player p) {
        for (ItemStack it : p.getInventory().getContents()) {
            if (idOf(plugin, it) != null) return true;
        }
        return false;
    }

    // ----------------------------------------------------- inventory backup

    private static Path file(PvPReplayPlugin plugin, UUID id) {
        return plugin.getDataFolder().toPath().resolve("viewer-backup").resolve(id + ".dat");
    }

    public static boolean hasBackup(PvPReplayPlugin plugin, UUID id) {
        return Files.exists(file(plugin, id));
    }

    /**
     * Writes the viewer's real inventory to disk, then clears it. False if it could not be saved safely (the
     * inventory is then left untouched).
     */
    public static boolean backup(PvPReplayPlugin plugin, Player p) {
        Path f = file(plugin, p.getUniqueId());
        try {
            if (Files.exists(f) && hasControlItems(plugin, p)) return true; // already backed up, still wearing the hotbar
            Files.createDirectories(f.getParent());
            byte[] data = ItemStack.serializeItemsAsBytes(p.getInventory().getContents());
            Files.write(f, data);
            if (Files.size(f) != data.length) return false;
            return true;
        } catch (IOException | RuntimeException e) {
            plugin.getLogger().log(Level.WARNING, "Could not back up the inventory of " + p.getName(), e);
            return false;
        }
    }

    /** Gives the viewer their real inventory back and deletes the backup. */
    public static void restore(PvPReplayPlugin plugin, Player p) {
        Path f = file(plugin, p.getUniqueId());
        if (!Files.exists(f)) {
            removeItems(plugin, p);
            return;
        }
        try {
            ItemStack[] items = ItemStack.deserializeItemsFromBytes(Files.readAllBytes(f));
            p.getInventory().clear();
            p.getInventory().setContents(items);
            Files.delete(f);
        } catch (IOException | RuntimeException e) {
            plugin.getLogger().log(Level.SEVERE, "Could not restore the inventory of " + p.getName()
                    + ". The backup is kept at " + f, e);
            p.sendMessage(Component.text("Your inventory could not be restored automatically. Ask an admin: it is saved in "
                    + f.getFileName() + ".", NamedTextColor.RED));
        }
    }

    /** After a crash or quit: undo hotbar mode (flags and inventory) for someone who is not in a session. */
    public static void recover(PvPReplayPlugin plugin, Player p) {
        p.setInvulnerable(false);
        p.setInvisible(false);
        p.setCollidable(true);
        p.setCanPickupItems(true);
        p.setAffectsSpawning(true);
        p.setSleepingIgnored(false);
        GameMode gm = p.getGameMode();
        if (gm != GameMode.CREATIVE && gm != GameMode.SPECTATOR) {
            p.setFlying(false);
            p.setAllowFlight(false);
        }
        restore(plugin, p);
    }
}
