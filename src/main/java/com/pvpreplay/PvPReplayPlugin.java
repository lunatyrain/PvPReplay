package com.pvpreplay;

import com.pvpreplay.cmd.ReplayCommand;
import com.pvpreplay.play.InvGuiListener;
import com.pvpreplay.play.ReplayEntityGuard;
import com.pvpreplay.play.NpcFactory;
import com.pvpreplay.play.PlaybackManager;
import com.pvpreplay.play.VoiceBridge;
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
    private VoiceBridge voice;

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
        getServer().getPluginManager().registerEvents(new ReplayEntityGuard(this), this);
        ReplayCommand cmd = new ReplayCommand(this);
        if (getCommand("replay") != null) {
            getCommand("replay").setExecutor(cmd);
            getCommand("replay").setTabCompleter(cmd);
        }

        recorder.start();
        setupVoice();

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

    /** Hooks into Simple Voice Chat if it is installed. Done by reflection so the plugin loads fine without it. */
    private void setupVoice() {
        if (Bukkit.getPluginManager().getPlugin("voicechat") == null) return;
        try {
            Object hook = Class.forName("com.pvpreplay.voice.VoiceHook").getMethod("register", PvPReplayPlugin.class).invoke(null, this);
            if (hook instanceof VoiceBridge vb) {
                voice = vb;
                getLogger().info("Simple Voice Chat found: voice recording (recording.voice) and playback are available.");
            }
        } catch (Throwable t) {
            getLogger().warning("Simple Voice Chat is installed but the hook failed (" + t + "); voice is disabled.");
        }
    }

    /** Null when Simple Voice Chat is not available. */
    public VoiceBridge voice() {
        return voice;
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
