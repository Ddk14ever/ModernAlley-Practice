package dev.revere.alley.visual.tablist.legacy;

import com.comphenix.protocol.PacketType;
import com.comphenix.protocol.ProtocolLibrary;
import com.comphenix.protocol.events.ListenerPriority;
import com.comphenix.protocol.events.PacketAdapter;
import com.comphenix.protocol.events.PacketContainer;
import com.comphenix.protocol.events.PacketEvent;
import com.comphenix.protocol.reflect.StructureModifier;
import com.comphenix.protocol.wrappers.BukkitConverters;
import com.comphenix.protocol.wrappers.EnumWrappers;
import com.comphenix.protocol.wrappers.PlayerInfoData;
import com.comphenix.protocol.wrappers.WrappedChatComponent;
import dev.revere.alley.AlleyPlugin;
import dev.revere.alley.bootstrap.AlleyContext;
import dev.revere.alley.bootstrap.lifecycle.Service;
import dev.revere.alley.common.version.ClientVersionService;
import dev.revere.alley.core.locale.LocaleService;
import dev.revere.alley.core.locale.internal.impl.VisualsLocaleImpl;
import dev.revere.alley.core.profile.Profile;
import dev.revere.alley.core.profile.ProfileService;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.entity.Player;
import org.bukkit.scheduler.BukkitTask;
import org.bukkit.scoreboard.Team;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Level;

/**
 * Vanilla membership, with per-viewer name colors for 1.7. No fake profiles,
 * ADD_PLAYER, custom ordering, header/footer or scoreboard/team mutations.
 */
@dev.revere.alley.bootstrap.annotation.Service(provides = LegacyTablist.class, priority = 400)
public final class LegacyTablist implements Service {
    private final AlleyPlugin plugin;
    private final ClientVersionService versions;
    private final ProfileService profiles;
    private final LocaleService locale;
    private final Map<UUID, LegacyTablistState> viewers = new ConcurrentHashMap<>();
    private PacketAdapter listener;
    private PacketAdapter loginListener;
    private BukkitTask task;
    private long nextWarning;

    public LegacyTablist(AlleyPlugin plugin, ClientVersionService versions, ProfileService profiles, LocaleService locale) {
        this.plugin = plugin;
        this.versions = versions;
        this.profiles = profiles;
        this.locale = locale;
    }

    @Override
    public void initialize(AlleyContext context) {
        int displaySlots = LegacyTablistLayout.normalizeSlots(this.locale.getInt(VisualsLocaleImpl.TAB_LIST_LEGACY_DISPLAY_SLOTS));
        this.loginListener = new PacketAdapter(this.plugin, ListenerPriority.HIGHEST, PacketType.Play.Server.LOGIN) {
            @Override
            public void onPacketSending(PacketEvent event) {
                if (event.isCancelled() || event.getPlayer() == null || !versions.isLegacy17(event.getPlayer())) return;
                try {
                    // The login maxPlayers field controls 1.7's fixed Tab grid.
                    // Changing server.properties or Player Info names cannot fix
                    // a grid already established for this connection.
                    Object rewritten = LegacyTablistLayout.withDisplaySlots(event.getPacket().getHandle(), displaySlots);
                    event.setPacket(new PacketContainer(PacketType.Play.Server.LOGIN, rewritten));
                    plugin.getLogger().info("[LegacyTablist] Applied login display limit=" + displaySlots
                            + " for " + event.getPlayer().getName());
                } catch (RuntimeException exception) {
                    warn(exception);
                }
            }
        };
        ProtocolLibrary.getProtocolManager().addPacketListener(this.loginListener);
        // NORMAL is intentional: our filters=false updates bypass this listener
        // (MONITOR listeners can still see unfiltered ProtocolLib sends).
        this.listener = new PacketAdapter(this.plugin, ListenerPriority.NORMAL, PacketType.Play.Server.PLAYER_INFO) {
            @Override
            public void onPacketSending(PacketEvent event) {
                if (event.isCancelled() || event.getPlayer() == null) return;
                LegacyTablistState state = viewers.get(event.getPlayer().getUniqueId());
                if (state == null || state.viewer != event.getPlayer()) return;
                try {
                    Set<EnumWrappers.PlayerInfoAction> actions = event.getPacket().getPlayerInfoActions().read(0);
                    if (!actions.contains(EnumWrappers.PlayerInfoAction.ADD_PLAYER)
                            && !actions.contains(EnumWrappers.PlayerInfoAction.UPDATE_DISPLAY_NAME)) return;
                    // Native hide/show, reinitialization or another name writer
                    // can reset ViaRewind's stored name even if our color is unchanged.
                    for (PlayerInfoData row : entries(event.getPacket()).read(0)) {
                        state.invalidate(row.getProfileId());
                    }
                } catch (RuntimeException exception) {
                    warn(exception);
                }
            }
        };
        ProtocolLibrary.getProtocolManager().addPacketListener(this.listener);
        // Independent of modern header/footer and the player's custom-Tab toggle.
        this.task = Bukkit.getScheduler().runTaskTimer(this.plugin, this::update, 1L, 10L);
        this.plugin.getLogger().info("[LegacyTablist] renderer=vanilla-names-v2 displaySlots=" + displaySlots
                + " (fixed 1.7 grid, real players, no extra rows)");
    }

