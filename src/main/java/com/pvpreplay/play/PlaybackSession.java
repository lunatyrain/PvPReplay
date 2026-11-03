package com.pvpreplay.play;

import com.pvpreplay.Compat;
import com.pvpreplay.Msg;
import com.pvpreplay.PvPReplayPlugin;
import com.pvpreplay.Settings;
import com.pvpreplay.data.*;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Bukkit;
import org.bukkit.Color;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.SoundGroup;
import org.bukkit.World;
import org.bukkit.block.data.BlockData;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.entity.Pose;
import org.bukkit.inventory.EntityEquipment;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.scheduler.BukkitTask;

import java.util.*;

/**
 * One person watching one replay. The viewer is put in spectator mode (free camera) and sees the recorded
 * players as mannequins that only they can see. Everything is undone on stop.
 */
public final class PlaybackSession {
    private final PvPReplayPlugin plugin;
    private final Settings st;
    private final Player viewer;
    private final Replay replay;
    private final List<Frame> frames;

    private int cur = -1;
    private double cursor = 0;
    private double speed;
    private boolean paused = false;
    private int tickCount = 0;
    private boolean ended = false;

    private final Map<Integer, Entity> npcs = new HashMap<>();
    private final Map<Integer, PState> lastStates = new HashMap<>();
    private final Map<Integer, ItemStack[]> equip = new HashMap<>();
    private final Set<Integer> equipDirty = new HashSet<>();
    private final Map<Integer, ArrayDeque<Integer>> swingHist = new HashMap<>();
    private final Map<Integer, String> projType = new HashMap<>();
    private final Map<Integer, Entity> projEntities = new HashMap<>();
    private final Map<Integer, Location> projPos = new HashMap<>();
    private final Map<String, Location> changedBlocks = new HashMap<>();
    private final Map<String, BlockData> shownBlocks = new HashMap<>();
    private final List<Entity> flashes = new ArrayList<>();
    private final Map<Integer, Entity> shields = new HashMap<>();
    private Boolean poseSupported = null; // does this server let us raise an item (shield, bow, food) on a mannequin?
    private final List<Integer> fights = new ArrayList<>();

    // full inventories / vitals as of the current frame, plus visual state
    private final Map<Integer, ItemStack[]> inv = new HashMap<>();
    private final Map<Integer, Integer> held = new HashMap<>();
    private final Map<Integer, Ev> vitals = new HashMap<>();
    private final Map<Integer, Integer> hpShown = new HashMap<>();
    private final Map<Integer, Integer> usingNow = new HashMap<>(); // pid -> 1 main hand, 2 off hand
    private final Set<Integer> sprintNow = new HashSet<>();
    private int guiPid = -1;
    private Inventory gui;
    private boolean guiDirty = false;

    private BukkitTask task;
    private Location origLoc;
    private GameMode origMode;
    private int followPid = -1;
    private Entity attached;
    private String lastHit = "";
    private int lastHitFrame = -1000;

    public PlaybackSession(PvPReplayPlugin plugin, Player viewer, Replay replay) {
        this.plugin = plugin;
        this.st = plugin.settings();
        this.viewer = viewer;
        this.replay = replay;
        this.frames = replay.frames();
        this.speed = st.defaultSpeed;

        int lastHitAt = -10000;
        for (int i = 0; i < frames.size(); i++) {
            for (Ev e : frames.get(i).events()) {
                if (e.type() == Ev.HIT) {
                    if (i - lastHitAt > 60) fights.add(i);
                    lastHitAt = i;
                    break;
                }
            }
        }
    }

    public Replay replay() {
        return replay;
    }

    public Player viewer() {
        return viewer;
    }

    // ------------------------------------------------------------- lifecycle

    public void start() {
        origLoc = viewer.getLocation();
        origMode = viewer.getGameMode();
        viewer.getPersistentDataContainer().set(plugin.viewKey(), PersistentDataType.STRING, origMode.name());
        viewer.setGameMode(GameMode.SPECTATOR);

        advanceTo(0, true);
        placeViewerAtStart();
        task = Bukkit.getScheduler().runTaskTimer(plugin, this::tick, 1L, 1L);

        ReplayMeta m = replay.meta();
        viewer.sendMessage(Msg.ok("Watching " + m.id() + " - " + m.reason() + " (" + Fmt.time(frames.size()) + ")"));
        viewer.sendMessage(Msg.info("You are a free camera. Use /replay follow <player> to see through someone's eyes (sneak to leave)."));
        sendControls();
        if (!fights.isEmpty()) {
            viewer.sendMessage(Msg.info("Detected " + fights.size() + " fight" + (fights.size() == 1 ? "" : "s")
                    + ". Use the Prev/Next fight buttons to jump between them."));
        }
    }

