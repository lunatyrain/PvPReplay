package com.pvpreplay.cmd;

import com.pvpreplay.Msg;
import com.pvpreplay.PvPReplayPlugin;
import com.pvpreplay.Settings;
import com.pvpreplay.data.*;
import com.pvpreplay.play.PlaybackSession;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.HoverEvent;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextColor;
import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;

import java.util.*;
import java.util.logging.Level;

public final class ReplayCommand implements CommandExecutor, TabCompleter {
    private static final int PAGE = 8;
    private final PvPReplayPlugin plugin;

    public ReplayCommand(PvPReplayPlugin plugin) {
        this.plugin = plugin;
    }

    private Settings st() {
        return plugin.settings();
    }

    @Override
    public boolean onCommand(CommandSender s, Command c, String label, String[] a) {
        String sub = a.length == 0 ? "help" : a[0].toLowerCase(Locale.ROOT);
        switch (sub) {
            case "help", "?" -> help(s);
            case "list", "ls" -> list(s, a);
            case "play", "watch", "view" -> play(s, a);
            case "stop", "leave" -> {
                Player p = viewerOnly(s);
                if (p == null) return true;
                if (plugin.playback().get(p) == null) s.sendMessage(Msg.err("You are not watching a replay."));
                else plugin.playback().stop(p, false);
            }
            case "pause", "resume", "toggle" -> withSession(s, ses -> {
                switch (sub) {
                    case "pause" -> ses.pause();
                    case "resume" -> ses.resume();
                    default -> ses.toggle();
                }
                s.sendMessage(Msg.info(ses.isPaused() ? "Paused." : "Playing."));
            });
            case "speed" -> withSession(s, ses -> {
                if (a.length < 2) {
                    s.sendMessage(Msg.err("Usage: /replay speed <0.1-8>"));
                    return;
                }
                try {
                    ses.setSpeed(Double.parseDouble(a[1]));
                    s.sendMessage(Msg.info("Speed set to x" + Fmt.num(ses.speed()).replaceAll("0+$", "").replaceAll("\\.$", "")));
                } catch (NumberFormatException e) {
                    s.sendMessage(Msg.err("That is not a number."));
                }
            });
            case "seek" -> withSession(s, ses -> seek(s, ses, a));
            case "step" -> withSession(s, ses -> {
                int n = 1;
                if (a.length >= 2) {
                    try {
                        n = Integer.parseInt(a[1]);
                    } catch (NumberFormatException e) {
                        s.sendMessage(Msg.err("Usage: /replay step <ticks, can be negative>"));
                        return;
                    }
                }
                ses.step(n);
            });
            case "fight", "hit" -> withSession(s, ses -> {
                int dir = a.length >= 2 && a[1].toLowerCase(Locale.ROOT).startsWith("p") ? -1 : 1;
                s.sendMessage(Msg.info(ses.jumpFight(dir)));
            });
            case "follow" -> withSession(s, ses -> {
                if (a.length < 2) {
                    ses.freeCam();
                    s.sendMessage(Msg.info("Free camera."));
                    return;
                }
                int pid = ses.replay().indexOfName(a[1]);
                if (pid < 0 || !ses.follow(pid)) {
                    s.sendMessage(Msg.err("'" + a[1] + "' is not in this replay."));
                    return;
                }
                s.sendMessage(Msg.ok("Following " + ses.replay().players().get(pid).name() + ". Sneak to go back to free camera."));
            });
            case "controls" -> withSession(s, PlaybackSession::sendControls);
            case "flags", "sus", "suspicious" -> withSession(s, PlaybackSession::sendFlags);
            case "players" -> withSession(s, ses -> {
                if (a.length >= 2 && a[1].equalsIgnoreCase("list")) ses.sendPlayers();
                else ses.openPlayersMenu();
            });
            case "inv", "inventory" -> withSession(s, ses -> {
                String err = ses.openInventory(a.length >= 2 ? a[1] : null);
                if (err != null) s.sendMessage(Msg.err(err));
            });
            case "invtag", "invtags", "overhead", "tags" -> withSession(s, ses -> {
                boolean now = ses.toggleOverheadInv(a.length >= 2 ? onOff(a[1]) : null);
                s.sendMessage(Msg.info("Inventory above heads: " + (now ? "ON" : "OFF")));
            });
            case "sounds", "sound" -> withSession(s, ses -> {
                boolean now = ses.toggleSounds(a.length >= 2 ? onOff(a[1]) : null);
                s.sendMessage(Msg.info("Replay sounds: " + (now ? "ON" : "OFF")));
            });
            case "voice" -> withSession(s, ses -> {
                boolean now = ses.toggleVoice(a.length >= 2 ? onOff(a[1]) : null);
                s.sendMessage(Msg.info("Replay voice chat: " + (now ? "ON" : "OFF")));
                String problem = ses.voiceProblem();
                if (now && problem != null) s.sendMessage(Msg.err(problem));
            });
            case "info" -> info(s, a);
            case "save" -> save(s, a);
            case "flag" -> flag(s, a);
            case "delete", "remove" -> delete(s, a);
            case "deleteold", "purge", "prune" -> deleteOld(s, a);
            case "reload" -> {
                if (!s.hasPermission("pvpreplay.admin")) {
                    noPerm(s);
                    return true;
                }
                plugin.reloadConfig();
                st().load(plugin.getConfig());
                s.sendMessage(Msg.ok("Config reloaded."));
            }
            default -> help(s);
        }
        return true;
    }

