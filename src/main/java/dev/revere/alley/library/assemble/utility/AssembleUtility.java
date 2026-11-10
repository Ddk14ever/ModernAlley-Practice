package dev.revere.alley.library.assemble.utility;

import lombok.experimental.UtilityClass;
import org.bukkit.ChatColor;

/**
 * @author Remi
 * @project alley-practice
 * @date 12/08/2025
 */
@UtilityClass
public class AssembleUtility {
    /** Team prefix/suffix limit before Minecraft 1.13 (protocol 393). */
    public static final int LEGACY_TEAM_TEXT_LIMIT = 16;
    /** Team prefix/suffix limit from 1.13 onwards. */
    public static final int MODERN_TEAM_TEXT_LIMIT = 64;
    /** Sidebar objective title limit before Minecraft 1.13. */
    public static final int LEGACY_TITLE_LIMIT = 32;

    public String[] splitTeamText(String raw) {
        return splitTeamText(raw, MODERN_TEAM_TEXT_LIMIT);
    }

    /**
     * Splits one sidebar line into the team prefix and suffix halves.
     *
     * <p>Before 1.13 both halves are limited to 16 characters <em>including</em>
     * the § colour codes, so a long line has to continue in the suffix. The
     * suffix re-applies the colours active at the split point, otherwise the
     * continuation would render without its parent style.</p>
     *
     * @param raw   the line, may still contain {@code &} colour codes
     * @param limit maximum raw length of each half
     * @return {@code [prefix, suffix]}
     */
    public String[] splitTeamText(String raw, int limit) {
        if (raw == null) return new String[]{"", ""};

        int teamTextLimit = Math.max(1, limit);
        String input = ChatColor.translateAlternateColorCodes('&', raw);

        String prefix = safeTail(takeRaw(input, teamTextLimit));
        String remainder = input.substring(prefix.length());
        if (remainder.isEmpty()) return new String[]{prefix, ""};
        String lastColors = ChatColor.getLastColors(prefix);
        // The invisible entry ends in white, clearing all prefix formatting.
        // Restore a base colour even when the prefix only used formatting codes.
        if (lastColors.length() < 2 || !(isColor(lastColors.charAt(1)) || lastColors.charAt(1) == 'r')) {
            lastColors = ChatColor.WHITE + lastColors;
        }
        boolean remainderResetsStyle =
                remainder.length() >= 2
                        && remainder.charAt(0) == ChatColor.COLOR_CHAR
                        && (isColor(Character.toLowerCase(remainder.charAt(1)))
                        || Character.toLowerCase(remainder.charAt(1)) == 'r');

        String suffixBase = remainderResetsStyle ? remainder : (lastColors + remainder);
        String suffix = safeTail(takeRaw(suffixBase, teamTextLimit));

        return new String[]{prefix, suffix};
    }

    /**
     * Splits one logical line into several physical rows, each at most
     * {@code limit} raw characters.
     *
     * <p>1.7 team packets have 16-character prefix and suffix halves, so a
     * logical line longer than 32 raw characters cannot be carried by one
     * team. Each chunk becomes its own scoreboard row, with the colours active
     * at the split point re-applied so the continuation keeps its style.</p>
     *
     * @param raw   the logical line, may still contain {@code &} colour codes
     * @param limit maximum raw length of one physical row
     * @return the physical rows, in visual order
     */
    public java.util.List<String> wrapLegacy(String raw, int limit) {
        java.util.List<String> chunks = new java.util.ArrayList<>();
        if (raw == null || raw.isEmpty()) {
            chunks.add("");
            return chunks;
        }

        int rowLimit = Math.max(1, limit);
        String remaining = ChatColor.translateAlternateColorCodes('&', raw);
        // Bound the loop in case a line is pathological.
        int guard = 0;

        while (!remaining.isEmpty() && guard++ < 64) {
            LeadStyle style = extractLeadStyle(remaining);
            int budget = Math.max(1, rowLimit - style.minimal.length());
            String chunkText = safeTail(takeRaw(style.remainder, budget));
            String chunk = safeTail(style.minimal + chunkText);
            chunks.add(chunk);

            String rest = style.remainder.substring(chunkText.length());
            if (rest.isEmpty()) break;

            boolean restStartsWithCode = rest.length() >= 2
                    && rest.charAt(0) == ChatColor.COLOR_CHAR
                    && isCodeChar(Character.toLowerCase(rest.charAt(1)));
            remaining = restStartsWithCode ? rest : (ChatColor.getLastColors(chunk) + rest);
        }
        return chunks;
    }

