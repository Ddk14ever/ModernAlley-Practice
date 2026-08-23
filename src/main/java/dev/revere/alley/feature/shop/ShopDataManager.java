package dev.revere.alley.feature.shop;

import dev.revere.alley.AlleyPlugin;
import dev.revere.alley.bootstrap.AlleyContext;
import dev.revere.alley.bootstrap.annotation.Service;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.File;
import java.io.IOException;

/**
 * @author Alley
 * @project Alley
 * @since 03/07/2025
 *
 * Manages shop item overrides (price, enabled, category) via shop.yml.
 * 通过shop.yml管理商店物品的覆写（价格、启用、分类）。
 */
@Service(provides = ShopDataManager.class, priority = 165)
public class ShopDataManager implements dev.revere.alley.bootstrap.lifecycle.Service {
    private File configFile;
    private FileConfiguration config;

    @Override
    public void initialize(AlleyContext context) {
        this.load();
    }

    /**
     * Re-reads shop.yml from disk. Called by /alley reload so shop overrides
     * take effect without a server restart.
     */
    public void reload() {
        this.load();
    }

    private void load() {
        this.configFile = new File(AlleyPlugin.getInstance().getDataFolder(), "shop.yml");
        if (!this.configFile.exists()) {
            try {
                this.configFile.createNewFile();
            } catch (IOException e) {
                AlleyPlugin.getInstance().getLogger().warning("Failed to create shop.yml: " + e.getMessage());
            }
        }
        this.config = YamlConfiguration.loadConfiguration(this.configFile);
    }

    @Override
    public void shutdown(AlleyContext context) {
        saveConfig();
    }

    /**
     * Gets the price override for a shop item.
     * Returns the default price if no override exists.
     */
    public int getPrice(String category, String itemName, int defaultPrice) {
        String path = "items." + category + "." + itemName + ".price";
        if (config.contains(path)) {
            return config.getInt(path);
        }
        // Store default for future editing
        config.set(path, defaultPrice);
        saveConfig();
        return defaultPrice;
    }

    /**
     * Gets whether a shop item is enabled.
     */
    public boolean isEnabled(String category, String itemName, boolean defaultEnabled) {
        String path = "items." + category + "." + itemName + ".enabled";
        if (config.contains(path)) {
            return config.getBoolean(path);
        }
        config.set(path, defaultEnabled);
        saveConfig();
        return defaultEnabled;
    }

    /**
     * Sets the price for a shop item.
     */
    public void setPrice(String category, String itemName, int price) {
        config.set("items." + category + "." + itemName + ".price", price);
        saveConfig();
    }

    /**
     * Sets whether a shop item is enabled.
     */
    public void setEnabled(String category, String itemName, boolean enabled) {
        config.set("items." + category + "." + itemName + ".enabled", enabled);
        saveConfig();
    }

    private void saveConfig() {
        try {
            this.config.save(this.configFile);
        } catch (IOException e) {
            AlleyPlugin.getInstance().getLogger().warning("Failed to save shop.yml: " + e.getMessage());
        }
    }
}