    // ----------------------------------------------------------------- helpers

    /** "on"/"off" (also true/false/1/0) as a Boolean; null when it is neither, which means toggle. */
    private static Boolean onOff(String v) {
        return switch (v.toLowerCase(Locale.ROOT)) {
            case "on", "true", "yes", "1" -> Boolean.TRUE;
            case "off", "false", "no", "0" -> Boolean.FALSE;
            default -> null;
        };
    }

    private void noPerm(CommandSender s) {
        s.sendMessage(Msg.err("You do not have permission to do that."));
    }

    private Player viewerOnly(CommandSender s) {
        if (!s.hasPermission("pvpreplay.view")) {
            noPerm(s);
            return null;
        }
        if (!(s instanceof Player p)) {
            s.sendMessage(Msg.err("Only players can watch replays."));
            return null;
        }
        return p;
    }

    private void withSession(CommandSender s, java.util.function.Consumer<PlaybackSession> action) {
        Player p = viewerOnly(s);
        if (p == null) return;
        PlaybackSession ses = plugin.playback().get(p);
        if (ses == null) {
            s.sendMessage(Msg.err("You are not watching a replay. Use /replay list, then /replay play <id>."));
            return;
        }
        action.accept(ses);
    }

    private static Component line(String key, String value) {
        return Component.text(key + " ", NamedTextColor.GRAY).append(Component.text(value, NamedTextColor.WHITE));
    }

    // -------------------------------------------------------------------- help

    private void help(CommandSender s) {
        s.sendMessage(Msg.PREFIX.append(Component.text("Commands", NamedTextColor.WHITE)));
        if (s.hasPermission("pvpreplay.view")) {
            help(s, "/replay list [player] [page]", "Browse saved replays");
            help(s, "/replay play <id>", "Watch a replay (free camera)");
            help(s, "/replay info <id>", "Clicks per second, reach, damage and suspicious activity per player");
            help(s, "/replay flags", "While watching: list suspicious moments (click to jump there)");
            help(s, "/replay follow [player]", "See through a player's eyes; no name = free camera");
            help(s, "/replay inv [player]", "Open a player's inventory, food, effects as of right now");
            help(s, "/replay players", "List everyone in the replay with Follow / Inventory buttons");
            help(s, "/replay invtag [on|off]", "Show everyone's inventory floating above their head (live)");
            help(s, "/replay sounds [on|off]", "Turn the replay sounds on or off");
            help(s, "/replay voice [on|off]", "Hear the recorded voice chat (Simple Voice Chat, 1x speed only)");
            help(s, "/replay pause | resume | toggle", "Control playback");
            help(s, "/replay seek <+5|-5|1:20|30>", "Jump in time (seconds or m:ss)");
            help(s, "/replay step <ticks>", "Frame step while paused (negative = back)");
            help(s, "/replay speed <0.1-8>", "Playback speed");
            help(s, "/replay fight <next|prev>", "Jump between fights");
            help(s, "/replay stop", "Stop watching and go back");
        }
        if (s.hasPermission("pvpreplay.save")) {
            help(s, "/replay save [reason]", "Save the last " + st().preSeconds + "s as a replay");
            help(s, "/replay flag <player> [reason]", "Save a replay about a player (for anticheat commands)");
        }
        if (s.hasPermission("pvpreplay.admin")) {
            help(s, "/replay delete <id>", "Delete a replay");
            help(s, "/replay deleteold <age> [confirm]", "Delete replays older than e.g. 7d / 12h / 2w (shows a preview first)");
            help(s, "/replay reload", "Reload config.yml");
        }
    }