    @Override
    public void shutdown(AlleyContext context) {
        if (this.task != null) this.task.cancel();
        if (this.listener != null) ProtocolLibrary.getProtocolManager().removePacketListener(this.listener);
        if (this.loginListener != null) ProtocolLibrary.getProtocolManager().removePacketListener(this.loginListener);
        this.viewers.clear();
    }

    private void update() {
        List<? extends Player> online = new ArrayList<>(Bukkit.getOnlinePlayers());
        this.viewers.entrySet().removeIf(entry -> !entry.getValue().viewer.isOnline()
                || Bukkit.getPlayer(entry.getKey()) != entry.getValue().viewer
                || !this.versions.isLegacy17(entry.getValue().viewer));
        if (online.stream().noneMatch(this.versions::isLegacy17)) return;

        // Loaded profiles only: never load a profile/database record in a Tab tick.
        Map<UUID, String> fallbackColors = new HashMap<>();
        for (Player target : online) {
            Profile profile = this.profiles.getProfiles().get(target.getUniqueId());
            ChatColor color = profile == null ? ChatColor.GRAY : profile.getNameColor();
            fallbackColors.put(target.getUniqueId(), color == null ? "" : color.toString());
        }

        for (Player viewer : online) {
            if (!this.versions.isLegacy17(viewer)) continue;
            try {
                LegacyTablistState state = this.viewers.compute(viewer.getUniqueId(), (id, previous) ->
                        previous != null && previous.viewer == viewer ? previous : new LegacyTablistState(viewer));
                Map<UUID, String> names = new LinkedHashMap<>();
                for (Player target : online) {
                    if (!viewer.canSee(target)) continue;
                    Team team = viewer.getScoreboard().getEntryTeam(target.getName());
                    String prefix = team == null ? fallbackColors.get(target.getUniqueId()) : team.getPrefix();
                    names.put(target.getUniqueId(), renderName(target.getName(), prefix));
                }
                Map<UUID, String> changes = state.changes(names);
                if (changes.isEmpty()) continue;
                sendNames(viewer, changes);
                state.sent(changes);
            } catch (RuntimeException exception) {
                warn(exception);
            }
        }
    }

    private void sendNames(Player viewer, Map<UUID, String> names) {
        PacketContainer packet = ProtocolLibrary.getProtocolManager().createPacket(PacketType.Play.Server.PLAYER_INFO);
        packet.getPlayerInfoActions().write(0, EnumSet.of(EnumWrappers.PlayerInfoAction.UPDATE_DISPLAY_NAME));
        List<PlayerInfoData> rows = new ArrayList<>();
        names.forEach((id, name) -> rows.add(new PlayerInfoData(id, 0, true,
                EnumWrappers.NativeGameMode.SURVIVAL, null, WrappedChatComponent.fromLegacyText(name))));
        entries(packet).write(0, rows);
        // Only UPDATE_DISPLAY_NAME is encoded. Ping, listed, gamemode, skin and
        // membership remain native; unknown/hidden UUIDs are ignored by ViaRewind.
        ProtocolLibrary.getProtocolManager().sendServerPacket(viewer, packet, false);
    }

    static StructureModifier<List<PlayerInfoData>> entries(PacketContainer packet) {
        // getPlayerInfoDataLists() matches Collection, so index 0 is EnumSet actions
        // on 1.21.11! Select List specifically or entries overwrite the action set.
        return packet.getModifier().withType(List.class, BukkitConverters.getListConverter(PlayerInfoData.getConverter()));
    }

    static String renderName(String name, String prefix) {
        // ViaRewind's final 1.7 field is 16 raw characters, including color codes.
        // Keep long account names whole rather than truncating/colliding names.
        if (name.length() > 14) return name;
        char color = 'f';
        if (prefix != null) {
            for (int index = 0; index + 1 < prefix.length(); index++) {
                char marker = prefix.charAt(index);
                if (marker != ChatColor.COLOR_CHAR && marker != '&') continue;
                char code = Character.toLowerCase(prefix.charAt(++index));
                if ((code >= '0' && code <= '9') || (code >= 'a' && code <= 'f')) color = code;
                else if (code == 'r') color = 'f';
            }
        }
        // Only the active color, without rank text, formatting or suffixes.
        return color == 'f' ? name : "" + ChatColor.COLOR_CHAR + color + name;
    }

    private synchronized void warn(RuntimeException exception) {
        long now = System.nanoTime();
        if (now < this.nextWarning) return;
        this.nextWarning = now + 30_000_000_000L;
        this.plugin.getLogger().log(Level.WARNING, "Unable to update legacy Tab layout/name colors", exception);
    }
}
