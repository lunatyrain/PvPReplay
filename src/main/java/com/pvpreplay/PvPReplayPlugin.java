package com.pvpreplay;

import com.pvpreplay.cmd.ReplayCommand;
import com.pvpreplay.play.InvGuiListener;
import com.pvpreplay.play.NpcFactory;
import com.pvpreplay.play.PlaybackManager;
import com.pvpreplay.record.RecordListener;
import com.pvpreplay.record.Recorder;
import org.bukkit.Bukkit;
import org.bukkit.NamespacedKey;
import org.bukkit.World;
import org.bukkit.entity.Entity;
import org.bukkit.plugin.java.JavaPlugin;

public final class PvPReplayPlugin extends JavaPlugin {
    private Settings settings;
    private Recorder recorder;
    private ReplayStore store;
    private ReplayService service;
    private PlaybackManager playback;
    private NamespacedKey viewKey;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        settings = new Settings();
        settings.load(getConfig());
        viewKey = new NamespacedKey(this, "viewing");

        store = new ReplayStore(this, settings);
        recorder = new Recorder(this, settings);
        service = new ReplayService(this, settings, recorder, store);
        playback = new PlaybackManager(this);

        getServer().getPluginManager().registerEvents(new RecordListener(this), this);
        getServer().getPluginManager().registerEvents(new InvGuiListener(), this);
        ReplayCommand cmd = new ReplayCommand(this);
        if (getCommand("replay") != null) {
            getCommand("replay").setExecutor(cmd);
            getCommand("replay").setTabCompleter(cmd);
        }

        recorder.start();

        // Index existing replays and clean up old ones without blocking the main thread.
        Bukkit.getScheduler().runTaskAsynchronously(this, () -> {
            store.loadIndex();
            int removed = store.cleanup();
            if (removed > 0) getLogger().info("Removed " + removed + " old replays.");
        });
        // Daily cleanup (20 ticks * 60 * 60 * 24).
        Bukkit.getScheduler().runTaskTimerAsynchronously(this, store::cleanup, 20L * 3600, 20L * 3600 * 24);

        // Remove replay mannequins left over from a crash.
        for (World w : Bukkit.getWorlds()) {
            for (Entity e : w.getEntities()) {
                if (NpcFactory.isReplayEntity(this, e)) e.remove();
            }
        }
        getLogger().info("PvPReplay enabled: recording the last " + settings.bufferSeconds + "s of every player.");
    }

    @Override
    public void onDisable() {
        if (playback != null) playback.stopAll();
        if (recorder != null) recorder.stop();
    }

    public Settings settings() {
        return settings;
    }

    public Recorder recorder() {
        return recorder;
    }

    public ReplayStore store() {
        return store;
    }

    public ReplayService service() {
        return service;
    }

    public PlaybackManager playback() {
        return playback;
    }

    public NamespacedKey viewKey() {
        return viewKey;
    }
}
