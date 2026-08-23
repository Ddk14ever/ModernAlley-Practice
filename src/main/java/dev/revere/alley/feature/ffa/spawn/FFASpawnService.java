package dev.revere.alley.feature.ffa.spawn;

import dev.revere.alley.bootstrap.lifecycle.Service;
import dev.revere.alley.common.geom.Cuboid;
import org.bukkit.Location;

/**
 * @author Remi
 * @project alley-practice
 * @date 2/07/2025
 */
public interface FFASpawnService extends Service {
    /**
     * Gets the designated spawn point within the FFA arena.
     * 获取 FFA 竞技场内指定的出生点。
     *
     * @return The spawn Location.
     * @return 出生点位置。
     */
    Location getSpawn();

    /**
     * Gets the minimum boundary point of the FFA safe zone geom.
     * 获取 FFA 安全区几何体的最小边界点。
     *
     * @return The minimum Location.
     * @return 最小边界位置。
     */
    Location getMinimum();

    /**
     * Gets the maximum boundary point of the FFA safe zone geom.
     * 获取 FFA 安全区几何体的最大边界点。
     *
     * @return The maximum Location.
     * @return 最大边界位置。
     */
    Location getMaximum();

    /**
     * Gets the Cuboid object representing the FFA safe zone.
     * 获取表示 FFA 安全区的 Cuboid 对象。
     *
     * @return The Cuboid object, or null if not properly loaded.
     * @return Cuboid 对象，如果未正确加载则为 null。
     */
    Cuboid getCuboid();

    /**
     * Re-reads the FFA spawn cuboid from storage/arenas.yml (used by /alley reload).
     * 从 storage/arenas.yml 重新读取 FFA 出生点区域（由 /alley reload 调用）。
     */
    void loadCuboid();
}