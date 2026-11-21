package dev.revere.alley.feature.bot.entity;

import com.mojang.authlib.GameProfile;
import com.mojang.authlib.properties.Property;
import dev.revere.alley.AlleyPlugin;
import dev.revere.alley.feature.knockback.KnockbackManager;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.profile.PlayerProfile;
import org.bukkit.profile.PlayerTextures;
import org.bukkit.util.Vector;

import java.nio.charset.StandardCharsets;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Collection;
import java.util.Base64;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/**
 * Creates a server-owned Player without a real client connection.
 *
 * <p>The implementation intentionally uses reflection so the plugin does not
 * package or require an NMS library. The verified runtime target is 1.21.11.</p>
 */
public final class NativeBotPlayer {
    private static final String SUPPORTED_VERSION = "1.21.11";

    private final Object handle;
    private final Object level;
    private final Object playerList;
    private final Object connection;
    private final Object packetListener;
    private final Object syntheticChannel;
    private final Player player;
    private final Method doTick;
    private final Method resetConnectionPosition;
    private final Method readOutbound;
    private final Method releaseReference;
    private final Field lateralInput;
    private final Field forwardInput;
    private final Field jumpingInput;
    private final Constructor<?> inputConstructor;
    private final Method setLastClientInput;
    private boolean removed;

    private NativeBotPlayer(Object handle, Object level, Object playerList, Object connection,
                            Object packetListener, Object syntheticChannel, Player player, Method doTick,
                            Method resetConnectionPosition, Method readOutbound, Method releaseReference,
                            Field lateralInput,
                            Field forwardInput, Field jumpingInput, Constructor<?> inputConstructor,
                            Method setLastClientInput) {
        this.handle = handle;
        this.level = level;
        this.playerList = playerList;
        this.connection = connection;
        this.packetListener = packetListener;
        this.syntheticChannel = syntheticChannel;
        this.player = player;
        this.doTick = doTick;
        this.resetConnectionPosition = resetConnectionPosition;
        this.readOutbound = readOutbound;
        this.releaseReference = releaseReference;
        this.lateralInput = lateralInput;
        this.forwardInput = forwardInput;
        this.jumpingInput = jumpingInput;
        this.inputConstructor = inputConstructor;
        this.setLastClientInput = setLastClientInput;
    }

    public static boolean isSupported() {
        if (!Bukkit.getMinecraftVersion().equals(SUPPORTED_VERSION)) return false;
        try {
            Class.forName("net.minecraft.server.level.ServerPlayer");
            Class.forName("net.minecraft.server.level.ClientInformation");
            Class.forName("net.minecraft.server.network.ServerGamePacketListenerImpl");
            Class.forName("net.minecraft.network.protocol.game.ClientboundPlayerInfoUpdatePacket");
            Class.forName("net.minecraft.world.entity.player.Input");
            Class.forName("io.netty.channel.embedded.EmbeddedChannel");
            return true;
        } catch (ClassNotFoundException ignored) {
            return false;
        }
    }

    public static String unsupportedReason() {
        return "Native bots require a Mojang-mapped Paper-compatible " + SUPPORTED_VERSION
                + " server; detected " + Bukkit.getMinecraftVersion() + ".";
    }

    public static NativeBotPlayer spawn(Location location, String name, int ping) {
        return spawn(location, name, ping, null);
    }

