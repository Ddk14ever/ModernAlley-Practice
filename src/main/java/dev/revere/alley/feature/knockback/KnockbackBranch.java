package dev.revere.alley.feature.knockback;

/** Selects the independent knockback implementation used by a profile. */
public enum KnockbackBranch {
    DEFAULT,
    LEGACY,
    MINEMENCLUB;

    public static KnockbackBranch fromName(String name) {
        if (name == null || name.isBlank()) return null;
        try {
            return valueOf(name.trim().toUpperCase(java.util.Locale.ROOT));
        } catch (IllegalArgumentException ignored) {
            return null;
        }
    }
}
