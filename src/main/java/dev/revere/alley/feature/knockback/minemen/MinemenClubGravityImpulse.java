package dev.revere.alley.feature.knockback.minemen;

import com.comphenix.protocol.events.PacketContainer;

import java.lang.reflect.Constructor;
import java.util.Optional;

/** Uses an additive, vertical-only impulse instead of replacing a client's old XYZ velocity. */
final class MinemenClubGravityImpulse {
    private Access access;

    PacketContainer create(double vertical) throws ReflectiveOperationException {
        if (!Double.isFinite(vertical)) throw new IllegalArgumentException("Non-finite gravity impulse");
        Access hooks = access();
        Object impulse = hooks.vector().newInstance(0.0D, (double) (float) vertical, 0.0D);
        // ViaBackwards uses this same remote centre for additive player motion.
        // Radius 0, no blocks, a silent sound, and a remote particle ensure this
        // packet has no visible explosion or sound, including on 1.7/1.8.
        return PacketContainer.fromPacket(hooks.packet().newInstance(hooks.centre(), 0.0F, 0,
                Optional.of(impulse), hooks.particle(), hooks.sound(), hooks.blockParticles()));
    }

    private Access access() throws ReflectiveOperationException {
        if (access != null) return access;
        Class<?> vector = nms("world.phys.Vec3");
        Constructor<?> vectorConstructor = vector.getConstructor(double.class, double.class, double.class);
        Class<?> holder = nms("core.Holder");
        Class<?> weighted = nms("util.random.WeightedList");
        Object silent = holder.getMethod("direct", Object.class)
                .invoke(null, nms("sounds.SoundEvents").getField("EMPTY").get(null));
        access = new Access(vectorConstructor,
                nms("network.protocol.game.ClientboundExplodePacket").getConstructor(vector,
                        float.class, int.class, Optional.class, nms("core.particles.ParticleOptions"),
                        holder, weighted),
                vectorConstructor.newInstance(0.0D, 20000.0D, 0.0D),
                nms("core.particles.ParticleTypes").getField("EXPLOSION").get(null),
                silent, weighted.getMethod("of").invoke(null));
        return access;
    }

    private static Class<?> nms(String name) throws ClassNotFoundException {
        return Class.forName("net.minecraft." + name);
    }

    private record Access(Constructor<?> vector, Constructor<?> packet, Object centre,
                          Object particle, Object sound, Object blockParticles) {
    }
}
