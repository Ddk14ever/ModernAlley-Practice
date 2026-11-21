package dev.revere.alley.feature.knockback.minemen;

import com.google.common.base.Function;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.EntityDamageEvent.DamageModifier;

import java.lang.reflect.Field;
import java.util.Map;

/** Re-evaluates existing damage reducers after Club's pre-block damage delta. */
final class MinemenClubDamageModifiers {
    private static final Field FUNCTIONS = functionsField();
    private static final DamageModifier[] ORDER = {
            DamageModifier.FREEZING, DamageModifier.HARD_HAT, DamageModifier.BLOCKING,
            DamageModifier.ARMOR, DamageModifier.RESISTANCE, DamageModifier.MAGIC,
            DamageModifier.ABSORPTION
    };

    private MinemenClubDamageModifiers() {
    }

    @SuppressWarnings("unchecked")
    static void rebuild(EntityDamageEvent event, double damage, boolean sword, boolean blocking) {
        try {
            Map<DamageModifier, Function<? super Double, Double>> functions =
                    (Map<DamageModifier, Function<? super Double, Double>>) FUNCTIONS.get(event);
            event.setDamage(DamageModifier.BASE, damage);
            if (event.isApplicable(DamageModifier.INVULNERABILITY_REDUCTION)) {
                event.setDamage(DamageModifier.INVULNERABILITY_REDUCTION, 0.0D);
            }
            double remaining = damage;
            for (DamageModifier modifier : ORDER) {
                if (!event.isApplicable(modifier)) continue;
                double value;
                if (modifier == DamageModifier.BLOCKING && sword) {
                    value = blocking && remaining > 0.0D
                            ? (1.0D + remaining) * 0.5D - remaining : 0.0D;
                } else {
                    value = functions.get(modifier).apply(remaining);
                }
                event.setDamage(modifier, value);
                remaining += value;
            }
        } catch (IllegalAccessException exception) {
            throw new IllegalStateException("Cannot re-evaluate Club damage modifiers", exception);
        }
    }

    private static Field functionsField() {
        try {
            // Verified against the project's Paper 1.21.11 API. Keeping the original
            // reducer functions preserves modern armour when the old-armour kit
            // setting is off; its existing listener can still replace them later.
            Field field = EntityDamageEvent.class.getDeclaredField("modifierFunctions");
            field.setAccessible(true);
            return field;
        } catch (ReflectiveOperationException exception) {
            throw new ExceptionInInitializerError(exception);
        }
    }
}
