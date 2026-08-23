package dev.revere.alley.feature.item.listener;

import dev.revere.alley.AlleyPlugin;
import dev.revere.alley.common.text.CC;
import dev.revere.alley.core.locale.LocaleService;
import dev.revere.alley.core.locale.internal.impl.message.GlobalMessagesLocaleImpl;
import dev.revere.alley.core.profile.Profile;
import dev.revere.alley.core.profile.ProfileService;
import dev.revere.alley.feature.cooldown.Cooldown;
import dev.revere.alley.feature.cooldown.CooldownService;
import dev.revere.alley.feature.cooldown.CooldownType;
import dev.revere.alley.feature.item.ItemService;
import dev.revere.alley.feature.item.CustomItemType;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.ItemStack;

import java.util.Optional;

/**
 * @author Emmy
 * 作者 Emmy
 * @project alley-practice
 * 项目 alley-practice
 * @since 18/07/2025
 * 自 18/07/2025
 */
public class ItemListener implements Listener {

    @EventHandler(priority = EventPriority.HIGHEST)
    private void onInteract(PlayerInteractEvent event) {
        Player player = event.getPlayer();
        Profile profile = AlleyPlugin.getInstance().getService(ProfileService.class).getProfile(player.getUniqueId());
        if (profile.getMatch() == null && profile.getFfaMatch() == null) {
            return;
        }

        ItemStack item = event.getItem();
        if (item == null || item.getType() == Material.AIR) {
            return;
        }

        Action action = event.getAction();
        if (action != Action.RIGHT_CLICK_AIR && action != Action.RIGHT_CLICK_BLOCK) {
            return;
        }

        ItemService itemService = AlleyPlugin.getInstance().getService(ItemService.class);
        ItemStack goldenHead = itemService.getGoldenHead();
        if (item.getType() == Material.PLAYER_HEAD && item.hasItemMeta()
                && item.getItemMeta().hasDisplayName()
                && item.getItemMeta().getDisplayName().equals(goldenHead.getItemMeta().getDisplayName())) {
            event.setCancelled(true);
            event.setUseItemInHand(org.bukkit.event.Event.Result.DENY);

            if (this.isOnHeadCooldown(player)) return;

            // Remove from player's hand directly
            ItemStack hand = player.getInventory().getItemInMainHand();
            if (hand.getAmount() > 1) {
                hand.setAmount(hand.getAmount() - 1);
                player.getInventory().setItemInMainHand(hand);
            } else {
                player.getInventory().setItemInMainHand(null);
            }
            itemService.performHeadConsume(player, item);
            return;
        }

        if (itemService.getCustomItemType(item) == CustomItemType.SPECIAL_GOLDEN_APPLE) {
            event.setCancelled(true);
            event.setUseItemInHand(org.bukkit.event.Event.Result.DENY);
            itemService.performSpecialGoldenAppleConsume(player, item);
        }
    }

    /**
     * Checks if the player is on cooldown for consuming a golden head.
     * 检查玩家是否处于金头消耗的冷却状态。
     *
     * @param player The player to check the cooldown for.
     * 要检查冷却状态的玩家。
     * @return true if the player is on cooldown, false otherwise.
     * 如果玩家处于冷却状态则返回 true，否则返回 false。
     */
    private boolean isOnHeadCooldown(Player player) {
        CooldownService cooldownService = AlleyPlugin.getInstance().getService(CooldownService.class);
        LocaleService localeService = AlleyPlugin.getInstance().getService(LocaleService.class);

        CooldownType cooldownType = CooldownType.GOLDEN_HEAD_CONSUME;
        Optional<Cooldown> optionalCooldown = Optional.ofNullable(cooldownService.getCooldown(player.getUniqueId(), cooldownType));
        if (optionalCooldown.isPresent() && optionalCooldown.get().isActive()) {
            player.sendMessage(CC.translate("&cYou must wait before eating another Golden Head."));
            return true;
        }

        Cooldown cooldown = optionalCooldown.orElseGet(() -> {
            Cooldown newCooldown = new Cooldown(cooldownType, () -> {
            });
            cooldownService.addCooldown(player.getUniqueId(), cooldownType, newCooldown);
            return newCooldown;
        });

        cooldown.resetCooldown();
        return false;
    }
}
