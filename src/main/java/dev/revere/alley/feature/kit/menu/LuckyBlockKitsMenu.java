package dev.revere.alley.feature.kit.menu;

import dev.revere.alley.common.item.ItemBuilder;
import dev.revere.alley.common.text.CC;
import dev.revere.alley.feature.kit.Kit;
import dev.revere.alley.feature.luckyblock.LuckyBlockRewardType;
import dev.revere.alley.library.menu.Button;
import dev.revere.alley.library.menu.Menu;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.inventory.ItemStack;

import java.util.HashMap;
import java.util.Map;

/** Lets administrators choose the three independent LuckyBlock reward kits. */
public class LuckyBlockKitsMenu extends Menu {
    private final Kit matchKit;

    public LuckyBlockKitsMenu(Kit matchKit) {
        this.matchKit = matchKit;
        this.setPlaceholder(true);
    }

    @Override
    public String getTitle(Player player) {
        return "&6&lLuckyBlock Reward Kits";
    }

    @Override
    public int getSize() {
        return 9 * 3;
    }

    @Override
    public Map<Integer, Button> getButtons(Player player) {
        Map<Integer, Button> buttons = new HashMap<>();
        addBorder(buttons, Material.BLACK_STAINED_GLASS_PANE, 3);
        buttons.put(11, new RewardKitButton(LuckyBlockRewardType.GREEN));
        buttons.put(13, new RewardKitButton(LuckyBlockRewardType.BLUE));
        buttons.put(15, new RewardKitButton(LuckyBlockRewardType.YELLOW));
        buttons.put(22, new BackButton());
        return buttons;
    }

    private String getSelectedKit(LuckyBlockRewardType type) {
        return switch (type) {
            case GREEN -> this.matchKit.getLuckyBlockGreenKit();
            case BLUE -> this.matchKit.getLuckyBlockBlueKit();
            case YELLOW -> this.matchKit.getLuckyBlockYellowKit();
        };
    }

    private class RewardKitButton extends Button {
        private final LuckyBlockRewardType type;

        private RewardKitButton(LuckyBlockRewardType type) {
            this.type = type;
        }

        @Override
        public ItemStack getButtonItem(Player player) {
            String selected = getSelectedKit(this.type);
            return new ItemBuilder(this.type.getBlockMaterial())
                    .name("&" + colorCode(this.type) + "&l" + this.type.getDisplayName() + " Reward Kit")
                    .lore(
                            CC.MENU_BAR,
                            "&7Selected: &f" + (selected == null || selected.isEmpty() ? "Not Set" : selected),
                            "",
                            "&eClick &7to choose the Kit whose",
                            "&7saved items supply this block's drops.",
                            CC.MENU_BAR
                    )
                    .hideMeta()
                    .build();
        }

        @Override
        public void clicked(Player player, ClickType clickType) {
            if (clickType != ClickType.LEFT) return;
            new LuckyBlockKitSelectionMenu(matchKit, this.type).openMenu(player);
            this.playNeutral(player);
        }
    }

    private class BackButton extends Button {
        @Override
        public ItemStack getButtonItem(Player player) {
            return new ItemBuilder(Material.ARROW)
                    .name("&e&lBack")
                    .lore(CC.MENU_BAR, "&7Return to the kit editor.", CC.MENU_BAR)
                    .hideMeta()
                    .build();
        }

        @Override
        public void clicked(Player player, ClickType clickType) {
            if (clickType != ClickType.LEFT) return;
            new KitEditMenu(matchKit).openMenu(player);
            this.playNeutral(player);
        }
    }

    private static char colorCode(LuckyBlockRewardType type) {
        return switch (type) {
            case GREEN -> 'a';
            case BLUE -> 'b';
            case YELLOW -> 'e';
        };
    }
}
