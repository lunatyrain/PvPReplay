package com.pvpreplay.record;

import com.destroystokyo.paper.profile.ProfileProperty;
import com.pvpreplay.Compat;
import com.pvpreplay.PvPReplayPlugin;
import com.pvpreplay.Settings;
import com.pvpreplay.data.*;
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.data.BlockData;
import org.bukkit.block.data.Waterlogged;
import org.bukkit.entity.AbstractArrow;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.entity.Projectile;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.bukkit.potion.PotionEffect;
import org.bukkit.scheduler.BukkitTask;
import org.bukkit.util.BoundingBox;

import java.util.*;

/**
 * Keeps a rolling in-memory buffer of the last N seconds of every (non-exempt) player, one frame per tick.
 * The buffer is cut into 5-second segments; every segment starts with a key frame that re-emits all equipment
 * and live projectiles, so any whole number of segments is a valid replay on its own.
 * Runs on the main thread only. Saved frames are immutable and can be written from another thread.
 */
public final class Recorder {
    public static final int SEGMENT = 100; // ticks per segment (5 s)
    private static final int MAX_PROJECTILES = 200;
    private static final Set<String> NOISY_CAUSES =
            Set.of("FIRE_TICK", "POISON", "WITHER", "STARVATION", "DRYOUT", "FREEZE", "CRAMMING", "SUFFOCATION");

    private final PvPReplayPlugin plugin;
    private final Settings st;

    private final Map<UUID, Integer> pidByUuid = new HashMap<>();
    private final List<PlayerInfo> infos = new ArrayList<>();
    private final List<String> worlds = new ArrayList<>();

    private final ArrayDeque<Segment> segments = new ArrayDeque<>();
    private Segment cur;
    private int frameNo = 0;

    private final List<Ev> pending = new ArrayList<>();
    private final Map<Integer, ItemStack[]> equipCache = new HashMap<>();
    private Set<Integer> prevPresent = new HashSet<>();
    private final Map<Integer, ArrayDeque<Integer>> swings = new HashMap<>();

    private static final int INV_SLOTS = 41;
    private static final Set<String> SECRET_COMMANDS = Set.of("login", "l", "log", "register", "reg", "changepassword",
            "password", "passwd", "unregister", "authme", "2fa", "totp", "verify", "auth", "captcha");
    private static final Set<String> PRIVATE_COMMANDS = Set.of("msg", "tell", "w", "whisper", "m", "t", "r", "reply",
            "pm", "dm", "mail");

    private static final class InvCache {
        final ItemStack[] items = new ItemStack[INV_SLOTS];
        final byte[][] bytes = new byte[INV_SLOTS][];
        int held = -1;
        int food = -1, level = -1, absorb = -1, satur = -1;
        String effectKey = null;
        String effects = "";
    }

    private final Map<Integer, InvCache> invCaches = new HashMap<>();

    private final Map<Integer, Proj> projs = new LinkedHashMap<>();
    private final Map<UUID, Integer> projByEntity = new HashMap<>();
    private int nextProj = 1;

    private BukkitTask task;

    // voice packets arrive on Simple Voice Chat's own thread; they wait here until the next tick
    private record VoiceIn(UUID uuid, byte[] opus) {
    }

    private final java.util.concurrent.ConcurrentLinkedQueue<VoiceIn> voiceQueue = new java.util.concurrent.ConcurrentLinkedQueue<>();
    private final java.util.concurrent.atomic.AtomicInteger voiceQueued = new java.util.concurrent.atomic.AtomicInteger();

    /** Thread-safe. Called for every microphone packet when voice recording is on. */
    public void onVoice(UUID uuid, byte[] opus) {
        if (!st.recordVoice || opus == null || opus.length == 0 || opus.length > 4000) return;
        if (voiceQueued.get() > 4000) return; // server stalled: drop instead of growing without limit
        voiceQueued.incrementAndGet();
        voiceQueue.add(new VoiceIn(uuid, opus.clone()));
    }

    private static final class Segment {
        final int start;
        final List<Frame> frames = new ArrayList<>(SEGMENT);

        Segment(int start) {
            this.start = start;
        }
    }

    private record Proj(Projectile entity, String type) {
    }

