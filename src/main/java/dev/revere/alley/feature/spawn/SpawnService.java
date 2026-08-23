package dev.revere.alley.feature.spawn;

import dev.revere.alley.bootstrap.lifecycle.Service;
import org.bukkit.Location;
import org.bukkit.entity.Player;

/**
 * @author Remi
 * @project alley-practice
 * @date 2/07/2025
 */
public interface SpawnService extends Service {
    /**
     * Gets the currently loaded spawn location.
     * 获取当前已加载的出生点位置。
     *
     * @return The spawn Location, or null if not set.
     *         出生点位置，如果未设置则返回 null。
     */
    Location getLocation();

    /**
     * Sets or updates the spawn location and saves it to the configuration file.
     * 设置或更新出生点位置，并将其保存到配置文件中。
     *
     * @param location The new spawn location.
     *        新的出生点位置。
     */
    void updateSpawnLocation(Location location);

    /**
     * Teleports a player to the configured spawn location.
     * 将玩家传送到已配置的出生点位置。
     *
     * @param player The player to teleport.
     *        要传送的玩家。
     */
    void teleportToSpawn(Player player);

    /**
     * Re-reads the spawn location from settings.yml (used by /alley reload).
     * 从 settings.yml 重新读取出生点位置（由 /alley reload 调用）。
     */
    void reloadSpawnLocation();
}