package dev.revere.alley.feature.kit.menu;

import dev.revere.alley.AlleyPlugin;
import dev.revere.alley.common.item.ItemBuilder;
import dev.revere.alley.common.text.CC;
import dev.revere.alley.feature.event.skywars.SkyWarsLoot;
import dev.revere.alley.feature.kit.Kit;
import dev.revere.alley.feature.kit.KitService;
import dev.revere.alley.library.menu.Button;
import dev.revere.alley.library.menu.pagination.PaginatedMenu;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.inventory.ItemStack;

import java.util.HashMap;
import java.util.Map;

/** Selects the kit whose inventory will be used as randomized SkyWars chest loot. */
public class SkyWarsResourceKitSelectionMenu extends PaginatedMenu {
    private final Kit skyWarsKit;

    public SkyWarsResourceKitSelectionMenu(Kit skyWarsKit) {
        this.skyWarsKit = skyWarsKit;
    }

    @Override
    public String getPrePaginatedTitle(Player player) {
        return "&b&lSelect SkyWars Resource Kit";
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
        buttons.put(49, new ClearButton());
        buttons.put(53, new BackButton());
        return buttons;
    }

    @Override
    public Map<Integer, Button> getAllPagesButtons(Player player) {
        Map<Integer, Button> buttons = new HashMap<>();
        int index = 0;
        for (Kit candidate : AlleyPlugin.getInstance().getService(KitService.class).getKits()) {
            buttons.put(index++, new KitButton(candidate));
        }
        return buttons;
    }

    private void setResourceKit(String name) {
        this.skyWarsKit.setSkyWarsResourceKit(name);
        AlleyPlugin.getInstance().getService(KitService.class).saveKit(this.skyWarsKit);
    }

    private class KitButton extends Button {
        private final Kit candidate;

        private KitButton(Kit candidate) {
            this.candidate = candidate;
        }

        @Override
        public ItemStack getButtonItem(Player player) {
            int itemCount = SkyWarsLoot.getResourceItems(this.candidate).size();
            boolean selected = this.candidate.getName().equalsIgnoreCase(skyWarsKit.getSkyWarsResourceKit());
            boolean usable = itemCount >= SkyWarsLoot.MINIMUM_ITEMS_PER_CHEST;
            return new ItemBuilder(this.candidate.getIconItemOrDefault())
                    .name((selected ? "&a" : usable ? "&6" : "&c") + this.candidate.getDisplayName())
                    .lore(
                            CC.MENU_BAR,
                            "&7Kit ID: &f" + this.candidate.getName(),
                            "&7Loot items: " + (usable ? "&a" : "&c") + itemCount + "&7/" + SkyWarsLoot.MINIMUM_ITEMS_PER_CHEST,
                            "&7Selected: " + (selected ? "&aYes" : "&cNo"),
                            "",
                            usable ? "&eClick &7to assign this resource kit." : "&cAt least 7 non-air items are required.",
                            CC.MENU_BAR
                    )
                    .hideMeta()
                    .build();
        }

        @Override
        public void clicked(Player player, ClickType clickType) {
            if (clickType != ClickType.LEFT) return;
            if (!SkyWarsLoot.isUsableResourceKit(this.candidate)) {
                player.sendMessage(CC.translate("&cA SkyWars resource kit must contain at least 7 non-air items."));
                this.playFail(player);
                return;
            }

            setResourceKit(this.candidate.getName());
            player.sendMessage(CC.translate("&aSkyWars resource kit set to &6" + this.candidate.getName() + "&a."));
            new KitEditMenu(skyWarsKit).openMenu(player);
            this.playSuccess(player);
        }
    }

    private class ClearButton extends Button {
        @Override
        public ItemStack getButtonItem(Player player) {
            return new ItemBuilder(Material.BARRIER)
                    .name("&c&lClear Resource Kit")
                    .lore(CC.MENU_BAR, "&7SkyWars cannot be hosted until another resource kit is selected.", CC.MENU_BAR)
                    .hideMeta()
                    .build();
        }

        @Override
        public void clicked(Player player, ClickType clickType) {
            if (clickType != ClickType.LEFT) return;
            setResourceKit("");
            new KitEditMenu(skyWarsKit).openMenu(player);
            this.playSuccess(player);
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
            new KitEditMenu(skyWarsKit).openMenu(player);
            this.playNeutral(player);
        }
    }
}
