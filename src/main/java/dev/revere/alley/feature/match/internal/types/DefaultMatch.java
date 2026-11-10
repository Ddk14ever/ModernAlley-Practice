package dev.revere.alley.feature.match.internal.types;

import dev.revere.alley.AlleyPlugin;
import dev.revere.alley.common.InventoryUtil;
import dev.revere.alley.common.ListenerUtil;
import dev.revere.alley.common.PlayerUtil;
import dev.revere.alley.common.elo.EloCalculator;
import dev.revere.alley.common.elo.EloResult;
import dev.revere.alley.common.elo.OldEloResult;
import dev.revere.alley.common.logger.Logger;
import dev.revere.alley.common.text.CC;
import dev.revere.alley.core.locale.LocaleService;
import dev.revere.alley.core.locale.internal.impl.VisualsLocaleImpl;
import dev.revere.alley.core.locale.internal.impl.message.GameMessagesLocaleImpl;
import dev.revere.alley.core.profile.Profile;
import dev.revere.alley.core.profile.ProfileService;
import dev.revere.alley.core.profile.progress.PlayerProgress;
import dev.revere.alley.core.profile.progress.ProgressService;
import dev.revere.alley.feature.arena.Arena;
import dev.revere.alley.feature.bot.BotService;
import dev.revere.alley.feature.bot.match.BotMatchSession;
import dev.revere.alley.feature.challenge.ChallengeService;
import dev.revere.alley.feature.challenge.ChallengeType;
import dev.revere.alley.feature.layout.LayoutService;
import dev.revere.alley.feature.coin.CoinRewardService;
import dev.revere.alley.feature.division.Division;
import dev.revere.alley.feature.division.model.DivisionTier;
import dev.revere.alley.feature.kit.Kit;
import dev.revere.alley.feature.kit.raiding.BaseRaidingService;
import dev.revere.alley.feature.kit.setting.types.combat.KitSettingOldOffhand;
import dev.revere.alley.feature.kit.setting.types.mechanic.KitSettingDropItemsImpl;
import dev.revere.alley.feature.kit.setting.types.mode.KitSettingRaiding;
import dev.revere.alley.feature.kit.setting.types.mode.KitSettingRespawnTimer;
import dev.revere.alley.feature.layout.data.LayoutData;
import dev.revere.alley.feature.level.LevelService;
import dev.revere.alley.feature.level.data.LevelData;
import dev.revere.alley.feature.leaderboard.LeaderboardService;
import dev.revere.alley.feature.match.Match;
import dev.revere.alley.feature.match.MatchState;
import dev.revere.alley.feature.match.model.BaseRaiderRole;
import dev.revere.alley.feature.match.model.GameParticipant;
import dev.revere.alley.feature.match.model.MatchGamePlayerData;
import dev.revere.alley.feature.match.model.TeamGameParticipant;
import dev.revere.alley.feature.match.model.internal.MatchGamePlayer;
import dev.revere.alley.feature.match.utility.MatchUtility;
import dev.revere.alley.feature.match.utility.MatchResultFlight;
import dev.revere.alley.feature.queue.Queue;
import lombok.Getter;
import lombok.Setter;
import org.bukkit.ChatColor;
import org.bukkit.Location;
import org.bukkit.Sound;
import org.bukkit.entity.Player;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;

/**
 * @author Remi
 * @project Alley
 * @date 5/21/2024
 */
@Getter
@Setter
public class DefaultMatch extends Match {
    private GameParticipant<MatchGamePlayer> participantA;
    private GameParticipant<MatchGamePlayer> participantB;

    public final ChatColor teamAColor;
    public final ChatColor teamBColor;

    private GameParticipant<MatchGamePlayer> winner;
    private GameParticipant<MatchGamePlayer> loser;

    /**
     * Constructor for the MatchRegularImpl class.
     * MatchRegularImpl类的构造函数。
     *
     * @param queue        The queue of the match.
     *                     比赛的队列。
     * @param kit          The kit of the match.
     *                     比赛的工具包。
     * @param arena        The arena of the match.
     *                     比赛的竞技场。
     * @param ranked       Whether the match is ranked or not.
     *                     比赛是否为排位赛。
     * @param participantA The first participant.
     *                     第一个参赛方。
     * @param participantB The second participant.
     *                     第二个参赛方。
     */
    public DefaultMatch(Queue queue, Kit kit, Arena arena, boolean ranked, GameParticipant<MatchGamePlayer> participantA, GameParticipant<MatchGamePlayer> participantB) {
        super(queue, kit, arena, ranked);
        this.participantA = participantA;
        this.participantB = participantB;
        this.teamAColor = ChatColor.BLUE;
        this.teamBColor = ChatColor.RED;
    }

    @Override
    public void setupPlayer(Player player) {
        super.setupPlayer(player);
        this.applyColorKit(player);

        teleportToSpawn(player).thenRun(() -> {
            if (this.getKit().isSettingEnabled(KitSettingRaiding.class)) {
                this.determineRolesAndGiveKit(player);
            }
        });
    }

