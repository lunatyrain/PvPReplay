package com.pvpreplay.data;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Looks through a replay for things a moderator should review: long reach, very high CPS, hitting someone you are not
 * looking at, hitting several players at once, critical hits without any jump, and suspicious block placing / breaking.
 * Everything here is a lead, not proof: lag, knockback and the nearest-player guess for blocks can all cause false hits.
 */
public final class ReplayAnalyzer {
    private ReplayAnalyzer() {
    }

    /** One thing worth a look. {@code severity} is 1 (minor) or 2 (serious); {@code count} merges repeats close together. */
    public record Finding(int frame, int pid, String kind, int severity, int count, String detail) {
        public String label() {
            return switch (kind) {
                case "REACH" -> "Long reach";
                case "CPS" -> "High CPS";
                case "AIM" -> "Hit without aiming at target";
                case "MULTI" -> "Hit several players at once";
                case "CRIT" -> "Crit without jumping";
                case "CRIT_RATE" -> "Almost every hit a crit";
                case "BLOCK_REACH" -> "Block changed from far away";
                case "BLOCK_LOOK" -> "Placed blocks without looking at them";
                case "PLACE_RATE" -> "Very fast block placing";
                case "BREAK_RATE" -> "Mass block breaking";
                default -> kind;
            };
        }
    }

    private record Raw(int frame, int pid, String kind, int sev, double score, String detail) {
    }

    private static final double EYE = 1.62, EYE_SNEAK = 1.27;

    public static List<Finding> analyze(Replay r, double reachWarn, double reachBad, int cpsWarn) {
        int n = r.players().size();
        int nf = r.frames().size();
        PState[][] sf = new PState[nf][n];
        for (int fi = 0; fi < nf; fi++) {
            for (PState s : r.frames().get(fi).states()) {
                if (s.pid() >= 0 && s.pid() < n) sf[fi][s.pid()] = s;
            }
        }

        List<Raw> raw = new ArrayList<>();
        int[] melee = new int[n], crits = new int[n], firstMelee = new int[n];
        java.util.Arrays.fill(firstMelee, -1);
        Map<Integer, ArrayDeque<int[]>> recent = new HashMap<>(); // attacker -> [frame, victim]
        List<List<Integer>> places = new ArrayList<>(), breaks = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            places.add(new ArrayList<>());
            breaks.add(new ArrayList<>());
        }

        for (int fi = 0; fi < nf; fi++) {
            for (Ev ev : r.frames().get(fi).events()) {
                if (ev.type() == Ev.HIT) {
                    hit(ev, fi, sf, n, reachWarn, reachBad, cpsWarn, raw, melee, crits, firstMelee, recent);
                } else if (ev.type() == Ev.BLOCK) {
                    block(ev, fi, sf, n, raw, places, breaks);
                }
            }
        }

