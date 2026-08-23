package dev.revere.alley.feature.match.internal;

import dev.revere.alley.AlleyPlugin;
import dev.revere.alley.bootstrap.AlleyContext;
import dev.revere.alley.bootstrap.annotation.Service;
import dev.revere.alley.common.logger.Logger;
import dev.revere.alley.core.config.ConfigService;
import dev.revere.alley.core.locale.LocaleService;
import dev.revere.alley.core.locale.internal.impl.SettingsLocaleImpl;
import dev.revere.alley.core.profile.Profile;
import dev.revere.alley.core.profile.ProfileService;
import dev.revere.alley.feature.arena.Arena;
import dev.revere.alley.feature.arena.ArenaService;
import dev.revere.alley.feature.arena.internal.types.StandAloneArena;
import dev.revere.alley.feature.kit.Kit;
import dev.revere.alley.feature.kit.setting.KitSetting;
import dev.revere.alley.feature.kit.setting.types.mode.*;
import dev.revere.alley.feature.match.Match;
import dev.revere.alley.feature.match.MatchService;
import dev.revere.alley.feature.match.combat.legacy.LegacyCombatListener;
import dev.revere.alley.feature.match.combat.legacy.LegacyCombatService;
import dev.revere.alley.feature.match.internal.types.*;
import dev.revere.alley.feature.event.skywars.SkyWarsMatch;
import dev.revere.alley.feature.match.model.GameParticipant;
import dev.revere.alley.feature.match.model.internal.MatchGamePlayer;
import dev.revere.alley.feature.queue.Queue;
import dev.revere.alley.feature.queue.QueueService;
import dev.revere.alley.feature.tournament.model.Tournament;
import lombok.Getter;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * @author Remi Ddk1
 * @project Alley
 * @date 5/21/2024
 */
@Getter
@Service(provides = MatchService.class, priority = 220)
public class MatchServiceImpl implements MatchService {
    @FunctionalInterface
    private interface MatchFactory {
        Match create(Queue queue, Kit kit, Arena arena, boolean isRanked, GameParticipant<MatchGamePlayer> pA, GameParticipant<MatchGamePlayer> pB);
    }

    private final ProfileService profileService;
    private final QueueService queueService;
    private final ConfigService configService;
    private final LocaleService localeService;

    private final List<Match> matches = new CopyOnWriteArrayList<>();
    private final List<String> blockedCommands = new ArrayList<>();
    private final Map<Class<? extends KitSetting>, MatchFactory> matchFactoryRegistry = new LinkedHashMap<>();

    private LegacyCombatService legacyCombatService;

    public LegacyCombatService getLegacyCombatService() {
        return legacyCombatService;
    }

    /**
     * DI Constructor for the MatchServiceImpl class.
     * MatchServiceImpl类的依赖注入构造函数。
     *
     * @param profileService The profile service.
     *                       玩家资料服务。
     * @param queueService   The queue service.
     *                       队列服务。
     * @param configService  The configuration service.
     *                       配置服务。
     * @param localeService  The locale service.
     *                       本地化服务。
     */
    public MatchServiceImpl(ProfileService profileService, QueueService queueService, ConfigService configService, LocaleService localeService) {
        this.profileService = profileService;
        this.queueService = queueService;
        this.configService = configService;
        this.localeService = localeService;
    }

    @Override
    public void initialize(AlleyContext context) {
        this.registerMatchFactories();
        this.reloadBlockedCommands();
        this.legacyCombatService = new LegacyCombatService(AlleyPlugin.getInstance());
        this.legacyCombatService.start();
        AlleyPlugin.getInstance().getServer().getPluginManager()
                .registerEvents(new LegacyCombatListener(this.legacyCombatService), AlleyPlugin.getInstance());
        AlleyPlugin.getInstance().getServer().getPluginManager()
                .registerEvents(new dev.revere.alley.feature.match.listener.types.GomokuListener(this.profileService),
                        AlleyPlugin.getInstance());
        AlleyPlugin.getInstance().getServer().getPluginManager()
                .registerEvents(new dev.revere.alley.feature.queue.listener.PlayAgainListener(), AlleyPlugin.getInstance());
    }

