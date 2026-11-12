package dev.revere.alley.feature.bot.match;

import dev.revere.alley.feature.bot.internal.BotServiceImpl;
import dev.revere.alley.feature.kit.Kit;
import dev.revere.alley.feature.queue.listener.PlayAgainListener;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.scheduler.BukkitTask;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.Map;
import java.util.UUID;

/** Checks cleanup ownership and cancellation without starting a native Bot/server. */
public final class BotSessionLifecycleRegression {
    public static void main(String[] args) throws ReflectiveOperationException {
        BotServiceImpl service = new BotServiceImpl();
        Player human = player(UUID.randomUUID());
        Player oldBot = player(UUID.randomUUID());
        Player newBot = player(UUID.randomUUID());
        Kit kit = new Kit("test", "test", "", "", "", null, null, 0, null, null, null);
        BotMatchSession old = new BotMatchSession(service, human, kit, null, null,
                new YamlConfiguration(), null);
        set(old, "bot", oldBot);

        check(!old.closeForRematch(), "Rematch closed a running/starting session");
        check(!old.isEnded(), "Rejected rematch changed the session");
        int[] cancellations = {0};
        BukkitTask task = (BukkitTask) Proxy.newProxyInstance(BukkitTask.class.getClassLoader(),
                new Class<?>[]{BukkitTask.class}, (object, method, arguments) -> {
                    if (!method.getName().equals("cancel")) throw new AssertionError(method.getName());
                    cancellations[0]++;
                    return null;
                });
        set(old, "task", task);
        set(old, "returnTask", task);
        set(old, "running", true);
        set(old, "pendingAttackTick", 40);
        set(old, "pendingBlockHitStartTick", 40);
        Method beginCleanup = BotMatchSession.class.getDeclaredMethod("beginCleanup");
        beginCleanup.setAccessible(true);
        check((boolean) beginCleanup.invoke(old), "First cleanup was skipped");
        check(old.isEnded() && !old.isRunning(), "Cleanup did not stop combat");
        check(cancellations[0] == 2, "AI or delayed lobby return was not cancelled");
        check(old.getPendingAttackTick() == -1 && old.getPendingBlockHitStartTick() == -1,
                "A delayed attack survived cleanup");
        check(!(boolean) beginCleanup.invoke(old), "Cleanup was entered twice");
        old.leave();
        old.shutdown();
        old.abortStart();
        old.finish(true);
        check(old.closeForRematch(), "A completed session rejected idempotent closure");
        check(cancellations[0] == 2, "Late lifecycle callbacks repeated cleanup");

        Map<UUID, BotMatchSession> humans = sessions(service, "playerSessions");
        humans.put(human.getUniqueId(), old);
        service.registerBot(old, oldBot);
        service.setInventoryOpen(human, true);
        service.complete(old);
        check(service.getActivePlayerCount() == 0 && service.getSession(oldBot) == null,
                "Completed session still occupies the human or Bot index");
        check(!service.isInventoryOpen(human), "Completed session retained its inventory marker");

        BotMatchSession next = new BotMatchSession(service, human, kit, null, null,
                new YamlConfiguration(), null);
        set(next, "bot", newBot);
        humans.put(human.getUniqueId(), next);
        service.registerBot(next, newBot);
        service.setInventoryOpen(human, true);
        service.complete(old);
        check(service.getSession(human) == next && service.getSession(newBot) == next,
                "An old completion removed the rematch session");
        check(service.isInventoryOpen(human), "Old completion cleared the rematch inventory marker");
        service.complete(next);
        check(service.getActivePlayerCount() == 0 && service.getSession(newBot) == null,
                "Rematch did not release its session indices");

        Field pendingField = PlayAgainListener.class.getDeclaredField("PENDING_BOT_REMATCHES");
        pendingField.setAccessible(true);
        @SuppressWarnings("unchecked")
        Map<UUID, Object> requests = (Map<UUID, Object>) pendingField.get(null);
        requests.put(human.getUniqueId(), new Object());
        check(PlayAgainListener.isPlayAgainPending(human), "Bot rematch was not marked pending");
        check(service.leaveMatch(human), "Leave did not cancel a rematch after session removal");
        check(!PlayAgainListener.isPlayAgainPending(human), "Cancelled rematch remained pending");
        check(!service.leaveMatch(human), "Leave claimed a nonexistent session/request");
        System.out.println("PASS: task cancellation, idempotent closure, session release, late completion isolation and rematch cancellation");
    }

    private static Player player(UUID id) {
        return (Player) Proxy.newProxyInstance(Player.class.getClassLoader(), new Class<?>[]{Player.class},
                (object, method, arguments) -> {
                    if (method.getName().equals("getUniqueId")) return id;
                    throw new AssertionError("Lifecycle accessed unrelated player state: " + method.getName());
                });
    }

    @SuppressWarnings("unchecked")
    private static Map<UUID, BotMatchSession> sessions(BotServiceImpl service, String name)
            throws ReflectiveOperationException {
        Field field = BotServiceImpl.class.getDeclaredField(name);
        field.setAccessible(true);
        return (Map<UUID, BotMatchSession>) field.get(service);
    }

    private static void set(BotMatchSession session, String name, Object value)
            throws ReflectiveOperationException {
        Field field = BotMatchSession.class.getDeclaredField(name);
        field.setAccessible(true);
        field.set(session, value);
    }

    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
