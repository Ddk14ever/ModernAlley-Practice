package dev.revere.alley.feature.match;

import dev.revere.alley.bootstrap.lifecycle.Service;
import dev.revere.alley.feature.arena.Arena;
import dev.revere.alley.feature.kit.Kit;
import dev.revere.alley.feature.event.skywars.SkyWarsMatch;
import dev.revere.alley.feature.match.model.GameParticipant;
import dev.revere.alley.feature.match.model.internal.MatchGamePlayer;
import dev.revere.alley.feature.queue.Queue;
import dev.revere.alley.feature.tournament.model.Tournament;

import java.util.List;

/**
 * Service interface for managing matches.
 * 比赛管理服务接口。
 * @author Remi
 * @project alley-practice
 * @date 2/07/2025
 */
public interface MatchService extends Service {
    /**
     * Gets a list of all currently active matches.
     * 获取所有当前活跃比赛的列表。
     *
     * @return An unmodifiable list of active matches.
     *         活跃比赛的不可修改列表。
     */
    List<Match> getMatches();

    /**
     * Gets the list of commands that are blocked for players while in a match.
     * 获取玩家在比赛中被屏蔽的指令列表。
     *
     * @return A list of blocked command strings.
     *         被屏蔽的指令字符串列表。
     */
    List<String> getBlockedCommands();

    /**
     * Adds a match to the service's tracking list.
     * 将比赛添加到服务的跟踪列表中。
     * Should be called right after a match is created.
     * 应在比赛创建后立即调用。
     *
     * @param match The match to add.
     *              要添加的比赛。
     */
    void addMatch(Match match);

    /**
     * Removes a match from the service's tracking list.
     * 从服务的跟踪列表中移除比赛。
     * Should be called when a match ends.
     * 应在比赛结束时调用。
     *
     * @param match The match to remove.
     *              要移除的比赛。
     */
    void removeMatch(Match match);

    /**
     * Creates an unregistered, unstarted two-participant match using the first
     * enabled mode setting registered in the match factory.
     *
     * @param queue        queue associated with the match, or {@code null}
     * @param kit          kit whose settings select the concrete match type
     * @param arena        arena used by the match
     * @param isRanked     whether the match is ranked
     * @param participantA first participant
     * @param participantB second participant
     * @return the setting-specific match implementation, or the default match
     *         implementation when no registered mode setting is enabled
     */
    Match createMatch(Queue queue,
                      Kit kit,
                      Arena arena,
                      boolean isRanked,
                      GameParticipant<MatchGamePlayer> participantA,
                      GameParticipant<MatchGamePlayer> participantB);

    /**
     * Creates, starts, and registers a new match with the given parameters.
     * 使用给定参数创建、启动并注册新的比赛。
     *
     * @param kit              The kit to be used in the match.
     *                         比赛中使用的装备包。
     * @param arena            The arena where the match will take place.
     *                         比赛进行的竞技场。
     * @param participantA     The first participant in the match.
     *                         比赛中的第一个参与者。
     * @param participantB     The second participant in the match.
     *                         比赛中的第二个参与者。
     * @param teamMatch        Whether this is a team-based match.
     *                         是否为团队比赛。
     * @param affectStatistics Whether this match should affect player stats (Elo, wins/losses).
     *                         此比赛是否影响玩家统计数据（Elo、胜/负场）。
     * @param isRanked         Whether this match is ranked.
     *                         此比赛是否为排位赛。
     */
    void createAndStartMatch(Kit kit,
                             Arena arena,
                             GameParticipant<MatchGamePlayer> participantA,
                             GameParticipant<MatchGamePlayer> participantB,
                             boolean teamMatch,
                             boolean affectStatistics,
                             boolean isRanked);

    /**
     * Creates, starts, and registers a new match with the given parameters.
     * 使用给定参数创建、启动并注册新的比赛。
     *
     * @param kit          The kit to be used in the match.
     *                     比赛中使用的装备包。
     * @param arena        The arena where the match will take place.
     *                     比赛进行的竞技场。
     * @param participants A list of participants in the match.
     *                     比赛参与者列表。
     */
    void createAndStartMatch(Kit kit,
                             Arena arena,
                             List<GameParticipant<MatchGamePlayer>> participants);

    SkyWarsMatch createAndStartSkyWarsMatch(Kit kit, Arena arena,
                                            List<GameParticipant<MatchGamePlayer>> participants, Kit resourceKit);

    /**
     * Creates, starts, and registers a new tournament match.
     * 创建、启动并注册新的锦标赛比赛。
     * This method will use the internal match factory to create the correct match type
     * 此方法将使用内部比赛工厂创建正确的比赛类型
     * (e.g., BedMatch) and attach the tournament context to it.
     * （如 BedMatch）并附加锦标赛上下文。
     *
     * @param tournament The tournament this match belongs to.
     *                   此比赛所属的锦标赛。
     * @param kit        The kit to be used.
     *                   要使用的装备包。
     * @param arena      The arena where the match will take place.
     *                   比赛进行的竞技场。
     * @param pA         The first participant.
     *                   第一个参与者。
     * @param pB         The second participant.
     *                   第二个参与者。
     */
    void createTournamentMatch(Tournament tournament, Kit kit, Arena arena, GameParticipant<MatchGamePlayer> pA, GameParticipant<MatchGamePlayer> pB);

    /**
     * Re-reads the match-blocked commands from settings.yml (used by /alley reload).
     * 从 settings.yml 重新读取比赛禁用指令列表（由 /alley reload 调用）。
     */
    void reloadBlockedCommands();
}
