package dev.revere.alley.feature.knockback.minemen;

import org.bukkit.Location;

import java.util.ArrayList;
import java.util.List;

/** Club's 30 ms samples and ping + 92 ms interpolation, confined to one world. */
final class MinemenClubPositionHistory {
    private final List<Sample> samples = new ArrayList<>(41);

    void record(Location location, long now) {
        if (!samples.isEmpty()) {
            Sample last = samples.getLast();
            if (!last.location().getWorld().equals(location.getWorld()) || now < last.time()) {
                samples.clear();
            } else if (now - last.time() < 30L) {
                return;
            }
        }
        samples.add(new Sample(location.clone(), now));
        // The reference checks the old size > 40 after insertion: it retains 41.
        if (samples.size() > 41) samples.removeFirst();
    }

    Location rewind(Location current, int ping, long now) {
        long rewind = Math.max(0L, (long) ping) + 92L;
        for (int i = samples.size() - 1; i >= 0; i--) {
            Sample before = samples.get(i);
            if (!before.location().getWorld().equals(current.getWorld())) return current.clone();
            long age = now - before.time();
            if (age < rewind) continue;
            if (i == samples.size() - 1) return before.location().clone();
            Sample after = samples.get(i + 1);
            double weight = (double) (age - rewind) / (after.time() - before.time());
            return before.location().clone().add(after.location().toVector()
                    .subtract(before.location().toVector()).multiply(weight));
        }
        // Club falls back to current position when the history is too short.
        return current.clone();
    }

    private record Sample(Location location, long time) {
    }
}
