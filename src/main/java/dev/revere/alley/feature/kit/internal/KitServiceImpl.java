package dev.revere.alley.feature.kit.internal;

import dev.revere.alley.bootstrap.AlleyContext;
import dev.revere.alley.bootstrap.annotation.Service;
import dev.revere.alley.common.logger.Logger;
import dev.revere.alley.common.serializer.Serializer;
import dev.revere.alley.core.config.ConfigService;
import dev.revere.alley.core.locale.LocaleService;
import dev.revere.alley.core.locale.internal.impl.SettingsLocaleImpl;
import dev.revere.alley.feature.bot.BotAiMode;
import dev.revere.alley.feature.kit.Kit;
import dev.revere.alley.feature.kit.KitCategory;
import dev.revere.alley.feature.kit.KitService;
import dev.revere.alley.feature.kit.setting.KitSetting;
import dev.revere.alley.feature.kit.setting.KitSettingService;
import dev.revere.alley.feature.kit.setting.types.combat.KitSettingOldHitDelay;
import dev.revere.alley.feature.kit.setting.types.mechanic.KitSettingPearlCooldownImpl;
import lombok.Getter;
import org.bukkit.Material;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.inventory.ItemStack;
import org.bukkit.potion.PotionEffect;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * @author Emmy
 * @project Alley
 * @date 19/05/2024 - 23:30
 */
@Getter
@Service(provides = KitService.class, priority = 80)
public class KitServiceImpl implements KitService {
    private final ConfigService configService;
    private final KitSettingService kitSettingService;
    private final LocaleService localeService;

    private final List<Kit> kits = new ArrayList<>();

    /**
     * DI Constructor for the KitServiceImpl class.
     * KitServiceImpl类的依赖注入构造函数。
     *
     * @param configService     The configuration service.
     *                          配置服务。
     * @param kitSettingService The kit setting service.
     *                          工具包设置服务。
     * @param localeService     The locale service.
     *                          本地化服务。
     */
    public KitServiceImpl(ConfigService configService, KitSettingService kitSettingService, LocaleService localeService) {
        this.configService = configService;
        this.kitSettingService = kitSettingService;
        this.localeService = localeService;
    }

    @Override
    public void initialize(AlleyContext context) {
        this.loadKits();
    }

    @Override
    public void shutdown(AlleyContext context) {
        this.saveKits();
        Logger.info("Saved all kits.");
    }

    @Override
    public List<Kit> getKits() {
        return Collections.unmodifiableList(this.kits);
    }

    /**
     * Method to load all kits from the kits.yml file.
     * 从kits.yml文件加载所有工具包的方法。
     */
    public void loadKits() {
        this.kits.clear();
        FileConfiguration config = this.configService.getKitsConfig();
        ConfigurationSection kitsSection = config.getConfigurationSection("kits");
        if (kitsSection == null) {
            return;
        }

        boolean numericSettingsChanged = false;
        for (String name : kitsSection.getKeys(false)) {
            if (name == null || name.isEmpty() || name.trim().isEmpty()) continue;
            String key = "kits." + name;

            try {
            Kit kit = new Kit(
                    name,
                    config.getString(key + ".display-name"),
                    config.getString(key + ".description"),
                    config.getString(key + ".disclaimer"),
                    config.getString(key + ".menu-title"),
                    KitCategory.valueOf(config.getString(key + ".category")),
                    Material.matchMaterial(config.getString(key + ".icon.material")),
                    config.getInt(key + ".icon.durability"),
                    Serializer.deserializeItemStack(config.getString(key + ".items")),
                    Serializer.deserializeItemStack(config.getString(key + ".armor")),
                    Serializer.deserializeItemStack(config.getString(key + ".editor-items"))
            );

            // Load icon with NBT/data components (1.21+ potion fix)
            String iconData = config.getString(key + ".icon.data");
            if (iconData != null && !iconData.isEmpty()) {
                ItemStack[] loaded = Serializer.deserializeItemStack(iconData);
                if (loaded != null && loaded.length > 0) kit.setIconItem(loaded[0]);
            }

            // Load the kit's default offhand item (if saved)
            String offhandData = config.getString(key + ".offhand");
            if (offhandData != null && !offhandData.isEmpty()) {
                ItemStack[] offhandArr = Serializer.deserializeItemStack(offhandData);
                if (offhandArr != null && offhandArr.length > 0) kit.setOffhand(offhandArr[0]);
            }

            kit.setEnabled(config.getBoolean(key + ".enabled"));
            kit.setEditable(config.getBoolean(key + ".editable"));
            kit.setKnockbackProfile(config.getString(key + ".knockback-profile"));
            kit.setHideAndSeekSeekerKit(config.getString(key + ".hide-and-seek.seeker-kit", ""));
            kit.setHideAndSeekHiderKit(config.getString(key + ".hide-and-seek.hider-kit", ""));
            kit.setSkyWarsResourceKit(config.getString(key + ".skywars.resource-kit", ""));
            kit.setLuckyBlockGreenKit(config.getString(key + ".lucky-block.green-kit", ""));
            kit.setLuckyBlockBlueKit(config.getString(key + ".lucky-block.blue-kit", ""));
            kit.setLuckyBlockYellowKit(config.getString(key + ".lucky-block.yellow-kit", ""));
            kit.setBotAiMode(BotAiMode.fromName(config.getString(key + ".bot.ai-mode", "MELEE")));

            this.setupFFA(kit, config, key);
            this.loadKitSettings(config, key, kit);

            this.loadPotionEffects(config, key, kit);
            this.addMissingKitSettings(kit, config, key);
            numericSettingsChanged |= this.synchronizeOldHitDelay(kit, config, key);
            numericSettingsChanged |= this.synchronizePearlCooldown(kit, config, key);
            this.kits.add(kit);
            } catch (Exception e) {
                Logger.error("Failed to load kit '" + name + "': " + e.getMessage() + " — skipping.");
            }
        }

        if (numericSettingsChanged) {
            this.configService.saveConfig(this.configService.getConfigFile("storage/kits.yml"), config);
        }
    }

