package dev.revere.alley.feature.cosmetic.internal.repository.impl.mvpmusic;

import dev.revere.alley.AlleyPlugin;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipOutputStream;

/**
 * Builds the shared Alley resource pack, containing MVP music, its
 * {@code sounds.json} mapping, and CustomModelData weapon models.
 * <p>
 * The pack is generated once at startup into the plugin data folder and
 * served to players via {@code server.properties resource-pack=} or
 * {@link org.bukkit.entity.Player#setResourcePack(String)}.
 * <p>
 * Sound event naming convention: {@code alley.mvp.<songKey>}.
 */
public final class ResourcePackBuilder {

    /** Maps sound event name → .ogg asset path inside the pack. */
    private static final Map<String, String> SONGS = new LinkedHashMap<>();

    static {
        // Sound event name             .ogg file inside resources/mvpmusic/
        SONGS.put("alley.mvp.onmyown",    "onmyown.ogg");
        SONGS.put("alley.mvp.ez4ence",    "ez4ence.ogg");
        SONGS.put("alley.mvp.haruhikage", "cry.ogg");
        SONGS.put("alley.mvp.dashstar",   "cjx.ogg");
        SONGS.put("alley.mvp.flashbang",  "flashbang.ogg");
        SONGS.put("alley.mvp.inhuman",    "inhuman.ogg");
        SONGS.put("alley.mvp.boundbylove","qlwh.ogg");
        SONGS.put("alley.mvp.girlbandcry","xxrrwmdcs.ogg");
    }

    public static final String PACK_FILE_NAME = "alley_mvp_music.zip";
    private static final int PACK_FORMAT = 75; // Minecraft 1.21.11
    private static final int PACK_REVISION = 11;
    private static final String REVISION_ENTRY = "alley-pack-revision.txt";

    private ResourcePackBuilder() {}

    /**
     * Ensures the MVP music resource pack exists in the plugin data folder.
     * If the pack is already present and up-to-date this is a no-op.
     *
     * @param plugin the Alley plugin instance
     */
    public static void ensurePackExists(AlleyPlugin plugin) {
        Path packFile = plugin.getDataFolder().toPath().resolve(PACK_FILE_NAME);

        // Check whether at least one .ogg source is available
        boolean anySource = false;
        for (String oggFile : SONGS.values()) {
            if (plugin.getResource("mvpmusic/" + oggFile) != null) {
                anySource = true;
                break;
            }
        }
        if (!anySource) {
            plugin.getLogger().warning("[Alley] No .ogg MVP music files found in resources/mvpmusic/ — "
                    + "resource pack will not be generated.");
            return;
        }

        // Rebuild old packs when protocol features add assets. This is needed
        // for existing installations where the original music-only zip is
        // already present in the plugin data folder.
        if (Files.exists(packFile) && isCurrentRevision(packFile)) {
            plugin.getLogger().info("[Alley] MVP music resource pack already exists: " + packFile);
            return;
        }

        try {
            generatePack(plugin, packFile);
            plugin.getLogger().info("[Alley] MVP music resource pack generated: " + packFile
                    + " (" + packFile.toFile().length() / 1024 + " KB)");
        } catch (Exception e) {
            plugin.getLogger().severe("[Alley] Failed to generate MVP music resource pack: " + e.getMessage());
            e.printStackTrace();
        }
    }

