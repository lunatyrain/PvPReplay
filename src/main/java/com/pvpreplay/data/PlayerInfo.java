package com.pvpreplay.data;

import java.util.UUID;

/** A player that appears in a replay. Skin fields may be null/empty. */
public record PlayerInfo(UUID uuid, String name, String skinValue, String skinSignature) {
    public boolean hasSkin() {
        return skinValue != null && !skinValue.isEmpty();
    }
}
