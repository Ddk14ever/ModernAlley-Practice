package dev.revere.alley.library.assemble;

import dev.revere.alley.AlleyPlugin;
import dev.revere.alley.library.assemble.enums.AssembleStyle;
import dev.revere.alley.library.assemble.events.AssembleBoardCreateEvent;
import dev.revere.alley.library.assemble.events.AssembleBoardDestroyEvent;
import dev.revere.alley.library.assemble.listener.AssembleListener;
import dev.revere.alley.library.assemble.utility.LegacyGlyphs;
import dev.revere.alley.core.config.ConfigService;
import dev.revere.alley.bootstrap.AlleyContext;
import dev.revere.alley.bootstrap.annotation.Service;
import dev.revere.alley.core.profile.ProfileService;
import dev.revere.alley.common.animation.AnimationService;
import dev.revere.alley.common.logger.Logger;
import dev.revere.alley.common.version.ClientVersionService;
import lombok.Getter;
import lombok.Setter;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.entity.Player;
import org.bukkit.event.HandlerList;

import java.util.Collections;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

@Getter
@Setter
@Service(provides = AssembleService.class, priority = 320)
public class AssembleServiceImpl implements AssembleService {
    private final AlleyPlugin plugin;
    private final AnimationService animationService;
    private final ProfileService profileService;
    private final ConfigService configService;
    private final ClientVersionService clientVersionService;

    private final Map<UUID, AssembleBoard> boards = new ConcurrentHashMap<>();
    private final Map<UUID, AssembleBoard> pendingBoardRemovals = new ConcurrentHashMap<>();
    private final ChatColor[] chatColorCache = ChatColor.values();

    private AssembleAdapter adapter;
    private AssembleThread thread;
    private AssembleListener listeners;
    private LegacyScoreboardPacketTrace packetTrace;
    private AssembleStyle assembleStyle = AssembleStyle.MODERN;

    private boolean hook;
    private boolean debugMode = true;
    private boolean callEvents = true;
    private long ticks = 2;

    public AssembleServiceImpl(AlleyPlugin plugin, AnimationService animationService, ProfileService profileService,
                               ConfigService configService, ClientVersionService clientVersionService) {
        this.plugin = plugin;
        this.animationService = animationService;
        this.profileService = profileService;
        this.configService = configService;
        this.clientVersionService = clientVersionService;
    }

    @Override
    public void initialize(AlleyContext context) {
        this.adapter = new AssembleAdapterImpl(animationService, profileService, configService);

        this.listeners = new AssembleListener(this);
        this.plugin.getServer().getPluginManager().registerEvents(this.listeners, this.plugin);

        LegacyGlyphs legacyGlyphs = LegacyGlyphs.shared();
        applyGlyphOverrides(legacyGlyphs);

        for (Player player : this.plugin.getServer().getOnlinePlayers()) {
            if (this.callEvents) {
                AssembleBoardCreateEvent createEvent = new AssembleBoardCreateEvent(player);
                Bukkit.getPluginManager().callEvent(createEvent);
                if (createEvent.isCancelled()) continue;
            }
            this.boards.putIfAbsent(player.getUniqueId(), new AssembleBoard(player, this));
        }

        boolean traceLegacy = false;
        try {
            traceLegacy = this.configService.getScoreboardConfig() != null
                    && this.configService.getScoreboardConfig()
                            .getBoolean("scoreboard.legacy-glyphs.debug", false);
        } catch (RuntimeException ignored) {
            // Diagnostics must never break startup.
        }
        this.thread = new AssembleThread(this, this.clientVersionService, legacyGlyphs, traceLegacy);
        long updatePeriod = Math.max(1L, this.ticks);
        this.thread.runTaskTimer(this.plugin, 0L, updatePeriod);

        boolean tracePackets = this.configService.getScoreboardConfig()
                .getBoolean("scoreboard.legacy-glyphs.packet-trace", false);
        this.plugin.getLogger().info("[LegacySidebar] renderer=teams-v4 diagnostic="
                + this.configService.getScoreboardConfig().getString("scoreboard.legacy-diagnostic", "off")
                + " debug=" + traceLegacy + " packetTrace=" + tracePackets);
        if (tracePackets) {
            // Modern packets before Via rewriting; opt-in and rate limited.
            try {
                this.packetTrace = new LegacyScoreboardPacketTrace(this.clientVersionService);
                this.packetTrace.enable();
            } catch (Throwable ignored) {
                this.packetTrace = null;
            }
        }
    }

