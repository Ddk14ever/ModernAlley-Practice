package dev.revere.alley.feature.knockback.hitbox;

import dev.revere.alley.AlleyPlugin;
import dev.revere.alley.feature.knockback.KnockbackManager;
import dev.revere.alley.feature.knockback.KnockbackProfile;
import dev.revere.alley.feature.knockback.data.PlayerKnockbackData;
import dev.revere.alley.feature.knockback.packet.MisplaceHandler;
import dev.revere.alley.feature.match.MatchService;
import dev.revere.alley.feature.match.combat.legacy.LegacyCombatService;
import dev.revere.alley.feature.match.combat.legacy.LegacyHitboxes;
import dev.revere.alley.feature.match.internal.MatchServiceImpl;
import org.bukkit.Bukkit;
import org.bukkit.FluidCollisionMode;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.player.PlayerAnimationEvent;
import org.bukkit.event.player.PlayerAnimationType;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerItemHeldEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerTeleportEvent;
import org.bukkit.util.RayTraceResult;
import org.bukkit.util.Vector;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Adds configured hitbox reach without replacing normal client attacks.
 */
public class HitDetection implements Listener {
    private final KnockbackManager manager;
    private final Map<UUID, Integer> lastSwingTick = new ConcurrentHashMap<>();

    public HitDetection(KnockbackManager manager) {
        this.manager = manager;
    }

    @EventHandler
    public void onSwing(PlayerAnimationEvent event) {
        if (event.getAnimationType() != PlayerAnimationType.ARM_SWING) return;
        Player attacker = event.getPlayer();
        if (!isMeleeWeapon(attacker.getInventory().getItemInMainHand().getType())) return;

        KnockbackProfile profile = manager.getAppliedProfile(attacker);
        if (profile == null) return;
        boolean needsVisualCompensation = profile.isPacketMisplaceEnabled()
                && profile.isPacketMisplaceHitCompensation();
        if (!hasLegacySwordCombat(attacker) && !needsVisualCompensation
                && profile.getHitboxLength() <= 0.6D && profile.getHitboxHeight() <= 1.8D) return;

        int currentTick = Bukkit.getCurrentTick();
        Integer lastTick = lastSwingTick.put(attacker.getUniqueId(), currentTick);
        if (lastTick != null && lastTick == currentTick) return;

        detectAndAttack(attacker, profile);
    }

    @EventHandler
    public void onInteract(PlayerInteractEvent event) {
        if (event.getAction() == Action.RIGHT_CLICK_AIR
                || event.getAction() == Action.RIGHT_CLICK_BLOCK) {
            lastSwingTick.remove(event.getPlayer().getUniqueId());
        }
    }

    @EventHandler
    public void onItemHeld(PlayerItemHeldEvent event) {
        clearPlayer(event.getPlayer().getUniqueId());
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        clearPlayer(event.getPlayer().getUniqueId());
    }

    @EventHandler
    public void onTeleport(PlayerTeleportEvent event) {
        clearPlayer(event.getPlayer().getUniqueId());
    }

    public void clearPlayer(UUID playerId) {
        lastSwingTick.remove(playerId);
    }

    public void clear() {
        lastSwingTick.clear();
    }

    private void detectAndAttack(Player attacker, KnockbackProfile profile) {
        RayContext context = createRayContext(attacker, profile);
        if (context == null) return;

        Player closest = null;
        Vector closestHit = null;
        double closestDistance = context.maxDistance();
        boolean vanillaHit = false;

        for (Entity entity : attacker.getNearbyEntities(
                context.maxDistance() + 2.0D, context.maxDistance() + 2.0D,
                context.maxDistance() + 2.0D)) {
            if (!(entity instanceof Player victim) || !isUsableTarget(victim)
                    || victim.getUniqueId().equals(attacker.getUniqueId())) continue;

            RayHit hit = resolveHit(attacker, victim, profile, context);
            if (hit == null) continue;
            double distance = hit.distance();
            if (distance <= closestDistance) {
                closest = victim;
                closestHit = hit.position();
                closestDistance = distance;
                vanillaHit = hit.vanillaHit();
            }
        }

        Location eye = context.eye();
        if (closest == null || closestHit == null || isBlocked(eye, closestHit)) return;

        // A hit inside the vanilla box belongs to the client's normal attack.
        // Only the configured/legacy hitbox extension needs a server attack.
        if (vanillaHit) return;

        fireAttack(attacker, closest, profile);
    }