    /** Returns the SHA-1 hash of the generated pack file, or empty string if it doesn't exist. */
    public static String getPackSha1(AlleyPlugin plugin) {
        Path packFile = plugin.getDataFolder().toPath().resolve(PACK_FILE_NAME);
        if (!Files.exists(packFile)) return "";
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-1");
            try (InputStream in = Files.newInputStream(packFile)) {
                byte[] buf = new byte[8192];
                int n;
                while ((n = in.read(buf)) != -1) md.update(buf, 0, n);
            }
            byte[] digest = md.digest();
            StringBuilder sb = new StringBuilder();
            for (byte b : digest) sb.append(String.format("%02x", b));
            return sb.toString();
        } catch (Exception e) {
            return "";
        }
    }

    private static void generatePack(AlleyPlugin plugin, Path packFile) throws Exception {
        Path tmpFile = Files.createTempFile("alley_mvp_", ".zip");
        try {
            try (ZipOutputStream zos = new ZipOutputStream(
                    new BufferedOutputStream(Files.newOutputStream(tmpFile)))) {

                // --- pack.mcmeta ---
                zos.putNextEntry(new ZipEntry("pack.mcmeta"));
                writePackMcmeta(zos);
                zos.closeEntry();

                zos.putNextEntry(new ZipEntry(REVISION_ENTRY));
                zos.write(Integer.toString(PACK_REVISION).getBytes(StandardCharsets.UTF_8));
                zos.closeEntry();

                // --- sounds.json ---
                zos.putNextEntry(new ZipEntry("assets/minecraft/sounds.json"));
                writeSoundsJson(zos);
                zos.closeEntry();

                // Weapon models are selected by CustomModelData, so ordinary
                // bows and spyglasses keep their vanilla models.
                writeWeaponModelOverrides(plugin, zos);

                // The AWP scope wears a pumpkin; this override replaces the
                // pumpkin's screen overlay with the vanilla telescope scope
                // texture, so the scope looks like a real telescope lens.
                writeScopeOverlay(plugin, zos);

                // Concrete powder uses Minecraft's cube_all block model. Each
                // replacement texture is therefore rendered on all six faces,
                // while the server still uses normal Bukkit block materials.
                writeLuckyBlockTextures(plugin, zos);

                // END_GATEWAY has an empty collision shape and a full-cube
                // outline, making it ideal for the AutoClick input probe. Its
                // portal shader always writes alpha=1, so a transparent texture
                // alone cannot hide it. The replacement is the vanilla 1.21.11
                // shader with one guarded branch that discards only the
                // PORTAL_LAYERS=16 End Gateway pipeline. The probe's initial
                // beam is suppressed per player with block-entity data instead
                // of replacing the global End Gateway beam texture.
                writeTextEntry(zos, "assets/minecraft/shaders/core/rendertype_end_portal.fsh",
                        endPortalShader());

                // --- .ogg files ---
                for (Map.Entry<String, String> entry : SONGS.entrySet()) {
                    String resourcePath = "mvpmusic/" + entry.getValue();
                    try (InputStream in = plugin.getResource(resourcePath)) {
                        if (in == null) {
                            plugin.getLogger().warning("[Alley] Missing .ogg resource: " + resourcePath);
                            continue;
                        }
                        // Path inside the pack: assets/minecraft/sounds/alley/mvpmusic/xxx.ogg
                        String packPath = "assets/minecraft/sounds/alley/mvpmusic/" + entry.getValue();
                        zos.putNextEntry(new ZipEntry(packPath));
                        in.transferTo(zos);
                        zos.closeEntry();
                    }
                }
            }
            Files.move(tmpFile, packFile, StandardCopyOption.REPLACE_EXISTING);
        } finally {
            try { Files.deleteIfExists(tmpFile); } catch (IOException ignored) {}
        }
    }

    private static void writePackMcmeta(ZipOutputStream zos) throws IOException {
        String json = "{\"pack\":{\"description\":\"Alley resources\",\"min_format\":"
                + PACK_FORMAT + ",\"max_format\":" + PACK_FORMAT + "}}";
        zos.write(json.getBytes(StandardCharsets.UTF_8));
    }

    private static void writeSoundsJson(ZipOutputStream zos) throws IOException {
        StringBuilder sb = new StringBuilder("{");
        boolean first = true;
        for (String eventName : SONGS.keySet()) {
            if (!first) sb.append(",");
            first = false;
            String oggFile = SONGS.get(eventName);
            String soundPath = "alley/mvpmusic/" + (oggFile != null ? oggFile.replace(".ogg", "") : eventName);
            sb.append("\"").append(eventName).append("\":{\"sounds\":[")
              .append("{\"name\":\"").append(soundPath).append("\",\"stream\":true}")
              .append("]}");
        }
        sb.append("}");
        zos.write(sb.toString().getBytes(StandardCharsets.UTF_8));
    }

    private static void writeWeaponModelOverrides(AlleyPlugin plugin, ZipOutputStream zos) throws IOException {
        // These model/texture files come from the supplied AK-47 and AWP
        // packs. They are copied under the Alley namespace, so vanilla bows,
        // spyglasses and every other kit remain untouched.
        writeResourceEntry(plugin, zos, "assets/alley/models/item/ak47.json", "weapons/models/ak47.json");
        writeResourceEntry(plugin, zos, "assets/alley/textures/item/ak47.png", "weapons/textures/ak47.png");
        writeResourceEntry(plugin, zos, "assets/alley/models/item/awp.json", "weapons/models/awp.json");
        writeResourceEntry(plugin, zos, "assets/alley/textures/item/awp.png", "weapons/textures/awp.png");
        writeTextEntry(zos, "assets/alley/items/ak47.json", """
                {"model":{"type":"minecraft:model","model":"alley:item/ak47"}}
                """);
        writeTextEntry(zos, "assets/alley/items/awp.json", """
                {"model":{"type":"minecraft:model","model":"alley:item/awp"}}
                """);
    }

    private static void writeScopeOverlay(AlleyPlugin plugin, ZipOutputStream zos) throws IOException {
        // The vanilla telescope scope texture, served as the pumpkin overlay.
        // Wearing a pumpkin (used by the AWP scope) then shows the telescope
        // lens instead of the carved-pumpkin face.
        writeResourceEntry(plugin, zos, "assets/minecraft/textures/misc/pumpkinblur.png",
                "misc/pumpkinblur.png");
        writeResourceEntry(plugin, zos, "assets/minecraft/textures/misc/pumpkinblur.png.mcmeta",
                "misc/pumpkinblur.png.mcmeta");
    }

    private static void writeLuckyBlockTextures(AlleyPlugin plugin, ZipOutputStream zos) throws IOException {
        writeResourceEntry(plugin, zos, "assets/minecraft/textures/block/green_concrete_powder.png",
                "luckyblock/green_concrete_powder.png");
        writeResourceEntry(plugin, zos, "assets/minecraft/textures/block/blue_concrete_powder.png",
                "luckyblock/blue_concrete_powder.png");
        writeResourceEntry(plugin, zos, "assets/minecraft/textures/block/yellow_concrete_powder.png",
                "luckyblock/yellow_concrete_powder.png");
    }

    private static void writeResourceEntry(AlleyPlugin plugin, ZipOutputStream zos,
                                           String packPath, String resourcePath) throws IOException {
        try (InputStream input = plugin.getResource(resourcePath)) {
            if (input == null) {
                throw new FileNotFoundException("Missing bundled resource: " + resourcePath);
            }
            zos.putNextEntry(new ZipEntry(packPath));
            input.transferTo(zos);
            zos.closeEntry();
        }
    }

    private static boolean isCurrentRevision(Path packFile) {
        try (ZipFile zip = new ZipFile(packFile.toFile())) {
            ZipEntry entry = zip.getEntry(REVISION_ENTRY);
            if (entry == null) return false;
            try (InputStream in = zip.getInputStream(entry)) {
                String value = new String(in.readAllBytes(), StandardCharsets.UTF_8).trim();
                return Integer.toString(PACK_REVISION).equals(value);
            }
        } catch (IOException exception) {
            return false;
        }
    }

    private static void writeTextEntry(ZipOutputStream zos, String path, String content) throws IOException {
        zos.putNextEntry(new ZipEntry(path));
        zos.write(content.getBytes(StandardCharsets.UTF_8));
        zos.closeEntry();
    }

    /**
     * Keep real End Portals visible while making only the End Gateway pipeline
     * discard its fragments. Minecraft 1.21.11 defines PORTAL_LAYERS as 15 for
     * End Portals and 16 for End Gateways when compiling this shared shader.
     */
    private static String endPortalShader() {
        return """
                #version 330

                #moj_import <minecraft:fog.glsl>
                #moj_import <minecraft:matrix.glsl>
                #moj_import <minecraft:globals.glsl>

                uniform sampler2D Sampler0;
                uniform sampler2D Sampler1;

                in vec4 texProj0;
                in float sphericalVertexDistance;
                in float cylindricalVertexDistance;

                const vec3[] COLORS = vec3[](
                    vec3(0.022087, 0.098399, 0.110818),
                    vec3(0.011892, 0.095924, 0.089485),
                    vec3(0.027636, 0.101689, 0.100326),
                    vec3(0.046564, 0.109883, 0.114838),
                    vec3(0.064901, 0.117696, 0.097189),
                    vec3(0.063761, 0.086895, 0.123646),
                    vec3(0.084817, 0.111994, 0.166380),
                    vec3(0.097489, 0.154120, 0.091064),
                    vec3(0.106152, 0.131144, 0.195191),
                    vec3(0.097721, 0.110188, 0.187229),
                    vec3(0.133516, 0.138278, 0.148582),
                    vec3(0.070006, 0.243332, 0.235792),
                    vec3(0.196766, 0.142899, 0.214696),
                    vec3(0.047281, 0.315338, 0.321970),
                    vec3(0.204675, 0.390010, 0.302066),
                    vec3(0.080955, 0.314821, 0.661491)
                );
                const mat4 SCALE_TRANSLATE = mat4(
                    0.5, 0.0, 0.0, 0.25,
                    0.0, 0.5, 0.0, 0.25,
                    0.0, 0.0, 1.0, 0.0,
                    0.0, 0.0, 0.0, 1.0
                );

                mat4 end_portal_layer(float layer) {
                    mat4 translate = mat4(
                        1.0, 0.0, 0.0, 17.0 / layer,
                        0.0, 1.0, 0.0, (2.0 + layer / 1.5) * (GameTime * 1.5),
                        0.0, 0.0, 1.0, 0.0,
                        0.0, 0.0, 0.0, 1.0
                    );
                    mat2 rotate = mat2_rotate_z(radians((layer * layer * 4321.0 + layer * 9.0) * 2.0));
                    mat2 scale = mat2((4.5 - layer / 4.0) * 2.0);
                    return mat4(scale * rotate) * translate * SCALE_TRANSLATE;
                }

                out vec4 fragColor;

                void main() {
                #if PORTAL_LAYERS == 16
                        discard;
                #else
                    vec3 color = textureProj(Sampler0, texProj0).rgb * COLORS[0];
                    for (int i = 0; i < PORTAL_LAYERS; i++) {
                        color += textureProj(Sampler1, texProj0 * end_portal_layer(float(i + 1))).rgb * COLORS[i];
                    }
                    fragColor = apply_fog(vec4(color, 1.0), sphericalVertexDistance, cylindricalVertexDistance,
                        FogEnvironmentalStart, FogEnvironmentalEnd, FogRenderDistanceStart, FogRenderDistanceEnd, FogColor);
                #endif
                }
                """;
    }
}
