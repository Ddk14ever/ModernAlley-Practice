package dev.revere.alley.feature.kit.menu;

import dev.revere.alley.AlleyPlugin;
import dev.revere.alley.common.item.ItemBuilder;
import dev.revere.alley.common.text.CC;
import dev.revere.alley.feature.kit.Kit;
import dev.revere.alley.feature.kit.KitCategory;
import dev.revere.alley.feature.kit.KitService;
import dev.revere.alley.feature.kit.setting.types.mode.KitSettingHideAndSeek;
import dev.revere.alley.feature.kit.setting.types.mechanic.KitSettingLuckyBlock;
import dev.revere.alley.library.menu.Button;
import dev.revere.alley.library.menu.Menu;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.inventory.ItemStack;

import java.util.*;

/**
 * @author Alley
 * @project Alley
 * @since 02/07/2025
 *
 * Detailed kit editing menu - edit all properties of a single kit.
 * 详细的工具包编辑菜单 - 编辑单个工具包的所有属性。
 */
public class KitEditMenu extends Menu {
    private final Kit kit;

    public KitEditMenu(Kit kit) {
        this.kit = kit;
        setPlaceholder(true);
    }

    @Override
    public String getTitle(Player player) {
        return "&6&lEditing: " + this.kit.getDisplayName();
    }

    @Override
    public int getSize() {
        return 9 * 6;
    }

    @Override
    public Map<Integer, Button> getButtons(Player player) {
        Map<Integer, Button> buttons = new HashMap<>();

        // Row 1: Kit Icon display (center)
        // 第1行：工具包图标显示（居中）
        buttons.put(4, new KitIconButton(this.kit));

        // Row 3: Name, DisplayName, Description
        // 第3行：名称、显示名称、描述
        buttons.put(19, new KitNameButton(this.kit));
        buttons.put(22, new DisplayNameButton(this.kit));
        buttons.put(25, new DescriptionButton(this.kit));

        // Row 3 (continued): Disclaimer, Menu Title
        // 第3行（续）：免责声明、菜单标题
        buttons.put(28, new DisclaimerButton(this.kit));
        buttons.put(31, new MenuTitleButton(this.kit));
        buttons.put(34, new IconSelectButton(this.kit));

        // Row 4: Category toggle, Enabled/Editable
        // 第4行：分类切换、启用/可编辑
        buttons.put(37, new CategoryButton(this.kit));
        buttons.put(40, new ToggleEnabledButton(this.kit));
        buttons.put(43, new ToggleEditableButton(this.kit));
        if (this.kit.isSettingEnabled(KitSettingHideAndSeek.class)) {
            buttons.put(39, new HideAndSeekKitsButton(this.kit));
        }
        if (this.kit.isSettingEnabled(KitSettingLuckyBlock.class)) {
            buttons.put(42, new LuckyBlockKitsButton(this.kit));
        }
        buttons.put(41, new SkyWarsResourceKitButton(this.kit));

        // Row 5: Kit Settings, Knockback Profile, FFA
        // 第5行：工具包设置、击退配置、FFA
        buttons.put(47, new SettingsButton(this.kit));
        buttons.put(49, new KnockbackProfileButton(this.kit));
        buttons.put(51, new FFAButton(this.kit));

        // Row 6: Save, Delete, Back, Potions
        // 第6行：保存、删除、返回、药水
        buttons.put(45, new SaveKitButton(this.kit));
        buttons.put(48, new PotionsButton(this.kit));
        buttons.put(50, new DeleteKitButton(this.kit));
        buttons.put(53, new BackButton());

        this.addBorder(buttons, Material.BLACK_STAINED_GLASS_PANE, 6);

        return buttons;
    }

    // ========================
    // Button Inner Classes
    // 按钮内部类
    // ========================

    /**
     * Displays the kit's current icon.
     * 显示工具包的当前图标。
     */
    private static class KitIconButton extends Button {
        private final Kit kit;

        public KitIconButton(Kit kit) {
            this.kit = kit;
        }

