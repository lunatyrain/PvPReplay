package com.pvpreplay.play;

import com.pvpreplay.PvPReplayPlugin;
import io.papermc.paper.event.player.PrePlayerAttackEntityEvent;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.Event;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.entity.EntityPickupItemEvent;
import org.bukkit.event.entity.FoodLevelChangeEvent;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.player.PlayerDropItemEvent;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerSwapHandItemsEvent;
import org.bukkit.event.player.PlayerToggleSneakEvent;
import org.bukkit.inventory.EquipmentSlot;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Clicks on the control hotbar, and everything that must NOT happen to a viewer in hotbar mode: they cannot touch
 * the world (blocks, doors, chests, mobs), drop or move their items, pick things up or get hungry.
 */
public final class HotbarListener implements Listener {
    private final PvPReplayPlugin plugin;
    private final Map<UUID, Integer> lastClickTick = new HashMap<>();
    private final Map<UUID, Integer> lastSneakTick = new HashMap<>();

    public HotbarListener(PvPReplayPlugin plugin) {
        this.plugin = plugin;
    }

    private PlaybackSession hotbar(Player p) {
        PlaybackSession s = plugin.playback().get(p);
        return s != null && s.controlsMode() ? s : null;
    }

    private void control(Player p, PlaybackSession s, org.bukkit.inventory.ItemStack item, boolean right) {
        String id = Hotbar.idOf(plugin, item);
        if (id == null) return;
        int now = Bukkit.getCurrentTick();
        Integer last = lastClickTick.get(p.getUniqueId());
        if (last != null && last == now) return; // the same click reported twice (e.g. interact + interact-at-entity)
        lastClickTick.put(p.getUniqueId(), now);
        s.onControl(id, right);
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onInteract(PlayerInteractEvent e) {
        PlaybackSession s = hotbar(e.getPlayer());
        if (s == null) return;
        e.setUseInteractedBlock(Event.Result.DENY);
        e.setUseItemInHand(Event.Result.DENY);
        e.setCancelled(true);
        if (e.getHand() != EquipmentSlot.HAND || e.getAction() == Action.PHYSICAL) return;
        control(e.getPlayer(), s, e.getItem(), e.getAction().isRightClick());
    }

    /** Right-click on a (replay) player: the click still counts as a button press. */
    @EventHandler(priority = EventPriority.HIGHEST)
    public void onInteractEntity(PlayerInteractEntityEvent e) {
        PlaybackSession s = hotbar(e.getPlayer());
        if (s == null) return;
        e.setCancelled(true);
        if (e.getHand() != EquipmentSlot.HAND) return;
        control(e.getPlayer(), s, e.getPlayer().getInventory().getItemInMainHand(), true);
    }

    /** Left-click on a (replay) player: also a button press, never an attack. */
    @EventHandler(priority = EventPriority.HIGHEST)
    public void onAttack(PrePlayerAttackEntityEvent e) {
        PlaybackSession s = hotbar(e.getPlayer());
        if (s == null) return;
        e.setCancelled(true);
        control(e.getPlayer(), s, e.getPlayer().getInventory().getItemInMainHand(), false);
    }

    /** In noclip (spectator) mode there is no hotbar, so double-tapping sneak brings it back. */
    @EventHandler(priority = EventPriority.MONITOR)
    public void onSneak(PlayerToggleSneakEvent e) {
        Player p = e.getPlayer();
        PlaybackSession s = plugin.playback().get(p);
        if (s == null || s.controlsMode() || !e.isSneaking() || !plugin.settings().hotbarControls) return;
        int now = Bukkit.getCurrentTick();
        Integer last = lastSneakTick.put(p.getUniqueId(), now);
        if (last != null && now - last <= 12) {
            lastSneakTick.remove(p.getUniqueId());
            Bukkit.getScheduler().runTask(plugin, s::enterControlsMode);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onDrop(PlayerDropItemEvent e) {
        if (hotbar(e.getPlayer()) != null) e.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onSwap(PlayerSwapHandItemsEvent e) {
        if (hotbar(e.getPlayer()) != null) e.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onClick(InventoryClickEvent e) {
        if (!(e.getWhoClicked() instanceof Player p) || hotbar(p) == null) return;
        var holder = e.getView().getTopInventory().getHolder();
        if (holder instanceof ReplayInvHolder || holder instanceof ReplayPlayersHolder) return; // handled (and cancelled) there
        e.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onDrag(InventoryDragEvent e) {
        if (e.getWhoClicked() instanceof Player p && hotbar(p) != null) e.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onPickup(EntityPickupItemEvent e) {
        if (e.getEntity() instanceof Player p && hotbar(p) != null) e.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onHunger(FoodLevelChangeEvent e) {
        if (e.getEntity() instanceof Player p && hotbar(p) != null) e.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onBreak(BlockBreakEvent e) {
        if (hotbar(e.getPlayer()) != null) e.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onPlace(BlockPlaceEvent e) {
        if (hotbar(e.getPlayer()) != null) e.setCancelled(true);
    }
}
