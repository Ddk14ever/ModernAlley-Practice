package dev.revere.alley.feature.knockback.minemen;

import org.bukkit.configuration.file.FileConfiguration;

/** Club profile fields plus independent friction/gravity controls, isolated from LEGACY. */
public record MinemenClubSettings(double horizontal, double vertical, double sprintMultiplier,
                                 double rangeFactor, double maxRangeReduction,
                                 double startRangeReduction, double minRange,
                                 boolean verticalLimit, double verticalLimitValue,
                                 double horizontalFriction, double gravity) {
    public static MinemenClubSettings load(FileConfiguration config) {
        double horizontal = value(config, "horizontal", 0.9055D);
        // Preserve the previous Club formula when upgrading an existing profile.
        double originalFriction = 2.0D - (1.0D - horizontal);
        return new MinemenClubSettings(
                horizontal,
                value(config, "vertical", 0.25635D),
                value(config, "sprint-multiplier", 1.0D),
                value(config, "range-factor", 0.025D),
                value(config, "max-range-reduction", 1.2D),
                value(config, "start-range-reduction", 3.0D),
                value(config, "min-range", 0.12D),
                config.getBoolean("vertical-limit", true),
                value(config, "vertical-limit-value", 4.0D),
                positive(config, "friction.horizontal", originalFriction),
                gravity(config));
    }

    private static double positive(FileConfiguration config, String key, double fallback) {
        double value = config.getDouble(key, fallback);
        return Double.isFinite(value) && value > 0.0D ? value : fallback;
    }

    private static double gravity(FileConfiguration config) {
        double value = config.getDouble("gravity", 0.08D);
        // The 1.21.11 GRAVITY attribute accepts negative (upward) acceleration too.
        return Double.isFinite(value) && value >= -1.0D && value <= 1.0D ? value : 0.08D;
    }

    private static double value(FileConfiguration config, String key, double fallback) {
        double value = config.getDouble(key, fallback);
        return Double.isFinite(value) && value >= 0.0D ? value : fallback;
    }
}
