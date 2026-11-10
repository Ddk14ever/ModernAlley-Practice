package dev.revere.alley.library.assemble.utility;

import java.util.HashMap;
import java.util.Map;

/**
 * Replaces glyphs a 1.7 client cannot render in its default font.
 * 替换 1.7 客户端默认字体无法显示的字符。
 *
 * <p>The configured sidebar uses small-caps letters (ᴘʀᴀᴄᴛɪᴄᴇ), box drawing
 * (│ ┃), and symbols (■ ✔ ✗). Those live in Unicode planes the 1.7 unicode
 * page cannot map, so they render as missing-glyph boxes. 1.8+ renders them
 * fine and is left untouched.</p>
 */
public final class LegacyGlyphs {
    /** Built-in fallbacks for the glyphs actually used by the default configs. */
    private static final Map<Character, Character> DEFAULTS = buildDefaults();

    /**
     * Shared table so the sidebar and the legacy tablist degrade identically
     * and the config overrides are loaded once at startup.
     */
    private static final LegacyGlyphs SHARED = new LegacyGlyphs();

    private final Map<Character, Character> replacements = new HashMap<>(DEFAULTS);

    public LegacyGlyphs() {
    }

    /** The process-wide instance configured from {@code providers/scoreboard.yml}. */
    public static LegacyGlyphs shared() {
        return SHARED;
    }

    /**
     * Applies configuration overrides on top of the built-in map.
     * 在内置映射之上应用配置覆盖。
     *
     * @param overrides replacement entries; keys must be single characters
     *                  覆盖项；键必须是单个字符
     */
    public void applyOverrides(Map<String, String> overrides) {
        if (overrides == null || overrides.isEmpty()) return;
        overrides.forEach((key, value) -> {
            if (key == null || key.isEmpty() || value == null || value.isEmpty()) return;
            // Key and value are single characters by contract; ignore anything
            // that would imply a multi-character re-mapping.
            this.replacements.put(key.charAt(0), value.charAt(0));
        });
    }

    /**
     * @return the text with every known unsupported glyph replaced.
     *         已知的不支持字符全部替换后的文本。
     */
    public String apply(String text) {
        if (text == null || text.isEmpty()) return text;

        StringBuilder builder = null;
        for (int i = 0; i < text.length(); i++) {
            char current = text.charAt(i);
            Character replacement = this.replacements.get(current);
            if (replacement == null) {
                if (builder != null) builder.append(current);
                continue;
            }
            if (builder == null) {
                builder = new StringBuilder(text.length());
                builder.append(text, 0, i);
            }
            builder.append(replacement);
        }
        return builder == null ? text : builder.toString();
    }

    private static Map<Character, Character> buildDefaults() {
        Map<Character, Character> map = new HashMap<>();

        // Small-caps Latin used throughout providers/scoreboard.yml.
        map.put('ᴀ', 'A'); // ᴀ
        map.put('ʙ', 'B'); // ʙ
        map.put('ᴄ', 'C'); // ᴄ
        map.put('ᴅ', 'D'); // ᴅ
        map.put('ᴇ', 'E'); // ᴇ
        map.put('ғ', 'F'); // ғ
        map.put('ɢ', 'G'); // ɢ
        map.put('ʜ', 'H'); // ʜ
        map.put('ɪ', 'I'); // ɪ
        map.put('ᴊ', 'J'); // ᴊ
        map.put('ᴋ', 'K'); // ᴋ
        map.put('ʟ', 'L'); // ʟ
        map.put('ᴍ', 'M'); // ᴍ
        map.put('ɴ', 'N'); // ɴ
        map.put('ᴏ', 'O'); // ᴏ
        map.put('ᴘ', 'P'); // ᴘ
        map.put('ʀ', 'R'); // ʀ
        map.put('ꜱ', 'S'); // ꜱ
        map.put('ᴛ', 'T'); // ᴛ
        map.put('ᴜ', 'U'); // ᴜ
        map.put('ᴠ', 'V'); // ᴠ
        map.put('ᴡ', 'W'); // ᴡ
        map.put('ˣ', 'x'); // ˣ
        map.put('ʏ', 'Y'); // ʏ
        map.put('ᴢ', 'Z'); // ᴢ

        // Layout glyphs.
        map.put('│', '|'); // │
        map.put('┃', '|'); // ┃
        map.put('■', '='); // ■
        map.put('✔', '+'); // ✔
        map.put('✓', '+'); // ✓
        map.put('✗', 'x'); // ✗
        map.put('✘', 'x'); // ✘

        return map;
    }
}