package dev.revere.alley.feature.match.combat.legacy;

import com.destroystokyo.paper.event.entity.ProjectileCollideEvent;
import com.destroystokyo.paper.event.player.PlayerLaunchProjectileEvent;
import io.papermc.paper.event.player.PlayerStopUsingItemEvent;
import dev.revere.alley.AlleyPlugin;
import dev.revere.alley.core.profile.Profile;
import dev.revere.alley.core.profile.ProfileService;
import dev.revere.alley.feature.knockback.KnockbackManager;
import org.bukkit.*;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.entity.*;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.entity.*;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.player.*;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.InventoryView;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.PotionMeta;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.potion.PotionType;
import org.bukkit.projectiles.ProjectileSource;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.util.RayTraceResult;
import org.bukkit.util.Vector;

import java.util.*;
import java.util.concurrent.ThreadLocalRandom;
/**
 * @author Ddk1 ClaudeCode
 * @project Alley
 * @since 13/06/2026
 *
 * Implements all 1.8 legacy PVP mechanics, organized by KitSetting module.
 */
public class LegacyCombatListener implements Listener {

    private static final float LEGACY_ACTION_EXHAUSTION_SCALE = 2.0F / 3.0F;
    private static final EntityDamageEvent.DamageModifier[] PRE_ARMOUR_MODIFIERS = {
            EntityDamageEvent.DamageModifier.INVULNERABILITY_REDUCTION,
            EntityDamageEvent.DamageModifier.FREEZING,
            EntityDamageEvent.DamageModifier.HARD_HAT,
            EntityDamageEvent.DamageModifier.BLOCKING
    };

    private final LegacyCombatService svc;
    private final LegacyProjectileCollisionTracker projectileCollisionTracker;
    private final LegacyProjectileVelocity projectileVelocity;
    private final LegacyFoodUseController foodUseController;
    private final java.util.Random blockRng = new java.util.Random();
    private final Set<UUID> pendingPearlDamageVictims = new HashSet<>();
    private final Map<UUID, EntityDamageEvent> acceptedPearlDamageEvents = new HashMap<>();

    public LegacyCombatListener(LegacyCombatService svc) {
        this.svc = svc;
        this.projectileCollisionTracker = new LegacyProjectileCollisionTracker(svc);
        this.projectileVelocity = new LegacyProjectileVelocity();
        this.foodUseController = new LegacyFoodUseController();
    }

    // ================================================================
    //  oldSwordBlockKB — sword blocking, damage, knockback, sweep, crits, burn
    // ================================================================