        @Override
        public ItemStack getButtonItem(Player player) {
            ItemStack base = this.kit.getIconItem() != null
                    ? this.kit.getIconItem().clone() : new ItemStack(this.kit.getIcon());
            return new ItemBuilder(base)
                    .name("&6&lKit Icon: " + this.kit.getDisplayName())
                    .durability(this.kit.getDurability())
                    .lore(
                            CC.MENU_BAR,
                            "&7Current icon: &f" + this.kit.getIcon().name(),
                            "&7Durability/data: &f" + this.kit.getDurability(),
                            "",
                            "&eLeft-Click &7to copy held item as icon.",
                            CC.MENU_BAR
                    )
                    .hideMeta()
                    .build();
        }

        @Override
        public void clicked(Player player, ClickType clickType) {
            if (clickType != ClickType.LEFT) return;

            ItemStack held = player.getInventory().getItemInMainHand();
            if (held == null || held.getType() == Material.AIR) {
                player.sendMessage(CC.translate("&cYou must hold an item in your hand!"));
                this.playFail(player);
                return;
            }

            this.kit.setIcon(held.getType());
            this.kit.setIconItem(held.clone());
            if (held.getItemMeta() instanceof org.bukkit.inventory.meta.Damageable damageable) {
                this.kit.setDurability(damageable.getDamage());
            } else {
                this.kit.setDurability(0);
            }
            AlleyPlugin.getInstance().getService(KitService.class).saveKit(this.kit);
            player.sendMessage(CC.translate("&aKit icon updated to &6" + held.getType().name() + "&a."));
            new KitEditMenu(this.kit).openMenu(player);
            this.playSuccess(player);
        }
    }

    /**
     * Displays the kit's internal name.
     * 显示工具包的内部名称。
     */
    private static class KitNameButton extends Button {
        private final Kit kit;

        public KitNameButton(Kit kit) {
            this.kit = kit;
        }

        @Override
        public ItemStack getButtonItem(Player player) {
            return new ItemBuilder(Material.NAME_TAG)
                    .name("&6&lKit ID")
                    .lore(
                            CC.MENU_BAR,
                            "&7Internal Name: &f" + this.kit.getName(),
                            "",
                            "&cThis cannot be changed.",
                            "&7It is the unique identifier.",
                            CC.MENU_BAR
                    )
                    .hideMeta()
                    .build();
        }
    }

    /**
     * Toggles the display name.
     * 切换显示名称。
     */
    private static class DisplayNameButton extends Button {
        private final Kit kit;

        public DisplayNameButton(Kit kit) {
            this.kit = kit;
        }

        @Override
        public ItemStack getButtonItem(Player player) {
            return new ItemBuilder(Material.OAK_SIGN)
                    .name("&6&lDisplay Name")
                    .lore(
                            CC.MENU_BAR,
                            "&7Current: &f" + this.kit.getDisplayName(),
                            "",
                            "&eClick &7to change via chat.",
                            "&7Use &e/kit setdisplayname " + this.kit.getName() + " <name>",
                            CC.MENU_BAR
                    )
                    .hideMeta()
                    .build();
        }

        @Override
        public void clicked(Player player, ClickType clickType) {
            if (clickType != ClickType.LEFT) return;
            player.closeInventory();
            player.sendMessage(CC.translate("&eUse &6/kit setdisplayname " + this.kit.getName() + " <name> &eto change."));
            this.playNeutral(player);
        }
    }

    /**
     * Shows and edits the kit description.
     * 显示和编辑工具包描述。
     */
    private static class DescriptionButton extends Button {
        private final Kit kit;

        public DescriptionButton(Kit kit) {
            this.kit = kit;
        }

