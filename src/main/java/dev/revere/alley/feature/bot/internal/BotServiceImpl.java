package dev.revere.alley.feature.bot.internal;

import com.comphenix.protocol.PacketType;
import com.comphenix.protocol.ProtocolLibrary;
import com.comphenix.protocol.events.ListenerPriority;
import com.comphenix.protocol.events.PacketAdapter;
import com.comphenix.protocol.events.PacketEvent;
import com.comphenix.protocol.wrappers.EnumWrappers;
import com.mongodb.client.model.Filters;
import com.mongodb.client.model.Updates;
import dev.revere.alley.AlleyPlugin;
import dev.revere.alley.bootstrap.AlleyContext;
import dev.revere.alley.bootstrap.annotation.Service;
import dev.revere.alley.common.text.CC;
import dev.revere.alley.core.config.ConfigService;
import dev.revere.alley.core.database.internal.MongoUtility;
import dev.revere.alley.core.profile.Profile;
import dev.revere.alley.core.profile.ProfileService;
import dev.revere.alley.core.profile.enums.ProfileState;
import dev.revere.alley.feature.arena.Arena;
import dev.revere.alley.feature.arena.ArenaService;
import dev.revere.alley.feature.bot.BotDifficultyProfile;
import dev.revere.alley.feature.bot.BotAiMode;
import dev.revere.alley.feature.bot.BotService;
import dev.revere.alley.feature.bot.CustomBotProfile;
import dev.revere.alley.feature.bot.entity.NativeBotPlayer;
import dev.revere.alley.feature.bot.listener.BotMatchListener;
import dev.revere.alley.feature.bot.listener.BotCustomInputListener;
import dev.revere.alley.feature.bot.match.BotMatchSession;
import dev.revere.alley.feature.kit.Kit;
import dev.revere.alley.feature.kit.setting.types.mode.KitSettingBotQueue;
import dev.revere.alley.feature.kit.setting.types.mode.KitSettingGomoku;
import dev.revere.alley.feature.party.PartyService;
import dev.revere.alley.feature.queue.listener.PlayAgainListener;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.Bukkit;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.profile.PlayerProfile;
import org.bukkit.scheduler.BukkitTask;

import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.atomic.AtomicReference;

@Service(provides = BotService.class, priority = 230)
public class BotServiceImpl implements BotService {
    private static final String[] BOT_HUMANIZATION_KEYS = {
            "click-jitter", "misclick-chance", "aim-smoothing",
            "switch-delay-ticks", "crit-chance", "blockhit-rate"
    };
    private final Map<String, BotDifficultyProfile> profiles = new LinkedHashMap<>();
    private final Map<UUID, BotMatchSession> playerSessions = new ConcurrentHashMap<>();
    private final Map<UUID, BotMatchSession> entitySessions = new ConcurrentHashMap<>();
    private final Set<UUID> openInventories = ConcurrentHashMap.newKeySet();
    private final Set<UUID> pendingStarts = ConcurrentHashMap.newKeySet();
    private final Map<String, CompletableFuture<PlayerProfile>> skinProfiles = new ConcurrentHashMap<>();
    private final Map<UUID, BukkitTask> customSaveTasks = new ConcurrentHashMap<>();
    private PacketAdapter inventoryPacketListener;

    @Override
    public void initialize(AlleyContext context) {
        reloadProfiles();
        cleanupStaleBots();
        context.getPlugin().getServer().getScheduler().runTaskLater(
                context.getPlugin(), this::cleanupStaleBots, 40L);
        context.getPlugin().getServer().getPluginManager()
                .registerEvents(new BotMatchListener(this), context.getPlugin());
        context.getPlugin().getServer().getPluginManager()
                .registerEvents(new BotCustomInputListener(this), context.getPlugin());
        registerInventoryPacketListener();
    }

    @Override
    public void shutdown(AlleyContext context) {
        for (BotMatchSession session : new ArrayList<>(playerSessions.values())) {
            session.shutdown();
        }
        playerSessions.clear();
        entitySessions.clear();
        openInventories.clear();
        pendingStarts.clear();
        skinProfiles.clear();
        customSaveTasks.values().forEach(BukkitTask::cancel);
        customSaveTasks.clear();
        if (this.inventoryPacketListener != null) {
            ProtocolLibrary.getProtocolManager().removePacketListener(this.inventoryPacketListener);
            this.inventoryPacketListener = null;
        }
    }

