package dev.revere.alley.feature.knockback.minemen;

import org.bukkit.util.Vector;

/** One delivered knockback authorizes at most one descent, never a later jump. */
final class MinemenClubGravityFlight {
    enum Phase { ARMED, DESCENDING, ENDED }

    private final long settledAt;
    private Object lastSample;
    private Phase phase = Phase.ARMED;

    MinemenClubGravityFlight(long settledAt) {
        this.settledAt = settledAt;
    }

    Phase update(long now, boolean grounded, double vertical, Object sample) {
        if (phase == Phase.ENDED) return phase;
        boolean fresh = sample != null && sample != lastSample;
        if (sample != null) lastSample = sample;
        // Consume pre-impulse samples during the round-trip grace too. They may
        // not become a "fresh fall" when the deadline expires without movement.
        if (now < settledAt) return phase;
        if (grounded) return phase = Phase.ENDED;
        if (!fresh) return phase;
        if (!Double.isFinite(vertical) || (phase == Phase.DESCENDING && vertical > 0.005D)) {
            return phase = Phase.ENDED;
        }
        if (vertical < -1.0E-5D) phase = Phase.DESCENDING;
        return phase;
    }

    Phase phase() {
        return phase;
    }

    static boolean hasImpulse(Vector before, Vector delivered) {
        if (!MinemenClubGravityMotion.finite(before) || !MinemenClubGravityMotion.finite(delivered)
                || delivered.lengthSquared() <= 1.0E-12D) return false;
        double x = delivered.getX() - before.getX();
        double y = delivered.getY() - before.getY();
        double z = delivered.getZ() - before.getZ();
        return x * x + y * y + z * z > 1.0E-12D;
    }
}
