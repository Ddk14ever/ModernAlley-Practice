package dev.revere.alley.feature.match.utility;

import org.bukkit.entity.Player;

/** Restores waiting players without resetting their inventory or respawn state. */
public final class MatchRespawnHealth {
    private MatchRespawnHealth() {}

    public static void restore(Player player) {
        // A genuine native death must finish respawning before health is written.
        if (!player.isOnline() || player.isDead()) return;
        double maximum = player.getMaxHealth();
        if (player.getHealth() < maximum) player.setHealth(maximum);
        player.setFireTicks(0);
        player.setFallDistance(0.0F);
    }
}
