package dev.revere.alley.feature.autoclick.internal;

import com.comphenix.protocol.PacketType;
import com.comphenix.protocol.ProtocolLibrary;
import com.comphenix.protocol.ProtocolManager;
import com.comphenix.protocol.events.ListenerPriority;
import com.comphenix.protocol.events.PacketAdapter;
import com.comphenix.protocol.events.PacketEvent;
import com.comphenix.protocol.events.PacketContainer;
import com.comphenix.protocol.wrappers.BlockPosition;
import com.comphenix.protocol.wrappers.EnumWrappers;
import com.comphenix.protocol.wrappers.WrappedRegistrable;
import com.comphenix.protocol.wrappers.nbt.NbtCompound;
import com.comphenix.protocol.wrappers.nbt.NbtFactory;
import dev.revere.alley.AlleyPlugin;
import dev.revere.alley.bootstrap.AlleyContext;
import dev.revere.alley.bootstrap.annotation.Service;
import dev.revere.alley.core.config.ConfigService;
import dev.revere.alley.feature.autoclick.AutoClickService;
import dev.revere.alley.feature.cps.CPSListener;
import dev.revere.alley.feature.knockback.KnockbackManager;
import dev.revere.alley.feature.knockback.KnockbackProfile;
import dev.revere.alley.feature.knockback.data.PlayerKnockbackData;
import dev.revere.alley.feature.match.MatchService;
import dev.revere.alley.feature.match.combat.legacy.LegacyCombatService;
import dev.revere.alley.feature.match.combat.legacy.LegacyHitboxes;
import dev.revere.alley.feature.match.internal.MatchServiceImpl;
import io.papermc.paper.event.player.PlayerStopUsingItemEvent;
import org.bukkit.Bukkit;
import org.bukkit.FluidCollisionMode;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.data.BlockData;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerGameModeChangeEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerTeleportEvent;
import org.bukkit.event.player.PlayerChangedWorldEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.scheduler.BukkitTask;
import org.bukkit.util.BoundingBox;
import org.bukkit.util.RayTraceResult;
import org.bukkit.util.Vector;

import java.io.File;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * A deliberately small, opt-in server-side autoclick implementation.
 *
 * <p>The client does not expose a mouse-key-up packet. To obtain a useful
 * hold signal without a client mod, this service sends one client-only,
 * invisible end-gateway probe along the player's current view ray. End gateways
 * have an empty collision shape, an unbreakable state and a non-empty outline
 * shape, so the client can mine the probe without an invisible movement wall.
 * The packet is cancelled before vanilla block breaking sees it.</p>
 *
 * <p>The probe is kept outside the player's bounding box and is restored on
 * every disable/quit/world change. It is intentionally not a hemisphere of
 * barriers: client-side barrier collision would create an invisible wall.
 * The probe follows the ray instead.</p>
 */
@Service(provides = AutoClickService.class, priority = 500)
public final class AutoClickServiceImpl implements AutoClickService, Listener {

    private static final long RECENT_PROBE_NANOS = 1_000_000_000L;

    private final AlleyPlugin plugin;
    private final KnockbackManager knockbackManager;
    private final MatchService matchService;
    private final Map<UUID, Session> sessions = new ConcurrentHashMap<>();

    private ProtocolManager protocolManager;
    private PacketAdapter packetListener;
    private BukkitTask tickTask;

    private int attackPeriodTicks = 2;
    private double maxAttackRange = 3.0D;
    private double raySize = 0.05D;
    private Material probeMaterial = Material.END_GATEWAY;
    private double probeMinDistance = 1.15D;
    private double probeMaxDistance = 1.85D;
    private long probeTransitionTimeoutNanos = 500_000_000L;

    public AutoClickServiceImpl(
            AlleyPlugin plugin,
            KnockbackManager knockbackManager,
            MatchService matchService
    ) {
        this.plugin = plugin;
        this.knockbackManager = knockbackManager;
        this.matchService = matchService;
    }