    public void stop(boolean silent) {
        if (task != null) task.cancel();
        task = null;
        if (viewer.isOnline()) {
            if (gui != null && viewer.getOpenInventory().getTopInventory() == gui) viewer.closeInventory();
            viewer.setSpectatorTarget(null);
        }
        gui = null;
        despawnAll();
        revertBlocks();
        if (viewer.isOnline()) {
            if (origMode != null) viewer.setGameMode(origMode);
            if (origLoc != null && origLoc.getWorld() != null) viewer.teleport(origLoc);
            viewer.getPersistentDataContainer().remove(plugin.viewKey());
            if (!silent) viewer.sendMessage(Msg.info("Replay stopped."));
        }
    }

    private void placeViewerAtStart() {
        PState s = null;
        if (!replay.meta().focus().isEmpty()) {
            int pid = replay.indexOf(replay.meta().focus().get(0).uuid());
            if (pid >= 0) s = lastStates.get(pid);
        }
        if (s == null && !lastStates.isEmpty()) s = lastStates.values().iterator().next();
        if (s == null) return;
        World w = world(s.world());
        if (w == null) return;
        viewer.teleport(new Location(w, s.x(), s.y() + 2.0, s.z(), s.yaw(), 25f));
    }

    // ------------------------------------------------------------- controls

    public boolean isPaused() {
        return paused;
    }

    public void pause() {
        paused = true;
    }

    public void resume() {
        if (ended) {
            seekFrame(0);
            ended = false;
        }
        paused = false;
    }

    public void toggle() {
        if (paused) resume();
        else pause();
    }

    public void setSpeed(double s) {
        speed = Math.max(0.1, Math.min(8.0, s));
    }

    public double speed() {
        return speed;
    }

    public int frameCount() {
        return frames.size();
    }

    public int currentFrame() {
        return Math.max(0, cur);
    }

    public void seekFrame(int frame) {
        int f = Math.max(0, Math.min(frames.size() - 1, frame));
        if (f <= cur) resetSim();
        advanceTo(f, true);
        cursor = f;
        ended = false;
        lastHit = "";
    }

    public void seekSeconds(double seconds) {
        seekFrame((int) Math.round(seconds * 20));
    }

    public void seekRelativeSeconds(double seconds) {
        seekFrame(Math.max(0, cur) + (int) Math.round(seconds * 20));
    }

    public void step(int frames) {
        pause();
        seekFrame(Math.max(0, cur) + frames);
    }

    /** Jump to the start (2 s before) of the next/previous fight. dir = +1 / -1. */
    public String jumpFight(int dir) {
        if (fights.isEmpty()) return "No fights (hits between players) in this replay.";
        int pick = -1;
        if (dir > 0) {
            for (int i = 0; i < fights.size(); i++) {
                if (Math.max(0, fights.get(i) - 40) > cur + 5) {
                    pick = i;
                    break;
                }
            }
        } else {
            for (int i = fights.size() - 1; i >= 0; i--) {
                if (Math.max(0, fights.get(i) - 40) < cur - 5) {
                    pick = i;
                    break;
                }
            }
        }
        if (pick < 0) return dir > 0 ? "No later fight." : "No earlier fight.";
        seekFrame(Math.max(0, fights.get(pick) - 40));
        return "Fight " + (pick + 1) + "/" + fights.size() + " at " + Fmt.time(fights.get(pick));
    }

    public boolean follow(int pid) {
        if (pid < 0 || pid >= replay.players().size()) return false;
        followPid = pid;
        attached = null;
        return true;
    }

    public void freeCam() {
        followPid = -1;
        attached = null;
        viewer.setSpectatorTarget(null);
    }

