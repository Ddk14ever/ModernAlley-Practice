package dev.revere.alley.feature.match;

import dev.revere.alley.AlleyPlugin;
import dev.revere.alley.feature.knockback.KnockbackManager;
import dev.revere.alley.common.ListenerUtil;
import dev.revere.alley.common.PlayerUtil;
import dev.revere.alley.common.SoundUtil;
import dev.revere.alley.common.logger.Logger;
import dev.revere.alley.common.reflect.ReflectionService;
import dev.revere.alley.common.reflect.internal.types.ActionBarReflectionServiceImpl;
import dev.revere.alley.common.reflect.internal.types.TitleReflectionServiceImpl;
import dev.revere.alley.common.text.CC;
import dev.revere.alley.common.time.TimeUtil;
import dev.revere.alley.core.locale.LocaleService;
import dev.revere.alley.core.locale.internal.impl.message.GameMessagesLocaleImpl;
import dev.revere.alley.core.profile.Profile;
import dev.revere.alley.core.profile.ProfileService;
import dev.revere.alley.core.profile.enums.ProfileState;
import dev.revere.alley.feature.arena.Arena;
import dev.revere.alley.feature.arena.ArenaService;
import dev.revere.alley.feature.arena.internal.types.StandAloneArena;
import dev.revere.alley.feature.bot.BotAiMode;
import dev.revere.alley.feature.bot.BotService;
import dev.revere.alley.feature.bot.match.BotMatchSession;
import dev.revere.alley.feature.combat.CombatService;
import dev.revere.alley.feature.challenge.ChallengeService;
import dev.revere.alley.feature.challenge.ChallengeType;
import dev.revere.alley.feature.cosmetic.CosmeticService;
import dev.revere.alley.feature.cosmetic.internal.repository.BaseCosmeticRepository;
import dev.revere.alley.feature.cosmetic.internal.repository.KillEffectRepository;
import dev.revere.alley.feature.cosmetic.internal.repository.SoundEffectRepository;
import dev.revere.alley.feature.cosmetic.internal.repository.impl.killeffect.BaseKillEffect;
import dev.revere.alley.feature.cosmetic.internal.repository.impl.killmessage.KillMessagePack;
import dev.revere.alley.feature.cosmetic.internal.repository.impl.soundeffect.BaseSoundEffect;
import dev.revere.alley.feature.cosmetic.model.CosmeticType;
import dev.revere.alley.feature.event.EventService;
import dev.revere.alley.feature.cooldown.Cooldown;
import dev.revere.alley.feature.cooldown.CooldownService;
import dev.revere.alley.feature.cooldown.CooldownType;
import dev.revere.alley.feature.hotbar.HotbarService;
import dev.revere.alley.feature.hotbar.HotbarType;
import dev.revere.alley.feature.kit.Kit;
import dev.revere.alley.feature.kit.setting.types.combat.KitSettingOldOffhand;
import dev.revere.alley.feature.kit.setting.types.mechanic.KitSettingCampProtectionImpl;
import dev.revere.alley.feature.kit.setting.types.mode.*;
import dev.revere.alley.feature.kit.setting.types.visual.KitSettingHealthBar;
import dev.revere.alley.feature.layout.LayoutService;
import dev.revere.alley.feature.luckyblock.LuckyBlockService;
import dev.revere.alley.feature.layout.data.LayoutData;
import dev.revere.alley.feature.match.data.MatchData;
import dev.revere.alley.feature.match.data.types.MatchDataSolo;
import dev.revere.alley.feature.match.internal.types.GomokuPlayable;
import dev.revere.alley.feature.match.internal.types.HideAndSeekMatch;
import dev.revere.alley.feature.match.internal.types.RoundsMatch;
import dev.revere.alley.feature.match.MatchService;
import dev.revere.alley.feature.match.internal.MatchServiceImpl;
import dev.revere.alley.feature.match.listener.MatchListener;
import dev.revere.alley.feature.match.model.GameParticipant;
import dev.revere.alley.feature.match.model.GamePlayer;
import dev.revere.alley.feature.match.model.MatchGamePlayerData;
import dev.revere.alley.feature.match.model.TeamGameParticipant;
import dev.revere.alley.feature.match.model.internal.MatchGamePlayer;
import dev.revere.alley.feature.match.snapshot.Snapshot;
import dev.revere.alley.feature.match.snapshot.SnapshotService;
import dev.revere.alley.feature.match.task.MatchTask;
import dev.revere.alley.feature.match.task.mode.PlatformDecayTask;
import dev.revere.alley.feature.match.task.other.MatchCampProtectionTask;
import dev.revere.alley.feature.match.task.other.MatchRespawnTask;
import dev.revere.alley.feature.match.utility.MatchResultFlight;
import dev.revere.alley.feature.match.utility.MatchRespawnHealth;
import dev.revere.alley.feature.music.MusicService;
import dev.revere.alley.feature.queue.Queue;
import dev.revere.alley.feature.queue.listener.PlayAgainListener;
import dev.revere.alley.feature.party.Party;
import dev.revere.alley.feature.party.PartyService;
import dev.revere.alley.feature.spawn.SpawnService;
import dev.revere.alley.feature.tournament.TournamentService;
import dev.revere.alley.feature.tournament.model.Tournament;
import dev.revere.alley.feature.visibility.VisibilityService;
import dev.revere.alley.visual.nametag.NametagService;
import lombok.Getter;
import lombok.Setter;
import net.md_5.bungee.api.chat.BaseComponent;
import org.bukkit.*;
import org.bukkit.block.Block;
import org.bukkit.block.BlockState;
import org.bukkit.entity.Player;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.scoreboard.DisplaySlot;
import org.bukkit.scoreboard.Objective;
import org.bukkit.scoreboard.Scoreboard;
import org.bukkit.util.Vector;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Abstract base class for all match types.
 * 所有比赛类型的抽象基类。
 * @author Remi
 * @project Alley
 * @date 5/21/2024
 */
@Getter
@Setter
public abstract class Match {
    protected final AlleyPlugin plugin = AlleyPlugin.getInstance();

    private final Queue queue;
    private final Kit kit;
    private final Arena arena;
    private final boolean ranked;

    protected Tournament tournament;

    private final Map<BlockState, Location> brokenBlocks = new ConcurrentHashMap<>();
    private final Map<BlockState, Location> placedBlocks = new ConcurrentHashMap<>();
    private final List<UUID> spectators = new CopyOnWriteArrayList<>();
    private final List<Snapshot> snapshots = new ArrayList<>();
    private final java.util.Set<UUID> playerWinners = java.util.concurrent.ConcurrentHashMap.newKeySet();
    /** Participants initialized through the normal Match setup pipeline. */
    private final java.util.Set<UUID> initializedPlayers = java.util.concurrent.ConcurrentHashMap.newKeySet();

    private boolean teamMatch;
    private boolean affectStatistics = true;

    private MatchTask runnable;
    private MatchState state;
    private long startTime;
    private long endTime;

    /** Bot difficulty id for a Bot duel, captured while the session still exists. */
    private String botDifficultyId;

    /**
     * Constructor for the AbstractMatch class.
     * 抽象比赛类的构造方法。
     *
     * @param queue  The queue associated with the match.
     *               与比赛关联的队列。
     * @param kit    The kit used in the match.
     *               比赛中使用的装备包。
     * @param arena  The arena where the match takes place.
     *               比赛进行的竞技场。
     * @param ranked Whether the match is ranked.
     *               比赛是否为排位赛。
     */
    public Match(Queue queue, Kit kit, Arena arena, boolean ranked) {
        this.queue = queue;
        this.kit = Objects.requireNonNull(kit, "Kit cannot be null");
        this.arena = Objects.requireNonNull(arena, "Arena cannot be null");
        this.ranked = ranked;
    }

    public boolean isPartyMultiplayerMatch() {
        List<UUID> playerIds = this.getParticipants().stream()
                .flatMap(participant -> participant.getAllPlayers().stream())
                .map(MatchGamePlayer::getUuid)
                .distinct()
                .toList();
        if (playerIds.size() < 2) {
            return false;
        }

        Party party = this.plugin.getService(PartyService.class).getPartyByMember(playerIds.get(0));
        return party != null && party.getMembers().containsAll(playerIds);
    }

    /**
     * Retrieves the list of participants in the match.
     * 获取比赛参与者列表。
     *
     * @return A list of GameParticipant objects representing the players.
     *         表示玩家的 GameParticipant 对象列表。
     */
    public abstract List<GameParticipant<MatchGamePlayer>> getParticipants();

    /**
     * Retrieves the GameParticipant associated with a given player.
     * 获取与给定玩家关联的 GameParticipant。
     *
     * @param player The player whose participant is to be retrieved.
     *               要检索其参与者的玩家。
     */
    public abstract void handleDisconnect(Player player);

    /**
     * Handles the respawn of a player based on the specific match type and its conditions.
     * 根据特定比赛类型及其条件处理玩家的重生。
     *
     * @param player The player to respawn.
     *               要重生的玩家。
     */
    public abstract void handleRespawn(Player player);

    /**
     * Determines if the round can start based on the specific match type and its conditions.
     * 根据特定比赛类型及其条件判断回合是否可以开始。
     *
     * @return True if the round can start, false otherwise.
     *         如果回合可以开始返回 true，否则返回 false。
     */
    public abstract boolean canStartRound();

    /**
     * Determines if the round can end based on the specific match type and its conditions.
     * 根据特定比赛类型及其条件判断回合是否可以结束。
     *
     * @return True if the round can end, false otherwise.
     *         如果回合可以结束返回 true，否则返回 false。
     */
    public abstract boolean canEndRound();

    /**
     * Determines if the match can end based on the specific match type and its conditions.
     * 根据特定比赛类型及其条件判断比赛是否可以结束。
     *
     * @return True if the match can end, false otherwise.
     *         如果比赛可以结束返回 true，否则返回 false。
     */
    public abstract boolean canEndMatch();

    /**
     * Handles the item drop on death for a player.
     * 处理玩家死亡时的物品掉落。
     * This method clears the drops to prevent items from being dropped on death.
     * 此方法清除掉落物，以防止死亡时掉落物品。
     *
     * @param player The player that died.
     *               死亡的玩家。
     * @param event  The PlayerDeathEvent that triggered this method.
     *               触发此方法的 PlayerDeathEvent。
     */
    public abstract void handleDeathItemDrop(Player player, PlayerDeathEvent event);

    /**
     * Defers a match transition to the primary server thread when a region
     * tick worker invokes it. Returns true when the caller must stop executing
     * its current path.
     */
    protected final boolean deferToPrimaryThread(Runnable transition) {
        if (Bukkit.isPrimaryThread()) return false;
        this.plugin.getServer().getScheduler().runTask(this.plugin, transition);
        return true;
    }

    /**
     * Starts the match by setting the state and updating player profiles and running the match task.
     * 通过设置状态、更新玩家资料并运行比赛任务来启动比赛。
     */
    public void startMatch() {
        this.sendPlayerVersusPlayerMessage();

        this.state = MatchState.STARTING;
        this.plugin.getService(LuckyBlockService.class).prepareMatch(this);

        this.handleMatchTasks();

        this.getParticipants().forEach(this::initializeParticipant);
        this.updateParticipantNametags();

        this.startTime = System.currentTimeMillis();
    }

