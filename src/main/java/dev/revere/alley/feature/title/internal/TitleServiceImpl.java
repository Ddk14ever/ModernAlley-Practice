package dev.revere.alley.feature.title.internal;

import dev.revere.alley.AlleyPlugin;
import dev.revere.alley.feature.kit.KitService;
import dev.revere.alley.feature.kit.Kit;
import dev.revere.alley.core.config.ConfigService;
import dev.revere.alley.bootstrap.AlleyContext;
import dev.revere.alley.bootstrap.annotation.Service;
import dev.revere.alley.feature.division.Division;
import dev.revere.alley.feature.division.DivisionService;
import dev.revere.alley.feature.title.TitleService;
import dev.revere.alley.feature.title.model.TitleRecord;
import dev.revere.alley.common.logger.Logger;
import dev.revere.alley.common.text.TextFormatter;
import dev.revere.alley.common.text.CC;
import lombok.Getter;
import org.bukkit.Bukkit;
import org.bukkit.configuration.file.FileConfiguration;

import java.io.File;
import java.util.*;

/**
 * @author Emmy
 * @project Alley
 * @since 21/04/2025
 */
@Getter
@Service(provides = TitleService.class, priority = 170)
public class TitleServiceImpl implements TitleService {
    private final ConfigService configService;
    private final KitService kitService;
    private final DivisionService divisionService;

    private final Map<Kit, TitleRecord> titles = new LinkedHashMap<>();
    private final Map<String, TitleRecord> customTitles = new LinkedHashMap<>();

    public TitleServiceImpl(ConfigService configService, KitService kitService, DivisionService divisionService) {
        this.configService = configService;
        this.kitService = kitService;
        this.divisionService = divisionService;
    }

    @Override
    public void initialize(AlleyContext context) {
        this.reloadTitles();
    }

    @Override
    public void reloadTitles() {
        FileConfiguration config = this.configService.getTitlesConfig();
        String path = "titles.";
        int missingKits = 0;
        int slotCounter = 0;

        // First pass: auto-generate defaults for missing kits (grandmaster titles, enabled by default)
        for (Kit kit : this.kitService.getKits()) {
            if (!config.contains(path + kit.getName())) {
                missingKits++;
                Division highest = this.divisionService.getHighestDivision();
                config.set(path + kit.getName() + ".prefix", this.getPrefixBasedOnHighestDivision(kit));
                config.set(path + kit.getName() + ".required", highest.getName());
                config.set(path + kit.getName() + ".enabled", true);
                config.set(path + kit.getName() + ".slot", slotCounter++);
            }
        }

        if (missingKits > 0) {
            File titlesFile = this.configService.getConfigFile("storage/titles.yml");
            this.configService.saveConfig(titlesFile, config);

            TextFormatter.centerText(
                    Arrays.asList(
                            "",
                            "&c&lINFO",
                            "&fMissing &c" + missingKits + " &fkits in titles.yml.",
                            "&fDefault grandmaster titles have been applied &a(enabled by default)&f.",
                            "&fUse &c/titlemanager &fto manage them.",
                            ""
                    ),
                    60
            ).forEach(line -> Bukkit.getConsoleSender().sendMessage(CC.translate(line)));
        }

        // Ensure ALL existing entries have enabled/slot (backward compat)
        for (Kit kit : this.kitService.getKits()) {
            if (!config.contains(path + kit.getName() + ".enabled")) {
                config.set(path + kit.getName() + ".enabled", true);
            }
            if (!config.contains(path + kit.getName() + ".slot")) {
                config.set(path + kit.getName() + ".slot", slotCounter++);
            }
        }

        // Save backward-compat additions
        File titlesFile = this.configService.getConfigFile("storage/titles.yml");
        this.configService.saveConfig(titlesFile, config);

        // Second pass: load into memory, sorted by slot
        this.titles.clear();

        List<TitleRecord> sorted = new ArrayList<>();
        for (Kit kit : this.kitService.getKits()) {
            String prefix = config.getString(path + kit.getName() + ".prefix");
            String requiredDivisionName = config.getString(path + kit.getName() + ".required");
            boolean enabled = config.getBoolean(path + kit.getName() + ".enabled", true);
            int slot = config.getInt(path + kit.getName() + ".slot", 0);
            boolean purchasable = config.getBoolean(path + kit.getName() + ".purchasable", false);

            if (prefix == null || requiredDivisionName == null) continue;

            Division requiredDivision = this.divisionService.getDivision(requiredDivisionName);
            if (requiredDivision == null) {
                Logger.error("Division " + requiredDivisionName + " for kit " + kit.getName() + " does not exist.");
            } else {
                sorted.add(new TitleRecord(kit, prefix, requiredDivision, enabled, slot, purchasable));
            }
        }

        sorted.sort(Comparator.comparingInt(TitleRecord::getSlot));

        for (TitleRecord title : sorted) {
            this.titles.put(title.getKit(), title);
        }

        // Load custom titles (not tied to kits)
        this.customTitles.clear();
        org.bukkit.configuration.ConfigurationSection customSection = config.getConfigurationSection("custom-titles");
        if (customSection != null) {
            for (String name : customSection.getKeys(false)) {
                String prefix = config.getString("custom-titles." + name + ".prefix");
                String requiredName = config.getString("custom-titles." + name + ".required", "Grandmaster");
                boolean enabled = config.getBoolean("custom-titles." + name + ".enabled", true);
                int slot = config.getInt("custom-titles." + name + ".slot", 0);
                boolean purchasable = config.getBoolean("custom-titles." + name + ".purchasable", false);

                if (prefix == null) continue;
                Division required = this.divisionService.getDivision(requiredName);
                if (required == null) required = this.divisionService.getHighestDivision();

                TitleRecord customTitle = new TitleRecord(name, prefix, required, enabled, slot, purchasable);
                this.customTitles.put(name.toLowerCase(), customTitle);
            }
        }
    }

