package dev.revere.alley.feature.knockback.minemen;

import com.destroystokyo.paper.event.player.PlayerPostRespawnEvent;
import com.destroystokyo.paper.event.server.ServerTickEndEvent;
import dev.revere.alley.AlleyPlugin;
import dev.revere.alley.common.version.ClientVersionService;
import dev.revere.alley.feature.knockback.KnockbackBranch;
import dev.revere.alley.feature.knockback.KnockbackManager;
import dev.revere.alley.feature.knockback.KnockbackProfile;
import org.bukkit.NamespacedKey;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.attribute.AttributeModifier;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerChangedWorldEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerTeleportEvent;
import org.bukkit.util.Vector;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/** Scoped to the descent following a delivered MMC knockback, never ordinary movement. */
public final class MinemenClubGravity implements Listener {
    private static final NamespacedKey KEY = new NamespacedKey("alley", "minemenclub_gravity");
    private static final int PROTOCOL_1_20_5 = 766;

    private final KnockbackManager manager;
    private final MinemenClubGravityCompensation compensation = new MinemenClubGravityCompensation();
    private final MinemenClubGravityBridge bridge = new MinemenClubGravityBridge();
    // Keep the actual Player too: native Bots are not necessarily in Bukkit's online list.
    private final Map<UUID, Player> players = new HashMap<>();
    private final Map<UUID, Flight> flights = new HashMap<>();
    private final Set<UUID> warned = new HashSet<>();

    public MinemenClubGravity(KnockbackManager manager) {
        this.manager = manager;
    }

    public void enable() {
        compensation.enable();
    }

    public void disable() {
        compensation.disable();
        clear();
    }

    public String compensationStatus(Player player) {
        Flight flight = flights.get(player.getUniqueId());
        if (flight == null || flight.player != player) return "idle (no knockback descent)";
        return (flight.active ? "active knockback descent" : "waiting for knockback descent")
                + (flight.legacy ? "; " + compensation.status(player) : "");
    }

    public void apply(Player player, KnockbackProfile profile) {
        UUID uuid = player.getUniqueId();
        Player previous = players.get(uuid);
        if (previous != null && previous != player) clear(uuid);
        AttributeInstance attribute = player.getAttribute(Attribute.GRAVITY);
        if (profile == null || profile.getBranch() != KnockbackBranch.MINEMENCLUB) {
            clear(uuid);
            if (attribute != null) remove(attribute);
            return;
        }
        if (attribute == null) {
            clear(uuid);
            return;
        }

        // Assigning a kit or reloading a profile must never change normal jumps.
        stopFlight(player);
        players.put(uuid, player);
        warned.remove(uuid);
    }