    protected CompletableFuture<Boolean> teleportToSpawn(Player player) {
        Location spawnLocation = this.getParticipantA().containsPlayer(player.getUniqueId())
                ? getArena().getPos1() : getArena().getPos2();
        if (spawnLocation == null) {
            return CompletableFuture.completedFuture(false);
        }
        return player.teleportAsync(spawnLocation);
    }

    @Override
    public List<GameParticipant<MatchGamePlayer>> getParticipants() {
        return Arrays.asList(getParticipantA(), getParticipantB());
    }

    @Override
    protected void replaceParticipant(GameParticipant<MatchGamePlayer> old, TeamGameParticipant<MatchGamePlayer> replacement) {
        if (this.participantA == old) {
            this.participantA = replacement;
        } else if (this.participantB == old) {
            this.participantB = replacement;
        }
    }

    /**
     * Get the team color of a participant.
     * 获取参赛方的队伍颜色。
     *
     * @param participant The participant to get the team color of.
     *                    要获取队伍颜色的参赛方。
     * @return The team color of the participant.
     *         参赛方的队伍颜色。
     */
    public ChatColor getTeamColor(GameParticipant<MatchGamePlayer> participant) {
        return participant == this.getParticipantA() ? this.teamAColor : this.teamBColor;
    }

    /**
     * Applies the wool color to the player based on their team.
     * 根据玩家所属队伍为其应用羊毛颜色。
     *
     * @param player The player to apply the wool color to.
     *               要应用羊毛颜色的玩家。
     */
    public void applyColorKit(Player player) {
        GameParticipant<MatchGamePlayer> participant = this.getParticipant(player);
        if (participant == null) {
            return;
        }

        final InventoryUtil.TeamColor colorToApply = (participant == this.getParticipantA())
                ? InventoryUtil.TeamColor.BLUE
                : InventoryUtil.TeamColor.RED;

        participant.getPlayers().stream()
                .map(MatchGamePlayer::getTeamPlayer)
                .filter(p -> p != null && p.isOnline())
                .forEach(teamPlayer -> InventoryUtil.applyTeamColorToInventory(teamPlayer, colorToApply));
    }

    @Override
    protected boolean shouldHandleRegularRespawn(Player player) {
        return !this.getKit().isSettingEnabled(KitSettingRespawnTimer.class);
    }

    @Override
    public void handleRoundEnd() {
        if (this.deferToPrimaryThread(this::handleRoundEnd)) return;

        final boolean teamADead = this.getParticipantA().isAllEliminated() || this.getParticipantA().isAllDead();
        final GameParticipant<MatchGamePlayer> winner = teamADead ? this.getParticipantB() : this.getParticipantA();
        final GameParticipant<MatchGamePlayer> loser = teamADead ? this.getParticipantA() : this.getParticipantB();

        this.winner = winner;
        this.loser = loser;

        broadcastMatchOutcome(winner, loser);
        processStatistics(winner, loser);

        if (!this.getSpectators().isEmpty()) {
            this.broadcastAndStopSpectating();
        }

        // Chat version coexists with the result-time papers (both are available).
        // 聊天版与结果阶段的物品版共存（两者都可使用）。
        MatchUtility.sendPlayAgain(this);
        super.handleRoundEnd();
    }

    /**
     * Handles all player-facing messages at the end of a match, including titles and results.
     * 处理比赛结束时所有面向玩家的消息，包括标题和结果。
     */
    private void broadcastMatchOutcome(GameParticipant<MatchGamePlayer> winner, GameParticipant<MatchGamePlayer> loser) {
        applyResultFlight(winner, true);
        applyResultFlight(loser, false);

        this.sendVictory(winner);
        this.sendDefeat(loser, winner);

        if (this.isTeamMatch()) {
            MatchUtility.sendConjoinedMatchResult(this, winner, loser);
        } else {
            MatchGamePlayer winnerPlayer = winner.getLeader();
            MatchGamePlayer loserPlayer = loser.getLeader();
            MatchUtility.sendMatchResult(
                    this,
                    winnerPlayer.getUsername(),
                    loserPlayer.getUsername(),
                    winnerPlayer.getUuid(),
                    loserPlayer.getUuid()
            );
        }

        // Give both papers two ticks later, after death inventory cleanup.
        // Winner keeps slot 1; loser keeps slot 0.
        this.plugin.getServer().getScheduler().runTaskLater(this.plugin,
                () -> givePlayAgainPapers(winner, loser), 2L);

        // Start MVP music now, then reveal the MVP after the result title has been visible.
        this.announceMVP(40L);
    }