    private void help(CommandSender s, String cmd, String desc) {
        s.sendMessage(Component.text(cmd, NamedTextColor.AQUA).append(Component.text(" - " + desc, NamedTextColor.GRAY)));
    }

    // -------------------------------------------------------------------- list

    private void list(CommandSender s, String[] a) {
        if (!s.hasPermission("pvpreplay.view")) {
            noPerm(s);
            return;
        }
        String filter = null;
        int page = 1;
        if (a.length >= 2) {
            if (a[1].matches("\\d+")) page = Integer.parseInt(a[1]);
            else filter = a[1];
        }
        if (a.length >= 3 && a[2].matches("\\d+")) page = Integer.parseInt(a[2]);

        List<ReplayMeta> all = new ArrayList<>();
        for (ReplayMeta m : plugin.store().list()) {
            if (filter == null || involves(m, filter)) all.add(m);
        }
        if (all.isEmpty()) {
            s.sendMessage(Msg.info(filter == null ? "No replays saved yet." : "No replays involving '" + filter + "'."));
            return;
        }
        int pages = (all.size() + PAGE - 1) / PAGE;
        page = Math.max(1, Math.min(page, pages));
        s.sendMessage(Msg.PREFIX.append(Component.text("Replays" + (filter != null ? " for " + filter : "")
                + " (page " + page + "/" + pages + ", " + all.size() + " total)", NamedTextColor.WHITE)));
        for (int i = (page - 1) * PAGE; i < Math.min(all.size(), page * PAGE); i++) {
            ReplayMeta m = all.get(i);
            StringBuilder names = new StringBuilder();
            for (PlayerInfo p : m.players()) names.append(names.length() == 0 ? "" : ", ").append(p.name());
            Component id = Component.text("#" + m.id() + " ", NamedTextColor.WHITE)
                    .hoverEvent(HoverEvent.showText(Component.text("Players: " + names, NamedTextColor.GRAY)));
            Component row = id
                    .append(Component.text(Fmt.date(m.created()) + "  ", NamedTextColor.GRAY))
                    .append(Component.text(m.reason(), NamedTextColor.YELLOW))
                    .append(Component.text("  (" + Fmt.time(m.frames()) + ", " + m.players().size() + "p) ", NamedTextColor.DARK_GRAY))
                    .append(Msg.button("[Watch]", NamedTextColor.GREEN, "/replay play " + m.id(), "Watch this replay"))
                    .append(Component.text(" "))
                    .append(Msg.button("[Info]", NamedTextColor.AQUA, "/replay info " + m.id(), "CPS / reach / damage numbers"));
            s.sendMessage(row);
        }
        if (pages > 1) {
            String base = "/replay list " + (filter == null ? "" : filter + " ");
            Component nav = Component.empty();
            if (page > 1) nav = nav.append(Msg.button("[< Prev]", NamedTextColor.GOLD, base + (page - 1), "Previous page")).append(Component.text(" "));
            if (page < pages) nav = nav.append(Msg.button("[Next >]", NamedTextColor.GOLD, base + (page + 1), "Next page"));
            s.sendMessage(nav);
        }
    }