    public void sendControls() {
        Component bar = Component.empty()
                .append(Msg.button("[<<10s]", NamedTextColor.AQUA, "/replay seek -10", "Back 10 seconds")).append(sp())
                .append(Msg.button("[<<5s]", NamedTextColor.AQUA, "/replay seek -5", "Back 5 seconds")).append(sp())
                .append(Msg.button("[Pause/Play]", NamedTextColor.YELLOW, "/replay toggle", "Pause or resume")).append(sp())
                .append(Msg.button("[5s>>]", NamedTextColor.AQUA, "/replay seek +5", "Forward 5 seconds")).append(sp())
                .append(Msg.button("[10s>>]", NamedTextColor.AQUA, "/replay seek +10", "Forward 10 seconds"));
        Component bar2 = Component.empty()
                .append(Msg.button("[0.25x]", NamedTextColor.GOLD, "/replay speed 0.25", "Slow motion")).append(sp())
                .append(Msg.button("[0.5x]", NamedTextColor.GOLD, "/replay speed 0.5", "Half speed")).append(sp())
                .append(Msg.button("[1x]", NamedTextColor.GOLD, "/replay speed 1", "Normal speed")).append(sp())
                .append(Msg.button("[2x]", NamedTextColor.GOLD, "/replay speed 2", "Double speed")).append(sp())
                .append(Msg.button("[Prev fight]", NamedTextColor.GREEN, "/replay fight prev", "Jump to the previous fight")).append(sp())
                .append(Msg.button("[Next fight]", NamedTextColor.GREEN, "/replay fight next", "Jump to the next fight")).append(sp())
                .append(Msg.button("[Stop]", NamedTextColor.RED, "/replay stop", "Stop watching"));
        Component bar3 = Component.empty()
                .append(Msg.button("[Inventory]", NamedTextColor.LIGHT_PURPLE, "/replay inv", "See the inventory of the player you follow"))
                .append(sp())
                .append(Msg.button("[Players]", NamedTextColor.LIGHT_PURPLE, "/replay players", "List everyone in this replay"))
                .append(sp())
                .append(Msg.button("[Free cam]", NamedTextColor.GRAY, "/replay follow", "Leave first person"));
        viewer.sendMessage(bar);
        viewer.sendMessage(bar2);
        viewer.sendMessage(bar3);
    }

    private static Component sp() {
        return Component.text(" ");
    }

    // ------------------------------------------------------------------ tick

    private void tick() {
        if (!viewer.isOnline()) {
            plugin.playback().remove(viewer.getUniqueId());
            stop(true);
            return;
        }
        if (!paused) {
            cursor += speed;
            int target = Math.min((int) cursor, frames.size() - 1);
            if (target > cur) advanceTo(target, false);
            if (cursor >= frames.size() - 1) {
                paused = true;
                ended = true;
                cursor = frames.size() - 1;
                viewer.sendMessage(Msg.info("End of replay. Use /replay resume to watch again, or /replay stop to leave."));
            }
        }
        maintainUsing();
        handleFollow();
        updateGui();
        tickCount++;
        if (st.actionBar && (tickCount & 1) == 0) hud();
    }

    /**
     * Keeps eating / drinking / bow-draw / shield-raise poses in sync with the recording. Checked every tick because
     * the item may only appear in the hand a moment after the mannequin spawns, and the server can clear the pose.
     */
    private void maintainUsing() {
        for (Map.Entry<Integer, Entity> en : npcs.entrySet()) {
            if (!(en.getValue() instanceof LivingEntity le) || !le.isValid()) continue;
            if (poseSupported == null) {
                poseSupported = Compat.has(le, "startUsingItem", 1);
                if (!poseSupported) {
                    plugin.getLogger().warning("This server cannot raise items on mannequins; showing a floating shield instead.");
                }
            }
            if (!poseSupported) return;
            int want = usingNow.getOrDefault(en.getKey(), 0);
            boolean raised = le.isHandRaised();
            if (want == 0) {
                if (raised) Compat.call(le, "clearActiveItem");
                continue;
            }
            EquipmentSlot slot = want == 2 ? EquipmentSlot.OFF_HAND : EquipmentSlot.HAND;
            ItemStack[] gear = equip.get(en.getKey());
            if (gear == null || gear[want == 2 ? 1 : 0] == null) continue; // nothing in that hand yet
            if (raised && !slot.equals(Compat.call(le, "getHandRaised"))) {
                Compat.call(le, "clearActiveItem");
                raised = false;
            }
            if (!raised) Compat.call(le, "startUsingItem", slot);
        }
    }

    private void handleFollow() {
        if (followPid < 0) return;
        Entity e = npcs.get(followPid);
        if (attached != null && !attached.isValid()) attached = null;
        if (attached != null) {
            if (viewer.getSpectatorTarget() != attached) {
                // the viewer pressed sneak: they want their free camera back
                followPid = -1;
                attached = null;
            }
            return;
        }
        if (e != null && e.isValid()) {
            viewer.setSpectatorTarget(e);
            attached = e;
        }
    }

    private void hud() {
        Component c = Component.text(paused ? "[PAUSED] " : "[PLAY] ", paused ? NamedTextColor.YELLOW : NamedTextColor.GREEN)
                .append(Component.text(Fmt.time(Math.max(0, cur)) + " / " + Fmt.time(frames.size()) + "  x"
                        + Fmt.num(speed).replaceAll("0+$", "").replaceAll("\\.$", ""), NamedTextColor.WHITE));
        PState s = followPid >= 0 ? lastStates.get(followPid) : null;
        if (s != null) {
            c = c.append(Component.text("  |  " + replay.players().get(followPid).name()
                    + "  CPS " + cps(followPid) + "  Ping " + s.ping() + "ms  HP " + Fmt.num1(s.health()), NamedTextColor.AQUA));
        }
        if (!lastHit.isEmpty() && Math.max(0, cur) - lastHitFrame < 100) {
            c = c.append(Component.text("  |  " + lastHit, NamedTextColor.GRAY));
        }
        viewer.sendActionBar(c);
    }

