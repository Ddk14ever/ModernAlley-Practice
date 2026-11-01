package dev.revere.alley.feature.match.combat.legacy;

import dev.revere.alley.AlleyPlugin;
import dev.revere.alley.feature.bot.match.BotMatchSession;
import org.bukkit.FluidCollisionMode;
import org.bukkit.Location;
import org.bukkit.entity.AbstractArrow;
import org.bukkit.entity.EnderPearl;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.entity.Projectile;
import org.bukkit.util.BoundingBox;
import org.bukkit.util.RayTraceResult;
import org.bukkit.util.Vector;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
/**
 * @author Ddk1 ClaudeCode
 * @project Alley
 * @since 13/06/2026
 *
 * Restores the 1.8.8 swept entity collision used by arrows and throwables.
 */

final class LegacyProjectileCollisionTracker {
    private static final int DEFAULT_OWNER_COLLISION_TICK = 5;
    private static final double BROAD_PHASE_EXPANSION = 1.3;

    private final LegacyCombatService combatService;
    private final Map<UUID, TrackedProjectile> trackedProjectiles = new LinkedHashMap<>();
    private Method getHandleMethod;
    private Method fixedDimensionsMethod;
    private Method makeBoundingBoxMethod;
    private Method setBoundingBoxMethod;
    private Field dimensionsField;
    private boolean pearlSizeWarningLogged;

    LegacyProjectileCollisionTracker(LegacyCombatService combatService) {
        this.combatService = combatService;
        AlleyPlugin plugin = AlleyPlugin.getInstance();
        plugin.getServer().getScheduler().runTaskTimer(plugin, this::tick, 1L, 1L);
    }

    void track(Projectile projectile, Player shooter) {
        // 1.8 only gates collisions with the owner; other entities are hittable immediately.
        projectile.setHasLeftShooter(false);
        if (projectile instanceof EnderPearl pearl) {
            ensurePearlSize(pearl);
        }
        this.trackedProjectiles.put(projectile.getUniqueId(),
                new TrackedProjectile(projectile, shooter, projectile.getLocation().clone()));
    }

    void stopTracking(Projectile projectile) {
        this.trackedProjectiles.remove(projectile.getUniqueId());
    }

    boolean isTracking(Projectile projectile) {
        return this.trackedProjectiles.containsKey(projectile.getUniqueId());
    }

    boolean isOwnerImmune(Projectile projectile) {
        // 1.8 EntityThrowable#onUpdate skips the thrower only while ticksInAir < 5;
        // after that the thrower can be hit by their own throwable (pearls included).
        return projectile.getTicksLived() < DEFAULT_OWNER_COLLISION_TICK;
    }

    private void tick() {
        Iterator<TrackedProjectile> iterator = this.trackedProjectiles.values().iterator();
        while (iterator.hasNext()) {
            TrackedProjectile tracked = iterator.next();
            Projectile projectile = tracked.projectile;
            Player shooter = tracked.shooter;

            if (!isActive(projectile, shooter)) {
                iterator.remove();
                continue;
            }

            boolean ownerCollidable = !isOwnerImmune(projectile);
            projectile.setHasLeftShooter(ownerCollidable);
            if (projectile instanceof EnderPearl pearl) {
                ensurePearlSize(pearl);
            }

            Location currentLocation = projectile.getLocation();
            LegacyEntityHit entityHit = findEntityHit(tracked.previousLocation, currentLocation,
                    projectile, shooter, ownerCollidable);
            tracked.previousLocation = currentLocation.clone();
            if (entityHit == null) continue;

            iterator.remove();
            projectile.hitEntity(entityHit.entity(), entityHit.hitPosition());
        }
    }

    private boolean isActive(Projectile projectile, Player shooter) {
        if (!projectile.isValid() || projectile.isDead()) return false;
        if (!shooter.isOnline() || shooter.isDead()) return false;
        if (!this.combatService.hasSwordBlockKB(shooter.getUniqueId())) return false;
        return !(projectile instanceof AbstractArrow arrow) || !arrow.isInBlock();
    }

