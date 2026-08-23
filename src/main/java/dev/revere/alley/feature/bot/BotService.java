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

    /** Re-reads the bot difficulty profiles from providers/bots.yml (used by /alley reload). */
    void reloadProfiles();
}
