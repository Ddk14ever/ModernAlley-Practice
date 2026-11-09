package dev.revere.alley.feature.knockback.sprint;

import com.comphenix.protocol.PacketType;
import com.comphenix.protocol.ProtocolLibrary;
import com.comphenix.protocol.events.ListenerPriority;
import com.comphenix.protocol.events.PacketAdapter;
import com.comphenix.protocol.events.PacketEvent;
import com.comphenix.protocol.wrappers.EnumWrappers;
import dev.revere.alley.AlleyPlugin;
import dev.revere.alley.feature.knockback.KnockbackManager;
import org.bukkit.entity.Player;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

/**
 * Tracks the client sprint declaration separately from server movement state and
 * the one-shot sprint knockback eligibility used by Legacy combat.
 */
public final class LegacySprintTracker {
    private static final long WTAP_WINDOW_NANOS = TimeUnit.MILLISECONDS.toNanos(350L);
    private static final long WTAP_EXTRA_LIFETIME_NANOS = TimeUnit.MILLISECONDS.toNanos(800L);
    private static final WTapResult NO_WTAP = new WTapResult(false, false);

    private final KnockbackManager manager;
    private final Map<UUID, SprintState> states = new ConcurrentHashMap<>();
    private PacketAdapter packetListener;

    public LegacySprintTracker(KnockbackManager manager) {
        this.manager = manager;
    }

    public void enable() {
        if (this.packetListener != null) return;

        this.packetListener = new PacketAdapter(PacketAdapter.params(
                AlleyPlugin.getInstance(), PacketType.Play.Client.ENTITY_ACTION)
                .listenerPriority(ListenerPriority.NORMAL)
                .optionSync()) {
            @Override
            public void onPacketReceiving(PacketEvent event) {
                Player player = event.getPlayer();
                if (!manager.isLegacyKnockback(player)) return;

                EnumWrappers.PlayerAction action = event.getPacket()
                        .getPlayerActions().readSafely(0);
                if (action == EnumWrappers.PlayerAction.START_SPRINTING) {
                    state(player).clientStart();
                } else if (action == EnumWrappers.PlayerAction.STOP_SPRINTING) {
                    state(player).clientStop();
                }
            }
        };
        ProtocolLibrary.getProtocolManager().addPacketListener(this.packetListener);
    }

    public void disable() {
        if (this.packetListener != null) {
            ProtocolLibrary.getProtocolManager().removePacketListener(this.packetListener);
            this.packetListener = null;
        }
        this.states.clear();
    }

    public void reset(Player player, boolean legacyEnabled) {
        if (!legacyEnabled) {
            clear(player.getUniqueId());
            return;
        }
        this.states.put(player.getUniqueId(), new SprintState(player.isSprinting()));
    }

    public void clear(UUID playerId) {
        this.states.remove(playerId);
    }

    public boolean hasKnockbackEligibility(Player player) {
        SprintState state = this.states.get(player.getUniqueId());
        return state != null && state.hasKnockbackEligibility();
    }

    public void consumeKnockbackEligibility(Player player) {
        SprintState state = this.states.get(player.getUniqueId());
        if (state != null) state.consumeKnockbackEligibility();
    }

    public WTapResult recordAcceptedMeleeHit(Player player) {
        SprintState state = this.states.get(player.getUniqueId());
        return state == null ? NO_WTAP : state.recordAcceptedMeleeHit();
    }

    /** Returns and consumes the one-shot bonus granted by a valid W-tap hit. */
    public boolean consumeWTapExtraEligibility(Player player) {
        SprintState state = this.states.get(player.getUniqueId());
        return state != null && state.consumeWTapExtraEligibility();
    }

    /** Arms the same one-shot bonus for a completed Legacy block-hit reset. */
    public void armWTapExtraEligibility(Player player) {
        SprintState state = state(player);
        state.armWTapExtraEligibility();
    }

    /** Clears expired one-shot W-tap bonuses from retained player sprint states. */
    public void tick() {
        long now = System.nanoTime();
        this.states.values().forEach(state -> state.expireWTapExtraEligibility(now));
    }

    /** Updates the virtual client sprint state for the server-controlled Bot. */
    public void updateSyntheticSprint(Player player, boolean sprinting) {
        if (!this.manager.isLegacyKnockback(player)) return;
        SprintState state = state(player);
        if (sprinting) {
            state.syntheticStart();
        } else {
            state.clientStop();
        }
    }

    /** Models the Bot immediately pressing sprint again after a successful hit. */
    public void forceSyntheticSprintStart(Player player) {
        if (this.manager.isLegacyKnockback(player)) state(player).clientStart();
    }

    /**
     * Drops sprint on both sides for an involuntary stop (consumable, backing
     * up, pause). Clears any in-flight W-tap marker so it can never be credited.
     */
    public void dropSyntheticSprint(Player player) {
        if (!this.manager.isLegacyKnockback(player)) return;
        SprintState state = this.states.get(player.getUniqueId());
        if (state != null) state.resetForcedStop();
    }