    private static boolean involves(ReplayMeta m, String name) {
        for (PlayerInfo p : m.focus()) if (p.name().equalsIgnoreCase(name)) return true;
        for (PlayerInfo p : m.players()) if (p.name().equalsIgnoreCase(name)) return true;
        return false;
    }

    // -------------------------------------------------------------------- play

    private void play(CommandSender s, String[] a) {
        Player p = viewerOnly(s);
        if (p == null) return;
        if (a.length < 2) {
            s.sendMessage(Msg.err("Usage: /replay play <id>"));
            return;
        }
        String id = a[1].replace("#", "").toLowerCase(Locale.ROOT);
        if (plugin.store().meta(id) == null) {
            s.sendMessage(Msg.err("No replay with id '" + id + "'. Use /replay list."));
            return;
        }
        plugin.playback().start(p, id);
    }

    private void seek(CommandSender s, PlaybackSession ses, String[] a) {
        if (a.length < 2) {
            s.sendMessage(Msg.err("Usage: /replay seek <+5 | -5 | 1:20 | 30>"));
            return;
        }
        String t = a[1];
        try {
            if (t.startsWith("+") || t.startsWith("-")) {
                ses.seekRelativeSeconds(Double.parseDouble(t));
            } else if (t.contains(":")) {
                String[] p = t.split(":", 2);
                ses.seekSeconds(Integer.parseInt(p[0]) * 60 + Double.parseDouble(p[1]));
            } else {
                ses.seekSeconds(Double.parseDouble(t));
            }
        } catch (NumberFormatException e) {
            s.sendMessage(Msg.err("Time must look like +5, -5, 30 or 1:20."));
        }
    }

    // -------------------------------------------------------------------- info