    @Override
    public void initialize(AlleyContext context) {
        reloadConfiguration();
        this.protocolManager = ProtocolLibrary.getProtocolManager();
        this.plugin.getServer().getPluginManager().registerEvents(this, this.plugin);
        registerPacketListener();
        this.tickTask = this.plugin.getServer().getScheduler()
                .runTaskTimer(this.plugin, this::tick, 1L, 1L);
    }

    @Override
    public void shutdown(AlleyContext context) {
        if (this.tickTask != null) {
            this.tickTask.cancel();
            this.tickTask = null;
        }
        if (this.protocolManager != null && this.packetListener != null) {
            this.protocolManager.removePacketListener(this.packetListener);
            this.packetListener = null;
        }
        for (Player player : Bukkit.getOnlinePlayers()) {
            disable(player);
        }
        this.sessions.clear();
    }

    @Override
    public boolean toggle(Player player) {
        Session session = this.sessions.get(player.getUniqueId());
        if (session != null && session.enabled) {
            disable(player);
            return false;
        }

        session = new Session(player.getUniqueId());
        session.enabled = true;
        this.sessions.put(player.getUniqueId(), session);
        session.worldId = player.getWorld().getUID();
        session.canLegacyBlock = canStartLegacyBlocking(player);
        updateProbe(player, session);
        return true;
    }

    @Override
    public void disable(Player player) {
        Session session = this.sessions.remove(player.getUniqueId());
        if (session == null) return;

        session.enabled = false;
        session.holding = false;
        session.rightClickHeld = false;
        restoreProbe(player, session.probe);
        session.probe = null;
        session.activeProbe = null;
        session.worldId = null;
        session.probeTransitionDeadlineNanos = 0L;
        stopLegacyBlocking(player.getUniqueId());
        session.recentProbes.clear();
        session.gatewayStateRefreshes = 0;
    }

    @Override
    public boolean isEnabled(Player player) {
        Session session = this.sessions.get(player.getUniqueId());
        return session != null && session.enabled;
    }

    /**
     * (Re-)loads the autoclick.yml settings. Called on startup and whenever
     * /alley reload re-reads all configuration files, so edits to autoclick.yml
     * take effect without a restart.
     */
    @Override
    public void reloadConfiguration() {
        ConfigService configService = AlleyPlugin.getInstance().getService(ConfigService.class);
        FileConfiguration config = configService.getAutoClickConfig();
        File file = configService.getConfigFile("autoclick.yml");
        double cps = Math.max(1.0D, Math.min(20.0D, config.getDouble("attacks-per-second", 10.0D)));
        this.attackPeriodTicks = Math.max(1, (int) Math.round(20.0D / cps));
        this.maxAttackRange = Math.max(1.0D, config.getDouble("max-attack-range", 3.0D));
        this.raySize = Math.max(0.0D, config.getDouble("ray-size", 0.05D));
        this.probeMinDistance = Math.max(0.75D, config.getDouble("probe.min-distance", 1.15D));
        this.probeMaxDistance = Math.max(this.probeMinDistance, config.getDouble("probe.max-distance", 1.85D));
        int transitionTimeoutTicks = Math.max(1,
                config.getInt("probe.transition-timeout-ticks", 10));
        this.probeTransitionTimeoutNanos = transitionTimeoutTicks * 50_000_000L;

        String materialName = config.getString("probe.material", Material.END_GATEWAY.name());
        Material configured = Material.matchMaterial(materialName == null ? "" : materialName);
        if (configured != Material.END_GATEWAY) {
            this.plugin.getLogger().warning(
                    "AutoClick probe material was forced to END_GATEWAY because other blocks may collide or cannot be targeted.");
            config.set("probe.material", Material.END_GATEWAY.name());
            configService.saveConfig(file, config);
        }
        this.probeMaterial = Material.END_GATEWAY;
    }

