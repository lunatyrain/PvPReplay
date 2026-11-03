package com.pvpreplay;

import org.bukkit.configuration.file.FileConfiguration;

import java.util.HashSet;
import java.util.Locale;
import java.util.Set;

/** Values from config.yml, loaded once on enable/reload. */
public final class Settings {
    public int bufferSeconds;
    public int preSeconds;
    public int postSeconds;

    public boolean autoSavePvpDeath;
    public int autoSaveCooldown;

    public int flagPreSeconds;
    public int flagPostSeconds;
    public int flagCooldown;

    public int maxReplays;
    public int maxAgeDays;

    public Set<String> excludedWorlds = new HashSet<>();
    public boolean recordBlocks;
    public boolean recordProjectiles;
    public boolean recordCreative;
    public boolean recordChat;
    public boolean recordCommands;

    public boolean useSkins;
    public boolean hitFeed;
    public boolean chatFeed;
    public boolean blockHighlight;
    public boolean shieldDisplay;
    public boolean actionBar;
    public double defaultSpeed;
    public double reachWarn;
    public double reachBad;
    public int cpsWarn;
    public boolean notifyStaff;

    public void load(FileConfiguration c) {
        bufferSeconds = clamp(c.getInt("recording.buffer-seconds", 60), 20, 300);
        preSeconds = clamp(c.getInt("save.pre-seconds", 30), 5, 240);
        postSeconds = clamp(c.getInt("save.post-seconds", 5), 0, 60);
        flagPreSeconds = clamp(c.getInt("flag.pre-seconds", 30), 5, 240);
        flagPostSeconds = clamp(c.getInt("flag.post-seconds", 10), 0, 60);

        // The rolling buffer must be able to hold "pre + post" plus one segment (5s) of slack.
        int need = Math.max(preSeconds + postSeconds, flagPreSeconds + flagPostSeconds) + 10;
        if (bufferSeconds < need) bufferSeconds = Math.min(300, need);

        autoSavePvpDeath = c.getBoolean("auto-save.on-pvp-death", true);
        autoSaveCooldown = clamp(c.getInt("auto-save.cooldown-seconds", 30), 0, 3600);
        flagCooldown = clamp(c.getInt("flag.cooldown-seconds", 20), 0, 3600);

        maxReplays = clamp(c.getInt("storage.max-replays", 200), 1, 100000);
        maxAgeDays = clamp(c.getInt("storage.max-age-days", 14), 0, 3650);

        excludedWorlds = new HashSet<>();
        for (String w : c.getStringList("recording.exclude-worlds")) excludedWorlds.add(w.toLowerCase(Locale.ROOT));
        recordBlocks = c.getBoolean("recording.blocks", true);
        recordProjectiles = c.getBoolean("recording.projectiles", true);
        recordCreative = c.getBoolean("recording.creative-players", true);
        recordChat = c.getBoolean("recording.chat", true);
        recordCommands = c.getBoolean("recording.commands", true);

        useSkins = c.getBoolean("playback.use-skins", true);
        hitFeed = c.getBoolean("playback.hit-feed", true);
        chatFeed = c.getBoolean("playback.chat-feed", true);
        blockHighlight = c.getBoolean("playback.block-highlight", true);
        shieldDisplay = c.getBoolean("playback.shield-display", false);
        actionBar = c.getBoolean("playback.action-bar", true);
        defaultSpeed = Math.max(0.1, Math.min(8.0, c.getDouble("playback.default-speed", 1.0)));
        reachWarn = c.getDouble("playback.reach-warn", 3.05);
        reachBad = c.getDouble("playback.reach-bad", 3.3);
        cpsWarn = c.getInt("playback.cps-warn", 14);
        notifyStaff = c.getBoolean("notify-staff", true);
    }

    private static int clamp(int v, int lo, int hi) {
        return Math.max(lo, Math.min(hi, v));
    }
}
