package dev.revere.alley.feature.match.internal.types;

import dev.revere.alley.common.PlayerUtil;
import dev.revere.alley.feature.arena.Arena;
import dev.revere.alley.feature.kit.Kit;
import dev.revere.alley.feature.match.model.GameParticipant;
import dev.revere.alley.feature.match.model.MatchGamePlayerData;
import dev.revere.alley.feature.match.model.internal.MatchGamePlayer;
import dev.revere.alley.feature.queue.Queue;
import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.scheduler.BukkitRunnable;

import dev.revere.alley.AlleyPlugin;

/**
 * @author Emmy
 * @project Alley
 * @date 5/21/2024
 */
public class LivesMatch extends DefaultMatch {

    /**
     * Constructor for the MatchLivesImpl class.
     * MatchLivesImpl类的构造函数。
     *
     * @param queue        The queue of the match.
     *                     比赛的队列。
     * @param kit          The kit of the match.
     *                     比赛的工具包。
     * @param arena        The arena of the match.
     *                     比赛的竞技场。
     * @param ranked       Whether the match is ranked.
     *                     比赛是否为排位赛。
     * @param participantA The first participant.
     *                     第一个参赛方。
     * @param participantB The second participant.
     *                     第二个参赛方。
     */
    public LivesMatch(Queue queue, Kit kit, Arena arena, boolean ranked, GameParticipant<MatchGamePlayer> participantA, GameParticipant<MatchGamePlayer> participantB) {
        super(queue, kit, arena, ranked, participantA, participantB);
    }

    @Override
    public boolean canStartRound() {
        return getParticipantA().getLeader().getData().getLives() > 0 && getParticipantB().getLeader().getData().getLives() > 0;
    }

    @Override
    public boolean canEndRound() {
        return (getParticipantA().isAllEliminated() || getParticipantB().isAllEliminated())
                || (this.getParticipantA().getAllPlayers().stream().allMatch(MatchGamePlayer::isDisconnected)
                || this.getParticipantB().getAllPlayers().stream().allMatch(MatchGamePlayer::isDisconnected));
    }

    @Override
    public void setupPlayer(Player player) {
        super.setupPlayer(player);

        player.setInvulnerable(false);

        // Lives count controls round elimination only. Every life always uses
        // the normal ten-heart health pool.
        player.setMaxHealth(20.0D);
        player.setHealth(player.getMaxHealth());
    }

    /**
     * Reduces the life count of a player in the match.
     * 减少比赛中玩家的生命数。
     *
     * @param player The player whose life is to be reduced.
     *               要减少生命数的玩家。
     * @param data   The MatchGamePlayerData of the player whose life is to be reduced.
     *               要减少生命数的玩家的MatchGamePlayerData数据。
     */
    public void reduceLife(Player player, MatchGamePlayerData data) {
        data.setLives(data.getLives() - 1);
        player.setMaxHealth(20.0D);
        player.setHealth(player.getMaxHealth());
    }

    @Override
    public void handleParticipant(Player player, MatchGamePlayer gamePlayer) {
        MatchGamePlayerData data = this.getGamePlayer(player).getData();
        this.reduceLife(player, data);

        if (data.getLives() <= 0) {
            gamePlayer.setEliminated(true);
        }

        super.handleParticipant(player, gamePlayer);
    }

    @Override
    public void handleRespawn(Player player) {
        PlayerUtil.reset(player, true, false);

        // A respawned Lives player gets one second of complete protection,
        // independent of the kit's normal hurt-resistance configuration.
        player.setMaxHealth(20.0D);
        player.setHealth(player.getMaxHealth());
        player.setInvulnerable(true);
        new BukkitRunnable() {
            @Override
            public void run() {
                if (player.isOnline() && player.isValid()) {
                    player.setInvulnerable(false);
                }
            }
        }.runTaskLater(AlleyPlugin.getInstance(), 20L);

        Location spawnLocation = this.getParticipants().get(0).containsPlayer(player.getUniqueId()) ? this.getArena().getPos1() : this.getArena().getPos2();
        player.teleport(spawnLocation);

        this.giveLoadout(player, this.getKit());
        this.applyColorKit(player);
    }
}