    /**
     * Saves all titles back to the config file.
     */
    public void saveTitle(TitleRecord title) {
        FileConfiguration config = this.configService.getTitlesConfig();
        if (title.getKit() == null) {
            saveCustomTitle(title.getName(), title);
            return;
        }
        String path = "titles." + title.getKit().getName();

        config.set(path + ".prefix", title.getPrefix());
        config.set(path + ".required", title.getRequiredDivision().getName());
        config.set(path + ".enabled", title.isEnabled());
        config.set(path + ".slot", title.getSlot());
        config.set(path + ".purchasable", title.isPurchasable());

        File titlesFile = this.configService.getConfigFile("storage/titles.yml");
        this.configService.saveConfig(titlesFile, config);
    }

    /**
     * Saves all titles.
     */
    public void saveAllTitles() {
        for (TitleRecord title : this.titles.values()) {
            saveTitle(title);
        }
    }

    /**
     * Gets a title by name (checks kit titles and custom titles).
     */
    public TitleRecord getTitle(String name) {
        Kit kit = this.kitService.getKit(name);
        if (kit != null && this.titles.containsKey(kit)) {
            return this.titles.get(kit);
        }
        return this.customTitles.get(name.toLowerCase());
    }

    /**
     * Gets all titles (kit + custom) sorted by slot.
     */
    public List<TitleRecord> getSortedTitles() {
        List<TitleRecord> list = new ArrayList<>(this.titles.values());
        list.addAll(this.customTitles.values());
        list.sort(Comparator.comparingInt(TitleRecord::getSlot));
        return list;
    }

    /**
     * Creates a custom title not tied to any kit.
     */
    public void createCustomTitle(String name, String prefix) {
        Division highest = this.divisionService.getHighestDivision();
        TitleRecord title = new TitleRecord(name, prefix, highest, true,
                this.customTitles.size() + this.titles.size(), true);
        this.customTitles.put(name.toLowerCase(), title);
        saveCustomTitle(name, title);
    }

    /**
     * Deletes a custom title. System titles (tied to kits) cannot be deleted.
     * 删除自定义头衔。系统头衔（与套件绑定）无法被删除。
     *
     * @param title the title to delete
     * @return true if the title was deleted, false if it was a system title
     */
    public boolean deleteTitle(TitleRecord title) {
        if (title.getKit() != null) {
            return false;
        }

        // Resolve the map key by identity to stay safe after renames.
        String key = null;
        for (Map.Entry<String, TitleRecord> entry : this.customTitles.entrySet()) {
            if (entry.getValue() == title) {
                key = entry.getKey();
                break;
            }
        }
        if (key == null) {
            key = title.getName().toLowerCase();
        }

        this.customTitles.remove(key);

        FileConfiguration config = this.configService.getTitlesConfig();
        config.set("custom-titles." + key, null);
        File titlesFile = this.configService.getConfigFile("storage/titles.yml");
        this.configService.saveConfig(titlesFile, config);

        return true;
    }

    /**
     * Renames a custom title.
     */
    public void renameTitle(TitleRecord title, String newName) {
        FileConfiguration config = this.configService.getTitlesConfig();
        config.set("custom-titles." + title.getName().toLowerCase(), null);
        File titlesFile = this.configService.getConfigFile("storage/titles.yml");
        this.configService.saveConfig(titlesFile, config);

        this.customTitles.remove(title.getName().toLowerCase());
        this.customTitles.put(newName.toLowerCase(), title);
        saveCustomTitle(newName, title);
    }

    private void saveCustomTitle(String name, TitleRecord title) {
        FileConfiguration config = this.configService.getTitlesConfig();
        String path = "custom-titles." + name.toLowerCase();
        config.set(path + ".prefix", title.getPrefix());
        config.set(path + ".required", title.getRequiredDivision().getName());
        config.set(path + ".enabled", title.isEnabled());
        config.set(path + ".slot", title.getSlot());
        config.set(path + ".purchasable", title.isPurchasable());
        File titlesFile = this.configService.getConfigFile("storage/titles.yml");
        this.configService.saveConfig(titlesFile, config);
    }

    private boolean isKitPresentInConfig(FileConfiguration config, Kit kit) {
        return config.contains("titles." + kit.getName());
    }

    private String getPrefixBasedOnHighestDivision(Kit kit) {
        Division highestDivision = AlleyPlugin.getInstance().getService(DivisionService.class).getHighestDivision();
        return "&6&l" + kit.getName().toUpperCase() + " " + highestDivision.getName().toUpperCase();
    }
}