    private void loadKitSettings(FileConfiguration config, String key, Kit kit) {
        ConfigurationSection settingsSection = config.getConfigurationSection(key + ".settings");
        if (settingsSection == null) {
            this.applyDefaultSettings(config, key, kit);
            return;
        }

        this.migrateLegacyPartyFFASetting(config, key, settingsSection);
        settingsSection = config.getConfigurationSection(key + ".settings");

        for (String settingName : settingsSection.getKeys(false)) {
            KitSetting kitSetting = this.kitSettingService.createSettingByName(settingName);
            if (kitSetting == null) continue;

            if (kitSetting instanceof KitSettingOldHitDelay) {
                Object storedValue = settingsSection.get(settingName);
                if (storedValue instanceof Boolean enabled) {
                    kitSetting.setValue(KitSettingOldHitDelay.DEFAULT_DELAY);
                    kitSetting.setEnabled(enabled);
                } else {
                    kitSetting.setValue(this.normalizeOldHitDelay(storedValue));
                    kitSetting.setEnabled(true);
                }
            } else if (kitSetting instanceof KitSettingPearlCooldownImpl) {
                int seconds = this.normalizePearlCooldown(settingsSection.get(settingName));
                kitSetting.setValue(seconds);
                kitSetting.setEnabled(seconds > 0);
            } else {
                boolean enabled = settingsSection.getBoolean(settingName);
                kitSetting.setEnabled(enabled);
            }
            kit.addKitSetting(kitSetting);
        }

    }

    private int normalizeOldHitDelay(Object storedValue) {
        if (storedValue instanceof Number number) {
            return Math.max(0, number.intValue());
        }
        if (storedValue instanceof String value) {
            try {
                return Math.max(0, Integer.parseInt(value));
            } catch (NumberFormatException ignored) {
                // Legacy boolean or malformed values use the standard delay.
            }
        }
        return KitSettingOldHitDelay.DEFAULT_DELAY;
    }

    private int normalizePearlCooldown(Object storedValue) {
        if (storedValue instanceof Number number) {
            return Math.max(0, number.intValue());
        }
        if (storedValue instanceof Boolean enabled) {
            return enabled ? KitSettingPearlCooldownImpl.DEFAULT_SECONDS : 0;
        }
        if (storedValue instanceof String value) {
            try {
                return Math.max(0, Integer.parseInt(value));
            } catch (NumberFormatException ignored) {
                if (value.equalsIgnoreCase("true")) return KitSettingPearlCooldownImpl.DEFAULT_SECONDS;
                if (value.equalsIgnoreCase("false")) return 0;
            }
        }
        return KitSettingPearlCooldownImpl.DEFAULT_SECONDS;
    }

