package dev.revere.alley.feature.knockback.minemen;

import org.bukkit.util.Vector;

/** Ordinary air physics and the additive difference from vanilla gravity. */
final class MinemenClubGravityMotion {
    private MinemenClubGravityMotion() {
    }

    static Vector next(Vector movement, double gravity) {
        // Travel moves first, then applies gravity and drag. Never apply the
        // profile's on-hit friction here: it is not airborne movement drag.
        return new Vector(movement.getX() * (double) 0.91F,
                (movement.getY() - gravity) * (double) 0.98F,
                movement.getZ() * (double) 0.91F);
    }

    static double correction(double gravity) {
        // (Y - customGravity) * drag - (Y - vanillaGravity) * drag.
        // Independent of the delayed sample's Y and, crucially, its X/Z.
        return (0.08D - gravity) * (double) 0.98F;
    }

    static boolean finite(Vector velocity) {
        return Double.isFinite(velocity.getX()) && Double.isFinite(velocity.getY())
                && Double.isFinite(velocity.getZ());
    }
}