    private void registerInventoryPacketListener() {
        this.inventoryPacketListener = new PacketAdapter(
                AlleyPlugin.getInstance(), ListenerPriority.MONITOR,
                PacketType.Play.Client.CLIENT_COMMAND,
                PacketType.Play.Client.WINDOW_CLICK,
                PacketType.Play.Client.CLOSE_WINDOW) {
            @Override
            public void onPacketReceiving(PacketEvent event) {
                UUID playerId = event.getPlayer().getUniqueId();
                if (!playerSessions.containsKey(playerId)) {
                    openInventories.remove(playerId);
                    return;
                }
                if (event.getPacketType() == PacketType.Play.Client.CLOSE_WINDOW) {
                    openInventories.remove(playerId);
                    return;
                }
                if (event.getPacketType() == PacketType.Play.Client.WINDOW_CLICK) {
                    openInventories.add(playerId);
                    return;
                }
                EnumWrappers.ClientCommand command = event.getPacket().getClientCommands().readSafely(0);
                if (command == EnumWrappers.ClientCommand.OPEN_INVENTORY_ACHIEVEMENT) {
                    openInventories.add(playerId);
                }
            }
        };
        ProtocolLibrary.getProtocolManager().addPacketListener(this.inventoryPacketListener);
    }

    @Override
    public void reloadProfiles() {
        profiles.clear();
        ConfigService configService = AlleyPlugin.getInstance().getService(ConfigService.class);
        FileConfiguration config = configService.getBotConfig();
        if (config == null) return;
        ConfigurationSection configuredSection = config.getConfigurationSection("profiles");
        Set<String> configuredIds = configuredSection == null
                ? Set.of() : new java.util.LinkedHashSet<>(configuredSection.getKeys(false));
        FileConfiguration defaults = null;
        try (InputStream stream = AlleyPlugin.getInstance().getResource("providers/bots.yml")) {
            if (stream != null) {
                defaults = YamlConfiguration.loadConfiguration(
                        new InputStreamReader(stream, StandardCharsets.UTF_8));
                config.setDefaults(defaults);
            }
        } catch (java.io.IOException exception) {
            AlleyPlugin.getInstance().getLogger().warning(
                    "Could not load bot configuration defaults: " + exception.getMessage());
        }

        ConfigurationSection section = config.getConfigurationSection("profiles");
        ConfigurationSection defaultSection = defaults == null
                ? null : defaults.getConfigurationSection("profiles");
        boolean migrated = false;
        if (defaultSection != null) {
            for (String id : defaultSection.getKeys(false)) {
                if (!id.equalsIgnoreCase("hacker")) continue;
                if (configuredIds.contains(id)) continue;
                ConfigurationSection source = defaultSection.getConfigurationSection(id);
                if (source == null) continue;
                for (String key : source.getKeys(false)) {
                    config.set("profiles." + id + "." + key, source.get(key));
                }
                migrated = true;
            }
        }
        // Existing server copies were created before the humanization keys existed.
        // Materialize those defaults once so they are visible and editable on disk,
        // instead of only living in the resource defaults.
        if (defaultSection != null && section != null) {
            for (String id : defaultSection.getKeys(false)) {
                ConfigurationSection target = section.getConfigurationSection(id);
                ConfigurationSection source = defaultSection.getConfigurationSection(id);
                if (target == null || source == null) continue;
                for (String key : BOT_HUMANIZATION_KEYS) {
                    if (!target.isSet(key) && source.isSet(key)) {
                        config.set("profiles." + id + "." + key, source.get(key));
                        migrated = true;
                    }
                }
            }
        }
        if (migrated) {
            configService.saveConfig(configService.getConfigFile("providers/bots.yml"), config);
            section = config.getConfigurationSection("profiles");
        }
        Set<String> profileIds = new java.util.LinkedHashSet<>();
        if (section != null) profileIds.addAll(section.getKeys(false));
        for (String id : profileIds) {
            if (id.equalsIgnoreCase("custom")) continue;
            ConfigurationSection profileSection = config.getConfigurationSection("profiles." + id);
            if (profileSection == null && defaultSection != null) {
                profileSection = defaultSection.getConfigurationSection(id);
            }
            if (profileSection != null) profiles.put(id.toLowerCase(), BotDifficultyProfile.fromConfig(id, profileSection));
        }
    }

    private void cleanupStaleBots() {
        for (Player online : List.copyOf(AlleyPlugin.getInstance().getServer().getOnlinePlayers())) {
            if (online.getScoreboardTags().contains(BotMatchSession.BOT_ENTITY_TAG)
                    && !entitySessions.containsKey(online.getUniqueId())) NativeBotPlayer.remove(online);
        }
    }

    @Override
    public Map<String, BotDifficultyProfile> getProfiles() {
        return Collections.unmodifiableMap(profiles);
    }

    @Override
    public int getActivePlayerCount() {
        return playerSessions.size();
    }

    @Override
    public BotMatchSession getSession(Player player) {
        if (player == null) return null;
        // Native bots are indexed as match entities as well as Bukkit players.
        BotMatchSession session = playerSessions.get(player.getUniqueId());
        return session != null ? session : entitySessions.get(player.getUniqueId());
    }