    private boolean synchronizeOldHitDelay(Kit kit, FileConfiguration config, String key) {
        KitSettingOldHitDelay setting = kit.getKitSettings().stream()
                .filter(KitSettingOldHitDelay.class::isInstance)
                .map(KitSettingOldHitDelay.class::cast)
                .findFirst()
                .orElseGet(() -> {
                    KitSettingOldHitDelay created = new KitSettingOldHitDelay();
                    kit.addKitSetting(created);
                    return created;
                });

        int delay = Math.max(0, setting.getValue());
        boolean changed = setting.getValue() != delay;
        setting.setValue(delay);

        Object storedValue = config.get(key + ".settings." + setting.getName());
        Object synchronizedValue = setting.isEnabled() ? Integer.valueOf(delay) : Boolean.FALSE;
        if (!synchronizedValue.equals(storedValue)) {
            config.set(key + ".settings." + setting.getName(), synchronizedValue);
            changed = true;
        }
        return changed;
    }

    private boolean synchronizePearlCooldown(Kit kit, FileConfiguration config, String key) {
        KitSettingPearlCooldownImpl setting = kit.getSetting(KitSettingPearlCooldownImpl.class);
        if (setting == null) {
            setting = new KitSettingPearlCooldownImpl();
            kit.addKitSetting(setting);
        }

        int seconds = Math.max(0, setting.getValue());
        boolean enabled = seconds > 0;
        boolean changed = setting.getValue() != seconds || setting.isEnabled() != enabled;
        setting.setValue(seconds);
        setting.setEnabled(enabled);

        Object storedValue = config.get(key + ".settings." + setting.getName());
        if (!(storedValue instanceof Number number) || number.intValue() != seconds) {
            config.set(key + ".settings." + setting.getName(), seconds);
            changed = true;
        }
        return changed;
    }

    private void migrateLegacyPartyFFASetting(FileConfiguration config, String key, ConfigurationSection settingsSection) {
        if (!settingsSection.contains("FFA")) {
            return;
        }

        if (!settingsSection.contains("PartyFFA")) {
            config.set(key + ".settings.PartyFFA", settingsSection.get("FFA"));
        }
        config.set(key + ".settings.FFA", null);
        this.configService.saveConfig(this.configService.getConfigFile("storage/kits.yml"), config);
    }

    @Override
    public void saveKit(Kit kit) {
        FileConfiguration config = this.configService.getKitsConfig();
        String key = "kits." + kit.getName();
        this.kitToConfig(kit, config, key);

        if (kit.getKitSettings() == null) {
            this.applyDefaultSettings(config, key, kit);
        } else {
            this.saveKitSettings(config, key, kit);
        }

        if (kit.getPotionEffects() != null) {
            this.savePotionEffects(config, key, kit);
        }

        this.configService.saveConfig(this.configService.getConfigFile("storage/kits.yml"), config);
    }

    @Override
    public void createKit(String kitName, ItemStack[] inventory, ItemStack[] armor, Material icon) {
        String defaultDisplayName = this.localeService.getString(SettingsLocaleImpl.CONFIG_KIT_DEFAULT_DISPLAYNAME).replace("{kit-name}", kitName);
        String defaultDescription = this.localeService.getString(SettingsLocaleImpl.CONFIG_KIT_DEFAULT_DESCRIPTION).replace("{kit-name}", kitName);
        String defaultDisclaimer = this.localeService.getString(SettingsLocaleImpl.CONFIG_KIT_DEFAULT_DISCLAIMER).replace("{kit-name}", kitName);
        String defaultMenuTitle = this.localeService.getString(SettingsLocaleImpl.CONFIG_KIT_DEFAULT_MENU_TITLE).replace("{kit-name}", kitName);

        Kit kit = new Kit(
                kitName,
                defaultDisplayName,
                defaultDescription,
                defaultDisclaimer,
                defaultMenuTitle,
                KitCategory.NORMAL,
                icon,
                0,
                inventory,
                armor,
                new ItemStack[0]
        );

        kitSettingService.applyAllSettingsToKit(kit);
        this.kits.add(kit);
        this.saveKit(kit);
    }

    @Override
    public void deleteKit(Kit kit) {
        FileConfiguration config = this.configService.getKitsConfig();

        this.kits.remove(kit);
        config.set("kits." + kit.getName(), null);

        this.configService.saveConfig(this.configService.getConfigFile("storage/kits.yml"), config);
    }

