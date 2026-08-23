package dev.revere.alley.feature.division;

import dev.revere.alley.bootstrap.lifecycle.Service;

import java.util.List;

/**
 * @author Remi
 * @project alley-practice
 * @date 2/07/2025
 */
public interface DivisionService extends Service {
    /**
     * Gets a sorted list of all loaded divisions.
     * 获取所有已加载部门的排序列表。
     * @return An unmodifiable list of all divisions.
     *         所有部门的不可修改列表。
     */
    List<Division> getDivisions();

    /**
     * Gets a specific division by its unique name (case-insensitive).
     * 根据唯一名称获取特定的部门（不区分大小写）。
     * @param name The name of the division.
     *             部门的名称。
     * @return The Division object, or null if not found.
     *         部门对象，如果未找到则返回null。
     */
    Division getDivision(String name);

    /**
     * Creates a new division with default tiers and saves it to the configuration.
     * 创建一个带有默认等级的新部门并将其保存到配置中。
     * @param name The unique name for the new division.
     *             新部门的唯一名称。
     * @param requiredWins The number of wins required for the first tier of this division.
     *                     此部门第一个等级所需的胜场数。
     */
    void createDivision(String name, int requiredWins);

    /**
     * Deletes a division from the service and the configuration file.
     * 从服务和配置文件中删除一个部门。
     * @param name The name of the division to delete.
     *             要删除的部门名称。
     */
    void deleteDivision(String name);

    /**
     * Saves a single division object to the configuration file.
     * 将单个部门对象保存到配置文件中。
     * @param division The division to save.
     *                 要保存的部门。
     */
    void saveDivision(Division division);

    /**
     * Finds the highest-ranked division based on the required wins.
     * 根据所需胜场数查找排名最高的部门。
     * @return The highest division, or null if no divisions are loaded.
     *         排名最高的部门，如果未加载任何部门则返回null。
     */
    Division getHighestDivision();

    /**
     * Re-reads all divisions from storage/divisions.yml (used by /alley reload).
     * 从 storage/divisions.yml 重新读取所有部门（由 /alley reload 调用）。
     */
    void reloadDivisions();
}