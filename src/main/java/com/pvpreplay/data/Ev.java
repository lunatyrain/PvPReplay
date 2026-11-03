package com.pvpreplay.data;

/**
 * A discrete event inside one tick. Field meaning depends on the type:
 * <pre>
 * EQUIP       a=pid  b=slot(0 main,1 off,2 head,3 chest,4 legs,5 feet)  data=serialized item (empty = air)
 * SWING       a=pid  b=hand(0 main,1 off)
 * HIT         a=attacker pid (-1 unknown/none)  b=victim pid  f=damage  x=reach(-1 n/a)  y=attacker CPS  s="CAUSE|detail"
 * BLOCK       a=world idx  x,y,z=block coords  s=new block data string  data=previous block data (UTF-8, may be empty)
 * DEATH       a=pid  s=death message
 * PROJ_SPAWN  a=projectile id  s=entity type name
 * PROJ_POS    a=projectile id  b=world idx  x,y,z
 * PROJ_END    a=projectile id
 * INV         a=pid  b=inventory slot(0-8 hotbar, 9-35 main, 36 boots,37 legs,38 chest,39 helmet,40 offhand)  data=item (empty = air)
 * HELD        a=pid  b=selected hotbar slot
 * VITALS      a=pid  b=food  f=saturation  x=xp level  y=absorption  s=effects "name:amp:ticks;..."
 * MSG         a=pid  b=0 chat / 1 command  s=text
 * ITEM        a=pid  b=0 dropped / 1 picked up  f=amount  s=material
 * VOICE       a=pid  data=one Opus voice packet (20 ms) from Simple Voice Chat
 * TOTEM       a=pid  a totem of undying saved this player
 * </pre>
 */
public record Ev(byte type, int a, int b, double x, double y, double z, float f, String s, byte[] data) {
    public static final byte EQUIP = 1, SWING = 2, HIT = 3, BLOCK = 4, DEATH = 5,
            PROJ_SPAWN = 6, PROJ_POS = 7, PROJ_END = 8, INV = 9, HELD = 10, VITALS = 11, MSG = 12, ITEM = 13, VOICE = 14, TOTEM = 15;

    private static final byte[] NONE = new byte[0];

    public static Ev equip(int pid, int slot, byte[] item) {
        return new Ev(EQUIP, pid, slot, 0, 0, 0, 0, "", item == null ? NONE : item);
    }

    public static Ev swing(int pid, int hand) {
        return new Ev(SWING, pid, hand, 0, 0, 0, 0, "", NONE);
    }

    public static Ev hit(int attacker, int victim, float dmg, double reach, double cps, String desc) {
        return new Ev(HIT, attacker, victim, reach, cps, 0, dmg, desc == null ? "" : desc, NONE);
    }

    /** @param prior block data that was there before (may be empty); stored UTF-8 in {@code data} */
    public static Ev block(int world, int x, int y, int z, String blockData, String prior) {
        byte[] pb = prior == null || prior.isEmpty() ? NONE : prior.getBytes(java.nio.charset.StandardCharsets.UTF_8);
        return new Ev(BLOCK, world, 0, x, y, z, 0, blockData, pb);
    }

    /** The block data that was replaced (BLOCK events), or "". */
    public String priorBlock() {
        return data.length == 0 ? "" : new String(data, java.nio.charset.StandardCharsets.UTF_8);
    }

    public static Ev death(int pid, String message) {
        return new Ev(DEATH, pid, 0, 0, 0, 0, 0, message == null ? "" : message, NONE);
    }

    public static Ev projSpawn(int id, String type) {
        return new Ev(PROJ_SPAWN, id, 0, 0, 0, 0, 0, type, NONE);
    }

    public static Ev projPos(int id, int world, double x, double y, double z) {
        return new Ev(PROJ_POS, id, world, x, y, z, 0, "", NONE);
    }

    public static Ev projEnd(int id) {
        return new Ev(PROJ_END, id, 0, 0, 0, 0, 0, "", NONE);
    }

    public static Ev inv(int pid, int slot, byte[] item) {
        return new Ev(INV, pid, slot, 0, 0, 0, 0, "", item == null ? NONE : item);
    }

    public static Ev held(int pid, int slot) {
        return new Ev(HELD, pid, slot, 0, 0, 0, 0, "", NONE);
    }

    public static Ev vitals(int pid, int food, float saturation, float xpLevel, float absorption, String effects) {
        return new Ev(VITALS, pid, food, xpLevel, absorption, 0, saturation, effects == null ? "" : effects, NONE);
    }

    public static Ev msg(int pid, int kind, String text) {
        return new Ev(MSG, pid, kind, 0, 0, 0, 0, text == null ? "" : text, NONE);
    }

    public static Ev item(int pid, int kind, float amount, String material) {
        return new Ev(ITEM, pid, kind, 0, 0, 0, amount, material == null ? "" : material, NONE);
    }

    public static Ev voice(int pid, byte[] opus) {
        return new Ev(VOICE, pid, 0, 0, 0, 0, 0, "", opus == null ? NONE : opus);
    }

    public static Ev totem(int pid) {
        return new Ev(TOTEM, pid, 0, 0, 0, 0, 0, "", NONE);
    }

    /** Same event with a different player id in {@code a}. */
    public Ev withA(int newA) {
        return new Ev(type, newA, b, x, y, z, f, s, data);
    }

    /** True for event types whose {@code a} field is a player id. */
    public boolean aIsPlayer() {
        return type == EQUIP || type == SWING || type == DEATH || type == INV || type == HELD
                || type == VITALS || type == MSG || type == ITEM || type == HIT || type == VOICE || type == TOTEM;
    }
}
