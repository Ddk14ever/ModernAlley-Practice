package dev.revere.alley.feature.knockback.minemen;

/** Club keeps the complete pre-block amount, including when only a delta is dealt. */
final class MinemenClubDamageWindow {
    private float lastDamage;

    MinemenClubDamageWindow(double lastDamage) {
        this.lastDamage = (float) lastDamage;
    }

    Hit evaluate(double incoming, boolean supplement) {
        float raw = (float) Math.max(0.0D, incoming);
        boolean accepted = !supplement || raw > lastDamage;
        return new Hit(raw, accepted ? (supplement ? raw - lastDamage : raw) : 0.0F,
                supplement, accepted);
    }

    void accept(Hit hit) {
        if (hit.accepted()) lastDamage = hit.raw();
    }

    float lastDamage() {
        return lastDamage;
    }

    record Hit(float raw, float damage, boolean supplement, boolean accepted) {
    }
}