    public Recorder(PvPReplayPlugin plugin, Settings st) {
        this.plugin = plugin;
        this.st = st;
    }

    public void start() {
        task = Bukkit.getScheduler().runTaskTimer(plugin, this::tick, 1L, 1L);
    }

    public void stop() {
        if (task != null) task.cancel();
        task = null;
    }

    public int frameNo() {
        return frameNo;
    }

    // ------------------------------------------------------------ identities

    /** Returns the replay id for a player, creating/refreshing their entry (name, skin). */
    public int pidFor(Player p) {
        Integer pid = pidByUuid.get(p.getUniqueId());
        if (pid == null) {
            pid = infos.size();
            infos.add(infoOf(p));
            pidByUuid.put(p.getUniqueId(), pid);
        }
        return pid;
    }

    /** Refresh name/skin (call on join, skins can change). */
    public void refresh(Player p) {
        Integer pid = pidByUuid.get(p.getUniqueId());
        if (pid == null) {
            pidFor(p);
        } else {
            infos.set(pid, infoOf(p));
        }
    }

    private PlayerInfo infoOf(Player p) {
        String value = null, sig = null;
        try {
            for (ProfileProperty pp : p.getPlayerProfile().getProperties()) {
                if (pp.getName().equals("textures")) {
                    value = pp.getValue();
                    sig = pp.getSignature();
                    break;
                }
            }
        } catch (Throwable ignored) {
            // skin is optional
        }
        return new PlayerInfo(p.getUniqueId(), p.getName(), value, sig);
    }

    /** The replay id of a player if they are currently being recorded, otherwise null. */
    public Integer trackedPid(Player p) {
        return tracked(p) ? pidFor(p) : null;
    }

    public boolean tracked(Player p) {
        if (!p.isOnline() || p.isDead()) return false;
        GameMode gm = p.getGameMode();
        if (gm == GameMode.SPECTATOR) return false;
        if (gm == GameMode.CREATIVE && !st.recordCreative) return false;
        if (p.hasPermission("pvpreplay.exempt")) return false;
        return !st.excludedWorlds.contains(p.getWorld().getName().toLowerCase(Locale.ROOT));
    }

    private int worldIdx(World w) {
        String n = w.getName();
        int i = worlds.indexOf(n);
        if (i >= 0) return i;
        worlds.add(n);
        return worlds.size() - 1;
    }

    // ----------------------------------------------------------- event input

    public void onSwing(Player p, boolean offHand) {
        Integer pid = trackedPid(p);
        if (pid == null) return;
        swings.computeIfAbsent(pid, k -> new ArrayDeque<>()).addLast(frameNo);
        pending.add(Ev.swing(pid, offHand ? 1 : 0));
    }

    /** Clicks in the last second for a player id (swing animations, includes mining). */
    public int cps(int pid) {
        ArrayDeque<Integer> q = swings.get(pid);
        if (q == null) return 0;
        while (!q.isEmpty() && frameNo - q.peekFirst() >= 20) q.pollFirst();
        return q.size();
    }

    private record PvpHit(UUID attacker, long at) {
    }

    private final Map<UUID, PvpHit> lastPvpHit = new HashMap<>();

