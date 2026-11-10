package dev.revere.alley.library.assemble;

import com.comphenix.protocol.PacketType;
import com.comphenix.protocol.ProtocolLibrary;
import com.comphenix.protocol.events.ListenerPriority;
import com.comphenix.protocol.events.PacketAdapter;
import com.comphenix.protocol.events.PacketEvent;
import dev.revere.alley.AlleyPlugin;
import dev.revere.alley.common.version.ClientVersionService;
import org.bukkit.entity.Player;

/**
 * Rate-limited logger for modern scoreboard packets BEFORE Via translation.
 * 仅调试用：记录 Via 翻译之前的现代记分板数据包，不是最终 1.7 数据包。
 *
 * <p>Bukkit can hold a correct sidebar while the client shows nothing, because
 * ViaRewind rewrites the packets on the way out. This listener prints what
 * leaves the server so the rewrite can be compared against what the client
 * should receive.</p>
 */
final class LegacyScoreboardPacketTrace extends PacketAdapter {
    private final ClientVersionService clientVersionService;
    private long windowStart = System.nanoTime();
    private int logged;
    private int suppressed;
    private final java.util.Map<String, Integer> counts = new java.util.TreeMap<>();

    LegacyScoreboardPacketTrace(ClientVersionService clientVersionService) {
        super(AlleyPlugin.getInstance(), ListenerPriority.MONITOR,
                PacketType.Play.Server.SCOREBOARD_OBJECTIVE,
                PacketType.Play.Server.SCOREBOARD_DISPLAY_OBJECTIVE,
                PacketType.Play.Server.SCOREBOARD_SCORE,
                PacketType.Play.Server.SCOREBOARD_TEAM);
        this.clientVersionService = clientVersionService;
    }

    void enable() {
        ProtocolLibrary.getProtocolManager().addPacketListener(this);
    }

    void disable() {
        ProtocolLibrary.getProtocolManager().removePacketListener(this);
    }

    @Override
    public synchronized void onPacketSending(PacketEvent event) {
        Player player = event.getPlayer();
        if (player == null || !this.clientVersionService.isLegacy113(player)) return;

        try {
            long now = System.nanoTime();
            if (now - this.windowStart >= 10_000_000_000L) {
                getPlugin().getLogger().info("[LegacyPkt] previous window counts=" + this.counts
                        + " suppressed=" + this.suppressed + " (before Via translation)");
                this.windowStart = now;
                this.logged = this.suppressed = 0;
                this.counts.clear();
            }
            this.counts.merge(event.getPacketType().name(), 1, Integer::sum);
            if (this.logged >= 20) {
                if (this.suppressed++ == 0) getPlugin().getLogger()
                        .info("[LegacyPkt] Detail limit reached; aggregating packets for 10 seconds.");
                return;
            }
            this.logged++;
            getPlugin().getLogger().info("[LegacyPkt] " + player.getName()
                    + " session=" + Integer.toHexString(System.identityHashCode(player))
                    + " " + event.getPacketType().name()
                    + " " + describe(event.getPacketType(), event.getPacket()));
        } catch (Throwable ignored) {
            // Diagnostics must never interfere with packet delivery.
        }
    }

    private String describe(PacketType type, com.comphenix.protocol.events.PacketContainer packet) {
        StringBuilder builder = new StringBuilder();
        com.comphenix.protocol.reflect.StructureModifier<String> strings = packet.getStrings();
        for (int index = 0; index < Math.min(4, strings.size()); index++) {
            String value = strings.readSafely(index);
            builder.append(" s").append(index).append('=')
                    .append(value == null ? "null" : value.replace("§", "\\u00a7"));
        }
        com.comphenix.protocol.reflect.StructureModifier<Integer> integers = packet.getIntegers();
        for (int index = 0; index < Math.min(4, integers.size()); index++) {
            builder.append(" i").append(index).append('=').append(integers.readSafely(index));
        }
        return builder.toString();
    }
}