    /**
     * Returns whether a participant was found and initialized during startMatch.
     * Bot sessions use this only as a defensive fallback for a freshly spawned NPC
     * that Citizens has not exposed through Bukkit#getEntity yet.
     */
    public boolean isPlayerInitialized(UUID uuid) {
        return uuid != null && this.initializedPlayers.contains(uuid);
    }

    /**
     * Restores legacy combat when a prior match's delayed cleanup raced a fast
     * Play Again. This runs at the real match start, after that cleanup has had
     * a chance to finish.
     */
    public void ensureLegacyCombatApplied() {
        MatchServiceImpl matchService = (MatchServiceImpl) this.plugin.getService(MatchService.class);
        if (matchService.getLegacyCombatService() == null) {
            return;
        }

        this.getParticipants().forEach(participant -> participant.getPlayers().forEach(gamePlayer -> {
            Player player = gamePlayer.getTeamPlayer();
            if (player == null) {
                return;
            }

            Profile profile = this.plugin.getService(ProfileService.class).getProfile(player.getUniqueId());
            if (profile != null && profile.getMatch() == this
                    && !matchService.getLegacyCombatService().isKitApplied(player, this.kit)) {
                matchService.getLegacyCombatService().applyKit(player, this.kit);
            }
        }));
    }

    public void endMatch() {
        this.clearPearlCooldowns();
        this.plugin.getService(LuckyBlockService.class).cleanupMatch(this);

        // Delay arena deletion by 3s so the match world stays loaded
        // while MVP music plays and players are still in it.
        org.bukkit.Bukkit.getScheduler().runTaskLater(this.plugin,
                this::deleteArenaCopyIfStandalone, 60L);

        this.getParticipants().forEach(this::finalizeParticipant);
        this.updateParticipantNametags();

        this.cleanupTasks();
        this.cleanupHealthDisplay();

        this.plugin.getService(MatchService.class).removeMatch(this);

        if (this.tournament != null) {
           this.plugin.getService(TournamentService.class).handleMatchEnd(this);
        }
        this.plugin.getService(EventService.class).handleMatchEnd(this);
    }

    private void clearPearlCooldowns() {
        CooldownService cooldownService = this.plugin.getService(CooldownService.class);

        for (GameParticipant<MatchGamePlayer> participant : this.getParticipants()) {
            for (MatchGamePlayer gamePlayer : participant.getPlayers()) {
                UUID uuid = gamePlayer.getUuid();
                Cooldown cooldown = cooldownService.getCooldown(uuid, CooldownType.ENDER_PEARL);
                if (cooldown != null) {
                    cooldown.cancelCooldown();
                }
                cooldownService.removeCooldown(uuid, CooldownType.ENDER_PEARL);

                Player player = gamePlayer.getTeamPlayer();
                if (player != null) {
                    player.setLevel(0);
                    player.setExp(0.0F);
                }
            }
        }
    }

    protected void announceMVP(long titleDelayTicks) {
        MatchGamePlayer mvp = null;
        int mostKills = 0;
        for (var p : getParticipants()) {
            for (var gp : p.getPlayers()) {
                int k = gp.getData().getKills();
                if (k > mostKills) { mostKills = k; mvp = gp; }
            }
        }
        if (mvp == null || mostKills == 0) return;

        String mvpName = mvp.getUsername();
        String musicName = "None";

        // Look up MVP's selected music for subtitle
        dev.revere.alley.feature.cosmetic.CosmeticService cs = this.plugin.getService(dev.revere.alley.feature.cosmetic.CosmeticService.class);
        dev.revere.alley.feature.cosmetic.internal.repository.MVPMusicRepository repo = cs.getRepository(
                dev.revere.alley.feature.cosmetic.model.CosmeticType.MVP_MUSIC,
                dev.revere.alley.feature.cosmetic.internal.repository.MVPMusicRepository.class);
        java.util.List<Player> onlinePlayers = new java.util.ArrayList<>();
        java.util.List<Player> musicPlayers = new java.util.ArrayList<>();
        for (var p : getParticipants()) {
            for (var gp : p.getPlayers()) {
                Player pl = gp.getTeamPlayer();
                if (pl != null) {
                    onlinePlayers.add(pl);
                    Profile recipientProfile = this.plugin.getService(ProfileService.class)
                            .getProfile(pl.getUniqueId());
                    if (recipientProfile == null
                            || recipientProfile.getProfileData().getSettingData().isMatchMvpMusicEnabled()) {
                        musicPlayers.add(pl);
                    }
                }
            }
        }

        if (mvp != null && repo != null) {
            dev.revere.alley.core.profile.Profile mvpProfile = this.plugin.getService(dev.revere.alley.core.profile.ProfileService.class).getProfile(mvp.getUuid());
            if (mvpProfile != null) {
                musicName = mvpProfile.getProfileData().getCosmeticData().getSelected(dev.revere.alley.feature.cosmetic.model.CosmeticType.MVP_MUSIC);
                if (musicName != null && !musicName.equalsIgnoreCase("None")) {
                    var music = (dev.revere.alley.feature.cosmetic.internal.repository.impl.mvpmusic.BaseMVPMusic) repo.getCosmetic(musicName);
                    if (music != null) music.play(musicPlayers);
                }
            }
        }

        String sub = musicName != null && !musicName.equalsIgnoreCase("None")
                ? CC.translate("&d" + mvpName + "'s MVP Music: &e" + musicName)
                : CC.translate("&d" + mvpName + "'s MVP Music: &7None");
        int mvpKills = mostKills;
        Runnable titleAnnouncement = () -> onlinePlayers.stream()
                .filter(Player::isOnline)
                .forEach(player -> {
                    Profile recipientProfile = this.plugin.getService(ProfileService.class)
                            .getProfile(player.getUniqueId());
                    boolean musicEnabled = recipientProfile == null
                            || recipientProfile.getProfileData().getSettingData().isMatchMvpMusicEnabled();
                    player.sendTitle(CC.translate("&6&lMVP: &e&l" + mvpName),
                            musicEnabled ? sub : "", 10, 80, 10);
                    player.sendMessage(CC.translate("&6&lMVP &7| &e" + mvpName
                            + " &7with &f" + mvpKills + " &7kills!"));
                });

        if (titleDelayTicks <= 0L) {
            titleAnnouncement.run();
        } else {
            this.plugin.getServer().getScheduler().runTaskLater(
                    this.plugin, titleAnnouncement, titleDelayTicks);
        }
    }

    private void deleteArenaCopyIfStandalone() {
        if (!(this.arena instanceof StandAloneArena)) {
            return;
        }

        ArenaService arenaService = this.plugin.getService(ArenaService.class);
        arenaService.deleteTemporaryArena((StandAloneArena) this.arena);
    }

    /**
     * Helper method to trigger a nametag update for all participants in the match.
     * 触发比赛中所有参与者名称标签更新的辅助方法。
     */
    protected final void updateParticipantNametags() {
        NametagService nametagService = this.plugin.getService(NametagService.class);

        getParticipants().forEach(participant -> {
            List<MatchGamePlayer> playersToUpdate = participant.getAllPlayers();

            playersToUpdate.stream()
                    .map(MatchGamePlayer::getTeamPlayer)
                    .filter(Objects::nonNull)
                    .forEach(nametagService::updatePlayerState);
        });
    }

    /**
     * Helper method to update the nametag of a specific participant.
     * 更新特定参与者名称标签的辅助方法。
     *
     * @param player The player to update the nametag of.
     *               要更新名称标签的玩家。
     */
    private void updateParticipantNametag(Player player) {
        NametagService nametagService = this.plugin.getService(NametagService.class);
        nametagService.updatePlayerState(player);
    }

    /**
     * Initializes a game participant and updates the player profiles.
     * 初始化游戏参与者并更新玩家资料。
     *
     * @param gameParticipant The game participant to initialize.
     *                        要初始化的游戏参与者。
     */
    private void initializeParticipant(GameParticipant<MatchGamePlayer> gameParticipant) {
        VisibilityService visibilityService = this.plugin.getService(VisibilityService.class);
        KnockbackManager knockbackManager = this.plugin.getService(KnockbackManager.class);

        gameParticipant.getPlayers().forEach(gamePlayer -> {
            Player player = gamePlayer.getTeamPlayer();
            if (player == null) {
                return;
            }

            // Reset max CPS for the new match
            dev.revere.alley.feature.cps.CPSListener.getCpsManager().resetMaxCPS(gamePlayer.getUuid());

            this.updatePlayerProfileForMatch(player);
            this.setupPlayer(player);

            visibilityService.updateVisibility(player);
            knockbackManager.applyKnockback(player, getKit());

            this.registerHealthObjectiveForPlayer(player);
            this.registerCampProtectionTask(player);
            this.initializedPlayers.add(gamePlayer.getUuid());
        });
    }

    /**
     * Finalizes a game participant and updates the player profiles.
     * 完成游戏参与者的收尾工作并更新玩家资料。
     *
     * @param gameParticipant The game participant to finalize.
     *                        要收尾的游戏参与者。
     */
    private void finalizeParticipant(GameParticipant<MatchGamePlayer> gameParticipant) {
        gameParticipant.getPlayers().stream()
                .filter(gamePlayer -> !gamePlayer.isDisconnected())
                .forEach(gamePlayer -> {
                    Player player = gamePlayer.getTeamPlayer();
                    if (player != null) {
                        // Clean up legacy combat state when match ends
                        MatchServiceImpl matchServiceImpl = (MatchServiceImpl) this.plugin.getService(MatchService.class);
                        if (matchServiceImpl.getLegacyCombatService() != null) {
                            matchServiceImpl.getLegacyCombatService().removeAll(player);
                        }
                        finalizePlayer(player);
                    }
                });
    }