    public void onDamage(Player victim, org.bukkit.event.entity.EntityDamageEvent e) {
        Integer vp = trackedPid(victim);
        if (vp == null) return;
        String cause = e.getCause().name();

        // Who really did it: the damage source knows the player behind arrows, tridents, fireballs, wind charges,
        // lit TNT and popped end crystals, not just the entity that touched the victim.
        Entity direct = null, causing = null;
        try {
            org.bukkit.damage.DamageSource ds = e.getDamageSource();
            direct = ds.getDirectEntity();
            causing = ds.getCausingEntity();
        } catch (Throwable ignored) {
            // older API: fall back to the damager below
        }
        if (direct == null && causing == null && e instanceof org.bukkit.event.entity.EntityDamageByEntityEvent be) {
            direct = be.getDamager();
            causing = direct instanceof Projectile pr && pr.getShooter() instanceof Entity sh ? sh : direct;
        }

        int ap = -1;
        double reach = -1;
        double cps = 0;
        String desc = cause + "|";
        if (direct instanceof Player pl) {
            desc = cause + "|" + pl.getInventory().getItemInMainHand().getType().name();
            // Reach only means something for a normal swing (not sweep splash or thorns reflecting damage).
            if (e.getCause() == org.bukkit.event.entity.EntityDamageEvent.DamageCause.ENTITY_ATTACK) {
                reach = reachDistance(pl, victim);
            }
        } else if (direct instanceof Projectile pr) {
            desc = "PROJECTILE|" + pr.getType().name();
        } else if (direct != null) {
            desc = cause + "|" + direct.getType().name(); // e.g. ENTITY_EXPLOSION|END_CRYSTAL
        }
        Player attacker = causing instanceof Player pl ? pl : null;
        if (attacker != null) {
            Integer a = trackedPid(attacker);
            if (a != null) {
                ap = a;
                cps = cps(a);
            }
            if (!attacker.equals(victim)) {
                lastPvpHit.put(victim.getUniqueId(), new PvpHit(attacker.getUniqueId(), System.currentTimeMillis()));
                if (lastPvpHit.size() > 200) {
                    long old = System.currentTimeMillis() - 60_000L;
                    lastPvpHit.values().removeIf(h -> h.at() < old);
                }
            }
        }
        // Burning / poison / starvation ticks would flood the replay with "hits" from nobody.
        if (ap < 0 && NOISY_CAUSES.contains(cause)) return;
        // Critical hit (falling, not sprinting, full-strength swing). Tagged in the description so old replays still load.
        if (isCrit(e, direct)) desc += " [CRIT]";
        // A shield that soaked up the whole hit: say so in the feed.
        if (victim.isBlocking() && e.getFinalDamage() <= 0.001) desc += " [BLOCKED by shield]";
        pending.add(Ev.hit(ap, vp, (float) e.getFinalDamage(), reach, cps, desc));
    }

    /** True when a player's normal melee swing was a critical hit. Asks the server first, then checks the vanilla rules. */
    private static boolean isCrit(org.bukkit.event.entity.EntityDamageEvent e, Entity direct) {
        if (e.getCause() != org.bukkit.event.entity.EntityDamageEvent.DamageCause.ENTITY_ATTACK) return false;
        if (!(direct instanceof Player pl)) return false;
        if (e instanceof org.bukkit.event.entity.EntityDamageByEntityEvent be) {
            try {
                return be.isCritical();
            } catch (Throwable ignored) {
                // older API: work it out below
            }
        }
        try {
            return pl.getFallDistance() > 0 && !pl.isOnGround() && !pl.isClimbing() && !pl.isInWater()
                    && !pl.isSprinting() && !pl.isInsideVehicle() && pl.getAttackCooldown() > 0.9f
                    && !pl.hasPotionEffect(org.bukkit.potion.PotionEffectType.BLINDNESS);
        } catch (Throwable t) {
            return false;
        }
    }

    /**
     * If this player was hit by another player within the last {@code withinMs}, returns that attacker's id
     * (and forgets the hit), otherwise null. Used to catch logging out in the middle of a fight.
     */
    public UUID takeRecentPvpAttacker(Player victim, long withinMs) {
        PvpHit h = lastPvpHit.remove(victim.getUniqueId());
        return h != null && System.currentTimeMillis() - h.at() <= withinMs ? h.attacker() : null;
    }

    public void onTotem(Player p) {
        Integer pid = trackedPid(p);
        if (pid != null) pending.add(Ev.totem(pid));
    }

    /** Lava / water / powder snow placed or picked up with a bucket (the block event fires before the change). */
    public void onBucket(Player p, Block block, org.bukkit.Material bucket, boolean fill) {
        if (!st.recordBlocks || trackedPid(p) == null) return;
        if (block.getType().name().contains("CAULDRON")) return;
        BlockData prior = block.getBlockData();
        BlockData now;
        if (fill) {
            if (prior instanceof Waterlogged wl && wl.isWaterlogged()) {
                Waterlogged c = (Waterlogged) wl.clone();
                c.setWaterlogged(false);
                now = c;
            } else {
                now = org.bukkit.Material.AIR.createBlockData();
            }
        } else {
            String n = bucket.name();
            if (n.equals("LAVA_BUCKET")) {
                now = org.bukkit.Material.LAVA.createBlockData();
            } else if (n.equals("POWDER_SNOW_BUCKET")) {
                now = org.bukkit.Material.POWDER_SNOW.createBlockData();
            } else if (n.endsWith("_BUCKET") && !n.equals("BUCKET") && !n.equals("MILK_BUCKET")) {
                // water, and the fish / axolotl / tadpole buckets that also pour water
                if (prior instanceof Waterlogged wl) {
                    Waterlogged c = (Waterlogged) wl.clone();
                    c.setWaterlogged(true);
                    now = c;
                } else {
                    now = org.bukkit.Material.WATER.createBlockData();
                }
            } else {
                return;
            }
        }
        pending.add(Ev.block(worldIdx(block.getWorld()), block.getX(), block.getY(), block.getZ(),
                now.getAsString(), prior.getAsString()));
    }