        for (int p = 0; p < n; p++) {
            if (melee[p] >= 8 && crits[p] >= melee[p] * 0.85) {
                raw.add(new Raw(firstMelee[p], p, "CRIT_RATE", 1, crits[p],
                        crits[p] + " of " + melee[p] + " melee hits were crits"));
            }
            rate(places.get(p), p, "PLACE_RATE", 9, 12, "blocks placed", raw);
            rate(breaks.get(p), p, "BREAK_RATE", 10, 14, "blocks broken", raw);
        }
        return merge(raw);
    }

    // ------------------------------------------------------------------ hits

    private static void hit(Ev ev, int fi, PState[][] sf, int n, double reachWarn, double reachBad, int cpsWarn,
                            List<Raw> raw, int[] melee, int[] crits, int[] firstMelee,
                            Map<Integer, ArrayDeque<int[]>> recent) {
        int a = ev.a(), v = ev.b();
        if (a < 0 || a >= n || v < 0 || v >= n || a == v) return;
        if (!ev.s().startsWith("ENTITY_ATTACK")) return; // arrows, sweeps, explosions... are not melee
        melee[a]++;
        if (firstMelee[a] < 0) firstMelee[a] = fi;

        if (ev.x() >= reachWarn) {
            int sev = ev.x() >= reachBad ? 2 : 1;
            raw.add(new Raw(fi, a, "REACH", sev, ev.x(), "reach " + Fmt.num(ev.x())));
        }
        if (ev.y() >= cpsWarn) {
            int sev = ev.y() >= cpsWarn + 6 ? 2 : 1;
            raw.add(new Raw(fi, a, "CPS", sev, ev.y(), "CPS " + (int) ev.y()));
        }

        PState as = sf[fi][a], vs = sf[fi][v];
        if (as != null && vs != null && as.world() == vs.world()) {
            double ex = as.x(), ey = as.y() + eye(as), ez = as.z();
            double dx = vs.x() - ex, dy = vs.y() + 0.9 - ey, dz = vs.z() - ez;
            double dist = Math.sqrt(dx * dx + dy * dy + dz * dz);
            if (dist >= 1.2) {
                double ang = angleTo(as, dx, dy, dz, dist);
                if (ang >= 55) raw.add(new Raw(fi, a, "AIM", 2, ang, "hit someone " + Math.round(ang) + "\u00b0 away from where they looked"));
                else if (ang >= 35) raw.add(new Raw(fi, a, "AIM", 1, ang, "hit someone " + Math.round(ang) + "\u00b0 away from where they looked"));
            }
        }

        // several different players inside 8 ticks (0.4 s)
        ArrayDeque<int[]> q = recent.computeIfAbsent(a, k -> new ArrayDeque<>());
        q.addLast(new int[]{fi, v});
        while (!q.isEmpty() && fi - q.peekFirst()[0] > 8) q.removeFirst();
        java.util.Set<Integer> victims = new java.util.HashSet<>();
        for (int[] x : q) victims.add(x[1]);
        if (victims.size() >= 3) {
            raw.add(new Raw(fi, a, "MULTI", 2, victims.size(), "hit " + victims.size() + " different players within 0.4 s"));
        }

        if (ev.s().contains("[CRIT")) {
            crits[a]++;
            if (as != null && as.has(PState.GROUND) && flat(sf, a, fi, 8)) {
                raw.add(new Raw(fi, a, "CRIT", 2, 1, "crit while standing on the ground, no jump or fall"));
            }
        }
    }

    /** True if the player's height did not change over the last {@code back} frames (and they were recorded for all of them). */
    private static boolean flat(PState[][] sf, int pid, int fi, int back) {
        if (fi < back) return false;
        double lo = Double.MAX_VALUE, hi = -Double.MAX_VALUE;
        for (int i = fi - back; i <= fi; i++) {
            PState s = sf[i][pid];
            if (s == null) return false;
            lo = Math.min(lo, s.y());
            hi = Math.max(hi, s.y());
        }
        return hi - lo < 0.03;
    }

    // ---------------------------------------------------------------- blocks

    private static void block(Ev ev, int fi, PState[][] sf, int n, List<Raw> raw,
                              List<List<Integer>> places, List<List<Integer>> breaks) {
        String now = ev.s() == null ? "" : ev.s();
        String prior = ev.priorBlock();
        if (liquid(now) || liquid(prior) || now.startsWith("minecraft:fire") || now.startsWith("minecraft:soul_fire")) return;
        boolean broke = isAir(now);

        double cx = ev.x() + 0.5, cy = ev.y() + 0.5, cz = ev.z() + 0.5;
        int best = -1;
        double bestD = 12.0;
        for (int p = 0; p < n; p++) {
            PState s = sf[fi][p];
            if (s == null || s.world() != ev.a()) continue;
            double d = dist(s.x(), s.y() + eye(s), s.z(), cx, cy, cz);
            if (d < bestD) {
                bestD = d;
                best = p;
            }
        }
        if (best < 0) return; // nobody recorded close enough to credit
        PState s = sf[fi][best];
        double ex = s.x(), ey = s.y() + eye(s), ez = s.z();
        double dx = cx - ex, dy = cy - ey, dz = cz - ez;

        (broke ? breaks : places).get(best).add(fi);

        if (bestD > 6.5) {
            raw.add(new Raw(fi, best, "BLOCK_REACH", 2, bestD,
                    (broke ? "broke" : "placed") + " a block " + Fmt.num1(bestD) + " blocks away"));
        }
        if (!broke && bestD >= 1.5) {
            double ang = angleTo(s, dx, dy, dz, bestD);
            if (ang >= 85) raw.add(new Raw(fi, best, "BLOCK_LOOK", 2, ang, "placed a block " + Math.round(ang) + "\u00b0 away from where they looked"));
            else if (ang >= 65) raw.add(new Raw(fi, best, "BLOCK_LOOK", 1, ang, "placed a block " + Math.round(ang) + "\u00b0 away from where they looked"));
        }
    }

    private static boolean isAir(String data) {
        return data.matches("minecraft:(void_|cave_)?air(\\[.*)?");
    }

    private static boolean liquid(String data) {
        return data.startsWith("minecraft:water") || data.startsWith("minecraft:lava");
    }

    /** Emits a finding for every moment {@code warnAt} or more events happened inside one second. */
    private static void rate(List<Integer> fr, int pid, String kind, int warnAt, int badAt, String what, List<Raw> out) {
        int lo = 0;
        for (int hi = 0; hi < fr.size(); hi++) {
            while (fr.get(hi) - fr.get(lo) >= 20) lo++;
            int c = hi - lo + 1;
            if (c >= warnAt) {
                out.add(new Raw(fr.get(lo), pid, kind, c >= badAt ? 2 : 1, c, c + " " + what + " within 1 second"));
            }
        }
    }

    // ------------------------------------------------------------------ math

    private static double eye(PState s) {
        return s.has(PState.SNEAK) ? EYE_SNEAK : EYE;
    }

    private static double dist(double ax, double ay, double az, double bx, double by, double bz) {
        double dx = ax - bx, dy = ay - by, dz = az - bz;
        return Math.sqrt(dx * dx + dy * dy + dz * dz);
    }

    /** Angle in degrees between where the player looks and the direction (dx,dy,dz) of length {@code len}. */
    private static double angleTo(PState s, double dx, double dy, double dz, double len) {
        double yaw = Math.toRadians(s.yaw()), pitch = Math.toRadians(s.pitch());
        double lx = -Math.sin(yaw) * Math.cos(pitch), ly = -Math.sin(pitch), lz = Math.cos(yaw) * Math.cos(pitch);
        double dot = (lx * dx + ly * dy + lz * dz) / len;
        return Math.toDegrees(Math.acos(Math.max(-1.0, Math.min(1.0, dot))));
    }

    // ----------------------------------------------------------------- merge

    /** How many repeats are needed before something is worth showing (single lag spikes are ignored). */
    private static int minCount(String kind, int sev) {
        return switch (kind) {
            case "AIM", "BLOCK_LOOK" -> sev >= 2 ? 2 : 3;
            case "REACH", "CPS" -> sev >= 2 ? 1 : 3;
            default -> 1;
        };
    }

    private static List<Finding> merge(List<Raw> raw) {
        Map<String, List<Raw>> groups = new HashMap<>();
        for (Raw r : raw) groups.computeIfAbsent(r.pid() + ":" + r.kind(), k -> new ArrayList<>()).add(r);
        List<Finding> out = new ArrayList<>();
        for (List<Raw> g : groups.values()) {
            g.sort(Comparator.comparingInt(Raw::frame));
            List<Raw> cluster = new ArrayList<>();
            for (Raw r : g) {
                if (!cluster.isEmpty() && r.frame() - cluster.get(cluster.size() - 1).frame() > 60) {
                    flush(cluster, out);
                    cluster = new ArrayList<>();
                }
                cluster.add(r);
            }
            flush(cluster, out);
        }
        out.sort(Comparator.comparingInt(Finding::frame));
        return out;
    }

    private static void flush(List<Raw> cluster, List<Finding> out) {
        if (cluster.isEmpty()) return;
        Raw best = cluster.get(0);
        int sev = 1;
        for (Raw r : cluster) {
            sev = Math.max(sev, r.sev());
            if (r.sev() > best.sev() || (r.sev() == best.sev() && r.score() > best.score())) best = r;
        }
        // rate findings repeat for every frame of a burst; their count is the burst size, not the number of raws
        boolean burst = best.kind().endsWith("_RATE");
        int count = burst ? (int) best.score() : cluster.size();
        if (!burst && cluster.size() < minCount(best.kind(), sev)) return;
        out.add(new Finding(cluster.get(0).frame(), best.pid(), best.kind(), sev, count, best.detail()));
    }

    /** A rough "how suspicious" number for one player, for ordering. */
    public static int score(List<Finding> all, int pid) {
        int s = 0;
        for (Finding f : all) {
            if (f.pid() == pid) s += f.severity() == 2 ? 3 : 1;
        }
        return s;
    }
}
