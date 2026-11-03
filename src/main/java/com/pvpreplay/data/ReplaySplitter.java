package com.pvpreplay.data;

import java.util.*;

/**
 * Splits one recording into separate fights. Players who were never within {@code dist} blocks of each other (same
 * world) and never hit each other end up in different groups, so two fights in different areas become two replays.
 * Pure function; safe to run on any thread.
 */
public final class ReplaySplitter {
    private ReplaySplitter() {
    }

    public record Group(List<Frame> frames, List<PlayerInfo> focus) {
    }

    private static final double MARGIN = 64.0;

    public static List<Group> split(List<Frame> frames, List<PlayerInfo> all, List<PlayerInfo> focus, double dist) {
        List<Group> single = List.of(new Group(frames, focus));
        if (dist <= 0 || frames.isEmpty()) return single;
        int n = all.size();
        int[] parent = new int[n];
        for (int i = 0; i < n; i++) parent[i] = i;
        boolean[] seen = new boolean[n];
        double d2 = dist * dist;

        for (int fi = 0; fi < frames.size(); fi++) {
            Frame f = frames.get(fi);
            PState[] st = f.states();
            for (PState s : st) if (s.pid() >= 0 && s.pid() < n) seen[s.pid()] = true;
            for (Ev e : f.events()) {
                if (e.type() == Ev.HIT && e.a() >= 0 && e.a() < n && e.b() >= 0 && e.b() < n) union(parent, e.a(), e.b());
            }
            if (fi % 10 != 0) continue; // twice a second is plenty
            for (int i = 0; i < st.length; i++) {
                for (int j = i + 1; j < st.length; j++) {
                    PState a = st[i], b = st[j];
                    if (a.pid() < 0 || a.pid() >= n || b.pid() < 0 || b.pid() >= n) continue;
                    if (a.world() != b.world()) continue;
                    double dx = a.x() - b.x(), dy = a.y() - b.y(), dz = a.z() - b.z();
                    if (dx * dx + dy * dy + dz * dz <= d2) union(parent, a.pid(), b.pid());
                }
            }
        }

        // which fighters belong to which group
        Map<Integer, List<PlayerInfo>> byRoot = new LinkedHashMap<>();
        for (PlayerInfo fp : focus) {
            for (int i = 0; i < n; i++) {
                if (seen[i] && all.get(i).uuid().equals(fp.uuid())) {
                    byRoot.computeIfAbsent(find(parent, i), k -> new ArrayList<>()).add(fp);
                    break;
                }
            }
        }
        if (byRoot.size() < 2) return single;

        List<Group> out = new ArrayList<>();
        for (Map.Entry<Integer, List<PlayerInfo>> en : byRoot.entrySet()) {
            int root = en.getKey();
            Set<Integer> members = new HashSet<>();
            for (int i = 0; i < n; i++) if (seen[i] && find(parent, i) == root) members.add(i);
            out.add(new Group(filter(frames, members), en.getValue()));
        }
        return out;
    }

    private static List<Frame> filter(List<Frame> frames, Set<Integer> members) {
        // bounding box per world around this group (for blocks and projectiles)
        Map<Integer, double[]> box = new HashMap<>();
        for (Frame f : frames) {
            for (PState s : f.states()) {
                if (!members.contains(s.pid())) continue;
                double[] b = box.computeIfAbsent(s.world(), k -> new double[]{1e18, 1e18, -1e18, -1e18});
                b[0] = Math.min(b[0], s.x());
                b[1] = Math.min(b[1], s.z());
                b[2] = Math.max(b[2], s.x());
                b[3] = Math.max(b[3], s.z());
            }
        }
        Set<Integer> projs = new HashSet<>();
        for (Frame f : frames) {
            for (Ev e : f.events()) {
                if (e.type() == Ev.PROJ_POS && inBox(box, e.b(), e.x(), e.z())) projs.add(e.a());
            }
        }
        List<Frame> out = new ArrayList<>(frames.size());
        for (Frame f : frames) {
            List<PState> st = new ArrayList<>();
            for (PState s : f.states()) if (members.contains(s.pid())) st.add(s);
            List<Ev> ev = new ArrayList<>();
            for (Ev e : f.events()) {
                boolean keep = switch (e.type()) {
                    case Ev.HIT -> members.contains(e.b());
                    case Ev.BLOCK -> inBox(box, e.a(), e.x(), e.z());
                    case Ev.PROJ_SPAWN, Ev.PROJ_POS, Ev.PROJ_END -> projs.contains(e.a());
                    default -> !e.aIsPlayer() || members.contains(e.a());
                };
                if (keep) ev.add(e);
            }
            out.add(new Frame(f.key(), st.toArray(new PState[0]), ev.toArray(new Ev[0])));
        }
        return out;
    }

    private static boolean inBox(Map<Integer, double[]> box, int world, double x, double z) {
        double[] b = box.get(world);
        return b != null && x >= b[0] - MARGIN && x <= b[2] + MARGIN && z >= b[1] - MARGIN && z <= b[3] + MARGIN;
    }

    private static int find(int[] p, int x) {
        while (p[x] != x) {
            p[x] = p[p[x]];
            x = p[x];
        }
        return x;
    }

    private static void union(int[] p, int a, int b) {
        int ra = find(p, a), rb = find(p, b);
        if (ra != rb) p[rb] = ra;
    }
}
