package dev.revere.alley.feature.knockback.minemen;

import org.bukkit.entity.Player;
import org.bukkit.util.Vector;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.HashMap;
import java.util.Map;

/** Verified 1.21.11 hooks; ordinary Bukkit velocity can be stale for client players. */
final class MinemenClubGravityBridge {
    private final Map<Class<?>, Access> access = new HashMap<>();

    Sample sample(Player player) throws ReflectiveOperationException {
        Access hooks = access(player);
        Object handle = hooks.getHandle().invoke(player);
        Object movement = hooks.getKnownMovement().invoke(handle);
        // ClientTickEnd clears the field to this shared constant when no move
        // was accepted. It must not seed a fresh downward packet every tick.
        if (movement == hooks.zero()) return null;
        return new Sample(movement, new Vector(hooks.x().getDouble(movement),
                hooks.y().getDouble(movement), hooks.z().getDouble(movement)));
    }

    private Access access(Player player) throws ReflectiveOperationException {
        Access cached = access.get(player.getClass());
        if (cached != null) return cached;
        Method getHandle = player.getClass().getMethod("getHandle");
        Class<?> handle = getHandle.getReturnType();
        Method known = handle.getMethod("getKnownMovement");
        Class<?> vec = known.getReturnType();
        Access resolved = new Access(getHandle, known,
                vec.getField("x"), vec.getField("y"), vec.getField("z"), vec.getField("ZERO").get(null));
        access.put(player.getClass(), resolved);
        return resolved;
    }

    record Sample(Object token, Vector movement) {
    }

    private record Access(Method getHandle, Method getKnownMovement,
                          Field x, Field y, Field z, Object zero) {
    }
}
