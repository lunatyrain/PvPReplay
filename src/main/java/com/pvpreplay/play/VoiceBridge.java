package com.pvpreplay.play;

import org.bukkit.Location;

import java.util.UUID;

/** What playback needs from Simple Voice Chat. Kept free of voice chat classes so it is safe to load without it. */
public interface VoiceBridge {
    /** Opens an audio channel that only {@code viewer} can hear, at the given spot. Returns null on failure. */
    Object open(UUID channelId, Location at, UUID viewer);

    /** Moves the channel to {@code at} and plays one Opus packet. */
    void send(Object channel, Location at, byte[] opus);
}
