package dev.revere.alley.visual.tablist.legacy;

import org.bukkit.entity.Player;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentLinkedQueue;

/** One connection's last successful color updates; Bukkit state stays on the tick thread. */
final class LegacyTablistState {
    final Player viewer;
    private final Map<UUID, String> sentNames = new HashMap<>();
    private final ConcurrentLinkedQueue<UUID> invalidations = new ConcurrentLinkedQueue<>();

    LegacyTablistState(Player viewer) {
        this.viewer = viewer;
    }

    /** Called by ProtocolLib, possibly on Netty; never accesses Bukkit state. */
    void invalidate(UUID target) {
        this.invalidations.add(target);
    }

    Map<UUID, String> changes(Map<UUID, String> visibleNames) {
        UUID target;
        while ((target = this.invalidations.poll()) != null) this.sentNames.remove(target);
        this.sentNames.keySet().retainAll(visibleNames.keySet());
        Map<UUID, String> changes = new LinkedHashMap<>();
        visibleNames.forEach((id, name) -> {
            if (!name.equals(this.sentNames.get(id))) changes.put(id, name);
        });
        return changes;
    }

    /** Commit only after a successful send, so a failed update is retried. */
    void sent(Map<UUID, String> updates) {
        this.sentNames.putAll(updates);
    }
}
