package dev.revere.alley.feature.kit;

import dev.revere.alley.bootstrap.lifecycle.Service;
import org.bukkit.Material;
import org.bukkit.inventory.ItemStack;

import java.util.List;

/**
 * @author Remi
 * @project alley-practice
 * @date 2/07/2025
 */
public interface KitService extends Service {
    /**
     * Gets a list of all loaded kits.
     * 获取所有已加载工具包的列表。
     *
     * @return An unmodifiable list of kits.
     *         一个不可修改的工具包列表。
     */
    List<Kit> getKits();

    /**
     * Gets a specific kit by its unique name (case-insensitive).
     * 通过唯一名称（不区分大小写）获取特定的工具包。
     *
     * @param name The name of the kit.
     *             工具包的名称。
     * @return The Kit object, or null if not found.
     *         Kit对象，如果未找到则返回null。
     */
    Kit getKit(String name);

    /**
     * Saves a single kit to the configuration file.
     * 将单个工具包保存到配置文件中。
     *
     * @param kit The kit to save.
     *            要保存的工具包。
     */
    void saveKit(Kit kit);

    /**
     * Creates and saves a new kit with default values.
     * 创建并保存一个具有默认值的新工具包。
     *
     * @param kitName The unique name for the new kit.
     *                新工具包的唯一名称。
     * @param inventory The default inventory contents.
     *                  默认的物品栏内容。
     * @param armor The default armor contents.
     *              默认的盔甲内容。
     * @param icon The material for the kit's menu icon.
     *             工具包菜单图标的材质。
     */
    void createKit(String kitName, ItemStack[] inventory, ItemStack[] armor, Material icon);

    /**
     * Deletes a kit from the service and the configuration file.
     * 从服务和配置文件中删除一个工具包。
     *
     * @param kit The kit to delete.
     *            要删除的工具包。
     */
    void deleteKit(Kit kit);

    void saveKits();

    /**
     * Re-reads all kits from storage/kits.yml (used by /alley reload).
     * 从 storage/kits.yml 重新读取所有工具包（由 /alley reload 调用）。
     */
    void loadKits();
}