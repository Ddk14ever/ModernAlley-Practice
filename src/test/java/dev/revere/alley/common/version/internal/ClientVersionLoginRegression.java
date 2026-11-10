package dev.revere.alley.common.version.internal;

import org.bukkit.Bukkit;
import org.bukkit.Server;
import org.bukkit.entity.Player;
import org.bukkit.event.player.PlayerQuitEvent;

import java.lang.reflect.Field;
import java.lang.reflect.Proxy;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;

/** Resolving the original protocol before PlayerJoinEvent must also work on Netty. */
public final class ClientVersionLoginRegression {
    public static final class ViaApi {
        int protocol = 5;
        public int getPlayerVersion(UUID uuid) { return this.protocol; }
    }

    public static void main(String[] args) throws ReflectiveOperationException {
        AtomicBoolean primary = new AtomicBoolean(false);
        Server server = (Server) Proxy.newProxyInstance(Server.class.getClassLoader(), new Class<?>[]{Server.class},
                (proxy, method, arguments) -> {
                    if (method.getName().equals("isPrimaryThread")) return primary.get();
                    throw new AssertionError("Unexpected Bukkit call from protocol lookup: " + method.getName());
                });
        Field serverField = Bukkit.class.getDeclaredField("server");
        serverField.setAccessible(true);
        serverField.set(null, server);

        ClientVersionServiceImpl versions = new ClientVersionServiceImpl();
        ViaApi via = new ViaApi();
        set(versions, "viaProbeDone", true);
        set(versions, "viaApi", via);
        set(versions, "viaGetPlayerVersionUuid", ViaApi.class.getMethod("getPlayerVersion", UUID.class));
        UUID uuid = UUID.randomUUID();
        Player oldConnection = connection(uuid);
        check(versions.isLegacy17(oldConnection), "Join Game on Netty could not identify 1.7 before the join event");
        check(protocolCache(versions).isEmpty(), "Netty wrote a Bukkit session cache entry");

        primary.set(true);
        check(versions.getProtocol(oldConnection) == 5 && protocolCache(versions).size() == 1, "Main-thread cache failed");
        primary.set(false);
        Player newConnection = connection(uuid);
        via.protocol = 774;
        check(!versions.isLegacy17(newConnection), "A modern reconnect inherited the old connection's protocol");
        via.protocol = 4;
        check(versions.isLegacy17(newConnection), "An off-thread read was incorrectly cached");

        primary.set(true);
        via.protocol = 774;
        check(versions.getProtocol(newConnection) == 774, "New session was not cached");
        versions.onQuit(new PlayerQuitEvent(oldConnection, ""));
        via.protocol = 5;
        primary.set(false);
        check(versions.getProtocol(newConnection) == 774, "Old quit evicted the new session's protocol");
        versions.onQuit(new PlayerQuitEvent(newConnection, ""));
        check(protocolCache(versions).isEmpty(), "Current connection cleanup failed");
        System.out.println("PASS: pre-join Netty protocol resolution, modern exclusion and overlapping session cache safety");
    }

    private static Player connection(UUID uuid) {
        return (Player) Proxy.newProxyInstance(Player.class.getClassLoader(), new Class<?>[]{Player.class},
                (proxy, method, arguments) -> {
                    if (method.getName().equals("getUniqueId")) return uuid;
                    throw new AssertionError("Unexpected player call from protocol lookup: " + method.getName());
                });
    }

    private static Map<?, ?> protocolCache(ClientVersionServiceImpl service) throws ReflectiveOperationException {
        Field field = ClientVersionServiceImpl.class.getDeclaredField("protocols");
        field.setAccessible(true);
        return (Map<?, ?>) field.get(service);
    }

    private static void set(Object object, String name, Object value) throws ReflectiveOperationException {
        Field field = object.getClass().getDeclaredField(name);
        field.setAccessible(true);
        field.set(object, value);
    }

    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
