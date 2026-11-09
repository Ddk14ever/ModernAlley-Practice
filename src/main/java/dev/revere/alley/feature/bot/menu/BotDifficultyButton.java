package dev.revere.alley.feature.bot.menu;

import dev.revere.alley.AlleyPlugin;
import dev.revere.alley.common.item.ItemBuilder;
import dev.revere.alley.common.text.CC;
import dev.revere.alley.feature.bot.BotDifficultyProfile;
import dev.revere.alley.feature.bot.BotService;
import dev.revere.alley.feature.kit.Kit;
import dev.revere.alley.library.menu.Button;
import lombok.RequiredArgsConstructor;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.inventory.ItemStack;

@RequiredArgsConstructor
public class BotDifficultyButton extends Button {
    private final Kit kit;
    private final BotDifficultyProfile profile;

    @Override
    public ItemStack getButtonItem(Player player) {
        return new ItemBuilder(profile.getIcon())
                .name(profile.getDisplayName())
                .lore(
                        CC.MENU_BAR,
                        "&7AI Mode: &f" + kit.getBotAiMode().name(),
                        "&7CPS: &f" + profile.getCps(),
                        "&7Attack Reach: &f" + profile.getMinReach() + " - " + profile.getMaxReach(),
                        "&7Combat Distance: &f" + profile.getCombatDistance(),
                        "&7Movement Speed: &f" + profile.getMovementSpeed(),
                        "&7Ping / Latency: &f" + profile.getPing() + "ms / "
                                + profile.getLatencyTicks() + "t",
                        "&7Click Jitter / Misclick: &f" + percent(profile.getClickJitter())
                                + " / " + percent(profile.getMisclickChance()),
                        "&7Aim Smoothing / Switch: &f" + percent(profile.getAimSmoothing())
                                + " / " + profile.getSwitchDelayTicks() + "t",
                        "&7Crit / BlockHit Rate: &f" + percent(profile.getCritChance())
                                + " / " + percent(profile.getBlockHitRate()),
                        "&7Aim Error: &f" + profile.getAimError(),
                        "&7W-Tap: " + (profile.isWTap() ? "&aYes" : "&cNo"),
                        "&7Strafe: " + (profile.isStrafe() ? "&aYes" : "&cNo"),
                        "&7Bow / Rod / Lava: &f" + yesNo(profile.isBow()) + " / "
                                + yesNo(profile.isRod()) + " / " + yesNo(profile.isLava()),
                        "&7Heal At: &f" + profile.getHealHealth() + " HP",
                        "",
                        "&aClick to fight.",
                        CC.MENU_BAR
                )
                .hideMeta()
                .build();
    }

    @Override
    public void clicked(Player player, ClickType clickType) {
        if (clickType != ClickType.LEFT) return;
        if (AlleyPlugin.getInstance().getService(BotService.class).startMatch(player, kit, profile.getId())) {
            player.closeInventory();
            this.playSuccess(player);
        } else {
            this.playFail(player);
        }
    }

    private String yesNo(boolean enabled) {
        return enabled ? "Yes" : "No";
    }

    private String percent(double value) {
        return Math.round(value * 100.0D) + "%";
    }
}
