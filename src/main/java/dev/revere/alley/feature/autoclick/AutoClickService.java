package dev.revere.alley.feature.autoclick;

import dev.revere.alley.bootstrap.lifecycle.Service;
import org.bukkit.entity.Player;

/**
 * Server-side autoclick sessions. A session is always opt-in and is enabled
 * only by the {@code /autoclick} command.
 */
public interface AutoClickService extends Service {

    /** Toggle the session for a player and return the new state. */
    boolean toggle(Player player);

    /** Disable a player's session and restore any client-side probe block. */
    void disable(Player player);

    /** Return whether a player currently has autoclick enabled. */
    boolean isEnabled(Player player);

    /** Re-reads the autoclick.yml settings from disk (used by /alley reload). */
    void reloadConfiguration();
}
