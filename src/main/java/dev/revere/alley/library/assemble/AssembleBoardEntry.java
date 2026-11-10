package dev.revere.alley.library.assemble;

import dev.revere.alley.library.assemble.utility.AssembleUtility;
import lombok.Setter;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import org.bukkit.scoreboard.Scoreboard;
import org.bukkit.scoreboard.Team;

@Setter
public class AssembleBoardEntry {
    private static final LegacyComponentSerializer LEGACY_SERIALIZER = LegacyComponentSerializer.legacySection();
    private final AssembleBoard board;
    private final String identifier;
    private String text;
    private Team team;
    private Scoreboard scoreboard;

    /**
     * Assemble Board Entry
     * Assemble 记分板条目。
     *
     * @param board    that entry belongs to.
     *        条目所属的记分板。
     * @param text     of entry.
     *        条目的文本。
     * @param position of entry.
     *        条目的位置。
     */
    public AssembleBoardEntry(AssembleBoard board, String text, int position) {
        this.board = board;
        this.identifier = this.board.getUniqueIdentifier(position);
        this.text = text;

        this.setup();
    }

    /**
     * Setup Board Entry.
     * 设置记分板条目。
     */
    public void setup() {
        final Scoreboard scoreboard = this.board.getScoreboard();
        if (scoreboard == null) return;

        String teamName = this.identifier;
        if (teamName.length() > 16) {
            teamName = teamName.substring(0, 16);
        }

        Team team = scoreboard.getTeam(teamName);
        if (team == null) {
            team = scoreboard.registerNewTeam(teamName);
        }

        if (!team.getEntries().contains(this.identifier)) {
            team.addEntry(this.identifier);
        }

        if (!this.board.getEntries().contains(this)) {
            this.board.getEntries().add(this);
        }

        this.team = team;
        this.scoreboard = scoreboard;
    }

    /**
     * Send the board entry update to the player.
     * 将记分板条目更新发送给玩家。
     *
     * @param position of entry.
     *        条目的位置。
     */
    public void send(int position) {
        send(position, AssembleUtility.MODERN_TEAM_TEXT_LIMIT);
    }

    /**
     * Send the board entry update, splitting the line into the team prefix and
     * suffix so it survives the legacy 16-character half limit.
     *
     * <p>Before 1.13 each of prefix and suffix holds at most 16 characters
     * including colour codes; a longer sidebar line has to continue in the
     * suffix or the client truncates it.</p>
     *
     * @param position    of entry.
     *                    条目的位置。
     * @param prefixLimit maximum raw length of each half for this client.
     *                    该客户端每个半段的最大原始长度。
     */
    public void send(int position, int prefixLimit) {
        String[] parts = AssembleUtility.splitTeamText(this.text, prefixLimit);
        net.kyori.adventure.text.Component prefix = LEGACY_SERIALIZER.deserialize(parts[0]);
        net.kyori.adventure.text.Component suffix = LEGACY_SERIALIZER.deserialize(parts[1]);
        if (!this.team.prefix().equals(prefix)) this.team.prefix(prefix);
        if (!this.team.suffix().equals(suffix)) this.team.suffix(suffix);

        org.bukkit.scoreboard.Score score = this.board.getObjective().getScore(this.identifier);
        if (!score.isScoreSet() || score.getScore() != position) score.setScore(position);
    }

    public void remove() {
        this.board.getIdentifiers().remove(this.identifier);
        this.board.getEntries().remove(this);
        if (this.scoreboard == null) return;
        this.scoreboard.resetScores(this.identifier);
        if (this.team != null && this.team.getScoreboard() != null) this.team.unregister();
    }
}