    @Override
    public void shutdown(AlleyContext context) {
        if (legacyCombatService != null) {
            legacyCombatService.stop();
        }
        if (this.matches.isEmpty()) {
            return;
        }
        Logger.info("Cleaning up " + this.matches.size() + " active matches due to server shutdown...");
        for (Match match : new ArrayList<>(this.matches)) {
            if (match instanceof GomokuPlayable gomoku) {
                gomoku.cleanupGomoku();
            }
            // Directly clean up player state without scheduling tasks
            for (var participant : match.getParticipants()) {
                for (var gp : participant.getPlayers()) {
                    org.bukkit.entity.Player player = gp.getTeamPlayer();
                    if (player != null && player.isOnline()) {
                        if (legacyCombatService != null) legacyCombatService.removeAll(player);
                        dev.revere.alley.core.profile.Profile prof = AlleyPlugin.getInstance()
                                .getService(dev.revere.alley.core.profile.ProfileService.class)
                                .getProfile(player.getUniqueId());
                        if (prof != null) {
                            prof.setState(dev.revere.alley.core.profile.enums.ProfileState.LOBBY);
                            prof.setMatch(null);
                        }
                        player.teleport(org.bukkit.Bukkit.getWorlds().get(0).getSpawnLocation());
                    }
                }
            }
        }
        this.matches.clear();
    }

    @Override
    public void addMatch(Match match) {
        if (match != null) {
            this.matches.add(match);
        }
    }

    @Override
    public void removeMatch(Match match) {
        // Clean up legacy combat mechanics for all participants
        if (legacyCombatService != null && match.getKit() != null) {
            for (GameParticipant<MatchGamePlayer> participant : match.getParticipants()) {
                for (MatchGamePlayer player : participant.getPlayers()) {
                    Player bukkitPlayer = player.getTeamPlayer();
                    if (bukkitPlayer != null) {
                        legacyCombatService.removeAll(bukkitPlayer);
                    }
                }
            }
        }
        this.matches.remove(match);
    }

    /**
     * Registers all known match types and their creation logic.
     * 注册所有已知的比赛类型及其创建逻辑。
     * To add a new gamemode, you only need to add a single line here.
     * 要添加新的游戏模式，只需在此处添加一行代码即可。
     */
    private void registerMatchFactories() {
        matchFactoryRegistry.put(KitSettingGomoku.class, GomokuMatch::new);
        matchFactoryRegistry.put(KitSettingBed.class, BedMatch::new);
        matchFactoryRegistry.put(KitSettingLives.class, LivesMatch::new);
        matchFactoryRegistry.put(KitSettingCheckpoint.class, CheckpointMatch::new);
        matchFactoryRegistry.put(KitSettingHideAndSeek.class, HideAndSeekMatch::new);
        matchFactoryRegistry.put(KitSettingStickFight.class, (q, k, ar, r, pA, pB) -> new RoundsMatch(q, k, ar, r, pA, pB, 5));
        matchFactoryRegistry.put(KitSettingRounds.class, (q, k, ar, r, pA, pB) -> new RoundsMatch(q, k, ar, r, pA, pB, 3));
    }

    @Override
    public Match createMatch(Queue queue, Kit kit, Arena arena, boolean isRanked,
                             GameParticipant<MatchGamePlayer> participantA,
                             GameParticipant<MatchGamePlayer> participantB) {
        for (Map.Entry<Class<? extends KitSetting>, MatchFactory> entry : matchFactoryRegistry.entrySet()) {
            if (kit.isSettingEnabled(entry.getKey())) {
                return entry.getValue().create(queue, kit, arena, isRanked, participantA, participantB);
            }
        }
        return new DefaultMatch(queue, kit, arena, isRanked, participantA, participantB);
    }