    private int cps(int pid) {
        ArrayDeque<Integer> q = swingHist.get(pid);
        if (q == null) return 0;
        int now = Math.max(0, cur);
        while (!q.isEmpty() && now - q.peekFirst() >= 20) q.pollFirst();
        return q.size();
    }

    // ------------------------------------------------------- players / inventory

    public void sendPlayers() {
        viewer.sendMessage(Msg.PREFIX.append(Component.text("Players in this replay", NamedTextColor.WHITE)));
        for (int pid = 0; pid < replay.players().size(); pid++) {
            String name = replay.players().get(pid).name();
            PState s = lastStates.get(pid);
            Component row = Component.text(name, s != null ? NamedTextColor.AQUA : NamedTextColor.DARK_GRAY)
                    .append(Component.text(s != null ? "  HP " + Fmt.num1(s.health()) + "  " : "  (not here right now)  ", NamedTextColor.GRAY))
                    .append(Msg.button("[Follow]", NamedTextColor.GREEN, "/replay follow " + name, "See through " + name + "'s eyes"))
                    .append(Component.text(" "))
                    .append(Msg.button("[Inventory]", NamedTextColor.LIGHT_PURPLE, "/replay inv " + name, "Open " + name + "'s inventory"));
            viewer.sendMessage(row);
        }
    }

    /** Opens a look-only window with the inventory of a player as of the current frame. Returns an error or null. */
    public String openInventory(String name) {
        int pid = name == null ? followPid : replay.indexOfName(name);
        if (pid < 0) {
            return name == null ? "Follow a player first, or use /replay inv <player>." : "'" + name + "' is not in this replay.";
        }
        guiPid = pid;
        ReplayInvHolder holder = new ReplayInvHolder();
        gui = Bukkit.createInventory(holder, 54, Component.text(replay.players().get(pid).name() + "'s inventory"));
        holder.set(gui);
        fillGui();
        viewer.openInventory(gui);
        return null;
    }

    private void updateGui() {
        if (gui == null || guiPid < 0) return;
        if (viewer.getOpenInventory().getTopInventory() != gui) {
            gui = null;
            guiPid = -1;
            return;
        }
        if (guiDirty || (tickCount % 10) == 0) fillGui();
    }

    private void fillGui() {
        guiDirty = false;
        ItemStack[] a = inv.getOrDefault(guiPid, new ItemStack[41]);
        String[] armorNames = {"Helmet", "Chestplate", "Leggings", "Boots", "Off hand"};
        int[] armorSlots = {39, 38, 37, 36, 40};
        for (int i = 0; i < 5; i++) {
            ItemStack it = a[armorSlots[i]];
            gui.setItem(i, it != null ? it : named(Material.GRAY_STAINED_GLASS_PANE, armorNames[i] + " (empty)", List.of()));
        }
        gui.setItem(5, named(Material.GRAY_STAINED_GLASS_PANE, " ", List.of()));
        gui.setItem(6, vitalsItem());
        gui.setItem(7, effectsItem());
        gui.setItem(8, positionItem());
        for (int i = 9; i < 36; i++) gui.setItem(i, a[i]);
        int h = held.getOrDefault(guiPid, 0);
        for (int i = 0; i < 9; i++) {
            gui.setItem(36 + i, i == h
                    ? named(Material.LIME_STAINED_GLASS_PANE, "Selected hotbar slot " + (h + 1), List.of())
                    : named(Material.GRAY_STAINED_GLASS_PANE, " ", List.of()));
            gui.setItem(45 + i, a[i]);
        }
    }

    private ItemStack vitalsItem() {
        PState s = lastStates.get(guiPid);
        Ev v = vitals.get(guiPid);
        List<String> lines = new ArrayList<>();
        lines.add("Health: " + (s == null ? "?" : Fmt.num1(s.health())));
        if (v != null) {
            lines.add("Food: " + v.b() + " / 20");
            lines.add("Saturation: " + Fmt.num1(v.f()));
            lines.add("XP level: " + Math.round(v.x()));
            lines.add("Absorption: " + Fmt.num1(v.y()));
        }
        lines.add("Held slot: " + (held.getOrDefault(guiPid, 0) + 1));
        return named(Material.GOLDEN_APPLE, "Vitals", lines);
    }

