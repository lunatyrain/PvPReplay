package com.pvpreplay.voice;

import com.pvpreplay.PvPReplayPlugin;
import com.pvpreplay.play.VoiceBridge;
import de.maxhenkel.voicechat.api.BukkitVoicechatService;
import de.maxhenkel.voicechat.api.VoicechatConnection;
import de.maxhenkel.voicechat.api.VoicechatPlugin;
import de.maxhenkel.voicechat.api.VoicechatServerApi;
import de.maxhenkel.voicechat.api.audiochannel.LocationalAudioChannel;
import de.maxhenkel.voicechat.api.events.EventRegistration;
import de.maxhenkel.voicechat.api.events.MicrophonePacketEvent;
import de.maxhenkel.voicechat.api.events.VoicechatServerStartedEvent;
import org.bukkit.Location;
import org.bukkit.entity.Player;

import java.util.UUID;

/**
 * The only class that touches the Simple Voice Chat API. It is loaded by reflection from
 * {@link PvPReplayPlugin}, and only when the voicechat plugin is present.
 */
public final class VoiceHook implements VoicechatPlugin, VoiceBridge {
    private final PvPReplayPlugin plugin;
    private volatile VoicechatServerApi server;

    private VoiceHook(PvPReplayPlugin plugin) {
        this.plugin = plugin;
    }

    /** Called by reflection. Registers the hook with Simple Voice Chat and returns it. */
    public static Object register(PvPReplayPlugin plugin) {
        BukkitVoicechatService service = plugin.getServer().getServicesManager().load(BukkitVoicechatService.class);
        if (service == null) throw new IllegalStateException("Simple Voice Chat service not available");
        VoiceHook hook = new VoiceHook(plugin);
        service.registerPlugin(hook);
        return hook;
    }

    @Override
    public String getPluginId() {
        return "pvpreplay";
    }

    @Override
    public void registerEvents(EventRegistration registration) {
        registration.registerEvent(VoicechatServerStartedEvent.class, e -> server = e.getVoicechat());
        registration.registerEvent(MicrophonePacketEvent.class, this::onMic);
    }

    /** Runs on Simple Voice Chat's thread. */
    private void onMic(MicrophonePacketEvent e) {
        if (!plugin.settings().recordVoice) return;
        VoicechatConnection con = e.getSenderConnection();
        if (con == null || con.isDisabled()) return;
        if (con.isInGroup() && !plugin.settings().recordVoiceGroups) return;
        Object p = con.getPlayer() == null ? null : con.getPlayer().getPlayer();
        if (!(p instanceof Player player)) return;
        plugin.recorder().onVoice(player.getUniqueId(), e.getPacket().getOpusEncodedData());
    }

    @Override
    public Object open(UUID channelId, Location at, UUID viewer) {
        VoicechatServerApi api = server;
        if (api == null || at.getWorld() == null) return null;
        try {
            LocationalAudioChannel ch = api.createLocationalAudioChannel(channelId, api.fromServerLevel(at.getWorld()),
                    api.createPosition(at.getX(), at.getY(), at.getZ()));
            if (ch == null) return null;
            ch.setFilter(p -> p.getUuid().equals(viewer));
            return ch;
        } catch (Throwable t) {
            return null;
        }
    }

    @Override
    public void send(Object channel, Location at, byte[] opus) {
        VoicechatServerApi api = server;
        if (api == null || !(channel instanceof LocationalAudioChannel ch)) return;
        try {
            ch.updateLocation(api.createPosition(at.getX(), at.getY(), at.getZ()));
            ch.send(opus);
        } catch (Throwable ignored) {
            // voice is optional
        }
    }
}
