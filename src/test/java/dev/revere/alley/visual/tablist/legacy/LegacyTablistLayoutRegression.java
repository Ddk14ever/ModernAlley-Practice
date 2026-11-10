package dev.revere.alley.visual.tablist.legacy;

import java.util.Set;

/** Login record copying and the actual fixed-grid formula used by 1.7 clients. */
public final class LegacyTablistLayoutRegression {
    public record Login(int playerId, boolean hardcore, Set<String> levels, int maxPlayers,
                        int chunkRadius, int simulationDistance, boolean reducedDebugInfo,
                        boolean showDeathScreen, boolean doLimitedCrafting, Object commonPlayerSpawnInfo,
                        boolean enforcesSecureChat) {}

    public record MissingCapacity(int playerId) {}

    public static void main(String[] args) {
        Set<String> levels = Set.of("overworld", "nether");
        Object spawnInfo = new Object();
        Login original = new Login(12345, true, levels, 2000, 12, 8,
                true, false, true, spawnInfo, true);
        Login compact = (Login) LegacyTablistLayout.withDisplaySlots(original, 20);
        check(compact != original && original.maxPlayers() == 2000, "Modified a shared login packet/server capacity");
        check(compact.equals(new Login(12345, true, levels, 20, 12, 8,
                true, false, true, spawnInfo, true)), "Changed world/position/view-distance/login flags");
        check(compact.levels() == levels && compact.commonPlayerSpawnInfo() == spawnInfo,
                "Replaced nested world/login objects");
        check(clientColumns(Math.min(original.maxPlayers(), 255)) == 13, "Incorrect screenshot reproduction");
        check(clientWidth(compact.maxPlayers()) == 150 && clientColumns(compact.maxPlayers()) == 1,
                "Default layout still squeezes names into narrow columns");
        Login twoColumns = (Login) LegacyTablistLayout.withDisplaySlots(original, 40);
        check(twoColumns.maxPlayers() == 40 && clientWidth(twoColumns.maxPlayers()) == 150,
                "40 slots should keep two full-width columns");
        Login smallServer = new Login(12345, true, levels, 7, 12, 8, true, false, true, spawnInfo, true);
        check(((Login) LegacyTablistLayout.withDisplaySlots(smallServer, 20)).maxPlayers() == 7,
                "Added unnecessary background slots to a small server");
        for (int configured : new int[]{Integer.MIN_VALUE, -1, 0, 1, 19, 20, 21, 40, 80, 2000, Integer.MAX_VALUE}) {
            Login rewritten = (Login) LegacyTablistLayout.withDisplaySlots(original, configured);
            check(rewritten.maxPlayers() >= 1 && rewritten.maxPlayers() <= 40, "Invalid legacy display capacity");
            check(clientWidth(rewritten.maxPlayers()) == 150, "Config permits narrow name cells");
        }
        try {
            LegacyTablistLayout.withDisplaySlots(new MissingCapacity(42), 20);
            throw new AssertionError("Unknown packet layout must not overwrite an unrelated integer");
        } catch (IllegalArgumentException expected) {
            // A future incompatible server packet is rejected instead of corrupting login data.
        }
        System.out.println("PASS: immutable login copying, original capacity preserved, 255-slot reproduction and wide 20/40-slot grids");
    }

    private static int clientColumns(int capacity) {
        int columns = 1;
        while ((capacity + columns - 1) / columns > 20) columns++;
        return columns;
    }

    private static int clientWidth(int capacity) {
        return Math.min(150, 300 / clientColumns(capacity));
    }

    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
