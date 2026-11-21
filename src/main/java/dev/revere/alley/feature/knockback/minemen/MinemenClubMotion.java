package dev.revere.alley.feature.knockback.minemen;

import org.bukkit.util.Vector;

/** EntityLiving base and EntityHuman melee extra from the supplied ClubSpigot source. */
public final class MinemenClubMotion {
    private static final float[] SIN = new float[65536];

    static {
        for (int i = 0; i < SIN.length; i++) {
            SIN[i] = (float) Math.sin(i * Math.PI * 2.0D / 65536.0D);
        }
    }

    private MinemenClubMotion() {
    }

    public static double rangeReduction(MinemenClubSettings settings, double distance) {
        if (distance <= settings.startRangeReduction()) return 0.0D;
        // Club subtracts maxRangeReduction, NOT startRangeReduction.
        // minRange caps the reduction; it is not a minimum horizontal velocity.
        return Math.min(settings.rangeFactor() * (distance - settings.maxRangeReduction()),
                settings.minRange());
    }

    public static Vector base(Vector previous, double awayX, double awayZ, double distance,
                              MinemenClubSettings settings) {
        // Preserve EntityLiving#a's float intermediates, including the normalizer.
        double length = (float) Math.sqrt(awayX * awayX + awayZ * awayZ);
        if (length == 0.0D) throw new IllegalArgumentException("Knockback direction is zero");
        double horizontal = (float) (0.4D * (1.0D - 0.4D * (1.0D - settings.horizontal())))
                - rangeReduction(settings, distance);
        double vertical = settings.vertical();
        if (settings.verticalLimit()) vertical = Math.min(vertical, settings.verticalLimitValue());
        double friction = settings.horizontalFriction();
        return new Vector(previous.getX() / friction + awayX / length * horizontal,
                vertical, previous.getZ() / friction + awayZ / length * horizontal);
    }

    /** Every successful player melee adds this, even if base KB was resisted or omitted. */
    public static void addMelee(Vector motion, float yaw, MinemenClubSettings attackerSettings) {
        // Match the 1.8 MathHelper float angle and lookup table as well as the formula.
        float radians = yaw * (float) Math.PI / 180.0F;
        float sin = SIN[(int) (radians * 10430.378F) & 65535];
        float cos = SIN[(int) (radians * 10430.378F + 16384.0F) & 65535];
        motion.add(new Vector(-sin * attackerSettings.sprintMultiplier() * 0.5F, 0.1D,
                cos * attackerSettings.sprintMultiplier() * 0.5F));
    }

    public static void addPunch(Vector motion, Vector flight, int level) {
        double length = Math.hypot(flight.getX(), flight.getZ());
        if (level <= 0 || length == 0.0D) return;
        motion.add(new Vector(flight.getX() / length * 0.6D * level, 0.1D,
                flight.getZ() / length * 0.6D * level));
    }

    public static boolean resistsBase(double resistance, double sample) {
        return sample < resistance;
    }
}