    private void registerPacketListener() {
        this.packetListener = new PacketAdapter(
                this.plugin,
                ListenerPriority.LOWEST,
                PacketType.Play.Client.BLOCK_DIG,
                PacketType.Play.Client.USE_ITEM_ON,
                PacketType.Play.Client.USE_ITEM
        ) {
            @Override
            public void onPacketReceiving(PacketEvent event) {
                if (event.getPacketType() == PacketType.Play.Client.USE_ITEM_ON) {
                    handleUseItemOn(event);
                } else if (event.getPacketType() == PacketType.Play.Client.USE_ITEM) {
                    handleUseItem(event);
                } else {
                    handleBlockDig(event);
                }
            }
        };
        this.protocolManager.addPacketListener(this.packetListener);
    }

    private void handleBlockDig(PacketEvent event) {
        Player player = event.getPlayer();
        Session session = this.sessions.get(player.getUniqueId());
        if (!isActiveSession(player, session)) return;

        EnumWrappers.PlayerDigType action = event.getPacket().getPlayerDigTypes().readSafely(0);
        if (action == EnumWrappers.PlayerDigType.RELEASE_USE_ITEM) {
            session.rightClickHeld = false;
            Bukkit.getScheduler().runTask(this.plugin, () -> {
                Session current = this.sessions.get(player.getUniqueId());
                if (current == session && isActiveSession(player, current)) {
                    stopLegacyBlocking(player.getUniqueId());
                }
            });
            return;
        }
        if (action != EnumWrappers.PlayerDigType.START_DESTROY_BLOCK
                && action != EnumWrappers.PlayerDigType.ABORT_DESTROY_BLOCK
                && action != EnumWrappers.PlayerDigType.STOP_DESTROY_BLOCK) {
            return;
        }

        BlockPosition position = event.getPacket().getBlockPositionModifier().readSafely(0);
        if (position == null) return;

        UUID worldId = session.worldId;
        if (worldId == null) return;
        Probe probe = new Probe(worldId, position.getX(), position.getY(), position.getZ());
        if (!session.matchesProbe(probe)) return;

        // Never let the client-only probe reach vanilla block-breaking logic.
        event.setCancelled(true);
        if (action == EnumWrappers.PlayerDigType.START_DESTROY_BLOCK) {
            session.holding = true;
            session.activeProbe = probe;
            session.probeTransitionDeadlineNanos = 0L;
        } else if (probe.equals(session.probe)
                && (session.activeProbe == null || session.activeProbe.equals(probe))) {
            // ABORT/STOP on the current continuous probe is a real release.
            // An ABORT for a recently replaced probe is ignored; START for
            // the replacement probe will reconnect the held state.
            session.holding = false;
            session.activeProbe = null;
            session.probeTransitionDeadlineNanos = 0L;
        }
    }

    private void handleUseItemOn(PacketEvent event) {
        Player player = event.getPlayer();
        Session session = this.sessions.get(player.getUniqueId());
        if (!isActiveSession(player, session) || session.worldId == null) return;

        EnumWrappers.Hand hand = event.getPacket().getHands().readSafely(0);
        if (hand == EnumWrappers.Hand.OFF_HAND) return;

        BlockPosition position = event.getPacket().getBlockPositionModifier().readSafely(0);
        if (position == null) return;
        Probe probe = new Probe(session.worldId, position.getX(), position.getY(), position.getZ());
        if (!session.canLegacyBlock || !session.matchesProbe(probe)) return;

        // The client aimed at the fake gateway and therefore sent USE_ITEM_ON
        // instead of the normal air-use path. Do not touch Bukkit state on the
        // Netty thread; complete legacy sword blocking on the main thread.
        event.setCancelled(true);
        session.rightClickHeld = true;
        scheduleLegacyBlockingStart(player, session);
    }

    private void handleUseItem(PacketEvent event) {
        Player player = event.getPlayer();
        Session session = this.sessions.get(player.getUniqueId());
        if (!isActiveSession(player, session) || !session.canLegacyBlock) return;

        EnumWrappers.Hand hand = event.getPacket().getHands().readSafely(0);
        if (hand == EnumWrappers.Hand.OFF_HAND) return;

        // Depending on whether the fake outline or air won the client's pick
        // result, sword right-click is encoded as USE_ITEM_ON or USE_ITEM.
        // Track both beginnings; RELEASE_USE_ITEM is the matching key-up signal.
        session.rightClickHeld = true;
        scheduleLegacyBlockingStart(player, session);
    }

