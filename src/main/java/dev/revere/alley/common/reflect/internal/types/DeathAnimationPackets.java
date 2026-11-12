package dev.revere.alley.common.reflect.internal.types;

import com.mojang.authlib.GameProfile;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.entity.Player;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.UUID;

/** Builds 1.21.11 packets from an unregistered, unticked ServerPlayer. */
record DeathAnimationPackets(GameProfile profile, List<Object> spawn, List<Object> removal) {
    static DeathAnimationPackets create(Player victim, Location location, boolean legacy17, int protocol)
            throws ReflectiveOperationException {
        Class<?> serverPlayer = nms("server.level.ServerPlayer");
        Class<?> entity = nms("world.entity.Entity");
        Class<?> living = nms("world.entity.LivingEntity");
        Class<?> data = nms("network.syncher.SynchedEntityData");
        Object original = victim.getClass().getMethod("getHandle").invoke(victim);
        GameProfile originalProfile = (GameProfile) serverPlayer.getMethod("getGameProfile").invoke(original);

        // A different UUID is essential even on 1.7: ViaRewind maintains a reverse
        // UUID -> entity ID map and must never confuse the corpse with the victim.
        GameProfile profile = new GameProfile(UUID.randomUUID(), originalProfile.getName());
        profile.getProperties().putAll(originalProfile.getProperties());
        Object craftServer = Bukkit.getServer();
        Object server = craftServer.getClass().getMethod("getServer").invoke(craftServer);
        Object level = victim.getWorld().getClass().getMethod("getHandle").invoke(victim.getWorld());
        Class<?> clientInformation = nms("server.level.ClientInformation");
        Object corpse = serverPlayer.getConstructor(nms("server.MinecraftServer"), nms("server.level.ServerLevel"),
                        GameProfile.class, clientInformation)
                .newInstance(server, level, profile, clientInformation.getMethod("createDefault").invoke(null));
        // The constructor allocates a unique ID from Minecraft's own entity counter.
        // Never add this object to the level/player list or give it a connection.
        int id = (int) entity.getMethod("getId").invoke(corpse);
        entity.getMethod("setPos", double.class, double.class, double.class)
                .invoke(corpse, location.getX(), location.getY(), location.getZ());
        entity.getMethod("setRot", float.class, float.class).invoke(corpse, location.getYaw(), location.getPitch());
        entity.getMethod("setYHeadRot", float.class).invoke(corpse, location.getYaw());
        entity.getMethod("setYBodyRot", float.class).invoke(corpse, location.getYaw());
        Object originalData = entity.getMethod("getEntityData").invoke(original);
        Object corpseData = entity.getMethod("getEntityData").invoke(corpse);
        List<?> values = (List<?>) data.getMethod("getNonDefaultValues").invoke(originalData);
        if (values != null) data.getMethod("assignValues", List.class).invoke(corpseData, values);
        // These mutations apply ONLY to the detached visual, never to victim.
        entity.getMethod("setInvisible", boolean.class).invoke(corpse, false);
        entity.getMethod("setSilent", boolean.class).invoke(corpse, true);
        entity.getMethod("setNoGravity", boolean.class).invoke(corpse, true);
        entity.getMethod("setPose", nms("world.entity.Pose"))
                .invoke(corpse, constant("world.entity.Pose", "STANDING"));
        // ServerPlayer#setHealth enters CraftPlayer's health/connection syncing.
        // Only change this detached entity's metadata; it has no connection.
        Object healthAccessor = living.getField("DATA_HEALTH_ID").get(null);
        data.getMethod("set", nms("network.syncher.EntityDataAccessor"), Object.class)
                .invoke(corpseData, healthAccessor, 0.0F);

        List<Object> packets = new ArrayList<>();
        List<Object> removal = new ArrayList<>();
        if (!legacy17) packets.add(playerInfo(profile));
        // The (Entity, int, BlockPos) overload floors coordinates for block-aligned spawns.
        // Player corpses must retain the exact death position, especially at edges.
        packets.add(packet("ClientboundAddEntityPacket").getConstructor(int.class, UUID.class,
                        double.class, double.class, double.class, float.class, float.class,
                        nms("world.entity.EntityType"), int.class, nms("world.phys.Vec3"), double.class)
                .newInstance(id, profile.getId(), location.getX(), location.getY(), location.getZ(),
                        location.getPitch(), location.getYaw(), entity.getMethod("getType").invoke(corpse),
                        0, entity.getMethod("getDeltaMovement").invoke(corpse), (double) location.getYaw()));
        packets.add(packet("ClientboundSetEntityDataPacket").getConstructor(int.class, List.class)
                .newInstance(id, data.getMethod("getNonDefaultValues").invoke(corpseData)));
        packets.add(packet("ClientboundRotateHeadPacket").getConstructor(entity, byte.class)
                .newInstance(corpse, (byte) (location.getYaw() * 256.0F / 360.0F)));
        packets.add(equipment(original, id));
        // Older clients get red hurtTime from status 3 below. Translating the
        // modern hurt packet to status 2 would play a SECOND hurt sound there.
        if (protocol < 0 || protocol >= 762) {
            packets.add(packet("ClientboundHurtAnimationPacket").getConstructor(int.class, float.class)
                    .newInstance(id, 0.0F));
        }
        packets.add(packet("ClientboundEntityEventPacket").getConstructor(entity, byte.class)
                .newInstance(corpse, (byte) 3));
        removal.add(packet("ClientboundRemoveEntitiesPacket").getConstructor(int[].class)
                .newInstance((Object) new int[]{id}));
        if (!legacy17) {
            removal.add(packet("ClientboundPlayerInfoRemovePacket").getConstructor(List.class)
                    .newInstance(List.of(profile.getId())));
        }
        return new DeathAnimationPackets(profile, List.copyOf(packets), List.copyOf(removal));
    }

