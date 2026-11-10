package dev.revere.alley.visual.tablist.legacy;

import org.bukkit.ChatColor;
import org.bukkit.entity.Player;

import java.lang.reflect.Proxy;
import java.util.Map;
import java.util.UUID;

/** Standalone checks for real account names and the native player-info lifecycle. */
public final class LegacyTablistRegression {
    public static void main(String[] args) throws InterruptedException {
        String section = "\u00a7";
        for (char color : "0123456789abcdef".toCharArray()) {
            String rendered = LegacyTablist.renderName("RealPlayer", section + color);
            check(ChatColor.stripColor(rendered).equals("RealPlayer"), "Changed the real name");
            check(rendered.equals(color == 'f' ? "RealPlayer" : section + color + "RealPlayer"), "Lost name color");
        }
        check(LegacyTablist.renderName("Ddk1", "&6[ADMIN] &c&l").equals(section + "cDdk1"),
                "Included rank text/styles or selected the wrong color");
        check(LegacyTablist.renderName("Ddk1", section + "a" + section + "r").equals("Ddk1"), "Ignored reset");
        check(LegacyTablist.renderName("Ddk1", null).equals("Ddk1"), "Null prefix added text");
        for (int length = 1; length <= 16; length++) {
            String realName = "N".repeat(length);
            String rendered = LegacyTablist.renderName(realName, section + "a");
            check(rendered.length() <= 16, "Exceeded the 1.7 raw-name limit");
            check(ChatColor.stripColor(rendered).equals(realName), "Truncated/corrupted an account name");
            if (length > 14) check(rendered.equals(realName), "Long name must remain intact");
        }

        UUID first = UUID.randomUUID();
        UUID second = UUID.randomUUID();
        Player oldConnection = connection();
        LegacyTablistState state = new LegacyTablistState(oldConnection);
        Map<UUID, String> both = Map.of(first, section + "aFirst", second, section + "cSecond");
        check(state.changes(both).equals(both), "Join did not update exactly the real players");
        check(state.changes(both).equals(both), "Failed send was not retried");
        state.sent(both);
        check(state.changes(both).isEmpty(), "Unchanged names would cause repeated ViaRewind remove/add flicker");

        Map<UUID, String> recolored = Map.of(first, section + "9First", second, section + "cSecond");
        Map<UUID, String> changed = state.changes(recolored);
        check(changed.equals(Map.of(first, section + "9First")), "Color change updated unrelated rows");
        state.sent(changed);

        // A native ADD/name reset can happen on Netty between a send and its commit.
        Thread nativePacket = new Thread(() -> state.invalidate(first));
        nativePacket.start();
        nativePacket.join();
        state.sent(changed);
        check(state.changes(recolored).equals(changed), "Concurrent native reset was lost at commit");
        state.sent(changed);
        check(state.changes(recolored).isEmpty(), "Native reset caused endless resends");

        Map<UUID, String> onlyFirst = Map.of(first, section + "9First");
        check(state.changes(onlyFirst).isEmpty(), "Hide/quit should use native removal, not extra rows");
        check(state.changes(recolored).equals(Map.of(second, section + "cSecond")), "Show/rejoin did not restore color");
        state.sent(recolored);
        check(state.changes(Map.of()).isEmpty(), "Empty server generated placeholder rows");
        check(state.changes(both).equals(both), "Empty-list cleanup retained stale players");

        LegacyTablistState reconnected = new LegacyTablistState(connection());
        check(reconnected.viewer != state.viewer, "Connections share state");
        state.invalidate(first); // Delayed packet from the old connection.
        check(reconnected.changes(both).equals(both), "Reconnect inherited the old connection cache");
        System.out.println("PASS: legacy Tab real names/colors, long names, native resets, dedup, hide/show and reconnect");
    }

    private static Player connection() {
        return (Player) Proxy.newProxyInstance(Player.class.getClassLoader(), new Class<?>[]{Player.class},
                (proxy, method, arguments) -> { throw new AssertionError("Unexpected Bukkit call: " + method.getName()); });
    }

    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
