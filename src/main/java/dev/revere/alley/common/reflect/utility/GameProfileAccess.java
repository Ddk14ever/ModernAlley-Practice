package dev.revere.alley.common.reflect.utility;

import com.mojang.authlib.GameProfile;
import com.mojang.authlib.properties.PropertyMap;

import java.lang.reflect.Method;
import java.util.UUID;

/** Bridges authlib 6 accessors and authlib 7's immutable record profiles. */
public final class GameProfileAccess {
    private GameProfileAccess() {
    }

    public static UUID id(GameProfile profile) throws ReflectiveOperationException {
        return (UUID) accessor("id", "getId").invoke(profile);
    }

    public static String name(GameProfile profile) throws ReflectiveOperationException {
        return (String) accessor("name", "getName").invoke(profile);
    }

    public static PropertyMap properties(GameProfile profile) throws ReflectiveOperationException {
        return (PropertyMap) accessor("properties", "getProperties").invoke(profile);
    }

    public static GameProfile copyWithId(GameProfile original, UUID id)
            throws ReflectiveOperationException {
        String name = name(original);
        PropertyMap properties = properties(original);
        try {
            // Authlib 7's properties are immutable; its two-argument constructor
            // uses PropertyMap.EMPTY. Supply the skin in the record constructor.
            return GameProfile.class.getConstructor(UUID.class, String.class, PropertyMap.class)
                    .newInstance(id, name, properties);
        } catch (NoSuchMethodException ignored) {
            // Authlib 6 has a mutable map and only the two-argument constructor.
            GameProfile copy = new GameProfile(id, name);
            properties(copy).putAll(properties);
            return copy;
        }
    }

    private static Method accessor(String recordName, String getterName) throws NoSuchMethodException {
        try {
            return GameProfile.class.getMethod(recordName);
        } catch (NoSuchMethodException ignored) {
            return GameProfile.class.getMethod(getterName);
        }
    }
}
