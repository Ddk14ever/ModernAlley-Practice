package dev.revere.alley.feature.command.impl.main.impl;

import dev.revere.alley.AlleyPlugin;
import dev.revere.alley.common.item.ItemBuilder;
import dev.revere.alley.common.text.CC;
import dev.revere.alley.core.config.ConfigService;
import dev.revere.alley.feature.arena.ArenaService;
import dev.revere.alley.feature.autoclick.AutoClickService;
import dev.revere.alley.feature.bot.BotService;
import dev.revere.alley.feature.challenge.ChallengeService;
import dev.revere.alley.feature.division.DivisionService;
import dev.revere.alley.feature.ffa.spawn.FFASpawnService;
import dev.revere.alley.feature.filter.FilterService;
import dev.revere.alley.feature.hotbar.HotbarService;
import dev.revere.alley.feature.item.ItemService;
import dev.revere.alley.feature.kit.KitService;
import dev.revere.alley.feature.kit.raiding.BaseRaidingService;
import dev.revere.alley.feature.leaderboard.hologram.HologramManager;
import dev.revere.alley.feature.level.LevelService;
import dev.revere.alley.feature.match.MatchService;
import dev.revere.alley.feature.queue.QueueService;
import dev.revere.alley.feature.server.ServerService;
import dev.revere.alley.feature.shop.ShopDataManager;
import dev.revere.alley.feature.spawn.SpawnService;
import dev.revere.alley.feature.title.TitleService;
import dev.revere.alley.library.menu.Button;
import dev.revere.alley.library.menu.Menu;
import org.bukkit.Material;
import org.bukkit.Sound;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.inventory.ItemStack;

import java.util.*;
import java.util.function.Consumer;
/**
 * @author Ddk1 ClaudeCode
 * @project Alley
 * @since 15/07/2026
 *
 * GUI menu for selectively reloading Alley subsystems.
 */
public final class ReloadMenu extends Menu {

    private static final List<ReloadOption> OPTIONS = new ArrayList<>();

    static {
        OPTIONS.add(new ReloadOption(Material.BOOK, "&e&lConfig Files",
                Arrays.asList("&7Re-read every YAML file from disk:", "&7settings, messages, menus, kits, arenas, bots,", "&7autoclick, shop, holograms, challenges, etc."),
                p -> reloadAllConfigFiles()));
        OPTIONS.add(new ReloadOption(Material.DIAMOND_SWORD, "&6&lQueues",
                Arrays.asList("&7Rebuild ranked/unranked/duos", "&7queues from current kits."),
                p -> AlleyPlugin.getInstance()
                        .getService(QueueService.class).reloadQueues()));
        OPTIONS.add(new ReloadOption(Material.GRASS_BLOCK, "&a&lArena Caches",
                Arrays.asList("&7Rebuild arena index maps", "&7from in-memory arena data."),
                p -> AlleyPlugin.getInstance()
                        .getService(ArenaService.class).refreshCaches()));
        OPTIONS.add(new ReloadOption(Material.STICK, "&c&lKnockback Profiles",
                Arrays.asList("&7Re-read knockback/*.yml", "&7from disk."),
                p -> AlleyPlugin.getInstance()
                        .getService(dev.revere.alley.feature.knockback.KnockbackManager.class).reloadProfiles()));
        OPTIONS.add(new ReloadOption(Material.BEACON, "&b&lLeaderboards",
                Arrays.asList("&7Full recalculation from DB.", "&cHeavy — use sparingly."),
                p -> AlleyPlugin.getInstance()
                        .getService(dev.revere.alley.feature.leaderboard.LeaderboardService.class).forceRecalculateAll()));
        // "Reload All" added last so it can reference the list safely
        OPTIONS.add(new ReloadOption(Material.NETHER_STAR, "&d&lReload All",
                Arrays.asList("&7Run all reload operations", "&7in sequence."),
                p -> OPTIONS.stream()
                        .filter(opt -> !opt.name.contains("All"))
                        .forEach(opt -> opt.action.accept(p))));
    }

