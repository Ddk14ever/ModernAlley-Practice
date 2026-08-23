package dev.revere.alley.feature.level;

import dev.revere.alley.bootstrap.lifecycle.Service;
import dev.revere.alley.feature.level.data.LevelData;

import java.util.List;

/**
 * @author Remi
 * @project alley-practice
 * @date 2/07/2025
 */
public interface LevelService extends Service {
    /**
     * Gets a list of all loaded level tiers.
     * 获取所有已加载等级层级的列表。
     * @return An unmodifiable list of all LevelData objects.
     *         一个不可修改的 LevelData 对象列表。
     */
    List<LevelData> getLevels();

    /**
     * Creates a new level player and saves it to the configuration.
     * 创建一个新的等级并保存到配置中。
     *
     * @param name   The unique name of the level.
     *               等级的唯一名称。
     * @param minElo The minimum Elo rating for this level.
     *               此等级的最低 Elo 评分。
     * @param maxElo The maximum Elo rating for this level.
     *               此等级的最高 Elo 评分。
     */
    void createLevel(String name, int minElo, int maxElo);

    /**
     * Deletes an existing level from the service and the configuration.
     * 从服务和配置中删除一个已有的等级。
     *
     * @param level The LevelData object to delete.
     *              要删除的 LevelData 对象。
     */
    void deleteLevel(LevelData level);

    /**
     * Saves a level's data to the configuration file.
     * 将等级数据保存到配置文件中。
     *
     * @param level The LevelData object to save.
     *              要保存的 LevelData 对象。
     */
    void saveLevel(LevelData level);

    /**
     * Gets the level player that corresponds to a given Elo rating.
     * 获取与给定 Elo 评分对应的等级。
     *
     * @param elo The Elo rating to check.
     *            要检查的 Elo 评分。
     * @return The matching LevelData, or null if no level contains the Elo.
     *         匹配的 LevelData，如果没有等级包含该 Elo 则返回 null。
     */
    LevelData getLevel(int elo);

    /**
     * Gets the level directly above the one the given Elo currently belongs to.
     * Returns null when the Elo already belongs to the highest level.
     * 获取给定 Elo 当前所属等级的下一个等级。若 Elo 已属于最高等级则返回 null。
     *
     * @param elo The Elo rating to check.
     *            要检查的 Elo 评分。
     * @return The next LevelData, or null if the player is at the max level.
     *         下一个 LevelData，如果玩家已处于最高等级则返回 null。
     */
    LevelData getNextLevel(int elo);

    /**
     * Gets a level player by its unique name (case-insensitive).
     * 通过唯一名称获取等级（不区分大小写）。
     *
     * @param name The name of the level.
     *             等级的名称。
     * @return The matching LevelData, or null if not found.
     *         匹配的 LevelData，如果未找到则返回 null。
     */
    LevelData getLevel(String name);

    /**
     * Generates a visual progress bar for the given elo.
     * 为给定的 Elo 生成可视化的进度条。
     *
     * @param elo the elo to check.
     *            要检查的 Elo 评分。
     * @return The formatted progress bar string.
     *         格式化后的进度条字符串。
     */
    String getProgressBar(int elo);

    /**
     * Generates the numerical details for level progression (e.g., 500/1000).
     * 生成等级进度的数值详情（例如 500/1000）。
     *
     * @param elo the elo to check.
     *            要检查的 Elo 评分。
     * @return The formatted progress details string.
     *         格式化后的进度详情字符串。
     */
    String getProgressDetails(int elo);

    /**
     * Re-reads all level tiers from storage/levels.yml (used by /alley reload).
     * 从 storage/levels.yml 重新读取所有等级层级（由 /alley reload 调用）。
     */
    void reloadLevels();
}