    private ItemStack effectsItem() {
        Ev v = vitals.get(guiPid);
        List<String> lines = new ArrayList<>();
        if (v != null && !v.s().isEmpty()) {
            for (String part : v.s().split(";")) {
                String[] f = part.split(":");
                if (f.length < 3) continue;
                try {
                    int amp = Integer.parseInt(f[1]) + 1;
                    int secs = Integer.parseInt(f[2]) / 20;
                    lines.add(f[0].replace('_', ' ') + " " + amp + "  (" + (secs / 60) + ":" + (secs % 60 < 10 ? "0" : "") + (secs % 60) + ")");
                } catch (NumberFormatException ignored) {
                    // skip malformed entry
                }
            }
        }
        if (lines.isEmpty()) lines.add("No active effects");
        return named(Material.POTION, "Potion effects", lines);
    }

    private ItemStack positionItem() {
        PState s = lastStates.get(guiPid);
        List<String> lines = new ArrayList<>();
        if (s == null) {
            lines.add("Not in the world at this moment");
        } else {
            List<String> ws = replay.meta().worlds();
            lines.add("World: " + (s.world() >= 0 && s.world() < ws.size() ? ws.get(s.world()) : "?"));
            lines.add("XYZ: " + Math.round(s.x()) + " " + Math.round(s.y()) + " " + Math.round(s.z()));
            lines.add("Ping: " + s.ping() + " ms");
            StringBuilder st2 = new StringBuilder();
            if (s.has(PState.SNEAK)) st2.append("sneaking ");
            if (s.has(PState.SPRINT)) st2.append("sprinting ");
            if (s.has(PState.SWIM)) st2.append("swimming ");
            if (s.has(PState.GLIDE)) st2.append("gliding ");
            if (s.has(PState.USING)) st2.append("using item ");
            if (s.has(PState.BLOCK)) st2.append("blocking ");
            lines.add("State: " + (st2.length() == 0 ? "standing" : st2.toString().trim()));
        }
        return named(Material.COMPASS, "Position", lines);
    }

    private static ItemStack named(Material m, String name, List<String> lore) {
        ItemStack it = new ItemStack(m);
        ItemMeta meta = it.getItemMeta();
        if (meta != null) {
            meta.displayName(Component.text(name, NamedTextColor.WHITE).decoration(TextDecoration.ITALIC, false));
            List<Component> l = new ArrayList<>();
            for (String line : lore) l.add(Component.text(line, NamedTextColor.GRAY).decoration(TextDecoration.ITALIC, false));
            if (!l.isEmpty()) meta.lore(l);
            it.setItemMeta(meta);
        }
        return it;
    }

    // ------------------------------------------------------------ simulation

    private World world(int idx) {
        List<String> ws = replay.meta().worlds();
        if (idx < 0 || idx >= ws.size()) return null;
        return Bukkit.getWorld(ws.get(idx));
    }

    private String nameOf(int pid) {
        return pid >= 0 && pid < replay.players().size() ? replay.players().get(pid).name() : "?";
    }

    /**
     * Plays frames cur+1..target. Events of skipped frames only update state (equipment, blocks, projectiles,
     * click history); positions are only taken from the target frame.
     *
     * @param quiet no chat feed and no animations (used when seeking)
     */
    private void advanceTo(int target, boolean quiet) {
        for (int f = cur + 1; f < target; f++) {
            for (Ev ev : frames.get(f).events()) handle(ev, f, quiet, false);
        }
        Frame tf = frames.get(target);
        // events first for equipment/blocks/projectiles so new NPCs spawn fully dressed
        for (Ev ev : tf.events()) {
            if (ev.type() != Ev.SWING && ev.type() != Ev.HIT && ev.type() != Ev.DEATH) handle(ev, target, quiet, true);
        }
        reconcileNpcs(tf);
        reconcileProjectiles();
        flushEquipment();
        for (Ev ev : tf.events()) {
            if (ev.type() == Ev.SWING || ev.type() == Ev.HIT || ev.type() == Ev.DEATH) handle(ev, target, quiet, true);
        }
        cur = target;
    }