    private void scheduleLegacyBlockingStart(Player player, Session session) {
        Bukkit.getScheduler().runTask(this.plugin, () -> {
            Session current = this.sessions.get(player.getUniqueId());
            if (isActiveSession(player, current) && current == session && current.rightClickHeld) {
                startLegacyBlocking(player.getUniqueId());
            }
        });
    }

    private void tick() {
        long now = System.nanoTime();
        for (Session session : this.sessions.values()) {
            Player player = Bukkit.getPlayer(session.playerId);
            if (player == null || !player.isOnline() || !session.enabled) {
                if (player != null) disable(player);
                else this.sessions.remove(session.playerId);
                continue;
            }

            if (player.getGameMode() == GameMode.SPECTATOR || player.getGameMode() == GameMode.CREATIVE) {
                session.holding = false;
            }

            session.canLegacyBlock = canStartLegacyBlocking(player);
            if (!session.canLegacyBlock && session.rightClickHeld) {
                session.rightClickHeld = false;
                stopLegacyBlocking(player.getUniqueId());
            } else if (session.canLegacyBlock && session.rightClickHeld) {
                ensureLegacyBlocking(player);
            }
            updateProbe(player, session);
            if (session.gatewayStateRefreshes > 0 && session.probe != null) {
                sendQuietGatewayState(player, session.probe);
                session.gatewayStateRefreshes--;
            }

            session.recentProbes.entrySet().removeIf(entry -> entry.getValue() < now);
            if (session.holding
                    && session.probeTransitionDeadlineNanos > 0L
                    && now > session.probeTransitionDeadlineNanos) {
                session.holding = false;
                session.activeProbe = null;
                session.probeTransitionDeadlineNanos = 0L;
            }
            if (session.holding && ++session.attackTick >= this.attackPeriodTicks) {
                session.attackTick = 0;
                performAttack(player, session);
            }
        }
    }

    private void updateProbe(Player player, Session session) {
        Location eye = player.getEyeLocation();
        Probe next = findProbe(player, eye, eye.getDirection().normalize());
        session.worldId = player.getWorld().getUID();
        Probe previous = session.probe;
        if (java.util.Objects.equals(previous, next)) return;

        long now = System.nanoTime();
        if (previous != null) {
            session.recentProbes.put(previous, now + RECENT_PROBE_NANOS);
            restoreProbe(player, previous);
        }

        session.probe = next;
        if (next == null) {
            session.holding = false;
            session.activeProbe = null;
            session.probeTransitionDeadlineNanos = 0L;
            return;
        }

        if (session.holding) {
            // Preserve AC while the client switches from the old target block
            // to the new one. If no START arrives, the player either released
            // left-click during the transition or the new probe was not hit.
            session.probeTransitionDeadlineNanos = now + this.probeTransitionTimeoutNanos;
        }
        sendProbe(player, next);
    }

    private Probe findProbe(Player player, Location eye, Vector direction) {
        World world = player.getWorld();
        BoundingBox playerBox = player.getBoundingBox().expand(0.05D);
        for (double distance = this.probeMinDistance; distance <= this.probeMaxDistance; distance += 0.15D) {
            Location sample = eye.clone().add(direction.clone().multiply(distance));
            Block block = world.getBlockAt(sample);
            if (!block.getType().isAir()) continue;

            BoundingBox blockBox = new BoundingBox(
                    block.getX(), block.getY(), block.getZ(),
                    block.getX() + 1.0D, block.getY() + 1.0D, block.getZ() + 1.0D);
            if (playerBox.overlaps(blockBox)) continue;
            return new Probe(world.getUID(), block.getX(), block.getY(), block.getZ());
        }
        return null;
    }

