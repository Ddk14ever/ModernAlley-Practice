package dev.revere.alley.library.assemble;

import dev.revere.alley.common.version.ClientVersionService;
import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.Server;
import org.bukkit.entity.Player;
import org.bukkit.scoreboard.*;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Proxy;
import java.util.*;

/** Exercises the real board/service with fresh Objective wrappers on every lookup. */
public final class AssembleLifecycleRegression {
    private static final class ObjectiveState {
        String title = "";
        DisplaySlot slot;
        int formatWrites;
        final Map<String, Integer> scores = new HashMap<>();
    }

    private static final class FakeBoard {
        int teamWrites;
        int scoreWrites;
        final Map<String, ObjectiveState> objectives = new HashMap<>();
        final Map<String, Team> teams = new HashMap<>();
        final Scoreboard api = proxy(Scoreboard.class, (object, method, args) -> switch (method.getName()) {
            case "registerNewObjective" -> {
                String name = (String) args[0];
                check(!objectives.containsKey(name), "Duplicate objective registration");
                objectives.put(name, new ObjectiveState());
                yield objective(name);
            }
            case "getObjective" -> objective((String) args[0]);
            case "getTeam" -> teams.get((String) args[0]);
            case "registerNewTeam" -> {
                String name = (String) args[0];
                check(!teams.containsKey(name), "Duplicate team registration");
                Set<String> entries = new HashSet<>();
                Component[] text = {Component.empty(), Component.empty()};
                Team team = proxy(Team.class, (t, m, a) -> switch (m.getName()) {
                    case "getScoreboard" -> teams.containsKey(name) ? FakeBoard.this.api : null;
                    case "getEntries" -> entries;
                    case "addEntry" -> entries.add((String) a[0]);
                    case "prefix", "suffix" -> {
                        int half = m.getName().equals("prefix") ? 0 : 1;
                        if (a == null || a.length == 0) yield text[half];
                        teamWrites++;
                        text[half] = (Component) a[0];
                        yield null;
                    }
                    case "unregister" -> { teams.remove(name); yield null; }
                    default -> throw new UnsupportedOperationException(m.getName());
                });
                teams.put(name, team);
                yield team;
            }
            case "resetScores" -> {
                objectives.values().forEach(state -> state.scores.remove((String) args[0]));
                yield null;
            }
            default -> throw new UnsupportedOperationException(method.getName());
        });

        Objective objective(String name) {
            ObjectiveState state = objectives.get(name);
            if (state == null) return null;
            // Deliberately return a DIFFERENT wrapper for the same objective.
            return proxy(Objective.class, (object, method, args) -> switch (method.getName()) {
                case "getName" -> name;
                case "getScoreboard" -> api;
                case "getDisplayName" -> state.title;
                case "setDisplayName" -> { state.title = (String) args[0]; yield null; }
                case "getDisplaySlot" -> state.slot;
                case "setDisplaySlot" -> { state.slot = (DisplaySlot) args[0]; yield null; }
                case "numberFormat" -> { state.formatWrites++; yield null; }
                case "unregister" -> { objectives.remove(name); yield null; }
                case "getScore" -> proxy(Score.class, (score, m, a) -> switch (m.getName()) {
                    case "isScoreSet" -> state.scores.containsKey((String) args[0]);
                    case "getScore" -> state.scores.getOrDefault((String) args[0], 0);
                    case "setScore" -> {
                        scoreWrites++;
                        state.scores.put((String) args[0], (Integer) a[0]);
                        yield null;
                    }
                    default -> throw new UnsupportedOperationException(m.getName());
                });
                default -> throw new UnsupportedOperationException(method.getName());
            });
        }
    }

    private static Player player(UUID id, FakeBoard initialBoard) {
        Scoreboard[] board = {initialBoard.api};
        return proxy(Player.class, (object, method, args) -> switch (method.getName()) {
            case "getUniqueId" -> id;
            case "getName" -> "TestPlayer";
            case "isOnline" -> true;
            case "getScoreboard" -> board[0];
            case "setScoreboard" -> { board[0] = (Scoreboard) args[0]; yield null; }
            default -> throw new UnsupportedOperationException(method.getName());
        });
    }

