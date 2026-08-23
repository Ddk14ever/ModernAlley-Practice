package dev.revere.alley.core.config;

import dev.revere.alley.bootstrap.lifecycle.Service;
import org.bukkit.configuration.file.FileConfiguration;

import java.io.File;

/**
 * @author Remi
 * @project alley-practice
 * @date 2/07/2025
 *
 * 配置服务接口，提供配置文件的加载、保存和获取功能。
 * Configuration service interface providing config file loading, saving and retrieval.
 */
public interface ConfigService extends Service {
    /**
     * Reloads all configurations from disk.
     *
     * 从磁盘重新加载所有配置文件。
     */
    void reloadConfigs();

    /**
     * Saves a FileConfiguration to its corresponding file on disk.
     *
     * 将 FileConfiguration 保存到磁盘上对应的文件中。
     *
     * @param configFile        The file to save.
     *                          要保存的文件。
     * @param fileConfiguration The configuration object to save.
     *                          要保存的配置对象。
     */
    void saveConfig(File configFile, FileConfiguration fileConfiguration);

    /**
     * Gets a loaded configuration by its file name.
     *
     * 通过文件名获取已加载的配置。
     *
     * @param configName The name of the config (e.g., "settings.yml").
     *                   配置文件的名称（例如 "settings.yml"）。
     * @return The FileConfiguration object.
     *         对应的 FileConfiguration 对象。
     */
    FileConfiguration getConfig(String configName);

    /**
     * Gets the File object for a configuration by its name.
     *
     * 通过文件名获取配置对应的 File 对象。
     *
     * @param fileName The name of the file (e.g., "settings.yml").
     *                 文件的名称（例如 "settings.yml"）。
     * @return The File object.
     *         对应的 File 对象。
     */
    File getConfigFile(String fileName);

    FileConfiguration getSettingsConfig();

    FileConfiguration getHotbarConfig();

    FileConfiguration getGlobalMessagesConfig();

    FileConfiguration getDatabaseConfig();

    FileConfiguration getKitsConfig();

    FileConfiguration getArenasConfig();

    FileConfiguration getScoreboardConfig();

    FileConfiguration getTabListConfig();

    FileConfiguration getTexturesConfig();

    FileConfiguration getDivisionsConfig();

    FileConfiguration getMenusConfig();

    FileConfiguration getTitlesConfig();

    FileConfiguration getLevelsConfig();

    FileConfiguration getPearlConfig();

    FileConfiguration getAbilityConfig();

    FileConfiguration getBotConfig();

    FileConfiguration getVisualsConfig();

    FileConfiguration getSaltyMessagesConfig();

    FileConfiguration getYeetMessagesConfig();

    FileConfiguration getNerdMessagesConfig();

    FileConfiguration getSpigotCommunityMessagesConfig();

    FileConfiguration getChallengesConfig();

    FileConfiguration getCustomItemsConfig();

    FileConfiguration getAutoClickConfig();

}
