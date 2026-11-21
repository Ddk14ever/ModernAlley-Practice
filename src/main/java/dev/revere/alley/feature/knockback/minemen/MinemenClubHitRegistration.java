package dev.revere.alley.feature.knockback.minemen;

import com.comphenix.protocol.PacketType;
import com.comphenix.protocol.ProtocolLibrary;
import com.comphenix.protocol.events.ListenerOptions;
import com.comphenix.protocol.events.ListenerPriority;
import com.comphenix.protocol.events.PacketAdapter;
import com.comphenix.protocol.events.PacketContainer;
import com.comphenix.protocol.events.PacketEvent;
import com.comphenix.protocol.wrappers.EnumWrappers;
import com.comphenix.protocol.wrappers.WrappedEnumEntityUseAction;
import dev.revere.alley.AlleyPlugin;
import dev.revere.alley.feature.knockback.KnockbackManager;
import org.bukkit.Bukkit;
import org.bukkit.FluidCollisionMode;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerChangedWorldEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerRespawnEvent;
import org.bukkit.util.Vector;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/** Player-target attack packets use Club's acceptance before modern range checks. */
public final class MinemenClubHitRegistration implements Listener {
    private final KnockbackManager manager;
    private final Map<UUID, MinemenClubPositionHistory> history = new HashMap<>();
    // Only this snapshot and the epoch are read by the defensive async fallback.
    private final Map<UUID, Player> active = new ConcurrentHashMap<>();
    private final AtomicLong epoch = new AtomicLong();
    private final PacketAdapter packets;

    public MinemenClubHitRegistration(KnockbackManager manager) {
        this.manager = manager;
        this.packets = new PacketAdapter(AlleyPlugin.getInstance(), ListenerPriority.HIGHEST,
                List.of(PacketType.Play.Client.USE_ENTITY), ListenerOptions.SYNC) {
            @Override
            public void onPacketReceiving(PacketEvent event) {
                receiveAttack(event);
            }
        };
    }

    public void enable() {
        ProtocolLibrary.getProtocolManager().addPacketListener(packets);
    }

    public void disable() {
        ProtocolLibrary.getProtocolManager().removePacketListener(packets);
        clear();
        active.clear();
    }

    public void refreshPlayer(Player player) {
        if (manager.isMinemenClubKnockback(player) && manager.getAppliedProfile(player) != null) {
            active.put(player.getUniqueId(), player);
        } else {
            active.remove(player.getUniqueId());
        }
    }

    private void receiveAttack(PacketEvent event) {
        if (event.isCancelled() || !isAttack(event.getPacket())) return;
        Player attacker = event.getPlayer();
        if (active.get(attacker.getUniqueId()) != attacker) return;
        Integer entityId = event.getPacket().getIntegers().readSafely(0);
        if (entityId == null) return;

        if (!Bukkit.isPrimaryThread()) {
            // SYNC normally already guarantees the primary thread. Never read
            // worlds/entities on a network thread if another injector violates it.
            long queuedEpoch = epoch.get();
            PacketContainer original = event.getPacket().deepClone();
            event.setCancelled(true);
            Bukkit.getScheduler().runTask(AlleyPlugin.getInstance(), () -> {
                if (queuedEpoch != epoch.get() || !attacker.isOnline()
                        || active.get(attacker.getUniqueId()) != attacker) return;
                Entity target = ProtocolLibrary.getProtocolManager()
                        .getEntityFromID(attacker.getWorld(), entityId);
                if (target instanceof Player victim) {
                    manager.attack(attacker, victim);
                } else {
                    // Keep the native behaviour for non-player targets; filters
                    // are disabled so the fallback cannot intercept itself.
                    ProtocolLibrary.getProtocolManager().receiveClientPacket(attacker, original, false);
                }
            });
            return;
        }

        Entity target = ProtocolLibrary.getProtocolManager().getEntityFromID(attacker.getWorld(), entityId);
        if (!(target instanceof Player victim)) return;
        // Cancel even rejected player attacks: otherwise the modern path could
        // accept them, or execute an accepted attack a second time.
        event.setCancelled(true);
        manager.attack(attacker, victim);
    }

    private boolean isAttack(PacketContainer packet) {
        WrappedEnumEntityUseAction action = packet.getEnumEntityUseActions().readSafely(0);
        return action != null ? action.getAction() == EnumWrappers.EntityUseAction.ATTACK
                : packet.getEntityUseActions().readSafely(0) == EnumWrappers.EntityUseAction.ATTACK;
    }

    public boolean canAttack(Player attacker, Player victim) {
        if (attacker.equals(victim) || attacker.getWorld() != victim.getWorld()
                || attacker.isDead() || victim.isDead() || !attacker.isValid() || !victim.isValid()
                || attacker.isSleeping() || attacker.getGameMode() == GameMode.SPECTATOR
                || victim.getGameMode() == GameMode.SPECTATOR || !attacker.canSee(victim)) return false;
        Location target = victim.getLocation();
        // Club excludes custom (Bot) connections on either side from rewinding.
        if (!manager.getPlayerData(attacker).isServerControlled()
                && !manager.getPlayerData(victim).isServerControlled()) {
            MinemenClubPositionHistory positions = history.get(victim.getUniqueId());
            if (positions != null) target = positions.rewind(target, attacker.getPing(), System.currentTimeMillis());
        }
        return acceptsDistance(attacker.getLocation().distanceSquared(target),
                hasLineOfSight(attacker.getEyeLocation(), victim.getLocation(), victim.getEyeHeight()));
    }

    static boolean acceptsDistance(double squaredDistance, boolean visible) {
        return Double.isFinite(squaredDistance) && squaredDistance >= 0.0D
                && squaredDistance <= (visible ? 36.0D : 12.75D);
    }

    static boolean hasLineOfSight(Location eye, Location feet, double targetEyeHeight) {
        double part = targetEyeHeight / 3.0D;
        for (int height = 3; height >= 1; height--) {
            Vector ray = feet.clone().add(0.0D, part * height, 0.0D).toVector().subtract(eye.toVector());
            double distance = ray.length();
            if (distance <= 1.0E-6D || eye.getWorld().rayTraceBlocks(eye, ray.multiply(1.0D / distance),
                    distance, FluidCollisionMode.NEVER, false) == null) return true;
        }
        return false;
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onMove(PlayerMoveEvent event) {
        if (manager.isMinemenClubKnockback(event.getPlayer())) {
            history.computeIfAbsent(event.getPlayer().getUniqueId(), ignored -> new MinemenClubPositionHistory())
                    .record(event.getTo(), System.currentTimeMillis());
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onWorldChange(PlayerChangedWorldEvent event) {
        manager.clearMinemenClubCombatState(event.getPlayer().getUniqueId());
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onRespawn(PlayerRespawnEvent event) {
        manager.clearMinemenClubCombatState(event.getPlayer().getUniqueId());
    }

    public void clearPlayer(UUID player) {
        epoch.incrementAndGet();
        history.remove(player);
    }

    public void removePlayer(UUID player) {
        clearPlayer(player);
        active.remove(player);
    }

    public void clear() {
        epoch.incrementAndGet();
        history.clear();
    }
}
