package dev.revere.alley.feature.knockback.minemen;

import com.comphenix.protocol.PacketType;
import com.comphenix.protocol.ProtocolLibrary;
import com.comphenix.protocol.events.ListenerPriority;
import com.comphenix.protocol.events.PacketAdapter;
import com.comphenix.protocol.events.PacketContainer;
import com.comphenix.protocol.events.PacketEvent;
import com.destroystokyo.paper.event.server.ServerTickEndEvent;
import dev.revere.alley.AlleyPlugin;
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.Material;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerChangedWorldEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerTeleportEvent;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.util.Vector;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/** Vertical-only descent correction for clients without the native gravity attribute. */
final class MinemenClubGravityCompensation implements Listener {
    private static final String PACKET_META = "alley:minemenclub_gravity";
    private final Map<UUID, Session> sessions = new ConcurrentHashMap<>();
    private final MinemenClubGravityBridge bridge = new MinemenClubGravityBridge();
    private final MinemenClubGravityImpulse impulse = new MinemenClubGravityImpulse();
    private PacketAdapter packets;
    private PacketAdapter gate;

    void enable() {
        if (packets != null) return;
        Bukkit.getPluginManager().registerEvents(this, AlleyPlugin.getInstance());
        gate = new PacketAdapter(AlleyPlugin.getInstance(), ListenerPriority.HIGHEST,
                PacketType.Play.Server.ENTITY_VELOCITY, PacketType.Play.Server.EXPLOSION) {
            @Override
            public void onPacketSending(PacketEvent event) {
                Emission own = event.getPacket().<Emission>getMeta(PACKET_META).orElse(null);
                if (own != null && (sessions.get(own.session.uuid) != own.session
                        || own.session.failed || own.generation != own.session.generation.get()
                        || System.nanoTime() - own.created > 1_000_000_000L)) {
                    // A delayed filter must not replay pre-teleport/pre-hit motion.
                    event.setCancelled(true);
                }
            }
        };
        packets = new PacketAdapter(AlleyPlugin.getInstance(), ListenerPriority.MONITOR,
                PacketType.Play.Server.ENTITY_VELOCITY, PacketType.Play.Server.EXPLOSION) {
            @Override
            public void onPacketSending(PacketEvent event) {
                observeVelocity(event);
            }
        };
        ProtocolLibrary.getProtocolManager().addPacketListener(gate);
        ProtocolLibrary.getProtocolManager().addPacketListener(packets);
    }

    void disable() {
        if (packets != null) {
            ProtocolLibrary.getProtocolManager().removePacketListener(packets);
            ProtocolLibrary.getProtocolManager().removePacketListener(gate);
            packets = null;
            gate = null;
        }
        sessions.clear();
        org.bukkit.event.HandlerList.unregisterAll(this);
    }

    void apply(Player player) {
        // The owning gravity controller already waited for the hit's round trip
        // and observed descent. Do not impose a second launch grace here.
        sessions.put(player.getUniqueId(), new Session(player));
    }

    void remove(UUID uuid) {
        sessions.remove(uuid);
    }

    String status(Player player) {
        Session session = sessions.get(player.getUniqueId());
        if (session == null || session.player != player) return "inactive";
        return (session.failed ? "failed" : System.nanoTime() < session.blockedUntil.get()
                ? "waiting after impulse/teleport" : "ready (vertical additive descent)")
                + ", packets=" + session.sentPackets;
    }