    private static Object playerInfo(GameProfile profile) throws ReflectiveOperationException {
        Class<?> entry = packet("ClientboundPlayerInfoUpdatePacket$Entry");
        Object value = entry.getConstructor(UUID.class, GameProfile.class, boolean.class, int.class,
                        nms("world.level.GameType"), nms("network.chat.Component"), boolean.class, int.class,
                        nms("network.chat.RemoteChatSession$Data"))
                .newInstance(profile.getId(), profile, false, 0, constant("world.level.GameType", "SURVIVAL"),
                        null, true, 0, null);
        Object actions = actions("ADD_PLAYER", "UPDATE_LISTED", "UPDATE_GAME_MODE", "UPDATE_HAT");
        return packet("ClientboundPlayerInfoUpdatePacket").getConstructor(EnumSet.class, List.class)
                .newInstance(actions, List.of(value));
    }

    private static Object equipment(Object original, int id) throws ReflectiveOperationException {
        Class<?> slot = nms("world.entity.EquipmentSlot");
        Method getItem = nms("world.entity.LivingEntity").getMethod("getItemBySlot", slot);
        Method copy = nms("world.item.ItemStack").getMethod("copy");
        Method pair = Class.forName("com.mojang.datafixers.util.Pair").getMethod("of", Object.class, Object.class);
        List<Object> equipment = new ArrayList<>();
        for (String name : List.of("MAINHAND", "OFFHAND", "FEET", "LEGS", "CHEST", "HEAD")) {
            Object equipmentSlot = constant("world.entity.EquipmentSlot", name);
            equipment.add(pair.invoke(null, equipmentSlot, copy.invoke(getItem.invoke(original, equipmentSlot))));
        }
        return packet("ClientboundSetEquipmentPacket").getConstructor(int.class, List.class).newInstance(id, equipment);
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private static EnumSet<?> actions(String... names) throws ClassNotFoundException {
        Class type = packet("ClientboundPlayerInfoUpdatePacket$Action");
        EnumSet actions = EnumSet.noneOf(type);
        for (String name : names) actions.add(Enum.valueOf(type, name));
        return actions;
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private static Object constant(String name, String value) throws ClassNotFoundException {
        return Enum.valueOf((Class<? extends Enum>) nms(name), value);
    }

    private static Class<?> packet(String name) throws ClassNotFoundException {
        return nms("network.protocol.game." + name);
    }

    private static Class<?> nms(String name) throws ClassNotFoundException {
        return Class.forName("net.minecraft." + name);
    }
}