    /** Distance from the attacker's eyes to the closest point of the victim's hitbox. */
    private static double reachDistance(Player attacker, Entity victim) {
        Location eye = attacker.getEyeLocation();
        BoundingBox b = victim.getBoundingBox();
        double dx = Math.max(Math.max(b.getMinX() - eye.getX(), 0), eye.getX() - b.getMaxX());
        double dy = Math.max(Math.max(b.getMinY() - eye.getY(), 0), eye.getY() - b.getMaxY());
        double dz = Math.max(Math.max(b.getMinZ() - eye.getZ(), 0), eye.getZ() - b.getMaxZ());
        return Math.sqrt(dx * dx + dy * dy + dz * dz);
    }

    public void onDeath(Player victim, String message) {
        Integer pid = trackedPid(victim);
        if (pid == null) {
            // player is dead at this point, so tracked() is false; use the existing id if we have one
            pid = pidByUuid.get(victim.getUniqueId());
        }
        lastPvpHit.remove(victim.getUniqueId());
        if (pid != null) pending.add(Ev.death(pid, message));
    }

    public void onBlock(Player p, Block block, String blockData, String priorData) {
        if (!st.recordBlocks) return;
        if (trackedPid(p) == null) return;
        pending.add(Ev.block(worldIdx(block.getWorld()), block.getX(), block.getY(), block.getZ(), blockData, priorData));
    }

    public void onProjectile(Projectile pr) {
        if (!st.recordProjectiles || projs.size() >= MAX_PROJECTILES) return;
        if (!(pr.getShooter() instanceof Player shooter) || trackedPid(shooter) == null) return;
        int id = nextProj++;
        String type = pr.getType().name();
        projs.put(id, new Proj(pr, type));
        projByEntity.put(pr.getUniqueId(), id);
        pending.add(Ev.projSpawn(id, type));
    }

    // ------------------------------------------------------------------ tick

