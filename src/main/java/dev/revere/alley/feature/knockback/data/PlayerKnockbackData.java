package dev.revere.alley.feature.knockback.data;

import dev.revere.alley.feature.knockback.KnockbackBranch;
import org.bukkit.util.Vector;

import java.util.UUID;
/**
 * @author Ddk1 ClaudeCode
 * @project Alley
 * @since 04/07/2026
 *
 * Per-player knockback state.
 */
public class PlayerKnockbackData {
    private Vector velocity;
    private long lastDamageTick;
    private double lastGroundY;
    private boolean onGround;
    private String profileName;
    private KnockbackBranch branch = KnockbackBranch.DEFAULT;
    private int configuredHitDelayWindow = -1;
    private boolean serverSideHit;
    private boolean serverControlled;
    private boolean captureServerControlledVelocity;
    private Vector serverControlledVelocity;
    private long pendingNativeProjectileVelocityTick = Long.MIN_VALUE;
    private boolean suppressLegacyPearlVelocity;
    private boolean legacyDamageWindowActive;
    private long legacyDamageWindowTick = Long.MIN_VALUE;
    private long legacyDamageSupplementTick = Long.MIN_VALUE;
    private UUID legacyDamageSupplementSource;
    private UUID legacyDamageSupplementAttacker;

    public Vector getVelocity() { return velocity; }
    public void setVelocity(Vector v) { this.velocity = v; }

    public long getLastDamageTick() { return lastDamageTick; }
    public void setLastDamageTick(long t) { this.lastDamageTick = t; }

    public double getLastGroundY() { return lastGroundY; }
    public void setLastGroundY(double y) { this.lastGroundY = y; }

    public boolean isOnGround() { return onGround; }
    public void setOnGround(boolean g) { this.onGround = g; }

    public String getProfileName() { return profileName; }
    public void setProfileName(String n) { this.profileName = n; }

    public KnockbackBranch getBranch() { return branch; }
    public void setBranch(KnockbackBranch branch) {
        this.branch = branch == null ? KnockbackBranch.DEFAULT : branch;
    }

    public int getConfiguredHitDelayWindow() { return configuredHitDelayWindow; }
    public void setConfiguredHitDelayWindow(int hitDelayWindow) { this.configuredHitDelayWindow = hitDelayWindow; }

    public boolean isServerSideHit() { return serverSideHit; }
    public void setServerSideHit(boolean s) { this.serverSideHit = s; }

    public boolean isServerControlled() { return serverControlled; }
    public void setServerControlled(boolean serverControlled) { this.serverControlled = serverControlled; }

    public boolean isCaptureServerControlledVelocity() { return captureServerControlledVelocity; }
    public void setCaptureServerControlledVelocity(boolean capture) { this.captureServerControlledVelocity = capture; }

    public void setServerControlledVelocity(Vector velocity) { this.serverControlledVelocity = velocity; }
    public Vector consumeServerControlledVelocity() {
        Vector velocity = this.serverControlledVelocity;
        this.serverControlledVelocity = null;
        return velocity;
    }

    /** Same captured slot, named for what it is: a vector seen on the velocity event. */
    public Vector consumeCapturedKnockback() {
        return consumeServerControlledVelocity();
    }

    public long getPendingNativeProjectileVelocityTick() { return pendingNativeProjectileVelocityTick; }
    public void setPendingNativeProjectileVelocityTick(long tick) { this.pendingNativeProjectileVelocityTick = tick; }
    public void clearPendingNativeProjectileVelocity() { this.pendingNativeProjectileVelocityTick = Long.MIN_VALUE; }

    public boolean isSuppressLegacyPearlVelocity() { return suppressLegacyPearlVelocity; }
    public void setSuppressLegacyPearlVelocity(boolean suppress) { this.suppressLegacyPearlVelocity = suppress; }

    public boolean isLegacyDamageWindowActive(long tick) {
        return legacyDamageWindowActive && legacyDamageWindowTick == tick;
    }
    public void setLegacyDamageWindowActive(boolean active, long tick) {
        this.legacyDamageWindowActive = active;
        this.legacyDamageWindowTick = tick;
    }

    public long getLegacyDamageSupplementTick() { return legacyDamageSupplementTick; }
    public UUID getLegacyDamageSupplementSource() { return legacyDamageSupplementSource; }
    public UUID getLegacyDamageSupplementAttacker() { return legacyDamageSupplementAttacker; }
    public void markLegacyDamageSupplement(long tick, UUID source, UUID attacker) {
        this.legacyDamageSupplementTick = tick;
        this.legacyDamageSupplementSource = source;
        this.legacyDamageSupplementAttacker = attacker;
    }
    public void clearLegacyDamageSupplement() {
        this.legacyDamageSupplementTick = Long.MIN_VALUE;
        this.legacyDamageSupplementSource = null;
        this.legacyDamageSupplementAttacker = null;
    }

    public void clearLegacyState() {
        this.pendingNativeProjectileVelocityTick = Long.MIN_VALUE;
        this.suppressLegacyPearlVelocity = false;
        this.captureServerControlledVelocity = false;
        this.serverControlledVelocity = null;
        this.legacyDamageWindowActive = false;
        this.legacyDamageWindowTick = Long.MIN_VALUE;
        clearLegacyDamageSupplement();
    }
}