    private void applyResultFlight(GameParticipant<MatchGamePlayer> participant, boolean won) {
        participant.getAllPlayers().forEach(gamePlayer -> {
            Profile profile = AlleyPlugin.getInstance().getService(ProfileService.class)
                    .getProfile(gamePlayer.getUuid());
            if (profile == null) {
                return;
            }

            boolean enabled = won
                    ? profile.getProfileData().getSettingData().isFlyOnWin()
                    : profile.getProfileData().getSettingData().isFlyOnLoss();
            if (!enabled) {
                return;
            }

            Player player = gamePlayer.getTeamPlayer();
            if (player != null) {
                MatchResultFlight.enable(player);
            }
        });
    }

    private void givePlayAgainPapers(GameParticipant<MatchGamePlayer> winner, GameParticipant<MatchGamePlayer> loser) {
        if (this.getKit() == null) return;

        // Bot matches hand out the same paper, but it restarts the same Bot duel
        // (same kit + difficulty) instead of queueing for an unranked match, so
        // the paper does not depend on a queue being available.
        // Bot对局也发放"再来一局"纸，但点击后用相同套件与难度重开Bot对局，而不是排位匹配。
        boolean botMatch = this.getBotDifficultyId() != null;
        if (!botMatch && this.getQueue() == null) return;

        Player winnerPlayer = winner.getLeader().getTeamPlayer();
        if (isHumanParticipant(winnerPlayer)) {
            this.giveResultPlayAgainItem(winnerPlayer, 1);
        }

        Player loserPlayer = loser.getLeader().getTeamPlayer();
        if (isHumanParticipant(loserPlayer)) {
            this.giveResultPlayAgainItem(loserPlayer, 0);
        }
    }

    /** Real players only: the Bot NPC is also a Player and must not receive items. */
    private boolean isHumanParticipant(Player target) {
        if (target == null || !target.isOnline()) return false;
        BotMatchSession session = AlleyPlugin.getInstance().getService(BotService.class)
                .getSession((org.bukkit.entity.Entity) target);
        return session == null || !target.getUniqueId().equals(session.getBotId());
    }

    /**
     * Processes all backend statistics if the match is configured to affect them.
     * 如果比赛配置为影响统计数据，则处理所有后端统计数据。
     */
    private void processStatistics(GameParticipant<MatchGamePlayer> winner, GameParticipant<MatchGamePlayer> loser) {
        if (!this.isAffectStatistics()) {
            return;
        }

        ProfileService profileService = AlleyPlugin.getInstance().getService(ProfileService.class);
        Profile winnerProfile = profileService.getProfile(winner.getLeader().getUuid());
        ChallengeService challengeService = AlleyPlugin.getInstance().getService(ChallengeService.class);
        challengeService.deferCompletionMessages(winnerProfile);

        try {
        // Capture the winner's division/level before the stats are applied, so we can announce upgrades.
        // 在统计生效前记录获胜者的段位/等级，以便播报升级。
        String oldDivision = winnerProfile == null ? "" : this.divisionKey(winnerProfile);
        String oldLevel = winnerProfile == null ? "" : this.levelName(winnerProfile);

        // Apply the win/loss & elo updates first so the progress message reflects the new values.
        handleMatchData(winner, loser); // 9 wins, increases to 10, as the player won
        // 9场胜利，增加到10场，因为该玩家获胜了

        String newDivision = winnerProfile == null ? "" : this.divisionKey(winnerProfile);
        String newLevel = winnerProfile == null ? "" : this.levelName(winnerProfile);
        boolean divisionUpgraded = !oldDivision.equals(newDivision);
        boolean levelUpgraded = !oldLevel.equals(newLevel);

        // Announce any level/division upgrade above the Progress message, with a level-up sound.
        // 在 Progress 消息上方播报等级/段位升级结果，并播放升级音效。
        if (divisionUpgraded || levelUpgraded) {
            this.sendUpgradeAnnouncement(winner.getLeader().getTeamPlayer(),
                    divisionUpgraded, newDivision, levelUpgraded, newLevel);
        }

        // Progress is only announced to the winner. Message order is: Match Results -> [upgrades] -> Progress -> coins.
        this.sendProgressToWinner(winner.getLeader().getTeamPlayer());

        this.rewardCoins(winner, loser);
        } finally {
            challengeService.flushCompletionMessages(winnerProfile);
        }
    }

    /**
     * Gets the readable division key (e.g. "Gold I") of a profile for the current kit.
     * 获取指定档案在当前套件下的可读段位标识（例如 "Gold I"）。
     *
     * @param profile The player's profile.
     *                玩家的资料。
     * @return The division name and tier, or an empty string if not available.
     *         段位名称和层级，若不可用则返回空字符串。
     */
    private String divisionKey(Profile profile) {
        var kitData = profile.getProfileData().getUnrankedKitData().get(this.getKit().getName());
        if (kitData == null) return "";
        Division division = kitData.getDivision();
        if (division == null) return "";
        DivisionTier tier = kitData.getTier();
        return division.getName() + " " + (tier == null ? "" : tier.getName());
    }

