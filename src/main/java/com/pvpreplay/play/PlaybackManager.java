package com.pvpreplay.play;

import com.pvpreplay.Msg;
import com.pvpreplay.PvPReplayPlugin;
import com.pvpreplay.data.Replay;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.logging.Level;

/** Tracks who is watching what. */
public final class PlaybackManager {
    private final PvPReplayPlugin plugin;
    private final Map<UUID, PlaybackSession> sessions = new HashMap<>();

    public PlaybackManager(PvPReplayPlugin plugin) {
        this.plugin = plugin;
    }

    public PlaybackSession get(Player p) {
        return sessions.get(p.getUniqueId());
    }

    public void remove(UUID id) {
        sessions.remove(id);
    }

    public boolean isViewing(Player p) {
        return sessions.containsKey(p.getUniqueId());
    }

    /** Loads the replay off the main thread, then starts watching. */
    public void start(Player viewer, String id) {
        viewer.sendMessage(Msg.info("Loading replay " + id + "..."));
        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            try {
                Replay r = plugin.store().load(id);
                Bukkit.getScheduler().runTask(plugin, () -> {
                    if (!viewer.isOnline()) return;
                    stop(viewer, true);
                    PlaybackSession s = new PlaybackSession(plugin, viewer, r);
                    sessions.put(viewer.getUniqueId(), s);
                    try {
                        s.start();
                    } catch (RuntimeException ex) {
                        plugin.getLogger().log(Level.SEVERE, "Could not start playback of " + id, ex);
                        sessions.remove(viewer.getUniqueId());
                        s.stop(true);
                        viewer.sendMessage(Msg.err("Could not start the replay, see console."));
                    }
                });
            } catch (Exception e) {
                plugin.getLogger().log(Level.WARNING, "Could not load replay " + id, e);
                Bukkit.getScheduler().runTask(plugin, () -> viewer.sendMessage(Msg.err("Could not load replay " + id + ".")));
            }
        });
    }

    public void stop(Player viewer, boolean silent) {
        PlaybackSession s = sessions.remove(viewer.getUniqueId());
        if (s != null) s.stop(silent);
    }

    public void stopAll() {
        for (PlaybackSession s : new ArrayList<>(sessions.values())) s.stop(true);
        sessions.clear();
    }
}