    private void handle(Ev ev, int f, boolean quiet, boolean isTarget) {
        boolean animate = isTarget && !quiet;
        switch (ev.type()) {
            case Ev.EQUIP -> onEquip(ev);
            case Ev.SWING -> {
                swingHist.computeIfAbsent(ev.a(), k -> new ArrayDeque<>()).addLast(f);
                if (animate) {
                    Entity e = npcs.get(ev.a());
                    if (e instanceof LivingEntity le) {
                        if (ev.b() == 1) le.swingOffHand();
                        else le.swingMainHand();
                    }
                }
            }
            case Ev.HIT -> {
                if (!quiet) hitFeed(ev, f);
                if (animate) {
                    boolean blocked = ev.s().contains("[BLOCKED");
                    if (!blocked) Compat.call(npcs.get(ev.b()), "playHurtAnimation", 0f);
                    PState vs = lastStates.get(ev.b());
                    World vw = vs == null ? null : world(vs.world());
                    if (vw != null) {
                        Location at = new Location(vw, vs.x(), vs.y() + 1.2, vs.z());
                        viewer.spawnParticle(Particle.CRIT, at, blocked ? 12 : 6, 0.25, 0.3, 0.25);
                        if (blocked) viewer.playSound(at, Sound.ITEM_SHIELD_BLOCK, 1.0f, 1.0f);
                    }
                }
            }
            case Ev.INV -> {
                if (ev.b() >= 0 && ev.b() < 41) {
                    ItemStack[] a = inv.computeIfAbsent(ev.a(), k -> new ItemStack[41]);
                    a[ev.b()] = decode(ev.data());
                    if (ev.a() == guiPid) guiDirty = true;
                }
            }
            case Ev.HELD -> {
                held.put(ev.a(), ev.b());
                if (ev.a() == guiPid) guiDirty = true;
            }
            case Ev.VITALS -> {
                vitals.put(ev.a(), ev);
                if (ev.a() == guiPid) guiDirty = true;
            }
            case Ev.MSG -> {
                if (!quiet && st.chatFeed) {
                    Component stamp = Component.text("[" + Fmt.time(f) + "] ", NamedTextColor.DARK_GRAY);
                    if (ev.b() == 0) {
                        viewer.sendMessage(stamp.append(Component.text("<" + nameOf(ev.a()) + "> ", NamedTextColor.WHITE))
                                .append(Component.text(ev.s(), NamedTextColor.GRAY)));
                    } else {
                        viewer.sendMessage(stamp.append(Component.text(nameOf(ev.a()) + ": ", NamedTextColor.WHITE))
                                .append(Component.text(ev.s(), NamedTextColor.YELLOW)));
                    }
                }
            }
            case Ev.ITEM -> {
                if (!quiet && st.chatFeed) {
                    int amount = Math.round(ev.f());
                    viewer.sendMessage(Component.text("[" + Fmt.time(f) + "] ", NamedTextColor.DARK_GRAY)
                            .append(Component.text(nameOf(ev.a()) + (ev.b() == 0 ? " dropped " : " picked up ")
                                    + amount + "x " + ev.s(), NamedTextColor.DARK_AQUA)));
                }
            }
            case Ev.DEATH -> {
                if (!quiet) viewer.sendMessage(Component.text("[" + Fmt.time(f) + "] ", NamedTextColor.DARK_GRAY)
                        .append(Component.text(ev.s().isEmpty() ? nameOf(ev.a()) + " died" : ev.s(), NamedTextColor.DARK_RED)));
            }
            case Ev.BLOCK -> onBlock(ev, animate);
            case Ev.PROJ_SPAWN -> projType.put(ev.a(), ev.s());
            case Ev.PROJ_POS -> {
                if (isTarget) {
                    World w = world(ev.b());
                    if (w != null) projPos.put(ev.a(), new Location(w, ev.x(), ev.y(), ev.z()));
                }
            }
            case Ev.PROJ_END -> {
                projType.remove(ev.a());
                projPos.remove(ev.a());
                Entity e = projEntities.remove(ev.a());
                if (e != null) e.remove();
            }
            default -> {
            }
        }
    }

    private void onEquip(Ev ev) {
        int slot = ev.b();
        if (slot < 0 || slot > 5) return;
        ItemStack[] a = equip.computeIfAbsent(ev.a(), k -> new ItemStack[6]);
        a[slot] = decode(ev.data());
        equipDirty.add(ev.a());
    }

    private static ItemStack decode(byte[] data) {
        if (data == null || data.length == 0) return null;
        try {
            return ItemStack.deserializeBytes(data);
        } catch (Throwable ignored) {
            return null;
        }
    }

    private void flushEquipment() {
        for (int pid : equipDirty) applyEquip(pid);
        equipDirty.clear();
    }

    private void applyEquip(int pid) {
        Entity e = npcs.get(pid);
        ItemStack[] a = equip.get(pid);
        if (!(e instanceof LivingEntity le) || a == null) return;
        EntityEquipment eq = le.getEquipment();
        if (eq == null) return;
        eq.setItem(EquipmentSlot.HAND, a[0]);
        eq.setItem(EquipmentSlot.OFF_HAND, a[1]);
        eq.setItem(EquipmentSlot.HEAD, a[2]);
        eq.setItem(EquipmentSlot.CHEST, a[3]);
        eq.setItem(EquipmentSlot.LEGS, a[4]);
        eq.setItem(EquipmentSlot.FEET, a[5]);
    }