    /**
     * Gets the name of the level a profile currently belongs to based on its global elo.
     * 根据档案的全局ELO获取其当前所属等级名称。
     *
     * @param profile The player's profile.
     *                玩家的资料。
     * @return The level name, or an empty string if no level matches.
     *         等级名称，若无匹配等级则返回空字符串。
     */
    private String levelName(Profile profile) {
        LevelService levelService = AlleyPlugin.getInstance().getService(LevelService.class);
        LevelData level = levelService.getLevel(profile.getProfileData().getElo());
        return level == null ? "" : level.getName();
    }

    /**
     * Sends the level/division upgrade announcement above the Progress message and plays a level-up sound.
     * 在 Progress 消息上方发送等级/段位升级公告，并播放升级音效。
     *
     * @param player            The winning player.
     *                          获胜的玩家。
     * @param divisionUpgraded  Whether the player's division changed.
     *                          玩家的段位是否发生了变化。
     * @param newDivision       The new division key.
     *                          新的段位标识。
     * @param levelUpgraded     Whether the player's level changed.
     *                          玩家的等级是否发生了变化。
     * @param newLevel          The new level name.
     *                          新的等级名称。
     */
    private void sendUpgradeAnnouncement(Player player, boolean divisionUpgraded, String newDivision,
                                         boolean levelUpgraded, String newLevel) {
        if (player == null || !player.isOnline()) {
            return;
        }

        List<String> message = new ArrayList<>();
        if (levelUpgraded) {
            message.add("&6&lNEW LEVEL &f| &a&lCONGRATULATIONS!");
            message.add(" &fYou have reached &6" + newLevel + " &fin the global ranking system.");
        }
        if (divisionUpgraded) {
            message.add("&6&lNEW DIVISION &f| &a&lCONGRATULATIONS!");
            message.add(" &fYou have reached &6" + newDivision + " &fin the division ranking system.");
        }

        message.forEach(line -> player.sendMessage(CC.translate(line)));
        player.playSound(player.getLocation(), Sound.ENTITY_PLAYER_LEVELUP, 1.0f, 1.0f);
    }

    /**
     * Rewards coins to both participants after the progress message has been sent.
     * 在进度消息发送之后再发放金币奖励。
     *
     * @param winner The winning participant.
     *               获胜的参赛方。
     * @param loser  The losing participant.
     *               失败的参赛方。
     */
    private void rewardCoins(GameParticipant<MatchGamePlayer> winner, GameParticipant<MatchGamePlayer> loser) {
        if (this.isTeamMatch()) {
            return;
        }

        CoinRewardService coinReward = AlleyPlugin.getInstance().getService(CoinRewardService.class);
        if (this.isRanked()) {
            coinReward.rewardRankedWin(winner.getLeader().getTeamPlayer());
            coinReward.rewardRankedLoss(loser.getLeader().getTeamPlayer());
        } else {
            coinReward.rewardUnrankedWin(winner.getLeader().getTeamPlayer());
            coinReward.rewardUnrankedLoss(loser.getLeader().getTeamPlayer());
        }
    }

    /**
     * Routes to the correct statistics handling method based on the match type.
     * 根据比赛类型路由到正确的统计数据处理方法。
     *
     * @param winner The winning participant.
     *               获胜的参赛方。
     * @param loser  The losing participant.
     *               失败的参赛方。
     */
    private void handleMatchData(GameParticipant<MatchGamePlayer> winner, GameParticipant<MatchGamePlayer> loser) {
        // Duos (unranked team) matches count toward stats; party/duel/bot/tournament matches all
        // run with affectStatistics=false and never reach this method.
        // 双打（非排位团队）比赛计入战绩；派对/约战/机器人/锦标赛比赛均为 affectStatistics=false，
        // 不会到达此方法。
        if (this.isRanked()) {
            updateRankedStats(winner, loser);
        } else {
            updateUnrankedStats(winner, loser);
        }
    }

    /**
     * Updates player profiles and Elo for a ranked match.
     * 为排位赛更新玩家资料和Elo分数。
     */
    private void updateRankedStats(GameParticipant<MatchGamePlayer> winner, GameParticipant<MatchGamePlayer> loser) {
        OldEloResult result = this.getOldEloResult(winner, loser);
        EloResult eloResult = this.getEloResult(result.getOldWinnerElo(), result.getOldLoserElo());

        this.handleWinner(eloResult.getNewWinnerElo(), winner);
        this.handleLoser(eloResult.getNewLoserElo(), loser);

        this.sendEloResult(
                winner.getLeader().getTeamPlayer().getName(),
                loser.getLeader().getTeamPlayer().getName(),
                result.getOldWinnerElo(),
                result.getOldLoserElo(),
                eloResult.getNewWinnerElo(),
                eloResult.getNewLoserElo()
        );
    }

