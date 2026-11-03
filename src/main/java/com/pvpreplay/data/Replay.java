package com.pvpreplay.data;

import java.util.List;

/** A full replay. {@code players} is index-aligned with player ids (pid) used in frames/events. */
public record Replay(ReplayMeta meta, List<PlayerInfo> players, List<Frame> frames) {

    public int indexOf(java.util.UUID uuid) {
        for (int i = 0; i < players.size(); i++) {
            if (players.get(i).uuid().equals(uuid)) return i;
        }
        return -1;
    }

    public int indexOfName(String name) {
        for (int i = 0; i < players.size(); i++) {
            if (players.get(i).name().equalsIgnoreCase(name)) return i;
        }
        return -1;
    }
}
