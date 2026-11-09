package dev.revere.alley.feature.bot.menu;

import dev.revere.alley.AlleyPlugin;
import dev.revere.alley.common.item.ItemBuilder;
import dev.revere.alley.common.text.CC;
import dev.revere.alley.core.profile.Profile;
import dev.revere.alley.core.profile.ProfileService;
import dev.revere.alley.feature.bot.BotService;
import dev.revere.alley.feature.bot.CustomBotProfile;
import dev.revere.alley.feature.bot.internal.BotServiceImpl;
import dev.revere.alley.feature.bot.listener.BotCustomInputListener;
import dev.revere.alley.feature.kit.Kit;
import dev.revere.alley.library.menu.Button;
import dev.revere.alley.library.menu.Menu;
import dev.revere.alley.library.menu.impl.BackButton;
import lombok.RequiredArgsConstructor;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.inventory.ItemStack;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.HashMap;
import java.util.Map;

@RequiredArgsConstructor
public final class CustomBotMenu extends Menu {
    private final Kit kit;

    @Override
    public String getTitle(Player player) {
        return "&6&lCustom Bot";
    }

    @Override
    public int getSize() {
        return 54;
    }

    @Override
    public Map<Integer, Button> getButtons(Player player) {
        CustomBotProfile custom = customProfile(player);
        Map<Integer, Button> buttons = new HashMap<>();
        int[] slots = {10, 11, 12, 13, 14, 15, 16, 19, 20, 21, 22, 23, 24, 25,
                28, 29, 30, 31, 32, 33, 34, 37, 38, 39, 40, 41, 42, 43};
        Setting[] settings = Setting.values();
        for (int index = 0; index < settings.length; index++) {
            buttons.put(slots[index], new SettingButton(custom, settings[index]));
        }
        buttons.put(46, new TextButton(this.kit, custom, true));
        buttons.put(48, new TextButton(this.kit, custom, false));
        buttons.put(50, new StartButton(this.kit, custom));
        buttons.put(45, new BackButton(new BotDifficultyMenu(this.kit)));
        addBorder(buttons, Material.BLACK_STAINED_GLASS_PANE, 6);
        return buttons;
    }

    private CustomBotProfile customProfile(Player player) {
        Profile profile = AlleyPlugin.getInstance().getService(ProfileService.class)
                .getProfile(player.getUniqueId());
        CustomBotProfile custom = profile.getProfileData().getCustomBotProfile();
        if (custom == null) {
            custom = new CustomBotProfile();
            profile.getProfileData().setCustomBotProfile(custom);
        }
        return custom;
    }

    @RequiredArgsConstructor
    private static final class TextButton extends Button {
        private final Kit kit;
        private final CustomBotProfile custom;
        private final boolean name;

        @Override
        public ItemStack getButtonItem(Player player) {
            return new ItemBuilder(this.name ? Material.NAME_TAG : Material.PLAYER_HEAD)
                    .name(this.name ? "&e&lBot Name" : "&b&lBot Skin")
                    .lore(CC.MENU_BAR,
                            "&7Current: &f" + (this.name ? this.custom.getName()
                                    : this.custom.getSkinName().isBlank() ? "Default" : this.custom.getSkinName()),
                            "",
                            this.name ? "&aClick and enter a name in chat."
                                    : "&aClick and enter a premium ID in chat.",
                            CC.MENU_BAR)
                    .hideMeta()
                    .build();
        }

        @Override
        public void clicked(Player player, ClickType clickType) {
            if (!clickType.isLeftClick()) return;
            BotCustomInputListener.request(player, this.kit,
                    this.name ? BotCustomInputListener.InputType.NAME
                            : BotCustomInputListener.InputType.SKIN);
            playNeutral(player);
        }
    }

    @RequiredArgsConstructor
    private static final class StartButton extends Button {
        private final Kit kit;
        private final CustomBotProfile custom;

        @Override
        public ItemStack getButtonItem(Player player) {
            return new ItemBuilder(Material.LIME_DYE)
                    .name("&a&lFight " + this.custom.getName())
                    .lore(CC.MENU_BAR,
                            "&7Start this Bot Duel with",
                            "&7your saved custom settings.",
                            "",
                            "&aClick to fight.",
                            CC.MENU_BAR)
                    .hideMeta()
                    .build();
        }

        @Override
        public void clicked(Player player, ClickType clickType) {
            if (!clickType.isLeftClick()) return;
            if (AlleyPlugin.getInstance().getService(BotService.class)
                    .startMatch(player, this.kit, "custom")) {
                player.closeInventory();
                playSuccess(player);
            } else {
                playFail(player);
            }
        }
    }

    @RequiredArgsConstructor
    private static final class SettingButton extends Button {
        private final CustomBotProfile custom;
        private final Setting setting;