    public static NativeBotPlayer spawn(Location location, String name, int ping, PlayerProfile skinProfile) {
        if (!isSupported()) throw new IllegalStateException(unsupportedReason());
        if (location.getWorld() == null) throw new IllegalArgumentException("Bot location has no world");

        Object handle = null;
        Object level = null;
        Object playerList = null;
        Object syntheticChannel = null;
        Player player = null;
        try {
            Class<?> minecraftServerClass = Class.forName("net.minecraft.server.MinecraftServer");
            Class<?> serverLevelClass = Class.forName("net.minecraft.server.level.ServerLevel");
            Class<?> serverPlayerClass = Class.forName("net.minecraft.server.level.ServerPlayer");
            Class<?> clientInformationClass = Class.forName("net.minecraft.server.level.ClientInformation");
            Class<?> connectionClass = Class.forName("net.minecraft.network.Connection");
            Class<?> packetFlowClass = Class.forName("net.minecraft.network.protocol.PacketFlow");
            Class<?> cookieClass = Class.forName("net.minecraft.server.network.CommonListenerCookie");
            Class<?> listenerClass = Class.forName("net.minecraft.server.network.ServerGamePacketListenerImpl");
            Class<?> inputClass = Class.forName("net.minecraft.world.entity.player.Input");
            Class<?> embeddedChannelClass = Class.forName("io.netty.channel.embedded.EmbeddedChannel");
            Class<?> referenceCountUtilClass = Class.forName("io.netty.util.ReferenceCountUtil");

            Object craftServer = Bukkit.getServer();
            Object server = craftServer.getClass().getMethod("getServer").invoke(craftServer);
            level = location.getWorld().getClass().getMethod("getHandle").invoke(location.getWorld());
            playerList = minecraftServerClass.getMethod("getPlayerList").invoke(server);
            Object clientInformation = clientInformationClass.getMethod("createDefault").invoke(null);

            String profileName = sanitizeName(name);
            GameProfile profile = new GameProfile(UUID.randomUUID(), profileName);
            applySkin(profile, skinProfile);
            Constructor<?> playerConstructor = serverPlayerClass.getConstructor(
                    minecraftServerClass, serverLevelClass, GameProfile.class, clientInformationClass);
            handle = playerConstructor.newInstance(server, level, profile, clientInformation);

            serverPlayerClass.getMethod("setPos", double.class, double.class, double.class)
                    .invoke(handle, location.getX(), location.getY(), location.getZ());
            serverPlayerClass.getMethod("setRot", float.class, float.class)
                    .invoke(handle, location.getYaw(), location.getPitch());

            @SuppressWarnings({"rawtypes", "unchecked"})
            Object serverbound = Enum.valueOf((Class<? extends Enum>) packetFlowClass, "SERVERBOUND");
            Object connection = connectionClass.getConstructor(packetFlowClass).newInstance(serverbound);
            syntheticChannel = embeddedChannelClass.getConstructor().newInstance();
            connectionClass.getField("channel").set(connection, syntheticChannel);
            connectionClass.getField("preparing").setBoolean(connection, false);
            connectionClass.getField("isPending").setBoolean(connection, false);
            Object cookie = cookieClass.getMethod("createInitial", GameProfile.class, boolean.class)
                    .invoke(null, profile, false);
            Object listener = listenerClass.getConstructor(
                            minecraftServerClass, connectionClass, serverPlayerClass, cookieClass)
                    .newInstance(server, connection, handle, cookie);
            setField(connection, "packetListener", listener);
            setField(listener, "latency", Math.max(0, ping));

            player = (Player) serverPlayerClass.getMethod("getBukkitEntity").invoke(handle);
            player.setPersistent(false);
            player.setSleepingIgnored(true);
            player.setInvulnerable(false);
            player.setNoDamageTicks(0);
            setIntField(handle, "invulnerableTime", 0);

            registerPlayer(playerList, handle, profileName);
            sendPlayerInfo(playerList, handle);
            serverLevelClass.getMethod("addNewPlayer", serverPlayerClass).invoke(level, handle);

            return new NativeBotPlayer(
                    handle, level, playerList, connection, listener, syntheticChannel, player,
                    serverPlayerClass.getMethod("doTick"),
                    listenerClass.getMethod("resetPosition"),
                    embeddedChannelClass.getMethod("readOutbound"),
                    referenceCountUtilClass.getMethod("release", Object.class),
                    findField(serverPlayerClass, "xxa"),
                    findField(serverPlayerClass, "zza"),
                    findField(serverPlayerClass, "jumping"),
                    inputClass.getConstructor(boolean.class, boolean.class, boolean.class, boolean.class,
                            boolean.class, boolean.class, boolean.class),
                    serverPlayerClass.getMethod("setLastClientInput", inputClass));
        } catch (ReflectiveOperationException | RuntimeException exception) {
            rollbackSpawn(playerList, level, handle, player);
            drainSyntheticChannel(syntheticChannel);
            throw new IllegalStateException("Could not create native bot player", exception);
        }
    }

    private static void applySkin(GameProfile profile, PlayerProfile skinProfile) {
        if (skinProfile == null || skinProfile.getTextures().getSkin() == null) return;
        String metadata = skinProfile.getTextures().getSkinModel() == PlayerTextures.SkinModel.SLIM
                ? ",\"metadata\":{\"model\":\"slim\"}" : "";
        String json = "{\"textures\":{\"SKIN\":{\"url\":\""
                + skinProfile.getTextures().getSkin() + "\"" + metadata + "}}}";
        String value = Base64.getEncoder().encodeToString(json.getBytes(StandardCharsets.UTF_8));
        profile.getProperties().put("textures", new Property("textures", value));
    }