        @Override
        public ItemStack getButtonItem(Player player) {
            List<String> lore = new ArrayList<>();
            lore.add(CC.MENU_BAR);
            lore.add("&7Current description:");
            if (this.kit.getDescription() != null && !this.kit.getDescription().isEmpty()) {
                for (String line : this.kit.getDescription().split("\n")) {
                    lore.add(" &f" + line);
                }
            } else {
                lore.add(" &7(None)");
            }
            lore.add("");
            lore.add("&eClick &7to change via chat.");
            lore.add("&7Use &e/kit setdescription " + this.kit.getName() + " <desc>");
            lore.add(CC.MENU_BAR);

            return new ItemBuilder(Material.WRITABLE_BOOK)
                    .name("&6&lDescription")
                    .lore(lore)
                    .hideMeta()
                    .build();
        }

        @Override
        public void clicked(Player player, ClickType clickType) {
            if (clickType != ClickType.LEFT) return;
            player.closeInventory();
            player.sendMessage(CC.translate("&eUse &6/kit setdescription " + this.kit.getName() + " <description> &eto change."));
            this.playNeutral(player);
        }
    }

    /**
     * Shows the kit disclaimer.
     * 显示工具包免责声明。
     */
    private static class DisclaimerButton extends Button {
        private final Kit kit;

        public DisclaimerButton(Kit kit) {
            this.kit = kit;
        }

        @Override
        public ItemStack getButtonItem(Player player) {
            List<String> lore = new ArrayList<>();
            lore.add(CC.MENU_BAR);
            lore.add("&7Current disclaimer:");
            if (this.kit.getDisclaimer() != null && !this.kit.getDisclaimer().isEmpty()) {
                lore.add(" &f" + this.kit.getDisclaimer());
            } else {
                lore.add(" &7(None)");
            }
            lore.add("");
            lore.add("&eClick &7to change via chat.");
            lore.add("&7Use &e/kit setdisclaimer " + this.kit.getName() + " <text>");
            lore.add(CC.MENU_BAR);

            return new ItemBuilder(Material.PAPER)
                    .name("&6&lDisclaimer")
                    .lore(lore)
                    .hideMeta()
                    .build();
        }

        @Override
        public void clicked(Player player, ClickType clickType) {
            if (clickType != ClickType.LEFT) return;
            player.closeInventory();
            player.sendMessage(CC.translate("&eUse &6/kit setdisclaimer " + this.kit.getName() + " <text> &eto change."));
            this.playNeutral(player);
        }
    }

    /**
     * Shows and edits the menu title.
     * 显示和编辑菜单标题。
     */
    private static class MenuTitleButton extends Button {
        private final Kit kit;

        public MenuTitleButton(Kit kit) {
            this.kit = kit;
        }

        @Override
        public ItemStack getButtonItem(Player player) {
            return new ItemBuilder(Material.ITEM_FRAME)
                    .name("&6&lMenu Title")
                    .lore(
                            CC.MENU_BAR,
                            "&7Current: &f" + (this.kit.getMenuTitle() != null ? this.kit.getMenuTitle() : "None"),
                            "",
                            "&eClick &7to change via chat.",
                            "&7Use &e/kit setmenutitle " + this.kit.getName() + " <title>",
                            CC.MENU_BAR
                    )
                    .hideMeta()
                    .build();
        }

        @Override
        public void clicked(Player player, ClickType clickType) {
            if (clickType != ClickType.LEFT) return;
            player.closeInventory();
            player.sendMessage(CC.translate("&eUse &6/kit setmenutitle " + this.kit.getName() + " <title> &eto change."));
            this.playNeutral(player);
        }
    }

    /**
     * Change the icon material by clicking with the held item.
     * 通过点击手持物品来更改图标材质。
     */
    private static class IconSelectButton extends Button {
        private final Kit kit;

        public IconSelectButton(Kit kit) {
            this.kit = kit;
        }

        @Override
        public ItemStack getButtonItem(Player player) {
            return new ItemBuilder(Material.PAINTING)
                    .name("&6&lChange Icon")
                    .lore(
                            CC.MENU_BAR,
                            "&7Hold an item and click this",
                            "&7to set it as the kit icon.",
                            "",
                            "&7Or use:",
                            "&e/kit seticon " + this.kit.getName(),
                            CC.MENU_BAR
                    )
                    .hideMeta()
                    .build();
        }

