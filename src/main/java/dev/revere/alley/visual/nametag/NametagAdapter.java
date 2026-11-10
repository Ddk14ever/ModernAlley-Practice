package dev.revere.alley.visual.nametag;

import dev.revere.alley.common.logger.Logger;
import dev.revere.alley.common.reflect.Reflection;
import dev.revere.alley.common.reflect.internal.types.DefaultReflectionImpl;
import dev.revere.alley.visual.nametag.internal.NametagServiceImpl;
import dev.revere.alley.library.assemble.utility.PlayerScoreboards;
import dev.revere.alley.library.assemble.utility.AssembleUtility;
import dev.revere.alley.AlleyPlugin;
import dev.revere.alley.common.version.ClientVersionService;
import lombok.Getter;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.entity.Player;
import org.bukkit.scoreboard.Scoreboard;
import org.bukkit.scoreboard.Team;

import java.util.Arrays;
import java.util.Collection;

/**
 * 名字标签适配器，封装一个记分板队伍，负责向特定玩家发送队伍创建、添加和移除数据包。
 * @author Remi
 * @project alley-practice
 * @date 27/06/2025
 */
@Getter
public class NametagAdapter {
    private final NametagServiceImpl engine;
    private final String name;
    private final String prefix;
    private final String suffix;
    private final NametagVisibility visibility;
    private final Reflection reflection = DefaultReflectionImpl.INSTANCE;

    public NametagAdapter(NametagServiceImpl engine, String name, String prefix, String suffix, NametagVisibility visibility) {
        this.engine = engine;
        this.name = name;
        this.prefix = prefix;
        this.suffix = suffix;
        this.visibility = visibility;
    }

    /**
     * Checks if this adapter represents the same style as a NametagView.
     * 检查此适配器是否表示与 NametagView 相同的样式。
     *
     * @param view The view to compare against.
     *        要与之比较的视图。
     * @return True if the prefix and suffix match.
     *         如果前缀和后缀匹配则返回 true。
     */
    public boolean represents(NametagView view) {
        return this.prefix.equals(view.getPrefix()) && this.suffix.equals(view.getSuffix());
    }

    /**
     * Sends the team creation packet to a specific player using modern Scoreboard API.
     * 使用现代 Scoreboard API 将队伍创建数据包发送给指定玩家。
     *
     * @param player The player to send the packet to.
     *        接收数据包的玩家。
     */
    public void sendCreationPacket(Player player) {
        Scoreboard board = PlayerScoreboards.get(player);

        Team team = board.getTeam(this.name);
        if (team == null) {
            team = board.registerNewTeam(this.name);
        }

        ClientVersionService versions = AlleyPlugin.getInstance().getService(ClientVersionService.class);
        boolean legacy = versions != null && versions.isLegacy113(player);
        boolean legacy17 = versions != null && versions.isLegacy17(player);
        applyTeamAppearance(team, legacy, legacy17);
    }

    void applyTeamAppearance(Team team, boolean legacy, boolean legacy17) {
        team.setDisplayName(this.name);
        team.setPrefix(legacy ? AssembleUtility.truncateLegacy(this.prefix, 16) : this.prefix);
        team.setSuffix(legacy ? AssembleUtility.truncateLegacy(this.suffix, 16) : this.suffix);

        // ViaRewind 4.1.3's 1.8 -> 1.7 team-color update selects a color-specific
        // sidebar, sending an empty objective when none exists. Keep the default
        // team color for 1.7 viewers; the legacy prefix still colors the nametag.
        ChatColor teamColor = parseTeamColor(this.prefix);
        if (!legacy17 && teamColor != null && team.getColor() != teamColor) {
            team.setColor(teamColor);
        }

        // Set nametag visibility
        // 设置名字标签可见性
        switch (this.visibility) {
            case ALWAYS:
                team.setOption(Team.Option.NAME_TAG_VISIBILITY, Team.OptionStatus.ALWAYS);
                break;
            case NEVER:
                team.setOption(Team.Option.NAME_TAG_VISIBILITY, Team.OptionStatus.NEVER);
                break;
            case HIDE_FOR_OTHER_TEAMS:
                team.setOption(Team.Option.NAME_TAG_VISIBILITY, Team.OptionStatus.FOR_OTHER_TEAMS);
                break;
            case HIDE_FOR_OWN_TEAM:
                team.setOption(Team.Option.NAME_TAG_VISIBILITY, Team.OptionStatus.FOR_OWN_TEAM);
                break;
        }
    }

    /**
     * Adds a player to this team for a specific viewer.
     * 为特定观察者将玩家添加到此队伍中。
     *
     * @param player The player to add to the team.
     *        要添加到队伍的玩家。
     * @param viewer The player who needs to see this change.
     *        需要看到此变更的观察者玩家。
     */
    public void addPlayer(Player player, Player viewer) {
        Scoreboard board = PlayerScoreboards.get(viewer);
        if (board == null) return;

        Team team = board.getTeam(this.name);
        if (team == null) {
            this.sendCreationPacket(viewer);
            team = board.getTeam(this.name);
        }

        if (team != null && !team.hasEntry(player.getName())) {
            team.addEntry(player.getName());
        }
    }

    /**
     * Removes a player from this team for a specific viewer.
     * 为特定观察者将玩家从此队伍中移除。
     *
     * @param player The player to remove from the team.
     *        要从队伍中移除的玩家。
     * @param viewer The player who needs to see this change.
     *        需要看到此变更的观察者玩家。
     */
    public void removePlayer(Player player, Player viewer) {
        Scoreboard board = viewer.getScoreboard();
        if (board == null) return;

        Team team = board.getTeam(this.name);
        if (team != null && team.hasEntry(player.getName())) {
            team.removeEntry(player.getName());
        }
    }

    /**
     * Extracts the first color code (e.g. {@code &c} or {@code §9}) from a nametag prefix,
     * returning it as a ChatColor so the scoreboard team can be colored the same way.
     * 从名字标签前缀中解析第一个颜色代码，用于给计分板队伍着色。
     */
    private ChatColor parseTeamColor(String prefix) {
        if (prefix == null || prefix.isEmpty()) return null;

        for (int i = 0; i < prefix.length() - 1; i++) {
            char c = prefix.charAt(i);
            if (c == '&' || c == '§') {
                ChatColor color = ChatColor.getByChar(prefix.charAt(i + 1));
                if (color != null && color.isColor()) {
                    return color;
                }
            }
        }
        return null;
    }
}
