package com.pvpreplay.record;

import com.pvpreplay.PvPReplayPlugin;
import com.pvpreplay.Settings;
import com.pvpreplay.data.PlayerInfo;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.scheduler.BukkitTask;

import java.util.*;

/**
 * Records whole fights, driven by the CombatTag timer.
 * The first time anybody becomes combat tagged a session starts and the rolling buffer stops discarding old frames.
 * As long as somebody is tagged the session keeps going. When nobody has been in combat for
 * combat-session.idle-seconds (5 minutes by default) the recording stops and is saved.
 * Without CombatTag a built-in timer based on PvP hits is used instead.
 */
public final class CombatSessions {
    private final PvPReplayPlugin plugin;
    private final Settings st;
    private final Recorder rec;
    private final CombatBridge bridge = new CombatBridge();

    private boolean active;
    private int startCutoff;
    private int lastCombatFrame;
    private long startMs;
    private long lastCombatMs;
    private final Map<UUID, String> names = new LinkedHashMap<>();
    private BukkitTask task;

    public CombatSessions(PvPReplayPlugin plugin, Settings st, Recorder rec) {
        this.plugin = plugin;
        this.st = st;
        this.rec = rec;
    }

    public void start() {
        task = Bukkit.getScheduler().runTaskTimer(plugin, this::tick, 20L, 20L);
    }

    /** Called on shutdown: an unfinished fight is saved right away. */
    public void stop() {
        if (task != null) task.cancel();
        task = null;
        if (active) finish(true);
    }

    public boolean active() {
        return active;
    }

    public String status() {
        if (!st.combatSession) return "Combat recording is turned off (combat-session.enabled).";
        if (!active) return "No fight is being recorded right now.";
        long now = System.currentTimeMillis();
        long idle = (now - lastCombatMs) / 1000L;
        return "Recording a fight for " + (now - startMs) / 1000L + "s with " + String.join(", ", names.values())
                + ". Out of combat for " + idle + "s (stops at " + st.combatIdleSeconds + "s).";
    }

    /** Ends the running session now and saves it. */
    public boolean forceEnd() {
        if (!active) return false;
        finish(false);
        return true;
    }

    private boolean inCombat(Player p) {
        Boolean t = bridge.tagged(p);
        if (t != null) return t;
        return rec.recentCombat(p.getUniqueId(), st.combatFallbackSeconds * 1000L);
    }

    private void tick() {
        if (!st.combatSession) {
            if (active) finish(false);
            return;
        }
        long now = System.currentTimeMillis();
        boolean any = false;
        for (Player p : Bukkit.getOnlinePlayers()) {
            if (!rec.tracked(p)) continue;
            if (inCombat(p)) {
                any = true;
                names.put(p.getUniqueId(), p.getName());
            }
        }
        if (any) {
            lastCombatMs = now;
            lastCombatFrame = rec.frameNo();
            if (!active) {
                active = true;
                startMs = now;
                startCutoff = rec.frameNo() - st.preSeconds * 20;
                rec.setHold(true);
                plugin.getLogger().info("Combat started: recording the fight until nobody has been in combat for "
                        + st.combatIdleSeconds + "s.");
            }
        }
        if (!active) return;
        boolean idle = now - lastCombatMs >= st.combatIdleSeconds * 1000L;
        boolean tooLong = now - startMs >= st.combatMaxSeconds * 1000L;
        if (idle || tooLong) finish(false);
    }

    private void finish(boolean sync) {
        rec.setHold(false);
        active = false;
        List<PlayerInfo> focus = new ArrayList<>();
        List<String> shown = new ArrayList<>();
        for (Map.Entry<UUID, String> e : names.entrySet()) {
            focus.add(new PlayerInfo(e.getKey(), e.getValue(), null, null));
            if (shown.size() < 4) shown.add(e.getValue());
        }
        names.clear();
        if (focus.isEmpty()) return;
        String reason = "Combat: " + String.join(", ", shown) + (focus.size() > shown.size() ? " +" + (focus.size() - shown.size()) : "");
        plugin.service().saveRange(reason, focus, startCutoff, lastCombatFrame + st.postSeconds * 20, sync);
    }
}