    public static void remove(Player player) {
        if (player == null) return;
        Object syntheticChannel = null;
        try {
            Object craftServer = Bukkit.getServer();
            Object server = craftServer.getClass().getMethod("getServer").invoke(craftServer);
            Object playerList = server.getClass().getMethod("getPlayerList").invoke(server);
            Object level = player.getWorld().getClass().getMethod("getHandle").invoke(player.getWorld());
            Object handle = player.getClass().getMethod("getHandle").invoke(player);
            Object packetListener = handle.getClass().getField("connection").get(handle);
            Object connection = getField(packetListener, "connection");
            syntheticChannel = getField(connection, "channel");
            removeNativePlayer(playerList, level, handle, player);
        } catch (ReflectiveOperationException exception) {
            player.remove();
            AlleyPlugin.getInstance().getLogger().warning(
                    "Native bot cleanup fell back to Bukkit removal: " + exception.getMessage());
        } finally {
            drainSyntheticChannel(syntheticChannel);
        }
    }

    public Player player() {
        return player;
    }

    public boolean isSpawned() {
        return !removed && player.isValid();
    }

    /**
     * Advances the normal player physics tick while keeping the synthetic
     * connection's accepted-position baseline aligned with server movement.
     */
    public void tick() {
        if (!isSpawned()) return;
        try {
            resetConnectionPosition.invoke(packetListener);
            doTick.invoke(handle);
            resetConnectionPosition.invoke(packetListener);
            clearPendingPackets();
        } catch (ReflectiveOperationException exception) {
            throw new IllegalStateException("Could not tick native bot player", exception);
        }
    }

    /** Forces the normal entity tracker to pair this player with a real viewer. */
    @SuppressWarnings("unchecked")
    public void refreshTrackingFor(Player viewer) {
        if (!isSpawned() || viewer == null || !viewer.isOnline()
                || !viewer.getWorld().equals(player.getWorld())) return;
        viewer.showPlayer(AlleyPlugin.getInstance(), player);
        try {
            Object viewerHandle = viewer.getClass().getMethod("getHandle").invoke(viewer);
            Object chunkSource = level.getClass().getMethod("getChunkSource").invoke(level);
            Object chunkMap = getField(chunkSource, "chunkMap");
            Map<Integer, Object> entityMap = (Map<Integer, Object>) getField(chunkMap, "entityMap");
            Object trackedEntity = entityMap.get(player.getEntityId());
            if (trackedEntity == null) return;

            Method updatePlayer = trackedEntity.getClass().getDeclaredMethod(
                    "updatePlayer", Class.forName("net.minecraft.server.level.ServerPlayer"));
            updatePlayer.setAccessible(true);
            updatePlayer.invoke(trackedEntity, viewerHandle);
        } catch (ReflectiveOperationException exception) {
            // Bukkit's visibility API asks Paper to repeat the pairing when available.
            viewer.showPlayer(AlleyPlugin.getInstance(), player);
        }
    }

    private void clearPendingPackets() throws ReflectiveOperationException {
        Object pending = getField(connection, "pendingActions");
        if (pending instanceof Collection<?> collection) collection.clear();
        Object outbound;
        while ((outbound = readOutbound.invoke(syntheticChannel)) != null) {
            releaseReference.invoke(null, outbound);
        }
    }

    public void face(Location target, float maximumStep) {
        Vector direction = target.toVector().subtract(player.getEyeLocation().toVector());
        if (direction.lengthSquared() < 1.0E-8D) return;

        Location desired = player.getLocation();
        desired.setDirection(direction);
        float yaw = approachAngle(player.getYaw(), desired.getYaw(), maximumStep);
        float pitch = approachAngle(player.getPitch(), desired.getPitch(), maximumStep);
        player.setRotation(yaw, pitch);
    }

    public void moveToward(Location target, double speed, double minimumDistance, double strafe) {
        Vector delta = target.toVector().subtract(player.getLocation().toVector()).setY(0.0D);
        double distance = delta.length();
        if (distance < 1.0E-6D) {
            clearMovementInput();
            return;
        }

        Vector forward = delta.multiply(1.0D / distance);
        if (distance < minimumDistance) forward.multiply(-0.55D);
        Vector side = new Vector(-forward.getZ(), 0.0D, forward.getX()).multiply(strafe);
        Vector horizontal = forward.add(side);
        if (horizontal.lengthSquared() > 1.0D) horizontal.normalize();

        horizontal.multiply(Math.max(0.0D, Math.min(1.0D, speed)));
        double yaw = Math.toRadians(player.getYaw());
        double sin = Math.sin(yaw);
        double cos = Math.cos(yaw);
        float lateral = (float) (horizontal.getX() * cos + horizontal.getZ() * sin);
        float forwardInput = (float) (-horizontal.getX() * sin + horizontal.getZ() * cos);
        setMovementInput(lateral, forwardInput,
                player.isOnGround() && shouldJump(horizontal));
    }

