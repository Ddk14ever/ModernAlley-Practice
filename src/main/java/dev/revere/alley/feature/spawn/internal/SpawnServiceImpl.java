package dev.revere.alley.feature.spawn.internal;

import dev.revere.alley.AlleyPlugin;
import dev.revere.alley.bootstrap.AlleyContext;
import dev.revere.alley.bootstrap.annotation.Service;
import dev.revere.alley.common.PlayerUtil;
import dev.revere.alley.common.logger.Logger;
import dev.revere.alley.common.serializer.Serializer;
import dev.revere.alley.core.locale.LocaleService;
import dev.revere.alley.core.locale.internal.impl.SettingsLocaleImpl;
import dev.revere.alley.feature.spawn.SpawnService;
import lombok.Getter;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.entity.Player;

/**
 * @author Emmy
 * @project Alley
 * @date 17/05/2024 - 17:47
 */
@Getter
@Service(provides = SpawnService.class, priority = 240)
public class SpawnServiceImpl implements SpawnService {
    private final LocaleService localeService;

    private Location location;

    /**
     * DI Constructor for the SpawnServiceImpl class.
     * SpawnServiceImpl 类的依赖注入构造函数。
     *
     * @param localeService The locale service.
     *        本地化服务。
     */
    public SpawnServiceImpl(LocaleService localeService) {
        this.localeService = localeService;
    }

    @Override
    public void initialize(AlleyContext context) {
        this.reloadSpawnLocation();
    }

    @Override
    public void reloadSpawnLocation() {
        Location location = Serializer.deserializeLocation(this.localeService.getString(SettingsLocaleImpl.SERVER_SPAWN_LOCATION));
        if (location == null) {
            Logger.error("Spawn location is null.");
            return;
        }

        this.location = new Location(location.getWorld(), location.getX(), location.getY(), location.getZ(), location.getYaw(), location.getPitch());
    }

    @Override
    public void updateSpawnLocation(Location location) {
        if (location == null) return;

        this.location = location;
        this.localeService.setString(SettingsLocaleImpl.SERVER_SPAWN_LOCATION, Serializer.serializeLocation(location));
    }

    @Override
    public void teleportToSpawn(Player player) {
        if (player == null) return;

        // SparklyPaper/Moonrise may dispatch entity and death callbacks on a
        // region tick worker. Cross-world teleports and PlayerUtil.reset are
        // main-thread-only operations, so marshal the complete operation before
        // touching either Bukkit API.
        if (!Bukkit.isPrimaryThread()) {
            Bukkit.getScheduler().runTask(AlleyPlugin.getInstance(),
                    () -> teleportToSpawn(player));
            return;
        }

        if (this.location == null) {
            Logger.error("Cannot teleport " + player.getName() + " to spawn: Spawn location is not set.");
            return;
        }

        player.teleport(this.location);
        PlayerUtil.reset(player, false, true);
    }
}