    /**
     * Updates player profiles with wins/losses for an unranked match.
     * 为非排位赛更新玩家资料的胜场/败场记录。
     */
    private void updateUnrankedStats(GameParticipant<MatchGamePlayer> winner, GameParticipant<MatchGamePlayer> loser) {
        ProfileService profileService = AlleyPlugin.getInstance().getService(ProfileService.class);
        ChallengeService challengeService = AlleyPlugin.getInstance().getService(ChallengeService.class);
        String kitName = getKit().getName();

        // Iterate every winning player (solo matches have a single leader, duos have two).
        // 遍历所有获胜玩家（单打只有队长，双打有两名队员）。
        for (MatchGamePlayer matchPlayer : winner.getAllPlayers()) {
            Profile profile = profileService.getProfile(matchPlayer.getUuid());
            if (profile == null) continue;

            var kitData = profile.getProfileData().getUnrankedKitData().get(kitName);
            if (kitData != null) kitData.incrementWins();
            AlleyPlugin.getInstance().getService(LeaderboardService.class)
                    .recordMonthlyUnrankedWin(profile, this.getKit());
            profile.getProfileData().incrementUnrankedWins();
            profile.getProfileData().refreshDivision(kitName);
            profile.getProfileData().determineTitles();
            challengeService.recordProgress(profile, ChallengeType.WINS, 1);
        }

        for (MatchGamePlayer matchPlayer : loser.getAllPlayers()) {
            Profile profile = profileService.getProfile(matchPlayer.getUuid());
            if (profile == null) continue;

            var kitData = profile.getProfileData().getUnrankedKitData().get(kitName);
            if (kitData != null) kitData.incrementLosses();
            profile.getProfileData().incrementUnrankedLosses();
        }
    }

    /**
     * Sends the victory title to the winning participant.
     * 向获胜方发送胜利标题。
     *
     * @param winner The winning participant.
     *               获胜的参赛方。
     */
    private void sendVictory(GameParticipant<MatchGamePlayer> winner) {
        LocaleService localeService = this.plugin.getService(LocaleService.class);


        if (localeService.getBoolean(VisualsLocaleImpl.TITLE_MATCH_VICTORY_ENABLED_BOOLEAN)) {
            String header = localeService.getString(VisualsLocaleImpl.TITLE_MATCH_VICTORY_HEADER).replace("{winner}", winner.getLeader().getUsername());
            String footer = localeService.getString(VisualsLocaleImpl.TITLE_MATCH_VICTORY_FOOTER).replace("{winner}", winner.getLeader().getUsername());

            int fadeIn = localeService.getInt(VisualsLocaleImpl.TITLE_MATCH_VICTORY_FADE_IN);
            int stay = localeService.getInt(VisualsLocaleImpl.TITLE_MATCH_VICTORY_STAY);
            int fadeOut = localeService.getInt(VisualsLocaleImpl.TITLE_MATCH_VICTORY_FADEOUT);

            winner.getPlayers().forEach(matchGamePlayer -> {
                Player player = matchGamePlayer.getTeamPlayer();
                if (player != null && player.isOnline()) {
                    sendResultTitle(player, header, footer, fadeIn, stay, fadeOut);
                }
            });
        }
    }

    /**
     * Sends the defeat title to the losing participant.
     * 向失败方发送失败标题。
     *
     * @param loser The losing participant.
     *              失败的参赛方。
     */
    private void sendDefeat(GameParticipant<MatchGamePlayer> loser, GameParticipant<MatchGamePlayer> winner) {
        LocaleService localeService = this.plugin.getService(LocaleService.class);

        if (localeService.getBoolean(VisualsLocaleImpl.TITLE_MATCH_DEFEAT_ENABLED_BOOLEAN)) {
            String header = localeService.getString(VisualsLocaleImpl.TITLE_MATCH_DEFEAT_HEADER).replace("{winner}", winner.getLeader().getUsername());
            String footer = localeService.getString(VisualsLocaleImpl.TITLE_MATCH_DEFEAT_FOOTER).replace("{winner}", winner.getLeader().getUsername());

            int fadeIn = localeService.getInt(VisualsLocaleImpl.TITLE_MATCH_DEFEAT_FADE_IN);
            int stay = localeService.getInt(VisualsLocaleImpl.TITLE_MATCH_DEFEAT_STAY);
            int fadeOut = localeService.getInt(VisualsLocaleImpl.TITLE_MATCH_DEFEAT_FADEOUT);

            loser.getPlayers().forEach(matchGamePlayer -> {
                Player player = matchGamePlayer.getTeamPlayer();
                if (player != null && player.isOnline()) {
                    sendResultTitle(player, header, footer, fadeIn, stay, fadeOut);
                }
            });
        }
    }