    /**
     * Builds the per-swing ray state: eye position, direction, the 1.8-style
     * max distance, and which hitbox variant applies.
     */
    private RayContext createRayContext(Player attacker, KnockbackProfile profile) {
        double maxDistance = getMaxDistance(profile);
        if (maxDistance <= 0.0D) return null;

        Location eye = attacker.getEyeLocation();
        Vector direction = eye.getDirection();
        if (direction.lengthSquared() < 1.0E-8D) return null;

        boolean legacyMelee = hasLegacySwordCombat(attacker);
        AABB expandedBox = null;
        if (!legacyMelee) {
            double halfWidth = profile.getHitboxLength() / 2.0D;
            double verticalExpansion = Math.max(0.0D, (profile.getHitboxHeight() - 1.8D) / 2.0D);
            expandedBox = new AABB(
                    new Vector(-halfWidth, -verticalExpansion, -halfWidth),
                    new Vector(halfWidth, profile.getHitboxHeight() - verticalExpansion, halfWidth));
        }
        return new RayContext(eye, new Ray(eye.toVector(), direction), expandedBox,
                AABB.fromSize(0.6D, 1.8D), maxDistance, legacyMelee);
    }

    /**
     * Intersects the current swing ray with one target's 1.8/legacy or profile
     * hitbox, and reports whether the vanilla box would also have been hit.
     * Returns null when the ray misses.
     */
    private RayHit resolveHit(Player attacker, Player target, KnockbackProfile profile,
                              RayContext context) {
        Vector visualOffset = getVisualOffset(attacker, target, profile);
        Vector origin = target.getLocation().toVector().add(visualOffset);
        float maxDistance = (float) context.maxDistance();

        Vector hit = context.legacyMelee()
                ? AABB.fromBoundingBox(LegacyHitboxes.meleeTarget(target))
                        .translate(visualOffset)
                        .intersectsRay(context.ray(), 0.0F, maxDistance)
                : context.expandedBox().translate(origin)
                        .intersectsRay(context.ray(), 0.0F, maxDistance);
        if (hit == null) return null;

        boolean vanillaHit = (context.legacyMelee()
                ? AABB.fromBoundingBox(target.getBoundingBox()).translate(visualOffset)
                : context.vanillaBox().translate(origin))
                .intersectsRay(context.ray(), 0.0F, maxDistance) != null;
        return new RayHit(hit, context.eye().toVector().distance(hit), vanillaHit);
    }

    private Vector getVisualOffset(Player attacker, Player target, KnockbackProfile profile) {
        if (!profile.isPacketMisplaceEnabled()
                || !profile.isPacketMisplaceHitCompensation()) return new Vector();
        MisplaceHandler handler = manager.getMisplaceHandler();
        if (handler == null) return new Vector();

        Vector offset = handler.getVisualOffset(attacker, target);
        double maximum = profile.getPacketMisplaceMaxHitCompensation();
        double length = offset.length();
        if (length > maximum && length > 1.0E-6D) {
            offset.multiply(maximum / length);
        }
        return offset;
    }

    private boolean isBlocked(Location eye, Vector hit) {
        Vector offset = hit.clone().subtract(eye.toVector());
        double distance = offset.length();
        if (distance <= 1.0E-6D) return false;
        RayTraceResult block = eye.getWorld().rayTraceBlocks(
                eye, offset.multiply(1.0D / distance), distance,
                FluidCollisionMode.NEVER, true);
        return block != null;
    }