    private void tick() {
        frameNo++;
        boolean key = cur == null || cur.frames.size() >= SEGMENT;
        if (key) {
            cur = new Segment(frameNo);
            segments.addLast(cur);
            int maxSegments = (st.bufferSeconds * 20) / SEGMENT + 1;
            while (segments.size() > maxSegments) segments.removeFirst();
        }

        List<Ev> evs = new ArrayList<>(pending);
        pending.clear();
        VoiceIn vi;
        while ((vi = voiceQueue.poll()) != null) {
            voiceQueued.decrementAndGet();
            Player vp = Bukkit.getPlayer(vi.uuid());
            if (vp == null || !tracked(vp)) continue;
            evs.add(Ev.voice(pidFor(vp), vi.opus()));
        }
        List<PState> states = new ArrayList<>();
        Set<Integer> present = new HashSet<>();

        for (Player p : Bukkit.getOnlinePlayers()) {
            if (!tracked(p)) continue;
            int pid = pidFor(p);
            present.add(pid);
            Location l = p.getLocation();
            int flags = 0;
            if (p.isSneaking()) flags |= PState.SNEAK;
            if (p.isSprinting()) flags |= PState.SPRINT;
            if (p.isSwimming()) flags |= PState.SWIM;
            if (p.isGliding()) flags |= PState.GLIDE;
            if (p.isBlocking()) flags |= PState.BLOCK;
            if (p.isOnGround()) flags |= PState.GROUND;
            if (p.isHandRaised()) {
                flags |= PState.USING;
                if (EquipmentSlot.OFF_HAND.equals(Compat.call(p, "getHandRaised"))) flags |= PState.USING_OFF;
            }
            states.add(new PState(pid, worldIdx(l.getWorld()), l.getX(), l.getY(), l.getZ(),
                    l.getYaw(), l.getPitch(), flags, (float) p.getHealth(), Math.max(0, Math.min(p.getPing(), 32000))));
            boolean rejoined = !prevPresent.contains(pid);
            recordEquipment(p, pid, key || rejoined, evs);
            recordInventory(p, pid, key || rejoined, rejoined, evs);
            recordVitals(p, pid, key || rejoined, evs);
        }
        prevPresent = present;

        // projectiles
        if (!projs.isEmpty()) {
            if (key) {
                for (Map.Entry<Integer, Proj> en : projs.entrySet()) {
                    evs.add(Ev.projSpawn(en.getKey(), en.getValue().type()));
                }
            }
            Iterator<Map.Entry<Integer, Proj>> it = projs.entrySet().iterator();
            while (it.hasNext()) {
                Map.Entry<Integer, Proj> en = it.next();
                Projectile pr = en.getValue().entity();
                boolean ended = !pr.isValid() || pr.isDead() || (pr instanceof AbstractArrow aa && aa.isInBlock());
                if (ended) {
                    evs.add(Ev.projEnd(en.getKey()));
                    projByEntity.remove(pr.getUniqueId());
                    it.remove();
                    continue;
                }
                Location l = pr.getLocation();
                evs.add(Ev.projPos(en.getKey(), worldIdx(l.getWorld()), l.getX(), l.getY(), l.getZ()));
            }
        }

        cur.frames.add(new Frame(key, states.toArray(new PState[0]), evs.toArray(new Ev[0])));
    }

    private static final int SLOTS = 6;

    private void recordEquipment(Player p, int pid, boolean force, List<Ev> evs) {
        PlayerInventory inv = p.getInventory();
        ItemStack[] now = {
                inv.getItemInMainHand(), inv.getItemInOffHand(),
                inv.getHelmet(), inv.getChestplate(), inv.getLeggings(), inv.getBoots()
        };
        ItemStack[] cache = equipCache.computeIfAbsent(pid, k -> new ItemStack[SLOTS]);
        for (int i = 0; i < SLOTS; i++) {
            ItemStack n = now[i];
            ItemStack o = cache[i];
            boolean nEmpty = n == null || n.getType().isAir();
            boolean oEmpty = o == null || o.getType().isAir();
            boolean changed = force || nEmpty != oEmpty || (!nEmpty && !o.isSimilar(n));
            if (!changed) continue;
            cache[i] = nEmpty ? null : n.clone();
            byte[] bytes = null;
            if (!nEmpty) {
                try {
                    bytes = n.serializeAsBytes();
                } catch (Throwable ignored) {
                    bytes = null;
                }
            }
            evs.add(Ev.equip(pid, i, bytes));
        }
    }

    private static boolean isEmpty(ItemStack i) {
        return i == null || i.getType().isAir();
    }

    private static boolean sameStack(ItemStack a, ItemStack b) {
        boolean ae = isEmpty(a), be = isEmpty(b);
        if (ae || be) return ae && be;
        return a.getAmount() == b.getAmount() && a.isSimilar(b);
    }

    /**
     * Full inventory (hotbar, main, armor, offhand) as slot-change events. Scanned every 5 ticks per player
     * (staggered). On key frames the cached bytes are re-emitted, so nothing is serialized twice.
     *
     * @param force      key frame or player just appeared: emit the whole inventory
     * @param emitEmpty  also emit empty slots (player re-appeared, the viewer may hold stale data)
     */
    private void recordInventory(Player p, int pid, boolean force, boolean emitEmpty, List<Ev> evs) {
        InvCache c = invCaches.computeIfAbsent(pid, k -> new InvCache());
        PlayerInventory inv = p.getInventory();

        int held = inv.getHeldItemSlot();
        if (force || held != c.held) {
            c.held = held;
            evs.add(Ev.held(pid, held));
        }

        boolean scan = force || ((frameNo + pid) % 5 == 0);
        if (scan) {
            for (int i = 0; i < INV_SLOTS; i++) {
                ItemStack n = inv.getItem(i);
                if (sameStack(c.items[i], n)) continue;
                if (isEmpty(n)) {
                    c.items[i] = null;
                    c.bytes[i] = null;
                } else {
                    c.items[i] = n.clone();
                    try {
                        c.bytes[i] = n.serializeAsBytes();
                    } catch (Throwable t) {
                        c.bytes[i] = null;
                        c.items[i] = null;
                    }
                }
                // On key frames non-empty slots are emitted below; a slot that just became empty must be emitted here.
                if (!force || c.bytes[i] == null) evs.add(Ev.inv(pid, i, c.bytes[i]));
            }
        }
        if (force) {
            for (int i = 0; i < INV_SLOTS; i++) {
                if (emitEmpty || c.bytes[i] != null) evs.add(Ev.inv(pid, i, c.bytes[i]));
            }
        }
    }

