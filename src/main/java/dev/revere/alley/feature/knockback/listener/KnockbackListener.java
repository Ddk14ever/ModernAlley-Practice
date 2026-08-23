package dev.revere.alley.feature.knockback.listener;

import com.destroystokyo.paper.event.player.PlayerAttackEntityCooldownResetEvent;
import dev.revere.alley.AlleyPlugin;
import dev.revere.alley.feature.match.MatchService;
import dev.revere.alley.feature.match.combat.legacy.LegacyCombatService;
import dev.revere.alley.feature.match.combat.legacy.LegacyProjectileData;
import dev.revere.alley.feature.match.internal.MatchServiceImpl;
import dev.revere.alley.feature.knockback.KnockbackBranch;
import dev.revere.alley.feature.knockback.KnockbackManager;
import dev.revere.alley.feature.knockback.KnockbackProfile;
import dev.revere.alley.feature.knockback.data.PlayerKnockbackData;
import dev.revere.alley.feature.knockback.packet.MisplaceHandler;
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.entity.*;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.EntityKnockbackByEntityEvent;
import org.bukkit.event.entity.EntityKnockbackEvent;
import org.bukkit.event.entity.EntityShootBowEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerTeleportEvent;
import org.bukkit.event.player.PlayerVelocityEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.projectiles.ProjectileSource;
import org.bukkit.util.Vector;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
/**
 * @author Ddk1 ClaudeCode
 * @project Alley
 * @since 04/07/2026
 *
 * Dispatches profile knockback to isolated DEFAULT and LEGACY calculations.
 * Only DEFAULT can use the configurable modern downward stage.
 */
public class KnockbackListener implements Listener {
    private final KnockbackManager manager;
    private final Map<UUID, AttackCooldownSnapshot> attackCooldowns = new ConcurrentHashMap<>();
    private final Map<UUID, PendingLegacyAttackerSlowdown> pendingLegacyAttackerSlowdowns = new ConcurrentHashMap<>();

