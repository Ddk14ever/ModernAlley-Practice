package dev.revere.alley.feature.knockback;

import dev.revere.alley.feature.kit.setting.types.combat.KitSettingOldHitDelay;
import lombok.Getter;
import org.bukkit.configuration.file.FileConfiguration;
/**
 * @author Ddk1 ClaudeCode
 * @project Alley
 * @since 05/07/2026
 *
 * Branch-aware KB profile model loaded from knockback/*.yml.
 */
@Getter
public class KnockbackProfile {
    private final String name;
    private KnockbackBranch branch;
    private double horizontalGround, horizontalAir, horizontalSprintExtra, legacyWTapExtra;
    private double verticalGround, verticalAir, verticalSprintExtra;
    private double legacyVerticalLimit;
    private double legacyAttackerHorizontalSlowdown;
    private double legacyHorizontalFriction;
    private boolean legacyDistanceReductionEnabled;
    private double legacyDistanceReductionStart;
    private double legacyDistanceReductionFactor;
    private double legacyDistanceReductionMaximum;
    private double legacyDistanceMinimumHorizontal;
    private double legacyArrowHorizontal, legacyArrowVertical, legacyArrowVerticalLimit;
    private double legacyArrowPunchHorizontal, legacyArrowPunchVertical;
    private int hitDelay;
    private double yLimit;
    private boolean stopSprint;
    private boolean projectileEnabled;
    private double projectileHorizontal, projectileVertical;
    private double projectileHorizontalMult, projectileVerticalMult;
    private boolean projectileDirectionOverride;
    private boolean disableDownwardKb;
    private boolean potionEnabled;
    private double potionHorizontalMult, potionVerticalMult;
    private double potionCompensationMultiplier;
    private double potionSpeedCompensation;
    private double potionHorizontalCompensation;
    private double potionHorizontalOffset, potionVerticalOffset;
    private boolean cooldownAffectsKb;
    private double entityInteractionRange;
    private boolean packetMisplaceEnabled;
    private double packetMisplaceDistance;
    private double packetMisplaceNormalDistance;
    private double packetMisplaceHitDistance;
    private int packetMisplaceHitWindowTicks;
    private boolean packetMisplaceHitCompensation;
    private double packetMisplaceMaxHitCompensation;
    private boolean packetDelayEnabled;
    private int packetDelayTicks;
    private double hitboxLength, hitboxHeight;

    public KnockbackProfile(String name, FileConfiguration config) {
        this.name = name;
        reload(config);
    }