    private void sendProbe(Player player, Probe probe) {
        if (!probe.worldId.equals(player.getWorld().getUID())) return;
        BlockData fakeData = this.probeMaterial.createBlockData();
        player.sendBlockChange(new Location(player.getWorld(), probe.x, probe.y, probe.z), fakeData);

        // A freshly-created client-side End Gateway block entity starts with
        // Age=0, which makes vanilla render its purple/magenta beam. This is
        // a per-player packet, so only the AC user receives the fake entity
        // state; real gateways and non-AC players remain untouched.
        sendQuietGatewayState(player, probe);
        // A block-state update and its block-entity data can be processed in
        // separate client ticks. Repeat the per-player state briefly so a late
        // block-entity creation can never retain the default Age=0 beam.
        Session session = this.sessions.get(player.getUniqueId());
        if (session != null && probe.equals(session.probe)) {
            session.gatewayStateRefreshes = 4;
        }
    }

    private void sendQuietGatewayState(Player player, Probe probe) {
        if (this.protocolManager == null) return;

        try {
            PacketContainer packet = this.protocolManager.createPacket(PacketType.Play.Server.TILE_ENTITY_DATA);
            packet.getBlockPositionModifier().write(0,
                    new BlockPosition(probe.x, probe.y, probe.z));
            packet.getBlockEntityTypeModifier().write(0,
                    WrappedRegistrable.blockEntityType("minecraft:end_gateway"));
            NbtCompound tag = NbtFactory.ofCompound("");
            tag.put("Age", Long.MAX_VALUE / 4L);
            tag.put("Cooldown", 0);
            packet.getNbtModifier().write(0, tag);
            this.protocolManager.sendServerPacket(player, packet, false);
        } catch (RuntimeException exception) {
            // The fake block is still usable if an older ProtocolLib build
            // cannot encode this optional block-entity packet.
            this.plugin.getLogger().fine("Unable to suppress fake End Gateway beam: "
                    + exception.getMessage());
        }
    }

    private boolean isActiveSession(Player player, Session session) {
        return player != null
                && player.isOnline()
                && session != null
                && session.enabled
                && this.sessions.get(player.getUniqueId()) == session;
    }

    private void restoreProbe(Player player, Probe probe) {
        if (probe == null || !probe.worldId.equals(player.getWorld().getUID())) return;
        Block block = player.getWorld().getBlockAt(probe.x, probe.y, probe.z);
        player.sendBlockChange(block.getLocation(), block.getBlockData());
    }

    private LegacyCombatService legacyCombatService() {
        if (this.matchService instanceof MatchServiceImpl implementation) {
            return implementation.getLegacyCombatService();
        }
        return null;
    }

    private boolean canStartLegacyBlocking(Player player) {
        LegacyCombatService legacy = legacyCombatService();
        if (legacy == null || !legacy.hasSwordBlockKB(player.getUniqueId())) return false;
        ItemStack item = player.getInventory().getItemInMainHand();
        return !item.getType().isAir() && item.getType().name().endsWith("_SWORD");
    }

    private void startLegacyBlocking(UUID playerId) {
        Player player = Bukkit.getPlayer(playerId);
        LegacyCombatService legacy = legacyCombatService();
        if (player == null || legacy == null || !player.isOnline()) return;

        ItemStack item = player.getInventory().getItemInMainHand();
        if (item.getType().isAir() || !item.getType().name().endsWith("_SWORD")) return;
        if (!legacy.hasSwordBlockKB(playerId)) return;

        legacy.onHeldSword(player, item);
        try {
            player.startUsingItem(EquipmentSlot.HAND);
        } catch (Throwable ignored) {
            return;
        }
        legacy.setBlocking(playerId, true);
    }

    private void ensureLegacyBlocking(Player player) {
        LegacyCombatService legacy = legacyCombatService();
        if (legacy == null) return;
        UUID playerId = player.getUniqueId();
        if (!legacy.isBlocking(playerId) || !player.hasActiveItem() || !player.isBlocking()) {
            startLegacyBlocking(playerId);
        }
    }

    private void stopLegacyBlocking(UUID playerId) {
        LegacyCombatService legacy = legacyCombatService();
        if (legacy == null) return;
        Player player = Bukkit.getPlayer(playerId);
        if (legacy.isBlocking(playerId) && player != null && player.hasActiveItem()) {
            player.clearActiveItem();
        }
        legacy.setBlocking(playerId, false);
    }

