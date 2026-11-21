package dev.revere.alley.feature.knockback.minemen;

import dev.revere.alley.feature.knockback.KnockbackBranch;
import dev.revere.alley.feature.knockback.KnockbackManager;
import dev.revere.alley.feature.knockback.KnockbackProfile;
import dev.revere.alley.feature.knockback.data.PlayerKnockbackData;
import dev.revere.alley.feature.match.combat.legacy.LegacyProjectileData;
import io.papermc.paper.event.entity.EntityKnockbackEvent;
import io.papermc.paper.event.entity.EntityPushedByEntityAttackEvent;
import io.papermc.paper.event.player.PrePlayerAttackEntityEvent;
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.entity.AbstractArrow;
import org.bukkit.entity.Egg;
import org.bukkit.entity.EnderPearl;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.entity.Projectile;
import org.bukkit.entity.Snowball;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.player.PlayerTeleportEvent;
import org.bukkit.event.player.PlayerVelocityEvent;
import org.bukkit.util.Vector;

import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

/** Independent ClubSpigot calculation, replacing the native impulses rather than stacking on them. */
public final class MinemenClubKnockbackListener implements Listener {
    private final KnockbackManager manager;
    private final MinemenClubDeliveryBridge bridge = new MinemenClubDeliveryBridge();
    private final Map<UUID, AttackState> attacks = new HashMap<>();
    private final Map<UUID, PendingHit> pending = new HashMap<>();
    private final Map<PlayerVelocityEvent, PendingHit> delivering = new IdentityHashMap<>();
    private final Map<UUID, BotVelocity> botVelocities = new HashMap<>();