    public void reload(FileConfiguration config) {
        KnockbackBranch configuredBranch = KnockbackBranch.fromName(config.getString("branch"));
        branch = configuredBranch == null ? KnockbackBranch.DEFAULT : configuredBranch;
        horizontalGround = config.getDouble("horizontal.ground", 0.35);
        horizontalAir = config.getDouble("horizontal.air", 0.35);
        horizontalSprintExtra = config.getDouble("horizontal.sprint_extra", 0.2);
        legacyWTapExtra = nonNegative(config.getDouble("horizontal.wtap_extra", 0.1));
        verticalGround = config.getDouble("vertical.ground", 0.36);
        verticalAir = config.getDouble("vertical.air", 0.36);
        verticalSprintExtra = config.getDouble("vertical.sprint_extra", 0.1);
        hitDelay = config.getInt("hit_delay", KitSettingOldHitDelay.DEFAULT_DELAY);
        yLimit = config.getDouble("y_limit", 3.0);
        disableDownwardKb = branch == KnockbackBranch.LEGACY
                || config.getBoolean("disable_downward_kb", false);
        stopSprint = config.getBoolean("stop_sprint", false);
        legacyVerticalLimit = config.getDouble("vertical.limit", 0.4);
        legacyAttackerHorizontalSlowdown = config.getDouble("attacker.horizontal_slowdown", 0.6);
        legacyHorizontalFriction = positiveOrDefault(
                config.getDouble("friction.horizontal", 2.0), 2.0);
        legacyDistanceReductionEnabled = config.getBoolean("distance_reduction.enabled", true);
        legacyDistanceReductionStart = nonNegative(
                config.getDouble("distance_reduction.start", 3.0));
        legacyDistanceReductionFactor = nonNegative(
                config.getDouble("distance_reduction.factor", 0.025));
        legacyDistanceReductionMaximum = nonNegative(
                config.getDouble("distance_reduction.maximum", 1.2));
        legacyDistanceMinimumHorizontal = nonNegative(
                config.getDouble("distance_reduction.minimum_horizontal", 0.12));
        legacyArrowHorizontal = config.getDouble("arrow.horizontal", 0.4);
        legacyArrowVertical = config.getDouble("arrow.vertical", 0.4);
        legacyArrowVerticalLimit = config.getDouble("arrow.vertical_limit", 0.4);
        legacyArrowPunchHorizontal = config.getDouble("arrow.punch_horizontal", 0.6);
        legacyArrowPunchVertical = config.getDouble("arrow.punch_vertical", 0.1);
        projectileEnabled = config.getBoolean("projectile.enabled", true);
        projectileHorizontal = config.getDouble("projectile.horizontal", 0.15);
        projectileVertical = config.getDouble("projectile.vertical", 0.15);
        projectileHorizontalMult = config.getDouble("projectile.horizontal_multiplier", 1.0);
        projectileVerticalMult = config.getDouble("projectile.vertical_multiplier", 1.0);
        projectileDirectionOverride = config.getBoolean("projectile.direction_override", false);
        potionEnabled = config.getBoolean("potion.enabled", false);
        potionHorizontalMult = config.getDouble("potion.horizontal_multiplier", 1.0);
        potionVerticalMult = config.getDouble("potion.vertical_multiplier", 1.0);
        potionCompensationMultiplier = config.getDouble("potion.compensation_multiplier", 1.0);
        double defaultPotionSpeedCompensation = name.equalsIgnoreCase("1_7")
                || name.equalsIgnoreCase("1_8") ? 0.0 : 1.0;
        potionSpeedCompensation = config.getDouble(
                "potion.speed_compensation", defaultPotionSpeedCompensation);
        potionHorizontalCompensation = config.getDouble("potion.horizontal_compensation", 0.0);
        potionHorizontalOffset = config.getDouble("potion.horizontal_offset", 0.0);
        potionVerticalOffset = config.getDouble("potion.vertical_offset", 0.0);
        cooldownAffectsKb = config.getBoolean("modern.cooldown_affects_kb", false);
        entityInteractionRange = config.getDouble("modern.entity_interaction_range", 3.0);
        packetMisplaceEnabled = config.getBoolean("packet.misplace.enabled", false);
        packetMisplaceDistance = config.getDouble("packet.misplace.distance", 0.1);
        packetMisplaceNormalDistance = Math.max(0.0D,
                config.getDouble("packet.misplace.normal_distance", 0.05D));
        packetMisplaceHitDistance = Math.max(0.0D,
                config.getDouble("packet.misplace.hit_distance", packetMisplaceDistance));
        packetMisplaceHitWindowTicks = Math.max(0,
                config.getInt("packet.misplace.hit_window_ticks", 4));
        packetMisplaceHitCompensation = config.getBoolean(
                "packet.misplace.hit_compensation", true);
        packetMisplaceMaxHitCompensation = Math.max(0.0D,
                config.getDouble("packet.misplace.max_hit_compensation", 0.3D));
        packetDelayEnabled = config.getBoolean("packet.delay.enabled", false);
        packetDelayTicks = config.getInt("packet.delay.ticks", 2);
        hitboxLength = config.getDouble("hitbox.length", 0.6);
        hitboxHeight = config.getDouble("hitbox.height", 1.8);
    }

    private static double positiveOrDefault(double value, double fallback) {
        return Double.isFinite(value) && value > 0.0D ? value : fallback;
    }

    private static double nonNegative(double value) {
        return Double.isFinite(value) ? Math.max(0.0D, value) : 0.0D;
    }
}
