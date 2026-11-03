package com.pvpreplay;

import com.pvpreplay.data.PlayerInfo;
import com.pvpreplay.data.Replay;
import com.pvpreplay.data.ReplayCompactor;
import com.pvpreplay.data.ReplayMeta;
import com.pvpreplay.record.Recorder;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

import java.util.*;
import java.util.logging.Level;

/** Turns "something happened" (a PvP death, a flag, a manual save) into a saved replay file. */
public final class ReplayService {
    private final PvPReplayPlugin plugin;
    private final Settings st;
    private final Recorder recorder;
    private final ReplayStore store;
    private final Map<String, Long> cooldowns = new HashMap<>();

    public ReplayService(PvPReplayPlugin plugin, Settings st, Recorder recorder, ReplayStore store) {
        this.plugin = plugin;
        this.st = st;
        this.recorder = recorder;
        this.store = store;
    }

    /**
     * Saves the last {@code pre} seconds plus {@code post} seconds after now.
     *
     * @param cooldownKey  identical keys are ignored for {@code cooldownSec} seconds (null = no cooldown)
     * @param requester    who gets a confirmation message (may be null)
     * @return false if ignored because of the cooldown
     */
    public boolean trigger(String reason, List<Player> focus, int pre, int post,
                           String cooldownKey, int cooldownSec, CommandSender requester) {
        long now = System.currentTimeMillis();
        if (cooldownKey != null && cooldownSec > 0) {
            Long until = cooldowns.get(cooldownKey);
            if (until != null && until > now) return false;
            cooldowns.put(cooldownKey, now + cooldownSec * 1000L);
            if (cooldowns.size() > 500) cooldowns.values().removeIf(t -> t < now);
        }

        final int cutoff = recorder.frameNo() - pre * 20;
        final List<PlayerInfo> focusInfos = new ArrayList<>();
        for (Player p : focus) {
            focusInfos.add(new PlayerInfo(p.getUniqueId(), p.getName(), null, null));
        }
        final String why = reason == null || reason.isBlank() ? "Saved replay" : reason;
        final long created = now;

        Runnable snap = () -> {
            Recorder.Snapshot snapshot = recorder.snapshot(cutoff);
            if (snapshot.frames().isEmpty()) {
                if (requester != null) requester.sendMessage(Msg.err("Nothing was recorded yet."));
                return;
            }
            final String id = store.newId();
            Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
                try {
                    Replay r = ReplayCompactor.compact(id, created, why, snapshot.worlds(), snapshot.players(),
                            snapshot.frames(), focusInfos);
                    store.save(r);
                    Bukkit.getScheduler().runTask(plugin, () -> announce(r.meta(), requester));
                } catch (Exception e) {
                    plugin.getLogger().log(Level.SEVERE, "Failed to save replay " + id, e);
                    if (requester != null) {
                        Bukkit.getScheduler().runTask(plugin, () -> requester.sendMessage(Msg.err("Saving failed, see console.")));
                    }
                }
            });
        };

        if (post <= 0) snap.run();
        else Bukkit.getScheduler().runTaskLater(plugin, snap, post * 20L);

        if (requester != null) {
            requester.sendMessage(Msg.info("Saving replay (" + pre + "s before + " + post + "s after)..."));
        }
        return true;
    }

    private void announce(ReplayMeta m, CommandSender requester) {
        plugin.getLogger().info("Saved replay " + m.id() + " (" + m.reason() + ", " + m.seconds() + "s)");
        Component line = Msg.PREFIX
                .append(Component.text("Saved ", NamedTextColor.GRAY))
                .append(Component.text(m.id(), NamedTextColor.WHITE))
                .append(Component.text(" - " + m.reason() + " ", NamedTextColor.GRAY))
                .append(Msg.button("[Watch]", NamedTextColor.GREEN, "/replay play " + m.id(), "Watch this replay"));
        boolean staffGetsIt = st.notifyStaff && requester instanceof Player rp && rp.hasPermission("pvpreplay.notify");
        if (requester != null && !staffGetsIt) {
            requester.sendMessage(line);
        }
        if (st.notifyStaff) {
            for (Player p : Bukkit.getOnlinePlayers()) {
                if (p.hasPermission("pvpreplay.notify")) p.sendMessage(line);
            }
        }
    }
}
