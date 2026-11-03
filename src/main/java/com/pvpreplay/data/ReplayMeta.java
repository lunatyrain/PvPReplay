package com.pvpreplay.data;

import java.util.List;

/** Small header read without decompressing the whole replay. Player skins are not included. */
public record ReplayMeta(String id, long created, String reason, int frames,
                         List<String> worlds, List<PlayerInfo> players, List<PlayerInfo> focus) {
    public int seconds() {
        return frames / 20;
    }
}
