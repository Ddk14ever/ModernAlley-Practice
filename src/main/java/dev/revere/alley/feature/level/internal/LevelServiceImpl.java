package dev.revere.alley.feature.level.internal;

import dev.revere.alley.core.config.ConfigService;
import dev.revere.alley.feature.level.LevelService;
import dev.revere.alley.feature.level.data.LevelData;
import dev.revere.alley.bootstrap.AlleyContext;
import dev.revere.alley.bootstrap.annotation.Service;
import dev.revere.alley.common.text.ProgressBarUtil;
import lombok.Getter;
import org.bukkit.Material;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;

import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

/**
 * @author Emmy
 * @project Alley
 * @since 22/04/2025
 */
@Getter
@Service(provides = LevelService.class, priority = 160)
public class LevelServiceImpl implements LevelService {
    private final ConfigService configService;
    private final List<LevelData> levels = new ArrayList<>();

    /**
     * Constructor for DI.
     * 用于依赖注入的构造函数。
     */
    public LevelServiceImpl(ConfigService configService) {
        this.configService = configService;
    }

    @Override
    public void initialize(AlleyContext context) {
        this.reloadLevels();
    }

    @Override
    public List<LevelData> getLevels() {
        return Collections.unmodifiableList(this.levels);
    }

    @Override
    public void reloadLevels() {
        this.levels.clear();
        FileConfiguration config = this.configService.getLevelsConfig();
        ConfigurationSection levelsSection = config.getConfigurationSection("levels");
        if (levelsSection == null) {
            return;
        }

        for (String key : levelsSection.getKeys(false)) {
            String path = "levels." + key;
            String displayName = config.getString(path + ".display-name");

            Material material = Material.matchMaterial(config.getString(path + ".material"));
            int durability = config.getInt(path + ".durability");
            int minElo = config.getInt(path + ".min-elo");
            int maxElo = config.getInt(path + ".max-elo");

            LevelData level = new LevelData(key, displayName, material, durability, minElo, maxElo);
            this.levels.add(level);
        }

        this.levels.sort(Comparator.comparingInt(LevelData::getMinElo));
    }

    @Override
    public void createLevel(String name, int minElo, int maxElo) {
        LevelData level = new LevelData(name, "&6" + name, Material.DIAMOND_SWORD, 0, minElo, maxElo);
        this.levels.add(level);
        this.saveLevel(level);
    }

    @Override
    public void deleteLevel(LevelData level) {
        if (!this.levels.remove(level)) return;

        FileConfiguration config = this.configService.getLevelsConfig();
        File file = this.configService.getConfigFile("storage/levels.yml");

        config.set("levels." + level.getName(), null);
        this.configService.saveConfig(file, config);
    }

    @Override
    public void saveLevel(LevelData level) {
        FileConfiguration config = this.configService.getLevelsConfig();
        File file = this.configService.getConfigFile("storage/levels.yml");
        String path = "levels." + level.getName();

        config.set(path + ".display-name", level.getDisplayName());
        config.set(path + ".material", level.getMaterial().name());
        config.set(path + ".durability", level.getDurability());
        config.set(path + ".min-elo", level.getMinElo());
        config.set(path + ".max-elo", level.getMaxElo());

        this.configService.saveConfig(file, config);
    }

    @Override
    public LevelData getLevel(int elo) {
        if (this.levels.isEmpty()) {
            return null;
        }

        for (LevelData level : this.levels) {
            if (elo >= level.getMinElo() && elo <= level.getMaxElo()) {
                return level;
            }
        }

        LevelData lowestLevel = this.levels.get(0);
        if (elo < lowestLevel.getMinElo()) {
            return lowestLevel;
        }

        LevelData highestLevel = this.levels.get(this.levels.size() - 1);
        if (elo > highestLevel.getMaxElo()) {
            return highestLevel;
        }

        return null;
    }

    @Override
    public LevelData getLevel(String name) {
        return this.levels.stream()
                .filter(tier -> tier.getName().equalsIgnoreCase(name))
                .findFirst()
                .orElse(null);
    }

    @Override
    public LevelData getNextLevel(int elo) {
        LevelData current = this.getLevel(elo);
        if (current == null) {
            return null;
        }

        int index = this.levels.indexOf(current);
        if (index != -1 && index < this.levels.size() - 1) {
            return this.levels.get(index + 1);
        }
        return null;
    }

    /**
     * Helper method to determine the total ELO span for a given level's progression.
     * 用于确定给定等级晋级所需的总 ELO 跨度的辅助方法。
     * The range belongs to the current level itself: max-elo - min-elo.
     * 进度范围属于当前段位本身：max-elo - min-elo。
     */
    private int getEloRequiredForLevel(LevelData level) {
        return Math.max(0, level.getMaxElo() - level.getMinElo());
    }

    @Override
    public String getProgressBar(int elo) {
        LevelData level = this.getLevel(elo);
        if (level == null) {
            return "";
        }

        int eloForNextLevel = getEloRequiredForLevel(level);
        int currentProgress = Math.max(0, Math.min(
                elo - level.getMinElo(), eloForNextLevel));

        // Once the span is reached or there is no next level, render a full bar
        // (the old code hardcoded 11/12 which made every maxed-out level look partial).
        if (eloForNextLevel <= 0) {
            return ProgressBarUtil.generate(1, 1, 8, "■");
        }

        if (currentProgress >= eloForNextLevel) {
            return ProgressBarUtil.generate(eloForNextLevel, eloForNextLevel, 8, "■");
        }

        return ProgressBarUtil.generate(currentProgress, eloForNextLevel, 8, "■");
    }


    @Override
    public String getProgressDetails(int elo) {
        LevelData level = this.getLevel(elo);
        if (level == null) {
            return "0%";
        }

        int eloSpanForLevel = getEloRequiredForLevel(level);

        if (eloSpanForLevel <= 0) return "100%";

        int currentProgress = Math.max(0, Math.min(
                elo - level.getMinElo(), eloSpanForLevel));

        double percentage = ((double) currentProgress / eloSpanForLevel) * 100;

        int finalPercentage = (int) Math.floor(percentage);

        return Math.min(100, finalPercentage) + "%";
    }
}
