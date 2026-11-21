package dev.revere.alley.feature.match.snapshot.listener;

import dev.revere.alley.AlleyPlugin;
import dev.revere.alley.feature.match.Match;
import dev.revere.alley.feature.match.MatchState;
import dev.revere.alley.feature.knockback.KnockbackManager;
import dev.revere.alley.feature.match.model.internal.MatchGamePlayer;
import dev.revere.alley.core.profile.ProfileService;
import dev.revere.alley.core.profile.Profile;
import dev.revere.alley.core.profile.enums.ProfileState;
import org.bukkit.entity.Player;
import org.bukkit.entity.ThrownPotion;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.PotionSplashEvent;
import org.bukkit.event.entity.ProjectileLaunchEvent;
import org.bukkit.potion.PotionEffectType;

/**
 * @author Emmy
 * @project alley-practice
 * @since 01/07/2025
 */
public class SnapshotListener implements Listener {
    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    private void onPotionSplashEvent(PotionSplashEvent event) {
        if (!(event.getPotion().getShooter() instanceof Player)) {
            return;
        }

        Player shooter = (Player) event.getPotion().getShooter();
        Profile profile = AlleyPlugin.getInstance().getService(ProfileService.class).getProfile(shooter.getUniqueId());

        ProfileState profileState = profile.getState();
        if (profileState != ProfileState.PLAYING) {
            return;
        }

        Match match = profile.getMatch();
        if (match == null || match.getState() != MatchState.RUNNING) {
            return;
        }

        MatchGamePlayer gamePlayer = match.getGamePlayer(shooter);

        if (event.getIntensity(shooter) <= 0.5D) {
            gamePlayer.getData().incrementMissedPotions();
        }
    }

    @EventHandler
    private void onProjectileLaunch(ProjectileLaunchEvent event) {
        if (!(event.getEntity().getShooter() instanceof Player) || !(event.getEntity() instanceof ThrownPotion)) {
            return;
        }

        Player shooter = (Player) event.getEntity().getShooter();
        Profile profile = AlleyPlugin.getInstance().getService(ProfileService.class).getProfile(shooter.getUniqueId());

        if (profile.getState() != ProfileState.PLAYING) {
            return;
        }

        Match match = profile.getMatch();
        if (match == null || match.getState() != MatchState.RUNNING) {
            return;
        }

        MatchGamePlayer gamePlayer = match.getGamePlayer(shooter);
        gamePlayer.getData().incrementThrownPotions();
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    private void onEntityDamageByEntity(EntityDamageByEntityEvent event) {
        if (!(event.getDamager() instanceof Player) || !(event.getEntity() instanceof Player)) {
            return;
        }
        if (event.getCause() != EntityDamageEvent.DamageCause.ENTITY_ATTACK) return;

        Player attacker = (Player) event.getDamager();
        Player defender = (Player) event.getEntity();
        if (AlleyPlugin.getInstance().getService(KnockbackManager.class)
                .wasInsideHurtResistanceWindow(defender)) return;

        Profile attackerProfile = AlleyPlugin.getInstance().getService(ProfileService.class).getProfile(attacker.getUniqueId());
        Profile defenderProfile = AlleyPlugin.getInstance().getService(ProfileService.class).getProfile(defender.getUniqueId());

        if (attackerProfile.getState() != ProfileState.PLAYING ||
                defenderProfile.getState() != ProfileState.PLAYING) {
            return;
        }

        Match match = attackerProfile.getMatch();
        if (match == null || match != defenderProfile.getMatch() || match.getState() != MatchState.RUNNING) {
            return;
        }

        MatchGamePlayer attackerGamePlayer = match.getGamePlayer(attacker);
        MatchGamePlayer defenderGamePlayer = match.getGamePlayer(defender);

        if (isCriticalHit(attacker, match)) {
            attackerGamePlayer.getData().incrementCriticalHits();
        }

        boolean effectiveBlock = event.isApplicable(EntityDamageEvent.DamageModifier.BLOCKING)
                && event.getDamage(EntityDamageEvent.DamageModifier.BLOCKING) < 0.0;
        if (effectiveBlock) {
            defenderGamePlayer.getData().incrementBlockedHits();
        }
    }

    private boolean isCriticalHit(Player attacker, Match match) {
        boolean fallingCritical = attacker.getFallDistance() > 0.0f
                && !attacker.isOnGround()
                && !attacker.isClimbing()
                && !attacker.isInWater()
                && !attacker.isGliding()
                && !attacker.hasPotionEffect(PotionEffectType.BLINDNESS)
                && attacker.getVehicle() == null;
        if (!fallingCritical) return false;

        if (AlleyPlugin.getInstance().getService(KnockbackManager.class).usesLegacyCombat(match.getKit())) return true;
        return !attacker.isSprinting() && attacker.getAttackCooldown() > 0.9f;
    }
}