    // --- Sword right-click → blocking (Paper consumable path) ---

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onSwordRightClick(PlayerInteractEvent e) {
        Player p = e.getPlayer();
        if (!svc.hasSwordBlockKB(p.getUniqueId())) return;
        Action a = e.getAction();
        if (a != Action.RIGHT_CLICK_AIR && a != Action.RIGHT_CLICK_BLOCK) return;
        if (e.getHand() != EquipmentSlot.HAND) return;
        ItemStack item = e.getItem();
        if (item == null || !item.getType().name().endsWith("_SWORD")) return;
        // Raise hand for blocking animation
        try { p.startUsingItem(EquipmentSlot.HAND); } catch (Throwable ignored) {}
        svc.setBlocking(p.getUniqueId(), true);
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onSlotChange(PlayerItemHeldEvent e) { svc.setBlocking(e.getPlayer().getUniqueId(), false); }
    @EventHandler(priority = EventPriority.MONITOR)
    public void onSwap(PlayerSwapHandItemsEvent e) { svc.setBlocking(e.getPlayer().getUniqueId(), false); }
    @EventHandler(priority = EventPriority.MONITOR)
    public void onDrop(PlayerDropItemEvent e) { svc.setBlocking(e.getPlayer().getUniqueId(), false); }
    @EventHandler(priority = EventPriority.MONITOR)
    public void onStopUsingItem(PlayerStopUsingItemEvent e) {
        svc.setBlocking(e.getPlayer().getUniqueId(), false);
    }

    // --- Apply blocks_attacks when switching to sword ---

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onHeldSword(PlayerItemHeldEvent e) {
        Player p = e.getPlayer();
        svc.onHeldSword(p, p.getInventory().getItem(e.getNewSlot()));
    }

    // --- Shield damage reduction (sword block → 50%) ---

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onBlockReduction(EntityDamageByEntityEvent e) {
        if (!(e.getEntity() instanceof Player victim)) return;
        if (!isLegacyBlockableDamage(e)) return;
        if (!svc.isBlocking(victim.getUniqueId())) return;
        ItemStack main = victim.getInventory().getItemInMainHand();
        if (!main.getType().name().endsWith("_SWORD")) return;

        // 80% block success rate
        if (blockRng.nextDouble() > 0.8) {
            if (e.isApplicable(EntityDamageEvent.DamageModifier.BLOCKING)) {
                e.setDamage(EntityDamageEvent.DamageModifier.BLOCKING, 0.0);
            }
            playSwordBlockSound(victim, Sound.ITEM_SHIELD_BREAK, 0.5f, 1.0f);
            return;
        }

        double base = e.getDamage(EntityDamageEvent.DamageModifier.BASE)
                    + e.getDamage(EntityDamageEvent.DamageModifier.HARD_HAT);
        double reduction = Math.max(0.0, (base - 1.0) * 0.5);
        if (e.isApplicable(EntityDamageEvent.DamageModifier.BLOCKING)) {
            e.setDamage(EntityDamageEvent.DamageModifier.BLOCKING, -reduction);
        } else {
            e.setDamage(Math.max(0.0, e.getDamage() - reduction));
        }
        playSwordBlockSound(victim, Sound.ITEM_SHIELD_BLOCK, 1.0f, 0.8f);
    }

    private void playSwordBlockSound(Player player, Sound sound, float volume, float pitch) {
        Profile profile = plugin().getService(ProfileService.class).getProfile(player.getUniqueId());
        if (profile == null || !profile.getProfileData().getSettingData().isSwordBlockSoundsEnabled()) return;

        player.playSound(player.getLocation(), sound, volume, pitch);
    }

    private boolean isLegacyBlockableDamage(EntityDamageByEntityEvent event) {
        if (event.getCause() == EntityDamageEvent.DamageCause.ENTITY_ATTACK) return true;
        if (!(event.getDamager() instanceof Projectile projectile)) return false;

        // 1.8 indirect magic damage bypasses blocking; ordinary arrows,
        // fireballs, snowballs, eggs and similar projectile sources do not.
        return !(projectile instanceof ThrownPotion)
                && !(projectile instanceof ThrownExpBottle);
    }

    // --- Old tool damage (1.8 sword/axe base) — same logic for both, no per-weapon difference ---

    // LOWEST priority: enforce hit delay + set 1.8 base damage BEFORE armour calc
    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onToolDamage(EntityDamageByEntityEvent e) {
        if (!(e.getDamager() instanceof Player attacker)) return;
        if (e.getCause() != EntityDamageEvent.DamageCause.ENTITY_ATTACK) return;
        if (isSyntheticLegacyRodHit(e)) return;

        UUID u = attacker.getUniqueId();
        boolean legacyToolDamage = svc.hasSwordBlockKB(u) || svc.hasOldEnchants(u);
        boolean legacyPotionValues = svc.hasSwordBlockKB(u);
        if (!legacyToolDamage && !legacyPotionValues) return;

        ItemStack weapon = attacker.getInventory().getItemInMainHand();
        Double legacyBase = legacyToolDamage ? toolDamage(weapon.getType()) : null;
        if (legacyBase == null && !legacyPotionValues) return;

        boolean legacyCrit = e.getEntity() instanceof LivingEntity
                && svc.hasSwordBlockKB(u)
                && isLegacyCritical(attacker);
        boolean vanillaCrit = legacyCrit && hasVanillaCritical(attacker);
        double base = legacyBase != null
                ? legacyBase
                : recoverModernPotionBase(
                        vanillaCrit
                                ? e.getDamage(EntityDamageEvent.DamageModifier.BASE) / 1.5
                                : e.getDamage(EntityDamageEvent.DamageModifier.BASE),
                        attacker);
        base = applyLegacyPotionValues(attacker, base);
        if (legacyCrit) {
            base *= 1.5;
        }

        if (legacyBase != null && svc.hasOldEnchants(u)) {
            base += weapon.getEnchantmentLevel(Enchantment.SHARPNESS) * 1.25;
        }
        e.setDamage(EntityDamageEvent.DamageModifier.BASE, Math.max(0.0, base));
    }

    // --- Old critical hits (1.5x, allow sprinting) ---

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onCrit(EntityDamageByEntityEvent e) {
        if (!(e.getDamager() instanceof Player attacker)) return;
        if (!(e.getEntity() instanceof LivingEntity)) return;
        if (!svc.hasSwordBlockKB(attacker.getUniqueId())) return;
        if (isSyntheticLegacyRodHit(e)) return;
        if (!isLegacyCritical(attacker)) return;

        // onToolDamage already applies the 1.5x Legacy crit for this attacker: this
        // handler only runs for oldSwordBlockKB players, so the old
        // "composedByToolDamage" guard was always true and that block never executed.
        if (!isLegacyDamageSupplement(e)) {
            attacker.getWorld().spawnParticle(Particle.CRIT,
                    attacker.getLocation().add(0, 1, 0), 5, 0.2, 0.2, 0.2, 0);
        }
    }

    private boolean isSyntheticLegacyRodHit(EntityDamageByEntityEvent event) {
        return event.getEntity().getScoreboardTags().contains("alley_legacy_rod_kb");
    }

    private boolean isLegacyCritical(Player attacker) {
        return attacker.getFallDistance() > 0.0f
                && !attacker.isOnGround()
                && !attacker.isClimbing()
                && !attacker.isInWater()
                && !attacker.hasPotionEffect(PotionEffectType.BLINDNESS)
                && attacker.getVehicle() == null;
    }

    private boolean hasVanillaCritical(Player attacker) {
        return !attacker.isSprinting() && attacker.getAttackCooldown() > 0.9f;
    }

    // 1.8 briefly ignites a non-burning target before damage is accepted. The
    // modern Fire Aspect effect still supplies the successful 4 seconds/level.
    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onLegacyFireAspectPreIgnite(EntityDamageByEntityEvent event) {
        if (!(event.getDamager() instanceof Player attacker)) return;
        if (!(event.getEntity() instanceof LivingEntity victim)) return;
        if (event.getCause() != EntityDamageEvent.DamageCause.ENTITY_ATTACK) return;
        if (!svc.hasSwordBlockKB(attacker.getUniqueId()) || isSyntheticLegacyRodHit(event)) return;

        int fireAspect = attacker.getInventory().getItemInMainHand()
                .getEnchantmentLevel(Enchantment.FIRE_ASPECT);
        if (fireAspect <= 0 || victim.getFireTicks() > 0) return;

        victim.setFireTicks(20);
        Bukkit.getScheduler().runTask(plugin(), () -> {
            // A successful modern Fire Aspect application is at least 4 seconds;
            // a rejected 1.8 attack must undo only this temporary one-second fire.
            if (event.isCancelled() || victim.getFireTicks() <= 20) {
                victim.setFireTicks(0);
            }
        });
    }

    // --- Sword sweep removal ---

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onSweep(EntityDamageByEntityEvent e) {
        if (e.getCause() != EntityDamageEvent.DamageCause.ENTITY_SWEEP_ATTACK) return;
        if (!(e.getDamager() instanceof Player attacker)) return;
        if (svc.hasSwordBlockKB(attacker.getUniqueId())) e.setCancelled(true);
    }

    // --- Projectile knockback (snowball, egg and enemy pearl hits) ---

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onLegacyBowVelocity(EntityShootBowEvent event) {
        if (!(event.getEntity() instanceof Player player)) return;
        if (!(event.getProjectile() instanceof AbstractArrow arrow) || arrow instanceof Trident) return;
        if (!svc.hasSwordBlockKB(player.getUniqueId())) return;

        this.projectileVelocity.removeInheritedVelocity(arrow, player);
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onLegacyThrownVelocity(PlayerLaunchProjectileEvent event) {
        Projectile projectile = event.getProjectile();
        if (!isLegacyThrowable(projectile)) return;
        Player player = event.getPlayer();
        if (!svc.hasSwordBlockKB(player.getUniqueId())) return;

        this.projectileVelocity.removeInheritedVelocity(projectile, player);
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onBowShoot(EntityShootBowEvent e) {
        if (!(e.getEntity() instanceof Player player)) return;
        if (!(e.getProjectile() instanceof AbstractArrow arrow) || arrow instanceof Trident) return;
        if (!svc.hasSwordBlockKB(player.getUniqueId())) return;

        ItemStack bow = e.getBow();
        int punchLevel = bow == null ? 0 : bow.getEnchantmentLevel(Enchantment.PUNCH);
        int powerLevel = bow == null ? 0 : bow.getEnchantmentLevel(Enchantment.POWER);
        LegacyProjectileData.markArrow(arrow, punchLevel, powerLevel);
        this.projectileCollisionTracker.track(arrow, player);
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onThrownProjectileLaunch(ProjectileLaunchEvent e) {
        if (!(e.getEntity() instanceof Projectile projectile)) return;
        if (!isLegacyThrowable(projectile)) return;
        if (!(projectile.getShooter() instanceof Player player)) return;
        if (!svc.hasSwordBlockKB(player.getUniqueId())) return;

        if (projectile instanceof Snowball || projectile instanceof Egg || projectile instanceof EnderPearl) {
            LegacyProjectileData.mark(projectile, 0);
        }
        this.projectileCollisionTracker.track(projectile, player);
    }

    private boolean isLegacyThrowable(Projectile projectile) {
        return projectile instanceof Snowball
                || projectile instanceof Egg
                || projectile instanceof EnderPearl
                || projectile instanceof ThrownPotion
                || projectile instanceof ThrownExpBottle;
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onTrackedProjectileImpact(ProjectileHitEvent event) {
        this.projectileCollisionTracker.stopTracking(event.getEntity());
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onLegacyOwnerCollision(ProjectileCollideEvent event) {
        Projectile projectile = event.getEntity();
        if (!this.projectileCollisionTracker.isTracking(projectile)) return;
        if (!(projectile.getShooter() instanceof Player shooter)) return;
        if (!event.getCollidedWith().equals(shooter)) return;
        if (!svc.hasSwordBlockKB(shooter.getUniqueId())) return;

        if (this.projectileCollisionTracker.isOwnerImmune(projectile)) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onLegacyPearlHit(ProjectileHitEvent event) {
        if (!(event.getEntity() instanceof EnderPearl pearl)) return;
        if (!(event.getHitEntity() instanceof Player victim)) return;
        if (!(pearl.getShooter() instanceof Player attacker) || attacker.equals(victim)) return;
        if (!LegacyProjectileData.isMarked(pearl)
                || !svc.hasSwordBlockKB(attacker.getUniqueId())
                || !svc.hasSwordBlockKB(victim.getUniqueId())) return;
        if (victim.isDead() || victim.getGameMode() == GameMode.SPECTATOR) return;

        KnockbackManager knockbackManager = plugin().getService(KnockbackManager.class);
        // This path has to reset Bukkit's counter briefly in order to probe NMS
        // for the synthetic pearl hit. Never use that reset to bypass an already
        // active hurt window: a pearl is a tiny damage probe, so ordinary/fire ->
        // pearl must be rejected before the probe starts. The reverse direction
        // (pearl/rod -> a larger melee hit) is left to NMS's damage-delta branch.
        if (knockbackManager.isInsideHurtResistanceWindow(victim)
                || knockbackManager.wasInsideHurtResistanceWindow(victim)) return;

        UUID victimId = victim.getUniqueId();
        Vector impactVelocity = pearl.getVelocity().clone();
        int previousNoDamageTicks = victim.getNoDamageTicks();
        boolean accepted = false;
        pendingPearlDamageVictims.add(victimId);
        acceptedPearlDamageEvents.remove(victimId);
        knockbackManager.getPlayerData(victim).setSuppressLegacyPearlVelocity(true);
        victim.setNoDamageTicks(0);
        try {
            victim.damage(0.1, pearl);
            EntityDamageEvent acceptedDamage = acceptedPearlDamageEvents.remove(victimId);
            if (acceptedDamage != null && !acceptedDamage.isCancelled()) {
                accepted = true;
                knockbackManager.applyHitDelayWindow(victim);
                victim.setNoDamageTicks(victim.getMaximumNoDamageTicks());
                victim.playHurtAnimation(0.0F);
                knockbackManager.applyLegacyPearlKnockback(attacker, victim, impactVelocity);
            }
        } finally {
            knockbackManager.getPlayerData(victim).setSuppressLegacyPearlVelocity(false);
            if (!accepted) {
                victim.setNoDamageTicks(previousNoDamageTicks);
            }
            pendingPearlDamageVictims.remove(victimId);
            acceptedPearlDamageEvents.remove(victimId);
        }
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onProjectileKB(EntityDamageByEntityEvent e) {
        if (!(e.getEntity() instanceof Player victim)) return;
        Entity damager = e.getDamager();
        if (!(damager instanceof Projectile projectile) || !LegacyProjectileData.isMarked(projectile)) return;
        ProjectileSource shooter = projectile.getShooter();
        if (!(shooter instanceof Player attacker)) return;
        if (!svc.hasSwordBlockKB(attacker.getUniqueId())
                || !svc.hasSwordBlockKB(victim.getUniqueId())) return;

        EntityType type = damager.getType();
        if (type == EntityType.ENDER_PEARL) {
            // Vanilla emits a zero-damage event when a pearl collides with an
            // entity. Keep that native event harmless; onLegacyPearlHit below
            // applies the single fixed target hit and the legacy knockback.
            if (!attacker.equals(victim)) e.setDamage(0.0);
        } else if (type == EntityType.SNOWBALL || type == EntityType.EGG) {
            if (e.getDamage() == 0.0) e.setDamage(0.001);
            if (e.isApplicable(EntityDamageEvent.DamageModifier.ABSORPTION))
                e.setDamage(EntityDamageEvent.DamageModifier.ABSORPTION, 0);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onLegacyPearlDamageAccepted(EntityDamageEvent event) {
        if (!(event.getEntity() instanceof Player victim)) return;
        UUID victimId = victim.getUniqueId();
        if (pendingPearlDamageVictims.contains(victimId)) {
            setFixedPearlDamage(event, 0.1);
            acceptedPearlDamageEvents.put(victimId, event);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onLegacyPearlSelfDamage(EntityDamageEvent event) {
        if (!(event.getEntity() instanceof Player victim)) return;
        if (!(event instanceof EntityDamageByEntityEvent damageEvent)
                || !(damageEvent.getDamager() instanceof EnderPearl pearl)
                || !(pearl.getShooter() instanceof Player attacker)
                || !attacker.equals(victim)
                || !LegacyProjectileData.isMarked(pearl)
                || !svc.hasSwordBlockKB(victim.getUniqueId())) return;

        setFixedPearlDamage(event, 0.5);
    }

    /** Keeps pearl damage independent of armour, resistance, and absorption modifiers. */
    private void setFixedPearlDamage(EntityDamageEvent event, double amount) {
        event.setDamage(amount);
        for (EntityDamageEvent.DamageModifier modifier : EntityDamageEvent.DamageModifier.values()) {
            if (modifier != EntityDamageEvent.DamageModifier.BASE && event.isApplicable(modifier)) {
                event.setDamage(modifier, 0.0);
            }
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onLegacyPearlTeleport(PlayerTeleportEvent event) {
        if (event.getCause() != PlayerTeleportEvent.TeleportCause.ENDER_PEARL
                || !svc.hasSwordBlockKB(event.getPlayer().getUniqueId())) return;

        svc.suppressPearlTeleportSound(event.getFrom(), event.getTo());
    }

    // --- Fishing rod launch velocity (1.7/1.8) ---

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onRodLaunch(ProjectileLaunchEvent e) {
        if (!(e.getEntity() instanceof FishHook hook)) return;
        if (hook.hasMetadata(dev.revere.alley.feature.item.listener.GrapplingHookListener.HOOK_METADATA)) return;
        if (!(hook.getShooter() instanceof Player p)) return;
        if (!svc.hasSwordBlockKB(p.getUniqueId())) return;

        Location facing = p.getLocation();
        double yaw = Math.toRadians(facing.getYaw());
        double pitch = Math.toRadians(facing.getPitch());
        Vector velocity = new Vector(
                -Math.sin(yaw) * Math.cos(pitch) * 0.4,
                -Math.sin(pitch) * 0.4,
                Math.cos(yaw) * Math.cos(pitch) * 0.4).normalize();
        velocity.add(new Vector(
                blockRng.nextGaussian() * 0.007499999832361937,
                blockRng.nextGaussian() * 0.007499999832361937,
                blockRng.nextGaussian() * 0.007499999832361937));
        velocity.multiply(1.5);
        hook.setVelocity(velocity);

        new BukkitRunnable() {
            private Location previousLocation = hook.getLocation().clone();

            @Override
            public void run() {
                if (!hook.isValid() || hook.isDead()) {
                    cancel();
                    return;
                }

                Location currentLocation = hook.getLocation();
                Player hitPlayer = findRodHitAlongPath(hook, p, previousLocation, currentLocation);
                if (hitPlayer != null) {
                    applyFishingRodHit(hook, p, hitPlayer);
                    hook.setHookedEntity(hitPlayer);
                    cancel();
                    return;
                }
                if (!hook.isInWater() && !hook.isOnGround()) {
                    Vector current = hook.getVelocity();
                    current.setY(current.getY() - 0.01);
                    hook.setVelocity(current);
                }
                previousLocation = currentLocation.clone();
            }
        }.runTaskTimer(plugin(), 1L, 1L);
    }

    // --- Fishing rod knockback (OCM style: damage + KB on bobber hit, cancel drag on reel) ---

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onFishingRodHit(ProjectileHitEvent e) {
        if (!(e.getEntity() instanceof FishHook hook)) return;
        if (hook.hasMetadata(dev.revere.alley.feature.item.listener.GrapplingHookListener.HOOK_METADATA)) return;
        if (!(hook.getShooter() instanceof Player attacker)) return;
        if (!svc.hasSwordBlockKB(attacker.getUniqueId())) return;

        Entity hitEntity = e.getHitEntity();
        if (hitEntity == null) hitEntity = findNearbyEntity(hook);
        applyFishingRodHit(hook, attacker, hitEntity);
    }

    private void applyFishingRodHit(FishHook hook, Player attacker, Entity hitEntity) {
        if (!(hitEntity instanceof LivingEntity victim)) return;
        if (hitEntity.equals(attacker)) return;
        if (victim instanceof Player target) {
            if (target.isDead() || target.getGameMode() == GameMode.SPECTATOR
                    || !svc.hasSwordBlockKB(target.getUniqueId())) return;
        }
        if (hook.getScoreboardTags().contains("alley_rod_hit_applied")) return;
        hook.addScoreboardTag("alley_rod_hit_applied");

        // KnockbackManager owns velocity delivery for this legacy rod hit.
        victim.addScoreboardTag("alley_rod");
        victim.addScoreboardTag("alley_legacy_rod_kb");
        try {
            victim.damage(0.01, attacker);
        } finally {
            victim.removeScoreboardTag("alley_rod");
            victim.removeScoreboardTag("alley_legacy_rod_kb");
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onFishingReel(PlayerFishEvent e) {
        if (e.getState() != PlayerFishEvent.State.CAUGHT_ENTITY) return;
        if (e.getHook().hasMetadata(dev.revere.alley.feature.item.listener.GrapplingHookListener.HOOK_METADATA)) return;
        if (!svc.hasSwordBlockKB(e.getPlayer().getUniqueId())) return;
        applyFishingRodHit(e.getHook(), e.getPlayer(), e.getCaught());
        e.setCancelled(true);
        e.getHook().remove();
    }

    private Entity findNearbyEntity(FishHook hook) {
        Entity shooter = hook.getShooter() instanceof Entity entity ? entity : null;
        return hook.getWorld().getNearbyEntities(hook.getLocation(), 1.0, 2.0, 1.0).stream()
                .filter(en -> en instanceof Player)
                .filter(en -> shooter == null || !en.getUniqueId().equals(shooter.getUniqueId()))
                .filter(en -> LegacyHitboxes.projectileTarget(en).contains(hook.getLocation().toVector()))
                .min(Comparator.comparingDouble(en -> en.getLocation().distanceSquared(hook.getLocation())))
                .orElse(null);
    }

    /** Checks each flight segment against the 1.8 standing box expanded by 0.3. */
    private Player findRodHitAlongPath(FishHook hook, Player attacker, Location from, Location to) {
        Vector travel = to.toVector().subtract(from.toVector());
        double distance = travel.length();
        if (distance <= 1.0E-5) return null;

        Vector direction = travel.multiply(1.0 / distance);
        Location midpoint = from.clone().add(direction.clone().multiply(distance / 2.0));
        double searchRadius = distance / 2.0 + 0.5;
        Player closest = null;
        double closestDistance = Double.MAX_VALUE;

        for (Entity entity : hook.getWorld().getNearbyEntities(midpoint, searchRadius, 2.0, searchRadius)) {
            if (!(entity instanceof Player target) || target.equals(attacker) || target.isDead()) continue;

            RayTraceResult hit = LegacyHitboxes.projectileTarget(target)
                    .rayTrace(from.toVector(), direction, distance);
            if (hit == null) continue;

            double hitDistance = from.toVector().distanceSquared(hit.getHitPosition());
            if (hitDistance < closestDistance) {
                closest = target;
                closestDistance = hitDistance;
            }
        }
        return closest;
    }

    // KB handled by KnockbackListener — see feature/knockback/listener/
    // ================================================================
    //  oldFood — player regen, golden apple, potion effects, run-eating
    // ================================================================

    // --- 1.8 natural regeneration: food >= 18, 1 health every 80 ticks ---

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onRegen(EntityRegainHealthEvent e) {
        if (e.getEntityType() != EntityType.PLAYER) return;
        Player p = (Player) e.getEntity();
        if (!svc.hasOldFood(p.getUniqueId())) return;

        EntityRegainHealthEvent.RegainReason reason = e.getRegainReason();
        if (reason == EntityRegainHealthEvent.RegainReason.SATIATED
                || reason == EntityRegainHealthEvent.RegainReason.REGEN) {
            if (!svc.isApplyingNaturalHeal(p.getUniqueId())) {
                e.setCancelled(true);
                return;
            }
            e.setAmount(1.0);
        }
    }

    // --- Golden apple effects (1.8) ---

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onGapple(PlayerItemConsumeEvent e) {
        Player p = e.getPlayer();
        if (!svc.hasOldFood(p.getUniqueId())) return;
        Material mat = e.getItem().getType();
        if (mat == Material.GOLDEN_APPLE) {
            e.setCancelled(true);
            consumeOne(p, e.getHand());
            p.addPotionEffect(new PotionEffect(PotionEffectType.ABSORPTION, 2400, 0), true);
            p.addPotionEffect(new PotionEffect(PotionEffectType.REGENERATION, 100, 1), true);
            p.setFoodLevel(Math.min(20, p.getFoodLevel() + 4));
            p.setSaturation(Math.min(p.getFoodLevel(), p.getSaturation() + 9.6f));
        } else if (mat == Material.ENCHANTED_GOLDEN_APPLE) {
            e.setCancelled(true);
            consumeOne(p, e.getHand());
            p.addPotionEffect(new PotionEffect(PotionEffectType.ABSORPTION, 2400, 0), true);
            p.addPotionEffect(new PotionEffect(PotionEffectType.REGENERATION, 600, 4), true);
            p.addPotionEffect(new PotionEffect(PotionEffectType.FIRE_RESISTANCE, 6000, 0), true);
            p.addPotionEffect(new PotionEffect(PotionEffectType.RESISTANCE, 6000, 0), true);
            p.setFoodLevel(Math.min(20, p.getFoodLevel() + 4));
            p.setSaturation(Math.min(p.getFoodLevel(), p.getSaturation() + 9.6f));
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onPotionDrink(PlayerItemConsumeEvent e) {
        if (!svc.hasOldFood(e.getPlayer().getUniqueId())) return;
        ItemStack potion = e.getItem();
        if (potion.getType() != Material.POTION) return;
        if (applyLegacyPotionDuration(potion, false)) {
            e.setItem(potion);
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onPotionThrow(ProjectileLaunchEvent e) {
        if (!(e.getEntity() instanceof ThrownPotion potion)) return;
        if (!(potion.getShooter() instanceof Player player)) return;
        if (!svc.hasOldFood(player.getUniqueId())) return;

        ItemStack potionItem = potion.getItem();
        if (applyLegacyPotionDuration(potionItem, true)) {
            potion.setItem(potionItem);
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onLegacyExhaustion(EntityExhaustionEvent e) {
        if (!(e.getEntity() instanceof Player player)) return;
        if (e.getExhaustionReason() == EntityExhaustionEvent.ExhaustionReason.ATTACK
                && svc.hasSwordBlockKB(player.getUniqueId())) {
            e.setExhaustion(0.3F * LEGACY_ACTION_EXHAUSTION_SCALE);
            return;
        }
        if (!svc.hasOldFood(player.getUniqueId())) return;
        switch (e.getExhaustionReason()) {
            case REGEN -> e.setExhaustion(0.0F);
            case JUMP -> e.setExhaustion(0.2F * LEGACY_ACTION_EXHAUSTION_SCALE);
            case JUMP_SPRINT -> e.setExhaustion(0.8F * LEGACY_ACTION_EXHAUSTION_SCALE);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onLegacyFoodDrop(PlayerDropItemEvent e) {
        Player player = e.getPlayer();
        if (!svc.hasOldFood(player.getUniqueId())) return;
        this.foodUseController.interruptUse(player);
    }

    // ================================================================
    //  oldOffhandSounds — disable offhand, block 1.9+ attack sounds
    // ================================================================

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onSwapOffhand(PlayerSwapHandItemsEvent e) {
        if (svc.hasOldOffhand(e.getPlayer().getUniqueId())) e.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onOffhandClick(InventoryClickEvent e) {
        if (!(e.getWhoClicked() instanceof Player p)) return;
        if (!svc.hasOldOffhand(p.getUniqueId())) return;
        if (e.getClick() == ClickType.SWAP_OFFHAND || isOffhandSlot(e.getView(), e.getRawSlot(), p)) {
            e.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onOffhandDrag(InventoryDragEvent e) {
        if (!(e.getWhoClicked() instanceof Player p)) return;
        if (!svc.hasOldOffhand(p.getUniqueId())) return;
        if (e.getRawSlots().stream().anyMatch(slot -> isOffhandSlot(e.getView(), slot, p))) {
            e.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onOffhandInteract(PlayerInteractEvent e) {
        if (e.getHand() == EquipmentSlot.OFF_HAND && svc.hasOldOffhand(e.getPlayer().getUniqueId())) {
            e.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onOffhandEntityInteract(PlayerInteractEntityEvent e) {
        if (e.getHand() == EquipmentSlot.OFF_HAND && svc.hasOldOffhand(e.getPlayer().getUniqueId())) {
            e.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onOffhandConsume(PlayerItemConsumeEvent e) {
        if (e.getHand() == EquipmentSlot.OFF_HAND && svc.hasOldOffhand(e.getPlayer().getUniqueId())) {
            e.setCancelled(true);
        }
    }

    // --- Old attack sounds (play ENTITY_PLAYER_HURT instead of 1.9+ sounds) ---

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onOldSound(EntityDamageByEntityEvent e) {
        if (!(e.getEntity() instanceof Player p)) return;
        if (!svc.hasOldOffhand(p.getUniqueId())) return;
        if (isLegacyDamageSupplement(e)) return;
        p.getWorld().playSound(p.getLocation(), Sound.ENTITY_PLAYER_HURT, 1.0f, 1.0f);
    }

    // ================================================================
    //  oldEnchantsArmor — old armour strength, durability, tool damage, sharpness, protection
    // ================================================================

    // --- Armour strength (1.8 formula: dmg * (25 - points) / 25) ---

    // Run AFTER onToolDamage (LOWEST) so corrected 1.8 base damage is used
    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onArmourStrength(EntityDamageEvent e) {
        if (!(e.getEntity() instanceof Player p)) return;
        if (!svc.hasOldEnchants(p.getUniqueId())) return;

        double damage = damageBeforeArmour(e);
        if (damage <= 0.0D) return;

        // 1.8 applies a fixed 4% reduction per armour point. Damage strength and
        // ARMOR_TOUGHNESS never participate in this calculation.
        if (e.isApplicable(EntityDamageEvent.DamageModifier.ARMOR)) {
            double afterArmour = damage * (25.0D - Math.min(20.0D, armourPoints(p))) / 25.0D;
            e.setDamage(EntityDamageEvent.DamageModifier.ARMOR, afterArmour - damage);
            damage = afterArmour;
        }

        // Resistance is between armour and enchantments in EntityLivingBase.
        if (e.isApplicable(EntityDamageEvent.DamageModifier.RESISTANCE)) {
            double afterResistance = applyLegacyResistance(p, e, damage);
            e.setDamage(EntityDamageEvent.DamageModifier.RESISTANCE, afterResistance - damage);
            damage = afterResistance;
        }

        if (e.isApplicable(EntityDamageEvent.DamageModifier.MAGIC)) {
            int protection = legacyProtectionFactor(p, e);
            double afterProtection = damage * (25.0D - protection) / 25.0D;
            e.setDamage(EntityDamageEvent.DamageModifier.MAGIC, afterProtection - damage);
            damage = afterProtection;
        }

        // Modifier changes do not recalculate Bukkit's cached absorption value.
        if (e.isApplicable(EntityDamageEvent.DamageModifier.ABSORPTION)) {
            e.setDamage(EntityDamageEvent.DamageModifier.ABSORPTION,
                    -Math.min(damage, p.getAbsorptionAmount()));
        }
    }

    // --- Armour durability (full-block prevents armour damage, 1.8 style) ---

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onArmourDurability(PlayerItemDamageEvent e) {
        Player p = e.getPlayer();
        if (!svc.hasOldEnchants(p.getUniqueId())) return;
        if (!isWornArmour(p, e.getItem())) return;

        int unbreaking = e.getItem().getEnchantmentLevel(Enchantment.UNBREAKING);
        int damageChance = 60 + 40 / (Math.max(0, unbreaking) + 1);
        if (ThreadLocalRandom.current().nextInt(100) >= damageChance) {
            e.setDamage(0);
        }
    }

    // ================================================================
    //  Helpers
    // ================================================================

    private AlleyPlugin plugin() { return AlleyPlugin.getInstance(); }

    private boolean isLegacyDamageSupplement(EntityDamageEvent event) {
        if (!(event.getEntity() instanceof Player victim)) return false;
        if (!svc.hasSwordBlockKB(victim.getUniqueId())) return false;
        return plugin().getService(KnockbackManager.class)
                .wasInsideHurtResistanceWindow(victim);
    }

    private boolean isSword(Material t) { return t.name().endsWith("_SWORD"); }
    private boolean isAxe(Material t) { return t.name().endsWith("_AXE"); }

    private double swordDmg(Material t) { return switch (t) {
        case WOODEN_SWORD, GOLDEN_SWORD -> 4.0; case STONE_SWORD -> 5.0;
        case IRON_SWORD -> 6.0; case DIAMOND_SWORD -> 7.0; case NETHERITE_SWORD -> 8.0; default -> 0;
    };}
    private double axeDmg(Material t) { return switch (t) {
        case WOODEN_AXE, GOLDEN_AXE -> 3.0; case STONE_AXE -> 4.0;
        case IRON_AXE -> 5.0; case DIAMOND_AXE -> 6.0; case NETHERITE_AXE -> 7.0; default -> 0;
    };}
    private double pickaxeDmg(Material t) { return switch (t) {
        case WOODEN_PICKAXE, GOLDEN_PICKAXE -> 2.0; case STONE_PICKAXE -> 3.0;
        case IRON_PICKAXE -> 4.0; case DIAMOND_PICKAXE -> 5.0; case NETHERITE_PICKAXE -> 6.0; default -> 0;
    };}
    private double shovelDmg(Material t) { return switch (t) {
        case WOODEN_SHOVEL, GOLDEN_SHOVEL -> 1.0; case STONE_SHOVEL -> 2.0;
        case IRON_SHOVEL -> 3.0; case DIAMOND_SHOVEL -> 4.0; case NETHERITE_SHOVEL -> 5.0; default -> 0;
    };}

    private Double toolDamage(Material type) {
        // EntityPlayer contributes 1 base damage before the held item's
        // attribute modifier in 1.8.
        if (isSword(type)) return 1.0 + swordDmg(type);
        if (isAxe(type)) return 1.0 + axeDmg(type);
        if (type.name().endsWith("_PICKAXE")) return 1.0 + pickaxeDmg(type);
        if (type.name().endsWith("_SHOVEL")) return 1.0 + shovelDmg(type);
        if (type.name().endsWith("_HOE")) return 1.0;
        return null;
    }

    private double recoverModernPotionBase(double currentBase, Player attacker) {
        int strength = potionAmplifier(attacker, PotionEffectType.STRENGTH);
        int weakness = potionAmplifier(attacker, PotionEffectType.WEAKNESS);
        if (strength >= 0) currentBase -= 3.0 * (strength + 1);
        if (weakness >= 0) currentBase += 4.0 * (weakness + 1);
        return Math.max(0.0, currentBase);
    }

    private double applyLegacyPotionValues(Player attacker, double base) {
        if (!svc.hasSwordBlockKB(attacker.getUniqueId())) return base;
        int strength = potionAmplifier(attacker, PotionEffectType.STRENGTH);
        int weakness = potionAmplifier(attacker, PotionEffectType.WEAKNESS);
        if (weakness >= 0) base -= 0.5 * (weakness + 1);
        if (strength >= 0) base *= 1.0 + 1.3 * (strength + 1);
        return Math.max(0.0, base);
    }

    private int potionAmplifier(Player player, PotionEffectType type) {
        PotionEffect effect = player.getPotionEffect(type);
        return effect == null ? -1 : effect.getAmplifier();
    }

    private void consumeOne(Player player, EquipmentSlot hand) {
        ItemStack item = hand == EquipmentSlot.OFF_HAND
                ? player.getInventory().getItemInOffHand()
                : player.getInventory().getItemInMainHand();
        if (item == null || item.getType().isAir()) return;

        if (item.getAmount() <= 1) {
            item = new ItemStack(Material.AIR);
        } else {
            item.setAmount(item.getAmount() - 1);
        }
        if (hand == EquipmentSlot.OFF_HAND) {
            player.getInventory().setItemInOffHand(item);
        } else {
            player.getInventory().setItemInMainHand(item);
        }
    }

    private boolean isWornArmour(Player player, ItemStack item) {
        if (item == null || item.getType() == Material.ELYTRA) return false;
        for (ItemStack armour : player.getInventory().getArmorContents()) {
            if (armour != null && armour.getType() == item.getType()) return true;
        }
        return false;
    }

    private boolean applyLegacyPotionDuration(ItemStack item, boolean splash) {
        if (item == null || !(item.getItemMeta() instanceof PotionMeta meta)) return false;
        PotionType potionType = meta.getBasePotionType();
        if (potionType == null || potionType.isInstant()) return false;

        int duration = legacyPotionDuration(potionType, splash);
        PotionEffectType effectType = potionType.getEffectType();
        if (duration < 0 || effectType == null) return false;

        int amplifier = potionType.name().startsWith("STRONG_") ? 1 : 0;
        meta.addCustomEffect(new PotionEffect(effectType, duration, amplifier), true);
        meta.setBasePotionType(PotionType.WATER);
        item.setItemMeta(meta);
        return true;
    }

    private int legacyPotionDuration(PotionType potionType, boolean splash) {
        String name = potionType.name();
        boolean strong = name.startsWith("STRONG_");
        boolean extended = name.startsWith("LONG_");
        String base = strong ? name.substring("STRONG_".length())
                : extended ? name.substring("LONG_".length()) : name;

        return switch (base) {
            case "REGENERATION", "POISON" -> potionDuration(splash,
                    strong ? 440 : extended ? 2400 : 900,
                    strong ? 320 : extended ? 1800 : 660);
            case "SWIFTNESS", "STRENGTH", "LEAPING" -> potionDuration(splash,
                    strong ? 1800 : extended ? 9600 : 3600,
                    strong ? 1340 : extended ? 7200 : 2700);
            case "FIRE_RESISTANCE", "WATER_BREATHING", "INVISIBILITY" -> potionDuration(splash,
                    extended ? 9600 : 3600,
                    extended ? 7200 : 2700);
            case "NIGHT_VISION" -> potionDuration(splash,
                    extended ? 9600 : 3600,
                    extended ? 9600 : 3600);
            case "WEAKNESS" -> potionDuration(splash,
                    extended ? 4800 : 1800,
                    extended ? 4800 : 1800);
            case "SLOWNESS" -> potionDuration(splash,
                    extended ? 4800 : 1800,
                    extended ? 3600 : 1340);
            default -> -1;
        };
    }

    private int potionDuration(boolean splash, int drinkDuration, int splashDuration) {
        return splash ? splashDuration : drinkDuration;
    }

    private boolean isOffhandSlot(InventoryView view, int rawSlot, Player player) {
        return rawSlot >= view.getTopInventory().getSize()
                && view.getBottomInventory().equals(player.getInventory())
                && view.convertSlot(rawSlot) == 40;
    }

    private double damageBeforeArmour(EntityDamageEvent event) {
        double damage = event.getDamage(EntityDamageEvent.DamageModifier.BASE);
        for (EntityDamageEvent.DamageModifier modifier : PRE_ARMOUR_MODIFIERS) {
            if (event.isApplicable(modifier)) damage += event.getDamage(modifier);
        }
        return Math.max(0.0D, damage);
    }

    private double applyLegacyResistance(Player player, EntityDamageEvent event, double damage) {
        if (event.getCause() == EntityDamageEvent.DamageCause.VOID
                || event.getCause() == EntityDamageEvent.DamageCause.KILL
                || event.getCause() == EntityDamageEvent.DamageCause.STARVATION) {
            return damage;
        }

        PotionEffect resistance = player.getPotionEffect(PotionEffectType.RESISTANCE);
        if (resistance == null) return damage;

        int reduction = (resistance.getAmplifier() + 1) * 5;
        return Math.max(0.0D, damage * (25.0D - reduction) / 25.0D);
    }

    private int legacyProtectionFactor(Player player, EntityDamageEvent event) {
        if (event.getCause() == EntityDamageEvent.DamageCause.VOID
                || event.getCause() == EntityDamageEvent.DamageCause.KILL
                || event.getCause() == EntityDamageEvent.DamageCause.STARVATION) {
            return 0;
        }

        int rawProtection = 0;
        for (ItemStack armour : player.getInventory().getArmorContents()) {
            if (armour == null || armour.getType().isAir()) continue;

            rawProtection += legacyProtectionValue(
                    armour.getEnchantmentLevel(Enchantment.PROTECTION), 0.75D);
            if (isFireDamage(event)) {
                rawProtection += legacyProtectionValue(
                        armour.getEnchantmentLevel(Enchantment.FIRE_PROTECTION), 1.25D);
            }
            if (event.getCause() == EntityDamageEvent.DamageCause.FALL) {
                rawProtection += legacyProtectionValue(
                        armour.getEnchantmentLevel(Enchantment.FEATHER_FALLING), 2.5D);
            }
            if (isExplosionDamage(event)) {
                rawProtection += legacyProtectionValue(
                        armour.getEnchantmentLevel(Enchantment.BLAST_PROTECTION), 1.5D);
            }
            if (event.getCause() == EntityDamageEvent.DamageCause.PROJECTILE) {
                rawProtection += legacyProtectionValue(
                        armour.getEnchantmentLevel(Enchantment.PROJECTILE_PROTECTION), 1.5D);
            }
        }

        int capped = Math.max(0, Math.min(25, rawProtection));
        if (capped == 0) return 0;

        int randomized = ((capped + 1) >> 1)
                + ThreadLocalRandom.current().nextInt((capped >> 1) + 1);
        return Math.min(20, randomized);
    }

    private int legacyProtectionValue(int level, double typeMultiplier) {
        if (level <= 0) return 0;
        double base = (6.0D + (double) level * level) / 3.0D;
        return (int) Math.floor(base * typeMultiplier);
    }

    private boolean isFireDamage(EntityDamageEvent event) {
        return switch (event.getCause()) {
            case FIRE, FIRE_TICK, LAVA, HOT_FLOOR, CAMPFIRE, MELTING -> true;
            default -> false;
        };
    }

    private boolean isExplosionDamage(EntityDamageEvent event) {
        return event.getCause() == EntityDamageEvent.DamageCause.BLOCK_EXPLOSION
                || event.getCause() == EntityDamageEvent.DamageCause.ENTITY_EXPLOSION;
    }

    private double armourPoints(Player p) {
        double pts = 0;
        for (ItemStack a : p.getInventory().getArmorContents()) {
            if (a == null) continue;
            pts += switch (a.getType()) {
                case LEATHER_HELMET, LEATHER_BOOTS, GOLDEN_BOOTS, CHAINMAIL_BOOTS -> 1;
                case LEATHER_LEGGINGS, GOLDEN_HELMET, CHAINMAIL_HELMET,
                        IRON_HELMET, IRON_BOOTS, TURTLE_HELMET -> 2;
                case LEATHER_CHESTPLATE, GOLDEN_LEGGINGS, DIAMOND_HELMET,
                        DIAMOND_BOOTS, NETHERITE_HELMET, NETHERITE_BOOTS -> 3;
                case CHAINMAIL_LEGGINGS -> 4;
                case GOLDEN_CHESTPLATE, CHAINMAIL_CHESTPLATE, IRON_LEGGINGS -> 5;
                case IRON_CHESTPLATE, DIAMOND_LEGGINGS, NETHERITE_LEGGINGS -> 6;
                case DIAMOND_CHESTPLATE, NETHERITE_CHESTPLATE -> 8;
                default -> 0;
            };
        }
        return pts;
    }

}
