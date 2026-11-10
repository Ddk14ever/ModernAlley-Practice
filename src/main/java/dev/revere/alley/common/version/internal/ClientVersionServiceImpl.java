package dev.revere.alley.common.version.internal;

import com.comphenix.protocol.ProtocolLibrary;
import com.comphenix.protocol.ProtocolManager;
import dev.revere.alley.AlleyPlugin;
import dev.revere.alley.bootstrap.AlleyContext;
import dev.revere.alley.bootstrap.annotation.Service;
import dev.revere.alley.common.logger.Logger;
import dev.revere.alley.common.version.ClientVersionService;
import dev.revere.alley.core.config.ConfigService;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;

import java.lang.reflect.Method;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Resolves and caches each player's real client protocol.
 * 解析并缓存每个玩家真正的客户端协议版本。
 *
 * <p>ProtocolLib alone is not enough: {@code ProtocolManager#getProtocolVersion}
 * reports the protocol stored on the server-side {@code Connection}, which
 * ViaVersion leaves at the <em>server</em> version. A 1.7 client therefore looks
 * like a 1.21 client through ProtocolLib. ViaVersion's own API keeps the client
 * side protocol, so it is queried first through reflection (no compile-time
 * dependency on ViaVersion or ViaRewind).</p>
 */
@Service(provides = ClientVersionService.class, priority = 200)
public class ClientVersionServiceImpl implements ClientVersionService, Listener {
    private record ClientProtocol(Player player, int protocol) {}
    private final Map<UUID, ClientProtocol> protocols = new ConcurrentHashMap<>();

    /** Reflection handles into ViaVersion's API; resolved once, then reused. */
    private volatile boolean viaProbeDone;
    private volatile Object viaApi;
    private volatile Method viaGetPlayerVersionUuid;
    private volatile Method viaGetPlayerVersionPlayer;
    private volatile Method viaVersionReadInt;

    @Override
    public void initialize(AlleyContext context) {
        viaApi();
        Bukkit.getPluginManager().registerEvents(this, AlleyPlugin.getInstance());
        // Players already online (plugin reload) still need a cached value.
        Bukkit.getOnlinePlayers().forEach(this::cache);
    }

    @Override
    public void shutdown(AlleyContext context) {
        this.protocols.clear();
    }

    /**
     * Runs first so the protocol is cached before the scoreboard/tablist
     * listeners create any board for this player.
     * 优先于其它 join 监听器执行，确保记分板/制表符读取前协议已缓存。
     */
    @EventHandler(priority = EventPriority.LOWEST)
    public void onJoin(PlayerJoinEvent event) {
        cache(event.getPlayer());
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onQuit(PlayerQuitEvent event) {
        Player player = event.getPlayer();
        this.protocols.computeIfPresent(player.getUniqueId(),
                (id, cached) -> cached.player() == player ? null : cached);
    }

    @Override
    public int getProtocol(Player player) {
        if (player == null) return -1;
        ClientProtocol cached = this.protocols.get(player.getUniqueId());
        if (cached != null && cached.player() == player) return cached.protocol();

        // Join Game leaves before PlayerJoinEvent, possibly on Netty. Via's
        // UUID lookup only reads its connection/protocol state, so it is safe
        // before the Bukkit cache exists. Do not query ProtocolLib or mutate
        // the session cache off-thread (an older connection may still exist).
        if (!Bukkit.isPrimaryThread()) return resolveViaVersion(player);

        int resolved = resolve(player);
        if (resolved > 0) this.protocols.put(player.getUniqueId(), new ClientProtocol(player, resolved));
        return resolved;
    }

    @Override
    public boolean isLegacy113(Player player) {
        int protocol = getProtocol(player);
        return protocol > 0 && protocol < PROTOCOL_1_13;
    }

    @Override
    public boolean isLegacy17(Player player) {
        int protocol = getProtocol(player);
        return protocol == PROTOCOL_1_7_2 || protocol == PROTOCOL_1_7_10;
    }

    private void cache(Player player) {
        int resolved = resolve(player);
        if (resolved <= 0) return;
        this.protocols.put(player.getUniqueId(), new ClientProtocol(player, resolved));
        if (isDebug() && resolved < PROTOCOL_1_13) {
            Logger.info("Detected legacy client " + player.getName() + " (protocol " + resolved + ").");
        }
    }

    /**
     * @return the client protocol, or {@code -1} when it cannot be resolved.
     *         客户端协议号；无法获取时返回 {@code -1}。
     */
    private int resolve(Player player) {
        int via = resolveViaVersion(player);
        if (via > 0) return via;
        return resolveProtocolLib(player);
    }

    /**
     * Asks ViaVersion for the client-side protocol. Returns -1 when ViaVersion
     * is absent or its API shape is unknown, so the caller falls back.
     */
    private int resolveViaVersion(Player player) {
        Object api = viaApi();
        if (api == null) return -1;

        Object version = null;
        try {
            if (this.viaGetPlayerVersionUuid != null) {
                version = this.viaGetPlayerVersionUuid.invoke(api, player.getUniqueId());
            }
            if (version == null && this.viaGetPlayerVersionPlayer != null) {
                version = this.viaGetPlayerVersionPlayer.invoke(api, player);
            }
        } catch (ReflectiveOperationException | RuntimeException ignored) {
            return -1;
        }
        return readProtocol(version);
    }

    /**
     * Extracts an int protocol from ViaVersion's ProtocolVersion object.
     * The accessor moved between ViaVersion majors, so try each known name.
     */
    private int readProtocol(Object version) {
        if (version == null) return -1;
        // Older ViaVersion majors return the int protocol directly.
        if (version instanceof Number number) return number.intValue();

        Method reader = this.viaVersionReadInt;
        if (reader == null || !reader.getDeclaringClass().isInstance(version)) {
            for (String name : new String[]{"getProtocol", "getVersion", "getOriginalVersion"}) {
                try {
                    Method candidate = version.getClass().getMethod(name);
                    if (candidate.getReturnType() == int.class) {
                        this.viaVersionReadInt = candidate;
                        reader = candidate;
                        break;
                    }
                } catch (NoSuchMethodException ignored) {
                }
            }
        }
        if (reader == null) return -1;

        try {
            Object value = reader.invoke(version);
            return value instanceof Integer protocol ? protocol : -1;
        } catch (ReflectiveOperationException | RuntimeException ignored) {
            return -1;
        }
    }

    /** Lazily binds to {@code com.viaversion.viaversion.api.Via#getAPI()}. */
    private Object viaApi() {
        if (this.viaProbeDone) return this.viaApi;
        synchronized (this) {
            if (this.viaProbeDone) return this.viaApi;

            try {
                Class<?> viaClass = Class.forName("com.viaversion.viaversion.api.Via");
                Object api = viaClass.getMethod("getAPI").invoke(null);
                if (api != null) {
                    this.viaApi = api;
                    bindViaMethods(api.getClass());
                }
            } catch (Throwable ignored) {
                // ViaVersion not installed, or a shape we do not understand.
                this.viaApi = null;
            }
            this.viaProbeDone = true;
            return this.viaApi;
        }
    }

    private void bindViaMethods(Class<?> apiClass) {
        for (Method method : apiClass.getMethods()) {
            String name = method.getName();
            // getPlayerVersion (older) / getPlayerProtocolVersion (newer).
            if (!(name.equals("getPlayerVersion") || name.equals("getPlayerProtocolVersion"))
                    || method.getParameterCount() != 1) {
                continue;
            }
            Class<?> parameter = method.getParameterTypes()[0];
            if (parameter == UUID.class) {
                this.viaGetPlayerVersionUuid = method;
            } else if (Player.class.isAssignableFrom(parameter)) {
                this.viaGetPlayerVersionPlayer = method;
            }
        }
    }

    /**
     * ProtocolLib fallback: only correct when no cross-version plugin rewrites
     * the connection's protocol. Kept so platforms without Via still resolve.
     */
    private int resolveProtocolLib(Player player) {
        try {
            ProtocolManager manager = ProtocolLibrary.getProtocolManager();
            if (manager == null) return -1;
            return manager.getProtocolVersion(player);
        } catch (Throwable ignored) {
            return -1;
        }
    }

    /** Opt-in diagnostics under {@code scoreboard.legacy-glyphs.debug}. */
    private boolean isDebug() {
        try {
            ConfigService configService = AlleyPlugin.getInstance().getService(ConfigService.class);
            return configService != null && configService.getScoreboardConfig() != null
                    && configService.getScoreboardConfig().getBoolean("scoreboard.legacy-glyphs.debug", false);
        } catch (RuntimeException ignored) {
            return false;
        }
    }
}