    private void onBlock(Ev ev, boolean animate) {
        World w = world(ev.a());
        if (w == null) return;
        try {
            BlockData bd = Bukkit.createBlockData(ev.s());
            Location l = new Location(w, ev.x(), ev.y(), ev.z());
            String key = w.getName() + ":" + (int) ev.x() + ":" + (int) ev.y() + ":" + (int) ev.z();
            BlockData prior = null;
            String priorStr = ev.priorBlock();
            if (!priorStr.isEmpty()) {
                try {
                    prior = Bukkit.createBlockData(priorStr);
                } catch (IllegalArgumentException ignored) {
                    prior = null;
                }
            }
            if (prior == null) prior = shownBlocks.get(key);
            viewer.sendBlockChange(l, bd);
            shownBlocks.put(key, bd);
            changedBlocks.put(key, l);
            if (animate) blockEffects(l, prior, bd);
        } catch (IllegalArgumentException ignored) {
            // unknown block data from a different server version
        }
    }

    /** Particles, sound and a brief outline so a placed (green) or broken (red) block is easy to spot. */
    private void blockEffects(Location l, BlockData prior, BlockData now) {
        boolean broke = now.getMaterial().isAir();
        BlockData fx = broke ? prior : now;
        if (fx == null || fx.getMaterial().isAir()) return;
        Location center = l.clone().add(0.5, 0.5, 0.5);
        try {
            viewer.spawnParticle(Particle.BLOCK, center, 25, 0.3, 0.3, 0.3, fx);
            SoundGroup sg = fx.getSoundGroup();
            viewer.playSound(center, broke ? sg.getBreakSound() : sg.getPlaceSound(), 0.8f, 1.0f);
        } catch (Throwable ignored) {
            // effects are optional
        }
        if (st.blockHighlight && flashes.size() < 60) {
            Entity d = NpcFactory.spawnBlockFlash(plugin, l, fx, broke ? Color.RED : Color.LIME, viewer);
            if (d != null) {
                flashes.add(d);
                Bukkit.getScheduler().runTaskLater(plugin, () -> {
                    d.remove();
                    flashes.remove(d);
                }, 15L);
            }
        }
    }

    private void revertBlocks() {
        if (!viewer.isOnline()) {
            changedBlocks.clear();
            shownBlocks.clear();
            return;
        }
        for (Location l : changedBlocks.values()) {
            World w = l.getWorld();
            if (w == null || !w.isChunkLoaded(l.getBlockX() >> 4, l.getBlockZ() >> 4)) continue;
            viewer.sendBlockChange(l, l.getBlock().getBlockData());
        }
        changedBlocks.clear();
        shownBlocks.clear();
    }

