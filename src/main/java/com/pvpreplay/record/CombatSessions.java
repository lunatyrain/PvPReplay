package com.pvpreplay.record;

/**
 * Nothing uses this any more. Fight recording (a replay that lasts as long as the fight and ends after
 * {@code fights.idle-seconds} without a hit) now lives in {@link Recorder}. The class stays only so that an
 * old copy of this file in a repository gets replaced by something that compiles; it is safe to delete.
 */
final class CombatSessions {
    private CombatSessions() {
    }
}