    /**
     * Sends the elo result message.
     * 发送Elo分数结果消息。
     *
     * @param winnerName   The name of the winner.
     *                     获胜者的名称。
     * @param loserName    The name of the loser.
     *                     失败者的名称。
     * @param oldEloWinner The old elo of the winner.
     *                     获胜者的旧Elo分数。
     * @param oldEloLoser  The old elo of the loser.
     *                     失败者的旧Elo分数。
     * @param newEloWinner The new elo of the winner.
     *                     获胜者的新Elo分数。
     * @param newEloLoser  The new elo of the loser.
     *                     失败者的新Elo分数。
     */
    public void sendEloResult(String winnerName, String loserName, int oldEloWinner, int oldEloLoser, int newEloWinner, int newEloLoser) {
        LocaleService localeService = this.plugin.getService(LocaleService.class);
        if (localeService.getBoolean(GameMessagesLocaleImpl.MATCH_ENDED_MATCH_RESULT_ELO_CHANGES_ENABLED_BOOLEAN)) {
            List<String> list = localeService.getStringList(GameMessagesLocaleImpl.MATCH_ENDED_MATCH_RESULT_ELO_CHANGES_FORMAT);

            list.replaceAll(string -> string
                    .replace("{winner}", winnerName)
                    .replace("{loser}", loserName)
                    .replace("{old-winner-elo}", String.valueOf(oldEloWinner))
                    .replace("{old-loser-elo}", String.valueOf(oldEloLoser))
                    .replace("{new-winner-elo}", String.valueOf(newEloWinner))
                    .replace("{new-loser-elo}", String.valueOf(newEloLoser))
                    .replace("{math-winner-elo}", String.valueOf(Math.abs(oldEloWinner - newEloWinner)))
                    .replace("{math-loser-elo}", String.valueOf(Math.abs(oldEloLoser - newEloLoser)))
            );

            list.forEach(this::notifyParticipants);
        }

    }

    /**
     * Sends the progress of the winner to the player using the ProgressService.
     * 使用ProgressService向玩家发送获胜者的进度信息。
     * The method no longer needs Division or Tier passed in.
     * 该方法不再需要传入Division或Tier。
     *
     * @param winner The winning player.
     *               获胜的玩家。
     */
    public void sendProgressToWinner(Player winner) {
        if (winner == null || !winner.isOnline()) {
            return;
        }

        Profile winnerProfile = AlleyPlugin.getInstance().getService(ProfileService.class).getProfile(winner.getUniqueId());
        if (winnerProfile == null) {
            return;
        }

        PlayerProgress progress = AlleyPlugin.getInstance().getService(ProgressService.class)
                .calculateProgress(winnerProfile, this.getKit().getName());

        List<String> message = new ArrayList<>();

        // Big title.
        // 大标题。
        message.add("&6&lProgress");

        // Division progress (unranked wins based).
        // 段位进度（基于非排位胜场）。
        if (progress.isMaxRank() && progress.getCurrentWins() >= progress.getWinsForNextTier()) {
            message.add(" &6&l◼ &fCONGRATULATIONS! You have reached the maximum rank!");
        } else {
            message.add(String.format(" &6&l◼ &fUnlock &6%s &fwith %d more %s!",
                    progress.getNextRankName(),
                    progress.getWinsRequired(),
                    progress.getWinOrWins()));
        }
        message.add(" &6&l◼ &7(" + progress.getProgressBar(12, "■") + "&7) &f" + progress.getProgressPercentage());

        // Level progress, ranked wins only (placed above the win streak line).
        // 等级进度，仅排位赛获胜时显示（放在连胜行的上方）。
        if (this.isRanked()) {
            int elo = winnerProfile.getProfileData().getElo();
            LevelService levelService = AlleyPlugin.getInstance().getService(LevelService.class);
            LevelData currentLevel = levelService.getLevel(elo);
            LevelData nextLevel = levelService.getNextLevel(elo);

            if (currentLevel != null && nextLevel != null) {
                int eloRequired = nextLevel.getMinElo() - elo;
                message.add(" &6&l◼ &fUnlock &6" + nextLevel.getDisplayName() + " &fwith " + eloRequired + " more elos!");
                message.add(" &6&l◼ &7(" + levelService.getProgressBar(elo) + "&7) &f" + levelService.getProgressDetails(elo));
            } else if (currentLevel != null) {
                message.add(" &6&l◼ &fCONGRATULATIONS! You have reached the maximum level!");
            }
        }

        // Win streak (current & best).
        // 连胜（当前及历史最佳）。
        int winstreak = 0;
        int bestWinstreak = 0;
        if (this.isRanked()) {
            var rankedKitData = winnerProfile.getProfileData().getRankedKitData().get(this.getKit().getName());
            if (rankedKitData != null) {
                winstreak = rankedKitData.getWinstreak();
                bestWinstreak = rankedKitData.getBestWinstreak();
            }
        } else {
            var unrankedKitData = winnerProfile.getProfileData().getUnrankedKitData().get(this.getKit().getName());
            if (unrankedKitData != null) {
                winstreak = unrankedKitData.getWinstreak();
                bestWinstreak = unrankedKitData.getBestWinstreak();
            }
        }
        message.add(" &6&l◼ &fWin Streak: &6" + winstreak + " &f(Best: &6" + bestWinstreak + "&f)");
        message.add("");

        message.forEach(line -> winner.sendMessage(CC.translate(line)));
    }


