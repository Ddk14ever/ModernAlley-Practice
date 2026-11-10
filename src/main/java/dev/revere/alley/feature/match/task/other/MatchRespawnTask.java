package dev.revere.alley.feature.match.task.other;

import dev.revere.alley.AlleyPlugin;
import dev.revere.alley.common.reflect.ReflectionService;
import dev.revere.alley.common.reflect.internal.types.TitleReflectionServiceImpl;
import dev.revere.alley.common.text.CC;
import dev.revere.alley.core.locale.LocaleService;
import dev.revere.alley.core.locale.internal.impl.VisualsLocaleImpl;
import dev.revere.alley.core.locale.internal.impl.message.GameMessagesLocaleImpl;
import dev.revere.alley.feature.match.Match;
import dev.revere.alley.feature.match.MatchState;
import dev.revere.alley.core.profile.Profile;
import dev.revere.alley.core.profile.ProfileService;
import dev.revere.alley.core.profile.enums.ProfileState;
import dev.revere.alley.feature.match.utility.MatchRespawnHealth;
import org.bukkit.GameMode;
import org.bukkit.entity.Player;
import org.bukkit.scheduler.BukkitRunnable;

import java.util.List;

/**
 * @author Emmy
 * @project Alley
 * @since 23/05/2025
 * 比赛重生任务 - 处理玩家死亡后的倒计时重生逻辑。
 */
public class MatchRespawnTask extends BukkitRunnable {
    protected final Player player;
    protected final Match match;
    private int count;

    /**
     * Constructor for the MatchRespawnTask class.
     * MatchRespawnTask 类的构造函数。
     *
     * @param player The player to respawn.
     *        要重生的玩家。
     * @param match  The match instance.
     *        比赛实例。
     * @param count  The countdown time in seconds.
     *        倒计时时间（秒）。
     */
    public MatchRespawnTask(Player player, Match match, int count) {
        this.player = player;
        this.match = match;
        this.count = count;
    }

    @Override
    public void run() {
        // Check ownership/state before count==0: a stale timer must not revive
        // someone after the match ended or after they entered another match.
        Profile profile = AlleyPlugin.getInstance().getService(ProfileService.class)
                .getProfiles().get(this.player.getUniqueId());
        if (!this.player.isOnline() || profile == null || profile.getMatch() != this.match
                || profile.getState() != ProfileState.PLAYING
                || this.player.getGameMode() != GameMode.SPECTATOR
                || (this.match.getState() != MatchState.RUNNING && this.match.getState() != MatchState.STARTING)) {
            this.cancel();
            return;
        }

        MatchRespawnHealth.restore(this.player);
        // The native-death safety net completes on the next tick. Do not revive
        // a dead entity by setting health or consume its waiting countdown yet.
        if (this.player.isDead()) return;

        if (this.count <= 0) {
            this.cancel();
            this.match.handleRespawn(this.player);
            return;
        }

        LocaleService localeService = AlleyPlugin.getInstance().getService(LocaleService.class);
        if (localeService.getBoolean(VisualsLocaleImpl.TITLE_MATCH_RESPAWNING_ENABLED_BOOLEAN)) {
            String header = localeService.getString(VisualsLocaleImpl.TITLE_MATCH_RESPAWNING_HEADER).replace("{seconds}", String.valueOf(this.count));
            String footer = localeService.getString(VisualsLocaleImpl.TITLE_MATCH_RESPAWNING_FOOTER).replace("{seconds}", String.valueOf(this.count));
            int fadeIn = localeService.getInt(VisualsLocaleImpl.TITLE_MATCH_RESPAWNING_FADE_IN);
            int stay = localeService.getInt(VisualsLocaleImpl.TITLE_MATCH_RESPAWNING_STAY);
            int fadeOut = localeService.getInt(VisualsLocaleImpl.TITLE_MATCH_RESPAWNING_FADEOUT);

            AlleyPlugin.getInstance().getService(ReflectionService.class).getReflectionService(TitleReflectionServiceImpl.class).sendTitle(
                    player,
                    header,
                    footer,
                    fadeIn, stay, fadeOut
            );
        }

        List<String> messageFormat = localeService.getStringList(GameMessagesLocaleImpl.MATCH_RESPAWNING_MESSAGE_FORMAT);
        if (localeService.getBoolean(GameMessagesLocaleImpl.MATCH_RESPAWNING_MESSAGE_ENABLED_BOOLEAN)) {
            messageFormat.forEach(message -> this.player.sendMessage(CC.translate(message.replace("{seconds}", String.valueOf(this.count)))));
        }

        this.count--;
    }
}
