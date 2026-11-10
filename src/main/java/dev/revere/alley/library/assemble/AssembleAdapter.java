package dev.revere.alley.library.assemble;

import org.bukkit.entity.Player;

import java.util.List;

public interface AssembleAdapter {

    /** False while content is unavailable; retain the last rendered board. */
    default boolean isReady(Player player) {
        return true;
    }

    /**
     * Get's the scoreboard title.
     * 获取记分板标题。
     *
     * @param player who's title is being displayed.
     *        正在显示其标题的玩家。
     * @return title.
     *         标题。
     */
    String getTitle(Player player);

    /**
     * Get's the scoreboard lines.
     * 获取记分板行。
     *
     * @param player who's lines are being displayed.
     *        正在显示其行的玩家。
     * @return lines.
     *         行列表。
     */
    List<String> getLines(Player player);

}
