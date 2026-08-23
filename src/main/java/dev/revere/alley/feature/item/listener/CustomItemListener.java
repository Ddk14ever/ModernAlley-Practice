package dev.revere.alley.feature.item.listener;

import dev.revere.alley.AlleyPlugin;
import dev.revere.alley.common.text.CC;
import dev.revere.alley.core.config.ConfigService;
import dev.revere.alley.core.profile.Profile;
import dev.revere.alley.core.profile.ProfileService;
import dev.revere.alley.feature.item.CustomItemType;
import dev.revere.alley.feature.item.ItemService;
import com.comphenix.protocol.PacketType;
import com.comphenix.protocol.ProtocolLibrary;
import com.comphenix.protocol.ProtocolManager;
import com.comphenix.protocol.events.ListenerPriority;
import com.comphenix.protocol.events.PacketAdapter;
import com.comphenix.protocol.events.PacketEvent;
import com.comphenix.protocol.wrappers.EnumWrappers;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.attribute.AttributeModifier;
import org.bukkit.Material;
import org.bukkit.Particle;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Snowball;
import org.bukkit.entity.Player;
import org.bukkit.entity.LivingEntity;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.entity.ProjectileHitEvent;
import org.bukkit.event.player.PlayerDropItemEvent;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.entity.EntityKnockbackByEntityEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerItemHeldEvent;
import org.bukkit.event.player.PlayerAnimationEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerSwapHandItemsEvent;
import io.papermc.paper.event.player.PlayerStopUsingItemEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.Damageable;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.NamespacedKey;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.Sound;
import org.bukkit.scheduler.BukkitTask;
import org.bukkit.util.RayTraceResult;
import org.bukkit.util.Vector;

import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/** First-pass AK-47/AWP implementation. */
public final class CustomItemListener implements Listener {
    private final Map<UUID, BukkitTask> automaticFire = new ConcurrentHashMap<>();
    private final Map<UUID, Long> lastAkUseNanos = new ConcurrentHashMap<>();
    /**
     * A left click can arrive both as an interact event and as an arm-animation
     * packet.  Keep a very small guard so an AWP click can be handled through
     * either path without consuming two rounds.
     */
    private final Map<UUID, Long> lastAwpShotNanos = new ConcurrentHashMap<>();
    /** Item draw/equip lock, and the AWP's post-shot lock, in epoch millis. */
    private final Map<UUID, Long> weaponReadyAt = new ConcurrentHashMap<>();
    private final Map<UUID, Long> awpCooldownUntil = new ConcurrentHashMap<>();
    private final Set<UUID> weaponEquipped = ConcurrentHashMap.newKeySet();
    private final Set<UUID> experienceHudOverride = ConcurrentHashMap.newKeySet();
    private final Set<UUID> scoped = ConcurrentHashMap.newKeySet();
    private final Map<UUID, ReloadState> reloads = new ConcurrentHashMap<>();
    /** Slow applied while the AWP is held (stronger while scoped). */
    private final Set<UUID> awpSlowed = ConcurrentHashMap.newKeySet();
    private final Map<UUID, PotionEffect> displacedSlowness = new ConcurrentHashMap<>();
    /** Helmet worn before the scope pumpkin, so it can be restored on unscope. */
    private final Map<UUID, ItemStack> scopedHelmet = new ConcurrentHashMap<>();
    /** Scope-zoom state: transient speed modifier, and the pre-scope walk speed / speed base. */
    private final Map<UUID, AttributeModifier> scopedSpeedModifier = new ConcurrentHashMap<>();
    private final Map<UUID, Float> scopedWalkSpeedBefore = new ConcurrentHashMap<>();
    private final Map<UUID, Double> scopedSpeedBaseBefore = new ConcurrentHashMap<>();
    private final Set<UUID> weaponNoKnockback = ConcurrentHashMap.newKeySet();

