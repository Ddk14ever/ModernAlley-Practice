package dev.revere.alley.feature.kit.menu;

import dev.revere.alley.AlleyPlugin;
import dev.revere.alley.common.item.ItemBuilder;
import dev.revere.alley.common.text.CC;
import dev.revere.alley.feature.kit.Kit;
import dev.revere.alley.feature.kit.KitService;
import dev.revere.alley.feature.luckyblock.LuckyBlockRewardType;
import dev.revere.alley.library.menu.Button;
import dev.revere.alley.library.menu.pagination.PaginatedMenu;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.inventory.ItemStack;

import java.util.HashMap;
import java.util.Map;

/** Selects a separate Kit as the reward pool for one LuckyBlock color. */
public class LuckyBlockKitSelectionMenu extends PaginatedMenu {
    private final Kit matchKit;
    private final LuckyBlockRewardType rewardType;

    public LuckyBlockKitSelectionMenu(Kit matchKit, LuckyBlockRewardType rewardType) {
        this.matchKit = matchKit;
        this.rewardType = rewardType;
    }

    @Override
    public String getPrePaginatedTitle(Player player) {
        return "&6&lSelect " + this.rewardType.getDisplayName() + " Reward Kit";
    }

    @Override
    public int getMaxItemsPerPage() {
        return 28;
    }

    @Override
    public int getSize() {
        return 9 * 6;
    }

    @Override
    public Map<Integer, Button> getGlobalButtons(Player player) {
        Map<Integer, Button> buttons = new HashMap<>();
        addGlassHeader(buttons, Material.BLACK_STAINED_GLASS_PANE);
        buttons.put(48, new ClearButton());
        buttons.put(53, new BackButton());
        return buttons;
    }

    @Override
    public Map<Integer, Button> getAllPagesButtons(Player player) {
        Map<Integer, Button> buttons = new HashMap<>();
        int index = 0;
        for (Kit candidate : AlleyPlugin.getInstance().getService(KitService.class).getKits()) {
            if (!candidate.getName().equalsIgnoreCase(this.matchKit.getName())) {
                buttons.put(index++, new KitButton(candidate));
            }
        }
        return buttons;
    }

    private String getSelectedKitName() {
        return switch (this.rewardType) {
            case GREEN -> this.matchKit.getLuckyBlockGreenKit();
            case BLUE -> this.matchKit.getLuckyBlockBlueKit();
            case YELLOW -> this.matchKit.getLuckyBlockYellowKit();
        };
    }

    private void setSelectedKitName(String kitName) {
        switch (this.rewardType) {
            case GREEN -> this.matchKit.setLuckyBlockGreenKit(kitName);
            case BLUE -> this.matchKit.setLuckyBlockBlueKit(kitName);
            case YELLOW -> this.matchKit.setLuckyBlockYellowKit(kitName);
        }
        AlleyPlugin.getInstance().getService(KitService.class).saveKit(this.matchKit);
    }

    private class KitButton extends Button {
        private final Kit candidate;

        private KitButton(Kit candidate) {
            this.candidate = candidate;
        }

        @Override
        public ItemStack getButtonItem(Player player) {
            boolean selected = this.candidate.getName().equalsIgnoreCase(getSelectedKitName());
            return new ItemBuilder(this.candidate.getIconItemOrDefault())
                    .name((selected ? "&a" : "&6") + this.candidate.getDisplayName())
                    .lore(
                            CC.MENU_BAR,
                            "&7Kit ID: &f" + this.candidate.getName(),
                            "&7Selected: " + (selected ? "&aYes" : "&cNo"),
                            "",
                            "&eClick &7to assign this reward kit.",
                            CC.MENU_BAR
                    )
                    .hideMeta()
                    .build();
        }

        @Override
        public void clicked(Player player, ClickType clickType) {
            if (clickType != ClickType.LEFT) return;
            setSelectedKitName(this.candidate.getName());
            player.sendMessage(CC.translate("&a" + rewardType.getDisplayName() + " LuckyBlock reward kit set to &6"
                    + this.candidate.getName() + "&a."));
            new LuckyBlockKitsMenu(matchKit).openMenu(player);
            this.playSuccess(player);
        }
    }

    private class ClearButton extends Button {
        @Override
        public ItemStack getButtonItem(Player player) {
            return new ItemBuilder(Material.BARRIER)
                    .name("&c&lClear Reward Kit")
                    .lore(CC.MENU_BAR, "&7This LuckyBlock color will have no drops.", CC.MENU_BAR)
                    .hideMeta()
                    .build();
        }

        @Override
        public void clicked(Player player, ClickType clickType) {
            if (clickType != ClickType.LEFT) return;
            setSelectedKitName("");
            new LuckyBlockKitsMenu(matchKit).openMenu(player);
            this.playSuccess(player);
        }
    }

    private class BackButton extends Button {
        @Override
        public ItemStack getButtonItem(Player player) {
            return new ItemBuilder(Material.ARROW)
                    .name("&e&lBack")
                    .lore(CC.MENU_BAR, "&7Return to LuckyBlock reward kits.", CC.MENU_BAR)
                    .hideMeta()
                    .build();
        }

        @Override
        public void clicked(Player player, ClickType clickType) {
            if (clickType != ClickType.LEFT) return;
            new LuckyBlockKitsMenu(matchKit).openMenu(player);
            this.playNeutral(player);
        }
    }
}