    /**
     * Method to finalize a player after the match ends.
     * 比赛结束后完成玩家收尾工作的方法。
     * This method resets the player's state, updates their profile for the lobby,
     * 此方法重置玩家状态，更新其大厅资料，
     *
     * @param player The player to finalize.
     *               要收尾的玩家。
     */
    public void finalizePlayer(Player player) {
        Profile profileBeforeFinalize = this.plugin.getService(ProfileService.class).getProfile(player.getUniqueId());
        if (profileBeforeFinalize != null && profileBeforeFinalize.getMatch() != this) {
            // Play Again can release a player before the match's normal delayed
            // finalizer runs. Do not reset that player back into the old match.
            return;
        }

        VisibilityService visibilityService = this.plugin.getService(VisibilityService.class);
        MusicService musicService = this.plugin.getService(MusicService.class);
        this.plugin.getService(KnockbackManager.class).clearKnockback(player);
        musicService.stopMusic(player);
        this.updatePlayerProfileForLobby(player);

        boolean preserveResultFlight = MatchResultFlight.isPending(player);
        boolean wasFlying = player.isFlying() || player.getAllowFlight();
        this.resetPlayerState(player);
        if (wasFlying || preserveResultFlight) {
            // Keep airborne players (e.g. partyFFA spectators hovering at the arena center)
            // flying until the delayed teleport to spawn, so they don't fall out of the
            // arena and die while the MVP buffer plays out before being sent to the lobby.
            player.setAllowFlight(true);
            player.setFlying(true);
        }

        Profile profile = this.plugin.getService(ProfileService.class).getProfile(player.getUniqueId());
        Party party = this.plugin.getService(PartyService.class).getParty(player);
        profile.setParty(party);
        HotbarService hotbarService = this.plugin.getService(HotbarService.class);
        visibilityService.updateVisibility(player);
        // Bot sessions are removed during match cleanup, before this delayed
        // lobby task executes. Capture the state (including the difficulty id
        // needed to rebuild a Bot rematch paper) while the session still exists.
        final boolean botMatch = this.plugin.getService(BotService.class).getSession(player) != null;
        final String botDifficultyId = this.getBotDifficultyId();

        // Resume lobby music only after the player is back at the lobby spawn.
        org.bukkit.Bukkit.getScheduler().runTaskLater(this.plugin, () -> {
            Profile delayedProfile = this.plugin.getService(ProfileService.class).getProfile(player.getUniqueId());
            if (delayedProfile == null || delayedProfile.getMatch() != null
                    || delayedProfile.getState() == ProfileState.WAITING) {
                return;
            }
            this.teleportPlayerToSpawn(player);
            MatchResultFlight.clear(player);
            this.startLobbyMusicAfterMvp(player, musicService);

            final UUID pid = player.getUniqueId();
            try {
                Party currentParty = this.plugin.getService(PartyService.class).getParty(player);
                Profile currentProfile = this.plugin.getService(ProfileService.class).getProfile(pid);
                currentProfile.setParty(currentParty);
                hotbarService.applyHotbarItems(player,
                        currentParty != null ? HotbarType.PARTY : HotbarType.LOBBY);
                // The result-time paper (slot 0/1) was replaced by the lobby hotbar. If the
                // player did not click Play Again before returning (not queued), top the
                // paper back up into the 4th hotbar slot (3). Players with a pending
                // Play Again request (queue add runs next tick) must not receive it.
                // 结果阶段发的纸（0/1格）已被大厅hotbar取代。若玩家返回前没有点击"再来一局"
                // （未入队），将纸补发到第4格（3）。有pending"再来一局"请求的玩家（入队操作
                // 在下一tick执行）不得补发。
                // Bot matches get the same top-up, but with the rematch paper.
                if ((!botMatch || botDifficultyId != null) && currentParty == null
                        && currentProfile.getQueueProfile() == null
                        && !PlayAgainListener.isPlayAgainPending(player)) {
                    player.getInventory().setItem(3, createPlayAgainItem(botDifficultyId));
                }
            } catch (Exception ignored) {}
        }, 50L);
    }

    /**
     * Releases a player from an ending match before its normal finalizer. This
     * makes the player a real lobby player immediately, allowing the next-tick
     * Play Again queue operation to use the regular queue path.
     */
    public boolean releasePlayerForPlayAgain(Player player) {
        // Allow releasing during any ending stage (the result is broadcast while the
        // state is still ENDING_ROUND, then flips to ENDING_MATCH), so Play Again works
        // from the moment the match result appears — not only after the delayed finalizer.
        // 允许在任意结束阶段释放（结果广播时状态仍为ENDING_ROUND，随后翻转为ENDING_MATCH），
        // 使"再来一局"从比赛结果出现那一刻起即可使用。
        MatchState state = this.getState();
        if (player == null
                || (state != MatchState.ENDING_ROUND && state != MatchState.ENDING_MATCH)) {
            return false;
        }

        Profile profile = this.plugin.getService(ProfileService.class).getProfile(player.getUniqueId());
        if (profile == null || profile.getMatch() != this || profile.getGameEvent() != null) {
            return false;
        }

        if (profile.getQueueProfile() != null) {
            profile.getQueueProfile().getQueue().getProfiles().remove(profile.getQueueProfile());
            profile.setQueueProfile(null);
        }

        this.plugin.getService(MusicService.class).stopMusic(player);
        this.plugin.getService(KnockbackManager.class).clearKnockback(player);
        this.updatePlayerProfileForLobby(player);
        this.resetPlayerState(player);
        this.teleportPlayerToSpawn(player);
        MatchResultFlight.clear(player);
        this.plugin.getService(VisibilityService.class).updateVisibility(player);
        this.plugin.getService(NametagService.class).updatePlayerState(player);
        return true;
    }

    private void startLobbyMusicAfterMvp(Player player, MusicService musicService) {
        Runnable startLobbyMusic = () -> {
            Profile currentProfile = this.plugin.getService(ProfileService.class).getProfile(player.getUniqueId());
            if (player.isOnline() && currentProfile != null && currentProfile.getState() == ProfileState.LOBBY) {
                musicService.startMusic(player);
            }
        };

        long remainingTicks = dev.revere.alley.feature.cosmetic.internal.repository.impl.mvpmusic.MVPMusicSession
                .getRemainingTicks(player.getUniqueId());
        if (remainingTicks > 0L) {
            this.plugin.getServer().getScheduler().runTaskLater(
                    this.plugin, startLobbyMusic, remainingTicks + 1L);
        } else {
            startLobbyMusic.run();
        }
    }

    protected org.bukkit.inventory.ItemStack createPlayAgainItem() {
        return createPlayAgainItem(this.getBotDifficultyId());
    }

    /**
     * Builds the paper from an already captured Bot difficulty id. The delayed
     * lobby top-up runs after the Bot session is gone, so it must not look the
     * difficulty up again.
     */
    protected org.bukkit.inventory.ItemStack createPlayAgainItem(String botDifficultyId) {
        return dev.revere.alley.feature.match.utility.MatchUtility.createPlayAgainItem(
                this.getKit(), botDifficultyId);
    }

    /**
     * Returns the Bot difficulty id when this match is a Bot duel, otherwise null.
     * Used to build a Play Again paper that restarts the same Bot instead of queueing.
     */
    public String getBotDifficultyId() {
        // Cache it: the delayed lobby task runs after the Bot NPC has been removed,
        // so a live lookup would return null right when the paper is handed out.
        if (this.botDifficultyId != null) return this.botDifficultyId;

        BotService botService = this.plugin.getService(BotService.class);
        if (botService == null) return null;
        for (GameParticipant<MatchGamePlayer> participant : this.getParticipants()) {
            for (MatchGamePlayer gamePlayer : participant.getAllPlayers()) {
                Player player = gamePlayer.getTeamPlayer();
                if (player == null) continue;
                BotMatchSession session = botService.getSession(player);
                if (session == null || session.getDifficulty() == null) continue;
                // Only the computer side identifies the Bot duel; the human also has
                // a session, but getDifficulty() there would return the same value.
                if (session.getBotId() != null
                        && session.getBotId().equals(player.getUniqueId())) {
                    this.botDifficultyId = session.getDifficulty().getId();
                    return this.botDifficultyId;
                }
            }
        }
        return null;
    }

    /**
     * Registers the below-name health objective for a player if the setting is enabled.
     * 如果设置已启用，则为玩家注册名称下方血量显示目标。
     *
     * @param player The player to register the objective for.
     *               要为其注册目标的玩家。
     */
    private void registerHealthObjectiveForPlayer(Player player) {
        // HealthBar kit setting controls both the action-bar health indicator
        // (ActionBarReflectionServiceImpl.visualizeTargetHealth on hit) and the
        // below-name health objective (this method).
        if (!this.getKit().isSettingEnabled(KitSettingHealthBar.class)) {
            return;
        }

        Scoreboard scoreboard = dev.revere.alley.library.assemble.utility.PlayerScoreboards.get(player);

        Objective objective = scoreboard.getObjective("showhealth");
        if (objective == null) {
            objective = scoreboard.registerNewObjective("showhealth", "health");
        }

        objective.setDisplaySlot(DisplaySlot.BELOW_NAME);
        objective.setDisplayName(ChatColor.RED + "❤");
    }

    /**
     * Registers a camp protection task for a player if the kit setting is enabled.
     * 如果装备包设置已启用，则为玩家注册营地保护任务。
     *
     * @param player The player to register the task for.
     *               要为其注册任务的玩家。
     */
    private void registerCampProtectionTask(Player player) {
        if (!this.getKit().isSettingEnabled(KitSettingCampProtectionImpl.class)) {
            return;
        }

        MatchCampProtectionTask campProtectionTask = new MatchCampProtectionTask(player);
        campProtectionTask.runTaskTimer(this.plugin, 0L, 20L);
    }

    /**
     * Cleans up and unregisters the health objective from all match participants.
     * 清理并注销所有比赛参与者的血量目标。
     */
    private void cleanupHealthDisplay() {
        if (!this.getKit().isSettingEnabled(KitSettingHealthBar.class)) {
            return;
        }

        getParticipants().stream()
                .flatMap(participant -> participant.getPlayers().stream())
                .map(MatchGamePlayer::getTeamPlayer)
                .filter(Objects::nonNull)
                .forEach(player -> {
                    Scoreboard scoreboard = player.getScoreboard();
                    Objective objective = scoreboard.getObjective("showhealth");
                    if (objective != null) {
                        objective.unregister();
                    }
                });
    }

    private void cleanupTasks() {
        this.runnable.cancel();
    }

    /**
     * Sets up a player for the match.
     * 为比赛设置玩家。
     *
     * @param player The player to set up.
     *               要设置的玩家。
     */
    public void setupPlayer(Player player) {
        MatchGamePlayer gamePlayer = getGamePlayer(player);
        if (gamePlayer == null) {
            return;
        }

        gamePlayer.setDead(false);
        MatchListener.clearDeadPlayerPickupBlock(player);
        PlayerUtil.reset(player, true, true);
        this.giveLoadout(player, this.kit);
    }

    /**
     * Gives a loadout to a player.
     * 给予玩家装备。
     *
     * @param player The player to give the kit to.
     *               要给予装备的玩家。
     */
    public void giveLoadout(Player player, Kit kit) {
        LayoutService layoutService = this.plugin.getService(LayoutService.class);
        ProfileService profileService = this.plugin.getService(ProfileService.class);

        Profile profile = profileService.getProfile(player.getUniqueId());
        java.util.List<LayoutData> nonNullLayouts = profile.getProfileData()
                .getLayoutData().getNonNullLayouts(kit.getName());
        // Give books if the player has multiple non-null layouts for this kit
        if (nonNullLayouts.size() > 1) {
            layoutService.giveBooks(player, kit.getName());
        } else if (nonNullLayouts.size() == 1) {
            player.getInventory().setContents(nonNullLayouts.get(0).getItems());
            if (!kit.isSettingEnabled(KitSettingOldOffhand.class)) {
                player.getInventory().setItemInOffHand(nonNullLayouts.get(0).getOffhand());
            }
        } else {
            player.getInventory().setContents(kit.getItems());
            if (!kit.isSettingEnabled(KitSettingOldOffhand.class)) {
                player.getInventory().setItemInOffHand(kit.getOffhand());
            }
        }

        player.updateInventory();

        this.kit.applyPotionEffects(player);
    }

