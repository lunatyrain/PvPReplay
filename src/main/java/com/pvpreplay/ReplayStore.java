package com.pvpreplay;

import com.pvpreplay.data.Replay;
import com.pvpreplay.data.ReplayIO;
import com.pvpreplay.data.ReplayMeta;

import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Level;

/** Replay files on disk plus an in-memory index of their headers. */
public final class ReplayStore {
    private final PvPReplayPlugin plugin;
    private final Settings st;
    private final Path dir;
    private final Map<String, ReplayMeta> metas = new ConcurrentHashMap<>();

    public ReplayStore(PvPReplayPlugin plugin, Settings st) {
        this.plugin = plugin;
        this.st = st;
        this.dir = plugin.getDataFolder().toPath().resolve("replays");
        try {
            Files.createDirectories(dir);
        } catch (IOException e) {
            plugin.getLogger().log(Level.SEVERE, "Could not create replay folder", e);
        }
    }

    /** Reads all headers. Call off the main thread. */
    public void loadIndex() {
        try (DirectoryStream<Path> ds = Files.newDirectoryStream(dir, "*.pvr")) {
            for (Path p : ds) {
                try {
                    ReplayMeta m = ReplayIO.readMeta(p);
                    metas.put(m.id(), m);
                } catch (Exception e) {
                    plugin.getLogger().warning("Skipping unreadable replay " + p.getFileName() + ": " + e.getMessage());
                }
            }
        } catch (IOException e) {
            plugin.getLogger().log(Level.WARNING, "Could not list replays", e);
        }
    }

    public Path pathOf(String id) {
        return dir.resolve(id + ".pvr");
    }

    public boolean exists(String id) {
        return metas.containsKey(id) || Files.exists(pathOf(id));
    }

    /** Newest first. */
    public List<ReplayMeta> list() {
        List<ReplayMeta> out = new ArrayList<>(metas.values());
        out.sort(Comparator.comparingLong(ReplayMeta::created).reversed());
        return out;
    }

    public ReplayMeta meta(String id) {
        return metas.get(id);
    }

    /** Writes the replay and adds it to the index. Call off the main thread. */
    public void save(Replay r) throws IOException {
        ReplayIO.write(r, pathOf(r.meta().id()));
        metas.put(r.meta().id(), r.meta());
    }

    /** Reads a full replay. Call off the main thread. Only ids present in the index are accepted. */
    public Replay load(String id) throws IOException {
        if (!metas.containsKey(id)) throw new IOException("Unknown replay id");
        return ReplayIO.read(pathOf(id));
    }

    public boolean delete(String id) {
        if (metas.remove(id) == null) return false;
        try {
            Files.deleteIfExists(pathOf(id));
        } catch (IOException e) {
            plugin.getLogger().log(Level.WARNING, "Could not delete " + id, e);
        }
        return true;
    }

    /** Removes replays older than max-age-days and the oldest beyond max-replays. Call off the main thread. */
    public int cleanup() {
        int removed = 0;
        List<ReplayMeta> all = list(); // newest first
        long cutoff = st.maxAgeDays <= 0 ? 0 : System.currentTimeMillis() - st.maxAgeDays * 86_400_000L;
        for (int i = 0; i < all.size(); i++) {
            ReplayMeta m = all.get(i);
            boolean tooOld = cutoff > 0 && m.created() < cutoff;
            boolean tooMany = i >= st.maxReplays;
            if ((tooOld || tooMany) && delete(m.id())) removed++;
        }
        return removed;
    }

    public String newId() {
        String chars = "abcdefghijklmnopqrstuvwxyz0123456789";
        Random rnd = new Random();
        for (int tries = 0; tries < 50; tries++) {
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < 6; i++) sb.append(chars.charAt(rnd.nextInt(chars.length())));
            if (!exists(sb.toString())) return sb.toString();
        }
        return Long.toString(System.nanoTime(), 36);
    }
}
