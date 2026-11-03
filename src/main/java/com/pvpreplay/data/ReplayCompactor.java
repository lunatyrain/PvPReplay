package com.pvpreplay.data;

import java.util.*;

/**
 * Drops players who never appear in the selected frames and renumbers the rest, so saved replays
 * only mention players who were actually present. Pure function; safe to run on any thread.
 */
public final class ReplayCompactor {
    private ReplayCompactor() {
    }

    /**
     * @param allPlayers full pid-indexed table at snapshot time
     * @param focus      players to highlight (kept in the table even if absent from the frames)
     */
    public static Replay compact(String id, long created, String reason, List<String> worlds,
                                 List<PlayerInfo> allPlayers, List<Frame> frames, List<PlayerInfo> focus) {
        Set<Integer> used = new TreeSet<>();
        for (Frame f : frames) {
            for (PState s : f.states()) used.add(s.pid());
            for (Ev e : f.events()) {
                for (int pid : pids(e)) if (pid >= 0) used.add(pid);
            }
        }
        for (PlayerInfo fp : focus) {
            for (int i = 0; i < allPlayers.size(); i++) {
                if (allPlayers.get(i).uuid().equals(fp.uuid())) used.add(i);
            }
        }
        used.removeIf(p -> p >= allPlayers.size());

        Map<Integer, Integer> map = new HashMap<>();
        List<PlayerInfo> players = new ArrayList<>();
        for (int old : used) {
            map.put(old, players.size());
            players.add(allPlayers.get(old));
        }

        List<Frame> out = new ArrayList<>(frames.size());
        for (Frame f : frames) {
            PState[] st = new PState[f.states().length];
            for (int i = 0; i < st.length; i++) {
                PState s = f.states()[i];
                st[i] = new PState(map.get(s.pid()), s.world(), s.x(), s.y(), s.z(), s.yaw(), s.pitch(),
                        s.flags(), s.health(), s.ping());
            }
            Ev[] ev = new Ev[f.events().length];
            for (int i = 0; i < ev.length; i++) ev[i] = remap(f.events()[i], map);
            out.add(new Frame(f.key(), st, ev));
        }

        List<PlayerInfo> plainPlayers = players.stream()
                .map(p -> new PlayerInfo(p.uuid(), p.name(), null, null)).toList();
        ReplayMeta meta = new ReplayMeta(id, created, reason, out.size(), List.copyOf(worlds), plainPlayers, List.copyOf(focus));
        return new Replay(meta, List.copyOf(players), out);
    }

    private static int[] pids(Ev e) {
        if (e.type() == Ev.HIT) return new int[]{e.a(), e.b()};
        if (e.aIsPlayer()) return new int[]{e.a()};
        return new int[0];
    }

    private static Ev remap(Ev e, Map<Integer, Integer> map) {
        if (e.type() == Ev.HIT) {
            return Ev.hit(e.a() < 0 ? -1 : map.getOrDefault(e.a(), -1), map.getOrDefault(e.b(), -1),
                    e.f(), e.x(), e.y(), e.s());
        }
        if (e.aIsPlayer()) return e.withA(map.getOrDefault(e.a(), 0));
        return e;
    }
}