    public MinemenClubKnockbackListener(KnockbackManager manager) {
        this.manager = manager;
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void beginAttack(PrePlayerAttackEntityEvent event) {
        Player attacker = event.getPlayer();
        if (!(event.getAttacked() instanceof Player victim) || !event.willAttack()) return;
        if (!manager.isMinemenClubKnockback(attacker)
                && !manager.isMinemenClubKnockback(victim)) return;
        attacks.put(attacker.getUniqueId(), new AttackState(attacker, victim.getUniqueId(),
                attacker.getVelocity().clone(), attacker.isSprinting(), Bukkit.getCurrentTick()));
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void calculate(EntityDamageByEntityEvent event) {
        if (!(event.getEntity() instanceof Player victim) || !eligible(victim)) return;
        if (victim.hasMetadata("alley_weapon_no_kb")) return;
        if (event.getCause() == EntityDamageEvent.DamageCause.ENTITY_SWEEP_ATTACK) return;

        Entity source = event.getDamager();
        // The legacy pearl adapter probes damage once, then explicitly calls applyPearl.
        if (source instanceof EnderPearl) return;
        boolean rod = victim.getScoreboardTags().contains("alley_legacy_rod_kb");
        LivingEntity attacker;
        if (source instanceof Projectile projectile) {
            if (!(projectile instanceof AbstractArrow || projectile instanceof Snowball
                    || projectile instanceof Egg)
                    || !(projectile.getShooter() instanceof LivingEntity shooter)) return;
            attacker = shooter;
        } else if (source instanceof LivingEntity living
                && event.getCause() == EntityDamageEvent.DamageCause.ENTITY_ATTACK) {
            attacker = living;
        } else {
            return;
        }
        if (attacker.equals(victim) || attacker.getWorld() != victim.getWorld()) return;
        boolean melee = source instanceof Player && !rod;
        queue(victim, attacker, source, melee, source instanceof AbstractArrow arrow ? arrow : null,
                event.getDamage() == 0.0D);
    }

    public void applyPearl(Player attacker, Player victim) {
        if (!eligible(victim) || attacker.getWorld() != victim.getWorld()) return;
        queue(victim, attacker, attacker, false, null, false);
    }

    private boolean eligible(Player victim) {
        return manager.isMinemenClubKnockback(victim)
                && !victim.isDead() && victim.getGameMode() != GameMode.SPECTATOR
                && manager.getAppliedProfile(victim) != null;
    }

    private void queue(Player victim, LivingEntity attacker, Entity source,
                       boolean melee, AbstractArrow arrow, boolean zeroDamage) {
        KnockbackProfile profile = manager.getAppliedProfile(victim);
        MinemenClubSettings settings = profile.getMinemenClub();
        Location from = attacker.getLocation();
        Location to = victim.getLocation();
        Vector before = victim.getVelocity().clone();
        Vector motion = before.clone();
        AttributeInstance resistance = victim.getAttribute(Attribute.KNOCKBACK_RESISTANCE);
        boolean supplement = manager.wasInsideHurtResistanceWindow(victim);
        boolean resisted = !supplement && resistance != null
                && MinemenClubMotion.resistsBase(resistance.getValue(),
                ThreadLocalRandom.current().nextDouble());
        if (!supplement && !resisted) {
            double dx = to.getX() - from.getX();
            double dz = to.getZ() - from.getZ();
            while (dx * dx + dz * dz < 1.0E-4D) {
                dx = (ThreadLocalRandom.current().nextDouble()
                        - ThreadLocalRandom.current().nextDouble()) * 0.01D;
                dz = (ThreadLocalRandom.current().nextDouble()
                        - ThreadLocalRandom.current().nextDouble()) * 0.01D;
            }
            motion = MinemenClubMotion.base(before, dx, dz, from.distance(to), settings);
        }
        AttackState attack = null;
        if (melee && attacker instanceof Player player) {
            KnockbackProfile attackerProfile = manager.getAppliedProfile(player);
            MinemenClubSettings extraSettings = attackerProfile != null
                    && attackerProfile.getBranch() == KnockbackBranch.MINEMENCLUB
                    ? attackerProfile.getMinemenClub() : settings;
            MinemenClubMotion.addMelee(motion, from.getYaw(), extraSettings);
            AttackState candidate = attacks.get(player.getUniqueId());
            if (candidate != null && candidate.tick() == Bukkit.getCurrentTick()
                    && candidate.target().equals(victim.getUniqueId())) {
                if (zeroDamage) {
                    // Boxing/Sumo accepts the Bukkit hit with zero damage. NMS returns
                    // before causeExtraKnockback, so no attacker slowdown needs undoing.
                    attacks.remove(player.getUniqueId(), candidate);
                } else {
                    attack = candidate;
                }
            }
        }
        if (arrow != null) {
            int punch = Math.max(LegacyProjectileData.getPunchLevel(arrow), arrow.getKnockbackStrength());
            MinemenClubMotion.addPunch(motion, arrow.getVelocity(), punch);
        }
        PendingHit hit = new PendingHit(victim, source.getUniqueId(), attacker.getUniqueId(),
                before, motion, melee, attack, Bukkit.getCurrentTick());
        pending.put(victim.getUniqueId(), hit);
        botVelocities.remove(victim.getUniqueId());
        // CraftEntity#setVelocity only writes motion and hurtMarked. The native attack
        // sends the single final packet, even with no sprint/enchant or no base KB.
        victim.setVelocity(motion.clone());
        manager.getPlayerData(victim).setLastDamageTick(System.currentTimeMillis());
        manager.applyHitDelayWindow(victim);
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void replaceNativeImpulse(EntityKnockbackEvent event) {
        if (!(event.getEntity() instanceof Player victim)) return;
        PendingHit hit = pending.get(victim.getUniqueId());
        if (hit == null || hit.tick() != Bukkit.getCurrentTick()) return;
        // 1.21.11 arrows apply Punch through Entity#push, whose cause is PUSH.
        boolean arrowPush = event.getCause() == EntityKnockbackEvent.Cause.PUSH
                && event instanceof EntityPushedByEntityAttackEvent pushed
                && pushed.getPushedBy() instanceof AbstractArrow
                && pushed.getPushedBy().getUniqueId().equals(hit.source());
        if (!arrowPush && event.getCause() != EntityKnockbackEvent.Cause.ENTITY_ATTACK
                && event.getCause() != EntityKnockbackEvent.Cause.DAMAGE) return;
        if (event instanceof EntityPushedByEntityAttackEvent byEntity) {
            UUID source = byEntity.getPushedBy().getUniqueId();
            if (!source.equals(hit.source()) && !source.equals(hit.attacker())) return;
        }
        // Cancels both native base and later sprint/enchantment/Punch impulses.
        // The calculated motion is already on the entity; cancellation preserves it.
        event.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void applyVelocity(PlayerVelocityEvent event) {
        Player victim = event.getPlayer();
        PlayerKnockbackData data = manager.getPlayerData(victim);
        if (!manager.isMinemenClubKnockback(victim)) return;
        if (data.isSuppressLegacyPearlVelocity()) {
            event.setCancelled(true);
            return;
        }
        PendingHit hit = pending.remove(victim.getUniqueId());
        if (hit == null) return;
        if (!current(hit)) {
            discard(hit);
            return;
        }
        delivering.put(event, hit);
        event.setVelocity(hit.motion().clone());
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void finishVelocity(PlayerVelocityEvent event) {
        PendingHit hit = delivering.remove(event);
        if (hit == null) return;
        restoreAttacker(hit);
        Player victim = event.getPlayer();
        PlayerKnockbackData data = manager.getPlayerData(victim);
        if (event.isCancelled()) {
            bridge.setMotion(victim, hit.before());
            bridge.clearVelocityMarker(victim);
            botVelocities.remove(victim.getUniqueId());
            return;
        }
        manager.beginKnockbackGravity(victim, hit.before(), event.getVelocity());
        if (hit.melee()) victim.setFallDistance(0.0F);
        // NMS restores the pre-hit server motion after its melee packet. A Bot has
        // no client to apply that packet, so its movement adapter drains this once.
        if (data.isServerControlled()) {
            botVelocities.put(victim.getUniqueId(), new BotVelocity(event.getVelocity().clone(),
                    hit.attack() != null, hit.attacker(), Bukkit.getCurrentTick()));
        }
    }

    private void restoreAttacker(PendingHit hit) {
        AttackState attack = hit.attack();
        if (attack == null || !manager.isMinemenClubKnockback(attack.player())) return;
        Player player = attack.player();
        Vector current = player.getVelocity();
        bridge.setMotion(player, new Vector(attack.motion().getX(), current.getY(), attack.motion().getZ()));
        if (attack.sprinting() && !player.isSprinting()) player.setSprinting(true);
        attacks.remove(player.getUniqueId(), attack);
    }

    /** Consumed by the existing Bot adapter; no second algorithm or delayed re-hit. */
    public Vector consumePending(Player player) {
        PendingHit hit = pending.remove(player.getUniqueId());
        if (hit == null) return null;
        if (!current(hit)) {
            discard(hit);
            return null;
        }
        restoreAttacker(hit);
        manager.beginKnockbackGravity(player, hit.before(), hit.motion());
        if (hit.melee()) player.setFallDistance(0.0F);
        return hit.motion().clone();
    }

    /** Only native player melee restores the old server motion after its velocity event. */
    public Vector deliverBotVelocity(Player player) {
        Vector queued = consumePending(player);
        BotVelocity captured = botVelocities.remove(player.getUniqueId());
        if (queued != null) return queued; // Already applied by queue(), before the Bot moves.
        if (captured == null || Bukkit.getCurrentTick() - captured.tick() > 1) return null;
        if (captured.needsApplication()) player.setVelocity(captured.motion().clone());
        // Projectile motion is already live. Re-applying it here would extend KB by a tick.
        return captured.motion().clone();
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onTeleport(PlayerTeleportEvent event) {
        clearPlayer(event.getPlayer().getUniqueId());
    }

    public void tick() {
        int tick = Bukkit.getCurrentTick();
        // Connected melee is delivered within Player#attack. Projectiles may reach
        // the tracker in the following tick. Never replay an expired melee hit.
        pending.values().removeIf(hit -> {
            if (hit.tick() >= tick - (hit.attack() == null ? 1 : 0)) return false;
            discard(hit);
            return true;
        });
        botVelocities.values().removeIf(velocity -> velocity.tick() < tick - 1);
        attacks.values().removeIf(attack -> attack.tick() < tick);
    }

    private boolean current(PendingHit hit) {
        int age = Bukkit.getCurrentTick() - hit.tick();
        return age >= 0 && age <= (hit.attack() == null ? 1 : 0)
                && manager.isMinemenClubKnockback(hit.victim());
    }

    public void clearPlayer(UUID player) {
        manager.clearMinemenClubCombatState(player);
        pending.values().removeIf(hit -> {
            if (!hit.victim().getUniqueId().equals(player) && !hit.attacker().equals(player)) return false;
            discard(hit);
            return true;
        });
        botVelocities.remove(player);
        botVelocities.values().removeIf(velocity -> velocity.attacker().equals(player));
        attacks.remove(player);
        attacks.values().removeIf(attack -> attack.target().equals(player));
        delivering.entrySet().removeIf(entry -> {
            PendingHit hit = entry.getValue();
            if (!hit.victim().getUniqueId().equals(player) && !hit.attacker().equals(player)) return false;
            entry.getKey().setCancelled(true);
            discard(hit);
            return true;
        });
    }

    private void discard(PendingHit hit) {
        Player victim = hit.victim();
        if (victim.getVelocity().equals(hit.motion())) bridge.setMotion(victim, hit.before());
        bridge.clearVelocityMarker(victim);
    }

    public void clear() {
        manager.clearMinemenClubCombatState();
        for (PendingHit hit : pending.values()) discard(hit);
        for (Map.Entry<PlayerVelocityEvent, PendingHit> entry : delivering.entrySet()) {
            entry.getKey().setCancelled(true);
            discard(entry.getValue());
        }
        pending.clear();
        attacks.clear();
        delivering.clear();
        botVelocities.clear();
    }

    private record BotVelocity(Vector motion, boolean needsApplication, UUID attacker, int tick) {
    }

    private record AttackState(Player player, UUID target, Vector motion, boolean sprinting, int tick) {
    }

    private record PendingHit(Player victim, UUID source, UUID attacker, Vector before, Vector motion,
                              boolean melee, AttackState attack, int tick) {
    }
}