    public KnockbackListener(KnockbackManager manager) {
        this.manager = manager;
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onAttackCooldownReset(PlayerAttackEntityCooldownResetEvent event) {
        if (event.getAttackedEntity() instanceof Player victim) {
            PlayerKnockbackData victimData = manager.getPlayerData(victim);
            if (victimData.getProfileName() != null) {
                manager.applyHitDelayWindow(victim);
            }
        }
        if (hasLegacySwordCombat(event.getPlayer())) {
            // 1.8 has no attack-strength ticker. Cancelling the reset keeps
            // subsequent legacy hits at full strength without altering other kits.
            event.setCancelled(true);
            this.attackCooldowns.remove(event.getPlayer().getUniqueId());
            return;
        }
        this.attackCooldowns.put(event.getPlayer().getUniqueId(), new AttackCooldownSnapshot(
                event.getAttackedEntity().getUniqueId(),
                event.getCooledAttackStrength(),
                Bukkit.getCurrentTick()
        ));
    }

    // Track ground state
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onMove(PlayerMoveEvent e) {
        if (e.getTo() == null) return;
        manager.updateMovementState(e.getPlayer());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onTeleport(PlayerTeleportEvent event) {
        MisplaceHandler handler = manager.getMisplaceHandler();
        if (handler != null) {
            // Pre-teleport relative/absolute movement packets are invalid after
            // a world, round, respawn, or arena teleport.
            handler.clearPlayer(event.getPlayer().getUniqueId());
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBowShoot(EntityShootBowEvent event) {
        if (!(event.getProjectile() instanceof AbstractArrow arrow)) return;
        int punchLevel = event.getBow() == null ? 0 : event.getBow().getEnchantmentLevel(Enchantment.PUNCH);
        LegacyProjectileData.storeBowPunch(arrow, punchLevel);
    }

    /** Captures the pre-event hurt-window state for damage-only supplements. */
    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void beginLegacyDamage(EntityDamageEvent event) {
        if (!(event.getEntity() instanceof Player victim)) return;
        if (!hasLegacySwordCombat(victim)) return;

        // Do not cancel by damage category. Both 1.8 and modern NMS compare the
        // incoming amount with lastDamage inside this window: smaller/equal hits
        // are rejected, while a larger hit applies only the difference.
        manager.beginLegacyDamage(victim);
        // Keep the profile window authoritative for the NMS work that follows
        // this event, without changing the pre-event state used above to decide
        // whether this is a fresh hit or a damage delta.
        manager.applyHitDelayWindow(victim);
    }

    public void applyLegacyPearlKnockback(Player attacker, Player victim, Vector impactVelocity) {
        PlayerKnockbackData data = manager.getPlayerData(victim);

        KnockbackProfile profile = manager.getAppliedProfile(victim);
        if (profile == null || profile.getBranch() != KnockbackBranch.LEGACY
                || !profile.isProjectileEnabled()) return;

        Location victimLocation = victim.getLocation();
        Vector direction = impactVelocity == null ? new Vector() : impactVelocity.clone();
        double dx = direction.getX();
        double dz = direction.getZ();
        double distance = Math.hypot(dx, dz);
        if (distance < 0.001) {
            direction = attacker.getLocation().getDirection();
            dx = direction.getX();
            dz = direction.getZ();
            distance = Math.hypot(dx, dz);
            if (distance < 0.001) {
                dx = 0.0;
                dz = 1.0;
                distance = 1.0;
            }
        }

        double sourceDistance = attacker.getLocation().distance(victimLocation);
        double horizontal = applyLegacyDistanceReduction(profile,
                profile.getProjectileHorizontal() * profile.getProjectileHorizontalMult(),
                sourceDistance);
        double vertical = profile.getProjectileVertical() * profile.getProjectileVerticalMult();
        Vector impulse = new Vector(dx / distance * horizontal, vertical, dz / distance * horizontal);
        Vector knockback = isLegacyBaseKnockbackResisted(victim)
                ? victim.getVelocity().clone()
                : applyLegacyBaseMotion(
                        victim.getVelocity(), impulse, profile, profile.getLegacyVerticalLimit());

        if (victimLocation.getY() - data.getLastGroundY() > profile.getYLimit()) {
            knockback.setY(0.0);
        }

        data.setVelocity(knockback);
        data.setLastDamageTick(System.currentTimeMillis());
        forceLegacyPearlKnockback(victim, data, knockback);

        manager.applyHitDelayWindow(victim);
    }

    // Compute KB after all damage listeners have finished, but before NMS applies it.
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void computeVelocity(EntityDamageByEntityEvent event) {
        if (!(event.getEntity() instanceof Player victim)) return;
        // Alley weapon damage is intentionally damage-only. Its listener sets
        // this one-event marker before Bukkit applies damage so neither
        // Default nor Legacy can queue a later velocity packet.
        if (victim.hasMetadata("alley_weapon_no_kb")) return;
        PlayerKnockbackData data = manager.getPlayerData(victim);
        if (event.getCause() == EntityDamageEvent.DamageCause.ENTITY_ATTACK
                && event.getDamager() instanceof Player attacker
                && manager.isLegacyKnockback(attacker)
                && data.getBranch() == KnockbackBranch.LEGACY) {
            // MatchDamageListener records this earlier for match statistics. The
            // tracker returns the cached same-tick result, while this fallback
            // also covers Legacy fights outside that listener.
            manager.recordLegacyMeleeHit(attacker);
        }
        if (data.getBranch() == KnockbackBranch.LEGACY) {
            computeLegacyVelocity(event, victim, data);
        } else {
            computeDefaultVelocity(event);
        }
    }

    /** Existing 1.9+ calculation, isolated from the Legacy motion-inheritance path. */
    private void computeDefaultVelocity(EntityDamageByEntityEvent event) {
        if (!(event.getEntity() instanceof Player victim)) return;
        if (victim.isDead() || victim.getGameMode() == GameMode.SPECTATOR) return;
        if (event.getCause() == EntityDamageEvent.DamageCause.FALL
                || event.getCause() == EntityDamageEvent.DamageCause.FIRE_TICK
                || event.getCause() == EntityDamageEvent.DamageCause.LAVA
                // Sweep attacks deal 1 point of flat damage with no knockback in vanilla.
                // Routing them through the profile knockback both adds phantom KB and
                // re-triggers misplace/delay bookkeeping on a hit the victim could not
                // even see coming; the vanilla hurt-resistance window already gates them.
                // 跳劈（横扫）在原版中只造成1点固定伤害且无击退。如果走配置文件击退，
                // 会产生虚假击退并重复触发misplace/delay的记录；原版无敌帧窗口已对其限制。
                || event.getCause() == EntityDamageEvent.DamageCause.ENTITY_SWEEP_ATTACK) return;

        PlayerKnockbackData vData = manager.getPlayerData(victim);

        KnockbackProfile profile = manager.getAppliedProfile(victim);
        if (profile == null) return;
        Entity source = event.getDamager();
        // Accepted legacy pearl hits are delivered explicitly after damage confirmation.
        // Pearl landing self-damage must never enter the generic direction calculation.
        if (source instanceof EnderPearl) return;
        LivingEntity attacker;
        boolean isProjectile = false;

        if (source instanceof LivingEntity) {
            attacker = (LivingEntity) source;
        } else if (source instanceof Projectile projectile) {
            if (!profile.isProjectileEnabled()) {
                vData.setPendingNativeProjectileVelocityTick(manager.getCurrentTick());
                return;
            }
            ProjectileSource shooter = projectile.getShooter();
            if (!(shooter instanceof LivingEntity livingShooter)) return;
            attacker = livingShooter;
            isProjectile = true;
        } else return;

        Location vLoc = victim.getLocation();
        Location aLoc = attacker.getLocation();
        double dx = vLoc.getX() - aLoc.getX();
        double dz = vLoc.getZ() - aLoc.getZ();
        double dist = Math.sqrt(dx * dx + dz * dz);
        if (dist < 0.001) { dx = Math.random() * 0.02 - 0.01; dz = Math.random() * 0.02 - 0.01; dist = Math.sqrt(dx * dx + dz * dz); }

        Vector kb;
        if (isProjectile && source instanceof Projectile && !profile.isProjectileDirectionOverride()) {
            kb = source.getVelocity().clone().normalize().setY(1.0);
        } else {
            kb = new Vector(dx / dist, 1.0, dz / dist);
        }

        boolean onGround = vData.isOnGround();
        if (isProjectile) {
            kb.setX(kb.getX() * profile.getProjectileHorizontal());
            kb.setY(kb.getY() * profile.getProjectileVertical());
            kb.setZ(kb.getZ() * profile.getProjectileHorizontal());
            kb.setX(kb.getX() * profile.getProjectileHorizontalMult());
            kb.setY(kb.getY() * profile.getProjectileVerticalMult());
            kb.setZ(kb.getZ() * profile.getProjectileHorizontalMult());
        } else {
            double hor = onGround ? profile.getHorizontalGround() : profile.getHorizontalAir();
            double ver = onGround ? profile.getVerticalGround() : profile.getVerticalAir();

            kb.setX(kb.getX() * hor);
            kb.setY(kb.getY() * ver);
            kb.setZ(kb.getZ() * hor);
            Player playerAttacker = attacker instanceof Player ? (Player) attacker : null;
            float attackCooldown = playerAttacker == null
                    ? 1.0f
                    : getAttackCooldown(playerAttacker, victim);
            int knockbackLevel = getKnockbackLevel(attacker);
            boolean sprinting = playerAttacker != null && playerAttacker.isSprinting();
            if (sprinting && profile.isCooldownAffectsKb()) {
                if (attackCooldown <= 0.9f) sprinting = false;
            }
            int bonusLevels = knockbackLevel + (sprinting ? 1 : 0);
            if (bonusLevels > 0) {
                double yaw = Math.toRadians(aLoc.getYaw());
                double horizontalBonus = bonusLevels * profile.getHorizontalSprintExtra();
                kb.setX(kb.getX() - Math.sin(yaw) * horizontalBonus);
                kb.setZ(kb.getZ() + Math.cos(yaw) * horizontalBonus);
                kb.setY(Math.min(0.4, kb.getY() + profile.getVerticalSprintExtra()));
            }
        }

        if (source instanceof AbstractArrow arrow) {
            int punchLevel = getArrowPunch(arrow);
            Vector arrowVelocity = arrow.getVelocity();
            double horizontalLength = Math.hypot(arrowVelocity.getX(), arrowVelocity.getZ());
            if (punchLevel > 0 && horizontalLength > 1.0E-4) {
                kb.setX(kb.getX() + arrowVelocity.getX() / horizontalLength * 0.6 * punchLevel);
                kb.setY(kb.getY() + 0.1);
                kb.setZ(kb.getZ() + arrowVelocity.getZ() / horizontalLength * 0.6 * punchLevel);
            }
        }

        // Y limit: if victim is > yLimit blocks above last ground, cancel vertical KB
        if (!isProjectile
                && vLoc.getY() - vData.getLastGroundY() > profile.getYLimit()) {
            kb.setY(0.0);
        }
        vData.setVelocity(kb);
        vData.setLastDamageTick(System.currentTimeMillis());

        // Record attack for misplace handler
        if (attacker instanceof Player && !isProjectile) {
            MisplaceHandler mh = manager.getMisplaceHandler();
            if (mh != null) mh.recordAttack((Player) attacker, victim);
            // stop_sprint conflicts with cooldown_affects_kb: clearing sprint makes
            // isSprinting() read false on every later hit, so the modern sprint bonus
            // would never apply regardless of the attacker's cooldown. With
            // cooldown_affects_kb enabled, let the client's sprint intent drive the
            // bonus, exactly like vanilla 1.21.11.
            // stop_sprint与cooldown_affects_kb冲突：强制清除冲刺会让后续每次攻击的
            // isSprinting()都为false，冲刺加成（哪怕满冷却）永远不会生效。
            // 启用cooldown_affects_kb时，冲刺状态交给客户端意图决定（与1.21.11原版一致）。
            if (profile.isStopSprint() && !profile.isCooldownAffectsKb()) {
                ((Player) attacker).setSprinting(false);
            }
        }

        manager.applyHitDelayWindow(victim);
    }

    /** Dedicated 1.8 calculation. No Default downward or cooldown logic is used here. */
    private void computeLegacyVelocity(EntityDamageByEntityEvent event, Player victim,
                                       PlayerKnockbackData data) {
        if (victim.isDead() || victim.getGameMode() == GameMode.SPECTATOR) return;
        if (event.getCause() == EntityDamageEvent.DamageCause.FALL
                || event.getCause() == EntityDamageEvent.DamageCause.FIRE_TICK
                || event.getCause() == EntityDamageEvent.DamageCause.LAVA
                || event.getCause() == EntityDamageEvent.DamageCause.ENTITY_SWEEP_ATTACK) return;

        KnockbackProfile profile = manager.getAppliedProfile(victim);
        if (profile == null || profile.getBranch() != KnockbackBranch.LEGACY) return;

        Entity source = event.getDamager();
        if (source instanceof EnderPearl) return;

        boolean legacyRodHit = victim.getScoreboardTags().remove("alley_legacy_rod_kb");
        LivingEntity attacker;
        boolean projectile = false;
        AbstractArrow legacyArrow = null;

        if (source instanceof LivingEntity living) {
            if (living instanceof Player player && !hasLegacySwordCombat(player)) return;
            attacker = living;
        } else if (source instanceof Projectile projectileSource) {
            ProjectileSource shooter = projectileSource.getShooter();
            if (!(shooter instanceof LivingEntity livingShooter)) return;
            if (!LegacyProjectileData.isMarked(projectileSource)) return;
            if (livingShooter instanceof Player player && !hasLegacySwordCombat(player)) return;
            attacker = livingShooter;
            projectile = true;
            if (projectileSource instanceof AbstractArrow arrow) legacyArrow = arrow;
        } else {
            return;
        }

        if ((projectile || legacyRodHit) && !profile.isProjectileEnabled()) {
            if (manager.wasInsideHurtResistanceWindow(victim)) {
                data.markLegacyDamageSupplement(
                        Bukkit.getCurrentTick(), source.getUniqueId(), attacker.getUniqueId());
            } else if (projectile) {
                data.setPendingNativeProjectileVelocityTick(manager.getCurrentTick());
            }
            return;
        }
        if (manager.wasInsideHurtResistanceWindow(victim)) {
            data.markLegacyDamageSupplement(
                    Bukkit.getCurrentTick(), source.getUniqueId(), attacker.getUniqueId());
            return;
        }

        Location victimLocation = victim.getLocation();
        Location attackerLocation = attacker.getLocation();
        boolean onGround = victim.isOnGround();
        data.setOnGround(onGround);
        if (onGround) data.setLastGroundY(victimLocation.getY());
        double sourceDistance = attackerLocation.distance(victimLocation);
        double dx = victimLocation.getX() - attackerLocation.getX();
        double dz = victimLocation.getZ() - attackerLocation.getZ();
        double distance = Math.hypot(dx, dz);
        // Previous implementation used the arrow velocity for base direction:
        // Vector arrowVelocity = legacyArrow.getVelocity();
        // Legacy base KB now follows the damage-source entity (the shooter),
        // matching vanilla, WindSpigot, and FulfillSpigot. Punch remains arrow-directed.
        if (distance < 0.001D) {
            dx = Math.random() * 0.02D - 0.01D;
            dz = Math.random() * 0.02D - 0.01D;
            distance = Math.hypot(dx, dz);
        }

        Vector knockback;
        if (isLegacyBaseKnockbackResisted(victim)) {
            // 1.8 resistance rejects only the base portion. Sprint/enchant bonus remains eligible.
            knockback = victim.getVelocity().clone();
        } else if (legacyArrow != null) {
            double horizontal = applyLegacyDistanceReduction(
                    profile, profile.getLegacyArrowHorizontal(), sourceDistance);
            Vector impulse = new Vector(
                    dx / distance * horizontal,
                    profile.getLegacyArrowVertical(),
                    dz / distance * horizontal);
            knockback = applyLegacyBaseMotion(
                    victim.getVelocity(), impulse, profile, profile.getLegacyArrowVerticalLimit());
        } else if (projectile || legacyRodHit) {
            double horizontal = applyLegacyDistanceReduction(profile,
                    profile.getProjectileHorizontal() * profile.getProjectileHorizontalMult(),
                    sourceDistance);
            Vector impulse = new Vector(
                    dx / distance * horizontal,
                    profile.getProjectileVertical() * profile.getProjectileVerticalMult(),
                    dz / distance * horizontal);
            knockback = applyLegacyBaseMotion(
                    victim.getVelocity(), impulse, profile, profile.getLegacyVerticalLimit());
        } else {
            double configuredHorizontal = onGround
                    ? profile.getHorizontalGround() : profile.getHorizontalAir();
            double horizontal = applyLegacyDistanceReduction(
                    profile, configuredHorizontal, sourceDistance);
            double vertical = onGround
                    ? profile.getVerticalGround() : profile.getVerticalAir();
            Vector impulse = new Vector(dx / distance * horizontal, vertical, dz / distance * horizontal);
            knockback = applyLegacyBaseMotion(
                    victim.getVelocity(), impulse, profile, profile.getLegacyVerticalLimit());
        }

        if (!projectile && !legacyRodHit) {
            Player playerAttacker = attacker instanceof Player player ? player : null;
            boolean sprintKnockback = playerAttacker != null
                    && manager.hasLegacySprintKnockback(playerAttacker);
            // Previous Legacy check retained for rollback comparison:
            // boolean sprintKnockback = playerAttacker != null && playerAttacker.isSprinting();
            int bonusLevels = getKnockbackLevel(attacker)
                    + (sprintKnockback ? 1 : 0);
            if (bonusLevels > 0) {
                double yaw = Math.toRadians(attackerLocation.getYaw());
                double horizontalBonus = bonusLevels * profile.getHorizontalSprintExtra();
                knockback.setX(knockback.getX() - Math.sin(yaw) * horizontalBonus);
                // EntityPlayer#addVelocity uses one vertical bonus, not one per level.
                knockback.setY(knockback.getY() + profile.getVerticalSprintExtra());
                knockback.setZ(knockback.getZ() + Math.cos(yaw) * horizontalBonus);
                if (playerAttacker != null
                        && !manager.getPlayerData(playerAttacker).isServerSideHit()) {
                    queueLegacyAttackerSlowdownAdjustment(
                            playerAttacker, victim, profile.getLegacyAttackerHorizontalSlowdown());
                }
                if (profile.isStopSprint() && playerAttacker != null) {
                    if (sprintKnockback) {
                        manager.consumeLegacySprintKnockback(playerAttacker);
                    }
                    playerAttacker.setSprinting(false);
                }
            }

            // A successful STOP -> START -> melee-hit W-tap grants one extra
            // Legacy horizontal impulse. It is consumed only after this hit has
            // reached the actual Legacy knockback calculation.
            if (playerAttacker != null && manager.consumeLegacyWTapExtra(playerAttacker)) {
                double yaw = Math.toRadians(attackerLocation.getYaw());
                double wtapExtra = profile.getLegacyWTapExtra();
                knockback.setX(knockback.getX() - Math.sin(yaw) * wtapExtra);
                knockback.setZ(knockback.getZ() + Math.cos(yaw) * wtapExtra);
            }
        }

        if (legacyArrow != null) {
            applyLegacyArrowPunch(knockback, legacyArrow, profile);
        }
        if (legacyArrow == null
                && victimLocation.getY() - data.getLastGroundY() > profile.getYLimit()) {
            knockback.setY(0.0D);
        }

        data.setVelocity(knockback);
        data.setLastDamageTick(System.currentTimeMillis());
        boolean syntheticProjectile = projectile && legacyArrow == null;
        if (data.isServerControlled() || syntheticProjectile || legacyRodHit) {
            deliverPendingLegacyKnockback(victim, data, knockback);
        } else {
            expirePendingLegacyKnockback(data, knockback);
        }
        if (attacker instanceof Player playerAttacker && !projectile && !legacyRodHit) {
            MisplaceHandler misplaceHandler = manager.getMisplaceHandler();
            if (misplaceHandler != null) misplaceHandler.recordAttack(playerAttacker, victim);
        }
        manager.applyHitDelayWindow(victim);
    }

    private int getKnockbackLevel(LivingEntity attacker) {
        if (attacker.getEquipment() == null) return 0;
        ItemStack hand = attacker.getEquipment().getItemInMainHand();
        return hand.getType().isAir() ? 0 : hand.getEnchantmentLevel(Enchantment.KNOCKBACK);
    }

    private void applyLegacyArrowPunch(Vector knockback, AbstractArrow arrow,
                                       KnockbackProfile profile) {
        int punchLevel = getArrowPunch(arrow);
        Vector arrowVelocity = arrow.getVelocity();
        double horizontalLength = Math.hypot(arrowVelocity.getX(), arrowVelocity.getZ());
        if (punchLevel <= 0 || horizontalLength <= 1.0E-4D) return;

        double horizontal = profile.getLegacyArrowPunchHorizontal() * punchLevel;
        knockback.setX(knockback.getX() + arrowVelocity.getX() / horizontalLength * horizontal);
        knockback.setY(knockback.getY() + profile.getLegacyArrowPunchVertical());
        knockback.setZ(knockback.getZ() + arrowVelocity.getZ() / horizontalLength * horizontal);
    }

    /**
     * Applies the knockback enchantment bonus to minecarts and TNT minecarts when hit by
     * a player, pushing them in the attacker's facing direction using the same per-level
     * horizontal bonus the profile gives to players.
     * 当玩家击中矿车/TNT矿车时，为其施加击退附魔加成，使用与玩家相同的每级水平加成，
     * 沿攻击者朝向将其推开。
     */
    @EventHandler(priority = EventPriority.HIGHEST)
    public void computeMinecartKnockback(EntityDamageByEntityEvent event) {
        if (!(event.getEntity() instanceof Minecart minecart)) return;
        if (!(event.getDamager() instanceof Player attacker)) return;
        if (event.getCause() == EntityDamageEvent.DamageCause.ENTITY_SWEEP_ATTACK) return;

        ItemStack hand = attacker.getEquipment() != null ? attacker.getEquipment().getItemInMainHand() : null;
        if (hand == null || hand.getType().isAir()) return;
        int knockbackLevel = hand.getEnchantmentLevel(Enchantment.KNOCKBACK);
        if (knockbackLevel <= 0) return;

        KnockbackProfile profile = manager.getAppliedProfile(attacker);
        // Use the profile's full base horizontal knockback per level so the push is clearly
        // felt; the old sprint_extra (0.25/level) was too weak to overcome minecart rail friction.
        double perLevel = profile != null ? profile.getHorizontalGround() : 0.5;

        double yaw = Math.toRadians(attacker.getLocation().getYaw());
        double factor = knockbackLevel * perLevel;
        Vector velocity = minecart.getVelocity();
        velocity.add(new Vector(-Math.sin(yaw) * factor, 0.0, Math.cos(yaw) * factor));
        minecart.setVelocity(velocity);
    }

    /**
     * Player and arrow attack code can add an extra native knockback after the
     * damage event has returned. A legacy damage supplement is damage-only, so
     * cancel that post-event knockback as well as skipping the profile velocity.
     */
    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void suppressLegacyDamageSupplementKnockback(EntityKnockbackEvent event) {
        if (!(event.getEntity() instanceof Player victim)) return;

        PlayerKnockbackData data = manager.getPlayerData(victim);
        long supplementTick = data.getLegacyDamageSupplementTick();
        long currentTick = Bukkit.getCurrentTick();
        if (supplementTick != currentTick) {
            if (supplementTick != Long.MIN_VALUE && supplementTick < currentTick) {
                data.clearLegacyDamageSupplement();
            }
            return;
        }

        if (event instanceof EntityKnockbackByEntityEvent byEntity) {
            Entity sourceEntity = byEntity.getSourceEntity();
            if (sourceEntity == null) return;
            UUID sourceId = sourceEntity.getUniqueId();
            if (!sourceId.equals(data.getLegacyDamageSupplementSource())
                    && !sourceId.equals(data.getLegacyDamageSupplementAttacker())) {
                return;
            }
        } else if (event.getCause() != EntityKnockbackEvent.KnockbackCause.ENTITY_ATTACK
                && event.getCause() != EntityKnockbackEvent.KnockbackCause.DAMAGE) {
            // Do not let an unrelated explosion, shield block, or piston push
            // in the same tick consume the attack supplement marker.
            return;
        }

        event.setCancelled(true);
    }

    /**
     * Downward suppression must also cover native knockback that does not produce
     * a PlayerVelocityEvent (notably some projectile and server-side paths).
     */
    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void suppressDisabledDownwardKnockback(EntityKnockbackEvent event) {
        if (!(event.getEntity() instanceof Player victim)) return;

        PlayerKnockbackData data = manager.getPlayerData(victim);
        KnockbackProfile profile = manager.getAppliedProfile(victim);
        if (profile == null || !profile.isDisableDownwardKb()) return;

        Vector knockback = event.getFinalKnockback();
        if (knockback != null && knockback.getY() < 0.0D) {
            event.setFinalKnockback(knockback.clone().setY(0.0D));
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void observeNativeLegacyAttackerKnockback(EntityKnockbackByEntityEvent event) {
        if (!(event.getEntity() instanceof Player victim)
                || !(event.getSourceEntity() instanceof Player attacker)) {
            return;
        }

        PendingLegacyAttackerSlowdown pending = pendingLegacyAttackerSlowdowns.get(attacker.getUniqueId());
        if (pending != null
                && pending.tick() == Bukkit.getCurrentTick()
                && pending.victimId().equals(victim.getUniqueId())
                && pendingLegacyAttackerSlowdowns.remove(attacker.getUniqueId(), pending)) {
            double nativeSlowdown = 0.6D;
            double configuredSlowdown = pending.horizontalSlowdown();
            if (Math.abs(configuredSlowdown - nativeSlowdown) > 1.0E-8D) {
                Vector current = attacker.getVelocity();
                double compensation = configuredSlowdown / nativeSlowdown;
                manager.applyLegacyAttackerHorizontalMotion(
                        attacker, current, current.getY(), compensation);
            }
        }
    }

    // Apply cached velocity
    @EventHandler(priority = EventPriority.LOWEST)
    public void applyVelocity(PlayerVelocityEvent event) {
        PlayerKnockbackData data = manager.getPlayerData(event.getPlayer());
        if (data.isSuppressLegacyPearlVelocity()) {
            data.setCaptureServerControlledVelocity(false);
            data.setServerControlledVelocity(null);
            event.setCancelled(true);
            return;
        }

        Vector pending = data.getVelocity();
        long pendingNativeTick = data.getPendingNativeProjectileVelocityTick();
        boolean nativeProjectile = pendingNativeTick != Long.MIN_VALUE
                && manager.getCurrentTick() >= pendingNativeTick
                && manager.getCurrentTick() - pendingNativeTick <= 1L;
        data.setCaptureServerControlledVelocity(
                data.isServerControlled() && (pending != null || nativeProjectile));
        Vector velocity = consumePendingKnockback(event.getPlayer(), event.getVelocity());
        if (velocity != null) {
            event.setVelocity(velocity);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void retainServerControlledVelocity(PlayerVelocityEvent event) {
        PlayerKnockbackData data = manager.getPlayerData(event.getPlayer());
        if (!data.isCaptureServerControlledVelocity()) return;

        data.setCaptureServerControlledVelocity(false);
        data.setServerControlledVelocity(event.isCancelled() ? null : event.getVelocity().clone());
    }

    public Vector consumePendingKnockback(Player player, Vector nativeVelocity) {
        PlayerKnockbackData data = manager.getPlayerData(player);
        Vector calculated = data.getVelocity();
        long pendingNativeTick = data.getPendingNativeProjectileVelocityTick();
        boolean nativeProjectile = pendingNativeTick != Long.MIN_VALUE
                && manager.getCurrentTick() >= pendingNativeTick
                && manager.getCurrentTick() - pendingNativeTick <= 1L;
        if (calculated == null && !nativeProjectile) {
            if (pendingNativeTick != Long.MIN_VALUE
                    && manager.getCurrentTick() - pendingNativeTick > 1L) {
                data.clearPendingNativeProjectileVelocity();
            }
            return null;
        }
        KnockbackProfile profile = manager.getAppliedProfile(player);
        if (profile == null) {
            data.setVelocity(null);
            data.clearPendingNativeProjectileVelocity();
            return null;
        }

        Vector applied = null;
        if (calculated != null) {
            applyDownwardKnockback(player, profile, calculated, nativeVelocity);
            applied = calculated;
        } else if (profile.isDisableDownwardKb() && nativeVelocity.getY() < 0.0) {
            applied = nativeVelocity.clone();
            applied.setY(0.0);
        }

        data.setVelocity(null);
        data.clearPendingNativeProjectileVelocity();
        return applied;
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent e) {
        this.attackCooldowns.remove(e.getPlayer().getUniqueId());
        this.pendingLegacyAttackerSlowdowns.remove(e.getPlayer().getUniqueId());
        manager.removePlayer(e.getPlayer().getUniqueId());
    }

    private float getAttackCooldown(Player attacker, Player victim) {
        AttackCooldownSnapshot snapshot = this.attackCooldowns.remove(attacker.getUniqueId());
        if (snapshot != null
                && snapshot.tick() == Bukkit.getCurrentTick()
                && snapshot.targetId().equals(victim.getUniqueId())) {
            return snapshot.strength();
        }
        return attacker.getAttackCooldown();
    }

    private boolean hasLegacySwordCombat(Player player) {
        LegacyCombatService legacyCombat = getLegacyCombatService();
        return legacyCombat != null && legacyCombat.hasSwordBlockKB(player.getUniqueId());
    }

    private LegacyCombatService getLegacyCombatService() {
        MatchService matchService = AlleyPlugin.getInstance().getService(MatchService.class);
        if (!(matchService instanceof MatchServiceImpl service)) {
            return null;
        }
        return service.getLegacyCombatService();
    }

    private int getArrowPunch(AbstractArrow arrow) {
        return Math.max(LegacyProjectileData.getPunchLevel(arrow), arrow.getKnockbackStrength());
    }

    /**
     * Keeps the Legacy horizontal motion inheritance while using FulfillSpigot's
     * fixed base vertical velocity. Sprint/enchantment and Punch vertical bonuses
     * are applied later and may exceed this base limit.
     */
    private Vector applyLegacyBaseMotion(Vector currentMotion, Vector impulse,
                                         KnockbackProfile profile, double verticalLimit) {
        double friction = profile.getLegacyHorizontalFriction();
        return new Vector(
                currentMotion.getX() / friction + impulse.getX(),
                Math.min(verticalLimit, impulse.getY()),
                currentMotion.getZ() / friction + impulse.getZ());
    }

    private double applyLegacyDistanceReduction(KnockbackProfile profile,
                                                double horizontal,
                                                double sourceDistance) {
        if (!profile.isLegacyDistanceReductionEnabled()
                || !Double.isFinite(sourceDistance)
                || sourceDistance <= profile.getLegacyDistanceReductionStart()) {
            return horizontal;
        }

        double reduction = Math.min(profile.getLegacyDistanceReductionMaximum(),
                profile.getLegacyDistanceReductionFactor()
                        * (sourceDistance - profile.getLegacyDistanceReductionStart()));
        double minimum = Math.min(horizontal, profile.getLegacyDistanceMinimumHorizontal());
        return Math.max(minimum, horizontal - reduction);
    }

    /** 1.8 treats knockback resistance as a per-hit probability, not a multiplier. */
    private boolean isLegacyBaseKnockbackResisted(Player victim) {
        AttributeInstance resistance = victim.getAttribute(Attribute.KNOCKBACK_RESISTANCE);
        if (resistance == null) return false;

        double chance = Math.max(0.0D, Math.min(1.0D, resistance.getValue()));
        return chance > 0.0D && Math.random() < chance;
    }

    /**
     * Server-controlled players and synthetic legacy projectile hits do not
     * always emit a velocity event. Deliver only those explicit fallback paths.
     */
    private void deliverPendingLegacyKnockback(Player player, PlayerKnockbackData data,
                                               Vector expected) {
        Bukkit.getScheduler().runTask(AlleyPlugin.getInstance(), () -> {
            Vector pending = data.getVelocity();
            // A server-side Player can have no client connection while its
            // world entity remains alive and valid.
            if ((!player.isOnline() && !player.isValid()) || player.isDead()) {
                if (pending == expected) {
                    data.setVelocity(null);
                }
                return;
            }
            if (pending != expected) return;
            Vector delivered = consumePendingKnockback(player, player.getVelocity());
            if (delivered != null) player.setVelocity(delivered);
        });
    }

    /** A connected native hit must never be replayed one tick late. */
    private void expirePendingLegacyKnockback(PlayerKnockbackData data, Vector expected) {
        Bukkit.getScheduler().runTask(AlleyPlugin.getInstance(), () -> {
            if (data.getVelocity() == expected) data.setVelocity(null);
        });
    }

    /** Modern pearl impact code may overwrite a velocity event after it fires. */
    private void forceLegacyPearlKnockback(Player player, PlayerKnockbackData data,
                                          Vector expected) {
        Bukkit.getScheduler().runTask(AlleyPlugin.getInstance(), () -> {
            // A native Bot is a valid server-side Player without a real client.
            if ((!player.isOnline() && !player.isValid()) || player.isDead()) {
                if (data.getVelocity() == expected) {
                    data.setVelocity(null);
                }
                return;
            }
            if (data.getVelocity() != expected) return;

            Vector delivered = consumePendingKnockback(player, player.getVelocity());
            if (delivered != null) player.setVelocity(delivered);
        });
    }

    /**
     * Pre-compensates custom slowdown during the native attack call. NMS applies
     * its fixed 0.6 multiplier immediately after the matching knockback event.
     */
    private void queueLegacyAttackerSlowdownAdjustment(Player attacker, Player victim,
                                                       double horizontalSlowdown) {
        if (Math.abs(horizontalSlowdown - 0.6D) < 1.0E-8D) return;

        UUID attackerId = attacker.getUniqueId();
        PendingLegacyAttackerSlowdown pending = new PendingLegacyAttackerSlowdown(
                victim.getUniqueId(), Bukkit.getCurrentTick(), horizontalSlowdown);
        pendingLegacyAttackerSlowdowns.put(attackerId, pending);

        Bukkit.getScheduler().runTask(AlleyPlugin.getInstance(), () -> {
            pendingLegacyAttackerSlowdowns.remove(attackerId, pending);
        });
    }

    /**
     * Uses the NMS-produced velocity event as the authoritative modern vertical.
     * Horizontal knockback remains owned by the configured KB profile.
     */
    private void applyDownwardKnockback(Player victim, KnockbackProfile profile, Vector knockback, Vector nativeVelocity) {
        if (profile.getBranch() == KnockbackBranch.LEGACY) {
            // Legacy downward KB is always disabled and intentionally has no YAML switch.
            knockback.setY(Math.max(0.0, knockback.getY()));
            return;
        }

        if (profile.isDisableDownwardKb()) {
            knockback.setY(Math.max(0.0, knockback.getY()));
            return;
        }

        if (!victim.isOnGround()) {
            knockback.setY(nativeVelocity.getY());
        }
    }

    private record AttackCooldownSnapshot(UUID targetId, float strength, int tick) {
    }

    private static final class PendingLegacyAttackerSlowdown {
        private final UUID victimId;
        private final int tick;
        private final double horizontalSlowdown;

        private PendingLegacyAttackerSlowdown(UUID victimId, int tick,
                                              double horizontalSlowdown) {
            this.victimId = victimId;
            this.tick = tick;
            this.horizontalSlowdown = horizontalSlowdown;
        }

        private UUID victimId() {
            return victimId;
        }

        private int tick() {
            return tick;
        }

        private double horizontalSlowdown() {
            return horizontalSlowdown;
        }
    }
}