    @Override
    public void createAndStartMatch(Kit kit, Arena arena, GameParticipant<MatchGamePlayer> participantA, GameParticipant<MatchGamePlayer> participantB, boolean teamMatch, boolean affectStatistics, boolean isRanked) {
        Profile profileA = this.profileService.getProfile(participantA.getPlayers().get(0).getUuid());
        Profile profileB = this.profileService.getProfile(participantB.getPlayers().get(0).getUuid());
        if (profileA.getMatch() != null || profileB.getMatch() != null) {
            return;
        }

        Queue matchingQueue = this.queueService.getQueues().stream()
                .filter(queue -> queue.getKit().equals(kit))
                .findFirst()
                .orElse(null);

        Match match = createMatch(matchingQueue, kit, arena, isRanked, participantA, participantB);

        match.setTeamMatch(teamMatch);
        match.setAffectStatistics(affectStatistics);

        this.addMatch(match);
        startMatchWhenArenaReady(match);

        // Apply 1.8 legacy combat mechanics if enabled for this kit
        if (legacyCombatService != null) {
            for (GameParticipant<MatchGamePlayer> participant : java.util.List.of(participantA, participantB)) {
                for (MatchGamePlayer player : participant.getPlayers()) {
                    Player bukkitPlayer = player.getTeamPlayer();
                    if (bukkitPlayer != null) {
                        legacyCombatService.applyKit(bukkitPlayer, kit);
                    }
                }
            }
        }
    }

    @Override
    public void createAndStartMatch(Kit kit, Arena arena, List<GameParticipant<MatchGamePlayer>> participants) {
        for (GameParticipant<MatchGamePlayer> participant : participants) {
            Profile profile = this.profileService.getProfile(participant.getLeader().getUuid());
            if (profile != null && profile.getMatch() != null) {
                Logger.warn("Profile " + profile.getName() + " is already in a match. Cannot start a new match.");
                return;
            }
        }

        Queue matchingQueue = this.queueService.getQueues().stream()
                .filter(queue -> queue.getKit().equals(kit))
                .findFirst()
                .orElse(null);

        Match match = kit.isSettingEnabled(KitSettingGomoku.class)
                ? new GomokuFFAMatch(matchingQueue, kit, arena, participants)
                : new FFAMatch(matchingQueue, kit, arena, participants);
        this.addMatch(match);
        startMatchWhenArenaReady(match);

        // Apply 1.8 legacy combat mechanics if enabled for this kit
        if (legacyCombatService != null) {
            for (GameParticipant<MatchGamePlayer> participant : participants) {
                for (MatchGamePlayer player : participant.getPlayers()) {
                    Player bukkitPlayer = player.getTeamPlayer();
                    if (bukkitPlayer != null) {
                        legacyCombatService.applyKit(bukkitPlayer, kit);
                    }
                }
            }
        }
    }

    @Override
    public SkyWarsMatch createAndStartSkyWarsMatch(Kit kit, Arena arena,
                                                    List<GameParticipant<MatchGamePlayer>> participants, Kit resourceKit) {
        for (GameParticipant<MatchGamePlayer> participant : participants) {
            Profile profile = this.profileService.getProfile(participant.getLeader().getUuid());
            if (profile != null && profile.getMatch() != null) {
                Logger.warn("Profile " + profile.getName() + " is already in a match. Cannot start SkyWars.");
                return null;
            }
        }

        Queue matchingQueue = this.queueService.getQueues().stream()
                .filter(queue -> queue.getKit().equals(kit))
                .findFirst()
                .orElse(null);

        SkyWarsMatch match = new SkyWarsMatch(matchingQueue, kit, arena, participants, resourceKit);
        this.addMatch(match);
        startMatchWhenArenaReady(match);

        if (legacyCombatService != null) {
            for (GameParticipant<MatchGamePlayer> participant : participants) {
                for (MatchGamePlayer player : participant.getPlayers()) {
                    Player bukkitPlayer = player.getTeamPlayer();
                    if (bukkitPlayer != null) {
                        legacyCombatService.applyKit(bukkitPlayer, kit);
                    }
                }
            }
        }
        return match;
    }