    @Override
    public BotMatchSession getSession(Entity entity) {
        if (entity == null) return null;
        BotMatchSession session = playerSessions.get(entity.getUniqueId());
        return session != null ? session : entitySessions.get(entity.getUniqueId());
    }

    @Override
    public boolean isKitSupported(Kit kit) {
        return kit != null && kit.isEnabled() && kit.isSettingEnabled(KitSettingBotQueue.class);
    }

    @Override
    public boolean startMatch(Player player, Kit kit, String difficultyId) {
        FileConfiguration config = AlleyPlugin.getInstance().getService(ConfigService.class).getBotConfig();
        if (config == null || !config.getBoolean("enabled", true)) {
            player.sendMessage(CC.translate("&cBot matches are disabled."));
            return false;
        }
        if (!NativeBotPlayer.isSupported()) {
            player.sendMessage(CC.translate("&c" + NativeBotPlayer.unsupportedReason()));
            return false;
        }

        Profile profile = AlleyPlugin.getInstance().getService(ProfileService.class).getProfile(player.getUniqueId());
        if (profile == null || profile.getState() != ProfileState.LOBBY
                || profile.getMatch() != null || profile.getQueueProfile() != null || getSession(player) != null
                || pendingStarts.contains(player.getUniqueId())) {
            player.sendMessage(CC.translate("&cYou cannot start a bot match right now."));
            return false;
        }
        if (AlleyPlugin.getInstance().getService(PartyService.class).getParty(player) != null) {
            player.sendMessage(CC.translate("&cLeave your party before starting a bot match."));
            return false;
        }
        if (!isKitSupported(kit)) {
            player.sendMessage(CC.translate("&cThat kit does not support bot matches."));
            return false;
        }
        boolean gomokuKit = kit.isSettingEnabled(KitSettingGomoku.class);
        if (gomokuKit != (kit.getBotAiMode() == BotAiMode.GOMOKU)) {
            player.sendMessage(CC.translate("&cThis kit has an incompatible bot AI mode. "
                    + "Gomoku kits must use GOMOKU, and other kits must not use it."));
            return false;
        }

        boolean customDifficulty = difficultyId.equalsIgnoreCase("custom");
        CustomBotProfile custom = profile.getProfileData().getCustomBotProfile();
        if (customDifficulty && custom == null) {
            custom = new CustomBotProfile();
            profile.getProfileData().setCustomBotProfile(custom);
        }
        Player nameOwner = customDifficulty ? Bukkit.getPlayerExact(custom.getName()) : null;
        if (customDifficulty && (!CustomBotProfile.isValidName(custom.getName())
                || nameOwner != null && !nameOwner.getScoreboardTags().contains(BotMatchSession.BOT_ENTITY_TAG))) {
            player.sendMessage(CC.translate("&cChoose a valid custom bot name that is not currently in use."));
            return false;
        }
        BotDifficultyProfile difficulty = customDifficulty
                ? BotDifficultyProfile.fromCustom(custom) : profiles.get(difficultyId.toLowerCase());
        if (difficulty == null) {
            player.sendMessage(CC.translate("&cThat bot difficulty does not exist."));
            return false;
        }

        if (customDifficulty && custom.getSkinName() != null && !custom.getSkinName().isBlank()) {
            if (!pendingStarts.add(player.getUniqueId())) return false;
            player.sendMessage(CC.translate("&eLoading the custom bot skin..."));
            resolveSkin(custom.getSkinName()).whenComplete((skin, throwable) ->
                    Bukkit.getScheduler().runTask(AlleyPlugin.getInstance(), () -> {
                        pendingStarts.remove(player.getUniqueId());
                        if (!player.isOnline()) return;
                        if (throwable != null || skin == null) {
                            player.sendMessage(CC.translate("&cCould not load that premium player's skin."));
                            return;
                        }
                        startPreparedMatch(player, kit, difficulty, config, skin);
                    }));
            return true;
        }
        return startPreparedMatch(player, kit, difficulty, config, null);
    }

