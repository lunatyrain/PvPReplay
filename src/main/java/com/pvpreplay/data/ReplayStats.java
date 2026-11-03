package com.pvpreplay.data;

import java.util.ArrayList;
import java.util.List;

/** Per-player numbers for reviewing a replay (clicks per second, reach, damage). */
public final class ReplayStats {
    private ReplayStats() {
    }

    public record PlayerStats(PlayerInfo player, int swings, int maxCps, int hitsDealt, double avgReach,
                              double maxReach, double maxHitCps, double damageDealt, int hitsTaken,
                              double avgPing, int maxPing) {
    }

    public static List<PlayerStats> compute(Replay r) {
        int n = r.players().size();
        List<List<Integer>> swingFrames = new ArrayList<>();
        for (int i = 0; i < n; i++) swingFrames.add(new ArrayList<>());
        int[] hitsDealt = new int[n], hitsTaken = new int[n], reachCount = new int[n], maxPing = new int[n];
        double[] reachSum = new double[n], maxReach = new double[n], maxHitCps = new double[n],
                dmg = new double[n], pingSum = new double[n];
        long[] pingN = new long[n];

        for (int fi = 0; fi < r.frames().size(); fi++) {
            Frame f = r.frames().get(fi);
            for (PState s : f.states()) {
                if (s.pid() < 0 || s.pid() >= n) continue;
                pingSum[s.pid()] += s.ping();
                pingN[s.pid()]++;
                maxPing[s.pid()] = Math.max(maxPing[s.pid()], s.ping());
            }
            for (Ev e : f.events()) {
                if (e.type() == Ev.SWING && e.a() >= 0 && e.a() < n) {
                    swingFrames.get(e.a()).add(fi);
                } else if (e.type() == Ev.HIT) {
                    if (e.b() >= 0 && e.b() < n) hitsTaken[e.b()]++;
                    int a = e.a();
                    if (a >= 0 && a < n) {
                        hitsDealt[a]++;
                        dmg[a] += e.f();
                        maxHitCps[a] = Math.max(maxHitCps[a], e.y());
                        if (e.x() >= 0) {
                            reachSum[a] += e.x();
                            reachCount[a]++;
                            maxReach[a] = Math.max(maxReach[a], e.x());
                        }
                    }
                }
            }
        }

        List<PlayerStats> out = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            out.add(new PlayerStats(r.players().get(i), swingFrames.get(i).size(), maxCps(swingFrames.get(i)),
                    hitsDealt[i], reachCount[i] == 0 ? 0 : reachSum[i] / reachCount[i], maxReach[i], maxHitCps[i],
                    dmg[i], hitsTaken[i], pingN[i] == 0 ? 0 : pingSum[i] / pingN[i], maxPing[i]));
        }
        return out;
    }

    /** Highest number of swings inside any 20-tick (1 second) window. */
    static int maxCps(List<Integer> frames) {
        int best = 0, lo = 0;
        for (int hi = 0; hi < frames.size(); hi++) {
            while (frames.get(hi) - frames.get(lo) >= 20) lo++;
            best = Math.max(best, hi - lo + 1);
        }
        return best;
    }
}
