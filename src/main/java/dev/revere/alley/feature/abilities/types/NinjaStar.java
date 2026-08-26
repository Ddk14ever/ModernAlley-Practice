package dev.revere.alley.feature.abilities.types;

import com.google.common.collect.Maps;
import dev.revere.alley.AlleyPlugin;
import dev.revere.alley.feature.abilities.Ability;
import dev.revere.alley.feature.abilities.AbilityService;
import dev.revere.alley.feature.knockback.KnockbackManager;
import dev.revere.alley.common.time.DurationFormatter;
import dev.revere.alley.core.profile.ProfileService;
import dev.revere.alley.core.profile.Profile;
import dev.revere.alley.core.profile.enums.GlobalCooldown;
import dev.revere.alley.common.PlayerUtil;
import dev.revere.alley.common.text.CC;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.block.Action;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.scheduler.BukkitRunnable;

import java.util.Map;
import java.util.UUID;
/**
 * @author Ddk1 ClaudeCode
 * @project Alley
 * @since 26/06/2026
 */
public class NinjaStar extends Ability {
    private final AlleyPlugin plugin = AlleyPlugin.getInstance();
    private final Map<UUID, UUID> TAGGED = Maps.newHashMap();

    public NinjaStar() {
        super("NINJA_STAR");
    }

    @EventHandler(ignoreCancelled = true)
    private void onDamage(EntityDamageByEntityEvent event) {
        if (event.getEntity() instanceof Player victim
                && AlleyPlugin.getInstance().getService(KnockbackManager.class)
                .wasInsideHurtResistanceWindow(victim)) return;

        if (event.getEntity() instanceof Player && event.getDamager() instanceof Player) {
            Player damager = (Player) event.getDamager();
            Player victim = (Player) event.getEntity();

            //AbilityCooldowns.addCooldown("TELEPORT", victim, 15);
            TAGGED.put(victim.getUniqueId(), damager.getUniqueId());
        }
    }

    @EventHandler
    private void onInteract(PlayerInteractEvent event) {
        if (!isAbility(event.getItem())) return;

        if (event.getAction().equals(Action.RIGHT_CLICK_AIR) || event.getAction().equals(Action.RIGHT_CLICK_BLOCK)) {
            event.setCancelled(true);

            Player player = event.getPlayer();

            ProfileService profileService = AlleyPlugin.getInstance().getService(ProfileService.class);
            AbilityService abilityService = AlleyPlugin.getInstance().getService(AbilityService.class);

            Profile profile = profileService.getProfile(player.getUniqueId());

            if (profile.getCooldown(NinjaStar.class).onCooldown(player)) {
                player.sendMessage(CC.translate("&fYou are on &6&lNinja Star &7cooldown for &4" + DurationFormatter.getRemaining(profile.getCooldown(NinjaStar.class).getRemainingMillis(player), true, true)));
                player.updateInventory();
                return;
            }

            if (profile.getGlobalCooldown(GlobalCooldown.PARTNER_ITEM).onCooldown(player)) {
                player.sendMessage(CC.translate("&fYou are on &6&lPartner Item &fcooldown for &6" + DurationFormatter.getRemaining(profile.getGlobalCooldown(GlobalCooldown.PARTNER_ITEM).getRemainingMillis(player), true, true)));
                player.updateInventory();
                return;
            }

            UUID targetId = TAGGED.get(player.getUniqueId());
            Player target = targetId == null ? null : Bukkit.getPlayer(targetId);
            if (!isTeleportable(target)) {
                TAGGED.remove(player.getUniqueId());
                player.sendMessage(CC.translate("&cYou need to be damaged by a player before using Ninja Star."));
                player.updateInventory();
                return;
            }

            PlayerUtil.decrement(player);

            profile.getCooldown(NinjaStar.class).applyCooldown(player, 60 * 1000);
            profile.getGlobalCooldown(GlobalCooldown.PARTNER_ITEM).applyCooldown(player, 10 * 1000);

            new BukkitRunnable() {
                @Override
                public void run() {
                    Player currentTarget = Bukkit.getPlayer(targetId);
                    if (!player.isOnline() || !isTeleportable(currentTarget)) {
                        return;
                    }
                    player.teleport(currentTarget.getLocation());
                    player.sendMessage(CC.translate("&7You have been successfully teleported")); // you just got teleported back
                    // 你刚刚被传送回来了
                }
            }.runTaskLater(this.plugin, 5 * 10L);

            abilityService.sendCooldownExpiredMessage(player, this.getName(), this.getAbility());
            abilityService.sendPlayerMessage(player, this.getAbility());
            abilityService.sendTargetMessage(target, player, this.getAbility());

            TAGGED.remove(player.getUniqueId());
        }
    }

    private boolean isTeleportable(Player player) {
        return player != null && !player.isDead() && (player.isOnline() || player.isValid());
    }
}