    /**
     * Automatic re-sprint after a forced stop. A 1.8 client re-sends
     * START_SPRINTING while W is held, so the sprint knockback bonus is
     * re-earned, but the stop was not a deliberate tap and must not arm the
     * W-tap extra.
     */
    public void restartSyntheticSprint(Player player) {
        if (!this.manager.isLegacyKnockback(player)) return;
        state(player).syntheticRestart();
    }

    private SprintState state(Player player) {
        return this.states.computeIfAbsent(
                player.getUniqueId(), ignored -> new SprintState(player.isSprinting()));
    }

    public record WTapResult(boolean attempt, boolean success) {
    }

    private static final class SprintState {
        private boolean clientSprinting;
        private boolean knockbackEligible;
        private boolean pendingStop;
        private boolean restartedAfterStop;
        private long lastStopNanos = Long.MIN_VALUE;
        private long wtapExtraExpiresAtNanos = Long.MIN_VALUE;
        private long lastRecordedHitTick = Long.MIN_VALUE;
        private WTapResult lastRecordedHitResult = NO_WTAP;

        private SprintState(boolean initiallySprinting) {
            this.clientSprinting = initiallySprinting;
            this.knockbackEligible = initiallySprinting;
        }

        /** Every explicit client START rearms KB, matching WindSpigot. */
        private synchronized void clientStart() {
            this.clientSprinting = true;
            this.knockbackEligible = true;
            if (this.pendingStop) this.restartedAfterStop = true;
        }

        /** Synthetic Bot input only rearms on a real false-to-true transition. */
        private synchronized void syntheticStart() {
            if (this.clientSprinting) return;
            clientStart();
        }

        private synchronized void clientStop() {
            if (this.clientSprinting) {
                this.pendingStop = true;
                this.restartedAfterStop = false;
                this.lastStopNanos = System.nanoTime();
            }
            this.clientSprinting = false;
            this.knockbackEligible = false;
        }

        /**
         * Automatic restart after a forced stop: sprint and eligibility are
         * restored, the tap marker is cleared so it is never credited as a
         * deliberate W-tap.
         */
        private synchronized void syntheticRestart() {
            this.clientSprinting = true;
            this.knockbackEligible = true;
            this.pendingStop = false;
            this.restartedAfterStop = false;
            this.lastStopNanos = Long.MIN_VALUE;
        }

        /**
         * Involuntary stop on both sides. Clears any in-flight tap marker so a
         * forced stop can never be credited as a W-tap.
         */
        private synchronized void resetForcedStop() {
            this.clientSprinting = false;
            this.knockbackEligible = false;
            this.pendingStop = false;
            this.restartedAfterStop = false;
            this.lastStopNanos = Long.MIN_VALUE;
        }

        private synchronized boolean hasKnockbackEligibility() {
            return this.knockbackEligible;
        }

        private synchronized void consumeKnockbackEligibility() {
            this.knockbackEligible = false;
        }

        private synchronized WTapResult recordAcceptedMeleeHit() {
            long currentTick = org.bukkit.Bukkit.getCurrentTick();
            if (this.lastRecordedHitTick == currentTick) return this.lastRecordedHitResult;

            if (!this.pendingStop) return NO_WTAP;

            long elapsed = System.nanoTime() - this.lastStopNanos;
            boolean attempt = elapsed >= 0L && elapsed <= WTAP_WINDOW_NANOS;
            boolean success = attempt && this.restartedAfterStop && this.knockbackEligible;
            this.pendingStop = false;
            this.restartedAfterStop = false;
            this.lastStopNanos = Long.MIN_VALUE;
            if (success) this.wtapExtraExpiresAtNanos = System.nanoTime() + WTAP_EXTRA_LIFETIME_NANOS;
            this.lastRecordedHitTick = currentTick;
            this.lastRecordedHitResult = attempt ? new WTapResult(true, success) : NO_WTAP;
            return this.lastRecordedHitResult;
        }

        private synchronized boolean consumeWTapExtraEligibility() {
            if (!hasWTapExtraEligibility(System.nanoTime())) return false;
            this.wtapExtraExpiresAtNanos = Long.MIN_VALUE;
            return true;
        }

        private synchronized void armWTapExtraEligibility() {
            this.wtapExtraExpiresAtNanos = System.nanoTime() + WTAP_EXTRA_LIFETIME_NANOS;
        }

        private synchronized void expireWTapExtraEligibility(long now) {
            hasWTapExtraEligibility(now);
        }

        private boolean hasWTapExtraEligibility(long now) {
            if (this.wtapExtraExpiresAtNanos == Long.MIN_VALUE) return false;
            if (now <= this.wtapExtraExpiresAtNanos) return true;
            this.wtapExtraExpiresAtNanos = Long.MIN_VALUE;
            return false;
        }
    }
}
