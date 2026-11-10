package dev.revere.alley.visual.tablist.task;

import dev.revere.alley.AlleyPlugin;
import dev.revere.alley.visual.tablist.TablistAdapter;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.scheduler.BukkitRunnable;

/**
 * 定时任务，定期为所有在线玩家更新制表符列表显示。
 * @author Emmy
 * @project Alley
 * @date 07/09/2024 - 15:23
 */
public class TablistUpdateTask extends BukkitRunnable {
    protected final TablistAdapter tablistAdapterVisualizer;

    public TablistUpdateTask() {
        this.tablistAdapterVisualizer = new TablistImpl(AlleyPlugin.getInstance());
    }

    @Override
    public void run() {
        for (Player player : Bukkit.getOnlinePlayers()) {
            try {
                this.tablistAdapterVisualizer.update(player);
            } catch (Exception exception) {
                AlleyPlugin.getInstance().getLogger().log(java.util.logging.Level.WARNING,
                        "Unable to update tab list for " + player.getName(), exception);
            }
        }
    }
}