    private void performAttack(Player attacker, Session session) {
        if (attacker.isDead() || !attacker.isValid()) return;
        if (attacker.getGameMode() == GameMode.SPECTATOR) return;

        // Bukkit's swing method emits the normal client-visible arm animation.
        attacker.swingMainHand();
        // CPSManager normally records the client's ARM_ANIMATION packet. A
        // server-generated swing has no inbound packet, so record this click
        // explicitly for the existing scoreboard/placeholder pipeline.
        CPSListener.getCpsManager().recordClick(attacker);
        if (session.rightClickHeld && session.canLegacyBlock) {
            startLegacyBlocking(attacker.getUniqueId());
        }

        Location eye = attacker.getEyeLocation();
        Vector direction = eye.getDirection().normalize();
        double range = this.maxAttackRange;
        if (attacker.getAttribute(org.bukkit.attribute.Attribute.ENTITY_INTERACTION_RANGE) != null) {
            range = Math.min(range, attacker.getAttribute(org.bukkit.attribute.Attribute.ENTITY_INTERACTION_RANGE).getValue());
        }
        if (range <= 0.0D) return;

        Player victim;
        Vector entityHitPosition;
        LegacyCombatService legacy = legacyCombatService();
        if (legacy != null && legacy.hasSwordBlockKB(attacker.getUniqueId())) {
            Player closest = null;
            Vector closestHit = null;
            double closestDistance = range;
            for (Entity entity : attacker.getNearbyEntities(range + 2.0D, range + 2.0D, range + 2.0D)) {
                if (!(entity instanceof Player target)
                        || target.equals(attacker)
                        || target.isDead()
                        || !target.isValid()
                        || target.getGameMode() == GameMode.SPECTATOR) continue;
                RayTraceResult hit = LegacyHitboxes.meleeTarget(target)
                        .rayTrace(eye.toVector(), direction, range);
                if (hit == null) continue;
                double distance = eye.toVector().distance(hit.getHitPosition());
                if (distance <= closestDistance) {
                    closest = target;
                    closestHit = hit.getHitPosition();
                    closestDistance = distance;
                }
            }
            if (closest == null || closestHit == null) return;
            victim = closest;
            entityHitPosition = closestHit;
        } else {
            RayTraceResult entityResult = attacker.getWorld().rayTraceEntities(
                    eye, direction, range, this.raySize,
                    entity -> entity instanceof Player
                            && entity != attacker
                            && !entity.isDead()
                            && entity.isValid());
            if (entityResult == null || !(entityResult.getHitEntity() instanceof Player traced)) return;
            victim = traced;
            entityHitPosition = entityResult.getHitPosition();
        }

        RayTraceResult blockResult = attacker.getWorld().rayTraceBlocks(
                eye, direction, range, FluidCollisionMode.NEVER, true);
        if (blockResult != null && blockResult.getHitPosition().distance(eye.toVector())
                <= entityHitPosition.distance(eye.toVector()) + 0.01D) {
            return;
        }

        // Player#attack is Paper's server-side equivalent of the normal
        // ServerboundInteractPacket ATTACK path. It fires ordinary Bukkit
        // damage events and therefore remains visible to Alley combat/KB code.
        PlayerKnockbackData data = this.knockbackManager.getPlayerData(attacker);
        Vector velocityBeforeAttack = attacker.getVelocity().clone();
        boolean legacyKnockback = this.knockbackManager.isLegacyKnockback(attacker);
        boolean hasKnockbackEnchant = attacker.getInventory().getItemInMainHand()
                .getEnchantmentLevel(Enchantment.KNOCKBACK) > 0;
        boolean shouldApplyAttackerSlowdown = legacyKnockback
                ? this.knockbackManager.hasLegacySprintKnockback(attacker) || hasKnockbackEnchant
                : attacker.isSprinting() || hasKnockbackEnchant;
        KnockbackProfile knockbackProfile = this.knockbackManager.getAppliedProfile(attacker);
        double horizontalSlowdown = legacyKnockback
                && knockbackProfile != null
                ? knockbackProfile.getLegacyAttackerHorizontalSlowdown() : 0.6D;
        data.setServerSideHit(true);
        try {
            attacker.attack(victim);
        } finally {
            data.setServerSideHit(false);
        }

        // A synthetic attack may clear the active-use state. The physical
        // right button is still held, so restore the sword block immediately.
        if (session.rightClickHeld && session.canLegacyBlock) {
            startLegacyBlocking(attacker.getUniqueId());
        }

        // Keep the server-side 1.8 attacker slowdown exact without sending a
        // second velocity packet that could shrink an in-flight knockback.
        if (shouldApplyAttackerSlowdown) {
            Vector current = attacker.getVelocity();
            if (legacyKnockback) {
                this.knockbackManager.applyLegacyAttackerHorizontalMotion(
                        attacker, velocityBeforeAttack, current.getY(), horizontalSlowdown);
            } else {
                attacker.setVelocity(new Vector(
                        velocityBeforeAttack.getX() * horizontalSlowdown,
                        current.getY(),
                        velocityBeforeAttack.getZ() * horizontalSlowdown));
            }
        }
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        disable(event.getPlayer());
    }

