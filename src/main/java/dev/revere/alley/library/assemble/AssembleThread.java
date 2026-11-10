package dev.revere.alley.library.assemble;

import dev.revere.alley.AlleyPlugin;
import dev.revere.alley.common.logger.Logger;
import dev.revere.alley.common.version.ClientVersionService;
import dev.revere.alley.library.assemble.utility.AssembleUtility;
import dev.revere.alley.library.assemble.utility.LegacyGlyphs;
import org.bukkit.ChatColor;
import org.bukkit.entity.Player;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.scoreboard.Objective;
import org.bukkit.scoreboard.Scoreboard;

import java.util.Collections;
import java.util.List;

/** Synchronous scoreboard updater kept under the historical AssembleThread name. */
public class AssembleThread extends BukkitRunnable {
    private final AssembleServiceImpl assembleServiceImpl;
    private final ClientVersionService clientVersionService;
    private final LegacyGlyphs legacyGlyphs;
    /** Opt-in per-tick diagnostics for pre-1.13 clients (scoreboard.legacy-glyphs.debug). */
    private final boolean traceLegacy;
    private final java.util.Map<java.util.UUID, Long> nextTrace = new java.util.HashMap<>();

    /**
     * Assemble Thread.
     * Assemble 线程。
     *
     * @param assembleServiceImpl instance.
     *        AssembleServiceImpl 实例。
     * @param clientVersionService cached client protocol lookup (thread-safe reads).
     *                             客户端协议缓存（线程安全读取）。
     * @param legacyGlyphs         glyph fallback for 1.7 clients.
     *                             1.7 客户端的字形降级。
     */
    AssembleThread(AssembleServiceImpl assembleServiceImpl, ClientVersionService clientVersionService,
                   LegacyGlyphs legacyGlyphs, boolean traceLegacy) {
        this.assembleServiceImpl = assembleServiceImpl;
        this.clientVersionService = clientVersionService;
        this.legacyGlyphs = legacyGlyphs;
        this.traceLegacy = traceLegacy;
    }

    /** Dumps the objective that the client is expected to render. */
    private String describeObjective(Scoreboard scoreboard, Objective objective) {
        try {
            boolean sameBoard = objective.getScoreboard() == scoreboard;
            return "[obj=" + objective.getName()
                    + " slot=" + objective.getDisplaySlot()
                    + " disp='" + objective.getDisplayName() + "'"
                    + " live=" + sameBoard + "]";
        } catch (RuntimeException exception) {
            return "[obj=? " + exception.getClass().getSimpleName() + "]";
        }
    }

    /** Dumps a few real score entries so we can see what the client receives. */
    private String describeEntries(Scoreboard scoreboard, Objective objective) {
        try {
            java.util.Set<String> entries = scoreboard.getEntries();
            StringBuilder builder = new StringBuilder("[n=" + entries.size());
            int shown = 0;
            for (String entry : entries) {
                if (!objective.getScore(entry).isScoreSet()) continue;
                if (shown++ >= 3) break;
                builder.append(" {")
                        .append(objective.getScore(entry).getScore())
                        .append(':')
                        .append(entry.replace("§", "&"))
                        .append('}');
            }
            return builder.append(']').toString();
        } catch (RuntimeException exception) {
            return "[entries? " + exception.getClass().getSimpleName() + "]";
        }
    }

    /** State name for diagnostics, without depending on the adapter's internals. */
    private String profileState(Player player) {
        try {
            dev.revere.alley.core.profile.Profile profile = AlleyPlugin.getInstance()
                    .getService(dev.revere.alley.core.profile.ProfileService.class)
                    .getProfile(player.getUniqueId());
            return profile == null ? "null" : String.valueOf(profile.getState());
        } catch (RuntimeException exception) {
            return "err";
        }
    }

    private void trace(String message) {
        this.assembleServiceImpl.getPlugin().getLogger().info("[LegacySidebar] " + message);
    }

    @Override
    public void run() {
        if (!this.assembleServiceImpl.getPlugin().isEnabled()) {
            cancel();
            return;
        }
        try {
            tick();
        } catch (Exception exception) {
            // Keep the repeating task alive if one player's text is malformed.
            Logger.error("There was an error in the Assemble scoreboard task.");
            exception.printStackTrace();
        }
    }

    /** One logical row stays one physical row; the entry enforces text limits. */
    private List<String> prepareLines(List<String> logicalLines, boolean legacy17) {
        List<String> rows = new java.util.ArrayList<>(logicalLines.size());
        for (String logicalLine : logicalLines) {
            String line = logicalLine == null ? "" : logicalLine;
            if (legacy17) line = this.legacyGlyphs.apply(line);
            rows.add(line);
            if (rows.size() == 15) break;
        }
        return rows;
    }

