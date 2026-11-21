package dev.revere.alley.common.reflect.utility;

import com.google.common.collect.ArrayListMultimap;
import com.google.common.collect.Multimap;
import com.mojang.authlib.GameProfile;
import com.mojang.authlib.properties.Property;
import com.mojang.authlib.properties.PropertyMap;

import java.util.UUID;

/** Run against authlib 6 and 7 separately: corpse IDs differ while name/skin stay intact. */
public final class GameProfileAccessRegression {
    public static void main(String[] args) throws ReflectiveOperationException {
        UUID originalId = UUID.randomUUID();
        UUID corpseId = UUID.randomUUID();
        Property texture = new Property("textures", "skin-payload", "skin-signature");
        GameProfile original = profile(originalId, texture);
        GameProfile corpse = GameProfileAccess.copyWithId(original, corpseId);

        check(GameProfileAccess.id(original).equals(originalId), "Victim UUID changed");
        check(GameProfileAccess.id(corpse).equals(corpseId), "Corpse reuses the victim UUID");
        check(GameProfileAccess.name(corpse).equals("DeathVisual"), "Corpse name changed");
        check(GameProfileAccess.properties(corpse).get("textures").contains(texture), "Corpse skin lost");
        check(GameProfileAccess.properties(original).size() == 1, "Victim properties were mutated");
        check(GameProfileAccess.properties(corpse).size() == 1, "Corpse properties were duplicated");

        GameProfile empty = GameProfileAccess.copyWithId(new GameProfile(originalId, "NoSkin"), corpseId);
        check(GameProfileAccess.name(empty).equals("NoSkin"), "Skinless corpse name changed");
        check(GameProfileAccess.properties(empty).isEmpty(), "Skinless corpse gained properties");
        System.out.println("PASS: authlib accessors, distinct corpse UUID, signed skin copy and skinless profile");
    }

    private static GameProfile profile(UUID id, Property texture) throws ReflectiveOperationException {
        try {
            Multimap<String, Property> values = ArrayListMultimap.create();
            values.put("textures", texture);
            PropertyMap properties = PropertyMap.class.getConstructor(Multimap.class).newInstance(values);
            return GameProfile.class.getConstructor(UUID.class, String.class, PropertyMap.class)
                    .newInstance(id, "DeathVisual", properties);
        } catch (NoSuchMethodException ignored) {
            GameProfile profile = new GameProfile(id, "DeathVisual");
            GameProfileAccess.properties(profile).put("textures", texture);
            return profile;
        }
    }

    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