    /**
     * Handles the death of a player.
     * 处理玩家死亡。
     *
     * @param player The player that died.
     *               死亡的玩家。
     */
    public void handleDeath(Player player, EntityDamageEvent.DamageCause cause) {
        if (player == null) return;

        // SparklyPaper/Moonrise can fire damage/death callbacks on an arena
        // tick worker. The match state machine performs inventory, profile,
        // spectator and cross-world operations, all of which must run on the
        // primary server thread. Schedule the whole transition before reading
        // or mutating any match state on the worker.
        if (this.deferToPrimaryThread(() -> this.handleDeath(player, cause))) return;

        if (!(this.state == MatchState.STARTING || this.state == MatchState.RUNNING)) {
            return;
        }

        CombatService combatService = this.plugin.getService(CombatService.class);
        ProfileService profileService = this.plugin.getService(ProfileService.class);

        GameParticipant<MatchGamePlayer> participant = this.getParticipant(player);
        MatchGamePlayer gamePlayer = this.getFromAllGamePlayers(player);
        if (participant == null || gamePlayer == null) {
            return;
        }
        if (participant.isAllEliminated() && !gamePlayer.isDisconnected()) {
            return;
        }

        MatchListener.blockDeadPlayerPickup(player);

        this.handleParticipant(player, gamePlayer);

        Player killer = combatService.getLastAttacker(player);
        Profile victimProfile = profileService.getProfile(player.getUniqueId());
        Profile killerProfile = (killer != null) ? profileService.getProfile(killer.getUniqueId()) : null;

        if (!gamePlayer.isDisconnected()) {
            this.handleDeathMessages(player, killer, victimProfile, killerProfile, cause);
        }

        this.createSnapshot(player);

        player.setVelocity(new Vector());

        if (checkForConclusion(player, killer)) {
            return;
        }

        if (handleSpectator(player, victimProfile, participant)) {
            gamePlayer.setEliminated(true);
            if (killer != null) {
                this.handleDeathEffects(player, killer);
            }
            this.setupSpectatorProfile(player);
            this.plugin.getServer().getScheduler().runTaskLater(this.plugin, () -> this.addSpectator(player), 1L);
            return;
        }

        if (gamePlayer.isEliminated()) {
            return;
        }

        if (this.shouldHandleRegularRespawn(player)) {
            this.plugin.getServer().getScheduler().runTaskLater(this.plugin, () -> this.handleRespawn(player), 1L);
        }

        if (!this.shouldHandleRegularRespawn(player)) {
            this.startRespawnProcess(player);
        }
    }

    /**
     * Checks if the match has reached a conclusion (round end or match end) and handles it accordingly.
     * 检查比赛是否已到达终点（回合结束或比赛结束）并进行相应处理。
     * This is the centralized method to determine if the match should end based on the current state and conditions.
     * 这是根据当前状态和条件判断比赛是否应该结束的集中方法。
     *
     * @param victim The player who may have triggered the conclusion (can be null).
     *               可能触发终点的玩家（可为 null）。
     * @param killer The killer involved in the conclusion (can be null).
     *               参与终点的击杀者（可为 null）。
     * @return true if a conclusion is reached, false otherwise.
     *         如果到达终点返回 true，否则返回 false。
     */
    public boolean checkForConclusion(Player victim, Player killer) {
        if (!this.canEndRound()) {
            return false;
        }

        boolean finalHit = victim != null && killer != null && this.willEndMatchAfterRoundEnd(victim);
        if (finalHit) {
            this.applySwingSlowly(killer);
        }

        this.state = MatchState.ENDING_ROUND;
        if (this.runnable != null) {
            this.runnable.setStage(4);
        }

        this.handleRoundEnd();

        if (this.canEndMatch()) {
            if (victim != null && killer != null) {
                this.handleDeathEffects(victim, killer);
                if (!finalHit) {
                    this.applySwingSlowly(killer);
                }
            }

            this.state = MatchState.ENDING_MATCH;

            // Delay both result papers by two additional ticks (2 -> 4).
            final Player winner = killer;
            final Player loser = victim;
            org.bukkit.Bukkit.getScheduler().runTaskLater(this.plugin, () -> {
                giveResultPlayAgainItem(winner, 1);
                giveResultPlayAgainItem(loser, 0);
            }, 4L);
        }

        return true;
    }

    /**
     * Determines whether the current round-ending hit will also end the match.
     * Most match types already expose this through {@link #canEndMatch()}, while
     * round-based modes can override it because their score is awarded during
     * round settlement.
     */
    protected boolean willEndMatchAfterRoundEnd(Player victim) {
        return this.canEndMatch();
    }

    protected void applySwingSlowly(Player killer) {
        Profile profile = this.plugin.getService(ProfileService.class).getProfile(killer.getUniqueId());
        if (profile == null || !profile.getProfileData().getSettingData().isSwingSlowlyEnabled()) {
            return;
        }

        // Bukkit amplifiers are zero-based: amplifier 237 is Mining Fatigue 238.
        killer.addPotionEffect(new PotionEffect(PotionEffectType.MINING_FATIGUE, 60, 237, false, false, false), true);
    }

    protected void giveResultPlayAgainItem(Player player, int slot) {
        if (player == null || !player.isOnline()) return;

        Profile profile = this.plugin.getService(ProfileService.class).getProfile(player.getUniqueId());
        if (profile == null || profile.getMatch() != this
                || profile.getState() != ProfileState.PLAYING
                || profile.getQueueProfile() != null
                || PlayAgainListener.isPlayAgainPending(player)) {
            return;
        }

        player.getInventory().setItem(slot, createPlayAgainItem());
    }

    /**
     * Handles the player becoming a spectator based on the match state and kit settings.
     * 根据比赛状态和装备包设置处理玩家变为观战者的情况。
     *
     * @param player      The player to handle.
     *                    要处理的玩家。
     * @param profile     The profile of the player.
     *                    玩家的资料。
     * @param participant The participant of the match.
     *                    比赛的参与者。
     */
    protected boolean handleSpectator(Player player, Profile profile, GameParticipant<MatchGamePlayer> participant) {
        Kit matchKit = profile.getMatch().getKit();

        MatchGamePlayer gamePlayer = this.getFromAllGamePlayers(player);
        if (gamePlayer.isDisconnected()) {
            return false;
        }

        if (this.shouldSpectateEliminationKit(participant, matchKit, gamePlayer)) {
            return true;
        }

        return shouldBecomeSpectatorForNonRoundKit(participant, matchKit);
    }

    /**
     * True when this player is out (eliminated or entire side is out on elimination kits), e.g. bed broken or out of lives.
     * 当此玩家出局时返回 true（已被淘汰，或在淘汰制装备包下整队出局），例如床被摧毁或用完生命数。
     */
    private boolean shouldSpectateEliminationKit(GameParticipant<MatchGamePlayer> participant, Kit matchKit, MatchGamePlayer gamePlayer) {
        if (!this.hasEliminationBasedKit(matchKit)) {
            return false;
        }
        return gamePlayer.isEliminated() || participant.isAllEliminated();
    }

    /**
     * Default-style matches: any death removes you from play (spectator) while teammates can still fight.
     * 默认模式比赛：任何死亡都会将你移出游戏（变为观战者），而队友仍可继续战斗。
     * Round-based and elimination-kit modes use respawns / delayed elimination instead.
     * 回合制和淘汰装备包模式则使用重生/延迟淘汰机制。
     */
    private boolean shouldBecomeSpectatorForNonRoundKit(GameParticipant<MatchGamePlayer> participant, Kit matchKit) {
        return !participant.isAllDead() && !this.isRoundBasedKit(matchKit) && !this.hasEliminationBasedKit(matchKit);
    }

    /**
     * Checks if the kit is elimination-based.
     * 检查装备包是否为淘汰制。
     *
     * @param kit The kit to check.
     *            要检查的装备包。
     * @return True if the kit is elimination-based, false otherwise.
     *         如果装备包是淘汰制的返回 true，否则返回 false。
     */
    private boolean hasEliminationBasedKit(Kit kit) {
        return kit.isSettingEnabled(KitSettingBed.class) || kit.isSettingEnabled(KitSettingCheckpoint.class) || kit.isSettingEnabled(KitSettingLives.class);
    }

    /**
     * Checks if the kit is round-based.
     * 检查装备包是否为回合制。
     *
     * @param kit The kit to check.
     *            要检查的装备包。
     * @return True if the kit is round-based, false otherwise.
     *         如果装备包是回合制的返回 true，否则返回 false。
     */
    private boolean isRoundBasedKit(Kit kit) {
        return kit.isSettingEnabled(KitSettingStickFight.class) || kit.isSettingEnabled(KitSettingRounds.class);
    }

    /**
     * This method checks for the presence of a killer and their selected kill message pack,
     * 此方法检查是否存在击杀者及其选择的击杀消息包，
     * and sends a customized death message if applicable. If no custom message is found,
     * 并在适用时发送自定义死亡消息。如果没有找到自定义消息，
     * it falls back to default death messages.
     * 则回退到默认死亡消息。
     *
     * @param victim        The player who died.
     *                      死亡的玩家。
     * @param killer        The player who got the kill (can be null).
     *                      获得击杀的玩家（可为 null）。
     * @param victimProfile The profile of the victim.
     *                      受害者的资料。
     * @param killerProfile The profile of the killer (can be null).
     *                      击杀者的资料（可为 null）。
     * @param cause         The cause of the damage that led to death.
     *                      导致死亡的伤害原因。
     */
    private void handleDeathMessages(Player victim, Player killer, Profile victimProfile, Profile killerProfile, EntityDamageEvent.DamageCause cause) {
        if (killer == null || killerProfile == null) {
            this.handleDefaultDeathMessages(victim, null, victimProfile);
            return;
        }

        String selectedPackName = killerProfile.getProfileData().getCosmeticData().getSelected(CosmeticType.KILL_MESSAGE);

        if (selectedPackName == null || selectedPackName.equalsIgnoreCase("None")) {
            this.handleDefaultDeathMessages(victim, killer, victimProfile);
            return;
        }

        CosmeticService cosmeticRepository = this.plugin.getService(CosmeticService.class);
        BaseCosmeticRepository<?> repository = cosmeticRepository.getRepository(CosmeticType.KILL_MESSAGE);
        KillMessagePack pack = (KillMessagePack) repository.getCosmetic(selectedPackName);

        if (pack == null) {
            this.handleDefaultDeathMessages(victim, killer, victimProfile);
            return;
        }

        String messageTemplate = pack.getRandomMessage(cause);

        if (messageTemplate != null) {
            String finalMessage = messageTemplate.replace("{victim}", victimProfile.getNameColor() + victim.getName() + "&f");
            finalMessage = finalMessage.replace("{killer}", killerProfile.getNameColor() + killer.getName() + "&f");

            this.notifyAll(this.plugin.getService(LocaleService.class).getString(GameMessagesLocaleImpl.MATCH_DEATH_MESSAGE_CUSTOM).replace("{message}", CC.translate(finalMessage)));
            this.processKillerStatActions(killer);
        } else {
            this.handleDefaultDeathMessages(victim, killer, victimProfile);
        }
    }

    /**
     * Sends the configured death message and records the kill through the regular match path.
     */
    public void announceDeath(Player victim, Player killer, EntityDamageEvent.DamageCause cause) {
        ProfileService profileService = this.plugin.getService(ProfileService.class);
        Profile victimProfile = profileService.getProfile(victim.getUniqueId());
        Profile killerProfile = killer == null ? null : profileService.getProfile(killer.getUniqueId());
        if (victimProfile == null) return;
        this.handleDeathMessages(victim, killer, victimProfile, killerProfile, cause);
    }