    private boolean startPreparedMatch(Player player, Kit kit, BotDifficultyProfile difficulty,
                                       FileConfiguration config, PlayerProfile skinProfile) {
        Profile profile = AlleyPlugin.getInstance().getService(ProfileService.class).getProfile(player.getUniqueId());
        if (!player.isOnline() || profile == null || profile.getState() != ProfileState.LOBBY
                || profile.getMatch() != null || profile.getQueueProfile() != null
                || getSession(player) != null
                || AlleyPlugin.getInstance().getService(PartyService.class).getParty(player) != null) {
            if (player.isOnline()) player.sendMessage(CC.translate("&cYou cannot start a bot match right now."));
            return false;
        }

        Arena arena = AlleyPlugin.getInstance().getService(ArenaService.class).getRandomArena(kit);
        if (arena == null || arena.getPos1() == null || arena.getPos2() == null) {
            player.sendMessage(CC.translate("&cThere are no available arenas for this kit."));
            return false;
        }

        BotMatchSession session = new BotMatchSession(
                this, player, kit, arena, difficulty, config, skinProfile);
        playerSessions.put(player.getUniqueId(), session);
        boolean started;
        try {
            started = session.start();
        } catch (RuntimeException exception) {
            AlleyPlugin.getInstance().getLogger().log(java.util.logging.Level.SEVERE,
                    "Could not start a bot match for " + player.getName(), exception);
            started = false;
        }
        if (!started) {
            session.abortStart();
            player.sendMessage(CC.translate("&cThe bot match could not be started."));
            return false;
        }
        return true;
    }

    public CompletableFuture<PlayerProfile> resolveSkin(String skinName) {
        if (!CustomBotProfile.isValidName(skinName)) {
            return CompletableFuture.failedFuture(new IllegalArgumentException("Invalid premium player ID"));
        }
        String key = skinName.toLowerCase(java.util.Locale.ROOT);
        CompletableFuture<PlayerProfile> cached = skinProfiles.get(key);
        if (cached != null) return cached;

        CompletableFuture<PlayerProfile> created = Bukkit.createPlayerProfile(skinName).update()
                .thenApply(profile -> {
                    if (profile == null || profile.getTextures().getSkin() == null) {
                        throw new CompletionException(new IllegalArgumentException("Premium skin not found"));
                    }
                    return (PlayerProfile) profile;
                });
        CompletableFuture<PlayerProfile> raced = skinProfiles.putIfAbsent(key, created);
        if (raced != null) return raced;
        created.whenComplete((profile, throwable) -> {
            if (throwable != null) skinProfiles.remove(key, created);
        });
        return created;
    }

    public void queueCustomProfileSave(Profile profile) {
        if (profile == null || profile.getProfileData().getCustomBotProfile() == null) return;
        UUID playerId = profile.getUuid();
        BukkitTask previous = customSaveTasks.remove(playerId);
        if (previous != null) previous.cancel();
        org.bson.Document snapshot = MongoUtility.convertCustomBotProfile(
                profile.getProfileData().getCustomBotProfile());
        AtomicReference<BukkitTask> taskHolder = new AtomicReference<>();
        BukkitTask task = Bukkit.getScheduler().runTaskLaterAsynchronously(
                AlleyPlugin.getInstance(), () -> {
                    try {
                        AlleyPlugin.getInstance().getService(ProfileService.class).getCollection().updateOne(
                                Filters.eq("uuid", playerId.toString()),
                                Updates.set("profileData.customBotProfile", snapshot));
                    } catch (RuntimeException exception) {
                        AlleyPlugin.getInstance().getLogger().log(java.util.logging.Level.WARNING,
                                "Could not save custom bot settings for " + playerId, exception);
                    } finally {
                        customSaveTasks.remove(playerId, taskHolder.get());
                    }
                }, 10L);
        taskHolder.set(task);
        customSaveTasks.put(playerId, task);
    }

    @Override
    public void endMatch(Player player, boolean playerWon) {
        BotMatchSession session = getSession(player);
        if (session != null) session.finish(playerWon);
    }

    @Override
    public boolean leaveMatch(Player player) {
        boolean cancelledRematch = PlayAgainListener.cancelPendingBotRematch(player);
        BotMatchSession session = getSession(player);
        if (session == null || !session.getPlayerId().equals(player.getUniqueId())) return cancelledRematch;
        session.leave();
        return true;
    }

    @Override
    public boolean restartMatch(Player player, Kit kit, String difficultyId) {
        if (player == null || !player.isOnline() || kit == null
                || difficultyId == null || difficultyId.isBlank()) return false;
        BotMatchSession session = getSession(player);
        if (session != null && !session.closeForRematch()) return false;
        return startMatch(player, kit, difficultyId);
    }

    public void registerBot(BotMatchSession session, Player bot) {
        entitySessions.put(bot.getUniqueId(), session);
    }

    public void complete(BotMatchSession session) {
        if (session.getBotId() != null) entitySessions.remove(session.getBotId(), session);
        // A late callback from an old session must not alter a newer session.
        if (!playerSessions.remove(session.getPlayerId(), session)) return;
        openInventories.remove(session.getPlayerId());
    }

    public boolean isInventoryOpen(Player player) {
        return player != null && openInventories.contains(player.getUniqueId());
    }

    public void setInventoryOpen(Player player, boolean open) {
        if (player == null || !playerSessions.containsKey(player.getUniqueId())) return;
        if (open) openInventories.add(player.getUniqueId());
        else openInventories.remove(player.getUniqueId());
    }
}