    public void stopMoving() {
        clearMovementInput();
        Vector velocity = player.getVelocity();
        velocity.setX(0.0D).setZ(0.0D);
        player.setVelocity(velocity);
    }

    public void clearMovementInput() {
        setMovementInput(0.0F, 0.0F, false);
    }

    public void scaleMovementInput(double scale) {
        try {
            float lateral = lateralInput.getFloat(handle);
            float forward = forwardInput.getFloat(handle);
            double length = Math.hypot(lateral, forward);
            if (length < 1.0E-6D) {
                setMovementInput(0.0F, 0.0F, false);
                return;
            }

            double clamped = Math.max(0.0D, Math.min(1.0D, scale));
            setMovementInput((float) (lateral / length * clamped),
                    (float) (forward / length * clamped), false);
        } catch (IllegalAccessException exception) {
            throw new IllegalStateException("Could not scale native bot movement input", exception);
        }
    }

    public void attack(Player target) {
        player.swingMainHand();
        AlleyPlugin.getInstance().getService(KnockbackManager.class).attack(player, target);
    }

    public void remove() {
        if (removed) return;
        removed = true;
        try {
            removeNativePlayer(playerList, level, handle, player);
        } finally {
            drainSyntheticChannel(syntheticChannel);
        }
    }

    private boolean shouldJump(Vector direction) {
        if (direction.lengthSquared() < 1.0E-8D) return false;
        Location ahead = player.getLocation().add(direction.clone().multiply(0.65D));
        return !ahead.getBlock().isPassable() && ahead.clone().add(0.0D, 1.0D, 0.0D).getBlock().isPassable();
    }

    private void setMovementInput(float lateral, float forward, boolean jumping) {
        try {
            lateralInput.setFloat(handle, lateral);
            forwardInput.setFloat(handle, forward);
            jumpingInput.setBoolean(handle, jumping);

            boolean moveForward = forward > 1.0E-4F;
            boolean moveBackward = forward < -1.0E-4F;
            boolean moveLeft = lateral > 1.0E-4F;
            boolean moveRight = lateral < -1.0E-4F;
            Object input = inputConstructor.newInstance(moveForward, moveBackward, moveLeft, moveRight,
                    jumping, false, player.isSprinting());
            setLastClientInput.invoke(handle, input);
        } catch (ReflectiveOperationException exception) {
            throw new IllegalStateException("Could not update native bot movement input", exception);
        }
    }

    @SuppressWarnings("unchecked")
    private static void registerPlayer(Object playerList, Object handle, String name)
            throws ReflectiveOperationException {
        Collection<Object> players = (Collection<Object>) playerList.getClass().getMethod("getPlayers").invoke(playerList);
        players.add(handle);
        ((Map<UUID, Object>) getField(playerList, "playersByUUID")).put(
                ((Player) handle.getClass().getMethod("getBukkitEntity").invoke(handle)).getUniqueId(), handle);
        ((Map<String, Object>) getField(playerList, "playersByName")).put(name.toLowerCase(Locale.ROOT), handle);
    }

    @SuppressWarnings("unchecked")
    private static void unregisterPlayer(Object playerList, Object handle, String name)
            throws ReflectiveOperationException {
        Collection<Object> players = (Collection<Object>) playerList.getClass().getMethod("getPlayers").invoke(playerList);
        players.remove(handle);
        UUID uuid = ((Player) handle.getClass().getMethod("getBukkitEntity").invoke(handle)).getUniqueId();
        ((Map<UUID, Object>) getField(playerList, "playersByUUID")).remove(uuid, handle);
        ((Map<String, Object>) getField(playerList, "playersByName"))
                .remove(name.toLowerCase(Locale.ROOT), handle);
    }

    private static void sendPlayerInfo(Object playerList, Object handle) throws ReflectiveOperationException {
        Class<?> packetClass = Class.forName("net.minecraft.network.protocol.game.ClientboundPlayerInfoUpdatePacket");
        Method factory = packetClass.getMethod("createPlayerInitializing", Collection.class);
        Object packet = factory.invoke(null, List.of(handle));
        sendToRealPlayers(playerList, packet, handle);
    }

    private static void sendPlayerInfoRemove(Object playerList, Object handle, UUID uuid)
            throws ReflectiveOperationException {
        Class<?> packetClass = Class.forName("net.minecraft.network.protocol.game.ClientboundPlayerInfoRemovePacket");
        Object packet = packetClass.getConstructor(List.class).newInstance(List.of(uuid));
        sendToRealPlayers(playerList, packet, handle);
    }

