package dev.revere.alley.common.version;

import dev.revere.alley.bootstrap.lifecycle.Service;
import org.bukkit.entity.Player;

/**
 * Cached client protocol version for cross-version support (ViaVersion/ViaRewind).
 * 客户端协议版本缓存，用于跨版本兼容（ViaVersion/ViaRewind）。
 *
 * <p>ViaVersion is queried first for the original client protocol; ProtocolLib
 * is a fallback when Via is unavailable. Values belong to a player connection
 * so an older connection's quit cannot evict a new connection's cache.</p>
 */
public interface ClientVersionService extends Service {
    int PROTOCOL_1_7_2 = 4;
    int PROTOCOL_1_7_10 = 5;
    int PROTOCOL_1_13 = 393;

    /**
     * @return the client protocol, or {@code -1} when it could not be resolved.
     *         客户端协议号；无法获取时返回 {@code -1}。
     */
    int getProtocol(Player player);

    /** 1.13+ widened team prefix/suffix to 64 and removed the 32-char title limit. */
    boolean isLegacy113(Player player);

    /** True for 1.7.x clients: no tablist header/footer packet and a limited font. */
    boolean isLegacy17(Player player);
}
