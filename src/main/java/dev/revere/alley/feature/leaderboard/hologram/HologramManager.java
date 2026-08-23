package dev.revere.alley.feature.leaderboard.hologram;

import dev.revere.alley.AlleyPlugin;
import dev.revere.alley.bootstrap.AlleyContext;
import dev.revere.alley.bootstrap.annotation.Service;
import dev.revere.alley.feature.kit.KitService;
import dev.revere.alley.feature.leaderboard.LeaderboardType;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * @author Alley
 * @project Alley
 * @since 02/07/2025
 *
 * Manages all holograms: creation, deletion, loading, saving, and periodic updates.
 * 管理所有全息图：创建、删除、加载、保存和定期更新。
 */
@Service(provides = HologramManager.class, priority = 290)
public class HologramManager implements dev.revere.alley.bootstrap.lifecycle.Service {
    private final Map<String, Hologram> holograms = new ConcurrentHashMap<>();
    private final List<HologramRunnable> runnables = new ArrayList<>();
    private File configFile;
    private FileConfiguration config;

    @Override
    public void setup(AlleyContext context) {
        // Register protection listener
        AlleyPlugin.getInstance().getServer().getPluginManager()
                .registerEvents(new HologramListener(), AlleyPlugin.getInstance());
    }

    @Override
    public void initialize(AlleyContext context) {
        this.configFile = new File(AlleyPlugin.getInstance().getDataFolder(), "holograms.yml");
        if (!this.configFile.exists()) {
            try {
                this.configFile.createNewFile();
            } catch (IOException e) {
                AlleyPlugin.getInstance().getLogger().warning("Failed to create holograms.yml: " + e.getMessage());
            }
        }
        this.config = YamlConfiguration.loadConfiguration(this.configFile);
        this.loadAll();
    }

    @Override
    public void shutdown(AlleyContext context) {
        this.saveAll();
        for (HologramRunnable runnable : this.runnables) {
            runnable.cancel();
        }
        for (Hologram holo : this.holograms.values()) {
            holo.despawn();
        }
        this.holograms.clear();
        this.runnables.clear();
    }

    /**
     * Re-reads holograms.yml from disk and rebuilds all holograms.
     * Called by /alley reload so manually edited hologram entries take
     * effect without a server restart.
     */
    public void reload() {
        for (HologramRunnable runnable : this.runnables) {
            runnable.cancel();
        }
        this.runnables.clear();
        for (Hologram holo : this.holograms.values()) {
            holo.setEnabled(false);
            holo.despawn();
        }
        this.holograms.clear();

        this.configFile = new File(AlleyPlugin.getInstance().getDataFolder(), "holograms.yml");
        if (!this.configFile.exists()) {
            try {
                this.configFile.createNewFile();
            } catch (IOException e) {
                AlleyPlugin.getInstance().getLogger().warning("Failed to create holograms.yml: " + e.getMessage());
            }
        }
        this.config = YamlConfiguration.loadConfiguration(this.configFile);
        this.loadAll();
    }

    // ========================
    // CRUD
    // ========================

    public void createHologram(String name, Location location, String kitName, LeaderboardType type) {
        LeaderboardHologram holo = new LeaderboardHologram(name.toLowerCase(), location, kitName, type);
        this.holograms.put(name.toLowerCase(), holo);
        holo.updateContent();
        startRunnable(holo);
        saveHologram(holo);
    }

    public void deleteHologram(String name) {
        Hologram holo = this.holograms.remove(name.toLowerCase());
        if (holo != null) {
            holo.setEnabled(false);
            holo.despawn();

            // Cancel associated runnable
            this.runnables.removeIf(r -> {
                if (r.getHologram() == holo) {
                    r.cancel();
                    return true;
                }
                return false;
            });

            this.config.set("holograms." + holo.getName(), null);
            saveConfig();
        }
    }

    public Optional<Hologram> getHologram(String name) {
        return Optional.ofNullable(this.holograms.get(name.toLowerCase()));
    }