    private void fireAttack(Player attacker, Player target, KnockbackProfile profile) {
        PlayerKnockbackData data = manager.getPlayerData(attacker);
        boolean hasKnockbackEnchant = attacker.getInventory().getItemInMainHand()
                .getEnchantmentLevel(Enchantment.KNOCKBACK) > 0;
        boolean applyLegacySlowdown = manager.isLegacyKnockback(attacker)
                && (manager.hasLegacySprintKnockback(attacker) || hasKnockbackEnchant);
        Vector velocityBeforeAttack = applyLegacySlowdown ? attacker.getVelocity().clone() : null;
        data.setServerSideHit(true);
        try {
            attacker.attack(target);
        } finally {
            data.setServerSideHit(false);
        }
        if (velocityBeforeAttack != null) {
            Vector current = attacker.getVelocity();
            double slowdown = profile.getLegacyAttackerHorizontalSlowdown();
            manager.applyLegacyAttackerHorizontalMotion(
                    attacker, velocityBeforeAttack, current.getY(), slowdown);
        }
        if (profile.isStopSprint() && applyLegacySlowdown) attacker.setSprinting(false);
    }

    /**
     * Maximum eye-to-hitbox distance, mirroring 1.8's 3.0 block entity pick.
     *
     * <p>The configured {@code modern.entity_interaction_range} is deliberately
     * allowed to be 3.0 even though the legacy melee box is already expanded by
     * 0.1. That is not a 0.1-block extension: the value is a cap on the measured
     * eye-to-intersection distance, so a 3.0 ray can still only reach a target
     * whose expanded box is within 3.0. Lowering it to 2.9 would make the reach
     * measurably *shorter* than 1.8, because the +0.1 expansion is not added on
     * top of this cap.</p>
     */
    private double getMaxDistance(KnockbackProfile profile) {
        return Math.max(0.0D, Math.min(3.0D, profile.getEntityInteractionRange()));
    }

    /** Per-swing ray state shared by all candidate hitbox intersections. */
    private record RayContext(Location eye, Ray ray, AABB expandedBox, AABB vanillaBox,
                              double maxDistance, boolean legacyMelee) {
    }

    /** A resolved hitbox intersection for one target. */
    private record RayHit(Vector position, double distance, boolean vanillaHit) {
    }

    private boolean isUsableTarget(Player player) {
        return player != null && player.isValid() && !player.isDead()
                && player.getGameMode() != GameMode.SPECTATOR;
    }

    private boolean hasLegacySwordCombat(Player player) {
        MatchService matchService = AlleyPlugin.getInstance().getService(MatchService.class);
        if (!(matchService instanceof MatchServiceImpl service)) return false;
        LegacyCombatService legacyCombat = service.getLegacyCombatService();
        return legacyCombat != null && legacyCombat.hasSwordBlockKB(player.getUniqueId());
    }

    private boolean isMeleeWeapon(Material material) {
        return switch (material) {
            case WOODEN_SWORD, STONE_SWORD, IRON_SWORD, GOLDEN_SWORD, DIAMOND_SWORD, NETHERITE_SWORD,
                 WOODEN_AXE, STONE_AXE, IRON_AXE, GOLDEN_AXE, DIAMOND_AXE, NETHERITE_AXE,
                 WOODEN_PICKAXE, STONE_PICKAXE, IRON_PICKAXE, GOLDEN_PICKAXE, DIAMOND_PICKAXE, NETHERITE_PICKAXE,
                 WOODEN_SHOVEL, STONE_SHOVEL, IRON_SHOVEL, GOLDEN_SHOVEL, DIAMOND_SHOVEL, NETHERITE_SHOVEL,
                 WOODEN_HOE, STONE_HOE, IRON_HOE, GOLDEN_HOE, DIAMOND_HOE, NETHERITE_HOE,
                 TRIDENT, MACE, STICK, AIR -> true;
            default -> false;
        };
    }

}