    private static void sendToRealPlayers(Object playerList, Object packet, Object excluded)
            throws ReflectiveOperationException {
        Class<?> packetType = Class.forName("net.minecraft.network.protocol.Packet");
        @SuppressWarnings("unchecked")
        Collection<Object> players = (Collection<Object>) playerList.getClass().getMethod("getPlayers").invoke(playerList);
        for (Object viewer : List.copyOf(players)) {
            if (viewer == excluded) continue;
            Object connection = viewer.getClass().getField("connection").get(viewer);
            if (connection == null) continue;
            Method send = java.util.Arrays.stream(connection.getClass().getMethods())
                    .filter(method -> method.getName().equals("send")
                            && method.getParameterCount() == 1
                            && method.getParameterTypes()[0].equals(packetType))
                    .findFirst()
                    .orElseThrow();
            send.invoke(connection, packet);
        }
    }

    private static void rollbackSpawn(Object playerList, Object level, Object handle, Player player) {
        if (handle == null) return;
        removeNativePlayer(playerList, level, handle, player);
    }

    private static void drainSyntheticChannel(Object channel) {
        if (channel == null) return;
        try {
            Method read = channel.getClass().getMethod("readOutbound");
            Method release = Class.forName("io.netty.util.ReferenceCountUtil")
                    .getMethod("release", Object.class);
            Object outbound;
            while ((outbound = read.invoke(channel)) != null) release.invoke(null, outbound);
        } catch (ReflectiveOperationException ignored) {
        }
    }

    private static void removeNativePlayer(Object playerList, Object level, Object handle, Player player) {
        ReflectiveOperationException failure = null;
        if (playerList != null) {
            if (player != null) {
                try {
                    sendPlayerInfoRemove(playerList, handle, player.getUniqueId());
                } catch (ReflectiveOperationException ignored) {
                    // Tab-list removal is cosmetic; entity/index cleanup still continues.
                }
            }
            try {
                unregisterPlayer(playerList, handle, player == null ? "" : player.getName());
            } catch (ReflectiveOperationException exception) {
                if (failure == null) failure = exception;
            }
        }

        if (level != null) {
            try {
                Class<?> serverPlayerClass = Class.forName("net.minecraft.server.level.ServerPlayer");
                Class<?> removalReasonClass = Class.forName("net.minecraft.world.entity.Entity$RemovalReason");
                @SuppressWarnings({"rawtypes", "unchecked"})
                Object discarded = Enum.valueOf((Class<? extends Enum>) removalReasonClass, "DISCARDED");
                level.getClass().getMethod("removePlayerImmediately", serverPlayerClass, removalReasonClass)
                        .invoke(level, handle, discarded);
            } catch (ReflectiveOperationException exception) {
                if (failure == null) failure = exception;
                if (player != null) player.remove();
            }
        } else if (player != null) {
            player.remove();
        }

        if (failure != null) {
            AlleyPlugin.getInstance().getLogger().warning(
                    "Native bot cleanup was only partially successful: " + failure.getMessage());
        }
    }

    private static Object getField(Object owner, String name) throws ReflectiveOperationException {
        Field field = findField(owner.getClass(), name);
        field.setAccessible(true);
        return field.get(owner);
    }

    private static void setField(Object owner, String name, Object value) throws ReflectiveOperationException {
        Field field = findField(owner.getClass(), name);
        field.setAccessible(true);
        field.set(owner, value);
    }

    private static void setIntField(Object owner, String name, int value) throws ReflectiveOperationException {
        Field field = findField(owner.getClass(), name);
        field.setAccessible(true);
        field.setInt(owner, value);
    }

    private static Field findField(Class<?> type, String name) throws NoSuchFieldException {
        for (Class<?> current = type; current != null; current = current.getSuperclass()) {
            try {
                return current.getDeclaredField(name);
            } catch (NoSuchFieldException ignored) {
            }
        }
        throw new NoSuchFieldException(name);
    }

    private static String sanitizeName(String name) {
        String clean = name == null ? "AlleyBot" : name.replaceAll("[^A-Za-z0-9_]", "");
        if (clean.isEmpty()) clean = "AlleyBot";
        return clean.length() > 16 ? clean.substring(0, 16) : clean;
    }

    private static float approachAngle(float current, float target, float maximumStep) {
        float difference = ((target - current + 540.0F) % 360.0F) - 180.0F;
        float step = Math.max(0.1F, maximumStep);
        return current + Math.max(-step, Math.min(step, difference));
    }
}
