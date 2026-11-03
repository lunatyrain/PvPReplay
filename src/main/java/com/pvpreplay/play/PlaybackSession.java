package com.pvpreplay.play;

import com.pvpreplay.Compat;
import com.pvpreplay.Msg;
import com.pvpreplay.PvPReplayPlugin;
import com.pvpreplay.Settings;
import com.pvpreplay.data.*;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextColor;
import org.bukkit.Bukkit;
import org.bukkit.Color;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.SoundCategory;
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
    // for every block the recording changes: the first change, whose "previous block" is what it looked like at the start
    private final Map<String, Ev> firstBlockChange = new LinkedHashMap<>();
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

    // sounds (synthesised from the recorded movement / events) and the inventory shown above heads
    private boolean soundsOn;
    private boolean overheadInv;
    private boolean quietNow = false; // true while seeking: no sounds
    private final Map<Integer, InvTagView> invTags = new HashMap<>();
    private final Set<Integer> tagDirty = new HashSet<>();
    private final Map<Integer, Double> stepDist = new HashMap<>();
    private final Map<Integer, Double> fallPeak = new HashMap<>();
    private final Set<Integer> inWaterNow = new HashSet<>();
    private final Map<Integer, Integer> useTicks = new HashMap<>();
    private final Set<Integer> eatingNow = new HashSet<>();
    private final Map<Integer, Location> projLast = new HashMap<>();
    private final Set<Integer> projSounded = new HashSet<>();
    private boolean voiceOn;
    private boolean hasVoice = false;
    private final Map<Integer, Object> voiceCh = new HashMap<>();
    private final List<ReplayAnalyzer.Finding> findings;
    private final Map<Integer, List<ReplayAnalyzer.Finding>> findingsAt = new HashMap<>();
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
        this.soundsOn = st.sounds;
        this.voiceOn = st.playVoice;
        this.overheadInv = st.overheadInventory;

        List<ReplayAnalyzer.Finding> found;
        try {
            found = ReplayAnalyzer.analyze(replay, st.reachWarn, st.reachBad, st.cpsWarn);
        } catch (RuntimeException ex) {
            plugin.getLogger().log(java.util.logging.Level.WARNING, "Could not analyse replay", ex);
            found = List.of();
        }
        this.findings = found;
        for (ReplayAnalyzer.Finding fd : findings) findingsAt.computeIfAbsent(fd.frame(), k -> new ArrayList<>()).add(fd);

        for (Frame fr : frames) {
            for (Ev e : fr.events()) {
                if (e.type() == Ev.BLOCK && !e.priorBlock().isEmpty()) {
                    firstBlockChange.putIfAbsent(e.a() + ":" + (int) e.x() + ":" + (int) e.y() + ":" + (int) e.z(), e);
                }
            }
        }

        int lastHitAt = -10000;
        for (int i = 0; i < frames.size(); i++) {
            for (Ev e : frames.get(i).events()) {
                if (!hasVoice && e.type() == Ev.VOICE) hasVoice = true;
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

        applyStartBlocks();
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
        syncAllTags();
        return true;
    }

    public void freeCam() {
        followPid = -1;
        attached = null;
        viewer.setSpectatorTarget(null);
        syncAllTags();
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
                .append(Msg.button("[Free cam]", NamedTextColor.GRAY, "/replay follow", "Leave first person"))
                .append(sp())
                .append(Msg.button("[Inv above heads]", NamedTextColor.GOLD, "/replay invtag", "Show or hide everyone's inventory above their head"))
                .append(sp())
                .append(Msg.button("[Sounds]", NamedTextColor.GREEN, "/replay sounds", "Turn replay sounds on or off"))
                .append(sp())
                .append(Msg.button("[Voice]", NamedTextColor.GREEN, "/replay voice", "Turn recorded voice chat on or off"));
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
        if (paused && (tickCount % 4) == 0) freezeProjectiles();
        maintainUsing();
        handleFollow();
        refreshTags();
        updateGui();
        tickCount++;
        if ((tickCount % 40) == 0) resendBlocks();
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
                syncAllTags();
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

    /** Prints a warning line when playback passes a suspicious moment. */
    private void warnAt(int f) {
        if (!st.suspicionFeed) return;
        List<ReplayAnalyzer.Finding> list = findingsAt.get(f);
        if (list == null) return;
        for (ReplayAnalyzer.Finding fd : list) viewer.sendMessage(findingLine(fd, false));
    }

    private Component findingLine(ReplayAnalyzer.Finding fd, boolean withButtons) {
        NamedTextColor c = fd.severity() == 2 ? NamedTextColor.RED : NamedTextColor.YELLOW;
        String name = nameOf(fd.pid());
        boolean rate = fd.kind().endsWith("_RATE");
        Component line = Component.text("[" + Fmt.time(fd.frame()) + "] ", NamedTextColor.DARK_GRAY)
                .append(Component.text("\u26a0 " + name + ": " + fd.label() + (fd.count() > 1 && !rate ? " x" + fd.count() : ""), c))
                .append(Component.text(" (" + fd.detail() + ")", NamedTextColor.GRAY));
        if (withButtons) {
            int lead = Math.max(0, fd.frame() - 60); // start 3 seconds earlier
            line = line.append(Component.text(" "))
                    .append(Msg.button("[Jump]", NamedTextColor.GREEN, "/replay seek " + Fmt.time(lead), "Go to 3 seconds before this"))
                    .append(Component.text(" "))
                    .append(Msg.button("[Follow]", NamedTextColor.AQUA, "/replay follow " + name, "See through " + name + "'s eyes"));
        }
        return line;
    }

    /** /replay flags: every suspicious moment found in this replay, with Jump / Follow buttons. */
    public void sendFlags() {
        viewer.sendMessage(Msg.PREFIX.append(Component.text("Suspicious moments", NamedTextColor.WHITE)));
        if (findings.isEmpty()) {
            viewer.sendMessage(Component.text("Nothing stood out.", NamedTextColor.GREEN));
            return;
        }
        int shown = 0;
        for (ReplayAnalyzer.Finding fd : findings) {
            if (shown++ >= 30) {
                viewer.sendMessage(Component.text("... " + (findings.size() - 30) + " more.", NamedTextColor.DARK_GRAY));
                break;
            }
            viewer.sendMessage(findingLine(fd, true));
        }
        viewer.sendMessage(Component.text("Leads, not proof: lag and knockback can cause false hits, and block actions are "
                + "credited to the nearest recorded player.", NamedTextColor.DARK_GRAY));
    }

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
        gui = Bukkit.createInventory(holder, 45, Component.text(replay.players().get(pid).name() + "'s inventory"));
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

    /**
     * Lays the inventory out like a normal player inventory, with the real items only (no labels or filler):
     * top row = armor (helmet to boots) and off hand, then the 27 storage slots, then the hotbar.
     */
    private void fillGui() {
        guiDirty = false;
        ItemStack[] a = inv.getOrDefault(guiPid, new ItemStack[41]);
        gui.setItem(0, a[39]); // helmet
        gui.setItem(1, a[38]); // chestplate
        gui.setItem(2, a[37]); // leggings
        gui.setItem(3, a[36]); // boots
        gui.setItem(5, a[40]); // off hand
        for (int i = 9; i < 36; i++) gui.setItem(i, a[i]); // storage
        for (int i = 0; i < 9; i++) gui.setItem(36 + i, a[i]); // hotbar
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
        quietNow = quiet;
        for (int f = cur + 1; f < target; f++) {
            for (Ev ev : frames.get(f).events()) handle(ev, f, quiet, false);
            if (!quiet) warnAt(f);
        }
        if (!quiet) warnAt(target);
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
        quietNow = false;
    }

    private void handle(Ev ev, int f, boolean quiet, boolean isTarget) {
        boolean animate = isTarget && !quiet;
        switch (ev.type()) {
            case Ev.EQUIP -> onEquip(ev, !quiet);
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
                        if (ev.s().contains("[CRIT")) {
                            // critical hit: a big burst of crit stars, like in the game
                            viewer.spawnParticle(Particle.CRIT, at, 30, 0.3, 0.4, 0.3, 0.4);
                            viewer.spawnParticle(Particle.ENCHANTED_HIT, at, 12, 0.3, 0.4, 0.3, 0.3);
                        } else {
                            viewer.spawnParticle(Particle.CRIT, at, blocked ? 12 : 6, 0.25, 0.3, 0.25);
                        }
                    }
                }
                if (!quiet) hitSounds(ev);
            }
            case Ev.INV -> {
                if (ev.b() >= 0 && ev.b() < 41) {
                    ItemStack[] a = inv.computeIfAbsent(ev.a(), k -> new ItemStack[41]);
                    a[ev.b()] = decode(ev.data());
                    if (ev.a() == guiPid) guiDirty = true;
                    if (overheadInv) tagDirty.add(ev.a());
                }
            }
            case Ev.HELD -> {
                held.put(ev.a(), ev.b());
                if (ev.a() == guiPid) guiDirty = true;
                if (overheadInv) tagDirty.add(ev.a());
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
            case Ev.VOICE -> {
                if (isTarget && !quiet) playVoice(ev);
            }
            case Ev.ITEM -> {
                if (!quiet && ev.b() == 1) snd(where(ev.a(), 1.0), "minecraft:entity.item.pickup", 0.5f, 1.2f);
                if (!quiet && st.chatFeed) {
                    int amount = Math.round(ev.f());
                    viewer.sendMessage(Component.text("[" + Fmt.time(f) + "] ", NamedTextColor.DARK_GRAY)
                            .append(Component.text(nameOf(ev.a()) + (ev.b() == 0 ? " dropped " : " picked up ")
                                    + amount + "x " + ev.s(), NamedTextColor.DARK_AQUA)));
                }
            }
            case Ev.DEATH -> {
                if (!quiet) snd(where(ev.a(), 1.0), "minecraft:entity.player.death", 1.0f, 1.0f);
                if (!quiet) viewer.sendMessage(Component.text("[" + Fmt.time(f) + "] ", NamedTextColor.DARK_GRAY)
                        .append(Component.text(ev.s().isEmpty() ? nameOf(ev.a()) + " died" : ev.s(), NamedTextColor.DARK_RED)));
            }
            case Ev.TOTEM -> {
                if (!quiet) {
                    Location at = where(ev.a(), 1.0);
                    if (at != null) {
                        snd(at, "minecraft:item.totem.use", 1.0f, 1.0f);
                        if (animate) viewer.spawnParticle(Particle.TOTEM_OF_UNDYING, at, 30, 0.4, 0.6, 0.4, 0.3);
                    }
                    viewer.sendMessage(Component.text("[" + Fmt.time(f) + "] ", NamedTextColor.DARK_GRAY)
                            .append(Component.text(nameOf(ev.a()) + " popped a totem", NamedTextColor.GOLD)));
                }
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
                String endType = projType.remove(ev.a());
                Location endAt = projLast.remove(ev.a());
                if (!quiet && endType != null) endSound(endType, endAt);
                projPos.remove(ev.a());
                Entity e = projEntities.remove(ev.a());
                if (e != null) e.remove();
            }
            default -> {
            }
        }
    }

    private void onEquip(Ev ev, boolean audible) {
        int slot = ev.b();
        if (slot < 0 || slot > 5) return;
        ItemStack[] a = equip.computeIfAbsent(ev.a(), k -> new ItemStack[6]);
        ItemStack old = a[slot];
        ItemStack now = decode(ev.data());
        a[slot] = now;
        equipDirty.add(ev.a());
        // armor put on (key frames repeat the same item, so only real changes make a sound)
        if (audible && slot >= 2 && now != null && (old == null || !old.isSimilar(now))) {
            snd(where(ev.a(), 1.2), armorSoundKey(now.getType()), 1.0f, 1.0f);
        }
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

    /**
     * Particles and sound for a block change. The block itself is already really placed or removed for the viewer
     * (sendBlockChange); a broken block also lingers for a moment as a real block that shrinks away, like a break.
     */
    private void blockEffects(Location l, BlockData prior, BlockData now) {
        boolean broke = now.getMaterial().isAir();
        BlockData fx = broke ? prior : now;
        if (fx == null || fx.getMaterial().isAir()) return;
        Location center = l.clone().add(0.5, 0.5, 0.5);
        try {
            viewer.spawnParticle(Particle.BLOCK, center, broke ? 30 : 12, 0.3, 0.3, 0.3, fx);
            SoundGroup sg = fx.getSoundGroup();
            if (soundsOn) viewer.playSound(center, broke ? sg.getBreakSound() : sg.getPlaceSound(), 0.8f, 1.0f);
        } catch (Throwable ignored) {
            // effects are optional
        }
        if (broke && st.blockHighlight && flashes.size() < 60) {
            Entity d = NpcFactory.spawnBreakGhost(plugin, l, fx, viewer);
            if (d != null) {
                flashes.add(d);
                Bukkit.getScheduler().runTaskLater(plugin, () -> {
                    d.remove();
                    flashes.remove(d);
                }, 8L);
            }
        }
    }

    /**
     * The world already contains every block placed during the recording (and lacks the ones broken), so without this
     * they would be in the way from the first second. Show each changed spot as it was before its first change; the
     * replay then places and breaks them at the right moments.
     */
    private void applyStartBlocks() {
        if (!viewer.isOnline()) return;
        for (Map.Entry<String, Ev> en : firstBlockChange.entrySet()) {
            Ev ev = en.getValue();
            World w = world(ev.a());
            if (w == null) continue;
            try {
                BlockData bd = Bukkit.createBlockData(ev.priorBlock());
                Location l = new Location(w, ev.x(), ev.y(), ev.z());
                String key = w.getName() + ":" + (int) ev.x() + ":" + (int) ev.y() + ":" + (int) ev.z();
                viewer.sendBlockChange(l, bd);
                shownBlocks.put(key, bd);
                changedBlocks.put(key, l);
            } catch (IllegalArgumentException ignored) {
                // block data from a different server version
            }
        }
    }

    /** Sends the replay's version of every touched block again: the client drops changes for chunks it has not loaded yet. */
    private void resendBlocks() {
        if (shownBlocks.isEmpty() || shownBlocks.size() > 1500 || !viewer.isOnline()) return;
        for (Map.Entry<String, BlockData> en : shownBlocks.entrySet()) {
            Location l = changedBlocks.get(en.getKey());
            if (l != null) viewer.sendBlockChange(l, en.getValue());
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
            PState prevState = lastStates.get(pid);
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
            moveSounds(pid, prevState, s, w);
            syncTag(pid, s, w);

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
            useSounds(pid, s, w, usingCode);
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
                InvTagView tagGone = invTags.remove(en.getKey());
                if (tagGone != null) tagGone.remove();
                stepDist.remove(en.getKey());
                fallPeak.remove(en.getKey());
                inWaterNow.remove(en.getKey());
                useTicks.remove(en.getKey());
                eatingNow.remove(en.getKey());
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
            Location prev = projLast.get(en.getKey());
            Location to = en.getValue();
            // point the projectile the way it is flying (arrows and tridents are drawn along their rotation)
            if (prev != null && prev.getWorld() == to.getWorld()) {
                double dx = to.getX() - prev.getX(), dy = to.getY() - prev.getY(), dz = to.getZ() - prev.getZ();
                double flat = Math.sqrt(dx * dx + dz * dz);
                if (flat * flat + dy * dy > 1.0E-6) {
                    to.setYaw((float) Math.toDegrees(Math.atan2(-dx, dz)));
                    to.setPitch((float) Math.toDegrees(-Math.atan2(dy, flat)));
                } else {
                    to.setYaw(prev.getYaw());
                    to.setPitch(prev.getPitch());
                }
            }
            projLast.put(en.getKey(), to.clone());
            Entity e = projEntities.get(en.getKey());
            if (e == null || !e.isValid()) {
                e = NpcFactory.spawnProjectile(plugin, en.getValue(), projType.getOrDefault(en.getKey(), "SNOWBALL"), viewer);
                if (e != null) {
                    projEntities.put(en.getKey(), e);
                    if (projSounded.add(en.getKey())) launchSound(projType.get(en.getKey()), en.getValue());
                }
            } else {
                e.teleport(to);
                // The server only syncs a projectile's position every 10-20 ticks; in between the client flies it using
                // its velocity. So give it this tick's real movement, otherwise it sits still (or seems to be missing).
                if (prev != null && prev.getWorld() == to.getWorld() && !(e instanceof org.bukkit.entity.Display)) {
                    try {
                        double k = Math.min(1.0, speed); // below 1x one frame lasts several ticks
                        e.setVelocity(new org.bukkit.util.Vector((to.getX() - prev.getX()) * k,
                                (to.getY() - prev.getY()) * k, (to.getZ() - prev.getZ()) * k));
                    } catch (Throwable ignored) {
                        // velocity is only a smoothing aid
                    }
                }
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

    /** While paused the client must not keep flying projectiles along their last velocity. */
    private void freezeProjectiles() {
        for (Entity e : projEntities.values()) {
            if (e.isValid() && !(e instanceof org.bukkit.entity.Display)) {
                try {
                    e.setVelocity(new org.bukkit.util.Vector(0, 0, 0));
                } catch (Throwable ignored) {
                    // only a smoothing aid
                }
            }
        }
    }

    private void despawnAll() {
        for (Entity e : npcs.values()) e.remove();
        for (Entity e : projEntities.values()) e.remove();
        for (Entity e : shields.values()) e.remove();
        for (Entity e : flashes) e.remove();
        for (InvTagView t : invTags.values()) t.remove();
        invTags.clear();
        voiceCh.clear();
        stepDist.clear();
        fallPeak.clear();
        inWaterNow.clear();
        useTicks.clear();
        eatingNow.clear();
        projLast.clear();
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
        applyStartBlocks();
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
        projSounded.clear();
        tagDirty.clear();
        cur = -1;
    }


    // ----------------------------------------------------------------- sounds
    //
    // Paper has no API to capture the sounds a server plays, so the replay rebuilds them from what was recorded:
    // footsteps, jumps, landings, swimming, hits, hurt/death, eating, drinking, armor, shots, item pickups and
    // block placing/breaking. Sounds the server never recorded (mobs, explosions, containers, doors) are not played.

    public boolean toggleSounds(Boolean on) {
        soundsOn = on == null ? !soundsOn : on;
        return soundsOn;
    }

    public boolean soundsOn() {
        return soundsOn;
    }

    private void snd(Location at, String key, float vol, float pitch) {
        if (!soundsOn || quietNow || at == null || at.getWorld() == null || key == null) return;
        try {
            viewer.playSound(at, key, SoundCategory.PLAYERS, vol, pitch);
        } catch (Throwable ignored) {
            // sounds are optional
        }
    }

    private void snd(Location at, Sound sound, float vol, float pitch) {
        if (!soundsOn || quietNow || at == null || at.getWorld() == null || sound == null) return;
        try {
            viewer.playSound(at, sound, SoundCategory.PLAYERS, vol, pitch);
        } catch (Throwable ignored) {
            // sounds are optional
        }
    }

    /** Where a player was at the last frame, lifted by {@code up} blocks; null if unknown. */
    private Location where(int pid, double up) {
        PState s = lastStates.get(pid);
        if (s == null) return null;
        World w = world(s.world());
        return w == null ? null : new Location(w, s.x(), s.y() + up, s.z());
    }

    private SoundGroup groundGroup(World w, PState s) {
        int bx = (int) Math.floor(s.x());
        int bz = (int) Math.floor(s.z());
        if (!w.isChunkLoaded(bx >> 4, bz >> 4)) return null;
        int by = (int) Math.floor(s.y() - 0.2);
        for (int i = 0; i < 2; i++) {
            BlockData bd = shownBlocks.get(w.getName() + ":" + bx + ":" + (by - i) + ":" + bz);
            if (bd == null) bd = w.getBlockAt(bx, by - i, bz).getBlockData();
            if (!bd.getMaterial().isAir()) return bd.getSoundGroup();
        }
        return null;
    }

    private boolean inWater(World w, PState s) {
        int bx = (int) Math.floor(s.x());
        int bz = (int) Math.floor(s.z());
        if (!w.isChunkLoaded(bx >> 4, bz >> 4)) return false;
        return w.getBlockAt(bx, (int) Math.floor(s.y() + 0.4), bz).getType() == Material.WATER;
    }

    /** Footsteps, jump, landing, splash and swimming, worked out from how the player moved since the last frame. */
    private void moveSounds(int pid, PState prev, PState s, World w) {
        boolean ground = s.has(PState.GROUND);
        boolean teleported = prev == null || prev.world() != s.world()
                || Math.abs(s.x() - prev.x()) > 8 || Math.abs(s.z() - prev.z()) > 8 || Math.abs(s.y() - prev.y()) > 8;
        boolean silent = quietNow || !soundsOn || teleported;
        double dx = prev == null ? 0 : s.x() - prev.x();
        double dz = prev == null ? 0 : s.z() - prev.z();
        double h = Math.sqrt(dx * dx + dz * dz);
        Location at = new Location(w, s.x(), s.y(), s.z());

        boolean water = inWater(w, s);
        boolean wasWater = inWaterNow.contains(pid);
        if (water) inWaterNow.add(pid);
        else inWaterNow.remove(pid);
        if (water && !wasWater && !silent) snd(at, "minecraft:entity.player.splash", 0.6f, 1.0f);

        if (teleported) {
            stepDist.put(pid, 0.0);
            fallPeak.put(pid, s.y());
            return;
        }
        double acc = stepDist.getOrDefault(pid, 0.0);

        if (water) {
            if (h > 0.02) {
                acc += h;
                if (acc >= 1.6) {
                    acc = 0;
                    if (!silent) snd(at, "minecraft:entity.player.swim", 0.35f, 1.0f);
                }
            }
            stepDist.put(pid, acc);
            fallPeak.put(pid, s.y());
            return;
        }

        if (!ground) {
            double peak = prev.has(PState.GROUND) ? Math.max(prev.y(), s.y())
                    : Math.max(fallPeak.getOrDefault(pid, s.y()), s.y());
            fallPeak.put(pid, peak);
            if (!silent && prev.has(PState.GROUND) && s.y() > prev.y() + 0.05) {
                SoundGroup g = groundGroup(w, prev);
                if (g != null) snd(at, g.getStepSound(), 0.4f * g.getVolume(), g.getPitch());
            }
            return;
        }

        if (!prev.has(PState.GROUND)) {
            double fall = fallPeak.getOrDefault(pid, s.y()) - s.y();
            if (!silent && fall > 0.6) {
                SoundGroup g = groundGroup(w, s);
                if (g != null) snd(at, g.getFallSound(), Math.min(1.0f, 0.4f + (float) fall * 0.1f) * g.getVolume(), g.getPitch());
                if (fall >= 3.0) snd(at, "minecraft:entity.player.big_fall", 0.8f, 1.0f);
                else if (fall >= 1.5) snd(at, "minecraft:entity.player.small_fall", 0.7f, 1.0f);
            }
            fallPeak.put(pid, s.y());
            acc = 0;
        }

        if (!s.has(PState.SNEAK) && h > 0.02) {
            acc += h;
            if (acc >= 1.67) {
                acc -= 1.67;
                if (!silent) {
                    SoundGroup g = groundGroup(w, s);
                    if (g != null) snd(at, g.getStepSound(), 0.5f * g.getVolume(), g.getPitch());
                }
            }
        }
        stepDist.put(pid, acc);
        fallPeak.put(pid, s.y());
    }

    /** Eating and drinking noises while the item is in use, plus the burp at the end of a meal. */
    private void useSounds(int pid, PState s, World w, int code) {
        if (code == 0) {
            Integer t = useTicks.remove(pid);
            boolean ate = eatingNow.remove(pid);
            if (t != null && ate && t >= 28) snd(where(pid, 1.4), "minecraft:entity.player.burp", 0.6f, 1.0f);
            return;
        }
        int t = useTicks.merge(pid, 1, Integer::sum);
        ItemStack[] gear = equip.get(pid);
        ItemStack it = gear == null ? null : gear[code == 2 ? 1 : 0];
        if (it == null) return;
        Material m = it.getType();
        boolean drink = m == Material.POTION || m == Material.HONEY_BOTTLE || m == Material.MILK_BUCKET;
        boolean eat = !drink && m.isEdible();
        if (!drink && !eat) return;
        if (eat) eatingNow.add(pid);
        if (t % 4 == 0) {
            Location at = new Location(w, s.x(), s.y() + 1.5, s.z());
            snd(at, drink ? "minecraft:entity.generic.drink" : "minecraft:entity.generic.eat", 0.6f,
                    0.9f + (float) (Math.random() * 0.2));
        }
    }

    /** Attack / hurt sounds for one recorded hit. */
    private void hitSounds(Ev ev) {
        Location at = where(ev.b(), 1.0);
        if (at == null) return;
        if (ev.s().contains("[BLOCKED")) {
            snd(at, Sound.ITEM_SHIELD_BLOCK, 1.0f, 1.0f);
            return;
        }
        String cause = ev.s().split("\\|", 2)[0];
        switch (cause) {
            case "ENTITY_ATTACK" -> snd(at, ev.s().contains("[CRIT") ? "minecraft:entity.player.attack.crit"
                    : "minecraft:entity.player.attack.strong", 1.0f, 1.0f);
            case "ENTITY_SWEEP_ATTACK" -> snd(at, "minecraft:entity.player.attack.sweep", 1.0f, 1.0f);
            case "PROJECTILE" -> snd(at, "minecraft:entity.arrow.hit_player", 0.8f, 1.0f);
            default -> {
            }
        }
        String hurt = switch (cause) {
            case "FIRE", "FIRE_TICK", "LAVA", "HOT_FLOOR" -> "minecraft:entity.player.hurt_on_fire";
            case "DROWNING" -> "minecraft:entity.player.hurt_drown";
            case "FREEZE" -> "minecraft:entity.player.hurt_freeze";
            case "THORNS" -> "minecraft:enchant.thorns.hit";
            case "FALL" -> ev.f() > 6 ? "minecraft:entity.player.big_fall" : "minecraft:entity.player.small_fall";
            default -> "minecraft:entity.player.hurt";
        };
        snd(at, hurt, 1.0f, 1.0f);
    }

    private static String armorSoundKey(Material m) {
        String n = m.name();
        String kind = n.contains("LEATHER") ? "leather"
                : n.contains("CHAINMAIL") ? "chain"
                : n.contains("NETHERITE") ? "netherite"
                : n.contains("DIAMOND") ? "diamond"
                : n.contains("IRON") ? "iron"
                : n.contains("GOLDEN") ? "gold"
                : n.contains("TURTLE") ? "turtle"
                : n.equals("ELYTRA") ? "elytra"
                : "generic";
        return "minecraft:item.armor.equip_" + kind;
    }

    private void launchSound(String type, Location at) {
        if (type == null) return;
        String key = switch (type) {
            case "ARROW", "SPECTRAL_ARROW" -> "minecraft:entity.arrow.shoot";
            case "ENDER_PEARL" -> "minecraft:entity.ender_pearl.throw";
            case "SNOWBALL" -> "minecraft:entity.snowball.throw";
            case "EGG" -> "minecraft:entity.egg.throw";
            case "TRIDENT" -> "minecraft:item.trident.throw";
            case "SPLASH_POTION", "LINGERING_POTION", "POTION" -> "minecraft:entity.splash_potion.throw";
            case "EXPERIENCE_BOTTLE", "THROWN_EXP_BOTTLE" -> "minecraft:entity.experience_bottle.throw";
            case "FISHING_BOBBER" -> "minecraft:entity.fishing_bobber.throw";
            case "WIND_CHARGE", "BREEZE_WIND_CHARGE" -> "minecraft:entity.wind_charge.throw";
            case "FIREBALL", "SMALL_FIREBALL" -> "minecraft:entity.blaze.shoot";
            default -> null;
        };
        snd(at, key, 1.0f, 1.0f);
    }

    private void endSound(String type, Location at) {
        if (at == null) return;
        if (type.startsWith("EXPERIENCE_ORB")) {
            // an orb that vanishes right next to a player was picked up
            for (PState ps : lastStates.values()) {
                if (world(ps.world()) != at.getWorld()) continue;
                double dx = ps.x() - at.getX(), dy = ps.y() + 0.9 - at.getY(), dz = ps.z() - at.getZ();
                if (dx * dx + dy * dy + dz * dz <= 6.25) {
                    snd(at, "minecraft:entity.experience_orb.pickup", 0.25f, 0.8f + (float) (Math.random() * 0.4));
                    return;
                }
            }
            return;
        }
        String key = switch (type) {
            case "ARROW", "SPECTRAL_ARROW", "TRIDENT" -> "minecraft:entity.arrow.hit";
            case "ENDER_PEARL" -> "minecraft:entity.player.teleport";
            case "SPLASH_POTION", "LINGERING_POTION", "POTION" -> "minecraft:entity.splash_potion.break";
            case "EXPERIENCE_BOTTLE", "THROWN_EXP_BOTTLE" -> "minecraft:entity.experience_bottle.throw";
            default -> null;
        };
        snd(at, key, 0.8f, 1.0f);
    }

    // ------------------------------------------------- inventory above heads

    public boolean toggleOverheadInv(Boolean on) {
        overheadInv = on == null ? !overheadInv : on;
        syncAllTags();
        return overheadInv;
    }

    public boolean toggleVoice(Boolean on) {
        voiceOn = on == null ? !voiceOn : on;
        return voiceOn;
    }

    /** 0 = voice works, otherwise the reason it cannot be heard. */
    public String voiceProblem() {
        if (plugin.voice() == null) return "Simple Voice Chat is not installed on this server.";
        if (!hasVoice) return "This replay has no voice recording (turn on recording.voice before saving replays).";
        return null;
    }

    /** One 20 ms voice packet, played from where the speaker was. Only at 1x speed, otherwise it would be garbled. */
    private void playVoice(Ev ev) {
        VoiceBridge vb = plugin.voice();
        if (vb == null || !voiceOn || paused || Math.abs(speed - 1.0) > 0.001) return;
        Location at = where(ev.a(), 1.6);
        if (at == null || at.getWorld() == null) return;
        Object ch = voiceCh.get(ev.a());
        if (ch == null) {
            ch = vb.open(UUID.randomUUID(), at, viewer.getUniqueId());
            if (ch == null) return;
            voiceCh.put(ev.a(), ch);
        }
        vb.send(ch, at, ev.data());
    }

    public boolean overheadInv() {
        return overheadInv;
    }

    /** Creates, moves or removes the floating inventory of every visible player. */
    private void syncAllTags() {
        for (Map.Entry<Integer, PState> en : new ArrayList<>(lastStates.entrySet())) {
            PState s = en.getValue();
            World w = world(s.world());
            if (w != null) syncTag(en.getKey(), s, w);
        }
        if (!overheadInv) {
            for (InvTagView t : invTags.values()) t.remove();
            invTags.clear();
        }
    }

    private void syncTag(int pid, PState s, World w) {
        InvTagView tag = invTags.get(pid);
        if (!overheadInv || pid == followPid) {
            if (tag != null) {
                tag.remove();
                invTags.remove(pid);
            }
            return;
        }
        double top = s.has(PState.SWIM) || s.has(PState.GLIDE) ? 1.4 : s.has(PState.SNEAK) ? 2.3 : 2.6;
        Location at = new Location(w, s.x(), s.y() + top, s.z());
        if (tag == null || !tag.isValid()) {
            if (tag != null) tag.remove();
            tag = new InvTagView(plugin, viewer, at);
            if (!tag.isValid()) {
                tag.remove();
                invTags.remove(pid);
                return;
            }
            invTags.put(pid, tag);
            tagDirty.add(pid);
        } else {
            tag.moveTo(at);
        }
    }

    private void refreshTags() {
        if (!overheadInv || tagDirty.isEmpty() || (tickCount % 3) != 0) return;
        for (int pid : new ArrayList<>(tagDirty)) refreshTag(pid);
        tagDirty.clear();
    }

    private void refreshTag(int pid) {
        InvTagView tag = invTags.get(pid);
        if (tag == null || !tag.isValid()) return;
        tag.update(inv.get(pid), held.getOrDefault(pid, 0));
    }

    // ------------------------------------------------------------- chat feed

    private void hitFeed(Ev ev, int f) {
        if (!st.hitFeed) return;
        String[] parts = ev.s().split("\\|", 2);
        String cause = parts[0];
        String detail = parts.length > 1 ? parts[1] : "";
        boolean crit = detail.contains("[CRIT]");
        if (crit) detail = detail.replace(" [CRIT]", "").replace("[CRIT]", "").trim();
        String what = detail.isEmpty() ? cause : detail;
        String attacker = ev.a() >= 0 ? nameOf(ev.a()) : "World";

        Component line = Component.text("[" + Fmt.time(f) + "] ", NamedTextColor.DARK_GRAY)
                .append(Component.text(attacker, NamedTextColor.WHITE))
                .append(Component.text(" -> ", NamedTextColor.GRAY))
                .append(Component.text(nameOf(ev.b()), NamedTextColor.WHITE))
                .append(Component.text("  " + Fmt.num1(ev.f()) + " dmg", NamedTextColor.RED));
        if (crit) line = line.append(Component.text("  CRIT", NamedTextColor.GOLD, net.kyori.adventure.text.format.TextDecoration.BOLD));
        String brief = attacker + " -> " + nameOf(ev.b()) + " " + Fmt.num1(ev.f()) + (crit ? " CRIT" : "");
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