    @Override
    public Kit getKit(String name) {
        return this.kits.stream().filter(kit -> kit.getName().equalsIgnoreCase(name)).findFirst().orElse(null);
    }

    @Override
    public void saveKits() {
        for (Kit kit : this.kits) {
            FileConfiguration config = configService.getKitsConfig();
            String key = "kits." + kit.getName();
            this.kitToConfig(kit, config, key);
            this.saveKitSettings(config, key, kit);
            this.savePotionEffects(config, key, kit);
            configService.saveConfig(configService.getConfigFile("storage/kits.yml"), config);
        }
    }

    /**
     * Method to get a kit by its object.
     * 通过对象获取工具包的方法。
     *
     * @param kit The kit instance.
     *            工具包实例。
     * @return The kit.
     *         工具包。
     */
    public Kit getKit(Kit kit) {
        return this.kits.stream().filter(k -> k.getName().equalsIgnoreCase(kit.getName())).findFirst().orElse(null);
    }

    /**
     * Method to save the settings of a kit.
     * 保存工具包设置的方法。
     *
     * @param config The configuration file.
     *               配置文件。
     * @param key    The path key.
     *               路径键。
     * @param kit    The kit.
     *               工具包。
     */
    private void saveKitSettings(FileConfiguration config, String key, Kit kit) {
        for (KitSetting kitSetting : kit.getKitSettings()) {
            if (kitSetting instanceof KitSettingOldHitDelay) {
                Object storedValue = kitSetting.isEnabled()
                        ? Integer.valueOf(kitSetting.getValue()) : Boolean.FALSE;
                config.set(key + ".settings." + kitSetting.getName(), storedValue);
            } else if (kitSetting instanceof KitSettingPearlCooldownImpl) {
                config.set(key + ".settings." + kitSetting.getName(), kitSetting.getValue());
            } else {
                config.set(key + ".settings." + kitSetting.getName(), kitSetting.isEnabled());
            }
        }
    }


    /**
     * Method to load the potion effects of a kit.
     * 加载工具包药水效果的方法。
     *
     * @param config The configuration file.
     *               配置文件。
     * @param key    The path key.
     *               路径键。
     * @param kit    The kit.
     *               工具包。
     */
    private void loadPotionEffects(FileConfiguration config, String key, Kit kit) {
        try {
            List<PotionEffect> potionEffects = Serializer.deserializePotionEffects(config.getStringList(key + ".potion-effects"));
            kit.setPotionEffects(potionEffects);
        } catch (Exception exception) {
            Logger.logException("Failed to load potion effects for kit " + kit.getName() + ": " + exception.getMessage(), exception);
        }
    }

    /**
     * Handle creation in config for each kit that has missing settings (for development purposes).
     * 在配置中为每个缺少设置的工具包处理创建（用于开发目的）。
     *
     * @param kit    The kit.
     *               工具包。
     * @param config The configuration file.
     *               配置文件。
     * @param key    The path key.
     *               路径键。
     */
    private void addMissingKitSettings(Kit kit, FileConfiguration config, String key) {
        boolean addedSettings = false;
        for (KitSetting setting : this.kitSettingService.getSettings()) {
            if (kit.getKitSettings().stream().noneMatch(kitSetting -> kitSetting.getName().equals(setting.getName()))) {
                KitSetting settingInstance = this.kitSettingService.createSettingByName(setting.getName());
                if (settingInstance == null) {
                    continue;
                }

                kit.addKitSetting(settingInstance);
                Logger.info("&cAdded missing kit setting to &4" + kit.getName() + ": &c" + setting.getName());
                addedSettings = true;
            }
        }

        if (addedSettings) {
            this.saveKitSettings(config, key, kit);
            this.configService.saveConfig(this.configService.getConfigFile("storage/kits.yml"), config);
        }
    }

    /**
     * Method to apply the default settings to a kit.
     * 将默认设置应用于工具包的方法。
     *
     * @param config The configuration file.
     *               配置文件。
     * @param key    The path key.
     *               路径键。
     * @param kit    The kit.
     *               工具包。
     */
    public void applyDefaultSettings(FileConfiguration config, String key, Kit kit) {
        for (KitSetting setting : this.kitSettingService.getSettings()) {
            KitSetting settingInstance = this.kitSettingService.createSettingByName(setting.getName());
            if (settingInstance == null) {
                continue;
            }

            kit.addKitSetting(settingInstance);
            if (settingInstance instanceof KitSettingOldHitDelay) {
                Object storedValue = settingInstance.isEnabled()
                        ? Integer.valueOf(settingInstance.getValue()) : Boolean.FALSE;
                config.set(key + ".settings." + settingInstance.getName(), storedValue);
            } else if (settingInstance instanceof KitSettingPearlCooldownImpl) {
                config.set(key + ".settings." + settingInstance.getName(), settingInstance.getValue());
            } else {
                config.set(key + ".settings." + settingInstance.getName(), settingInstance.isEnabled());
            }
        }

        this.configService.saveConfig(this.configService.getConfigFile("storage/kits.yml"), config);
    }

