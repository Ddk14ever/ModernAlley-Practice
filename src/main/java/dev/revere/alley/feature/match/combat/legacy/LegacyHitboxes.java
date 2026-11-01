package dev.revere.alley.feature.match.combat.legacy;

import org.bukkit.Location;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.util.BoundingBox;

/**
 * 1.8.8 combat uses the standing player box, then expands it.
 * Sneaking does not shrink the box; only the eye height changes.
 */
public final class LegacyHitboxes {
    public static final double PLAYER_WIDTH = 0.6D;
    public static final double PLAYER_HEIGHT = 1.8D;
    /** Entity#getCollisionBorderSize in 1.8 melee raycasts. */
    public static final double MELEE_EXPAND = 0.1D;
    /** Arrow, throwable and fishing-hook intercept expansion in 1.8. */
    public static final double PROJECTILE_EXPAND = 0.3D;
    /** Legacy ender pearl entity size. */
    public static final float PEARL_SIZE = 0.23F;

    private LegacyHitboxes() {
    }

    public static BoundingBox standingPlayerBox(Location location) {
        double half = PLAYER_WIDTH / 2.0D;
        double x = location.getX();
        double y = location.getY();
        double z = location.getZ();
        return new BoundingBox(x - half, y, z - half, x + half, y + PLAYER_HEIGHT, z + half);
    }

    public static BoundingBox standingPlayerBox(Entity entity) {
        return standingPlayerBox(entity.getLocation());
    }

    /** 1.8 melee pick: standing 0.6x1.8 expanded by 0.1. */
    public static BoundingBox meleeTarget(Entity entity) {
        return combatBase(entity).expand(MELEE_EXPAND);
    }

    /** 1.8 arrow / snowball / pearl / rod intercept: standing box expanded by 0.3. */
    public static BoundingBox projectileTarget(Entity entity) {
        return combatBase(entity).expand(PROJECTILE_EXPAND);
    }

    private static BoundingBox combatBase(Entity entity) {
        if (entity instanceof Player) {
            return standingPlayerBox(entity);
        }
        return entity.getBoundingBox().clone();
    }
}
