package dev.revere.alley.feature.match.utility;

import org.bukkit.entity.Player;

import java.lang.reflect.Proxy;

/** Waiting recovery must support custom health pools and never revive native dead entities. */
public final class MatchRespawnHealthRegression {
    public static void main(String[] args) {
        for (double maximum : new double[]{20.0D, 40.0D}) {
            double[] health = {1.0D};
            int[] writes = {0};
            int[] fireTicks = {200};
            float[] fallDistance = {150.0F};
            boolean[] online = {true};
            boolean[] dead = {false};
            Player player = (Player) Proxy.newProxyInstance(Player.class.getClassLoader(), new Class<?>[]{Player.class},
                    (object, method, arguments) -> switch (method.getName()) {
                        case "isOnline" -> online[0];
                        case "isDead" -> dead[0];
                        case "getMaxHealth" -> maximum;
                        case "getHealth" -> health[0];
                        case "setHealth" -> {
                            check(!dead[0] && online[0], "Attempted to heal a dead/offline player");
                            health[0] = (double) arguments[0];
                            writes[0]++;
                            yield null;
                        }
                        case "setFireTicks" -> { fireTicks[0] = (int) arguments[0]; yield null; }
                        case "setFallDistance" -> { fallDistance[0] = (float) arguments[0]; yield null; }
                        default -> throw new AssertionError("Recovery reset unrelated player state: " + method.getName());
                    });
            MatchRespawnHealth.restore(player);
            check(health[0] == maximum, "Waiting spectator was not fully healed");
            check(fireTicks[0] == 0 && fallDistance[0] == 0.0F, "Death fire/fall state survived recovery");
            MatchRespawnHealth.restore(player);
            check(writes[0] == 1, "Unchanged full health was repeatedly written");
            health[0] = 3.0D;
            MatchRespawnHealth.restore(player);
            check(health[0] == maximum, "Countdown did not repair later health loss");
            dead[0] = true;
            health[0] = 0.0D;
            MatchRespawnHealth.restore(player);
            check(health[0] == 0.0D && writes[0] == 2, "Bypassed native respawn by setting health");
            dead[0] = false;
            MatchRespawnHealth.restore(player);
            check(health[0] == maximum, "Recovery after native respawn failed");
            online[0] = false;
            health[0] = 2.0D;
            MatchRespawnHealth.restore(player);
            check(health[0] == 2.0D && writes[0] == 3, "Stale recovery changed an offline player");
        }
        System.out.println("PASS: waiting health, custom maximum, recurring recovery, native-death/offline guards and no inventory resets");
    }

    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