    public CustomItemListener() {
        // Keep the ammo HUD and held-item effects independent of the firing task.
        // This also makes the HUD work while the weapon is idle.
        Bukkit.getScheduler().runTaskTimer(AlleyPlugin.getInstance(), () -> {
            ItemService items = AlleyPlugin.getInstance().getService(ItemService.class);
            for (Player player : Bukkit.getOnlinePlayers()) {
                updateAwpSlowness(player, items);
                updateAmmoActionBar(player, items);
                updateWeaponCooldownHud(player, items);
            }
        }, 1L, 2L);
        registerUsePacketTracker();
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onInteract(PlayerInteractEvent event) {
        Player player = event.getPlayer();
        if (!isPlayable(player)) return;
        ItemService items = AlleyPlugin.getInstance().getService(ItemService.class);
        CustomItemType type = items.getCustomItemType(event.getItem());
        if (type == null) return;

        Action action = event.getAction();
        if (type == CustomItemType.AK47
                && (action == Action.RIGHT_CLICK_AIR || action == Action.RIGHT_CLICK_BLOCK)) {
            event.setCancelled(true);
            event.setUseItemInHand(org.bukkit.event.Event.Result.DENY);
            startAutomaticFire(player);
            return;
        }

        if (type == CustomItemType.AWP
                && (action == Action.RIGHT_CLICK_AIR || action == Action.RIGHT_CLICK_BLOCK)) {
            // Scope toggles: right-click again cancels it. Scoping equips a
            // pumpkin (telescope-lens overlay from the pack), applies the scoped
            // aim-slow, and zooms the FOV to the configured scope-fov through a
            // negative walkSpeed ability plus a movement-speed modifier. No
            // item-use state is entered, so left-click firing keeps working.
            event.setCancelled(true);
            event.setUseItemInHand(org.bukkit.event.Event.Result.DENY);
            if (this.scoped.contains(player.getUniqueId())) {
                clearScope(player);
            } else {
                this.scoped.add(player.getUniqueId());
                applyScopeVisual(player);
            }
            return;
        }

        if (type == CustomItemType.AWP
                && (action == Action.LEFT_CLICK_AIR || action == Action.LEFT_CLICK_BLOCK)
                ) {
            event.setCancelled(true);
            fireAwpOnce(player);
            clearScope(player);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onAnimation(PlayerAnimationEvent event) {
        Player player = event.getPlayer();
        if (!isPlayable(player)) return;
        ItemService items = AlleyPlugin.getInstance().getService(ItemService.class);
        if (items.getCustomItemType(player.getInventory().getItemInMainHand()) != CustomItemType.AWP) return;
        fireAwpOnce(player);
        clearScope(player);
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onReload(PlayerSwapHandItemsEvent event) {
        Player player = event.getPlayer();
        ItemService items = AlleyPlugin.getInstance().getService(ItemService.class);
        if (this.reloads.containsKey(player.getUniqueId())) {
            event.setCancelled(true);
            return;
        }
        CustomItemType heldType = items.getCustomItemType(player.getInventory().getItemInMainHand());
        if (heldType != CustomItemType.AK47 && heldType != CustomItemType.AWP) return;
        event.setCancelled(true);
        reload(player);
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onStopUsing(PlayerStopUsingItemEvent event) {
        stopAutomaticFire(event.getPlayer());
        // The AWP scope is a FOV change, not a spyglass use, so a stop-use
        // release does not touch it. Scope is cleared by shooting, switching
        // slots, dropping, quitting, or dying.
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onHeld(PlayerItemHeldEvent event) {
        stopAutomaticFire(event.getPlayer());
        clearScope(event.getPlayer());
        this.lastAwpShotNanos.remove(event.getPlayer().getUniqueId());
        ItemService items = AlleyPlugin.getInstance().getService(ItemService.class);
        CustomItemType newType = items.getCustomItemType(
                event.getPlayer().getInventory().getItem(event.getNewSlot()));
        if (newType == CustomItemType.AK47 || newType == CustomItemType.AWP) {
            this.weaponEquipped.add(event.getPlayer().getUniqueId());
            this.weaponReadyAt.put(event.getPlayer().getUniqueId(),
                    System.currentTimeMillis() + weaponSwitchDelayMillis(newType));
        } else {
            this.weaponEquipped.remove(event.getPlayer().getUniqueId());
            this.weaponReadyAt.remove(event.getPlayer().getUniqueId());
        }
        // Switching away at any point cancels the reload and restores the
        // captured ammo/durability snapshot.
        cancelReload(event.getPlayer(), true);
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onDrop(PlayerDropItemEvent event) {
        stopAutomaticFire(event.getPlayer());
        clearScope(event.getPlayer());
        this.lastAwpShotNanos.remove(event.getPlayer().getUniqueId());
        cancelReload(event.getPlayer(), false);
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onQuit(PlayerQuitEvent event) {
        stopAutomaticFire(event.getPlayer());
        clearScope(event.getPlayer());
        cancelReload(event.getPlayer(), false);
        this.lastAwpShotNanos.remove(event.getPlayer().getUniqueId());
        this.weaponReadyAt.remove(event.getPlayer().getUniqueId());
        this.awpCooldownUntil.remove(event.getPlayer().getUniqueId());
        this.weaponEquipped.remove(event.getPlayer().getUniqueId());
        this.experienceHudOverride.remove(event.getPlayer().getUniqueId());
        this.displacedSlowness.remove(event.getPlayer().getUniqueId());
        this.scopedHelmet.remove(event.getPlayer().getUniqueId());
        this.scopedSpeedModifier.remove(event.getPlayer().getUniqueId());
        this.scopedWalkSpeedBefore.remove(event.getPlayer().getUniqueId());
        this.scopedSpeedBaseBefore.remove(event.getPlayer().getUniqueId());
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onDeath(PlayerDeathEvent event) {
        stopAutomaticFire(event.getEntity());
        clearScope(event.getEntity());
        cancelReload(event.getEntity(), false);
        this.lastAwpShotNanos.remove(event.getEntity().getUniqueId());
        this.weaponReadyAt.remove(event.getEntity().getUniqueId());
        this.awpCooldownUntil.remove(event.getEntity().getUniqueId());
        this.weaponEquipped.remove(event.getEntity().getUniqueId());
        this.experienceHudOverride.remove(event.getEntity().getUniqueId());
        this.scopedSpeedModifier.remove(event.getEntity().getUniqueId());
        this.scopedWalkSpeedBefore.remove(event.getEntity().getUniqueId());
        this.scopedSpeedBaseBefore.remove(event.getEntity().getUniqueId());
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onWeaponKnockback(EntityKnockbackByEntityEvent event) {
        Entity source = event.getSourceEntity();
        if (source instanceof Snowball snowball && snowball.hasMetadata("alley_custom_ak")) {
            event.setCancelled(true);
            return;
        }
        if (weaponNoKnockback.remove(event.getEntity().getUniqueId())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBulletHit(ProjectileHitEvent event) {
        if (!(event.getEntity() instanceof Snowball snowball)) return;
        if (!snowball.hasMetadata("alley_custom_ak")) return;
        if (!(snowball.getShooter() instanceof Player shooter)) return;
        Entity hit = event.getHitEntity();
        if (hit instanceof LivingEntity living && hit != shooter && hit.isValid()) {
            double damage = config().getDouble("ak47.damage", 4.0D);
            applyWeaponDamage(living, Math.max(0.0D, damage), shooter);
        }
        snowball.remove();
    }

    private void startAutomaticFire(Player player) {
        UUID id = player.getUniqueId();
        if (isReloading(player)) return;
        this.lastAkUseNanos.put(id, System.nanoTime());
        if (automaticFire.containsKey(id)) return;
        long period = Math.max(1L, Math.round(20.0D / Math.max(1.0D,
                config().getDouble("ak47.shots-per-second", 10.0D))));
        BukkitTask task = Bukkit.getScheduler().runTaskTimer(AlleyPlugin.getInstance(), () -> {
            ItemService items = AlleyPlugin.getInstance().getService(ItemService.class);
            if (!player.isOnline()
                    || items.getCustomItemType(player.getInventory().getItemInMainHand()) != CustomItemType.AK47) {
                stopAutomaticFire(player);
                return;
            }
            // SHEARS deliberately has no vanilla use state (no bow FOV or
            // slowdown). Generic items send USE_ITEM while RMB is held; a
            // short heartbeat timeout turns a click into a short burst and
            // stops firing immediately after release.
            if (System.nanoTime() - lastAkUseNanos.getOrDefault(id, 0L) > 300_000_000L) {
                stopAutomaticFire(player);
                return;
            }
            fireAk47(player);
        }, 0L, period);
        automaticFire.put(id, task);
    }

    private void stopAutomaticFire(Player player) {
        BukkitTask task = automaticFire.remove(player.getUniqueId());
        if (task != null) task.cancel();
        lastAkUseNanos.remove(player.getUniqueId());
    }

    private void fireAk47(Player player) {
        if (isReloading(player)) return;
        if (!isWeaponReady(player)) return;
        ItemService items = AlleyPlugin.getInstance().getService(ItemService.class);
        ItemStack weapon = player.getInventory().getItemInMainHand();
        if (items.getCustomItemType(weapon) != CustomItemType.AK47) return;
        int ammo = items.getAmmo(weapon);
        if (ammo <= 0) {
            player.sendActionBar(CC.translate("&cAK-47 empty &7- press swap key to reload"));
            stopAutomaticFire(player);
            return;
        }
        items.setAmmo(weapon, ammo - 1);
        Location eye = player.getEyeLocation();
        Vector direction = eye.getDirection().normalize();
        Location muzzle = eye.clone().add(direction.clone().multiply(0.55D));
        spawnMuzzle(player, muzzle);
        player.getWorld().playSound(muzzle, Sound.ENTITY_ARROW_SHOOT, 0.75F, 1.65F);
        Snowball snowball = player.getWorld().spawn(muzzle, Snowball.class);
        snowball.setShooter(player);
        snowball.setGravity(false);
        // Empty projectile item prevents clients that ignore the invisible
        // entity flag from drawing the vanilla snowball model.
        snowball.setItem(new ItemStack(Material.AIR));
        // setInvisible is the server-side flag; hideEntity is an additional
        // packet-side safeguard for clients/resource packs that still render
        // the snowball model for one or two ticks after spawning.
        snowball.setInvisible(true);
        for (Player viewer : Bukkit.getOnlinePlayers()) {
            viewer.hideEntity(AlleyPlugin.getInstance(), snowball);
        }
        snowball.setVelocity(direction.multiply(config().getDouble("ak47.projectile-speed", 3.0D)));
        snowball.setMetadata("alley_custom_ak",
                new org.bukkit.metadata.FixedMetadataValue(AlleyPlugin.getInstance(), true));
        Bukkit.getScheduler().runTaskTimer(AlleyPlugin.getInstance(), task -> {
            if (!snowball.isValid() || snowball.isDead() || !player.isOnline()) {
                task.cancel();
                return;
            }
            snowball.getWorld().spawnParticle(Particle.FLAME, snowball.getLocation(), 1, 0, 0, 0, 0);
        }, 1L, 1L);
        // A missed round should not leave a hidden projectile (and its trail
        // task) alive until the vanilla projectile timeout.
        Bukkit.getScheduler().runTaskLater(AlleyPlugin.getInstance(), () -> {
            if (snowball.isValid() && !snowball.isDead()) snowball.remove();
        }, 40L);
    }

    private void fireAwpOnce(Player player) {
        if (!isWeaponReady(player) || isAwpOnCooldown(player)) return;
        long now = System.nanoTime();
        Long previous = this.lastAwpShotNanos.get(player.getUniqueId());
        if (previous != null && now - previous < 75_000_000L) return;
        this.lastAwpShotNanos.put(player.getUniqueId(), now);
        fireAwp(player);
    }

    private void fireAwp(Player player) {
        if (isReloading(player)) return;
        if (!isWeaponReady(player) || isAwpOnCooldown(player)) return;
        ItemService items = AlleyPlugin.getInstance().getService(ItemService.class);
        ItemStack weapon = player.getInventory().getItemInMainHand();
        if (items.getCustomItemType(weapon) != CustomItemType.AWP) return;
        int ammo = items.getAmmo(weapon);
        if (ammo <= 0) {
            player.sendActionBar(CC.translate("&cAWP empty &7- press swap key to reload"));
            return;
        }
        items.setAmmo(weapon, ammo - 1);
        this.awpCooldownUntil.put(player.getUniqueId(),
                System.currentTimeMillis() + awpShotCooldownMillis());
        Location eye = player.getEyeLocation();
        Vector direction = eye.getDirection().normalize();
        player.getWorld().playSound(eye, Sound.ENTITY_GENERIC_EXPLODE, 0.65F, 1.8F);
        spawnMuzzle(player, eye.clone().add(direction.clone().multiply(0.55D)));
        double range = config().getDouble("awp.range", 100.0D);
        RayTraceResult block = player.getWorld().rayTraceBlocks(eye, direction, range);
        double limit = block == null ? range : eye.toVector().distance(block.getHitPosition());
        RayTraceResult hit = player.getWorld().rayTraceEntities(eye, direction, limit, 0.1D,
                entity -> entity != player && entity instanceof LivingEntity && entity.isValid());
        if (hit != null && hit.getHitEntity() != null) {
            if (hit.getHitEntity() instanceof LivingEntity living) {
                applyWeaponDamage(living, Math.max(0.0D, config().getDouble("awp.damage", 16.0D)), player);
                Location hitLocation = hit.getHitPosition().toLocation(player.getWorld());
                player.getWorld().spawnParticle(Particle.CRIT, hitLocation, 8, 0.15, 0.15, 0.15, 0);
            }
        }
    }

    private void applyWeaponDamage(LivingEntity victim, double damage, Player attacker) {
        // Snowballs and the direct AWP ray are deliberately not knockback
        // weapons. Resetting noDamageTicks makes every AK round count even
        // during the normal hurt-resistance window, while preserving motion
        // protects the target from a side effect of Bukkit.damage().
        Vector velocity = victim.getVelocity().clone();
        victim.setMetadata("alley_weapon_no_kb",
                new org.bukkit.metadata.FixedMetadataValue(AlleyPlugin.getInstance(), true));
        weaponNoKnockback.add(victim.getUniqueId());
        Bukkit.getScheduler().runTaskLater(AlleyPlugin.getInstance(),
                () -> {
                    weaponNoKnockback.remove(victim.getUniqueId());
                }, 2L);
        victim.setNoDamageTicks(0);
        victim.damage(damage, attacker);
        victim.setNoDamageTicks(0);
        victim.setVelocity(velocity);
        Bukkit.getScheduler().runTask(AlleyPlugin.getInstance(), () -> {
            if (victim.isValid() && !victim.isDead()) {
                victim.setVelocity(velocity);
                victim.removeMetadata("alley_weapon_no_kb", AlleyPlugin.getInstance());
            }
        });
    }

    private void reload(Player player) {
        ItemService items = AlleyPlugin.getInstance().getService(ItemService.class);
        ItemStack weapon = player.getInventory().getItemInMainHand();
        CustomItemType type = items.getCustomItemType(weapon);
        if (type != CustomItemType.AK47 && type != CustomItemType.AWP) return;
        int magazine = config().getInt((type == CustomItemType.AK47 ? "ak47" : "awp") + ".magazine-size", 30);
        int ammo = items.getAmmo(weapon);
        if (ammo >= magazine) {
            player.sendActionBar(CC.translate("&cMagazine already full"));
            return;
        }
        long duration = config().getLong((type == CustomItemType.AK47 ? "ak47" : "awp") + ".reload-ticks", 40);
        stopAutomaticFire(player);
        player.sendActionBar(CC.translate("&eReloading..."));
        player.playSound(player.getLocation(), Sound.BLOCK_IRON_TRAPDOOR_OPEN, 0.8F, 1.2F);
        long endAt = System.currentTimeMillis() + duration * 50L;
        int originalDamage = getDamage(weapon);
        ReloadState state = new ReloadState(endAt, type, player.getInventory().getHeldItemSlot(), ammo, originalDamage);
        this.reloads.put(player.getUniqueId(), state);
        setDamage(weapon, weapon.getType().getMaxDurability() - 1);
        BukkitTask task = Bukkit.getScheduler().runTaskLater(AlleyPlugin.getInstance(), () -> {
            if (!player.isOnline()) return;
            ReloadState current = reloads.remove(player.getUniqueId());
            if (current == null || current.type != type) return;
            if (current.animation != null) current.animation.cancel();
            // A hotbar change should have cancelled the state already.  Keep
            // this guard as a last line of defence so a delayed task can never
            // refill a different weapon that happens to be in the inventory.
            ItemStack reloadedWeapon = player.getInventory().getItem(current.slot);
            if (items.getCustomItemType(reloadedWeapon) != type) {
                restoreReloadSnapshot(player, current, items);
                return;
            }
            items.setAmmo(reloadedWeapon, magazine);
            player.playSound(player.getLocation(), Sound.BLOCK_IRON_TRAPDOOR_CLOSE, 0.8F, 1.0F);
            player.swingMainHand();
        }, duration);
        state.task = task;
        BukkitTask animation = Bukkit.getScheduler().runTaskTimer(AlleyPlugin.getInstance(), () -> {
            if (player.isOnline() && reloads.containsKey(player.getUniqueId())) {
                setReloadProgress(player, state);
                player.swingMainHand();
            }
        }, 1L, 1L);
        state.animation = animation;
    }

    private void restoreReloadSnapshot(Player player, ReloadState state, ItemService items) {
        ItemStack weapon = player.getInventory().getItem(state.slot);
        if (items.getCustomItemType(weapon) == state.type) {
            items.setAmmo(weapon, state.originalAmmo);
            setDamage(weapon, state.originalDamage);
        }
    }

    private boolean isReloading(Player player) {
        return this.reloads.containsKey(player.getUniqueId());
    }

    private void setReloadProgress(Player player, ReloadState state) {
        ItemStack weapon = player.getInventory().getItem(state.slot);
        ItemService items = AlleyPlugin.getInstance().getService(ItemService.class);
        if (items.getCustomItemType(weapon) != state.type) {
            cancelReload(player, true);
            return;
        }
        long duration = Math.max(1L, state.endAt - state.startAt);
        double progress = Math.max(0.0D, Math.min(1.0D,
                (System.currentTimeMillis() - state.startAt) / (double) duration));
        int maximum = weapon.getType().getMaxDurability();
        if (maximum > 0) setDamage(weapon, (int) Math.round((maximum - 1) * (1.0D - progress)));
    }

    private int getDamage(ItemStack item) {
        if (item == null || !(item.getItemMeta() instanceof Damageable damageable)) return 0;
        return damageable.getDamage();
    }

    private void setDamage(ItemStack item, int damage) {
        if (item == null || !(item.getItemMeta() instanceof Damageable damageable)) return;
        int maximum = item.getType().getMaxDurability();
        if (maximum <= 0) return;
        damageable.setDamage(Math.max(0, Math.min(maximum - 1, damage)));
        item.setItemMeta(damageable);
    }

    /**
     * Bow use normally supplies a client-side release event, but calling
     * startUsingItem would also apply the bow's slowdown and FOV effect. Track
     * the same protocol packets directly so the AK remains a normal-speed,
     * normal-FOV item while held right-click still controls automatic fire.
     */
    private void registerUsePacketTracker() {
        ProtocolManager protocolManager = ProtocolLibrary.getProtocolManager();
        protocolManager.addPacketListener(new PacketAdapter(
                AlleyPlugin.getInstance(), ListenerPriority.LOWEST,
                PacketType.Play.Client.USE_ITEM,
                PacketType.Play.Client.USE_ITEM_ON,
                PacketType.Play.Client.BLOCK_DIG,
                PacketType.Play.Client.ARM_ANIMATION,
                PacketType.Play.Client.USE_ENTITY
        ) {
            @Override
            public void onPacketReceiving(PacketEvent event) {
                Player player = event.getPlayer();
                if (!isPlayable(player)) return;
                if (event.getPacketType() == PacketType.Play.Client.ARM_ANIMATION) {
                    ItemService items = AlleyPlugin.getInstance().getService(ItemService.class);
                    ItemStack held = player.getInventory().getItemInMainHand();
                    if (items.getCustomItemType(held) == CustomItemType.AWP
                            && !isReloading(player)) {
                        Bukkit.getScheduler().runTask(AlleyPlugin.getInstance(), () -> {
                            fireAwpOnce(player);
                            clearScope(player);
                        });
                    }
                    return;
                }
                if (event.getPacketType() == PacketType.Play.Client.USE_ENTITY) {
                    // Left-clicking an entity while scoped sends an INTERACT
                    // (attack) packet even though the arm swing is suppressed.
                    EnumWrappers.EntityUseAction useAction = event.getPacket()
                            .getEntityUseActions().readSafely(0);
                    if (useAction == EnumWrappers.EntityUseAction.ATTACK) {
                        ItemService items = AlleyPlugin.getInstance().getService(ItemService.class);
                        ItemStack held = player.getInventory().getItemInMainHand();
                        if (items.getCustomItemType(held) == CustomItemType.AWP
                                && CustomItemListener.this.scoped.contains(player.getUniqueId())) {
                            event.setCancelled(true);
                            Bukkit.getScheduler().runTask(AlleyPlugin.getInstance(), () -> {
                                fireAwpOnce(player);
                                clearScope(player);
                            });
                        }
                    }
                    return;
                }
                if (event.getPacketType() == PacketType.Play.Client.BLOCK_DIG) {
                    EnumWrappers.PlayerDigType action = event.getPacket()
                            .getPlayerDigTypes().readSafely(0);
                    if (action == EnumWrappers.PlayerDigType.SWAP_HELD_ITEMS) {
                        ItemService items = AlleyPlugin.getInstance().getService(ItemService.class);
                        ItemStack held = player.getInventory().getItemInMainHand();
                        CustomItemType type = items.getCustomItemType(held);
                        if (type == CustomItemType.AK47 || type == CustomItemType.AWP) {
                            event.setCancelled(true);
                            Bukkit.getScheduler().runTask(AlleyPlugin.getInstance(), () -> {
                                if (!isReloading(player)) reload(player);
                            });
                        }
                        return;
                    }
                    if (action == EnumWrappers.PlayerDigType.START_DESTROY_BLOCK) {
                        // While scoped the client suppresses the arm swing but
                        // still reports the "start mining" action on left-click,
                        // so this is the scoped-shot trigger.
                        ItemService items = AlleyPlugin.getInstance().getService(ItemService.class);
                        ItemStack held = player.getInventory().getItemInMainHand();
                        if (items.getCustomItemType(held) == CustomItemType.AWP
                                && CustomItemListener.this.scoped.contains(player.getUniqueId())) {
                            event.setCancelled(true);
                            Bukkit.getScheduler().runTask(AlleyPlugin.getInstance(), () -> {
                                fireAwpOnce(player);
                                clearScope(player);
                            });
                        }
                        return;
                    }
                    if (action == EnumWrappers.PlayerDigType.RELEASE_USE_ITEM) {
                        Bukkit.getScheduler().runTask(AlleyPlugin.getInstance(),
                                () -> stopAutomaticFire(player));
                    }
                    return;
                }

                EnumWrappers.Hand hand = event.getPacket().getHands().readSafely(0);
                if (hand == EnumWrappers.Hand.OFF_HAND) return;
                ItemService items = AlleyPlugin.getInstance().getService(ItemService.class);
                ItemStack held = player.getInventory().getItemInMainHand();
                if (items.getCustomItemType(held) != CustomItemType.AK47) return;
                if (isReloading(player)) return;
                lastAkUseNanos.put(player.getUniqueId(), System.nanoTime());
                Bukkit.getScheduler().runTask(AlleyPlugin.getInstance(),
                        () -> startAutomaticFire(player));
            }
        });
    }

    private void cancelReload(Player player, boolean notify) {
        ReloadState state = this.reloads.remove(player.getUniqueId());
        if (state == null) return;
        if (state.task != null) state.task.cancel();
        if (state.animation != null) state.animation.cancel();
        ItemService items = AlleyPlugin.getInstance().getService(ItemService.class);
        ItemStack weapon = player.getInventory().getItem(state.slot);
        if (items.getCustomItemType(weapon) == state.type) {
            items.setAmmo(weapon, state.originalAmmo);
            setDamage(weapon, state.originalDamage);
        }
        if (notify && player.isOnline()) {
            player.playSound(player.getLocation(), Sound.BLOCK_IRON_TRAPDOOR_CLOSE, 0.5F, 0.8F);
        }
    }

    private void clearScope(Player player) {
        if (this.scoped.remove(player.getUniqueId())) {
            removeScopeVisual(player);
        }
    }

    /** Assumed vanilla base FOV (degrees) used to convert scope-fov into a multiplier. */
    private static final double VANILLA_FOV = 70.0D;
    /**
     * Client walkSpeed ability applied while scoped. The 1.21.11 client renders
     * FOV = fov-setting * ((movementSpeed / walkSpeed + 1) / 2), so with a
     * positive walkSpeed the zoom can never go below 0.5x (FOV 35). A negative
     * walkSpeed is therefore required to reach the configured scope-fov: -0.1
     * paired with a 0.0429 movement speed gives exactly FOV 20 at the 70 setting.
     */
    private static final double SCOPE_WALK_SPEED = -0.1D;

    private void applyScopeVisual(Player player) {
        // Pumpkin in the helmet slot renders the pumpkinblur overlay, which the
        // resource pack replaces with the vanilla telescope scope lens.
        this.scopedHelmet.put(player.getUniqueId(), player.getInventory().getHelmet());
        player.getInventory().setHelmet(new ItemStack(Material.CARVED_PUMPKIN));
        setScopeFov(player, true);
    }

    private void removeScopeVisual(Player player) {
        UUID id = player.getUniqueId();
        ItemStack backup = this.scopedHelmet.remove(id);
        player.getInventory().setHelmet(backup == null ? new ItemStack(Material.AIR) : backup);
        setScopeFov(player, false);
    }

    /**
     * Applies or clears the scoped zoom. The client's FOV multiplier is
     * ((movementSpeed / walkSpeed + 1) / 2), so scoping sets a negative
     * walkSpeed ability (synced through the ABILITIES packet) and a transient
     * movement-speed modifier calibrated so the multiplier equals
     * scope-fov / 70. setWalkSpeed also pins the speed attribute's base to zero,
     * which gives a clean base for the modifier. The aim-slow is the reduced
     * movement speed itself, so no Slowness potion is used while scoped.
     */
    private void setScopeFov(Player player, boolean scoped) {
        UUID id = player.getUniqueId();
        AttributeInstance speed = player.getAttribute(Attribute.MOVEMENT_SPEED);
        if (speed == null) return;
        if (scoped) {
            double scopeFov = config().getDouble("awp.scope-fov", 20.0D);
            double targetMultiplier = scopeFov / VANILLA_FOV;
            // FOV multiplier = (speed / walkSpeed + 1) / 2  ->  speed = walkSpeed * (2*m - 1).
            // Clamp so a scope-fov above 35 (which would need a negative speed)
            // degrades to the 0.5x floor (FOV 35) instead of breaking movement.
            double targetSpeed = Math.max(0.0D, SCOPE_WALK_SPEED * (2.0 * targetMultiplier - 1.0));
            this.scopedWalkSpeedBefore.put(id, player.getWalkSpeed());
            this.scopedSpeedBaseBefore.put(id, speed.getBaseValue());
            player.removePotionEffect(PotionEffectType.SLOWNESS);
            // Bukkit's value is double the ability; this also zeroes the speed base.
            player.setWalkSpeed((float) (SCOPE_WALK_SPEED * 2.0F));
            double current = speed.getValue();
            AttributeModifier modifier = new AttributeModifier(
                    new NamespacedKey(AlleyPlugin.getInstance(), "awp_scope_speed"),
                    targetSpeed - current, AttributeModifier.Operation.ADD_NUMBER);
            speed.addTransientModifier(modifier);
            this.scopedSpeedModifier.put(id, modifier);
        } else {
            AttributeModifier modifier = this.scopedSpeedModifier.remove(id);
            if (modifier != null) speed.removeModifier(modifier);
            Float priorWalk = this.scopedWalkSpeedBefore.remove(id);
            if (priorWalk != null) player.setWalkSpeed(priorWalk);
            Double priorBase = this.scopedSpeedBaseBefore.remove(id);
            if (priorBase != null) speed.setBaseValue(priorBase);
        }
    }

    /**
     * Holding the AWP applies a small slow. While scoped the slow comes from the
     * movement-speed modifier set by setScopeFov instead, and the potion is kept
     * off so the FOV calibration (which depends on the exact speed value) is not
     * disturbed. Any pre-existing slowness is captured and restored on un-hold.
     */
    private void updateAwpSlowness(Player player, ItemService items) {
        UUID id = player.getUniqueId();
        boolean holding = items.getCustomItemType(player.getInventory().getItemInMainHand()) == CustomItemType.AWP;
        if (!holding) {
            if (awpSlowed.remove(id)) {
                player.removePotionEffect(PotionEffectType.SLOWNESS);
                PotionEffect previous = displacedSlowness.remove(id);
                if (previous != null) player.addPotionEffect(previous, true);
            }
            return;
        }

        if (!awpSlowed.contains(id)) {
            PotionEffect previous = player.getPotionEffect(PotionEffectType.SLOWNESS);
            if (previous != null) displacedSlowness.put(id, previous);
            awpSlowed.add(id);
        }
        if (scoped.contains(id)) {
            player.removePotionEffect(PotionEffectType.SLOWNESS);
            return;
        }
        player.addPotionEffect(new PotionEffect(PotionEffectType.SLOWNESS, 5, 0, false, false, false), true);
    }

    private void updateAmmoActionBar(Player player, ItemService items) {
        ItemStack held = player.getInventory().getItemInMainHand();
        boolean wasLegacyBow = held != null && held.getType() == org.bukkit.Material.BOW;
        ItemMeta before = held == null ? null : held.getItemMeta();
        CustomItemType type = items.getCustomItemType(held);
        if (type != CustomItemType.AK47 && type != CustomItemType.AWP) return;
        NamespacedKey expectedModel = new NamespacedKey(AlleyPlugin.getInstance(),
                type == CustomItemType.AK47 ? "ak47" : "awp");
        if (wasLegacyBow || before == null || !expectedModel.equals(before.getItemModel())) {
            // Persist first-pass weapon migration and make the
            // corrected item_model component visible to an already-connected
            // client immediately.
            player.getInventory().setItemInMainHand(held);
        }
        ReloadState reload = reloads.get(player.getUniqueId());
        if (reload != null) {
            long remaining = Math.max(0L, reload.endAt - System.currentTimeMillis());
            player.sendActionBar(CC.translate("&eReloading &7(" + ((remaining + 49L) / 50L) + " ticks)"));
            return;
        }
        // Backfill the durability component for items created before the ammo
        // bar was introduced, and keep it synchronized after each shot.
        items.setAmmo(held, items.getAmmo(held));
        String key = type == CustomItemType.AK47 ? "ak47" : "awp";
        int magazine = config().getInt(key + ".magazine-size", type == CustomItemType.AK47 ? 30 : 5);
        player.sendActionBar(CC.translate("&6" + (type == CustomItemType.AK47 ? "AK-47" : "AWP")
                + " &f" + items.getAmmo(held) + "&7/&f" + magazine));
    }

    private void updateWeaponCooldownHud(Player player, ItemService items) {
        CustomItemType type = items.getCustomItemType(player.getInventory().getItemInMainHand());
        boolean holdingWeapon = type == CustomItemType.AK47 || type == CustomItemType.AWP;
        if (!holdingWeapon) {
            this.weaponEquipped.remove(player.getUniqueId());
            this.weaponReadyAt.remove(player.getUniqueId());
            restoreExperienceHud(player);
            return;
        }

        long now = System.currentTimeMillis();
        if (this.weaponEquipped.add(player.getUniqueId())) {
            this.weaponReadyAt.put(player.getUniqueId(), now + weaponSwitchDelayMillis(type));
        }
        long readyAt = this.weaponReadyAt.getOrDefault(player.getUniqueId(), 0L);
        if (readyAt <= now) this.weaponReadyAt.remove(player.getUniqueId());
        long awpUntil = type == CustomItemType.AWP
                ? this.awpCooldownUntil.getOrDefault(player.getUniqueId(), 0L) : 0L;
        if (awpUntil <= now) this.awpCooldownUntil.remove(player.getUniqueId());

        long until = Math.max(readyAt, awpUntil);
        if (until <= now) {
            restoreExperienceHud(player);
            return;
        }

        long duration = weaponSwitchDelayMillis(type);
        if (type == CustomItemType.AWP) duration = Math.max(duration, awpShotCooldownMillis());
        float progress = (float) Math.max(0.0D, Math.min(1.0D,
                (until - now) / (double) Math.max(1L, duration)));
        this.experienceHudOverride.add(player.getUniqueId());
        player.sendExperienceChange(progress, player.getLevel());
    }

    private void restoreExperienceHud(Player player) {
        if (this.experienceHudOverride.remove(player.getUniqueId())) {
            player.sendExperienceChange(player.getExp(), player.getLevel());
        }
    }

    private boolean isWeaponReady(Player player) {
        long until = this.weaponReadyAt.getOrDefault(player.getUniqueId(), 0L);
        if (until <= System.currentTimeMillis()) {
            this.weaponReadyAt.remove(player.getUniqueId());
            return true;
        }
        player.sendActionBar(CC.translate("&eWeapon is not ready yet"));
        return false;
    }

    private boolean isAwpOnCooldown(Player player) {
        long until = this.awpCooldownUntil.getOrDefault(player.getUniqueId(), 0L);
        if (until <= System.currentTimeMillis()) {
            this.awpCooldownUntil.remove(player.getUniqueId());
            return false;
        }
        long remaining = until - System.currentTimeMillis();
        player.sendActionBar(CC.translate("&eAWP cooldown &7(" + ((remaining + 49L) / 50L) + " ticks)"));
        return true;
    }

    private long weaponSwitchDelayMillis(CustomItemType type) {
        String key = type == CustomItemType.AK47 ? "ak47" : "awp";
        return Math.max(0L, config().getLong(key + ".switch-ticks", 24L)) * 50L;
    }

    private long awpShotCooldownMillis() {
        return Math.max(1L, config().getLong("awp.shot-cooldown-ticks", 24L)) * 50L;
    }

    private static final class ReloadState {
        private BukkitTask task;
        private BukkitTask animation;
        private final long endAt;
        private final CustomItemType type;
        private final long startAt;
        private final int slot;
        private final int originalAmmo;
        private final int originalDamage;

        private ReloadState(long endAt, CustomItemType type, int slot, int originalAmmo, int originalDamage) {
            this.endAt = endAt;
            this.type = type;
            this.startAt = System.currentTimeMillis();
            this.slot = slot;
            this.originalAmmo = originalAmmo;
            this.originalDamage = originalDamage;
        }
    }

    private void spawnMuzzle(Player player, Location location) {
        player.getWorld().spawnParticle(Particle.SMOKE, location, 3, 0.04, 0.04, 0.04, 0.01);
    }

    private org.bukkit.configuration.file.FileConfiguration config() {
        return AlleyPlugin.getInstance().getService(ConfigService.class).getCustomItemsConfig();
    }

    private boolean isPlayable(Player player) {
        Profile profile = AlleyPlugin.getInstance().getService(ProfileService.class)
                .getProfile(player.getUniqueId());
        return profile != null && (profile.getMatch() != null || profile.getFfaMatch() != null);
    }
}