    @Override
    public void createTournamentMatch(Tournament tournament, Kit kit, Arena arena, GameParticipant<MatchGamePlayer> participantA, GameParticipant<MatchGamePlayer> participantB) {
        Match match = createMatch(null, kit, arena, false, participantA, participantB);

        match.setTournament(tournament);
        match.setAffectStatistics(false);
        match.setTeamMatch(tournament.getTeamSize() > 1);

        tournament.addActiveMatch(match);

        this.addMatch(match);
        startMatchWhenArenaReady(match);

        // Apply 1.8 legacy combat mechanics if enabled for this kit
        if (legacyCombatService != null) {
            for (GameParticipant<MatchGamePlayer> participant : java.util.List.of(participantA, participantB)) {
                for (MatchGamePlayer player : participant.getPlayers()) {
                    Player bukkitPlayer = player.getTeamPlayer();
                    if (bukkitPlayer != null) {
                        legacyCombatService.applyKit(bukkitPlayer, kit);
                    }
                }
            }
        }
    }

    @Override
    public void reloadBlockedCommands() {
        this.blockedCommands.clear();
        List<String> blockedCommands = this.localeService.getStringListRaw(SettingsLocaleImpl.GAME_BLOCKED_COMMANDS_DURING_MATCH_LIST);
        if (blockedCommands.isEmpty()) {
            Logger.info("No blocked commands found in the configuration. Please check your settings.yml file.");
            return;
        }

        this.blockedCommands.addAll(blockedCommands);
    }

    /**
     * Starts a normal match immediately for shared arenas. Standalone arenas
     * prepare their spawn region asynchronously; the callback is completed on
     * the main thread and never blocks it with Future#get/join.
     */
    private void startMatchWhenArenaReady(Match match) {
        if (!(match.getArena() instanceof StandAloneArena standalone)) {
            match.startMatch();
            return;
        }

        // Reserve the players immediately so queue/event callers see the match
        // synchronously, while the actual inventory setup and teleport happen
        // after the priority region is ready.
        match.getParticipants().forEach(participant -> participant.getPlayers().forEach(gamePlayer -> {
            Profile profile = this.profileService.getProfile(gamePlayer.getUuid());
            if (profile != null) {
                profile.setState(dev.revere.alley.core.profile.enums.ProfileState.PLAYING);
                profile.setMatch(match);
            }
        }));

        standalone.getSpawnReadyFuture().whenComplete((ignored, throwable) ->
                Bukkit.getScheduler().runTask(AlleyPlugin.getInstance(), () -> {
                    if (throwable != null) {
                        Logger.logException("Failed to prepare standalone arena " + standalone.getName(),
                                throwable instanceof Exception exception ? exception : new Exception(throwable));
                        this.removeMatch(match);
                        clearReservedProfiles(match);
                        AlleyPlugin.getInstance().getService(ArenaService.class).deleteTemporaryArena(standalone);
                        return;
                    }

                    boolean playersReady = match.getParticipants().stream()
                            .flatMap(participant -> participant.getPlayers().stream())
                            .allMatch(gamePlayer -> {
                                Player player = gamePlayer.getTeamPlayer();
                                return player != null && player.isOnline();
                            });
                    if (!playersReady) {
                        this.removeMatch(match);
                        clearReservedProfiles(match);
                        AlleyPlugin.getInstance().getService(ArenaService.class).deleteTemporaryArena(standalone);
                        return;
                    }

                    match.startMatch();
                }));
    }

    private void clearReservedProfiles(Match match) {
        match.getParticipants().forEach(participant -> participant.getPlayers().forEach(gamePlayer -> {
            Profile profile = this.profileService.getProfile(gamePlayer.getUuid());
            if (profile != null && profile.getMatch() == match) {
                profile.setMatch(null);
                profile.setState(dev.revere.alley.core.profile.enums.ProfileState.LOBBY);
            }
        }));
    }
}