    /**
     * Re-reads every YAML file the plugin uses and refreshes all services
     * that cache YAML-derived data in memory:
     * settings/messages/menus (ConfigService) + kits, arenas, bots, titles,
     * levels, divisions, hotbars, custom items, challenges, autoclick, shop,
     * holograms, filter, spawn, blocked lists.
     */
    private static void reloadAllConfigFiles() {
        AlleyPlugin plugin = AlleyPlugin.getInstance();

        // 1) File layer: re-read every registered YAML from disk.
        plugin.getService(ConfigService.class).reloadConfigs();

        // 2) Managers that keep their own YAML file.
        plugin.getService(AutoClickService.class).reloadConfiguration();
        plugin.getService(ShopDataManager.class).reload();
        plugin.getService(HologramManager.class).reload();

        // 3) Services that cache YAML-derived data in memory.
        plugin.getService(ChallengeService.class).reloadDefinitions();

        plugin.getService(KitService.class).loadKits();
        plugin.getService(BaseRaidingService.class).loadRaidingKitMappings();

        ArenaService arenaService = plugin.getService(ArenaService.class);
        arenaService.loadArenas();
        arenaService.refreshCaches();
        plugin.getService(FFASpawnService.class).loadCuboid();

        plugin.getService(BotService.class).reloadProfiles();
        plugin.getService(TitleService.class).reloadTitles();
        plugin.getService(LevelService.class).reloadLevels();
        plugin.getService(DivisionService.class).reloadDivisions();
        plugin.getService(HotbarService.class).reloadHotbars();
        plugin.getService(ItemService.class).reloadItems();
        plugin.getService(FilterService.class).reloadFilteredWords();
        plugin.getService(SpawnService.class).reloadSpawnLocation();
        plugin.getService(ServerService.class).loadBlockedCraftingItems();
        plugin.getService(MatchService.class).reloadBlockedCommands();
    }

    @Override
    public String getTitle(Player player) {
        return CC.translate("&8Alley Reload Menu");
    }

    @Override
    public Map<Integer, Button> getButtons(Player player) {
        Map<Integer, Button> buttons = new HashMap<>();

        // glass pane border (top and bottom rows)
        for (int i = 0; i < 9; i++) {
            buttons.put(i, new GlassButton());
            buttons.put(18 + i, new GlassButton());
        }
        // side borders
        buttons.put(9, new GlassButton());
        buttons.put(17, new GlassButton());

        // Place option buttons in middle row (slots 10-15)
        int slot = 10;
        for (ReloadOption option : OPTIONS) {
            buttons.put(slot++, new OptionButton(option));
        }

        return buttons;
    }

    // ---- option model ----

    private static class ReloadOption {
        final Material material;
        final String name;
        final List<String> lore;
        final Consumer<Player> action;

        ReloadOption(Material material, String name, List<String> lore, Consumer<Player> action) {
            this.material = material;
            this.name = name;
            this.lore = new ArrayList<>();
            for (String line : lore) this.lore.add(CC.translate(line));
            this.action = action;
        }
    }

    // ---- buttons ----

    private static class OptionButton extends Button {
        private final ReloadOption option;

        OptionButton(ReloadOption option) { this.option = option; }

        @Override
        public ItemStack getButtonItem(Player player) {
            return new ItemBuilder(option.material)
                    .name(CC.translate(option.name))
                    .lore(option.lore)
                    .hideMeta()
                    .build();
        }

        @Override
        public void clicked(Player player, ClickType clickType) {
            if (clickType != ClickType.LEFT) return;
            player.closeInventory();
            player.sendMessage(CC.translate("&6&lAlley &fReloading: &e" + option.name + "&f..."));
            try {
                option.action.accept(player);
                player.sendMessage(CC.translate("&6&lAlley &a" + option.name + " reloaded!"));
                player.playSound(player.getLocation(), Sound.ENTITY_PLAYER_LEVELUP, 1.0f, 1.0f);
            } catch (Exception e) {
                player.sendMessage(CC.translate("&cReload failed: " + e.getMessage()));
                e.printStackTrace();
            }
        }
    }

    private static class GlassButton extends Button {
        @Override
        public ItemStack getButtonItem(Player player) {
            return new ItemBuilder(Material.BLACK_STAINED_GLASS_PANE).name(" ").build();
        }
    }
}