    private void tick() {
        this.nextTrace.keySet().removeIf(id -> !this.assembleServiceImpl.getBoards().containsKey(id));
        for (Player player : this.assembleServiceImpl.getPlugin().getServer().getOnlinePlayers()) {
            try {
                AssembleBoard board = this.assembleServiceImpl.getBoards().get(player.getUniqueId());
                if (board == null) continue;
                if (board.getPlayer() != player) continue;
                AssembleAdapter adapter = this.assembleServiceImpl.getAdapter();
                if (adapter == null || !adapter.isReady(player)) continue;

                List<String> newLines = adapter.getLines(player);
                // Only null explicitly disables the sidebar. Unavailable content
                // and empty layouts must not destroy its update registration.
                if (newLines == null) {
                    this.assembleServiceImpl.removeBoard(player);
                    continue;
                }

                Scoreboard scoreboard = board.getScoreboard();
                Objective objective = board.getObjective();
                if (scoreboard == null || objective == null) continue;

                String rawTitle = adapter.getTitle(player);
                if (rawTitle == null) rawTitle = "";
                String title = ChatColor.translateAlternateColorCodes('&', rawTitle);

                // Pre-1.13 limits: 16 chars per team half and a 32-char
                // objective title. 1.7 also gets glyph fallbacks for the
                // small-caps/box characters its font cannot render.
                boolean legacy113 = this.clientVersionService.isLegacy113(player);
                boolean legacy17 = this.clientVersionService.isLegacy17(player);
                int prefixLimit = legacy113
                        ? AssembleUtility.LEGACY_TEAM_TEXT_LIMIT
                        : AssembleUtility.MODERN_TEAM_TEXT_LIMIT;
                if (legacy17) {
                    title = this.legacyGlyphs.apply(title);
                }
                if (legacy113) {
                    title = AssembleUtility.truncateLegacy(title, AssembleUtility.LEGACY_TITLE_LIMIT);
                }

                int rawCount = newLines.size();
                newLines = prepareLines(newLines, legacy17);
                if (this.assembleServiceImpl.getBoards().get(player.getUniqueId()) != board) continue;

                // Keep legacy titles static to reduce compatibility traffic.
                boolean titleChanged = !objective.getDisplayName().equals(title);
                if (titleChanged && (!legacy113 || objective.getDisplayName().isEmpty())) {
                    objective.setDisplayName(title);
                }

                List<AssembleBoardEntry> entries = board.getEntries();
                if (newLines.isEmpty()) {
                    // Keep the board alive; content will arrive once the profile
                    // finishes loading. Only clear the stale rows.
                    if (entries != null) {
                        while (!entries.isEmpty()) {
                            entries.get(entries.size() - 1).remove();
                        }
                    }
                } else {
                    if (newLines.size() > 15) {
                        newLines = newLines.subList(0, 15);
                    }

                    if (!this.assembleServiceImpl.getAssembleStyle().isDescending()) {
                        Collections.reverse(newLines);
                    }

                    if (entries != null && entries.size() > newLines.size()) {
                        for (int i = entries.size() - 1; i >= newLines.size(); i--) {
                            entries.get(i).remove();
                        }
                    }

                    int cache = this.assembleServiceImpl.getAssembleStyle().getStartNumber();
                    for (int i = 0; i < newLines.size(); i++) {
                        AssembleBoardEntry entry = board.getEntryAtPosition(i);

                        String rawLine = newLines.get(i);
                        if (rawLine == null) continue;
                        String line = ChatColor.translateAlternateColorCodes('&', rawLine);
                        if (entry == null) {
                            entry = new AssembleBoardEntry(board, line, i);
                        }

                        entry.setText(line);
                        entry.setup();
                        int score = this.assembleServiceImpl.getAssembleStyle().isDescending()
                                ? cache-- : cache++;
                        entry.send(score, prefixLimit);
                    }
                }

                if (this.assembleServiceImpl.getBoards().get(player.getUniqueId()) != board) continue;
                if (player.getScoreboard() != scoreboard && !assembleServiceImpl.isHook()) {
                    player.setScoreboard(scoreboard);
                }
                if (legacy17 && traceLegacy) {
                    long now = System.nanoTime();
                    if (now >= this.nextTrace.getOrDefault(player.getUniqueId(), 0L)) {
                        this.nextTrace.put(player.getUniqueId(), now + 10_000_000_000L);
                        trace("player=" + player.getName() + " protocol=" + this.clientVersionService.getProtocol(player)
                                + " session=" + Integer.toHexString(System.identityHashCode(player))
                                + " state=" + profileState(player) + " rawLines=" + rawCount
                                + " writtenRows=" + board.getEntries().size()
                                + " objective=" + describeObjective(scoreboard, objective)
                                + " entries=" + describeEntries(scoreboard, objective));
                    }
                }
            } catch (Exception exception) {
                // A single player's malformed placeholder or scoreboard state
                // must not prevent the remaining players from updating.
                Logger.error("There was an error updating " + player.getName() + "'s scoreboard.");
                exception.printStackTrace();
            }
        }
    }
}