    public List<Hologram> getHolograms() {
        return new ArrayList<>(this.holograms.values());
    }

    // ========================
    // Persistence
    // ========================

    public void saveHologram(Hologram holo) {
        if (!(holo instanceof LeaderboardHologram lbHolo)) return;

        String path = "holograms." + holo.getName();
        this.config.set(path + ".enabled", holo.isEnabled());
        this.config.set(path + ".kit", lbHolo.getKitName());
        this.config.set(path + ".type", lbHolo.getLeaderboardType().name());
        this.config.set(path + ".showStat", holo.getShowStat());
        this.config.set(path + ".rotating", lbHolo.isRotatingKits());

        if (holo.getBaseLocation() != null) {
            // Save display-facing location (+2 Y offset), so loading through
            // the constructor (which subtracts 2) restores the correct Y.
            Location displayLoc = holo.getBaseLocation().clone().add(0, 2, 0);
            this.config.set(path + ".world", displayLoc.getWorld().getName());
            this.config.set(path + ".x", displayLoc.getX());
            this.config.set(path + ".y", displayLoc.getY());
            this.config.set(path + ".z", displayLoc.getZ());
        }

        saveConfig();
    }

    public void saveAll() {
        for (Hologram holo : this.holograms.values()) {
            saveHologram(holo);
        }
    }

    private void loadAll() {
        ConfigurationSection section = this.config.getConfigurationSection("holograms");
        if (section == null) return;

        for (String name : section.getKeys(false)) {
            try {
                String path = "holograms." + name;
                boolean enabled = this.config.getBoolean(path + ".enabled", true);

                String worldName = this.config.getString(path + ".world");
                double x = this.config.getDouble(path + ".x");
                double y = this.config.getDouble(path + ".y");
                double z = this.config.getDouble(path + ".z");
                World world = worldName != null ? Bukkit.getWorld(worldName) : null;

                String kitName = this.config.getString(path + ".kit");
                String typeName = this.config.getString(path + ".type", "RANKED");
                LeaderboardType type;
                try {
                    type = LeaderboardType.valueOf(typeName);
                } catch (IllegalArgumentException e) {
                    type = LeaderboardType.RANKED;
                }

                int showStat = this.config.getInt(path + ".showStat", 10);
                boolean rotating = this.config.getBoolean(path + ".rotating", false);

                LeaderboardHologram holo;
                if (world != null) {
                    Location loc = new Location(world, x, y, z);
                    holo = new LeaderboardHologram(name, loc, kitName, type);
                } else {
                    holo = new LeaderboardHologram(name);
                    holo.setKitName(kitName);
                    holo.setLeaderboardType(type);
                }

                holo.setEnabled(enabled);
                holo.setShowStat(showStat);
                holo.setRotatingKits(rotating);

                this.holograms.put(name.toLowerCase(), holo);

                if (enabled && holo.getBaseLocation() != null) {
                    holo.updateContent();
                    startRunnable(holo);
                }
            } catch (Exception e) {
                AlleyPlugin.getInstance().getLogger()
                        .warning("Failed to load hologram '" + name + "': " + e.getMessage());
            }
        }
    }

    private void saveConfig() {
        try {
            this.config.save(this.configFile);
        } catch (IOException e) {
            AlleyPlugin.getInstance().getLogger().warning("Failed to save holograms.yml: " + e.getMessage());
        }
    }

    // ========================
    // Update cycle
    // ========================

    private void startRunnable(Hologram holo) {
        HologramRunnable runnable = new HologramRunnable(holo);
        this.runnables.add(runnable);
        runnable.start();
    }

    /**
     * Gets the leaderboard data label for a specific type.
     */
    public String getStatLabel(LeaderboardType type) {
        return switch (type) {
            case RANKED -> "Elo";
            case UNRANKED, UNRANKED_MONTHLY -> "Wins";
            case WIN_STREAK -> "Streak";
            case FFA -> "Kills";
            case TOURNAMENT -> "Wins";
        };
    }
}