        @Override
        public void clicked(Player player, ClickType clickType) {
            if (clickType != ClickType.LEFT) return;

            ItemStack held = player.getInventory().getItemInMainHand();
            if (held == null || held.getType() == Material.AIR) {
                player.sendMessage(CC.translate("&cYou must hold an item in your hand!"));
                this.playFail(player);
                return;
            }

            this.kit.setIcon(held.getType());
            this.kit.setIconItem(held.clone());
            if (held.getItemMeta() instanceof org.bukkit.inventory.meta.Damageable damageable) {
                this.kit.setDurability(damageable.getDamage());
            } else {
                this.kit.setDurability(0);
            }
            AlleyPlugin.getInstance().getService(KitService.class).saveKit(this.kit);
            player.sendMessage(CC.translate("&aKit icon set to &6" + held.getType().name() + "&a."));
            new KitEditMenu(this.kit).openMenu(player);
            this.playSuccess(player);
        }
    }

    /**
     * Toggle kit category between NORMAL and EXTRA.
     * 在 NORMAL 和 EXTRA 之间切换工具包分类。
     */
    private static class CategoryButton extends Button {
        private final Kit kit;

        public CategoryButton(Kit kit) {
            this.kit = kit;
        }

        @Override
        public ItemStack getButtonItem(Player player) {
            KitCategory current = this.kit.getCategory();
            KitCategory next = (current == KitCategory.NORMAL) ? KitCategory.EXTRA : KitCategory.NORMAL;

            return new ItemBuilder(Material.BOOKSHELF)
                    .name("&6&lCategory: &e" + current.getName())
                    .lore(
                            CC.MENU_BAR,
                            "&7Current: &f" + current.getName() + " &7- &f" + current.getDescription(),
                            "",
                            "&eClick &7to toggle to: &f" + next.getName(),
                            "&7" + next.getDescription(),
                            CC.MENU_BAR
                    )
                    .glow(current == KitCategory.EXTRA)
                    .hideMeta()
                    .build();
        }

        @Override
        public void clicked(Player player, ClickType clickType) {
            if (clickType != ClickType.LEFT) return;

            KitCategory current = this.kit.getCategory();
            this.kit.setCategory(current == KitCategory.NORMAL ? KitCategory.EXTRA : KitCategory.NORMAL);
            AlleyPlugin.getInstance().getService(KitService.class).saveKit(this.kit);
            new KitEditMenu(this.kit).openMenu(player);
            this.playSuccess(player);
        }
    }

    /**
     * Toggle kit enabled/disabled.
     * 切换工具包启用/禁用。
     */
    private static class ToggleEnabledButton extends Button {
        private final Kit kit;

        public ToggleEnabledButton(Kit kit) {
            this.kit = kit;
        }

        @Override
        public ItemStack getButtonItem(Player player) {
            boolean enabled = this.kit.isEnabled();
            return new ItemBuilder(enabled ? Material.LIME_DYE : Material.GRAY_DYE)
                    .name((enabled ? "&a" : "&c") + "&lEnabled: " + (enabled ? "Yes" : "No"))
                    .lore(
                            CC.MENU_BAR,
                            "&7The kit is currently " + (enabled ? "&aenabled" : "&cdisabled") + "&7.",
                            "",
                            "&eClick &7to " + (enabled ? "disable" : "enable") + ".",
                            CC.MENU_BAR
                    )
                    .glow(enabled)
                    .hideMeta()
                    .build();
        }

        @Override
        public void clicked(Player player, ClickType clickType) {
            if (clickType != ClickType.LEFT) return;
            this.kit.setEnabled(!this.kit.isEnabled());
            AlleyPlugin.getInstance().getService(KitService.class).saveKit(this.kit);
            new KitEditMenu(this.kit).openMenu(player);
            this.playSuccess(player);
        }
    }

    /**
     * Toggle kit editable.
     * 切换工具包可编辑性。
     */
    private static class ToggleEditableButton extends Button {
        private final Kit kit;

        public ToggleEditableButton(Kit kit) {
            this.kit = kit;
        }