    /**
     * Handles sending default death messages when no custom kill message is applicable.
     * 当没有适用的自定义击杀消息时，处理发送默认死亡消息。
     *
     * @param victim        The player who died.
     *                      死亡的玩家。
     * @param killer        The player who got the kill (can be null).
     *                      获得击杀的玩家（可为 null）。
     * @param victimProfile The profile of the victim.
     *                      受害者的资料。
     */
    private void handleDefaultDeathMessages(Player victim, Player killer, Profile victimProfile) {
        if (killer == null) {
            this.notifyAll(CC.translate(this.plugin.getService(LocaleService.class).getString(GameMessagesLocaleImpl.MATCH_DEATH_MESSAGE_GENERIC)
                    .replace("{player}", victimProfile.getName())
                    .replace("{name-color}", String.valueOf(victimProfile.getNameColor())))
            );
        } else {
            this.processKillerActions(victim, killer, victimProfile);
        }
    }

    /**
     * Processes actions related to the killer when a player is killed.
     * 当玩家被击杀时，处理与击杀者相关的操作。
     *
     * @param victim        The player who died.
     *                      死亡的玩家。
     * @param killer        The player who got the kill.
     *                      获得击杀的玩家。
     * @param victimProfile The profile of the victim.
     *                      受害者的资料。
     */
    private void processKillerActions(Player victim, Player killer, Profile victimProfile) {
        this.processKillerStatActions(killer);

        ProfileService profileService = this.plugin.getService(ProfileService.class);
        ReflectionService reflectionService = this.plugin.getService(ReflectionService.class);

        Profile killerProfile = profileService.getProfile(killer.getUniqueId());

        reflectionService.getReflectionService(ActionBarReflectionServiceImpl.class).sendDeathMessage(killer, victim);

        this.notifyAll(this.plugin.getService(LocaleService.class).getString(GameMessagesLocaleImpl.MATCH_DEATH_MESSAGE_GENERIC_KILLER)
                .replace("{victim}", victimProfile.getNameColor() + victim.getName() + "&f")
                .replace("{killer}", killerProfile.getNameColor() + killer.getName() + "&f")
                .replace("{name-color}", String.valueOf(victimProfile.getNameColor()))
                .replace("{killer-name-color}", String.valueOf(killerProfile.getNameColor()))
        );
    }

    /**
     * Processes stat actions for the killer when they get a kill.
     * 当击杀者获得击杀时，处理其统计数据更新。
     *
     * @param killer The player who got the kill.
     *               获得击杀的玩家。
     */
    private void processKillerStatActions(Player killer) {
        GameParticipant<MatchGamePlayer> killerParticipant = getParticipant(killer);
        if (killerParticipant != null) {
            MatchGamePlayer killerGamePlayer = getFromAllGamePlayers(killer);
            if (killerGamePlayer != null) {
                killerGamePlayer.getData().incrementKills();
            } else {
                killerParticipant.getLeader().getData().incrementKills();
            }

            if (this.isAffectStatistics() && !this.isTeamMatch()) {
                Profile killerProfile = this.plugin.getService(ProfileService.class).getProfile(killer.getUniqueId());
                this.plugin.getService(ChallengeService.class)
                        .recordProgress(killerProfile, ChallengeType.KILLS, 1);
            }
        }
    }

    /**
     * Handles applying all relevant on-kill cosmetic effects.
     * 处理应用所有相关的击杀装饰效果。
     * This is called when a player is confirmed to be eliminated from the match.
     * 当玩家确认从比赛中被淘汰时调用此方法。
     *
     * @param player The player who died (the victim).
     *               死亡的玩家（受害者）。
     * @param killer The player who got the kill.
     *               获得击杀的玩家。
     */
    private void handleDeathEffects(Player player, Player killer) {
        ProfileService profileService = this.plugin.getService(ProfileService.class);
        Profile profile = profileService.getProfile(killer.getUniqueId());

        String selectedKillEffectName = profile.getProfileData().getCosmeticData().getSelectedKillEffect();
        String selectedSoundEffectName = profile.getProfileData().getCosmeticData().getSelectedSoundEffect();

        this.applyCosmetic(CosmeticType.KILL_EFFECT, selectedKillEffectName, player);
        this.applyCosmetic(CosmeticType.SOUND_EFFECT, selectedSoundEffectName, killer);
    }

    /**
     * Plays the same death cosmetics used by regular match conclusions.
     */
    public void playDeathCosmetics(Player victim, Player killer) {
        if (victim == null || killer == null) return;
        this.handleDeathEffects(victim, killer);
    }

    /**
     * Applies a selected cosmetic to a target player in a generic, type-safe way.
     * 以通用、类型安全的方式将选定的装饰品应用到目标玩家。
     * This method is now updated to use the enum-based repository system.
     * 此方法现已更新为使用基于枚举的仓库系统。
     *
     * @param cosmeticType The type of cosmetic to apply.
     *                     要应用的装饰品类型。
     * @param cosmeticName The name of the cosmetic selected by the player.
     *                     玩家选择的装饰品名称。
     * @param targetPlayer The player to apply the effect to (e.g., the victim or the killer).
     *                     要应用效果的玩家（如受害者或击杀者）。
     */
    private void applyCosmetic(CosmeticType cosmeticType, String cosmeticName, Player targetPlayer) {
        if (cosmeticName == null || cosmeticName.equalsIgnoreCase("None")) {
            return;
        }

        CosmeticService cosmeticService = this.plugin.getService(CosmeticService.class);
        if (cosmeticService == null) {
            return;
        }

        switch (cosmeticType) {
            case KILL_EFFECT:
                KillEffectRepository killEffectRepository = cosmeticService.getRepository(CosmeticType.KILL_EFFECT, KillEffectRepository.class);
                if (killEffectRepository == null) {
                    return;
                }

                BaseKillEffect killEffect = killEffectRepository.getCosmetic(cosmeticName);
                if (killEffect == null) {
                    return;
                }

                killEffect.execute(targetPlayer);
                break;

            case SOUND_EFFECT:
                SoundEffectRepository soundEffectRepository = cosmeticService.getRepository(CosmeticType.SOUND_EFFECT, SoundEffectRepository.class);
                if (soundEffectRepository == null) {
                    return;
                }

                BaseSoundEffect soundEffect = soundEffectRepository.getCosmetic(cosmeticName);
                if (soundEffect == null) {
                    return;
                }

                soundEffect.execute(targetPlayer);
                break;

            default:
                Logger.warn("Cosmetic type " + cosmeticType.name() + " does not support execution");
                break;
        }
    }

    /**
     * Handles the start of a round.
     * 处理回合开始。
     */
    public void handleRoundStart() {
        if (this instanceof RoundsMatch && ((RoundsMatch) this).getCurrentRound() > 0) {
            return;
        }
        this.startTime = System.currentTimeMillis();
    }

    /**
     * Handles the end of a round.
     * 处理回合结束。
     */
    public void handleRoundEnd() {
        this.endTime = System.currentTimeMillis();

        this.handleMatchHistoryData();

        this.getParticipants().forEach(
                participant -> participant.getAllPlayers().forEach(gamePlayer -> {
                    Player player = gamePlayer.getTeamPlayer();
                    if (player != null) {
                        this.createSnapshot(player);
                    }
                })
        );

        SnapshotService snapshotRepository = this.plugin.getService(SnapshotService.class);
        this.snapshots.forEach(snapshotRepository::addSnapshot);
    }

    private void handleMatchHistoryData() {
        if (this.isTeamMatch()) return; //TODO: either handle this case too or we're just not storing team match history
        // 待办：要么也处理这种情况，要么我们就不存储团队比赛历史

        this.getParticipants().forEach(gameParticipant -> gameParticipant.getAllPlayers().forEach(gamePlayer -> {
            Player player = gamePlayer.getTeamPlayer();
            if (player == null) return;

            Profile profile = this.plugin.getService(ProfileService.class).getProfile(player.getUniqueId());

            UUID winnerID;
            UUID loserID;

            if (gamePlayer.isDead()) {
                winnerID = this.getOpponent(player).getLeader().getUuid();
                loserID = gamePlayer.getUuid();
            } else {
                winnerID = gamePlayer.getUuid();
                loserID = this.getOpponent(player).getLeader().getUuid();
                playerWinners.add(gamePlayer.getUuid());
            }

            String arenaName;
            if (this.arena instanceof StandAloneArena) {
                arenaName = ((StandAloneArena) this.arena).getOriginalArenaName();
            } else {
                arenaName = this.arena.getName();
            }

            MatchData matchData = new MatchDataSolo(
                    this.getKit().getName(),
                    arenaName,
                    winnerID,
                    loserID
            );

            if (this.isRanked()) {
                matchData.setRanked(true);
            }

            profile.getProfileData().getPreviousMatches().add(matchData);
        }));
    }

    /**
     * Creates a snapshot of the current match state for a player.
     * 为玩家创建当前比赛状态的快照。
     * This method captures various statistics and the opponent's UUID.
     * 此方法捕获各种统计数据以及对手的 UUID。
     *
     * @param player The player for whom to create the snapshot.
     *               要为其创建快照的玩家。
     */
    public void createSnapshot(Player player) {
        if (this.snapshots.stream().anyMatch(snapshot -> snapshot.getUuid().equals(player.getUniqueId()))) {
            return;
        }

        MatchGamePlayer gamePlayer = this.getGamePlayer(player);
        if (gamePlayer == null || gamePlayer.isDisconnected()) {
            return;
        }

        Snapshot snapshot = new Snapshot(player, !gamePlayer.isDead());
        snapshot.setOpponent(this.getOpponent(player).getLeader().getUuid());
        snapshot.setLongestCombo(gamePlayer.getData().getLongestCombo());
        snapshot.setTotalHits(gamePlayer.getData().getHits());
        snapshot.setThrownPotions(gamePlayer.getData().getThrownPotions());
        snapshot.setMissedPotions(gamePlayer.getData().getMissedPotions());
        snapshot.setCriticalHits(gamePlayer.getData().getCriticalHits());
        snapshot.setBlockedHits(gamePlayer.getData().getBlockedHits());
        snapshot.setWTapAttempts(gamePlayer.getData().getWTapAttempts());
        snapshot.setWTapSuccesses(gamePlayer.getData().getWTapSuccesses());
        snapshot.setRegen(gamePlayer.getData().getRegen());

        // Highest CPS uses the real-time session max (reset at match start); average combat CPS
        // has been removed.
        int highestCombatCps = dev.revere.alley.feature.cps.CPSListener.getCpsManager()
                .getMaxCPS(player.getUniqueId());
        // A native Bot has no client, so its ARM_ANIMATION packets never reach
        // CPSManager and the session max stays 0. Use the CPS configured for
        // that Bot (the value its click cadence is built from) instead.
        BotService botService = this.plugin.getService(BotService.class);
        BotMatchSession session = botService == null ? null : botService.getSession(player);
        if (session != null
                && session.getAiMode() != BotAiMode.GOMOKU
                && player.getUniqueId().equals(session.getBotId())) {
            highestCombatCps = Math.max(highestCombatCps,
                    (int) Math.round(session.getDifficulty().getCps()));
        }
        snapshot.setHighestCombatCps(highestCombatCps);

        this.snapshots.add(snapshot);
    }

