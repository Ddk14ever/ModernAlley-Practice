package dev.revere.alley.feature.arena;

import dev.revere.alley.bootstrap.lifecycle.Service;
import dev.revere.alley.feature.arena.internal.types.StandAloneArena;
import dev.revere.alley.feature.kit.Kit;
import org.bukkit.World;

import java.util.List;

/**
 * @author Remi
 * @project alley-practice
 * @date 2/07/2025
 */
public interface ArenaService extends Service {
    /**
     * Gets a list of all persistent arenas loaded from the configuration.
     * 获取从配置中加载的所有持久化竞技场的列表。
     *
     * @return An unmodifiable list of the base arenas.
     *         一个不可修改的基础竞技场列表。
     */
    List<Arena> getArenas();

    /**
     * Gets a list of currently active temporary (copied) arenas.
     * 获取当前活跃的临时（复制）竞技场的列表。
     *
     * @return An unmodifiable list of the temporary stand-alone arenas.
     *         一个不可修改的临时独立竞技场列表。
     */
    List<StandAloneArena> getTemporaryArenas();

    /**
     * Gets the world used for hosting temporary arena copies.
     * 获取用于托管临时竞技场副本的世界。
     *
     * @return The temporary Bukkit World.
     *         临时的 Bukkit 世界。
     */
    World getTemporaryWorld();

    /**
     * Retrieves an arena by its unique name.
     * 通过唯一名称检索竞技场。
     *
     * @param name The name of the arena.
     *             竞技场的名称。
     * @return The AbstractArena, or null if not found.
     *         AbstractArena 实例，如果未找到则返回 null。
     */
    Arena getArenaByName(String name);

    /**
     * Selects a random, enabled arena that is compatible with the given kit.
     * If the selected arena is a StandAloneArena, a temporary copy is created and returned.
     * 选择一个与给定套件兼容的已启用的随机竞技场。
     * 如果选中的竞技场是 StandAloneArena，则创建并返回一个临时副本。
     *
     * @param kit The kit to find a compatible arena for.
     *            用于查找兼容竞技场的套件。
     * @return A suitable AbstractArena, or null if none are available.
     *         一个合适的 AbstractArena 实例，如果没有可用的则返回 null。
     */
    Arena getRandomArena(Kit kit);

    /**
     * Returns whether an enabled, dedicated SkyWars arena has been configured for the kit.
     */
    boolean hasSkyWarsArena(Kit kit);

    /**
     * Selects a dedicated SkyWars arena and creates its temporary copy.
     */
    Arena getRandomSkyWarsArena(Kit kit);

    /**
     * Saves an arena's data to the configuration file.
     * 将竞技场数据保存到配置文件中。
     *
     * @param arena The arena to save.
     *              要保存的竞技场。
     */
    void saveArena(Arena arena);

    /**
     * Deletes an arena's data from the configuration file.
     * 从配置文件中删除竞技场数据。
     *
     * @param arena The arena to delete.
     *              要删除的竞技场。
     */
    void deleteArena(Arena arena);

    /**
     * Deletes a temporary StandAloneArena from the service's tracking list and the temporary world.
     * 从服务的跟踪列表和临时世界中删除一个临时 StandAloneArena。
     *
     * @param arena The StandAloneArena to delete.
     *              要删除的 StandAloneArena。
     */
    void deleteTemporaryArena(StandAloneArena arena);

    /**
     * Creates a temporary, instanced copy of a StandAloneArena in the dedicated temporary world.
     * 在专用的临时世界中创建一个 StandAloneArena 的临时实例副本。
     *
     * @param originalArena The original StandAloneArena to copy.
     *                      要复制的原始 StandAloneArena。
     * @return The new, temporary StandAloneArena instance.
     *         新的临时 StandAloneArena 实例。
     */
    StandAloneArena createTemporaryArenaCopy(StandAloneArena originalArena);

    /**
     * Takes an arena and returns a temporary copy if it's a StandAloneArena,
     * or returns the original arena if it's any other type.
     * 接收一个竞技场，如果是 StandAloneArena 则返回一个临时副本，
     * 如果是任何其他类型则返回原始竞技场。
     *
     * @param arena The arena to process.
     *              要处理的竞技场。
     * @return A temporary copy or the original arena.
     *         临时副本或原始竞技场。
     */
    Arena selectArenaWithPotentialTemporaryCopy(Arena arena);

    /**
     * Adds a newly created arena to the service's tracking list and caches.
     * 将新创建的竞技场添加到服务的跟踪列表和缓存中。
     *
     * @param arena The new arena to add.
     *              要添加的新竞技场。
     */
    void registerNewArena(Arena arena);

    /**
     * Provides access to the internal arena validator for external validation needs.
     * 提供对内部竞技场验证器的访问，以满足外部验证需求。
     *
     * @return The ArenaValidator instance.
     *         ArenaValidator 实例。
     */
    ArenaValidator getArenaValidator();

    /**
     * Re-reads all arenas from storage/arenas.yml (used by /alley reload).
     * 从 storage/arenas.yml 重新读取所有竞技场（由 /alley reload 调用）。
     */
    void loadArenas();

    /**
     * Rebuilds the arena index caches from the in-memory arena list.
     * 根据内存中的竞技场列表重建竞技场索引缓存。
     */
    void refreshCaches();
}
