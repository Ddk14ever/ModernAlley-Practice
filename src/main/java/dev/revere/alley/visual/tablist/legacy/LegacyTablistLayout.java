package dev.revere.alley.visual.tablist.legacy;

import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.lang.reflect.RecordComponent;

/** Rebuilds the immutable 1.21.11 login record without changing world/login data. */
final class LegacyTablistLayout {
    private static final ClassValue<LegacyTablistLayout> LAYOUTS = new ClassValue<>() {
        @Override
        protected LegacyTablistLayout computeValue(Class<?> type) {
            return new LegacyTablistLayout(type);
        }
    };

    private final Constructor<?> constructor;
    private final Method[] accessors;
    private final int maxPlayersIndex;

    private LegacyTablistLayout(Class<?> type) {
        if (!type.isRecord()) throw new IllegalArgumentException("Expected a login record: " + type.getName());
        RecordComponent[] components = type.getRecordComponents();
        Class<?>[] parameterTypes = new Class<?>[components.length];
        this.accessors = new Method[components.length];
        int capacityIndex = -1;
        for (int index = 0; index < components.length; index++) {
            RecordComponent component = components[index];
            parameterTypes[index] = component.getType();
            this.accessors[index] = component.getAccessor();
            if (component.getName().equals("maxPlayers") && component.getType() == int.class) capacityIndex = index;
        }
        if (capacityIndex < 0) throw new IllegalArgumentException("Login record has no maxPlayers field: " + type.getName());
        this.maxPlayersIndex = capacityIndex;
        try {
            this.constructor = type.getConstructor(parameterTypes);
        } catch (ReflectiveOperationException exception) {
            throw new IllegalStateException("Cannot resolve the login record constructor", exception);
        }
    }

    static int normalizeSlots(int configured) {
        // One/two columns give 150 GUI pixels per name. More columns narrow
        // names again; never allow 0 (which hides all names in vanilla 1.7).
        return Math.max(1, Math.min(40, configured));
    }

    static Object withDisplaySlots(Object original, int configured) {
        return LAYOUTS.get(original.getClass()).copy(original, normalizeSlots(configured));
    }

    private Object copy(Object original, int slots) {
        try {
            Object[] arguments = new Object[this.accessors.length];
            for (int index = 0; index < arguments.length; index++) arguments[index] = this.accessors[index].invoke(original);
            // A smaller actual server capacity need not gain empty background rows.
            int serverCapacity = (int) arguments[this.maxPlayersIndex];
            arguments[this.maxPlayersIndex] = serverCapacity > 0 ? Math.min(serverCapacity, slots) : slots;
            return this.constructor.newInstance(arguments);
        } catch (ReflectiveOperationException exception) {
            throw new IllegalStateException("Cannot rebuild the legacy Tab login packet", exception);
        }
    }
}
