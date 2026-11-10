package dev.revere.alley.library.assemble;

import dev.revere.alley.common.version.ClientVersionService;
import dev.revere.alley.library.assemble.events.AssembleBoardCreatedEvent;
import lombok.Getter;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.entity.Player;
import org.bukkit.scoreboard.DisplaySlot;
import org.bukkit.scoreboard.Objective;
import org.bukkit.scoreboard.Scoreboard;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

@Getter
public class AssembleBoard {
    private final AssembleServiceImpl assembleServiceImpl;

    private final List<AssembleBoardEntry> entries;
    private final List<String> identifiers;

    private final UUID uuid;
    private final Player player;
    private Scoreboard attachedScoreboard;

    /**
     * Assemble Board.
     * Assemble 记分板。
     *
     * @param player   that the board belongs to.
     *        记分板所属的玩家。
     * @param assembleServiceImpl instance.
     *        AssembleServiceImpl 实例。
     */
    public AssembleBoard(Player player, AssembleServiceImpl assembleServiceImpl) {
        this.assembleServiceImpl = assembleServiceImpl;
        this.entries = new ArrayList<>();
        this.identifiers = new ArrayList<>();
        this.uuid = player.getUniqueId();
        this.player = player;
        this.setup(player);
    }

    /**
     * Get's a player's bukkit scoreboard.
     * 获取玩家的 Bukkit 记分板。
     *
     * @return either existing scoreboard or new scoreboard.
     *         现有的记分板或新的记分板。
     */
    public Scoreboard getScoreboard() {
        Scoreboard scoreboard = this.assembleServiceImpl.isHook() ? player.getScoreboard()
                : dev.revere.alley.library.assemble.utility.PlayerScoreboards.get(player);
        if (this.attachedScoreboard != scoreboard) {
            boolean replaced = this.attachedScoreboard != null;
            clearSidebar();
            this.attachedScoreboard = scoreboard;
            if (replaced && this.assembleServiceImpl.isCallEvents()) {
                Bukkit.getPluginManager().callEvent(new AssembleBoardCreatedEvent(this));
            }
        }
        return scoreboard;
    }

    /** Remove only our sidebar, preserving nametag teams and health objectives. */
    public void clearSidebar() {
        while (!this.entries.isEmpty()) this.entries.get(this.entries.size() - 1).remove();
        if (this.attachedScoreboard != null) {
            Objective objective = this.attachedScoreboard.getObjective("Assemble");
            if (objective != null) objective.unregister();
        }
    }

    /**
     * Get's the player's scoreboard objective.
     * 获取玩家的记分板目标(objective)。
     *
     * @return either existing objecting or new objective.
     *         现有的目标或新的目标。
     */
    public Objective getObjective() {
        Scoreboard scoreboard = this.getScoreboard();
        if (scoreboard == null) return null;
        if (scoreboard.getObjective("Assemble") == null) {
            Objective objective = scoreboard.registerNewObjective("Assemble", "dummy");
            objective.setDisplaySlot(DisplaySlot.SIDEBAR);
            AssembleAdapter adapter = getAssembleServiceImpl().getAdapter();
            String title = adapter != null && adapter.isReady(player) ? adapter.getTitle(player) : "";
            title = ChatColor.translateAlternateColorCodes('&', title == null ? "" : title);
            ClientVersionService versions = this.assembleServiceImpl.getClientVersionService();
            boolean legacy = versions != null && versions.isLegacy113(player);
            if (legacy) {
                if (versions.isLegacy17(player)) {
                    title = dev.revere.alley.library.assemble.utility.LegacyGlyphs.shared().apply(title);
                }
                title = dev.revere.alley.library.assemble.utility.AssembleUtility
                        .truncateLegacy(title, dev.revere.alley.library.assemble.utility.AssembleUtility.LEGACY_TITLE_LIMIT);
            }
            objective.setDisplayName(title);
            // Number formats do not exist on legacy clients. Configure modern
            // objectives once, at creation; API lookups can return new wrappers.
            if (!legacy) blankScoreNumbers(objective);
            return objective;
        }

        Objective existing = scoreboard.getObjective("Assemble");
        if (existing != null && existing.getDisplaySlot() != DisplaySlot.SIDEBAR) {
            existing.setDisplaySlot(DisplaySlot.SIDEBAR);
        }
        return existing;
    }

    /**
     * Hides the red score numbers next to each sidebar line.
     * 隐藏每行记分板旁的红色分数。
     *
     * <p>Paper 1.20.3+ API; missing on older servers, where the numbers simply
     * stay visible. Set once per objective instead of once per line per tick.</p>
     */
    static void blankScoreNumbers(Objective objective) {
        try {
            objective.numberFormat(io.papermc.paper.scoreboard.numbers.NumberFormat.blank());
        } catch (Throwable ignored) {
            // Older Paper without the number-format API.
        }
    }

    /**
     * Setup the board for a player.
     * 为玩家设置记分板。
     *
     * @param player who's board to setup.
     *        要设置记分板的玩家。
     */
    private void setup(Player player) {
        // Resolve client protocol before choosing legacy limits.
        ClientVersionService versions = this.assembleServiceImpl.getClientVersionService();
        if (versions != null) versions.getProtocol(player);

        Scoreboard scoreboard = getScoreboard();
        player.setScoreboard(scoreboard);
        this.getObjective();

        if (this.assembleServiceImpl.isCallEvents()) {
            AssembleBoardCreatedEvent createdEvent = new AssembleBoardCreatedEvent(this);
            Bukkit.getPluginManager().callEvent(createdEvent);
        }
    }

    /**
     * Get the board entry at a specific position.
     * 获取指定位置上的记分板条目。
     *
     * @param pos to find entry.
     *        要查找条目的位置。
     * @return entry if it isn't out of range.
     *         如果未超出范围则返回条目。
     */
    public AssembleBoardEntry getEntryAtPosition(int pos) {
        return pos >= this.entries.size() ? null : this.entries.get(pos);
    }

    /**
     * Get the unique identifier for position in scoreboard.
     * 获取记分板中位置的唯一标识符。
     *
     * @param position for identifier.
     *        标识符对应的位置。
     * @return unique identifier.
     *         唯一标识符。
     */
    public String getUniqueIdentifier(int position) {
        String identifier = this.getRandomChatColor(position) + ChatColor.WHITE;

        while (this.identifiers.contains(identifier)) {
            identifier = identifier + this.getRandomChatColor(position) + ChatColor.WHITE;
        }

        if (identifier.length() > 16) {
            return this.getUniqueIdentifier(position);
        }

        this.identifiers.add(identifier);

        return identifier;
    }

    /**
     * Gets a ChatColor based off the position in the collection.
     * 根据集合中的位置获取对应的 ChatColor。
     *
     * @param position of entry.
     *        条目的位置。
     * @return ChatColor adjacent to position.
     *         与位置对应的 ChatColor。
     */
    private String getRandomChatColor(int position) {
        return this.assembleServiceImpl.getChatColorCache()[position].toString();
    }
}
