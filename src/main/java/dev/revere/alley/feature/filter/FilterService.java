package dev.revere.alley.feature.filter;

import dev.revere.alley.bootstrap.lifecycle.Service;
import org.bukkit.entity.Player;

/**
 * @author Remi
 * @project alley-practice
 * @date 2/07/2025
 */
public interface FilterService extends Service {
    /**
     * Replaces any profane words in a message with asterisks.
     *
     * @param message The message to censor.
     * @return The censored message.
     */
    String censorWords(String message);

    /**
     * Checks if a message contains any profane words from the filtered list.
     *
     * @param message The message to check.
     * @return True if profanity is detected, false otherwise.
     */
    boolean isProfanity(String message);

    /**
     * Sends a notification to all online staff members about a profane message.
     *
     * @param message  The original message that was sent.
     * @param offender The player who sent the message.
     */
    void notifyStaff(String message, Player offender);

    /**
     * Re-reads the profanity filter settings from settings.yml (used by /alley reload).
     * 从 settings.yml 重新读取脏话过滤器设置（由 /alley reload 调用）。
     */
    void reloadFilteredWords();
}