    /** Food, saturation, xp level, absorption and active potion effects (checked every 10 ticks, staggered). */
    private void recordVitals(Player p, int pid, boolean force, List<Ev> evs) {
        if (!force && (frameNo + pid) % 10 != 0) return;
        InvCache c = invCaches.computeIfAbsent(pid, k -> new InvCache());
        int food = p.getFoodLevel();
        int satur = Math.round(p.getSaturation());
        int level = p.getLevel();
        int absorb = Math.round((float) p.getAbsorptionAmount());

        StringBuilder key = new StringBuilder();
        StringBuilder full = new StringBuilder();
        for (PotionEffect pe : p.getActivePotionEffects()) {
            String name = pe.getType().getKey().getKey();
            key.append(name).append(':').append(pe.getAmplifier()).append(';');
            full.append(name).append(':').append(pe.getAmplifier()).append(':').append(pe.getDuration()).append(';');
        }
        String effectKey = key.toString();
        boolean changed = force || food != c.food || satur != c.satur || level != c.level || absorb != c.absorb
                || !effectKey.equals(c.effectKey);
        if (!changed) return;
        c.food = food;
        c.satur = satur;
        c.level = level;
        c.absorb = absorb;
        c.effectKey = effectKey;
        evs.add(Ev.vitals(pid, food, p.getSaturation(), level, absorb, full.toString()));
    }

    // -------------------------------------------------------- chat and items

    public void onChat(Player p, String text) {
        if (!st.recordChat) return;
        addMessage(p, 0, text);
    }

    public void onCommand(Player p, String raw) {
        if (!st.recordCommands) return;
        String m = raw.startsWith("/") ? raw.substring(1) : raw;
        String label = m.split("\\s+", 2)[0].toLowerCase(Locale.ROOT);
        int colon = label.indexOf(':');
        if (colon >= 0) label = label.substring(colon + 1);
        String text;
        if (SECRET_COMMANDS.contains(label)) text = "/" + label + " <hidden>";
        else if (PRIVATE_COMMANDS.contains(label)) text = "/" + label + " <private message>";
        else text = "/" + m;
        addMessage(p, 1, text);
    }

    private void addMessage(Player p, int kind, String text) {
        if (text == null || text.isEmpty()) return;
        if (p.getGameMode() == GameMode.SPECTATOR || p.hasPermission("pvpreplay.exempt")) return;
        String clipped = text.length() > 250 ? text.substring(0, 250) : text;
        pending.add(Ev.msg(pidFor(p), kind, clipped));
    }

    public void onItem(Player p, boolean pickup, ItemStack item) {
        Integer pid = trackedPid(p);
        if (pid == null || item == null || item.getType().isAir()) return;
        pending.add(Ev.item(pid, pickup ? 1 : 0, item.getAmount(), item.getType().name()));
    }

    // -------------------------------------------------------------- snapshot

    /**
     * Copies the buffered frames from the segment containing {@code cutoffFrame} up to now.
     * Call on the main thread. The returned lists contain only immutable objects.
     */
    public Snapshot snapshot(int cutoffFrame) {
        List<Frame> out = new ArrayList<>();
        for (Segment s : segments) {
            if (s.start + s.frames.size() > cutoffFrame) out.addAll(s.frames);
        }
        return new Snapshot(out, new ArrayList<>(infos), new ArrayList<>(worlds));
    }

    public record Snapshot(List<Frame> frames, List<PlayerInfo> players, List<String> worlds) {
    }
}