    /**
     * Handles the revival of a player.
     * 处理玩家的复活。
     *
     * @param player The player to revive.
     *               要复活的玩家。
     * @param silent Whether the revived message should be sent to the player.
     *               是否向玩家发送复活消息。
     */
    public void revivePlayer(Player player, boolean silent) {
        if (player == null) return;

        MatchGamePlayer gamePlayer = this.getFromAllGamePlayers(player);
        if (gamePlayer == null || gamePlayer.isDisconnected()) {
            return;
        }

        Profile profile = this.plugin.getService(ProfileService.class).getProfile(player.getUniqueId());
        boolean wasSpectatingEliminatedParticipant = profile.getState() == ProfileState.SPECTATING && this.getSpectators().contains(player.getUniqueId());
        if (!gamePlayer.isEliminated() && !wasSpectatingEliminatedParticipant) {
            return;
        }

        gamePlayer.setEliminated(false);
        gamePlayer.setDead(false);
        profile.setState(ProfileState.PLAYING);
        if (wasSpectatingEliminatedParticipant) {
            this.getSpectators().remove(player.getUniqueId());
        }

        player.setGameMode(GameMode.SURVIVAL);
        player.setAllowFlight(false);
        player.setFlying(false);


        this.registerHealthObjectiveForPlayer(player);
        this.setupPlayer(player);

        this.plugin.getService(NametagService.class).updatePlayerState(player);
        this.plugin.getService(KnockbackManager.class).applyKnockback(player, getKit());
        this.plugin.getService(VisibilityService.class).updateVisibility(player);

        if (!silent) {
            notifyAll(CC.translate("&a" + player.getName() + " &ahas been revived."));
        }
    }

    /**
     * Pulls a lobby player into this match, joining the same team as the target participant.
     * 将大厅玩家拉入此比赛，加入与目标参与者相同的队伍。
     * If {@code newTeam} is true (FFA-only), a brand-new participant is created instead.
     * 如果 {@code newTeam} 为 true（仅限 FFA），则改为创建全新的参与者。
     *
     * @param player  The player to pull in. Must be in {@link ProfileState#LOBBY}.
     *                要拉入的玩家。必须处于 {@link ProfileState#LOBBY} 状态。
     * @param target  A player already in this match whose team the pulled player will join.
     *                已在比赛中的玩家，被拉入的玩家将加入其队伍。
     * @param newTeam If true, create a new participant for the player (only valid for FFA matches).
     *                如果为 true，则为玩家创建新的参与者（仅对 FFA 比赛有效）。
     * @return true if the pull succeeded, false if any guard condition was not met.
     *         如果拉入成功返回 true，如果有任何保护条件未满足则返回 false。
     */
    public boolean pullPlayerIntoMatch(Player player, Player target, boolean newTeam) {
        if (player == null || target == null) return false;
        if (this.getState() == MatchState.ENDING_MATCH || this.getState() == MatchState.ENDING_ROUND) return false;
        if (newTeam && this.rejectsNewTeamPull()) return false;

        Profile playerProfile = this.plugin.getService(ProfileService.class).getProfile(player.getUniqueId());
        if (playerProfile == null || playerProfile.getState() != ProfileState.LOBBY) return false;

        if (this.getFromAllGamePlayers(player) != null || this.spectators.contains(player.getUniqueId())) return false;

        GameParticipant<MatchGamePlayer> targetParticipant = this.getParticipants().stream()
                .filter(p -> p.containsPlayer(target.getUniqueId()))
                .findFirst()
                .orElse(null);
        if (targetParticipant == null) return false;

        MatchGamePlayer newGamePlayer = new MatchGamePlayer(player.getUniqueId(), player.getName());

        if (newTeam) {
            this.getParticipants().add(new GameParticipant<>(newGamePlayer));
        } else if (targetParticipant instanceof TeamGameParticipant) {
            targetParticipant.addPlayer(newGamePlayer);
        } else {
            TeamGameParticipant<MatchGamePlayer> upgraded = new TeamGameParticipant<>(targetParticipant.getLeader());
            upgraded.addPlayer(newGamePlayer);
            this.replaceParticipant(targetParticipant, upgraded);
        }

        playerProfile.setState(ProfileState.PLAYING);
        playerProfile.setMatch(this);

        this.setupPlayer(player);
        this.registerHealthObjectiveForPlayer(player);
        this.plugin.getService(VisibilityService.class).updateVisibility(player);
        this.plugin.getService(KnockbackManager.class).applyKnockback(player, getKit());
        this.plugin.getService(NametagService.class).updatePlayerState(player);

        return true;
    }

    /**
     * Replaces a solo {@link GameParticipant} with a {@link TeamGameParticipant} that holds the same
     * 将单人 {@link GameParticipant} 替换为持有相同 leader 的 {@link TeamGameParticipant}
     * leader plus any newly added players. Subclasses must override this if they store participants
     * 以及任何新添加的玩家。如果子类将参与者存储在命名字段中
     * in named fields (e.g. {@code participantA}/{@code participantB}).
     * （如 {@code participantA}/{@code participantB}），则必须重写此方法。
     *
     * @param old         The participant to replace.
     *                    要替换的参与者。
     * @param replacement The upgraded team participant.
     *                    升级后的团队参与者。
     */
    protected void replaceParticipant(GameParticipant<MatchGamePlayer> old, TeamGameParticipant<MatchGamePlayer> replacement) {
        // no-op by default; overridden by DefaultMatch and FFAMatch
        // 默认不执行操作；由 DefaultMatch 和 FFAMatch 重写
    }

    /**
     * Returns whether this match type rejects creating a brand-new participant via
     * 返回此比赛类型是否拒绝通过 {@link #pullPlayerIntoMatch} 在 {@code newTeam} 为 {@code true} 时
     * {@link #pullPlayerIntoMatch} when {@code newTeam} is {@code true}.
     * 创建全新的参与者。
     * Returns {@code false} only in FFA matches.
     * 仅在 FFA 比赛中返回 {@code false}。
     *
     * @return true if a new team pull is not permitted for this match type.
     *         如果此比赛类型不允许新建队伍拉入，则返回 true。
     */
    public boolean rejectsNewTeamPull() {
        return true;
    }

    /**
     * Adds a player to the list of spectators.
     * 将玩家添加到观战者列表。
     *
     * @param player The player to add.
     *               要添加的玩家。
     */
    public void addSpectator(Player player) {
        if (this.getGamePlayer(player) == null) {
            if (this.getState() == MatchState.ENDING_MATCH) {
                player.sendMessage(CC.translate("&cThis match has already ended."));
                return;
            }

            if (!this.allowsSpectators()) {
                player.sendMessage(CC.translate("&cOne or more players have spectators disabled."));
                return;
            }

            this.setupSpectatorProfile(player);
            this.spectators.add(player.getUniqueId());
        }

        NametagService nametagService = this.plugin.getService(NametagService.class);
        VisibilityService visibilityService = this.plugin.getService(VisibilityService.class);
        HotbarService hotbarService = this.plugin.getService(HotbarService.class);

        nametagService.updatePlayerState(player);
        visibilityService.updateVisibility(player);
        hotbarService.applyHotbarItems(player);

        if (this.arena.getCenter() == null) {
            player.sendMessage(CC.translate("&cThe arena is not set up for spectating"));
            return;
        }

        player.setAllowFlight(true);
        player.setFlying(true);

        ListenerUtil.teleportAndClearSpawn(player, this.arena.getCenter());

        ProfileService profileService = this.plugin.getService(ProfileService.class);
        Profile profile = profileService.getProfile(player.getUniqueId());
        this.notifyAll("&6" + profile.getFancyName() + " &fis now spectating the match.");
    }

    public boolean allowsSpectators() {
        ProfileService profileService = this.plugin.getService(ProfileService.class);
        return this.getParticipants().stream()
                .flatMap(participant -> participant.getAllPlayers().stream())
                .map(gamePlayer -> profileService.getProfile(gamePlayer.getUuid()))
                .filter(Objects::nonNull)
                .allMatch(profile -> profile.getProfileData().getSettingData().isAllowSpectators());
    }

    /**
     * Removes a player from the list of spectators.
     * 从观战者列表中移除玩家。
     *
     * @param player The player to remove from spectating.
     *               要从观战中移除的玩家。
     */
    public void removeSpectator(Player player, boolean notify) {
        if (player == null) return;
        if (!Bukkit.isPrimaryThread()) {
            this.plugin.getServer().getScheduler().runTask(this.plugin,
                    () -> this.removeSpectator(player, notify));
            return;
        }

        ProfileService profileService = this.plugin.getService(ProfileService.class);
        Profile profile = profileService.getProfile(player.getUniqueId());

        profile.setState(profile.inTournament() ? ProfileState.TOURNAMENT_LOBBY : ProfileState.LOBBY);
        profile.setMatch(null);

        NametagService nametagService = this.plugin.getService(NametagService.class);
        VisibilityService visibilityService = this.plugin.getService(VisibilityService.class);

        nametagService.updatePlayerState(player);
        visibilityService.updateVisibility(player);

        player.setAllowFlight(false);
        player.setFlying(false);

        // A spectator is no longer part of the active combat timeline. Clear the
        // assigned profile as well as the live counter so an old hit-delay window
        // cannot leak into the lobby.
        this.plugin.getService(KnockbackManager.class).clearKnockback(player);
        this.resetPlayerState(player);
        this.teleportPlayerToSpawn(player);
        this.spectators.remove(player.getUniqueId());

        if (notify) {
            this.notifyAll("&6" + profile.getFancyName() + " &fis no longer spectating the match.");
        }
    }

    /**
     * Starts the respawn process for a participant.
     * 启动参与者的重生流程。
     *
     * @param player The player to start the respawn process for.
     *               要启动重生流程的玩家。
     */
    public void startRespawnProcess(Player player) {
        player.setGameMode(GameMode.SPECTATOR);
        MatchRespawnHealth.restore(player);
        player.setAllowFlight(true);
        player.setFlying(true);

        MatchGamePlayer gamePlayer = this.getGamePlayer(player);
        if (gamePlayer != null) {
            gamePlayer.setDead(false);
        }

        Location spawnLocation = this.arena.getCenter();
        ListenerUtil.teleportAndClearSpawn(player, spawnLocation);

        new MatchRespawnTask(player, this, 3).runTaskTimer(this.plugin, 0L, 20L);
    }

    /**
     * Determines whether handleRespawn should be called for a player.
     * 判断是否应该为玩家调用 handleRespawn。
     * This method can be overridden by subclasses to control the respawn process.
     * 子类可以重写此方法来控制重生流程。
     *
     * @param player The player to check.
     *               要检查的玩家。
     * @return True if handleRespawn should be called, false otherwise.
     *         如果应该调用 handleRespawn 返回 true，否则返回 false。
     */
    protected boolean shouldHandleRegularRespawn(Player player) {
        return true;
    }

    /**
     * Sets a participant as dead.
     * 将参与者设置为死亡状态。
     *
     * @param player     The player to set as dead.
     *                   要设置为死亡状态的玩家。
     * @param gamePlayer The game player to set as dead.
     *                   要设置为死亡状态的游戏玩家。
     */
    public void handleParticipant(Player player, MatchGamePlayer gamePlayer) {
        gamePlayer.setDead(true);
    }