    public static void main(String[] args) throws ReflectiveOperationException {
        FakeBoard main = new FakeBoard();
        ScoreboardManager manager = proxy(ScoreboardManager.class, (object, method, a) -> switch (method.getName()) {
            case "getMainScoreboard" -> main.api;
            case "getNewScoreboard" -> new FakeBoard().api;
            default -> throw new UnsupportedOperationException(method.getName());
        });
        Server server = proxy(Server.class, (object, method, a) -> switch (method.getName()) {
            case "getScoreboardManager" -> manager;
            case "isPrimaryThread" -> true;
            case "getLogger" -> java.util.logging.Logger.getLogger("LegacyRegression");
            case "getName", "getVersion", "getBukkitVersion", "getMinecraftVersion" -> "Regression";
            default -> throw new UnsupportedOperationException(method.getName());
        });
        // The API-only classpath has no Paper ServerBuildInfo provider for
        // Bukkit.setServer's startup banner. Install the fake in this test JVM.
        java.lang.reflect.Field serverField = Bukkit.class.getDeclaredField("server");
        serverField.setAccessible(true);
        serverField.set(null, server);
        for (boolean legacy : new boolean[]{true, false}) {
            ClientVersionService versions = proxy(ClientVersionService.class, (object, method, a) -> switch (method.getName()) {
                case "getProtocol" -> legacy ? 5 : 774;
                case "isLegacy113", "isLegacy17" -> legacy;
                default -> throw new UnsupportedOperationException(method.getName());
            });
            AssembleServiceImpl service = new AssembleServiceImpl(null, null, null, null, versions);
            service.setCallEvents(false);
            service.setAdapter(new AssembleAdapter() {
                public String getTitle(Player player) { return "Test"; }
                public List<String> getLines(Player player) { return List.of("Test"); }
            });
            UUID id = UUID.randomUUID();
            FakeBoard oldState = new FakeBoard();
            Player oldPlayer = player(id, oldState);
            service.createBoard(oldPlayer);
            AssembleBoard oldBoard = service.getBoards().get(id);
            for (int i = 0; i < 100; i++) oldBoard.getObjective();
            check(oldState.objectives.get("Assemble").formatWrites == (legacy ? 0 : 1),
                    "Number format rewritten on objective lookup");
            AssembleBoardEntry oldRow = new AssembleBoardEntry(oldBoard, "old row", 0);
            oldRow.send(1, 16);
            int teamWrites = oldState.teamWrites;
            int scoreWrites = oldState.scoreWrites;
            for (int i = 0; i < 100; i++) {
                oldRow.setup();
                oldRow.send(1, 16);
            }
            check(oldState.teamWrites == teamWrites && oldState.scoreWrites == scoreWrites,
                    "Unchanged row resent team or score updates");
            check(oldState.objectives.get("Assemble").formatWrites == (legacy ? 0 : 1),
                    "Rendering rows rewrote the number format");

            FakeBoard newState = new FakeBoard();
            Player newPlayer = player(id, newState);
            service.createBoard(newPlayer);
            AssembleBoard newBoard = service.getBoards().get(id);
            check(newBoard != oldBoard && newBoard.getPlayer() == newPlayer, "Reused old connection board");
            check(!oldState.objectives.containsKey("Assemble") && oldState.teams.isEmpty(), "Old sidebar leaked");
            service.removeBoard(oldPlayer);
            check(service.getBoards().get(id) == newBoard, "Old quit deleted the new sidebar");
            service.createBoard(newPlayer);
            check(service.getBoards().get(id) == newBoard, "Duplicate create replaced the active board");
            newPlayer.getScoreboard().registerNewObjective("showhealth", "health");
            newPlayer.getScoreboard().registerNewTeam("nt0");
            new AssembleBoardEntry(newBoard, "new row", 0).send(1, 16);
            service.removeBoard(newPlayer);
            check(!newState.objectives.containsKey("Assemble"), "Sidebar objective survived disable");
            check(newState.objectives.containsKey("showhealth") && newState.teams.keySet().equals(Set.of("nt0")),
                    "Sidebar removal damaged health or nametag teams");
            check(newPlayer.getScoreboard() == newState.api, "Sidebar removal replaced the shared board");
        }
        System.out.println("PASS: objective wrapper updates, overlapping sessions, sidebar cleanup and shared objectives");
    }

    @SuppressWarnings("unchecked")
    private static <T> T proxy(Class<T> type, InvocationHandler handler) {
        return (T) Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[]{type}, (object, method, args) -> {
            if (method.getDeclaringClass() == Object.class) return switch (method.getName()) {
                case "equals" -> object == args[0];
                case "hashCode" -> System.identityHashCode(object);
                case "toString" -> type.getSimpleName();
                default -> null;
            };
            return handler.invoke(object, method, args);
        });
    }

    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