    public void beginKnockback(Player player, Vector before, Vector delivered) {
        if (!MinemenClubGravityFlight.hasImpulse(before, delivered)) return;
        KnockbackProfile profile = manager.getAppliedProfile(player);
        if (profile == null || profile.getBranch() != KnockbackBranch.MINEMENCLUB) return;
        if (players.get(player.getUniqueId()) != player) apply(player, profile);
        stopFlight(player);
        double gravity = profile.getMinemenClub().gravity();
        if (Double.compare(gravity, 0.08D) == 0
                || !MinemenClubGravityCompensation.ordinaryPhysics(player)) return;
        boolean serverControlled = manager.getPlayerData(player).isServerControlled();
        boolean legacy = false;
        long grace = 50_000_000L; // A Bot may not have moved off the ground yet.
        if (!serverControlled) {
            ClientVersionService versions = AlleyPlugin.getInstance().getService(ClientVersionService.class);
            int protocol = versions.getProtocol(player);
            if (protocol <= 0) {
                if (warned.add(player.getUniqueId())) {
                    AlleyPlugin.getInstance().getLogger().warning(
                            "[Knockback] Not applying custom gravity=" + gravity + " to " + player.getName()
                                    + "; client protocol=" + protocol
                                    + " is unknown; gravity compensation needs a confirmed client version.");
                }
                return;
            }
            legacy = protocol < PROTOCOL_1_20_5;
            grace = MinemenClubGravityCompensation.graceNanos(player.getPing());
        }
        flights.put(player.getUniqueId(), new Flight(player, gravity, legacy, serverControlled,
                new MinemenClubGravityFlight(System.nanoTime() + grace)));
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onTickEnd(ServerTickEndEvent event) {
        // Run before the legacy compensation's MONITOR phase, so a landing or
        // reset removes its session before it can emit another velocity packet.
        for (Flight flight : new ArrayList<>(flights.values())) {
            Player player = flight.player;
            if ((!flight.serverControlled && !player.isOnline()) || !player.isValid()
                    || !manager.isMinemenClubKnockback(player)
                    || !MinemenClubGravityCompensation.ordinaryPhysics(player)) {
                stopFlight(player);
                continue;
            }
            try {
                Object token;
                double vertical;
                if (flight.serverControlled) {
                    token = event;
                    vertical = player.getVelocity().getY();
                } else {
                    MinemenClubGravityBridge.Sample sample = bridge.sample(player);
                    token = sample == null ? null : sample.token();
                    vertical = sample == null ? 0.0D : sample.movement().getY();
                }
                MinemenClubGravityFlight.Phase phase = flight.window.update(
                        System.nanoTime(), player.isOnGround(), vertical, token);
                if (phase == MinemenClubGravityFlight.Phase.ENDED) {
                    stopFlight(player);
                } else if (phase == MinemenClubGravityFlight.Phase.DESCENDING && !flight.active) {
                    AttributeInstance attribute = player.getAttribute(Attribute.GRAVITY);
                    if (attribute == null) {
                        stopFlight(player);
                        continue;
                    }
                    replace(attribute, flight.gravity);
                    if (flight.legacy) compensation.apply(player);
                    flight.active = true;
                }
            } catch (Exception | LinkageError exception) {
                stopFlight(player);
                if (warned.add(player.getUniqueId())) {
                    AlleyPlugin.getInstance().getLogger().warning(
                            "[MinemenClub] Cannot track knockback descent for " + player.getName()
                                    + ": " + exception);
                }
            }
        }
    }

    private void stopFlight(Player player) {
        UUID uuid = player.getUniqueId();
        flights.remove(uuid);
        compensation.remove(uuid);
        AttributeInstance attribute = player.getAttribute(Attribute.GRAVITY);
        if (attribute != null) remove(attribute);
    }

    /** Refreshes synthetic players as well as connected players after a profile reload. */
    public void refreshAll() {
        for (Player player : new ArrayList<>(players.values())) {
            apply(player, manager.getAppliedProfile(player));
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onRespawn(PlayerPostRespawnEvent event) {
        Player player = event.getPlayer();
        apply(player, manager.getAppliedProfile(player));
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onTeleport(PlayerTeleportEvent event) {
        stopFlight(event.getPlayer());
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onWorldChange(PlayerChangedWorldEvent event) {
        stopFlight(event.getPlayer());
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onQuit(PlayerQuitEvent event) {
        clear(event.getPlayer().getUniqueId());
    }

    public void clear(UUID uuid) {
        flights.remove(uuid);
        compensation.remove(uuid);
        Player player = players.remove(uuid);
        warned.remove(uuid);
        if (player == null) return;
        AttributeInstance attribute = player.getAttribute(Attribute.GRAVITY);
        if (attribute != null) remove(attribute);
    }

    public void clear() {
        for (UUID uuid : new ArrayList<>(players.keySet())) clear(uuid);
        warned.clear();
    }

    // An owned transient modifier leaves the pre-existing base and other plugins'
    // modifiers untouched. Removing it also avoids saving match gravity to player data.
    static void replace(AttributeInstance attribute, double gravity) {
        remove(attribute);
        double delta = gravity - attribute.getBaseValue();
        if (delta != 0.0D) {
            attribute.addTransientModifier(new AttributeModifier(
                    KEY, delta, AttributeModifier.Operation.ADD_NUMBER));
        }
    }

    static void remove(AttributeInstance attribute) {
        AttributeModifier modifier = attribute.getModifier(KEY);
        if (modifier != null) attribute.removeModifier(modifier);
    }

    private static final class Flight {
        final Player player;
        final double gravity;
        final boolean legacy;
        final boolean serverControlled;
        final MinemenClubGravityFlight window;
        boolean active;

        Flight(Player player, double gravity, boolean legacy, boolean serverControlled,
               MinemenClubGravityFlight window) {
            this.player = player;
            this.gravity = gravity;
            this.legacy = legacy;
            this.serverControlled = serverControlled;
            this.window = window;
        }
    }
}
