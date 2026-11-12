package dev.revere.alley.feature.bot;

import dev.revere.alley.bootstrap.lifecycle.Service;
import dev.revere.alley.feature.bot.match.BotMatchSession;
import dev.revere.alley.feature.kit.Kit;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;

import java.util.Map;

public interface BotService extends Service {
    Map<String, BotDifficultyProfile> getProfiles();

    int getActivePlayerCount();

    BotMatchSession getSession(Player player);

    BotMatchSession getSession(Entity entity);

    boolean isKitSupported(Kit kit);

    boolean startMatch(Player player, Kit kit, String difficultyId);

    void endMatch(Player player, boolean playerWon);

    /** Immediately closes the player's Bot session when leaving voluntarily. */
    boolean leaveMatch(Player player);

    /** Cleans up an ending Bot session before starting the requested rematch. */
    boolean restartMatch(Player player, Kit kit, String difficultyId);

    /** Re-reads the bot difficulty profiles from providers/bots.yml (used by /alley reload). */
    void reloadProfiles();
}