    private void info(CommandSender s, String[] a) {
        if (!s.hasPermission("pvpreplay.view")) {
            noPerm(s);
            return;
        }
        if (a.length < 2) {
            s.sendMessage(Msg.err("Usage: /replay info <id>"));
            return;
        }
        String id = a[1].replace("#", "").toLowerCase(Locale.ROOT);
        if (plugin.store().meta(id) == null) {
            s.sendMessage(Msg.err("No replay with id '" + id + "'."));
            return;
        }
        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            try {
                Replay r = plugin.store().load(id);
                List<ReplayStats.PlayerStats> stats = ReplayStats.compute(r);
                List<ReplayAnalyzer.Finding> sus = ReplayAnalyzer.analyze(r, st().reachWarn, st().reachBad, st().cpsWarn);
                Bukkit.getScheduler().runTask(plugin, () -> showInfo(s, r, stats, sus));
            } catch (Exception e) {
                plugin.getLogger().log(Level.WARNING, "info failed for " + id, e);
                Bukkit.getScheduler().runTask(plugin, () -> s.sendMessage(Msg.err("Could not read that replay.")));
            }
        });
    }

    private void showInfo(CommandSender s, Replay r, List<ReplayStats.PlayerStats> stats, List<ReplayAnalyzer.Finding> sus) {
        ReplayMeta m = r.meta();
        s.sendMessage(Msg.PREFIX.append(Component.text("Replay " + m.id(), NamedTextColor.WHITE)));
        s.sendMessage(line("Reason:", m.reason()));
        s.sendMessage(line("Saved:", Fmt.date(m.created()) + "   Length: " + Fmt.time(m.frames())));
        if (!m.focus().isEmpty()) {
            StringJoiner j = new StringJoiner(", ");
            for (PlayerInfo p : m.focus()) j.add(p.name());
            s.sendMessage(line("Focus:", j.toString()));
        }
        s.sendMessage(Component.text("Clicks = arm swings (includes mining). Reach is measured from the attacker's eyes to the "
                + "victim's hitbox when the hit landed, so allow some margin for lag.", NamedTextColor.DARK_GRAY));
        for (ReplayStats.PlayerStats p : stats) {
            if (p.swings() == 0 && p.hitsDealt() == 0 && p.hitsTaken() == 0) continue;
            TextColor cps = p.maxCps() >= st().cpsWarn ? NamedTextColor.RED : NamedTextColor.GREEN;
            TextColor reach = p.maxReach() >= st().reachBad ? NamedTextColor.RED
                    : p.maxReach() >= st().reachWarn ? NamedTextColor.YELLOW : NamedTextColor.GREEN;
            Component row = Component.text(p.player().name(), NamedTextColor.AQUA)
                    .append(Component.text("  max CPS ", NamedTextColor.GRAY))
                    .append(Component.text(String.valueOf(p.maxCps()), cps))
                    .append(Component.text("  swings " + p.swings(), NamedTextColor.GRAY))
                    .append(Component.text("  hits " + p.hitsDealt(), NamedTextColor.GRAY));
            if (p.hitsDealt() > 0 && p.maxReach() > 0) {
                row = row.append(Component.text("  reach avg " + Fmt.num(p.avgReach()) + " max ", NamedTextColor.GRAY))
                        .append(Component.text(Fmt.num(p.maxReach()), reach));
            }
            row = row.append(Component.text("  dmg " + Fmt.num1(p.damageDealt()) + "  taken " + p.hitsTaken()
                    + "  ping avg " + Math.round(p.avgPing()) + " max " + p.maxPing(), NamedTextColor.GRAY));
            s.sendMessage(row);
        }
        showSuspicion(s, r, sus);
        if (s instanceof Player) {
            s.sendMessage(Msg.button("[Watch this replay]", NamedTextColor.GREEN, "/replay play " + m.id(), "Watch this replay"));
        }
    }

    /** The "things worth a look" section of /replay info. */
    private void showSuspicion(CommandSender s, Replay r, List<ReplayAnalyzer.Finding> sus) {
        s.sendMessage(Component.text("Suspicious activity", NamedTextColor.GOLD));
        if (sus.isEmpty()) {
            s.sendMessage(Component.text("  Nothing stood out (reach, CPS, aim, crits, block placing/breaking).", NamedTextColor.GREEN));
            return;
        }
        for (int pid = 0; pid < r.players().size(); pid++) {
            int bad = 0, minor = 0;
            for (ReplayAnalyzer.Finding f : sus) {
                if (f.pid() != pid) continue;
                if (f.severity() == 2) bad++;
                else minor++;
            }
            if (bad + minor == 0) continue;
            s.sendMessage(Component.text("  " + r.players().get(pid).name() + ": ", NamedTextColor.AQUA)
                    .append(Component.text(bad + " serious", bad > 0 ? NamedTextColor.RED : NamedTextColor.GRAY))
                    .append(Component.text(", " + minor + " minor", minor > 0 ? NamedTextColor.YELLOW : NamedTextColor.GRAY)));
        }
        List<ReplayAnalyzer.Finding> ordered = new ArrayList<>(sus);
        ordered.sort((x, y) -> x.severity() != y.severity() ? y.severity() - x.severity() : x.frame() - y.frame());
        int shown = 0;
        for (ReplayAnalyzer.Finding f : ordered) {
            if (shown++ >= 15) {
                s.sendMessage(Component.text("  ... and " + (ordered.size() - 15) + " more minor ones. Watch the replay and use /replay flags.",
                        NamedTextColor.DARK_GRAY));
                break;
            }
            NamedTextColor c = f.severity() == 2 ? NamedTextColor.RED : NamedTextColor.YELLOW;
            String name = f.pid() >= 0 && f.pid() < r.players().size() ? r.players().get(f.pid()).name() : "?";
            boolean rate = f.kind().endsWith("_RATE");
            s.sendMessage(Component.text("  [" + Fmt.time(f.frame()) + "] ", NamedTextColor.DARK_GRAY)
                    .append(Component.text(name + " ", NamedTextColor.WHITE))
                    .append(Component.text(f.label() + (f.count() > 1 && !rate ? " x" + f.count() : ""), c))
                    .append(Component.text(" (" + f.detail() + ")", NamedTextColor.GRAY)));
        }
        s.sendMessage(Component.text("These are leads, not proof. Lag and knockback can cause false hits, and block actions are credited "
                + "to the nearest recorded player.", NamedTextColor.DARK_GRAY));
    }

    // ------------------------------------------------------------- save / flag

    private void save(CommandSender s, String[] a) {
        if (!s.hasPermission("pvpreplay.save")) {
            noPerm(s);
            return;
        }
        String reason = a.length > 1 ? String.join(" ", Arrays.copyOfRange(a, 1, a.length)) : "";
        List<Player> focus = s instanceof Player p ? List.of(p) : List.of();
        plugin.service().trigger(reason.isBlank() ? "Manual save" : "Manual: " + reason, focus,
                st().preSeconds, st().postSeconds, null, 0, s);
    }

    private void flag(CommandSender s, String[] a) {
        if (!s.hasPermission("pvpreplay.save")) {
            noPerm(s);
            return;
        }
        if (a.length < 2) {
            s.sendMessage(Msg.err("Usage: /replay flag <player> [reason]"));
            return;
        }
        Player target = Bukkit.getPlayerExact(a[1]);
        if (target == null) {
            s.sendMessage(Msg.err("Player '" + a[1] + "' is not online."));
            return;
        }
        String reason = a.length > 2 ? String.join(" ", Arrays.copyOfRange(a, 2, a.length)) : "flagged";
        boolean ok = plugin.service().trigger("Flag: " + target.getName() + " - " + reason, List.of(target),
                st().flagPreSeconds, st().flagPostSeconds, "flag:" + target.getUniqueId(), st().flagCooldown, s);
        if (!ok) s.sendMessage(Msg.info("A replay for " + target.getName() + " was saved very recently, skipping."));
    }

    private void delete(CommandSender s, String[] a) {
        if (!s.hasPermission("pvpreplay.admin")) {
            noPerm(s);
            return;
        }
        if (a.length < 2) {
            s.sendMessage(Msg.err("Usage: /replay delete <id>"));
            return;
        }
        String id = a[1].replace("#", "").toLowerCase(Locale.ROOT);
        s.sendMessage(plugin.store().delete(id) ? Msg.ok("Deleted " + id + ".") : Msg.err("No replay with id '" + id + "'."));
    }

    /** "12h", "7d", "2w" or a bare number of days, in milliseconds; -1 if it cannot be read. */
    private static long parseAge(String in) {
        String t = in.toLowerCase(Locale.ROOT).trim();
        if (t.isEmpty()) return -1;
        long unit = 86_400_000L;
        char last = t.charAt(t.length() - 1);
        if (Character.isLetter(last)) {
            switch (last) {
                case 'h' -> unit = 3_600_000L;
                case 'd' -> unit = 86_400_000L;
                case 'w' -> unit = 7 * 86_400_000L;
                case 'm' -> unit = 30 * 86_400_000L;
                default -> {
                    return -1;
                }
            }
            t = t.substring(0, t.length() - 1);
        }
        try {
            long n = Long.parseLong(t);
            return n <= 0 || n > 36500 ? -1 : n * unit;
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    private static String bytes(long b) {
        if (b < 1024) return b + " B";
        if (b < 1024L * 1024) return String.format(Locale.ROOT, "%.1f KB", b / 1024.0);
        if (b < 1024L * 1024 * 1024) return String.format(Locale.ROOT, "%.1f MB", b / 1048576.0);
        return String.format(Locale.ROOT, "%.2f GB", b / 1073741824.0);
    }

    /** /replay deleteold <age> [confirm]: without "confirm" it only shows what would be deleted. */
    private void deleteOld(CommandSender s, String[] a) {
        if (!s.hasPermission("pvpreplay.admin")) {
            noPerm(s);
            return;
        }
        long age = a.length >= 2 ? parseAge(a[1]) : -1;
        if (age < 0) {
            s.sendMessage(Msg.err("Usage: /replay deleteold <age> [confirm]   e.g. 7d, 12h, 2w, 1m (m = 30 days)"));
            return;
        }
        boolean confirm = a.length >= 3 && a[2].equalsIgnoreCase("confirm");
        String ageText = a[1];
        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            List<ReplayMeta> old = plugin.store().olderThan(age);
            long total = 0;
            for (ReplayMeta m : old) total += plugin.store().sizeOf(m.id());
            if (old.isEmpty()) {
                Bukkit.getScheduler().runTask(plugin, () -> s.sendMessage(Msg.info("No replays are older than " + ageText + ".")));
                return;
            }
            if (!confirm) {
                final long size = total;
                final int n = old.size();
                Bukkit.getScheduler().runTask(plugin, () -> {
                    s.sendMessage(Msg.info(n + " replay" + (n == 1 ? "" : "s") + " older than " + ageText + " (" + bytes(size) + ")."));
                    s.sendMessage(Msg.PREFIX.append(Msg.button("[Delete them]", NamedTextColor.RED,
                            "/replay deleteold " + ageText + " confirm", "Permanently delete these replays")));
                });
                return;
            }
            int removed = 0;
            long freed = 0;
            for (ReplayMeta m : old) {
                long sz = plugin.store().sizeOf(m.id());
                if (plugin.store().delete(m.id())) {
                    removed++;
                    freed += sz;
                }
            }
            final int r = removed;
            final long f = freed;
            Bukkit.getScheduler().runTask(plugin, () -> s.sendMessage(Msg.ok("Deleted " + r + " replay" + (r == 1 ? "" : "s")
                    + " older than " + ageText + ", freed " + bytes(f) + ".")));
        });
    }

    // ------------------------------------------------------------ tab complete

    @Override
    public List<String> onTabComplete(CommandSender s, Command c, String alias, String[] a) {
        List<String> out = new ArrayList<>();
        if (a.length == 1) {
            List<String> subs = new ArrayList<>();
            if (s.hasPermission("pvpreplay.view")) {
                subs.addAll(List.of("list", "play", "info", "follow", "pause", "resume", "toggle", "seek", "step", "speed",
                        "fight", "stop", "controls", "flags", "inv", "players", "invtag", "sounds", "voice"));
            }
            if (s.hasPermission("pvpreplay.save")) subs.addAll(List.of("save", "flag"));
            if (s.hasPermission("pvpreplay.admin")) subs.addAll(List.of("delete", "deleteold", "reload"));
            subs.add("help");
            return filter(subs, a[0]);
        }
        String sub = a[0].toLowerCase(Locale.ROOT);
        if (a.length == 2) {
            switch (sub) {
                case "play", "watch", "view", "info", "delete", "remove" -> {
                    if (s.hasPermission("pvpreplay.view")) {
                        int n = 0;
                        for (ReplayMeta m : plugin.store().list()) {
                            out.add(m.id());
                            if (++n >= 50) break;
                        }
                    }
                }
                case "list", "ls" -> {
                    for (Player p : Bukkit.getOnlinePlayers()) out.add(p.getName());
                }
                case "flag" -> {
                    for (Player p : Bukkit.getOnlinePlayers()) out.add(p.getName());
                }
                case "speed" -> out.addAll(List.of("0.25", "0.5", "1", "2", "4"));
                case "deleteold", "purge", "prune" -> out.addAll(List.of("1d", "3d", "7d", "14d", "30d"));
                case "invtag", "invtags", "overhead", "tags", "sounds", "sound", "voice" -> out.addAll(List.of("on", "off"));
                case "fight", "hit" -> out.addAll(List.of("next", "prev"));
                case "seek" -> out.addAll(List.of("+5", "-5", "+10", "-10", "0"));
                case "follow", "inv", "inventory" -> {
                    if (s instanceof Player p && plugin.playback().get(p) != null) {
                        for (PlayerInfo pi : plugin.playback().get(p).replay().players()) out.add(pi.name());
                    }
                }
                default -> {
                }
            }
            return filter(out, a[1]);
        }
        if (a.length == 3 && (sub.equals("deleteold") || sub.equals("purge") || sub.equals("prune"))) return filter(List.of("confirm"), a[2]);
        if (a.length == 3 && (sub.equals("list") || sub.equals("ls"))) return List.of();
        return List.of();
    }

    private static List<String> filter(List<String> in, String prefix) {
        String p = prefix.toLowerCase(Locale.ROOT);
        List<String> out = new ArrayList<>();
        for (String x : in) if (x.toLowerCase(Locale.ROOT).startsWith(p)) out.add(x);
        return out;
    }
}