    private void reconcileNpcs(Frame f) {
        Set<Integer> seen = new HashSet<>();
        for (PState s : f.states()) {
            int pid = s.pid();
            if (pid < 0 || pid >= replay.players().size()) continue;
            World w = world(s.world());
            if (w == null) continue;
            seen.add(pid);
            lastStates.put(pid, s);
            Location loc = new Location(w, s.x(), s.y(), s.z(), s.yaw(), s.pitch());
            Entity e = npcs.get(pid);
            if (e == null || !e.isValid()) {
                e = NpcFactory.spawnPlayer(plugin, loc, replay.players().get(pid), viewer);
                if (e == null) continue;
                npcs.put(pid, e);
                equipDirty.add(pid);
                hpShown.remove(pid);
                usingNow.remove(pid);
                sprintNow.remove(pid);
            } else {
                e.teleport(loc);
            }
            e.setRotation(s.yaw(), s.pitch());
            Pose pose = s.has(PState.GLIDE) ? Pose.FALL_FLYING
                    : s.has(PState.SWIM) ? Pose.SWIMMING
                    : s.has(PState.SNEAK) ? Pose.SNEAKING : Pose.STANDING;
            if (e.getPose() != pose) Compat.call(e, "setPose", pose, true);

            // shield held up: show it floating in front so blocking is obvious
            Entity sh = shields.get(pid);
            if ((st.shieldDisplay || Boolean.FALSE.equals(poseSupported)) && s.has(PState.BLOCK)) {
                double rad = Math.toRadians(s.yaw());
                Location sl = new Location(w, s.x() - Math.sin(rad) * 0.6, s.y() + (s.has(PState.SNEAK) ? 1.0 : 1.2),
                        s.z() + Math.cos(rad) * 0.6, s.yaw() + 180f, 0f);
                if (sh == null || !sh.isValid()) {
                    sh = NpcFactory.spawnShield(plugin, sl, viewer);
                    if (sh != null) shields.put(pid, sh);
                } else {
                    sh.teleport(sl);
                }
            } else if (sh != null) {
                sh.remove();
                shields.remove(pid);
            }

            // health next to the name
            int hp10 = Math.round(s.health() * 10);
            Integer shown = hpShown.get(pid);
            if (shown == null || shown != hp10) {
                hpShown.put(pid, hp10);
                e.customName(Component.text(replay.players().get(pid).name(), NamedTextColor.WHITE)
                        .append(Component.text(" \u2665" + Fmt.num1(s.health()), NamedTextColor.RED)));
            }
            // eating / drinking / drawing a bow / blocking
            int usingCode = !s.has(PState.USING) ? 0 : s.has(PState.USING_OFF) ? 2 : 1;
            if (usingCode == 0) usingNow.remove(pid);
            else usingNow.put(pid, usingCode);
            boolean sprint = s.has(PState.SPRINT);
            if (sprint != sprintNow.contains(pid)) {
                Compat.call(e, "setSprinting", sprint);
                if (sprint) sprintNow.add(pid);
                else sprintNow.remove(pid);
            }
        }
        Iterator<Map.Entry<Integer, Entity>> it = npcs.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<Integer, Entity> en = it.next();
            if (!seen.contains(en.getKey())) {
                en.getValue().remove();
                Entity gone = shields.remove(en.getKey());
                if (gone != null) gone.remove();
                lastStates.remove(en.getKey());
                hpShown.remove(en.getKey());
                usingNow.remove(en.getKey());
                sprintNow.remove(en.getKey());
                it.remove();
            }
        }
    }

    private void reconcileProjectiles() {
        for (Map.Entry<Integer, Location> en : projPos.entrySet()) {
            Entity e = projEntities.get(en.getKey());
            if (e == null || !e.isValid()) {
                e = NpcFactory.spawnProjectile(plugin, en.getValue(), projType.getOrDefault(en.getKey(), "SNOWBALL"), viewer);
                if (e != null) projEntities.put(en.getKey(), e);
            } else {
                e.teleport(en.getValue());
            }
        }
        Iterator<Map.Entry<Integer, Entity>> it = projEntities.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<Integer, Entity> en = it.next();
            if (!projPos.containsKey(en.getKey())) {
                en.getValue().remove();
                it.remove();
            }
        }
        projPos.clear();
    }

    private void despawnAll() {
        for (Entity e : npcs.values()) e.remove();
        for (Entity e : projEntities.values()) e.remove();
        for (Entity e : shields.values()) e.remove();
        for (Entity e : flashes) e.remove();
        shields.clear();
        flashes.clear();
        npcs.clear();
        projEntities.clear();
        projPos.clear();
        lastStates.clear();
    }

    private void resetSim() {
        if (viewer.isOnline()) viewer.setSpectatorTarget(null);
        attached = null;
        despawnAll();
        revertBlocks();
        equip.clear();
        equipDirty.clear();
        inv.clear();
        held.clear();
        vitals.clear();
        hpShown.clear();
        usingNow.clear();
        sprintNow.clear();
        guiDirty = true;
        swingHist.clear();
        projType.clear();
        cur = -1;
    }

    // ------------------------------------------------------------- chat feed

    private void hitFeed(Ev ev, int f) {
        if (!st.hitFeed) return;
        String[] parts = ev.s().split("\\|", 2);
        String cause = parts[0];
        String detail = parts.length > 1 ? parts[1] : "";
        String what = detail.isEmpty() ? cause : detail;
        String attacker = ev.a() >= 0 ? nameOf(ev.a()) : "World";

        Component line = Component.text("[" + Fmt.time(f) + "] ", NamedTextColor.DARK_GRAY)
                .append(Component.text(attacker, NamedTextColor.WHITE))
                .append(Component.text(" -> ", NamedTextColor.GRAY))
                .append(Component.text(nameOf(ev.b()), NamedTextColor.WHITE))
                .append(Component.text("  " + Fmt.num1(ev.f()) + " dmg", NamedTextColor.RED));
        String brief = attacker + " -> " + nameOf(ev.b()) + " " + Fmt.num1(ev.f());
        if (ev.x() >= 0) {
            TextColor rc = ev.x() >= st.reachBad ? NamedTextColor.RED : ev.x() >= st.reachWarn ? NamedTextColor.YELLOW : NamedTextColor.GREEN;
            TextColor cc = ev.y() >= st.cpsWarn ? NamedTextColor.RED : NamedTextColor.GREEN;
            line = line.append(Component.text("  reach " + Fmt.num(ev.x()), rc))
                    .append(Component.text("  CPS " + (int) ev.y(), cc));
            brief += " reach " + Fmt.num(ev.x()) + " CPS " + (int) ev.y();
        }
        line = line.append(Component.text("  (" + what + ")", NamedTextColor.DARK_GRAY));
        viewer.sendMessage(line);
        lastHit = brief;
        lastHitFrame = f;
    }
}