    private void ensurePearlSize(EnderPearl pearl) {
        BoundingBox currentBox = pearl.getBoundingBox();
        if (Math.abs(currentBox.getWidthX() - LegacyHitboxes.PEARL_SIZE) < 1.0E-4
                && Math.abs(currentBox.getHeight() - LegacyHitboxes.PEARL_SIZE) < 1.0E-4) return;

        try {
            if (this.getHandleMethod == null) {
                this.getHandleMethod = pearl.getClass().getMethod("getHandle");
            }
            Object handle = this.getHandleMethod.invoke(pearl);
            if (this.dimensionsField == null) {
                this.dimensionsField = findField(handle.getClass(), "dimensions");
                this.dimensionsField.setAccessible(true);
                Class<?> dimensionsType = this.dimensionsField.getType();
                this.fixedDimensionsMethod = dimensionsType.getMethod("fixed", float.class, float.class);
                this.makeBoundingBoxMethod = dimensionsType.getMethod(
                        "makeBoundingBox", double.class, double.class, double.class);
                this.setBoundingBoxMethod = handle.getClass().getMethod(
                        "setBoundingBox", this.makeBoundingBoxMethod.getReturnType());
            }

            Object dimensions = this.fixedDimensionsMethod.invoke(
                    null, LegacyHitboxes.PEARL_SIZE, LegacyHitboxes.PEARL_SIZE);
            this.dimensionsField.set(handle, dimensions);
            Location location = pearl.getLocation();
            Object boundingBox = this.makeBoundingBoxMethod.invoke(
                    dimensions, location.getX(), location.getY(), location.getZ());
            this.setBoundingBoxMethod.invoke(handle, boundingBox);
        } catch (ReflectiveOperationException exception) {
            if (!this.pearlSizeWarningLogged) {
                this.pearlSizeWarningLogged = true;
                AlleyPlugin.getInstance().getLogger().warning(
                        "Unable to apply the 0.23 legacy ender pearl size: "
                                + exception.getClass().getSimpleName());
            }
        }
    }

    private Field findField(Class<?> type, String name) throws NoSuchFieldException {
        Class<?> current = type;
        while (current != null) {
            try {
                return current.getDeclaredField(name);
            } catch (NoSuchFieldException ignored) {
                current = current.getSuperclass();
            }
        }
        throw new NoSuchFieldException(name);
    }

    private LegacyEntityHit findEntityHit(Location startLocation, Location endLocation,
                                          Projectile projectile, Player shooter, boolean ownerCollidable) {
        if (startLocation.getWorld() == null || startLocation.getWorld() != endLocation.getWorld()) return null;

        Vector start = startLocation.toVector();
        Vector travel = endLocation.toVector().subtract(start);
        double distance = travel.length();
        if (distance <= 1.0E-7) return null;

        Vector direction = travel.clone().multiply(1.0 / distance);
        RayTraceResult blockHit = startLocation.getWorld().rayTraceBlocks(
                startLocation, direction, distance, FluidCollisionMode.NEVER, true);
        double clippedDistance = blockHit == null
                ? distance
                : Math.min(distance, start.distance(blockHit.getHitPosition()));

        Vector clippedEnd = start.clone().add(direction.clone().multiply(clippedDistance));
        BoundingBox broadPhase = BoundingBox.of(start, clippedEnd).expand(BROAD_PHASE_EXPANSION);
        Entity nearest = null;
        Vector nearestHitPosition = null;
        double nearestDistance = Double.MAX_VALUE;

        for (Entity candidate : startLocation.getWorld().getNearbyEntities(broadPhase)) {
            if (candidate.equals(projectile) || candidate.isDead() || !candidate.isValid()) continue;
            if (candidate.equals(shooter) && !ownerCollidable) continue;
            boolean alleyBot = candidate.getScoreboardTags().contains(BotMatchSession.BOT_ENTITY_TAG);
            if (!candidate.equals(shooter) && !alleyBot && !projectile.canHitEntity(candidate)) continue;

            BoundingBox targetBox;
            if (projectile instanceof EnderPearl) {
                // Pearls keep their normal entity collision box. The 1.8 projectile
                // expansion is retained for arrows, snowballs, potions and rods.
                targetBox = candidate.getBoundingBox().clone();
            } else {
                targetBox = candidate instanceof Player
                        ? LegacyHitboxes.projectileTarget(candidate)
                        : candidate.getBoundingBox().clone().expand(LegacyHitboxes.PROJECTILE_EXPAND);
            }
            double hitDistance;
            Vector hitPosition;
            if (targetBox.contains(start.getX(), start.getY(), start.getZ())) {
                hitDistance = 0.0;
                hitPosition = start.clone();
            } else {
                RayTraceResult entityHit = targetBox.rayTrace(start, direction, clippedDistance);
                if (entityHit == null) continue;
                hitPosition = entityHit.getHitPosition();
                hitDistance = start.distance(hitPosition);
            }

            if (hitDistance < nearestDistance) {
                nearest = candidate;
                nearestHitPosition = hitPosition;
                nearestDistance = hitDistance;
            }
        }
        return nearest == null ? null : new LegacyEntityHit(nearest, nearestHitPosition);
    }

    private static final class TrackedProjectile {
        private final Projectile projectile;
        private final Player shooter;
        private Location previousLocation;

        private TrackedProjectile(Projectile projectile, Player shooter, Location previousLocation) {
            this.projectile = projectile;
            this.shooter = shooter;
            this.previousLocation = previousLocation;
        }
    }

    private record LegacyEntityHit(Entity entity, Vector hitPosition) {
    }
}