    /**
     * True when a line carries no information: only colour codes, whitespace
     * and repeated decoration characters such as {@code -}, {@code =} or
     * {@code _}.
     *
     * <p>1.7 has 15 sidebar rows and 16 characters each, so wrapping long lines
     * needs the space that decorative separators occupy.</p>
     *
     * @param raw the line, may still contain {@code &} colour codes
     * @return true when nothing but decoration remains
     */
    public boolean isDecorativeLine(String raw) {
        if (raw == null || raw.isEmpty()) return true;

        String input = ChatColor.translateAlternateColorCodes('&', raw);
        boolean sawVisible = false;
        for (int i = 0; i < input.length(); i++) {
            char current = input.charAt(i);
            if (current == ChatColor.COLOR_CHAR) {
                i++; // skip the code character
                continue;
            }
            if (Character.isWhitespace(current)) continue;
            if (current == '-' || current == '=' || current == '_' || current == '~') {
                sawVisible = true;
                continue;
            }
            return false;
        }
        // All-decoration (or empty) lines are dropped; a bare space stays so
        // deliberate blank spacers keep their row.
        return sawVisible || input.isBlank();
    }

    /**
     * Truncates text to at most {@code maxRaw} characters, counting § colour
     * codes, without leaving a dangling {@code §} or splitting a code pair.
     *
     * <p>Used for the sidebar title, which older clients limit to 32
     * characters while the configured title can be longer.</p>
     *
     * @param text   the raw text
     * @param maxRaw maximum length of the result
     * @return the truncated text
     */
    public String truncateLegacy(String text, int maxRaw) {
        if (text == null || text.isEmpty() || maxRaw <= 0) return "";
        return safeTail(takeRaw(text, maxRaw));
    }

    private static LeadStyle extractLeadStyle(String s) {
        int i = 0;
        char color = 0;
        boolean obf = false, bold = false, strike = false, underline = false, italic = false;

        while (i + 1 < s.length() && s.charAt(i) == ChatColor.COLOR_CHAR) {
            char code = Character.toLowerCase(s.charAt(i + 1));
            if (code == 'r') {
                color = 0;
                obf = bold = strike = underline = italic = false;
            } else if (isColor(code)) {
                color = code;
                obf = bold = strike = underline = italic = false;
            } else {
                switch (code) {
                    case 'k':
                        obf = true;
                        break;
                    case 'l':
                        bold = true;
                        break;
                    case 'm':
                        strike = true;
                        break;
                    case 'n':
                        underline = true;
                        break;
                    case 'o':
                        italic = true;
                        break;
                }
            }
            i += 2;
        }

        StringBuilder minimal = new StringBuilder(12);
        if (color != 0) minimal.append(ChatColor.COLOR_CHAR).append(color);
        if (obf) minimal.append(ChatColor.COLOR_CHAR).append('k');
        if (bold) minimal.append(ChatColor.COLOR_CHAR).append('l');
        if (strike) minimal.append(ChatColor.COLOR_CHAR).append('m');
        if (underline) minimal.append(ChatColor.COLOR_CHAR).append('n');
        if (italic) minimal.append(ChatColor.COLOR_CHAR).append('o');

        return new LeadStyle(minimal.toString(), s.substring(i));
    }

    private static boolean isColor(char code) {
        return (code >= '0' && code <= '9') || (code >= 'a' && code <= 'f');
    }

    private static boolean isCodeChar(char c) {
        return (c >= '0' && c <= '9')
                || (c >= 'a' && c <= 'f')
                || (c >= 'k' && c <= 'o')
                || c == 'r';
    }

    /**
     * Takes up to {@code rawCount} raw characters without cutting a §x pair in
     * half; a pair that would straddle the boundary is left for the next chunk.
     */
    private static String takeRaw(String s, int rawCount) {
        if (rawCount <= 0 || s.isEmpty()) return "";
        if (s.length() <= rawCount) return s;

        int end = rawCount;
        // Never leave a lone § at the end, and never split "§" from its code
        // character: step back until the cut is not inside a code pair.
        while (end > 0 && s.charAt(end - 1) == ChatColor.COLOR_CHAR) {
            end--;
        }
        if (end > 0 && Character.isHighSurrogate(s.charAt(end - 1))) end--;
        return s.substring(0, end);
    }

    private static String safeTail(String s) {
        if (s.isEmpty()) return s;
        if (s.charAt(s.length() - 1) == ChatColor.COLOR_CHAR) return s.substring(0, s.length() - 1);
        return s;
    }

    private static class LeadStyle {
        final String minimal, remainder;

        LeadStyle(String minimal, String remainder) {
            this.minimal = minimal;
            this.remainder = remainder;
        }
    }
}