    @EventHandler
    public void onChangedWorld(PlayerChangedWorldEvent event) {
        Session session = this.sessions.get(event.getPlayer().getUniqueId());
        if (session != null) {
            session.holding = false;
            session.rightClickHeld = false;
            session.probe = null;
            session.activeProbe = null;
            session.worldId = null;
            session.probeTransitionDeadlineNanos = 0L;
            session.recentProbes.clear();
            session.gatewayStateRefreshes = 0;
            stopLegacyBlocking(event.getPlayer().getUniqueId());
        }
    }

    @EventHandler
    public void onTeleport(PlayerTeleportEvent event) {
        Session session = this.sessions.get(event.getPlayer().getUniqueId());
        if (session != null) {
            session.holding = false;
            session.rightClickHeld = false;
            restoreProbe(event.getPlayer(), session.probe);
            session.probe = null;
            session.activeProbe = null;
            session.probeTransitionDeadlineNanos = 0L;
            session.recentProbes.clear();
            session.gatewayStateRefreshes = 0;
            stopLegacyBlocking(event.getPlayer().getUniqueId());
        }
    }

    @EventHandler
    public void onGameModeChange(PlayerGameModeChangeEvent event) {
        Session session = this.sessions.get(event.getPlayer().getUniqueId());
        if (session != null && (event.getNewGameMode() == GameMode.SPECTATOR
                || event.getNewGameMode() == GameMode.CREATIVE)) {
            session.holding = false;
            session.rightClickHeld = false;
            session.probeTransitionDeadlineNanos = 0L;
            stopLegacyBlocking(event.getPlayer().getUniqueId());
        }
    }

    @EventHandler
    public void onStopUsingItem(PlayerStopUsingItemEvent event) {
        Session session = this.sessions.get(event.getPlayer().getUniqueId());
        if (session != null && !session.rightClickHeld) {
            stopLegacyBlocking(event.getPlayer().getUniqueId());
        }
    }

    private static final class Session {
        private final UUID playerId;
        private volatile boolean enabled;
        private volatile boolean holding;
        private volatile boolean rightClickHeld;
        private volatile Probe probe;
        private volatile Probe activeProbe;
        private volatile UUID worldId;
        private volatile long probeTransitionDeadlineNanos;
        private volatile boolean canLegacyBlock;
        private int attackTick;
        private int gatewayStateRefreshes;
        private final Map<Probe, Long> recentProbes = new ConcurrentHashMap<>();

        private Session(UUID playerId) {
            this.playerId = playerId;
        }

        private boolean matchesProbe(Probe candidate) {
            if (candidate.equals(this.probe) || candidate.equals(this.activeProbe)) return true;
            Long expiresAt = this.recentProbes.get(candidate);
            return expiresAt != null && expiresAt >= System.nanoTime();
        }
    }

    private record Probe(UUID worldId, int x, int y, int z) { }
}