    /**
     * Method to get the old elo result.
     * 获取旧Elo结果的方法。
     *
     * @return The old elo result.
     *         旧Elo结果。
     */
    public @NotNull OldEloResult getOldEloResult(GameParticipant<MatchGamePlayer> winner, GameParticipant<MatchGamePlayer> loser) {
        int oldWinnerElo = winner.getLeader().getElo();
        int oldLoserElo = loser.getLeader().getElo();
        return new OldEloResult(oldWinnerElo, oldLoserElo);
    }

    /**
     * Method to get the elo result.
     * 获取Elo结果的方法。
     *
     * @param oldWinnerElo The old elo of the winner.
     *                     获胜者的旧Elo分数。
     * @param oldLoserElo  The old elo of the loser.
     *                     失败者的旧Elo分数。
     * @return The elo result.
     *         Elo结果。
     */
    public @NotNull EloResult getEloResult(int oldWinnerElo, int oldLoserElo) {
        EloCalculator eloCalculator = AlleyPlugin.getInstance().getService(EloCalculator.class);
        int newWinnerElo = eloCalculator.determineNewElo(oldWinnerElo, oldLoserElo, true);
        int newLoserElo = eloCalculator.determineNewElo(oldLoserElo, oldWinnerElo, false);
        return new EloResult(newWinnerElo, newLoserElo);
    }

    /**
     * Method to handle the winner.
     * 处理获胜者的方法。
     *
     * @param elo    The new elo of the winner.
     *               获胜者的新Elo分数。
     * @param winner The winner of the match.
     *               比赛的获胜者。
     */
    public void handleWinner(int elo, GameParticipant<MatchGamePlayer> winner) {
        Profile winnerProfile = AlleyPlugin.getInstance().getService(ProfileService.class).getProfile(winner.getLeader().getUuid());
        int eloGain = Math.max(0, elo - winner.getLeader().getElo());
        var winnerKitData = winnerProfile.getProfileData().getRankedKitData().get(getKit().getName());
        if (winnerKitData != null) {
            winnerKitData.setElo(elo);
            winnerKitData.incrementWins();
        }
        winnerProfile.getProfileData().incrementRankedWins();
        winnerProfile.getProfileData().updateElo(winnerProfile);
        // Ranked wins also advance the kit's division progression.
        // 排位胜场同样推进该套件的段位进度。
        winnerProfile.getProfileData().refreshDivision(getKit().getName());
        ChallengeService challengeService = AlleyPlugin.getInstance().getService(ChallengeService.class);
        challengeService.recordProgress(winnerProfile, ChallengeType.WINS, 1);
        challengeService.recordProgress(winnerProfile, ChallengeType.ELO, eloGain);
    }

    /**
     * Method to handle the loser.
     * 处理失败者的方法。
     *
     * @param elo   The new elo of the loser.
     *              失败者的新Elo分数。
     * @param loser The loser of the match.
     *              比赛的失败者。
     */
    public void handleLoser(int elo, GameParticipant<MatchGamePlayer> loser) {
        Profile loserProfile = AlleyPlugin.getInstance().getService(ProfileService.class).getProfile(loser.getLeader().getUuid());
        var loserKitData = loserProfile.getProfileData().getRankedKitData().get(getKit().getName());
        if (loserKitData != null) {
            loserKitData.setElo(elo);
            loserKitData.incrementLosses();
        }
        loserProfile.getProfileData().incrementRankedLosses();
        loserProfile.getProfileData().updateElo(loserProfile);
    }

    @Override
    public boolean canStartRound() {
        return false;
    }

    @Override
    public boolean canEndRound() {
        return (this.getParticipantA().isAllDead() || this.getParticipantB().isAllDead())
                || (this.getParticipantA().getAllPlayers().stream().allMatch(MatchGamePlayer::isDisconnected)
                || this.getParticipantB().getAllPlayers().stream().allMatch(MatchGamePlayer::isDisconnected));
    }

    @Override
    public boolean canEndMatch() {
        return true;
    }

