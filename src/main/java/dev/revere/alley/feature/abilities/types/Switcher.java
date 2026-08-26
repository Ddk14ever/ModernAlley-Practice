package dev.revere.alley.feature.abilities.types;

import dev.revere.alley.AlleyPlugin;
import dev.revere.alley.feature.abilities.Ability;
import dev.revere.alley.feature.abilities.AbilityService;
import dev.revere.alley.feature.knockback.KnockbackManager;
import dev.revere.alley.common.time.DurationFormatter;
import dev.revere.alley.core.profile.ProfileService;
import dev.revere.alley.core.profile.Profile;
import dev.revere.alley.core.profile.enums.GlobalCooldown;
import dev.revere.alley.common.text.CC;
import org.bukkit.Location;
import org.bukkit.entity.Egg;
import org.bukkit.entity.Player;
import org.bukkit.entity.Projectile;
import org.bukkit.entity.Snowball;
import org.bukkit.event.EventHandler;
import org.bukkit.event.block.Action;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.ProjectileLaunchEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.metadata.FixedMetadataValue;

import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
/**
 * @author Ddk1 ClaudeCode
 * @project Alley
 * @since 26/06/2026
 */
public class Switcher extends Ability {
    private final AlleyPlugin plugin = AlleyPlugin.getInstance();
    private final Set<UUID> pendingProjectileLaunchers = ConcurrentHashMap.newKeySet();

    public Switcher() {
        super("SWITCHER");
    }

    @EventHandler
    public void onLaunch(ProjectileLaunchEvent event) {
        if (!(event.getEntity() instanceof Egg || event.getEntity() instanceof Snowball)) return;
        if (event.getEntity().getShooter() instanceof Player shooter
                && (this.pendingProjectileLaunchers.remove(shooter.getUniqueId())
                || isAbility(shooter.getInventory().getItemInMainHand()))) {
            event.getEntity().setMetadata(this.getAbility(), new FixedMetadataValue(this.plugin, true));
        }
    }

    @EventHandler
    public void onInteract(PlayerInteractEvent event) {
        if (!isAbility(event.getItem())) return;

        if (event.getAction().equals(Action.RIGHT_CLICK_AIR) || event.getAction().equals(Action.RIGHT_CLICK_BLOCK)) {
            Player shooter = event.getPlayer();

            ProfileService profileService = AlleyPlugin.getInstance().getService(ProfileService.class);
            AbilityService abilityService = AlleyPlugin.getInstance().getService(AbilityService.class);

            Profile profile = profileService.getProfile(shooter.getUniqueId());

            if (profile.getCooldown(Switcher.class).onCooldown(shooter)) {
                shooter.sendMessage(CC.translate("&fYou are on &6&lSwitcher &7cooldown for &4" + DurationFormatter.getRemaining(profile.getCooldown(Switcher.class).getRemainingMillis(shooter), true, true)));
                shooter.updateInventory();
                event.setCancelled(true);
                return;
            }

            if(profile.getGlobalCooldown(GlobalCooldown.PARTNER_ITEM).onCooldown(shooter)){
                shooter.sendMessage(CC.translate("&fYou are on &6&lPartner Item &fcooldown for &6" + DurationFormatter.getRemaining(profile.getGlobalCooldown(GlobalCooldown.PARTNER_ITEM).getRemainingMillis(shooter), true, true)));
                shooter.updateInventory();
                event.setCancelled(true);
                return;
            }

            UUID shooterId = shooter.getUniqueId();
            this.pendingProjectileLaunchers.add(shooterId);
            this.plugin.getServer().getScheduler().runTaskLater(this.plugin,
                    () -> this.pendingProjectileLaunchers.remove(shooterId), 1L);

            profile.getCooldown(Switcher.class).applyCooldown(shooter, 8 * 1000);
            profile.getGlobalCooldown(GlobalCooldown.PARTNER_ITEM).applyCooldown(shooter,  10 * 1000);

            abilityService.sendCooldownExpiredMessage(shooter, this.getName(), this.getAbility());
            abilityService.sendPlayerMessage(shooter, this.getAbility());
        }
    }

    @EventHandler
    public void checkCooldown(PlayerInteractEvent event) {
        Player player = event.getPlayer();
        Action action = event.getAction();
        Profile profile = AlleyPlugin.getInstance().getService(ProfileService.class).getProfile(player.getUniqueId());
        if (action.equals(Action.LEFT_CLICK_AIR) || action.equals(Action.LEFT_CLICK_BLOCK)) {
            if (!isAbility(player.getInventory().getItemInMainHand())) {
                return;
            }
            if (isAbility(player.getInventory().getItemInMainHand())) {
                if (this.hasCooldown(player)) {
                    player.sendMessage(CC.translate("&fYou are on cooldown for &4" + DurationFormatter.getRemaining(profile.getCooldown(Switcher.class).getRemainingMillis(player), true)));
                    event.setCancelled(true);
                    player.updateInventory();
                }
            }
        }
    }

    @EventHandler(priority = org.bukkit.event.EventPriority.HIGHEST, ignoreCancelled = false)
    public void onDamage(EntityDamageByEntityEvent event) {
        if (event.getEntity() instanceof Player victim
                && AlleyPlugin.getInstance().getService(KnockbackManager.class)
                .wasInsideHurtResistanceWindow(victim)) return;

        if (!(event.getEntity() instanceof Player victim)
                || !(event.getDamager() instanceof Projectile projectile)
                || !projectile.hasMetadata(this.getAbility())
                || !(projectile.getShooter() instanceof Player shooter)
                || shooter.getUniqueId().equals(victim.getUniqueId())) {
            return;
        }

        projectile.removeMetadata(this.getAbility(), this.plugin);
        Location victimLocation = victim.getLocation().clone();
        Location shooterLocation = shooter.getLocation().clone();

        if (!victim.teleport(shooterLocation)) return;
        if (!shooter.teleport(victimLocation)) {
            victim.teleport(victimLocation);
            return;
        }

        AlleyPlugin.getInstance().getService(AbilityService.class)
                .sendTargetMessage(victim, shooter, this.getAbility());
    }
}