        @Override
        public ItemStack getButtonItem(Player player) {
            boolean editable = this.kit.isEditable();
            return new ItemBuilder(editable ? Material.CRAFTING_TABLE : Material.BARRIER)
                    .name((editable ? "&a" : "&c") + "&lLayout Editable: " + (editable ? "Yes" : "No"))
                    .lore(
                            CC.MENU_BAR,
                            "&7Players " + (editable ? "&acan&7" : "&ccannot&7") + " edit their layout.",
                            "",
                            "&eClick &7to toggle.",
                            CC.MENU_BAR
                    )
                    .glow(editable)
                    .hideMeta()
                    .build();
        }

        @Override
        public void clicked(Player player, ClickType clickType) {
            if (clickType != ClickType.LEFT) return;
            this.kit.setEditable(!this.kit.isEditable());
            AlleyPlugin.getInstance().getService(KitService.class).saveKit(this.kit);
            new KitEditMenu(this.kit).openMenu(player);
            this.playSuccess(player);
        }
    }

    private static class HideAndSeekKitsButton extends Button {
        private final Kit kit;

        public HideAndSeekKitsButton(Kit kit) {
            this.kit = kit;
        }

        @Override
        public ItemStack getButtonItem(Player player) {
            String seekerKit = this.kit.getHideAndSeekSeekerKit();
            String hiderKit = this.kit.getHideAndSeekHiderKit();
            return new ItemBuilder(Material.SPYGLASS)
                    .name("&6&lHideAndSeek Role Kits")
                    .lore(
                            CC.MENU_BAR,
                            "&7Seeker: &f" + (seekerKit == null || seekerKit.isEmpty() ? "Main Kit" : seekerKit),
                            "&7Hider: &f" + (hiderKit == null || hiderKit.isEmpty() ? "Main Kit" : hiderKit),
                            "",
                            "&eLeft-Click &7to set the seeker kit.",
                            "&eRight-Click &7to set the hider kit.",
                            CC.MENU_BAR
                    )
                    .hideMeta()
                    .build();
        }

        @Override
        public void clicked(Player player, ClickType clickType) {
            if (clickType == ClickType.LEFT) {
                new HideAndSeekKitSelectionMenu(this.kit, true).openMenu(player);
                this.playNeutral(player);
            } else if (clickType == ClickType.RIGHT) {
                new HideAndSeekKitSelectionMenu(this.kit, false).openMenu(player);
                this.playNeutral(player);
            }
        }
    }

    private static class LuckyBlockKitsButton extends Button {
        private final Kit kit;

        private LuckyBlockKitsButton(Kit kit) {
            this.kit = kit;
        }

        @Override
        public ItemStack getButtonItem(Player player) {
            return new ItemBuilder(Material.CHEST)
                    .name("&6&lLuckyBlock Reward Kits")
                    .lore(
                            CC.MENU_BAR,
                            "&aGreen: &f" + displayName(this.kit.getLuckyBlockGreenKit()),
                            "&bBlue: &f" + displayName(this.kit.getLuckyBlockBlueKit()),
                            "&eYellow: &f" + displayName(this.kit.getLuckyBlockYellowKit()),
                            "",
                            "&eClick &7to select each color's reward Kit.",
                            CC.MENU_BAR
                    )
                    .hideMeta()
                    .build();
        }

        @Override
        public void clicked(Player player, ClickType clickType) {
            if (clickType != ClickType.LEFT) return;
            new LuckyBlockKitsMenu(this.kit).openMenu(player);
            this.playNeutral(player);
        }

        private static String displayName(String kitName) {
            return kitName == null || kitName.isEmpty() ? "Not Set" : kitName;
        }
    }

    private static class SkyWarsResourceKitButton extends Button {
        private final Kit kit;

        public SkyWarsResourceKitButton(Kit kit) {
            this.kit = kit;
        }

