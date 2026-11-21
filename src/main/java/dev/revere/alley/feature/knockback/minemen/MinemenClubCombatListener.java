package dev.revere.alley.feature.knockback.minemen;

import dev.revere.alley.AlleyPlugin;
import dev.revere.alley.core.profile.Profile;
import dev.revere.alley.core.profile.ProfileService;
import dev.revere.alley.feature.knockback.KnockbackManager;
import dev.revere.alley.feature.match.MatchService;
import dev.revere.alley.feature.match.combat.legacy.LegacyCombatService;
import dev.revere.alley.feature.match.internal.MatchServiceImpl;
import io.papermc.paper.event.player.PrePlayerAttackEntityEvent;
import org.bukkit.GameMode;
import org.bukkit.Sound;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.entity.Projectile;
import org.bukkit.entity.ThrownExpBottle;
import org.bukkit.entity.ThrownPotion;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent;

import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.function.Supplier;

/** Club damage acceptance and the end-of-attack sword-block transition. */
public final class MinemenClubCombatListener implements Listener {
    private final KnockbackManager manager;
    private final Supplier<LegacyCombatService> legacy;
    private final Map<UUID, MinemenClubDamageWindow> windows = new HashMap<>();
    private final Map<EntityDamageEvent, Attempt> attempts = new IdentityHashMap<>();

    public MinemenClubCombatListener(KnockbackManager manager) {
        this(manager, () -> {
            MatchService matches = AlleyPlugin.getInstance().getService(MatchService.class);
            return matches instanceof MatchServiceImpl service ? service.getLegacyCombatService() : null;
        });
    }

    MinemenClubCombatListener(KnockbackManager manager, Supplier<LegacyCombatService> legacy) {
        this.manager = manager;
        this.legacy = legacy;
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void checkAttack(PrePlayerAttackEntityEvent event) {
        if (!event.willAttack() || !manager.isMinemenClubKnockback(event.getPlayer())) return;
        if (event.getAttacked() instanceof Player victim
                && !manager.canMinemenClubAttack(event.getPlayer(), victim)) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onDamage(EntityDamageEvent event) {
        prepare(event);
    }

    /** Also called by the old-armour listener so registration order cannot invert the stages. */
    public void prepare(EntityDamageEvent event) {
        if (event.isCancelled() || attempts.containsKey(event)
                || !(event.getEntity() instanceof Player victim) || !eligible(victim)) return;
        // Keep the existing void/fake-death path independent of combat acceptance.
        if (event.getCause() == EntityDamageEvent.DamageCause.VOID
                || event.getCause() == EntityDamageEvent.DamageCause.KILL) return;
        MinemenClubDamageWindow window = windows.computeIfAbsent(victim.getUniqueId(),
                ignored -> new MinemenClubDamageWindow(victim.getLastDamage()));
        boolean supplement = manager.wasInsideHurtResistanceWindow(victim);
        MinemenClubDamageWindow.Hit hit = window.evaluate(event.getDamage(), supplement);
        if (!hit.accepted()) {
            event.setCancelled(true);
            return;
        }
        LegacyCombatService legacy = legacyCombat();
        boolean sword = legacy != null && legacy.hasSwordBlockKB(victim.getUniqueId())
                && victim.getInventory().getItemInMainHand().getType().name().endsWith("_SWORD");
        boolean blocking = sword && legacy.isBlocking(victim.getUniqueId()) && blockable(event);
        MinemenClubDamageModifiers.rebuild(event, hit.damage(), sword, blocking);
        attempts.put(event, new Attempt(victim, window, hit, blocking));
    }

    private boolean blockable(EntityDamageEvent event) {
        if (!(event instanceof EntityDamageByEntityEvent byEntity)) return false;
        if (event.getCause() == EntityDamageEvent.DamageCause.ENTITY_ATTACK) return true;
        return byEntity.getDamager() instanceof Projectile projectile
                && !(projectile instanceof ThrownPotion) && !(projectile instanceof ThrownExpBottle);
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void finishDamage(EntityDamageEvent event) {
        Attempt attempt = attempts.remove(event);
        if (attempt == null || event.isCancelled() || !eligible(attempt.victim())
                || windows.get(attempt.victim().getUniqueId()) != attempt.window()) return;
        Player victim = attempt.victim();
        attempt.window().accept(attempt.hit());
        // Intentional zero-damage game modes still accept a hit. They must retain
        // the original incoming amount instead of opening the window with zero.
        victim.setLastDamage(attempt.hit().raw());
        if (!attempt.hit().supplement()) {
            victim.setNoDamageTicks(victim.getMaximumNoDamageTicks());
        }
        if (attempt.blocking()) {
            Profile profile = AlleyPlugin.getInstance().getService(ProfileService.class).getProfile(victim.getUniqueId());
            if (profile != null && profile.getProfileData().getSettingData().isSwordBlockSoundsEnabled()) {
                victim.playSound(victim.getLocation(), Sound.ITEM_SHIELD_BLOCK, 1.0F, 0.8F);
            }
        }
    }

    public void attack(Player attacker, Entity target) {
        if (manager.isMinemenClubKnockback(attacker) && target instanceof Player player
                && !manager.canMinemenClubAttack(attacker, player)) return;
        Player victim = target instanceof Player player && eligible(player) ? player : null;
        MinemenClubDamageWindow window = victim == null ? null
                : windows.computeIfAbsent(victim.getUniqueId(),
                        ignored -> new MinemenClubDamageWindow(victim.getLastDamage()));
        // NMS compares modern weapon damage before the legacy tool listener runs.
        // Let that listener see every positive melee attempt; our window then
        // accepts/rejects the corrected amount before damage, counts or velocity.
        if (victim != null && manager.isInsideHurtResistanceWindow(victim)) victim.setLastDamage(0.0D);
        try {
            attacker.attack(target);
        } finally {
            if (victim != null && eligible(victim) && windows.get(victim.getUniqueId()) == window) {
                // hurtServer writes its post-block amount after Bukkit's event.
                // Restore Club's complete amount immediately, including rejected hits.
                victim.setLastDamage(window.lastDamage());
            }
            if (manager.isMinemenClubKnockback(attacker)) clearBlocking(attacker);
        }
    }

    public void clearBlocking(Player player) {
        if (player.hasActiveItem()) player.clearActiveItem();
        LegacyCombatService legacy = legacyCombat();
        if (legacy != null) legacy.setBlocking(player.getUniqueId(), false);
    }

    private LegacyCombatService legacyCombat() {
        return legacy.get();
    }

    private boolean eligible(Player player) {
        return manager.isMinemenClubKnockback(player) && manager.getAppliedProfile(player) != null
                && !player.isDead() && player.getGameMode() != GameMode.SPECTATOR;
    }

    public void clearPlayer(UUID player) {
        windows.remove(player);
        attempts.values().removeIf(attempt -> attempt.victim().getUniqueId().equals(player));
    }

    public void clear() {
        windows.clear();
        attempts.clear();
    }

    private record Attempt(Player victim, MinemenClubDamageWindow window,
                           MinemenClubDamageWindow.Hit hit, boolean blocking) {
    }
}