        @Override
        public ItemStack getButtonItem(Player player) {
            return new ItemBuilder(this.setting.icon)
                    .name("&6&l" + this.setting.displayName)
                    .lore(CC.MENU_BAR,
                            "&7Current: &f" + this.setting.display(this.custom),
                            "",
                            this.setting.toggle ? "&aLeft or right click to toggle."
                                    : "&aLeft click: increase",
                            this.setting.toggle ? "" : "&cRight click: decrease",
                            CC.MENU_BAR)
                    .glow(this.setting.toggle && this.setting.value(this.custom) > 0.0D)
                    .hideMeta()
                    .build();
        }

        @Override
        public void clicked(Player player, ClickType clickType) {
            if (!clickType.isLeftClick() && !clickType.isRightClick()) return;
            this.setting.change(this.custom, clickType.isLeftClick());
            Profile profile = AlleyPlugin.getInstance().getService(ProfileService.class)
                    .getProfile(player.getUniqueId());
            BotService service = AlleyPlugin.getInstance().getService(BotService.class);
            ((BotServiceImpl) service).queueCustomProfileSave(profile);
            playNeutral(player);
        }
    }

    private enum Setting {
        CPS(Material.IRON_SWORD, "CPS", 0.5D, 1.0D, 20.0D, false),
        MAX_REACH(Material.DIAMOND_SWORD, "Maximum Reach", 0.1D, 1.0D, 6.0D, false),
        SWING_RANGE(Material.GOLDEN_SWORD, "Swing Range", 0.1D, 1.0D, 8.0D, false),
        MIN_REACH(Material.WOODEN_SWORD, "Minimum Reach", 0.1D, 0.0D, 5.0D, false),
        MOVEMENT_SPEED(Material.FEATHER, "Movement Speed", 0.05D, 0.1D, 2.0D, false),
        AIM_SPEED(Material.COMPASS, "Aim Speed", 5.0D, 1.0D, 180.0D, false),
        AIM_ERROR(Material.SPYGLASS, "Aim Error", 0.02D, 0.0D, 1.5D, false),
        PING(Material.CLOCK, "Simulated Ping", 10.0D, 0.0D, 500.0D, false),
        HEAL_HEALTH(Material.GOLDEN_APPLE, "Heal Health", 1.0D, 0.0D, 20.0D, false),
        LAVA_TICKS(Material.LAVA_BUCKET, "Lava Ticks", 1.0D, 1.0D, 100.0D, false),
        TRYHARD(Material.NETHERITE_SWORD, "Tryhard", 1.0D, 0.0D, 1.0D, true),
        W_TAP(Material.DIAMOND_BOOTS, "W-Tap", 1.0D, 0.0D, 1.0D, true),
        STRAFE(Material.LEATHER_BOOTS, "Strafe", 1.0D, 0.0D, 1.0D, true),
        BOW(Material.BOW, "Bow", 1.0D, 0.0D, 1.0D, true),
        ROD(Material.FISHING_ROD, "Fishing Rod", 1.0D, 0.0D, 1.0D, true),
        LAVA(Material.MAGMA_CREAM, "Lava", 1.0D, 0.0D, 1.0D, true),
        ANTI_FIRE(Material.WATER_BUCKET, "Anti-Fire", 1.0D, 0.0D, 1.0D, true),
        W_TAP_RATE(Material.REDSTONE, "W-Tap Rate", 0.05D, 0.0D, 1.0D, false),
        W_TAP_REACTION_TIME(Material.REPEATER, "W-Tap Reaction Time", 10.0D, 0.0D, 1000.0D, false),
        BLOCK_HIT(Material.SHIELD, "BlockHit", 1.0D, 0.0D, 1.0D, true),
        CLICK_JITTER(Material.LEVER, "Click Jitter", 0.05D, 0.0D, 0.5D, false),
        MISCLICK_CHANCE(Material.FLINT, "Misclick Chance", 0.01D, 0.0D, 0.25D, false),
        AIM_SMOOTHING(Material.ENDER_EYE, "Aim Smoothing", 0.05D, 0.0D, 0.95D, false),
        SWITCH_DELAY(Material.HOPPER, "Switch Delay", 1.0D, 0.0D, 10.0D, false),
        CRIT_CHANCE(Material.DIAMOND_AXE, "Crit Chance", 0.05D, 0.0D, 1.0D, false),
        BLOCKHIT_RATE(Material.IRON_DOOR, "BlockHit Rate", 0.05D, 0.0D, 1.0D, false);

        private final Material icon;
        private final String displayName;
        private final double step;
        private final double minimum;
        private final double maximum;
        private final boolean toggle;

        Setting(Material icon, String displayName, double step,
                double minimum, double maximum, boolean toggle) {
            this.icon = icon;
            this.displayName = displayName;
            this.step = step;
            this.minimum = minimum;
            this.maximum = maximum;
            this.toggle = toggle;
        }