        @Override
        public ItemStack getButtonItem(Player player) {
            String resourceKit = this.kit.getSkyWarsResourceKit();
            return new ItemBuilder(Material.CHEST)
                    .name("&b&lSkyWars Resource Kit")
                    .lore(
                            CC.MENU_BAR,
                            "&7Selected: &f" + (resourceKit == null || resourceKit.isEmpty() ? "Not Set" : resourceKit),
                            "",
                            "&eClick &7to select the Kit whose items",
                            "&7will be randomly placed in SkyWars chests.",
                            CC.MENU_BAR
                    )
                    .hideMeta()
                    .build();
        }

        @Override
        public void clicked(Player player, ClickType clickType) {
            if (clickType != ClickType.LEFT) return;
            new SkyWarsResourceKitSelectionMenu(this.kit).openMenu(player);
            this.playNeutral(player);
        }
    }

    /**
     * Open the kit settings sub-menu.
     * 打开工具包设置子菜单。
     */
    private static class SettingsButton extends Button {
        private final Kit kit;

        public SettingsButton(Kit kit) {
            this.kit = kit;
        }

        @Override
        public ItemStack getButtonItem(Player player) {
            long activeCount = this.kit.getKitSettings().stream().filter(s -> s.isEnabled()).count();
            long total = this.kit.getKitSettings().size();

            return new ItemBuilder(Material.COMPARATOR)
                    .name("&6&lKit Settings")
                    .lore(
                            CC.MENU_BAR,
                            "&7Active: &f" + activeCount + "&7/&f" + total,
                            "",
                            "&eClick &7to open settings editor.",
                            CC.MENU_BAR
                    )
                    .glow(activeCount > 0)
                    .hideMeta()
                    .build();
        }

        @Override
        public void clicked(Player player, ClickType clickType) {
            if (clickType != ClickType.LEFT) return;
            new KitSettingsMenu(this.kit).openMenu(player);
            this.playNeutral(player);
        }
    }

    /**
     * Knockback profile display.
     * 击退配置显示。
     */
    private static class KnockbackProfileButton extends Button {
        private final Kit kit;

        public KnockbackProfileButton(Kit kit) {
            this.kit = kit;
        }

        @Override
        public ItemStack getButtonItem(Player player) {
            String profile = this.kit.getKnockbackProfile();
            if (profile == null || profile.isEmpty()) {
                profile = "Default";
            }

            return new ItemBuilder(Material.STICK)
                    .name("&6&lKnockback Profile")
                    .lore(
                            CC.MENU_BAR,
                            "&7Current: &f" + profile,
                            "",
                            "&eClick &7to change via chat.",
                            "&7Use &e/kit setprofile " + this.kit.getName() + " <profile>",
                            CC.MENU_BAR
                    )
                    .hideMeta()
                    .build();
        }

        @Override
        public void clicked(Player player, ClickType clickType) {
            if (clickType != ClickType.LEFT) return;
            player.closeInventory();
            player.sendMessage(CC.translate("&eUse &6/kit setprofile " + this.kit.getName() + " <profile> &eto change."));
            this.playNeutral(player);
        }
    }

    /**
     * FFA settings display.
     * FFA 设置显示。
     */
    private static class FFAButton extends Button {
        private final Kit kit;

        public FFAButton(Kit kit) {
            this.kit = kit;
        }

        @Override
        public ItemStack getButtonItem(Player player) {
            boolean ffa = this.kit.isFfaEnabled();
            return new ItemBuilder(ffa ? Material.GOLDEN_AXE : Material.WOODEN_AXE)
                    .name((ffa ? "&a" : "&c") + "&lFFA Mode: " + (ffa ? "Enabled" : "Disabled"))
                    .lore(
                            CC.MENU_BAR,
                            "&7FFA: " + (ffa ? "&aYes" : "&cNo"),
                            "&7FFA Arena: &f" + (this.kit.getFfaArenaName().isEmpty() ? "None" : this.kit.getFfaArenaName()),
                            "&7Max Players: &f" + this.kit.getMaxFfaPlayers(),
                            "&7FFA Slot: &f" + this.kit.getFfaSlot(),
                            "",
                            "&7Use commands to configure FFA.",
                            "&e/ffa setup " + this.kit.getName() + " <arena> <max> <slot>",
                            CC.MENU_BAR
                    )
                    .glow(ffa)
                    .hideMeta()
                    .build();
        }
    }