    private void observeVelocity(PacketEvent event) {
        // This callback may run off-thread: read packet/identity data only;
        // publish results atomically and leave all world/motion work to tick end.
        Emission own = event.getPacket().<Emission>getMeta(PACKET_META).orElse(null);
        if (own != null) {
            try {
                own.delivered = !event.isCancelled();
            } finally {
                event.getPacket().removeMeta(PACKET_META);
                // Publish delivery status before the main-thread drain sees completion.
                own.observed = true;
            }
            return;
        }
        Session session = sessions.get(event.getPlayer().getUniqueId());
        if (session == null || session.player != event.getPlayer()) return;
        if (event.getPacketType().equals(PacketType.Play.Server.EXPLOSION)) {
            if (!event.isCancelled()) session.pause();
            return;
        }
        Integer id = event.getPacket().getIntegers().readSafely(0);
        if (id == null || id != session.entityId) return;
        if (!event.isCancelled()) session.pause();
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onTickEnd(ServerTickEndEvent event) {
        for (Session session : sessions.values()) {
            Player player = session.player;
            if (!player.isOnline() || !player.isValid()) {
                sessions.remove(player.getUniqueId(), session);
                continue;
            }
            if (session.failed) continue;
            try {
                session.graceNanos = graceNanos(player.getPing());
                boolean inAir = airborne(player);
                if (!inAir) session.generation.incrementAndGet();
                if (!drain(session)) continue;
                MinemenClubGravityBridge.Sample sample = bridge.sample(player);
                if (sample == null) continue;
                // NMS creates a fresh Vec3 for every accepted move. Its ordinary
                // movement event may be throttled near the apex, so don't use it.
                if (sample.token() == session.lastSample) continue;
                session.lastSample = sample.token();
                if (System.nanoTime() < session.blockedUntil.get() || !inAir) continue;
                AttributeInstance attribute = player.getAttribute(Attribute.GRAVITY);
                if (attribute == null) continue;
                double gravity = attribute.getValue();
                // Vanilla must generate no correction packets at all.
                if (Double.compare(gravity, 0.08D) == 0) continue;
                Vector movement = sample.movement();
                if (!MinemenClubGravityMotion.finite(movement)) continue;
                // Large discontinuities are not a trustworthy per-client-tick sample.
                if (movement.lengthSquared() > 100.0D) {
                    session.pause();
                    continue;
                }
                // Never replay an old horizontal velocity or integrate stale Y.
                // Only the difference from vanilla's gravity is added to the
                // client's CURRENT velocity; no server deltaMovement is changed.
                send(session, MinemenClubGravityMotion.correction(gravity));
            } catch (Exception | LinkageError exception) {
                // Fail once per session, rather than spam errors or fall back to
                // stale Bukkit velocity and damage combat movement every tick.
                session.failed = true;
                AttributeInstance attribute = player.getAttribute(Attribute.GRAVITY);
                if (attribute != null) MinemenClubGravity.remove(attribute);
                AlleyPlugin.getInstance().getLogger().warning(
                        "[MinemenClub] Gravity compensation disabled for " + player.getName()
                                + ": " + exception);
            }
        }
    }

    private void send(Session session, double vertical) throws Exception {
        PacketContainer packet = impulse.create(vertical);
        Emission emission = new Emission(session);
        session.inFlight = emission;
        // Metadata follows the native packet across ProtocolLib wrappers/threads.
        // A ThreadLocal would misidentify a deferred packet as external knockback.
        packet.setMeta(PACKET_META, emission);
        ProtocolLibrary.getProtocolManager().sendServerPacket(session.player, packet);
        drain(session);
    }

    private boolean drain(Session session) {
        Emission emission = session.inFlight;
        if (emission == null) return true;
        if (!emission.observed) {
            // Bound queue growth if another plugin defers or replaces the packet.
            if (System.nanoTime() - emission.created > 1_000_000_000L) {
                throw new IllegalStateException("Compensation packet filter did not finish within 1s");
            }
            return false;
        }
        session.inFlight = null;
        if (emission.delivered) session.sentPackets++;
        return true;
    }

    private static boolean airborne(Player player) {
        return !player.isOnGround() && ordinaryPhysics(player);
    }

    static boolean ordinaryPhysics(Player player) {
        if (player.isDead() || !player.hasGravity()
                || player.getGameMode() == GameMode.SPECTATOR || player.isFlying()
                || player.isInsideVehicle() || player.isSleeping() || player.isGliding()
                || player.isSwimming() || player.isRiptiding() || player.isClimbing()
                || player.isInWater() || player.isInLava()
                || player.hasPotionEffect(PotionEffectType.LEVITATION)
                || player.hasPotionEffect(PotionEffectType.SLOW_FALLING)) return false;
        Material feet = player.getLocation().getBlock().getType();
        Material head = player.getEyeLocation().getBlock().getType();
        return !special(feet) && !special(head);
    }

    private static boolean special(Material material) {
        return material == Material.COBWEB || material == Material.POWDER_SNOW
                || material == Material.SWEET_BERRY_BUSH || material == Material.SCAFFOLDING
                || material == Material.BUBBLE_COLUMN;
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onTeleport(PlayerTeleportEvent event) {
        reset(event.getPlayer());
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onWorldChange(PlayerChangedWorldEvent event) {
        reset(event.getPlayer());
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onQuit(PlayerQuitEvent event) {
        remove(event.getPlayer().getUniqueId());
    }

    private void reset(Player player) {
        Session session = sessions.get(player.getUniqueId());
        if (session == null || session.player != player) return;
        session.lastSample = null;
        session.pause();
    }

    static long graceNanos(int ping) {
        // Wait for an external velocity to reach the client and for its movement
        // to return. Do not continue the previous descent while a new melee,
        // Punch, or external impulse may already be launching the client upward.
        return (Math.max(0L, ping) + 100L) * 1_000_000L;
    }

    private static final class Session {
        final Player player;
        final UUID uuid;
        final int entityId;
        final AtomicLong blockedUntil = new AtomicLong(System.nanoTime());
        final AtomicLong generation = new AtomicLong();
        volatile long graceNanos;
        Object lastSample;
        volatile boolean failed;
        long sentPackets;
        Emission inFlight;

        Session(Player player) {
            this.player = player;
            this.uuid = player.getUniqueId();
            this.entityId = player.getEntityId();
            this.graceNanos = graceNanos(player.getPing());
        }

        void pause() {
            generation.incrementAndGet();
            blockedUntil.accumulateAndGet(System.nanoTime() + graceNanos, Math::max);
        }
    }

    private static final class Emission {
        final Session session;
        final long generation;
        final long created = System.nanoTime();
        boolean delivered;
        volatile boolean observed;

        Emission(Session session) {
            this.session = session;
            this.generation = session.generation.get();
        }
    }
}
