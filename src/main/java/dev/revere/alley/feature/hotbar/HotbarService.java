package dev.revere.alley.feature.hotbar;

import dev.revere.alley.library.menu.Menu;
import dev.revere.alley.bootstrap.lifecycle.Service;
import dev.revere.alley.core.profile.Profile;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

import java.util.List;

/**
 * @author Emmy
 * @project alley-practice
 * @since 21/07/2025
 */
public interface HotbarService extends Service {
    /**
     * Method to retrieve the list of hotbar items.
     * 获取热键栏物品列表的方法。
     *
     * @return A list of HotbarItem objects representing the items in the hotbar.
     *         表示热键栏中物品的 HotbarItem 对象列表。
     */
    List<HotbarItem> getHotbarItems();

    /**
     * Applies a specific type of hotbar layout to a player's inventory.
     * 将特定类型的热键栏布局应用到玩家的背包中。
     *
     * @param player The player to apply the hotbar to.
     *               要应用热键栏的玩家。
     * @param type   The type of hotbar to apply.
     *               要应用的热键栏类型。
     */
    void applyHotbarItems(Player player, HotbarType type);

    /**
     * Determines the correct hotbar type based on the player's current profile state
     * and applies it to their inventory.
     * 根据玩家当前的配置文件状态确定正确的热键栏类型，并将其应用到玩家的背包中。
     *
     * @param player The player to apply the hotbar to.
     *               要应用热键栏的玩家。
     */
    void applyHotbarItems(Player player);

    /**
     * Creates a new hotbar item with the specified name and type.
     * 使用指定的名称和类型创建新的热键栏物品。
     *
     * @param name The name of the hotbar item to create.
     *             要创建的热键栏物品的名称。
     * @param type The type of the hotbar item to create.
     *             要创建的热键栏物品的类型。
     */
    void createHotbarItem(String name, HotbarType type);

    /**
     * Deletes a hotbar item by its object reference.
     * 通过对象引用删除热键栏物品。
     *
     * @param hotbarItem The HotbarItem object to delete.
     *                   要删除的 HotbarItem 对象。
     */
    void deleteHotbarItem(HotbarItem hotbarItem);

    /**
     * Deletes a hotbar item by its object.
     * 通过对象删除热键栏物品。
     *
     * @param hotbarItem The name of the hotbar item to delete.
     *                   要删除的热键栏物品的名称。
     */
    void saveToConfig(HotbarItem hotbarItem);

    /**
     * Builds an ItemStack representation of a hotbar item that can be received by the player.
     * 构建一个玩家可以接收的热键栏物品的 ItemStack 表示。
     *
     * @param hotbarItem The HotbarItem to build the ItemStack for.
     *                   要构建 ItemStack 的 HotbarItem。
     * @return An ItemStack representing the hotbar item, ready to be given to the player.
     *         表示热键栏物品的 ItemStack，准备交给玩家。
     */
    ItemStack buildReceivableItem(HotbarItem hotbarItem);

    /**
     * Retrieves the list of hotbar items for a specific hotbar type.
     * 获取特定热键栏类型的热键栏物品列表。
     *
     * @param type The type of hotbar to retrieve items for.
     *             要获取物品的热键栏类型。
     * @return A list of HotbarItem objects corresponding to the specified type.
     *         与指定类型对应的 HotbarItem 对象列表。
     */
    List<HotbarItem> getItemsForType(HotbarType type);

    /**
     * Get the corresponding hotbar type for the given profile.
     * 获取给定配置文件对应的热键栏类型。
     *
     * @param profile the profile
     *                配置文件
     * @return the corresponding hotbar type
     *         对应的热键栏类型
     */
    HotbarType getCorrespondingType(Profile profile);

    /**
     * Retrieves the HotbarItem associated with the given ItemStack and hotbar type.
     * 获取与给定 ItemStack 和热键栏类型关联的 HotbarItem。
     *
     * @param itemStack The item stack to check.
     *                  要检查的物品堆。
     * @param type      The type of hotbar to retrieve the item for.
     *                  要获取物品的热键栏类型。
     * @return The HotbarItem associated with the item stack and type, or null if not found.
     *         与物品堆和类型关联的 HotbarItem，如果未找到则返回 null。
     */
    HotbarItem getHotbarItem(ItemStack itemStack, HotbarType type);

    /**
     * Method to retrieve the given hotbar item object.
     * 获取指定热键栏物品对象的方法。
     *
     * @param name the name of the item.
     *             物品的名称。
     * @return the hotbar item.
     *         热键栏物品。
     */
    HotbarItem getHotbarItem(String name);

    /**
     * Gets a menu instance by a given name.
     * 通过给定的名称获取菜单实例。
     *
     * @param name the name of the menu
     *             菜单的名称
     * @return the menu instance
     *         菜单实例
     */
    Menu getMenuInstanceFromName(String name, Player player);

    /**
     * Re-reads all hotbar items from storage/hotbar.yml (used by /alley reload).
     * 从 storage/hotbar.yml 重新读取所有快捷栏物品（由 /alley reload 调用）。
     */
    void reloadHotbars();
}
