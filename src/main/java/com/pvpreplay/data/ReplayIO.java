package com.pvpreplay.data;

import java.io.*;
import java.nio.file.*;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;

/**
 * Binary replay file (.pvr).
 * <pre>
 * [uncompressed header] magic, version, id, created, reason, frames, worlds, players(uuid,name), focus(uuid,name)
 * [gzip body]           per-player skin (value, signature), frame count, frames
 * </pre>
 * The header can be read on its own so listing replays is cheap.
 */
public final class ReplayIO {
    private ReplayIO() {
    }

    private static final int MAGIC = 0x50565031; // "PVP1"
    private static final short VERSION = 1;
    private static final int MAX_FRAMES = 2_000_000;
    private static final int MAX_LIST = 100_000;
    private static final int MAX_BYTES = 4 * 1024 * 1024;

    // ---------------------------------------------------------------- write

    public static void write(Replay r, Path file) throws IOException {
        Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
        try (OutputStream fos = Files.newOutputStream(tmp);
             BufferedOutputStream bos = new BufferedOutputStream(fos, 1 << 16)) {
            DataOutputStream h = new DataOutputStream(bos);
            ReplayMeta m = r.meta();
            h.writeInt(MAGIC);
            h.writeShort(VERSION);
            h.writeUTF(m.id());
            h.writeLong(m.created());
            h.writeUTF(clip(m.reason(), 300));
            h.writeInt(r.frames().size());
            h.writeShort(m.worlds().size());
            for (String w : m.worlds()) h.writeUTF(w);
            writePeople(h, r.players());
            writePeople(h, m.focus());
            h.flush();

            GZIPOutputStream gz = new GZIPOutputStream(bos, 1 << 16);
            DataOutputStream b = new DataOutputStream(new BufferedOutputStream(gz, 1 << 16));
            for (PlayerInfo p : r.players()) {
                b.writeUTF(safeSkin(p.skinValue()));
                b.writeUTF(safeSkin(p.skinSignature()));
            }
            b.writeInt(r.frames().size());
            for (Frame f : r.frames()) writeFrame(b, f);
            b.flush();
            gz.finish();
        }
        try {
            Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException e) {
            Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private static void writePeople(DataOutputStream o, List<PlayerInfo> list) throws IOException {
        o.writeShort(list.size());
        for (PlayerInfo p : list) {
            o.writeLong(p.uuid().getMostSignificantBits());
            o.writeLong(p.uuid().getLeastSignificantBits());
            o.writeUTF(clip(p.name(), 32));
        }
    }

    private static void writeFrame(DataOutputStream o, Frame f) throws IOException {
        o.writeBoolean(f.key());
        o.writeShort(f.states().length);
        for (PState s : f.states()) {
            o.writeShort(s.pid());
            o.writeByte(s.world());
            o.writeDouble(s.x());
            o.writeDouble(s.y());
            o.writeDouble(s.z());
            o.writeFloat(s.yaw());
            o.writeFloat(s.pitch());
            o.writeByte(s.flags());
            o.writeFloat(s.health());
            o.writeShort(s.ping());
        }
        o.writeInt(f.events().length);
        for (Ev e : f.events()) writeEv(o, e);
    }

    private static void writeEv(DataOutputStream o, Ev e) throws IOException {
        o.writeByte(e.type());
        switch (e.type()) {
            case Ev.EQUIP -> {
                o.writeShort(e.a());
                o.writeByte(e.b());
                o.writeInt(e.data().length);
                o.write(e.data());
            }
            case Ev.SWING -> {
                o.writeShort(e.a());
                o.writeByte(e.b());
            }
            case Ev.HIT -> {
                o.writeShort(e.a());
                o.writeShort(e.b());
                o.writeFloat(e.f());
                o.writeFloat((float) e.x());
                o.writeFloat((float) e.y());
                o.writeUTF(clip(e.s(), 200));
            }
            case Ev.BLOCK -> {
                o.writeByte(e.a());
                o.writeInt((int) e.x());
                o.writeInt((int) e.y());
                o.writeInt((int) e.z());
                o.writeUTF(clip(e.s(), 1000));
                o.writeUTF(clip(e.priorBlock(), 1000));
            }
            case Ev.DEATH -> {
                o.writeShort(e.a());
                o.writeUTF(clip(e.s(), 300));
            }
            case Ev.PROJ_SPAWN -> {
                o.writeInt(e.a());
                o.writeUTF(clip(e.s(), 64));
            }
            case Ev.PROJ_POS -> {
                o.writeInt(e.a());
                o.writeByte(e.b());
                o.writeDouble(e.x());
                o.writeDouble(e.y());
                o.writeDouble(e.z());
            }
            case Ev.PROJ_END -> o.writeInt(e.a());
            case Ev.INV -> {
                o.writeShort(e.a());
                o.writeByte(e.b());
                o.writeInt(e.data().length);
                o.write(e.data());
            }
            case Ev.HELD -> {
                o.writeShort(e.a());
                o.writeByte(e.b());
            }
            case Ev.VITALS -> {
                o.writeShort(e.a());
                o.writeByte(e.b());
                o.writeFloat(e.f());
                o.writeFloat((float) e.x());
                o.writeFloat((float) e.y());
                o.writeUTF(clip(e.s(), 600));
            }
            case Ev.MSG -> {
                o.writeShort(e.a());
                o.writeByte(e.b());
                o.writeUTF(clip(e.s(), 300));
            }
            case Ev.ITEM -> {
                o.writeShort(e.a());
                o.writeByte(e.b());
                o.writeFloat(e.f());
                o.writeUTF(clip(e.s(), 64));
            }
            default -> throw new IOException("Unknown event type " + e.type());
        }
    }

    // ----------------------------------------------------------------- read

    public static ReplayMeta readMeta(Path file) throws IOException {
        try (InputStream in = new BufferedInputStream(Files.newInputStream(file), 1 << 14)) {
            return readHeader(new DataInputStream(in));
        }
    }

    public static Replay read(Path file) throws IOException {
        try (InputStream in = new BufferedInputStream(Files.newInputStream(file), 1 << 16)) {
            DataInputStream h = new DataInputStream(in);
            ReplayMeta meta = readHeader(h);
            DataInputStream b = new DataInputStream(new BufferedInputStream(new GZIPInputStream(in, 1 << 16), 1 << 16));

            List<PlayerInfo> players = new ArrayList<>();
            for (PlayerInfo p : meta.players()) {
                String value = b.readUTF();
                String sig = b.readUTF();
                players.add(new PlayerInfo(p.uuid(), p.name(), value.isEmpty() ? null : value, sig.isEmpty() ? null : sig));
            }
            int n = b.readInt();
            if (n < 0 || n > MAX_FRAMES) throw new IOException("Bad frame count " + n);
            List<Frame> frames = new ArrayList<>(n);
            for (int i = 0; i < n; i++) frames.add(readFrame(b));
            return new Replay(meta, List.copyOf(players), frames);
        }
    }

    private static ReplayMeta readHeader(DataInputStream h) throws IOException {
        if (h.readInt() != MAGIC) throw new IOException("Not a PvPReplay file");
        short ver = h.readShort();
        if (ver != VERSION) throw new IOException("Unsupported replay version " + ver);
        String id = h.readUTF();
        long created = h.readLong();
        String reason = h.readUTF();
        int frames = h.readInt();
        int wn = h.readShort();
        List<String> worlds = new ArrayList<>();
        for (int i = 0; i < wn; i++) worlds.add(h.readUTF());
        List<PlayerInfo> players = readPeople(h);
        List<PlayerInfo> focus = readPeople(h);
        return new ReplayMeta(id, created, reason, frames, List.copyOf(worlds), List.copyOf(players), List.copyOf(focus));
    }

    private static List<PlayerInfo> readPeople(DataInputStream i) throws IOException {
        int n = i.readShort() & 0xFFFF;
        List<PlayerInfo> out = new ArrayList<>(n);
        for (int k = 0; k < n; k++) {
            long msb = i.readLong();
            long lsb = i.readLong();
            out.add(new PlayerInfo(new UUID(msb, lsb), i.readUTF(), null, null));
        }
        return out;
    }

    private static Frame readFrame(DataInputStream i) throws IOException {
        boolean key = i.readBoolean();
        int sn = i.readShort() & 0xFFFF;
        PState[] states = new PState[sn];
        for (int k = 0; k < sn; k++) {
            states[k] = new PState(i.readShort(), i.readByte(), i.readDouble(), i.readDouble(), i.readDouble(),
                    i.readFloat(), i.readFloat(), i.readByte() & 0xFF, i.readFloat(), i.readShort());
        }
        int en = i.readInt();
        if (en < 0 || en > MAX_LIST) throw new IOException("Bad event count " + en);
        Ev[] events = new Ev[en];
        for (int k = 0; k < en; k++) events[k] = readEv(i);
        return new Frame(key, states, events);
    }

    private static Ev readEv(DataInputStream i) throws IOException {
        byte t = i.readByte();
        return switch (t) {
            case Ev.EQUIP -> {
                int pid = i.readShort();
                int slot = i.readByte();
                int len = i.readInt();
                if (len < 0 || len > MAX_BYTES) throw new IOException("Bad item length " + len);
                byte[] d = new byte[len];
                i.readFully(d);
                yield Ev.equip(pid, slot, d);
            }
            case Ev.SWING -> Ev.swing(i.readShort(), i.readByte());
            case Ev.HIT -> {
                int a = i.readShort();
                int b = i.readShort();
                float dmg = i.readFloat();
                float reach = i.readFloat();
                float cps = i.readFloat();
                yield Ev.hit(a, b, dmg, reach, cps, i.readUTF());
            }
            case Ev.BLOCK -> {
                int w = i.readByte();
                int x = i.readInt();
                int y = i.readInt();
                int z = i.readInt();
                String now = i.readUTF();
                yield Ev.block(w, x, y, z, now, i.readUTF());
            }
            case Ev.DEATH -> Ev.death(i.readShort(), i.readUTF());
            case Ev.PROJ_SPAWN -> Ev.projSpawn(i.readInt(), i.readUTF());
            case Ev.PROJ_POS -> Ev.projPos(i.readInt(), i.readByte(), i.readDouble(), i.readDouble(), i.readDouble());
            case Ev.PROJ_END -> Ev.projEnd(i.readInt());
            case Ev.INV -> {
                int pid = i.readShort();
                int slot = i.readByte();
                int len = i.readInt();
                if (len < 0 || len > MAX_BYTES) throw new IOException("Bad item length " + len);
                byte[] d = new byte[len];
                i.readFully(d);
                yield Ev.inv(pid, slot, d);
            }
            case Ev.HELD -> Ev.held(i.readShort(), i.readByte());
            case Ev.VITALS -> {
                int pid = i.readShort();
                int food = i.readByte();
                float sat = i.readFloat();
                float xp = i.readFloat();
                float abs = i.readFloat();
                yield Ev.vitals(pid, food, sat, xp, abs, i.readUTF());
            }
            case Ev.MSG -> Ev.msg(i.readShort(), i.readByte(), i.readUTF());
            case Ev.ITEM -> {
                int pid = i.readShort();
                int kind = i.readByte();
                float amount = i.readFloat();
                yield Ev.item(pid, kind, amount, i.readUTF());
            }
            default -> throw new IOException("Unknown event type " + t);
        };
    }

    // -------------------------------------------------------------- helpers

    private static String clip(String s, int max) {
        if (s == null) return "";
        return s.length() <= max ? s : s.substring(0, max);
    }

    /** writeUTF is limited to 64KB; skin data is far smaller, but never risk it. */
    private static String safeSkin(String s) {
        return (s == null || s.length() > 30000) ? "" : s;
    }
}