    /**
     * Method to set up the FFA settings of a kit.
     * 设置工具包FFA配置的方法。
     *
     * @param kit    The kit.
     *               工具包。
     * @param config The configuration file.
     *               配置文件。
     * @param key    The path key.
     *               路径键。
     */
    private void setupFFA(Kit kit, FileConfiguration config, String key) {
        kit.setFfaEnabled(config.getBoolean(key + ".ffa.enabled"));
        kit.setFfaArenaName(config.getString(key + ".ffa.arena-name"));
        kit.setMaxFfaPlayers(config.getInt(key + ".ffa.max-players"));
        kit.setFfaSlot(config.getInt(key + ".ffa.slot"));
    }

    /**
     * Method to save the potion effects of a kit.
     * 保存工具包药水效果的方法。
     *
     * @param config The configuration file.
     *               配置文件。
     * @param key    The path key.
     *               路径键。
     * @param kit    The kit.
     *               工具包。
     */
    private void savePotionEffects(FileConfiguration config, String key, Kit kit) {
        List<String> potionEffects = Serializer.serializePotionEffects(kit.getPotionEffects());
        config.set(key + ".potion-effects", potionEffects);
    }

    /**
     * Method to save a kit to the configuration specified in the parameters with a given key path.
     * 使用给定的键路径将工具包保存到参数指定的配置中的方法。
     *
     * @param kit    The kit to save.
     *               要保存的工具包。
     * @param config The configuration file.
     *               配置文件。
     * @param key    The path key.
     *               路径键。
     */
    private void kitToConfig(Kit kit, FileConfiguration config, String key) {
        config.set(key + ".display-name", kit.getDisplayName());
        config.set(key + ".description", kit.getDescription());
        config.set(key + ".disclaimer", kit.getDisclaimer());
        config.set(key + ".menu-title", kit.getMenuTitle());
        config.set(key + ".enabled", kit.isEnabled());
        config.set(key + ".editable", kit.isEditable());
        config.set(key + ".category", kit.getCategory().name());
        config.set(key + ".icon.material", kit.getIcon().name());
        config.set(key + ".icon.durability", kit.getDurability());
        if (kit.getIconItem() != null) {
            config.set(key + ".icon.data", Serializer.serializeItemStack(new ItemStack[]{kit.getIconItem()}));
        }
        config.set(key + ".ffa.arena-name", kit.getFfaArenaName());
        config.set(key + ".ffa.enabled", kit.isFfaEnabled());
        config.set(key + ".ffa.slot", kit.getFfaSlot());
        config.set(key + ".ffa.max-players", kit.getMaxFfaPlayers());
        config.set(key + ".hide-and-seek.seeker-kit", kit.getHideAndSeekSeekerKit());
        config.set(key + ".hide-and-seek.hider-kit", kit.getHideAndSeekHiderKit());
        config.set(key + ".skywars.resource-kit", kit.getSkyWarsResourceKit());
        config.set(key + ".lucky-block.green-kit", kit.getLuckyBlockGreenKit());
        config.set(key + ".lucky-block.blue-kit", kit.getLuckyBlockBlueKit());
        config.set(key + ".lucky-block.yellow-kit", kit.getLuckyBlockYellowKit());
        config.set(key + ".bot.ai-mode", kit.getBotAiMode().name());
        config.set(key + ".items", Serializer.serializeItemStack(kit.getItems()));
        config.set(key + ".armor", Serializer.serializeItemStack(kit.getArmor()));
        config.set(key + ".knockback-profile", kit.getKnockbackProfile());
        config.set(key + ".editor-items", Serializer.serializeItemStack(kit.getEditorItems()));
        config.set(key + ".offhand", kit.getOffhand() != null && kit.getOffhand().getType() != Material.AIR ?
                Serializer.serializeItemStack(new ItemStack[]{kit.getOffhand()}) : null);
    }
}