        private String display(CustomBotProfile custom) {
            if (this.toggle) return value(custom) > 0.0D ? "Enabled" : "Disabled";
            if (this == W_TAP_RATE || this == CLICK_JITTER || this == MISCLICK_CHANCE
                    || this == AIM_SMOOTHING || this == CRIT_CHANCE || this == BLOCKHIT_RATE) {
                return Math.round(value(custom) * 100.0D) + "%";
            }
            if (this == W_TAP_REACTION_TIME || this == PING) {
                return (int) value(custom) + " ms";
            }
            if (this == SWITCH_DELAY) return (int) value(custom) + " ticks";
            return BigDecimal.valueOf(value(custom)).setScale(2, RoundingMode.HALF_UP)
                    .stripTrailingZeros().toPlainString();
        }

        private void change(CustomBotProfile custom, boolean increase) {
            if (this.toggle) {
                set(custom, value(custom) > 0.0D ? 0.0D : 1.0D);
                return;
            }
            double changed = value(custom) + (increase ? this.step : -this.step);
            changed = Math.max(this.minimum, Math.min(this.maximum, changed));
            set(custom, BigDecimal.valueOf(changed).setScale(2, RoundingMode.HALF_UP).doubleValue());
        }

        private double value(CustomBotProfile custom) {
            return switch (this) {
                case CPS -> custom.getCps();
                case MAX_REACH -> custom.getMaxReach();
                case SWING_RANGE -> custom.getSwingRange();
                case MIN_REACH -> custom.getMinReach();
                case MOVEMENT_SPEED -> custom.getMovementSpeed();
                case AIM_SPEED -> custom.getAimSpeed();
                case AIM_ERROR -> custom.getAimError();
                case PING -> custom.getPing();
                case HEAL_HEALTH -> custom.getHealHealth();
                case LAVA_TICKS -> custom.getLavaTicks();
                case TRYHARD -> custom.isTryhard() ? 1.0D : 0.0D;
                case W_TAP -> custom.isWTap() ? 1.0D : 0.0D;
                case STRAFE -> custom.isStrafe() ? 1.0D : 0.0D;
                case BOW -> custom.isBow() ? 1.0D : 0.0D;
                case ROD -> custom.isRod() ? 1.0D : 0.0D;
                case LAVA -> custom.isLava() ? 1.0D : 0.0D;
                case ANTI_FIRE -> custom.isAntiFire() ? 1.0D : 0.0D;
                case W_TAP_RATE -> custom.getWTapRate();
                case W_TAP_REACTION_TIME -> custom.getWTapReactionTimeMs();
                case BLOCK_HIT -> custom.isBlockHit() ? 1.0D : 0.0D;
                case CLICK_JITTER -> custom.getClickJitter();
                case MISCLICK_CHANCE -> custom.getMisclickChance();
                case AIM_SMOOTHING -> custom.getAimSmoothing();
                case SWITCH_DELAY -> custom.getSwitchDelayTicks();
                case CRIT_CHANCE -> custom.getCritChance();
                case BLOCKHIT_RATE -> custom.getBlockHitRate();
            };
        }

        private void set(CustomBotProfile custom, double value) {
            switch (this) {
                case CPS -> custom.setCps(value);
                case MAX_REACH -> custom.setMaxReach(value);
                case SWING_RANGE -> custom.setSwingRange(value);
                case MIN_REACH -> custom.setMinReach(value);
                case MOVEMENT_SPEED -> custom.setMovementSpeed(value);
                case AIM_SPEED -> custom.setAimSpeed(value);
                case AIM_ERROR -> custom.setAimError(value);
                case PING -> custom.setPing((int) value);
                case HEAL_HEALTH -> custom.setHealHealth(value);
                case LAVA_TICKS -> custom.setLavaTicks((int) value);
                case TRYHARD -> custom.setTryhard(value > 0.0D);
                case W_TAP -> custom.setWTap(value > 0.0D);
                case STRAFE -> custom.setStrafe(value > 0.0D);
                case BOW -> custom.setBow(value > 0.0D);
                case ROD -> custom.setRod(value > 0.0D);
                case LAVA -> custom.setLava(value > 0.0D);
                case ANTI_FIRE -> custom.setAntiFire(value > 0.0D);
                case W_TAP_RATE -> custom.setWTapRate(value);
                case W_TAP_REACTION_TIME -> custom.setWTapReactionTimeMs((int) value);
                case BLOCK_HIT -> custom.setBlockHit(value > 0.0D);
                case CLICK_JITTER -> custom.setClickJitter(value);
                case MISCLICK_CHANCE -> custom.setMisclickChance(value);
                case AIM_SMOOTHING -> custom.setAimSmoothing(value);
                case SWITCH_DELAY -> custom.setSwitchDelayTicks((int) value);
                case CRIT_CHANCE -> custom.setCritChance(value);
                case BLOCKHIT_RATE -> custom.setBlockHitRate(value);
            }
        }
    }
}
