package dev.revere.alley.common.reflect.internal.types;

import com.comphenix.protocol.ProtocolLibrary;
import com.comphenix.protocol.ProtocolManager;
import com.comphenix.protocol.events.PacketContainer;
import dev.revere.alley.AlleyPlugin;
import dev.revere.alley.common.logger.Logger;
import dev.revere.alley.common.reflect.Reflection;
import dev.revere.alley.common.version.ClientVersionService;
import dev.revere.alley.feature.bot.match.BotMatchSession;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Sound;
import org.bukkit.entity.Player;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicBoolean;

/** Client-only feedback for cancelled lethal damage; never kills the real player. */
public final class DeathReflectionServiceImpl implements Reflection {
    private final ProtocolManager protocolManager = ProtocolLibrary.getProtocolManager();
    private final Map<UUID, Integer> lastFeedbackTick = new HashMap<>();
    private final Set<Runnable> cleanups = ConcurrentHashMap.newKeySet();
    private final AtomicBoolean warned = new AtomicBoolean();

    /** The sound reaches nearby listeners; only the killer receives the corpse. */
    public void animateDeath(Player victim, Player killer) {
        if (!Bukkit.isPrimaryThread()) {
            Bukkit.getScheduler().runTask(AlleyPlugin.getInstance(), () -> animateDeath(victim, killer));
            return;
        }
        if (victim == null || !victim.isOnline() || victim.isDead()
                || victim.getScoreboardTags().contains(BotMatchSession.BOT_ENTITY_TAG)) return;
        int tick = Bukkit.getCurrentTick();
        lastFeedbackTick.entrySet().removeIf(entry -> entry.getValue() != tick);
        if (lastFeedbackTick.put(victim.getUniqueId(), tick) != null) return;

        try {
            Location location = victim.getLocation().clone();
            victim.getWorld().playSound(location, Sound.ENTITY_PLAYER_HURT, 1.0F, 1.0F);
            if (killer == null || killer.getUniqueId().equals(victim.getUniqueId())
                    || !killer.isOnline() || killer.isDead() || !killer.canSee(victim)
                    || killer.getScoreboardTags().contains(BotMatchSession.BOT_ENTITY_TAG)
                    || killer.getWorld() != victim.getWorld()) return;

            ClientVersionService versions = AlleyPlugin.getInstance().getService(ClientVersionService.class);
            boolean legacy17 = versions.isLegacy17(killer);
            DeathAnimationPackets animation = DeathAnimationPackets.create(victim, location, legacy17,
                    versions.getProtocol(killer));
            LegacyDeathProfile legacyProfile = legacy17
                    ? LegacyDeathProfile.create(killer, animation.profile()) : null;
            Executor connection = legacyProfile == null ? Runnable::run : legacyProfile::execute;
            AtomicBoolean removed = new AtomicBoolean();
            Runnable cleanup = new Runnable() {
                @Override
                public void run() {
                    if (!removed.compareAndSet(false, true)) return;
                    cleanups.remove(this);
                    try {
                        connection.execute(() -> {
                            if (killer.isOnline()) {
                                for (Object packet : animation.removal()) {
                                    try {
                                        send(killer, packet);
                                    } catch (RuntimeException exception) {
                                        warn(exception);
                                    }
                                }
                            }
                            try {
                                if (legacyProfile != null) legacyProfile.remove();
                            } catch (ReflectiveOperationException exception) {
                                warn(exception);
                            }
                        });
                    } catch (RuntimeException exception) {
                        warn(exception);
                    }
                }
            };
            cleanups.add(cleanup);
            try {
                // Register cleanup before sending, including partially delivered spawns.
                Bukkit.getScheduler().runTaskLater(AlleyPlugin.getInstance(), cleanup, 20L);
                connection.execute(() -> {
                    if (removed.get() || !killer.isOnline()) return;
                    try {
                        if (legacyProfile != null) legacyProfile.install();
                        for (Object packet : animation.spawn()) send(killer, packet);
                    } catch (ReflectiveOperationException | RuntimeException exception) {
                        cleanup.run();
                        warn(exception);
                    }
                });
            } catch (RuntimeException exception) {
                cleanup.run();
                throw exception;
            }
        } catch (ReflectiveOperationException | RuntimeException exception) {
            warn(exception);
        }
    }

    public void shutdown() {
        List.copyOf(cleanups).forEach(Runnable::run);
        lastFeedbackTick.clear();
    }

    private void send(Player viewer, Object packet) {
        // Bypass plugin packet listeners; Via still translates the server protocol.
        protocolManager.sendServerPacket(viewer, PacketContainer.fromPacket(packet), false);
    }

    private void warn(Exception exception) {
        if (!warned.compareAndSet(false, true)) return;
        Logger.logException("Could not show client-only death animation; synthetic respawn is unaffected.", exception);
    }
}
