package dev.revere.alley.feature.knockback.minemen;

import dev.revere.alley.AlleyPlugin;
import dev.revere.alley.feature.knockback.nms.LegacyMotionBridge;
import org.bukkit.entity.Player;
import org.bukkit.util.Vector;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.HashMap;
import java.util.Map;

/** Silent motion writes and cancellation cleanup for the verified 1.21.11 native flow. */
final class MinemenClubDeliveryBridge {
    private final LegacyMotionBridge motion = new LegacyMotionBridge();
    private final Map<Class<?>, Method> handles = new HashMap<>();
    private final Map<Class<?>, Field> hurtMarkedFields = new HashMap<>();
    private boolean warned;

    void setMotion(Player player, Vector velocity) {
        motion.setDeltaMovement(player, velocity);
    }

    void clearVelocityMarker(Player player) {
        try {
            Method getHandle = handles.get(player.getClass());
            if (getHandle == null) {
                getHandle = player.getClass().getMethod("getHandle");
                handles.put(player.getClass(), getHandle);
            }
            Object handle = getHandle.invoke(player);
            Field field = hurtMarkedFields.get(handle.getClass());
            if (field == null) {
                field = handle.getClass().getField("hurtMarked");
                hurtMarkedFields.put(handle.getClass(), field);
            }
            field.setBoolean(handle, false);
        } catch (ReflectiveOperationException exception) {
            if (!warned) {
                warned = true;
                AlleyPlugin.getInstance().getLogger().warning(
                        "[MinemenClub] Cannot clear native velocity marker: " + exception);
            }
        }
    }
}