    /**
     * Loads the optional 1.7 glyph override table from the scoreboard config.
     * 从记分板配置读取可选的 1.7 字形覆盖表。
     */
    private void applyGlyphOverrides(LegacyGlyphs legacyGlyphs) {
        try {
            org.bukkit.configuration.file.FileConfiguration config = this.configService.getScoreboardConfig();
            if (config == null || !config.getBoolean("scoreboard.legacy-glyphs.enabled", true)) {
                return;
            }
            org.bukkit.configuration.ConfigurationSection section =
                    config.getConfigurationSection("scoreboard.legacy-glyphs.replacements");
            if (section == null) return;

            java.util.Map<String, String> overrides = new java.util.LinkedHashMap<>();
            for (String key : section.getKeys(false)) {
                overrides.put(key, section.getString(key));
            }
            legacyGlyphs.applyOverrides(overrides);
        } catch (RuntimeException ignored) {
            // A malformed override table must never break the scoreboard.
        }
    }

    @Override
    public void shutdown(AlleyContext context) {
        if (this.thread != null) {
            this.thread.cancel();
            this.thread = null;
        }

        if (this.listeners != null) {
            HandlerList.unregisterAll(this.listeners);
            this.listeners = null;
        }

        if (this.packetTrace != null) {
            this.packetTrace.disable();
            this.packetTrace = null;
        }

        this.boards.values().forEach(AssembleBoard::clearSidebar);
        this.boards.clear();
        this.pendingBoardRemovals.clear();
        Logger.info("Assemble scoreboard has been shut down.");
    }

    @Override
    public void createBoard(Player player) {
        if (!Bukkit.isPrimaryThread()) {
            Bukkit.getScheduler().runTask(this.plugin, () -> {
                if (player.isOnline() && Bukkit.getPlayer(player.getUniqueId()) == player) createBoard(player);
            });
            return;
        }
        AssembleBoard previous = this.boards.get(player.getUniqueId());
        if (previous != null && previous.getPlayer() == player) return;
        if (isCallEvents()) {
            AssembleBoardCreateEvent createEvent = new AssembleBoardCreateEvent(player);
            Bukkit.getPluginManager().callEvent(createEvent);
            if (createEvent.isCancelled()) {
                return;
            }
        }
        if (previous != null) previous.clearSidebar();
        this.boards.put(player.getUniqueId(), new AssembleBoard(player, this));
    }

    @Override
    public void removeBoard(Player player) {
        UUID playerId = player.getUniqueId();
        AssembleBoard expectedBoard = this.boards.get(playerId);
        // A delayed quit from an old connection must not delete the new
        // connection's sidebar when both have the same UUID.
        if (expectedBoard == null || expectedBoard.getPlayer() != player) return;

        if (!Bukkit.isPrimaryThread()) {
            if (!this.plugin.isEnabled()
                    || this.pendingBoardRemovals.putIfAbsent(playerId, expectedBoard) != null) {
                return;
            }

            try {
                Bukkit.getScheduler().runTask(this.plugin, () -> {
                    try {
                        this.removeBoard(player, expectedBoard);
                    } finally {
                        this.pendingBoardRemovals.remove(playerId, expectedBoard);
                    }
                });
            } catch (RuntimeException exception) {
                this.pendingBoardRemovals.remove(playerId, expectedBoard);
                if (this.plugin.isEnabled()) throw exception;
            }
            return;
        }

        this.pendingBoardRemovals.remove(playerId, expectedBoard);
        this.removeBoard(player, expectedBoard);
    }

    private void removeBoard(Player player, AssembleBoard expectedBoard) {
        UUID playerId = player.getUniqueId();
        if (this.boards.get(playerId) != expectedBoard) return;

        if (isCallEvents()) {
            AssembleBoardDestroyEvent destroyEvent = new AssembleBoardDestroyEvent(player);
            Bukkit.getPluginManager().callEvent(destroyEvent);
            if (destroyEvent.isCancelled()) {
                return;
            }
        }
        if (!this.boards.remove(playerId, expectedBoard)) return;
        expectedBoard.clearSidebar();
    }

    @Override
    public Map<UUID, AssembleBoard> getBoards() {
        return Collections.unmodifiableMap(this.boards);
    }
}
