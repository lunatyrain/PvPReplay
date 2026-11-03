package com.pvpreplay.data;

/** One player's state in one tick. Immutable. */
public record PState(int pid, int world, double x, double y, double z,
                     float yaw, float pitch, int flags, float health, int ping) {
    public static final int SNEAK = 1;
    public static final int SPRINT = 2;
    public static final int SWIM = 4;
    public static final int GLIDE = 8;
    public static final int BLOCK = 16;
    public static final int GROUND = 32;
    public static final int USING = 64; // eating, drinking, drawing a bow, blocking...
    public static final int USING_OFF = 128; // ...and the raised hand is the off hand (e.g. a shield)

    public boolean has(int flag) {
        return (flags & flag) != 0;
    }
}