    /**
     * Notifies the participants with a message.
     * 向参与者发送通知消息。
     *
     * @param message The message to notify.
     *                要通知的消息。
     */
    public void notifyParticipants(String message) {
        this.getParticipants().forEach(gameParticipant -> gameParticipant.getPlayers().forEach(uuid -> {
            Player player = uuid.getTeamPlayer();
            if (player != null) {
                player.sendMessage(CC.translate(message));
            }
        }));
    }

    /**
     * Notifies the spectators with a message.
     * 向观战者发送通知消息。
     *
     * @param message The message to notify.
     *                要通知的消息。
     */
    public void notifySpectators(String message) {
        this.spectators.stream()
                .map(uuid -> this.plugin.getServer().getPlayer(uuid))
                .filter(Objects::nonNull)
                .forEach(player -> player.sendMessage(CC.translate(message)));
    }

    /**
     * Notifies all participants and spectators with a message.
     * 向所有参与者和观战者发送通知消息。
     *
     * @param message The message to notify.
     *                要通知的消息。
     */
    public void notifyAll(String message) {
        this.notifyParticipants(message);
        this.notifySpectators(message);
    }

    public void broadcastAndStopSpectating() {
        if (!Bukkit.isPrimaryThread()) {
            this.plugin.getServer().getScheduler().runTask(this.plugin,
                    this::broadcastAndStopSpectating);
            return;
        }

        List<String> firstThreeSpectatorNames = new ArrayList<>();
        this.spectators.stream()
                .map(uuid -> this.plugin.getServer().getPlayer(uuid))
                .filter(Objects::nonNull)
                .limit(3)
                .forEach(player -> firstThreeSpectatorNames.add(player.getName()));

        List<Integer> remainingSpectators = new ArrayList<>();
        this.spectators.stream()
                .map(uuid -> this.plugin.getServer().getPlayer(uuid))
                .filter(Objects::nonNull)
                .skip(3)
                .forEach(player -> remainingSpectators.add(player.getEntityId()));

        this.notifyAll(this.plugin.getService(LocaleService.class).getString(GameMessagesLocaleImpl.MATCH_ENDED_SPECTATORS_LIST)
                .replace("{spectators}", String.join(", ", firstThreeSpectatorNames))
                .replace("{more_count}", String.valueOf(remainingSpectators.size())));

        this.spectators.forEach(uuid -> {
            Player player = this.plugin.getServer().getPlayer(uuid);
            if (player != null) {
                this.removeSpectator(player, false);
            }
        });
    }

    /**
     * Gets the duration of the match.
     * 获取比赛的持续时间。
     *
     * @return The duration of the match.
     *         比赛的持续时间。
     */
    public String getDuration() {
        if (this.state == MatchState.STARTING) {
            return TimeUtil.getFormattedElapsedTime(this.getElapsedTime());
        } else if (this.state == MatchState.ENDING_MATCH) {
            return TimeUtil.getFormattedElapsedTime(this.endTime - this.startTime);
        } else {
            return TimeUtil.getFormattedElapsedTime(this.getElapsedTime());
        }
    }

    /**
     * Gets the elapsed time of the match.
     * 获取比赛的已过时间。
     *
     * @return The elapsed time of the match.
     *         比赛的已过时间。
     */
    public long getElapsedTime() {
        return System.currentTimeMillis() - this.startTime;
    }

    /**
     * Gets the game player of a player.
     * 获取玩家的游戏玩家对象。
     *
     * @param player The player to get the game player of.
     *               要获取其游戏玩家对象的玩家。
     * @return The game player of the player.
     *         玩家的游戏玩家对象。
     */
    public MatchGamePlayer getGamePlayer(Player player) {
        return this.getParticipants().stream()
                .map(GameParticipant::getPlayers)
                .flatMap(List::stream)
                .filter(gamePlayer -> gamePlayer.getUuid().equals(player.getUniqueId()))
                .findFirst()
                .orElse(null);
    }

    /**
     * Gets a game player from all game players in the match.
     * 从比赛中的所有游戏玩家中获取游戏玩家对象。
     *
     * @param player The player to get the game player of.
     *               要获取其游戏玩家对象的玩家。
     * @return The game player of the player, or null if not found.
     *         玩家的游戏玩家对象，如果未找到则返回 null。
     */
    public MatchGamePlayer getFromAllGamePlayers(Player player) {
        return this.getParticipants().stream()
                .map(GameParticipant::getAllPlayers)
                .flatMap(List::stream)
                .filter(gamePlayer -> gamePlayer.getUuid().equals(player.getUniqueId()))
                .findFirst()
                .orElse(null);
    }

    /**
     * Gets a participant by a player.
     * 根据玩家获取参与者。
     *
     * @param player The player to get the participant of.
     *               要获取其参与者的玩家。
     * @return The participant of the player.
     *         玩家的参与者。
     */
    public GameParticipant<MatchGamePlayer> getParticipant(Player player) {
        return this.getParticipants().stream()
                .filter(gameParticipant -> gameParticipant.containsPlayer(player.getUniqueId()))
                .findFirst()
                .orElse(null);
    }

    /**
     * Gets the opposing participant in a two-sided match.
     * 在双方比赛中获取对方参与者。
     *
     * @param player The player object of a player on one side.
     *               某一方玩家的玩家对象。
     * @return The opposing GameParticipant, or null if it cannot be determined.
     *         对方的 GameParticipant，如果无法确定则返回 null。
     */
    public GameParticipant<MatchGamePlayer> getOpponent(Player player) {
        GameParticipant<MatchGamePlayer> participant = this.getParticipant(player);
        if (participant == null) {
            return null;
        }

        return this.getParticipants().stream()
                .filter(p -> !p.equals(participant))
                .findFirst()
                .orElse(null);
    }

    /**
     * Plays a sound for a player.
     * 为玩家播放音效。
     *
     * @param sound The sound to play.
     *              要播放的音效。
     */
    public void playSound(Sound sound) {
        this.getParticipants().forEach(gameParticipant -> gameParticipant.getPlayers().forEach(uuid -> {
            Player player = uuid.getTeamPlayer();
            if (player != null) {
                SoundUtil.playCustomSound(player, sound, 1.0F, 1.0F);
            }
        }));

        this.getSpectators().forEach(uuid -> {
            Player player = this.plugin.getServer().getPlayer(uuid);
            if (player != null) {
                SoundUtil.playCustomSound(player, sound, 1.0F, 1.0F);
            }
        });
    }

    /**
     * Plays a sound for a specific participant.
     * 为特定参与者播放音效。
     *
     * @param participant The participant to play the sound for.
     *                    要为其播放音效的参与者。
     * @param sound       The sound to play.
     *                    要播放的音效。
     */
    public void playSound(GameParticipant<MatchGamePlayer> participant, Sound sound) {
        participant.getPlayers().forEach(uuid -> {
            Player player = uuid.getTeamPlayer();
            if (player != null) {
                SoundUtil.playCustomSound(player, sound, 1.0F, 1.0F);
            }
        });
    }

    /**
     * Sends a title to all participants and spectators.
     * 向所有参与者和观战者发送标题。
     *
     * @param title    The title to send.
     *                 要发送的标题。
     * @param subtitle The subtitle to send.
     *                 要发送的副标题。
     */
    public void sendTitle(String title, String subtitle) {
        ReflectionService reflectionService = this.plugin.getService(ReflectionService.class);
        this.getParticipants().forEach(gameParticipant -> gameParticipant.getPlayers().forEach(uuid -> {
            Player player = uuid.getTeamPlayer();
            if (player != null) {
                reflectionService.getReflectionService(TitleReflectionServiceImpl.class).sendTitle(
                        player,
                        title,
                        subtitle
                );
            }
        }));

        this.getSpectators().forEach(uuid -> {
            Player player = this.plugin.getServer().getPlayer(uuid);
            if (player != null) {
                reflectionService.getReflectionService(TitleReflectionServiceImpl.class).sendTitle(
                        player,
                        title,
                        subtitle
                );
            }
        });
    }

    /**
     * Sends a title to all participants and spectators with custom timings.
     * 向所有参与者和观战者发送带有自定义时间的标题。
     *
     * @param title    The title to send.
     *                 要发送的标题。
     * @param subtitle The subtitle to send.
     *                 要发送的副标题。
     * @param fadeIn   The fade-in time in ticks.
     *                 淡入时间（tick）。
     * @param stay     The stay time in ticks.
     *                 停留时间（tick）。
     * @param fadeOut  The fade-out time in ticks.
     *                 淡出时间（tick）。
     */
    public void sendTitle(String title, String subtitle, int fadeIn, int stay, int fadeOut, boolean spectators) {
        ReflectionService reflectionService = this.plugin.getService(ReflectionService.class);
        this.getParticipants().forEach(gameParticipant -> gameParticipant.getPlayers().forEach(uuid -> {
            Player player = uuid.getTeamPlayer();
            if (player != null) {
                reflectionService.getReflectionService(TitleReflectionServiceImpl.class).sendTitle(
                        player,
                        title,
                        subtitle,
                        fadeIn, stay, fadeOut
                );
            }
        }));

        if (spectators) {
            this.getSpectators().forEach(uuid -> {
                Player player = this.plugin.getServer().getPlayer(uuid);
                if (player != null) {
                    reflectionService.getReflectionService(TitleReflectionServiceImpl.class).sendTitle(
                            player,
                            title,
                            subtitle,
                            fadeIn, stay, fadeOut
                    );
                }
            });
        }
    }

    /**
     * Sends a result title immediately and repeats it after the automatic
     * respawn when the recipient is currently dead.
     */
    protected void sendResultTitle(Player player, String title, String subtitle,
                                   int fadeIn, int stay, int fadeOut) {
        if (player == null || !player.isOnline()) {
            return;
        }

        Runnable sendTitle = () -> {
            if (!player.isOnline()) return;
            this.plugin.getService(ReflectionService.class)
                    .getReflectionService(TitleReflectionServiceImpl.class)
                    .sendTitle(player, title, subtitle, fadeIn, stay, fadeOut);
        };

        sendTitle.run();
        if (player.isDead()) {
            this.plugin.getServer().getScheduler().runTaskLater(this.plugin, sendTitle, 3L);
        }
    }

    /**
     * Sends a list of messages to all participants.
     * 向所有参与者发送消息列表。
     *
     * @param messages The list of messages to send.
     *                 要发送的消息列表。
     */
    public void sendMessage(List<String> messages) {
        messages.forEach(this::sendMessage);
    }

    /**
     * Sends a message to all participants.
     * 向所有参与者发送消息。
     *
     * @param message The message to send.
     *                要发送的消息。
     */
    public void sendMessage(String message) {
        this.getParticipants().forEach(gameParticipant -> gameParticipant.getPlayers().forEach(uuid -> {
            Player player = uuid.getTeamPlayer();
            if (player != null) {
                player.sendMessage(CC.translate(message));
            }
        }));

        this.getSpectators().forEach(uuid -> {
            Player player = this.plugin.getServer().getPlayer(uuid);
            if (player != null) {
                player.sendMessage(CC.translate(message));
            }
        });
    }

