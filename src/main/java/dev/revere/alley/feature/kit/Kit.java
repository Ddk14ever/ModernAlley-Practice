package dev.revere.alley.feature.kit;

import dev.revere.alley.feature.bot.BotAiMode;
import dev.revere.alley.feature.kit.setting.KitSetting;
import lombok.Getter;
import lombok.Setter;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.potion.PotionEffect;

import java.util.ArrayList;
import java.util.List;

/**
 * @author Emmy
 * @project Alley
 * @date 28/04/2024 - 21:49
 */
@Getter
@Setter
public class Kit {
    private final String name;

    private String displayName;
    private String description;
    private String disclaimer;
    private String menuTitle;

    private boolean enabled;
    private boolean editable;

    private Material icon;
    private int durability;
    private ItemStack iconItem; // full ItemStack with NBT/data components (1.21+ potion fix)

    private ItemStack[] items;
    private ItemStack[] armor;
    private ItemStack[] editorItems;
    private ItemStack offhand;

    private String ffaArenaName;
    private boolean ffaEnabled;
    private int ffaSlot;
    private int maxFfaPlayers;

    private String hideAndSeekSeekerKit;
    private String hideAndSeekHiderKit;
    private String skyWarsResourceKit;
    private String luckyBlockGreenKit;
    private String luckyBlockBlueKit;
    private String luckyBlockYellowKit;
    private BotAiMode botAiMode;

    private KitCategory category;

    private String knockbackProfile;
    private List<PotionEffect> potionEffects;
    private final List<KitSetting> kitSettings;

    /**
     * Constructor for the Kit class.
     * Kit类的构造函数。
     *
     * @param name        The name of the kit.
     *                   工具包的名称。
     * @param displayName The display name of the kit.
     *                   工具包的显示名称。
     * @param description The description of the kit.
     *                   工具包的描述。
     * @param disclaimer  The disclaimer of the kit.
     *                   工具包的免责声明。
     * @param menuTitle   The title of the kit in the menu.
     *                   工具包在菜单中的标题。
     * @param category    The category of the kit.
     *                   工具包的分类。
     * @param icon        The icon of the kit.
     *                   工具包的图标。
     * @param durability  The durability of the kit's icon.
     *                   工具包图标的耐久度。
     * @param items       The items in the kit.
     *                   工具包中的物品。
     * @param armor       The armor in the kit.
     *                   工具包中的盔甲。
     * @param editorItems The items used in the editor for this kit.
     *                   此工具包在编辑器中使用物品。
     */
    public Kit(String name, String displayName, String description, String disclaimer, String menuTitle, KitCategory category, Material icon, int durability, ItemStack[] items, ItemStack[] armor, ItemStack[] editorItems) {
        this.name = name;

        this.displayName = displayName;
        this.description = description;
        this.disclaimer = disclaimer;
        this.menuTitle = menuTitle;

        this.enabled = false;
        this.editable = true;

        this.category = category;

        this.icon = icon;
        this.durability = durability;

        this.items = items;
        this.armor = armor;
        this.editorItems = editorItems;

        this.ffaEnabled = false;
        this.ffaArenaName = "";
        this.maxFfaPlayers = 20;
        this.ffaSlot = 0;

        this.hideAndSeekSeekerKit = "";
        this.hideAndSeekHiderKit = "";
        this.skyWarsResourceKit = "";
        this.luckyBlockGreenKit = "";
        this.luckyBlockBlueKit = "";
        this.luckyBlockYellowKit = "";
        this.botAiMode = BotAiMode.MELEE;

        this.kitSettings = new ArrayList<>();
        this.potionEffects = new ArrayList<>();
        this.knockbackProfile = "";
    }

    /**
     * Method to add a kit setting.
     * 添加工具包设置的方法。
     *
     * @param kitSetting The kit setting to add.
     *                   要添加的工具包设置。
     */
    public void addKitSetting(KitSetting kitSetting) {
        this.kitSettings.add(kitSetting);
    }

    public ItemStack getIconItemOrDefault() {
        if (this.iconItem != null) {
            return this.iconItem.clone();
        }

        ItemStack item = new ItemStack(this.icon);
        if (item.getItemMeta() instanceof org.bukkit.inventory.meta.Damageable damageable) {
            damageable.setDamage(this.durability);
            item.setItemMeta(damageable);
        }
        return item;
    }

    /**
     * Method to check if a setting is enabled.
     * 检查某个设置是否启用的方法。
     *
     * @param name The name of the setting.
     *             设置的名称。
     * @return Whether the setting is enabled.
     *         该设置是否启用。
     */
    public boolean isSettingEnabled(String name) {
        KitSetting kitSetting = this.kitSettings.stream()
                .filter(setting -> setting.getName().equals(name))
                .findFirst()
                .orElse(null);

        return kitSetting != null && kitSetting.isEnabled();
    }

    /**
     * Method to check if a setting is enabled.
     * 检查某个设置是否启用的方法。
     *
     * @param clazz The class of the setting.
     *              设置的类。
     * @return Whether the setting is enabled.
     *         该设置是否启用。
     */
    public boolean isSettingEnabled(Class<? extends KitSetting> clazz) {
        KitSetting kitSetting = this.getSetting(clazz);

        return kitSetting != null && kitSetting.isEnabled();
    }

    public <T extends KitSetting> T getSetting(Class<T> clazz) {
        return this.kitSettings.stream()
                .filter(clazz::isInstance)
                .map(clazz::cast)
                .findFirst()
                .orElse(null);
    }

    /**
     * Method to apply the potion effects of the kit to a player.
     * 将工具包的药水效果应用于玩家的方法。
     *
     * @param player The player to apply the potion effects to.
     *               要应用药水效果的玩家。
     */
    public void applyPotionEffects(Player player) {
        for (PotionEffect effect : this.potionEffects) {
            player.addPotionEffect(effect);
        }
    }
}
