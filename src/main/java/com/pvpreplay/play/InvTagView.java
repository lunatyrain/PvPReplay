package com.pvpreplay.play;

import com.pvpreplay.PvPReplayPlugin;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.util.Transformation;
import org.joml.AxisAngle4f;
import org.joml.Vector3f;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * A player's inventory floating above their head, drawn like the real inventory screen: a dark panel with the
 * actual item icons in their slots (armor and off hand on top, then the 27 storage slots, then the hotbar with the
 * held slot highlighted) and the stack-size number in the corner of stacks bigger than one. No text labels.
 * Only the one viewer can see it.
 */
final class InvTagView {
    private static final float P = 0.16f;      // distance between slot centres
    private static final float MARGIN = 0.04f; // panel border
    private static final float GAP = 0.05f;    // gap between storage rows and hotbar, like the real screen
    private static final float W = 9 * P + 2 * MARGIN;
    private static final float H = 5 * P + GAP + 2 * MARGIN;

    private static final float PANEL_Z = 0f;
    private static final float HILITE_Z = 0.01f;
    private static final float ITEM_Z = 0.09f;
    private static final float COUNT_Z = 0.2f;
    private static final float COUNT_SCALE = 0.3f;

    private final PvPReplayPlugin plugin;
    private final Player viewer;
    private Location pos;

    private Entity panel;
    private Entity highlight;
    private int shownHeld = -1;
    private final Entity[] items = new Entity[41];
    private final Entity[] counts = new Entity[41];
    private final ItemStack[] shown = new ItemStack[41];

    InvTagView(PvPReplayPlugin plugin, Player viewer, Location at) {
        this.plugin = plugin;
        this.viewer = viewer;
        this.pos = at.clone();
        panel = NpcFactory.spawnTagPanel(plugin, pos, Material.BLACK_STAINED_GLASS,
                tf(-W / 2f, 0f, PANEL_Z - 0.01f, W, H, 0.01f), viewer);
    }

    boolean isValid() {
        return panel != null && panel.isValid();
    }

    /** Moves the whole thing to a new spot; does nothing if it is already there. */
    void moveTo(Location at) {
        if (pos.getWorld() == at.getWorld() && pos.distanceSquared(at) < 1e-6) return;
        pos = at.clone();
        teleport(panel);
        teleport(highlight);
        for (int i = 0; i < 41; i++) {
            teleport(items[i]);
            teleport(counts[i]);
        }
    }

    private void teleport(Entity e) {
        if (e != null && e.isValid()) e.teleport(pos);
    }

    /** Brings the displayed icons up to date with the given inventory (null = empty) and held hotbar slot. */
    void update(ItemStack[] inv, int held) {
        for (int slot = 0; slot < 41; slot++) {
            ItemStack want = inv != null && inv[slot] != null && !inv[slot].getType().isAir() ? inv[slot] : null;
            if (Objects.equals(want, shown[slot]) && (want == null || items[slot] != null && items[slot].isValid())) continue;
            drop(slot);
            shown[slot] = want == null ? null : want.clone();
            if (want == null) continue;
            float x = slotX(slot);
            float y = slotY(slot);
            if (x != x) continue; // not a visible slot
            float s = P * 0.9f;
            items[slot] = NpcFactory.spawnTagItem(plugin, pos, want, tf(x, y, ITEM_Z, s, s, s), viewer);
            if (want.getAmount() > 1) {
                String txt = String.valueOf(want.getAmount());
                // a digit is 6 font pixels wide (5 + 1 spacing); one font pixel is 1/40 block before scaling
                float width = (6 * txt.length() - 1) * 0.025f * COUNT_SCALE;
                float cx = x + P / 2f - 0.01f - width / 2f;
                float cy = y - P / 2f + 0.035f;
                counts[slot] = NpcFactory.spawnTagCount(plugin, pos, want.getAmount(),
                        tf(cx, cy, COUNT_Z, COUNT_SCALE, COUNT_SCALE, COUNT_SCALE), viewer);
            }
        }
        setHeld(held);
    }

    private void setHeld(int held) {
        if (held < 0 || held > 8) {
            if (highlight != null) {
                highlight.remove();
                highlight = null;
            }
            shownHeld = -1;
            return;
        }
        if (held == shownHeld && highlight != null && highlight.isValid()) return;
        shownHeld = held;
        Transformation t = tf(slotX(held) - P / 2f, slotY(held) - P / 2f, HILITE_Z, P, P, 0.01f);
        if (highlight != null && highlight.isValid()) {
            ((org.bukkit.entity.Display) highlight).setTransformation(t);
        } else {
            highlight = NpcFactory.spawnTagPanel(plugin, pos, Material.WHITE_STAINED_GLASS, t, viewer);
        }
    }

    private void drop(int slot) {
        if (items[slot] != null) items[slot].remove();
        if (counts[slot] != null) counts[slot].remove();
        items[slot] = null;
        counts[slot] = null;
        shown[slot] = null;
    }

    void remove() {
        if (panel != null) panel.remove();
        if (highlight != null) highlight.remove();
        for (int i = 0; i < 41; i++) drop(i);
        panel = null;
        highlight = null;
    }

    // ------------------------------------------------------------ layout

    /** Row counted from the bottom: 0 = hotbar, 1..3 = storage (bottom to top), 4 = armor row. -1 = not shown. */
    private static int rowFromBottom(int slot) {
        if (slot >= 0 && slot <= 8) return 0;
        if (slot >= 9 && slot <= 35) return 3 - (slot - 9) / 9;
        if (slot >= 36 && slot <= 40) return 4;
        return -1;
    }

    private static int col(int slot) {
        if (slot >= 0 && slot <= 8) return slot;
        if (slot >= 9 && slot <= 35) return (slot - 9) % 9;
        return switch (slot) {
            case 39 -> 0; // helmet
            case 38 -> 1; // chestplate
            case 37 -> 2; // leggings
            case 36 -> 3; // boots
            case 40 -> 5; // off hand
            default -> -1;
        };
    }

    private static float slotX(int slot) {
        int c = col(slot);
        return c < 0 ? Float.NaN : (c - 4) * P;
    }

    private static float slotY(int slot) {
        int r = rowFromBottom(slot);
        if (r < 0) return Float.NaN;
        return MARGIN + P * (r + 0.5f) + (r >= 1 ? GAP : 0f);
    }

    private static Transformation tf(float x, float y, float z, float sx, float sy, float sz) {
        return new Transformation(new Vector3f(x, y, z), new AxisAngle4f(), new Vector3f(sx, sy, sz), new AxisAngle4f());
    }
}
