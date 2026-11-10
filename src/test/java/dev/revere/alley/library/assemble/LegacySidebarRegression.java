package dev.revere.alley.library.assemble;

import dev.revere.alley.library.assemble.utility.AssembleUtility;
import org.bukkit.ChatColor;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/** Standalone regression checks: run with scripts/test-legacy-sidebar.ps1. */
public final class LegacySidebarRegression {
    private record Glyph(char text, char color, int formats) {}

    public static void main(String[] args) {
        List<String> cases = new ArrayList<>(List.of("", " ", "1234567890123456Z",
                "&c12345678901234&lBOLD", "&l12345678901234&nUNDERLINE",
                "&r12345678901234plain", "&c12345678901234&rRESET",
                "&c&l1234567890&nUNDERLINE", "中文计分板 &a文字继续显示",
                "123456789012345\uD83D\uDE00abcdef", "a".repeat(180)));
        Random random = new Random(17010);
        String[] tokens = {"&a", "&c", "&f", "&r", "&k", "&l", "&m", "&n", "&o", " ", "中", "x"};
        for (int i = 0; i < 2000; i++) {
            StringBuilder line = new StringBuilder();
            for (int j = 0; j < 60; j++) line.append(tokens[random.nextInt(tokens.length)]);
            cases.add(line.toString());
        }
        for (String line : cases) {
            for (int limit : new int[]{16, 64}) verifyLine(line, limit);
        }
        check(AssembleUtility.truncateLegacy("a".repeat(31) + "§cX", 32).equals("a".repeat(31)),
                "Title must not end inside a color code");
        check(AssembleUtility.truncateLegacy("a".repeat(31) + "\uD83D\uDE00", 32).equals("a".repeat(31)),
                "Title must not split a surrogate pair");
        check(AssembleUtility.truncateLegacy("a".repeat(40), 32).length() == 32, "Title limit");
        String[] blank = AssembleUtility.splitTeamText(null, 16);
        check(blank[0].isEmpty() && blank[1].isEmpty(), "Null line");
        System.out.println("PASS: " + cases.size() * 2 + " line/style checks and title boundaries");
    }

    private static void verifyLine(String line, int limit) {
        String[] parts = AssembleUtility.splitTeamText(line, limit);
        for (String part : parts) {
            check(part.length() <= limit, "Team half exceeds " + limit);
            check(!part.endsWith("§"), "Dangling color code");
            check(part.isEmpty() || !Character.isHighSurrogate(part.charAt(part.length() - 1)),
                    "Split surrogate pair");
        }
        // A real row contains an invisible unique entry between the two halves.
        List<Glyph> actual = render(parts[0] + "§0§f" + parts[1]);
        List<Glyph> expected = render(ChatColor.translateAlternateColorCodes('&', line));
        check(actual.size() <= expected.size() && actual.equals(expected.subList(0, actual.size())),
                "Text/style changed at split: " + line + " -> " + List.of(parts));
        if (ChatColor.translateAlternateColorCodes('&', line).length() <= limit) {
            check(actual.equals(expected), "Short line lost text");
        }
        LegacyComponentSerializer serializer = LegacyComponentSerializer.legacySection();
        String prefix = serializer.serialize(serializer.deserialize(parts[0]));
        String suffix = serializer.serialize(serializer.deserialize(parts[1]));
        check(prefix.length() <= limit && suffix.length() <= limit, "Component round trip exceeds budget");
        check(render(prefix + "§0§f" + suffix).equals(actual), "Component round trip lost formatting");
    }

    private static List<Glyph> render(String text) {
        List<Glyph> glyphs = new ArrayList<>();
        char color = 'f';
        int formats = 0;
        for (int i = 0; i < text.length(); i++) {
            char current = text.charAt(i);
            if (current == '§' && i + 1 < text.length()) {
                char code = Character.toLowerCase(text.charAt(++i));
                if ("0123456789abcdefr".indexOf(code) >= 0) {
                    color = code == 'r' ? 'f' : code;
                    formats = 0;
                } else if (code >= 'k' && code <= 'o') {
                    formats |= 1 << (code - 'k');
                }
            } else {
                glyphs.add(new Glyph(current, color, formats));
            }
        }
        return glyphs;
    }

    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