    @Override
    public void sendPlayerVersusPlayerMessage() {
        LocaleService localeService = this.plugin.getService(LocaleService.class);

        GameParticipant<MatchGamePlayer> participantA = this.getParticipants().get(0);
        GameParticipant<MatchGamePlayer> participantB = this.getParticipants().get(1);

        if (this.isTeamMatch()) {
            if (localeService.getBoolean(GameMessagesLocaleImpl.MATCH_PLAYER_VS_PLAYER_TEAM_ENABLED_BOOLEAN)) {
                int teamSizeA = participantA.getPlayerSize();
                int teamSizeB = participantB.getPlayerSize();

                List<String> message = localeService.getStringList(GameMessagesLocaleImpl.MATCH_PLAYER_VS_PLAYER_TEAM_FORMAT);
                for (String line : message) {
                    String formatted = line
                            .replace("{teamA-leader}", participantA.getLeader().getUsername())
                            .replace("{teamA-size}", String.valueOf(teamSizeA))
                            .replace("{teamB-leader}", participantB.getLeader().getUsername())
                            .replace("{teamB-size}", String.valueOf(teamSizeB));
                    this.sendMessage(formatted);
                }
            }
        } else {
            if (localeService.getBoolean(GameMessagesLocaleImpl.MATCH_PLAYER_VS_PLAYER_SOLO_ENABLED_BOOLEAN)) {
                List<String> message = localeService.getStringList(GameMessagesLocaleImpl.MATCH_PLAYER_VS_PLAYER_SOLO_FORMAT);
                for (String line : message) {
                    String formatted = line
                            .replace("{playerA}", participantA.getLeader().getUsername())
                            .replace("{playerB}", participantB.getLeader().getUsername());
                    this.sendMessage(formatted);
                }
            }
        }
    }

    @Override
    public void handleRespawn(Player player) {
        // Match deaths are synthetic, so the victim is normally alive here and this is
        // a no-op. The guard only rescues a genuine death from an unhandled path.
        if (player.isDead()) player.spigot().respawn();
        PlayerUtil.reset(player, false, true);
    }

    @Override
    public void handleDeathItemDrop(Player player, PlayerDeathEvent event) {
        if (this.getKit().isSettingEnabled(KitSettingDropItemsImpl.class)) {
            ListenerUtil.clearDroppedItemsOnDeath(event, player);
        } else {
            event.getDrops().clear();
        }
    }

    @Override
    public void handleDisconnect(Player player) {
        if (!(this.getState() == MatchState.STARTING || this.getState() == MatchState.RUNNING)) {
            return;
        }

        Profile profile = this.plugin.getService(ProfileService.class).getProfile(player.getUniqueId());
        this.sendMessage(profile.getFancyName() + " &fdisconnected.");

        MatchGamePlayer gamePlayer = this.getFromAllGamePlayers(player);
        if (gamePlayer != null) {
            gamePlayer.setDisconnected(true);
            gamePlayer.setEliminated(true);
            if (!gamePlayer.isDead()) {
                this.handleDeath(player, EntityDamageEvent.DamageCause.CUSTOM);
            }
        }

        if (player.isOnline()) {
            this.finalizePlayer(player);
        }
    }

    /**
     * Gives the base raiding kit to the player based on their team.
     * 根据玩家所属队伍为其发放基础掠夺工具包。
     *
     * @param player The player to give the kit to.
     *               要接收工具包的玩家。
     */
    public void determineRolesAndGiveKit(Player player) {
        if (this.getParticipantA() == null || this.getParticipantB() == null) {
            return;
        }

        Kit parentKit = this.getKit();
        if (parentKit == null) {
            Logger.error("&cCould not determine the parent kit for the raiding match.");
            return;
        }

        BaseRaiderRole role = getParticipantA().containsPlayer(player.getUniqueId())
                ? BaseRaiderRole.TRAPPER
                : BaseRaiderRole.RAIDER;

        Kit kitToGive = AlleyPlugin.getInstance().getService(BaseRaidingService.class).getRaidingKitByRole(parentKit, role);
        if (kitToGive == null) {
            Logger.info("&cNo kit found for role: " + role.name() + " linked to parent kit.");
            return;
        }

        MatchGamePlayerData data = this.getGamePlayer(player).getData();
        data.setRole(role);

        ProfileService profileService = AlleyPlugin.getInstance().getService(ProfileService.class);
        Profile profile = profileService.getProfile(player.getUniqueId());
        java.util.List<LayoutData> nonNullLayouts = profile.getProfileData()
                .getLayoutData().getNonNullLayouts(kitToGive.getName());

        if (nonNullLayouts.size() > 1) {
            AlleyPlugin.getInstance().getService(LayoutService.class).giveBooks(player, kitToGive.getName());
        } else if (nonNullLayouts.size() == 1) {
            player.getInventory().setContents(nonNullLayouts.get(0).getItems());
            if (!kitToGive.isSettingEnabled(KitSettingOldOffhand.class)) {
                player.getInventory().setItemInOffHand(nonNullLayouts.get(0).getOffhand());
            }
        } else {
            player.getInventory().setContents(kitToGive.getItems());
            if (!kitToGive.isSettingEnabled(KitSettingOldOffhand.class)) {
                player.getInventory().setItemInOffHand(kitToGive.getOffhand());
            }
        }

        player.getInventory().setArmorContents(kitToGive.getArmor());
        player.updateInventory();
    }
}
