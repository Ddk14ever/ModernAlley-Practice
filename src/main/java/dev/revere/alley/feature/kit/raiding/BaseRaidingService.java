package dev.revere.alley.feature.kit.raiding;

import dev.revere.alley.feature.kit.Kit;
import dev.revere.alley.bootstrap.lifecycle.Service;
import dev.revere.alley.feature.match.model.BaseRaiderRole;

/**
 * @author Remi
 * @project alley-practice
 * @date 2/07/2025
 */
public interface BaseRaidingService extends Service {
    /**
     * Sets or updates the kit used for a specific role within a parent raiding kit.
     * 设置或更新父级掠夺工具包中特定角色使用的工具包。
     * This change is immediately saved to the configuration.
     * 此更改会立即保存到配置中。
     *
     * @param parentKit The main raiding kit.
     * @param parentKit 主掠夺工具包。
     * @param role      The role to map.
     * @param role      要映射的角色。
     * @param roleKit   The kit to assign to the role.
     * @param roleKit   要分配给该角色的工具包。
     */
    void setRaidingKitMapping(Kit parentKit, BaseRaiderRole role, Kit roleKit);

    /**
     * Removes a role-specific kit mapping from a parent raiding kit.
     * 从父级掠夺工具包中移除特定角色的工具包映射。
     *
     * @param parentKit The main raiding kit.
     * @param parentKit 主掠夺工具包。
     * @param role      The role mapping to remove.
     * @param role      要移除的角色映射。
     */
    void removeRaidingKitMapping(Kit parentKit, BaseRaiderRole role);

    /**
     * Gets the assigned sub-kit for a specific role within a parent kit.
     * 获取父级工具包中特定角色分配的子工具包。
     *
     * @param parentKit The parent raiding kit.
     * @param parentKit 父级掠夺工具包。
     * @param role      The raider role.
     * @param role      掠夺者角色。
     * @return The corresponding Kit, or null if no mapping exists.
     * @return 对应的工具包，如果不存在映射则返回 null。
     */
    Kit getRaidingKitByRole(Kit parentKit, BaseRaiderRole role);

    /**
     * Finds the first available parent kit configured for Raiding mode.
     * 查找第一个配置为掠夺模式的父级工具包。
     *
     * @return The first raiding-enabled Kit found, or null if none exist.
     * @return 找到的第一个启用了掠夺模式的工具包，如果不存在则返回 null。
     */
    Kit getRaidingKit();

    /**
     * Checks if a given kit is configured as a parent for Raiding mode.
     * 检查给定的工具包是否被配置为掠夺模式的父级工具包。
     *
     * @param kit The kit to check.
     * @param kit 要检查的工具包。
     * @return True if the kit has raiding role mappings.
     * @return 如果工具包有掠夺角色映射则返回 True。
     */
    boolean hasRaidingKit(Kit kit);

    /**
     * Re-reads all raiding kit role mappings from storage/kits.yml (used by /alley reload).
     * 从 storage/kits.yml 重新读取所有掠夺工具包角色映射（由 /alley reload 调用）。
     */
    void loadRaidingKitMappings();
}