    /**
     * Notifies all participants and spectators with an advanced chat component.
     * 使用高级聊天组件通知所有参与者和观战者。
     * This is used for sending clickable or hoverable messages.
     * 用于发送可点击或可悬停的消息。
     *
     * @param component The component(s) to send.
     *                  要发送的组件。
     */
    public void sendComponentMessage(BaseComponent component) {
        this.getParticipants().forEach(gameParticipant -> gameParticipant.getPlayers().forEach(uuid -> {
            Player player = uuid.getTeamPlayer();
            if (player != null) {
                player.spigot().sendMessage(component);
            }
        }));

        this.getSpectators().forEach(uuid -> {
            Player player = this.plugin.getServer().getPlayer(uuid);
            if (player != null) {
                player.spigot().sendMessage(component);
            }
        });
    }

    /**
     * Checks if the attacker is in the same participant team as the supposed victim.
     * 检查攻击者是否与目标受害者在同一参与者队伍中。
     *
     * @param attacker The attacker.
     *                 攻击者。
     * @param victim   The victim.
     *                 受害者。
     * @return If the attacker is in the same participant team as the victim.
     *         如果攻击者与受害者在同一参与者队伍中。
     */
    public boolean isInSameTeam(Player attacker, Player victim) {
        if (this instanceof HideAndSeekMatch hideAndSeekMatch && hideAndSeekMatch.isFreeForAllCombat()) {
            return false;
        }
        GameParticipant<MatchGamePlayer> attackerParticipant = this.getParticipant(attacker);
        GameParticipant<MatchGamePlayer> victimParticipant = this.getParticipant(victim);

        return attackerParticipant.equals(victimParticipant);
    }

    /**
     * Intentionally made to deny player movement during a match countdown.
     * 故意设计用于在比赛倒计时期间禁止玩家移动。
     *
     * @param participants the participants
     *                     参与者列表
     */
    public void denyPlayerMovement(List<GameParticipant<MatchGamePlayer>> participants) {
        if (this instanceof GomokuPlayable) return;
        if (participants.size() == 2) {
            GameParticipant<?> participantA = participants.get(0);
            GameParticipant<?> participantB = participants.get(1);

            Location locationA = this.arena.getPos1();
            Location locationB = this.arena.getPos2();

            for (GamePlayer gamePlayer : participantA.getPlayers()) {
                Player participantPlayer = gamePlayer.getTeamPlayer();
                if (participantPlayer != null) {
                    this.teleportBackIfMoved(participantPlayer, locationA);
                }
            }

            for (GamePlayer gamePlayer : participantB.getPlayers()) {
                Player participantPlayer = gamePlayer.getTeamPlayer();
                if (participantPlayer != null) {
                    this.teleportBackIfMoved(participantPlayer, locationB);
                }
            }
        }
    }

    /**
     * Teleports the player back to their designated position if they moved.
     * 如果玩家移动了，则将其传送回指定位置。
     *
     * @param player   The player to check.
     *                 要检查的玩家。
     * @param location The designated location.
     *                 指定位置。
     */
    protected void teleportBackIfMoved(Player player, Location location) {
        Location playerLocation = player.getLocation();

        double deltaX = Math.abs(playerLocation.getX() - location.getX());
        double deltaZ = Math.abs(playerLocation.getZ() - location.getZ());

        if (deltaX > 0.1 || deltaZ > 0.1) {
            player.teleport(location.clone());
        }
    }

    /**
     * Teleports a player to the spawn and applies spawn items.
     * 将玩家传送到出生点并应用出生点物品。
     *
     * @param player The player to teleport.
     *               要传送的玩家。
     */
    private void teleportPlayerToSpawn(Player player) {
        if (player == null) return;

        if (!Bukkit.isPrimaryThread()) {
            this.plugin.getServer().getScheduler().runTask(this.plugin,
                    () -> this.teleportPlayerToSpawn(player));
            return;
        }

        if (player.isDead()) {
            // Match deaths are synthetic, so this normally never runs. It only rescues
            // a genuine death from an unhandled path; never teleport a dead player
            // across worlds — the client rejects it with a protocol error.
            player.spigot().respawn();
        }

        // Ground the player before the cross-world teleport. Spectators hover at the
        // arena center and leaving the arena world (a void/custom world) while still
        // flying is a known source of client-side protocol errors on 1.21 forks. The
        // teleport follows in the same tick, so grounding here cannot cause a fall.
        player.setAllowFlight(false);
        player.setFlying(false);

        HotbarService hotbarService = this.plugin.getService(HotbarService.class);
        SpawnService spawnService = this.plugin.getService(SpawnService.class);

        spawnService.teleportToSpawn(player);
        MatchListener.clearDeadPlayerPickupBlock(player);
        hotbarService.applyHotbarItems(player);
        MatchResultFlight.clear(player);
    }

    /**
     * Teleports a player to the spawn.
     * 将玩家传送到出生点。
     *
     * @param player The player to teleport.
     *               要传送的玩家。
     */
    private void resetPlayerState(Player player) {
        player.setFireTicks(0);
        player.updateInventory();
        PlayerUtil.reset(player, false, true);
    }

    /**
     * Adds a block to the placed blocks map with the intention to handle block placement and removal.
     * 将方块添加到已放置方块映射中，用于处理方块的放置和移除。
     *
     * @param blockState The block state to add.
     *                   要添加的方块状态。
     * @param location   The location of the block.
     *                   方块的位置。
     */
    public void addBlockToPlacedBlocksMap(BlockState blockState, Location location) {
        this.placedBlocks.entrySet().removeIf(entry -> entry.getValue().equals(location));
        this.placedBlocks.put(blockState, location);
    }

    /**
     * Removes a block from the placed blocks map.
     * 从已放置方块映射中移除方块。
     *
     * @param blockState The block state to remove.
     *                   要移除的方块状态。
     */
    public void removeBlockFromPlacedBlocksMap(BlockState blockState, Location location) {
        this.placedBlocks.remove(blockState, location);
    }

    public void addBlockToBrokenBlocksMap(BlockState blockState, Location location) {
        if (this.placedBlocks.containsValue(location)) {
            this.placedBlocks.values().remove(location);
        } else if (!this.brokenBlocks.containsValue(location)) {
            this.brokenBlocks.put(blockState, location);
        }
    }

    @SuppressWarnings("deprecation")
    public void resetBlockChanges() {
        this.plugin.getService(LuckyBlockService.class).cleanupMatch(this);
        if (this.getKit().isSettingEnabled(KitSettingRaiding.class)) {
            Arena arena = this.getArena();
            Location pos1 = arena.getPos1();
            Location pos2 = arena.getPos2();

            for (int x = pos1.getBlockX(); x <= pos2.getBlockX(); x++) {
                for (int z = pos1.getBlockZ(); z <= pos2.getBlockZ(); z++) {
                    for (int y = pos1.getBlockY(); y <= pos2.getBlockY(); y++) {
                        Location location = new Location(pos1.getWorld(), x, y, z);
                        Block block = location.getBlock();
                        if (ListenerUtil.isInteractiveBlock(block.getType())) {
                            BlockState originalState = block.getState();
                            if (originalState.getType() == Material.AIR) {
                                continue;
                            }
                            this.brokenBlocks.put(originalState, location);
                            block.setType(Material.AIR);
                        }
                    }
                }
            }
        }

        this.removePlacedBlocks();

        for (Map.Entry<BlockState, Location> entry : this.brokenBlocks.entrySet()) {
            Location location = entry.getValue();
            BlockState originalState = entry.getKey();

            Block block = location.getBlock();
            block.setBlockData(originalState.getBlockData());
        }

        this.brokenBlocks.clear();
    }

    public void removePlacedBlocks() {
        for (Map.Entry<BlockState, Location> entry : this.placedBlocks.entrySet()) {
            Location location = entry.getValue();
            location.getBlock().setType(Material.AIR);
        }

        this.placedBlocks.clear();
    }

    public abstract void sendPlayerVersusPlayerMessage();

    private void handleMatchTasks() {
        this.runnable = new MatchTask(this);
        this.runnable.runTaskTimer(this.plugin, 0L, 20L);

        if (this.getKit().isSettingEnabled(KitSettingPlatformDecay.class) && this.getArena() instanceof StandAloneArena) {
            PlatformDecayTask.start(this);
        }
    }

    private void updatePlayerProfileForMatch(Player player) {
        ProfileService profileService = this.plugin.getService(ProfileService.class);
        Profile profile = profileService.getProfile(player.getUniqueId());
        profile.setState(ProfileState.PLAYING);
        profile.setMatch(this);
    }

    private void updatePlayerProfileForLobby(Player player) {
        ProfileService profileService = this.plugin.getService(ProfileService.class);
        Profile profile = profileService.getProfile(player.getUniqueId());

        profile.setState(profile.getGameEvent() == null ? ProfileState.LOBBY : ProfileState.PLAYING_EVENT);
        profile.setMatch(null);

        // Remove all legacy combat effects.
        MatchServiceImpl matchServiceImpl = (MatchServiceImpl) this.plugin.getService(MatchService.class);
        if (matchServiceImpl.getLegacyCombatService() != null) {
            matchServiceImpl.getLegacyCombatService().removeAll(player);
        }
    }

    private void setupSpectatorProfile(Player player) {
        ProfileService profileService = this.plugin.getService(ProfileService.class);
        Profile profile = profileService.getProfile(player.getUniqueId());
        profile.setState(ProfileState.SPECTATING);
        profile.setMatch(this);

        // Death spectators must not retain the defeated round's hurt window.
        this.plugin.getService(KnockbackManager.class).clearKnockback(player);
        PlayerUtil.reset(player, false, true);
    }

    /**
     * Calculates the coin reward for a player based on their performance in the match.
     * 根据玩家在比赛中的表现计算金币奖励。
     * The calculation is based on kills, deaths, and missed potions.
     * 计算基于击杀数、死亡数和未命中药水数。
     *
     * @param player The player to calculate the coin reward for.
     *               要计算金币奖励的玩家。
     */
    public void calculateCoinReward(Player player) {
        ProfileService profileService = this.plugin.getService(ProfileService.class);
        Profile profile = profileService.getProfile(player.getUniqueId());

        MatchGamePlayerData data = this.getGamePlayer(player).getData();
        int kills = data.getKills();
        int deaths = data.getDeaths();
        int missedPotions = data.getMissedPotions();

        double score = 0;

        score += kills * 15;
        score -= deaths * 10;

        int excessPotions = Math.max(missedPotions - 20, 0);
        score -= excessPotions * 1.5;

        int performanceScore = (int) Math.max(0, Math.min(100, score));
        profile.getProfileData().incrementCoins(performanceScore);
    }

    /**
     * Sends a reward message to the player after the match.
     * 比赛结束后向玩家发送奖励消息。
     * This message includes the number of coins earned.
     * 此消息包含获得的金币数量。
     *
     * @param player The player to send the reward message to.
     *               要发送奖励消息的玩家。
     */
    public void sendRewardMessage(Player player) {
        ProfileService profileService = this.plugin.getService(ProfileService.class);
        Profile profile = profileService.getProfile(player.getUniqueId());
        int coins = profile.getProfileData().getCoins();

        player.sendMessage(CC.translate(" &7(&a+&6" + coins + "&f&7)"));
    }
}
