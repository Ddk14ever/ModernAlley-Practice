package dev.revere.alley.feature.item.listener;

import dev.revere.alley.AlleyPlugin;
import dev.revere.alley.core.config.ConfigService;
import dev.revere.alley.core.profile.Profile;
import dev.revere.alley.core.profile.ProfileService;
import dev.revere.alley.feature.item.CustomItemType;
import dev.revere.alley.feature.item.ItemService;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.entity.Entity;
import org.bukkit.entity.FishHook;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.ProjectileLaunchEvent;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.player.PlayerFishEvent;
import org.bukkit.event.player.PlayerDropItemEvent;
import org.bukkit.event.player.PlayerItemHeldEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerSwapHandItemsEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.metadata.FixedMetadataValue;
import org.bukkit.scheduler.BukkitTask;
import org.bukkit.util.Vector;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * A fishing-rod based grappling hook. The hook is still a normal FishHook, so
 * clients render and animate it naturally; only rods marked by ItemService are
 * intercepted here.
 */
public final class GrapplingHookListener implements Listener {
    public static final String HOOK_METADATA = "alley_grappling_hook";

    private final Map<UUID, HookState> activeHooks = new ConcurrentHashMap<>();
    private final BukkitTask cleanupTask;

    public GrapplingHookListener() {
        this.cleanupTask = Bukkit.getScheduler().runTaskTimer(
                AlleyPlugin.getInstance(), this::cleanupExpiredHooks, 20L, 20L);
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onLaunch(ProjectileLaunchEvent event) {
        if (!(event.getEntity() instanceof FishHook hook)) return;
        if (!(hook.getShooter() instanceof Player player) || !isPlayable(player)) return;

        ItemService items = itemService();
        if (items.getCustomItemType(player.getInventory().getItemInMainHand())
                != CustomItemType.GRAPPLING_HOOK) return;

        HookState previous = activeHooks.remove(player.getUniqueId());
        if (previous != null) remove(previous.hook());

        hook.setMetadata(HOOK_METADATA,
                new FixedMetadataValue(AlleyPlugin.getInstance(), true));
        activeHooks.put(player.getUniqueId(), new HookState(hook, System.currentTimeMillis()));
    }

    /**
     * Vanilla emits FISHING for the initial cast and one of the reeling states
     * when the player clicks again. Only the latter states pull the player.
     */
    @EventHandler(priority = EventPriority.LOWEST)
    public void onFish(PlayerFishEvent event) {
        FishHook hook = event.getHook();
        if (!isOurHook(hook, event.getPlayer())) return;

        switch (event.getState()) {
            case FISHING, BITE, LURED -> {
                return;
            }
            case REEL_IN, CAUGHT_FISH, CAUGHT_ENTITY, IN_GROUND, FAILED_ATTEMPT -> {
                event.setCancelled(true);
                pullPlayer(event.getPlayer(), hook);
                clearHook(event.getPlayer());
            }
            default -> {
                // Keep future Paper states harmless until they are explicitly
                // identified as a reel action.
            }
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onHeld(PlayerItemHeldEvent event) {
        clearHook(event.getPlayer());
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onDrop(PlayerDropItemEvent event) {
        clearHook(event.getPlayer());
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onSwapHands(PlayerSwapHandItemsEvent event) {
        clearHook(event.getPlayer());
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onQuit(PlayerQuitEvent event) {
        clearHook(event.getPlayer());
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onDeath(PlayerDeathEvent event) {
        clearHook(event.getEntity());
    }

    private void pullPlayer(Player player, FishHook hook) {
        if (!hook.isValid() || hook.isDead() || !player.isOnline()) return;

        Location origin = player.getEyeLocation();
        Vector toHook = hook.getLocation().toVector().subtract(origin.toVector());
        double distance = toHook.length();
        double minimum = config().getDouble("grappling-hook.min-distance", 1.0D);
        if (distance < Math.max(0.0D, minimum)) return;

        Vector direction = toHook.multiply(1.0D / distance);
        double baseSpeed = Math.max(0.0D, config().getDouble("grappling-hook.base-speed", 0.35D));
        double distanceSpeed = Math.max(0.0D,
                config().getDouble("grappling-hook.distance-speed", 0.08D));
        double maxSpeed = Math.max(0.0D,
                config().getDouble("grappling-hook.max-speed", 3.5D));
        double speed = Math.min(maxSpeed, baseSpeed + distance * distanceSpeed);
        player.setVelocity(direction.multiply(speed));
    }

    private boolean isOurHook(FishHook hook, Player player) {
        if (hook == null || !hook.hasMetadata(HOOK_METADATA)) return false;
        if (!(hook.getShooter() instanceof Player shooter)
                || !shooter.getUniqueId().equals(player.getUniqueId())) return false;
        HookState state = activeHooks.get(player.getUniqueId());
        return state != null && state.hook().getUniqueId().equals(hook.getUniqueId());
    }

    private void clearHook(Player player) {
        HookState state = activeHooks.remove(player.getUniqueId());
        if (state != null) remove(state.hook());
    }

    private void cleanupExpiredHooks() {
        long maxAge = Math.max(1L, config().getLong("grappling-hook.max-lifetime-ticks", 200L)) * 50L;
        ItemService items = itemService();
        for (Map.Entry<UUID, HookState> entry : activeHooks.entrySet()) {
            Player player = Bukkit.getPlayer(entry.getKey());
            HookState state = entry.getValue();
            boolean holding = player != null
                    && items.getCustomItemType(player.getInventory().getItemInMainHand())
                    == CustomItemType.GRAPPLING_HOOK;
            if (player == null || !player.isOnline() || !holding
                    || !state.hook().isValid() || state.hook().isDead()
                    || System.currentTimeMillis() - state.createdAt() > maxAge) {
                if (activeHooks.remove(entry.getKey(), state)) remove(state.hook());
            }
        }
    }

    private void remove(Entity entity) {
        if (entity != null && entity.isValid()) entity.remove();
    }

    private ItemService itemService() {
        return AlleyPlugin.getInstance().getService(ItemService.class);
    }

    private org.bukkit.configuration.file.FileConfiguration config() {
        return AlleyPlugin.getInstance().getService(ConfigService.class).getCustomItemsConfig();
    }

    private boolean isPlayable(Player player) {
        Profile profile = AlleyPlugin.getInstance().getService(ProfileService.class)
                .getProfile(player.getUniqueId());
        return profile != null && (profile.getMatch() != null || profile.getFfaMatch() != null);
    }

    private record HookState(FishHook hook, long createdAt) {
    }
}
