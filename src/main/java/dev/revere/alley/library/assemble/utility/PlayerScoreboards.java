package dev.revere.alley.library.assemble.utility;

import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.scoreboard.Scoreboard;
import org.bukkit.scoreboard.ScoreboardManager;

/** Shared scoreboard access for sidebar, nametags and below-name health. */
public final class PlayerScoreboards {
    private PlayerScoreboards() {}

    /** Must be called on the server thread; never writes to the global main board. */
    public static Scoreboard get(Player player) {
        if (!Bukkit.isPrimaryThread()) throw new IllegalStateException("Scoreboard access must be synchronous");
        ScoreboardManager manager = Bukkit.getScoreboardManager();
        Scoreboard board = player.getScoreboard();
        if (board == manager.getMainScoreboard()) {
            board = manager.getNewScoreboard();
            player.setScoreboard(board);
        }
        return board;
    }
}
