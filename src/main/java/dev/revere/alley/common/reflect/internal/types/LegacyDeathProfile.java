package dev.revere.alley.common.reflect.internal.types;

import com.mojang.authlib.GameProfile;
import com.mojang.authlib.properties.Property;
import org.bukkit.entity.Player;

import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Executor;

/** ViaRewind 4.1.x spawn-profile cache, without sending any tablist add/remove. */
final class LegacyDeathProfile {
    private static final String STORAGE =
            "com.viaversion.viarewind.protocol.v1_8to1_7_6_10.storage.GameProfileStorage";

    private final Executor eventLoop;
    private final Object storage;
    private final GameProfile profile;
    private final Method put;
    private final Method remove;
    private final List<Object> properties;
    private final java.lang.reflect.Field propertiesField;

    private LegacyDeathProfile(Executor eventLoop, Object storage, GameProfile profile,
                               Method put, Method remove, List<Object> properties,
                               java.lang.reflect.Field propertiesField) {
        this.eventLoop = eventLoop;
        this.storage = storage;
        this.profile = profile;
        this.put = put;
        this.remove = remove;
        this.properties = properties;
        this.propertiesField = propertiesField;
    }

    static LegacyDeathProfile create(Player viewer, GameProfile profile) throws ReflectiveOperationException {
        Class<?> via = Class.forName("com.viaversion.viaversion.api.Via");
        Object manager = via.getMethod("getManager").invoke(null);
        Object connections = Class.forName("com.viaversion.viaversion.api.ViaManager")
                .getMethod("getConnectionManager").invoke(manager);
        Object user = Class.forName("com.viaversion.viaversion.api.connection.ConnectionManager")
                .getMethod("getConnectedClient", UUID.class).invoke(connections, viewer.getUniqueId());
        if (user == null) throw new IllegalStateException("No Via connection for legacy death animation");
        Class<?> userType = Class.forName("com.viaversion.viaversion.api.connection.UserConnection");
        Object channel = userType.getMethod("getChannel").invoke(user);
        Executor eventLoop = (Executor) Class.forName("io.netty.channel.Channel")
                .getMethod("eventLoop").invoke(channel);
        Class<?> storageType = Class.forName(STORAGE);
        Object storage = userType.getMethod("get", Class.class).invoke(user, storageType);
        if (storage == null) throw new IllegalStateException("No ViaRewind profile cache for legacy death animation");

        Constructor<?> property = Class.forName(STORAGE + "$Property")
                .getConstructor(String.class, String.class, String.class);
        List<Object> properties = new ArrayList<>();
        for (Property value : profile.getProperties().values()) {
            properties.add(property.newInstance(value.name(), value.value(), value.signature()));
        }
        return new LegacyDeathProfile(eventLoop, storage, profile,
                storageType.getMethod("put", UUID.class, String.class, String.class, int.class, int.class),
                storageType.getMethod("remove", UUID.class), properties,
                Class.forName(STORAGE + "$GameProfile").getField("properties"));
    }

    void execute(Runnable task) {
        eventLoop.execute(task);
    }

    // Cache mutation and packet translation share the connection's event loop.
    // Reusing the real UUID instead would corrupt ViaRewind's entity/equipment maps.
    void install() throws ReflectiveOperationException {
        Object entry = put.invoke(storage, profile.getId(), profile.getName(), null, 0, 0);
        propertiesField.set(entry, new ArrayList<>(properties));
    }

    void remove() throws ReflectiveOperationException {
        remove.invoke(storage, profile.getId());
    }
}
