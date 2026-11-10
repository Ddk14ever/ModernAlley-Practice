package dev.revere.alley.visual.nametag;

import org.bukkit.ChatColor;
import org.bukkit.scoreboard.Team;

import java.lang.reflect.Proxy;
import java.util.HashMap;
import java.util.Map;

/** Guards against ViaRewind 4.1.3 clearing the sidebar on a team-color update. */
public final class LegacyNametagRegression {
    public static void main(String[] args) {
        for (int protocol : new int[]{5, 47, 340, 774}) {
            Map<String, Object> values = new HashMap<>();
            int[] colorWrites = {0};
            Team team = (Team) Proxy.newProxyInstance(Team.class.getClassLoader(), new Class<?>[]{Team.class},
                    (object, method, arguments) -> switch (method.getName()) {
                        case "setDisplayName", "setPrefix", "setSuffix", "setOption" -> {
                            values.put(method.getName(), arguments[0]);
                            yield null;
                        }
                        case "getColor" -> values.getOrDefault("color", ChatColor.RESET);
                        case "setColor" -> {
                            colorWrites[0]++;
                            values.put("color", arguments[0]);
                            yield null;
                        }
                        default -> throw new UnsupportedOperationException(method.getName());
                    });
            String prefix = "§a12345678901234567890";
            String suffix = "§f12345678901234567890";
            NametagAdapter adapter = new NametagAdapter(null, "nt0", prefix, suffix, NametagVisibility.ALWAYS);
            adapter.applyTeamAppearance(team, protocol < 393, protocol == 5);
            if (protocol == 5) {
                check(colorWrites[0] == 0, "1.7 emitted a sidebar-clearing team-color change");
                check(values.get("setPrefix").toString().startsWith("§a"), "1.7 lost prefix color");
            } else {
                check(colorWrites[0] == 1 && values.get("color") == ChatColor.GREEN,
                        "Modern team lost its color");
                adapter.applyTeamAppearance(team, protocol < 393, false);
                check(colorWrites[0] == 1, "Unchanged team color was resent");
            }
            int limit = protocol < 393 ? 16 : 64;
            check(values.get("setPrefix").toString().length() <= limit, "Prefix exceeds client limit");
            check(values.get("setSuffix").toString().length() <= limit, "Suffix exceeds client limit");
        }
        System.out.println("PASS: 1.7 nametag colors avoid ViaRewind sidebar clearing; newer team colors preserved");
    }

    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