    /**
     * Save the kit button.
     * 保存工具包按钮。
     */
    private static class SaveKitButton extends Button {
        private final Kit kit;

        public SaveKitButton(Kit kit) {
            this.kit = kit;
        }

        @Override
        public ItemStack getButtonItem(Player player) {
            return new ItemBuilder(Material.WRITABLE_BOOK)
                    .name("&a&lSave Kit")
                    .lore(
                            CC.MENU_BAR,
                            "&7Click to save this kit",
                            "&7to the configuration file.",
                            CC.MENU_BAR
                    )
                    .hideMeta()
                    .build();
        }

        @Override
        public void clicked(Player player, ClickType clickType) {
            if (clickType != ClickType.LEFT) return;
            AlleyPlugin.getInstance().getService(KitService.class).saveKit(this.kit);
            player.sendMessage(CC.translate("&aKit &6" + this.kit.getName() + " &asaved successfully!"));
            this.playSuccess(player);
        }
    }

    /**
     * Open potion list for this kit.
     * 打开此工具包的药水列表。
     */
    private static class PotionsButton extends Button {
        private final Kit kit;

        public PotionsButton(Kit kit) {
            this.kit = kit;
        }

        @Override
        public ItemStack getButtonItem(Player player) {
            int count = this.kit.getPotionEffects().size();
            return new ItemBuilder(Material.POTION)
                    .name("&6&lPotion Effects")
                    .lore(
                            CC.MENU_BAR,
                            "&7Active effects: &f" + count,
                            "",
                            "&eClick &7to view potions.",
                            CC.MENU_BAR
                    )
                    .glow(count > 0)
                    .hideMeta()
                    .build();
        }

        @Override
        public void clicked(Player player, ClickType clickType) {
            if (clickType != ClickType.LEFT) return;
            new KitPotionListMenu(this.kit).openMenu(player);
            this.playNeutral(player);
        }
    }

    /**
     * Delete the kit button.
     * 删除工具包按钮。
     */
    private static class DeleteKitButton extends Button {
        private final Kit kit;

        public DeleteKitButton(Kit kit) {
            this.kit = kit;
        }

        @Override
        public ItemStack getButtonItem(Player player) {
            return new ItemBuilder(Material.LAVA_BUCKET)
                    .name("&c&lDelete Kit")
                    .lore(
                            CC.MENU_BAR,
                            "&cWarning: This cannot be undone!",
                            "",
                            "&eShift-Click &7to confirm delete.",
                            CC.MENU_BAR
                    )
                    .hideMeta()
                    .build();
        }

        @Override
        public void clicked(Player player, ClickType clickType) {
            if (clickType != ClickType.SHIFT_LEFT && clickType != ClickType.SHIFT_RIGHT) {
                player.sendMessage(CC.translate("&cShift-click to confirm deletion!"));
                this.playFail(player);
                return;
            }
            String kitName = this.kit.getName();
            AlleyPlugin.getInstance().getService(KitService.class).deleteKit(this.kit);
            player.sendMessage(CC.translate("&aKit &6" + kitName + " &ahas been deleted."));
            new KitManagementMenu().openMenu(player);
            this.playSuccess(player);
        }
    }

    /**
     * Return to kit management menu.
     * 返回工具包管理菜单。
     */
    private static class BackButton extends Button {
        @Override
        public ItemStack getButtonItem(Player player) {
            return new ItemBuilder(Material.ARROW)
                    .name("&e&lBack")
                    .lore(
                            CC.MENU_BAR,
                            "&7Click to return to",
                            "&7the kit management menu.",
                            CC.MENU_BAR
                    )
                    .hideMeta()
                    .build();
        }

        @Override
        public void clicked(Player player, ClickType clickType) {
            if (clickType != ClickType.LEFT) return;
            new KitManagementMenu().openMenu(player);
            this.playNeutral(player);
        }
    }
}
