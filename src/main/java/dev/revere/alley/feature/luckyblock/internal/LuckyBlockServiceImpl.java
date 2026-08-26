package dev.revere.alley.feature.luckyblock.internal;

import dev.revere.alley.AlleyPlugin;
import dev.revere.alley.bootstrap.AlleyContext;
import dev.revere.alley.bootstrap.annotation.Service;
import dev.revere.alley.common.text.CC;
import dev.revere.alley.core.profile.Profile;
import dev.revere.alley.core.profile.ProfileService;
import dev.revere.alley.core.profile.enums.ProfileState;
import dev.revere.alley.feature.kit.Kit;
import dev.revere.alley.feature.kit.KitService;
import dev.revere.alley.feature.kit.setting.types.mechanic.KitSettingLuckyBlock;
import dev.revere.alley.feature.luckyblock.LuckyBlockService;
import dev.revere.alley.feature.luckyblock.LuckyBlockRewardType;
import dev.revere.alley.feature.match.Match;
import dev.revere.alley.feature.match.MatchState;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.block.Block;
import org.bukkit.block.BlockState;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Mob;
import org.bukkit.entity.Player;
import org.bukkit.entity.Skeleton;
import org.bukkit.entity.Zombie;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockFromToEvent;
import org.bukkit.event.entity.EntityDeathEvent;
import org.bukkit.event.entity.CreatureSpawnEvent;
import org.bukkit.inventory.EntityEquipment;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.scheduler.BukkitRunnable;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Runtime implementation for LuckyBlock rewards and penalties.
 */
@Service(provides = LuckyBlockService.class, priority = 360)
public class LuckyBlockServiceImpl implements LuckyBlockService, Listener {
    private static final int PENALTY_CHANCE_PERCENT = 20;
    private static final long LAVA_DURATION_TICKS = 100L;
    private static final long MOB_MAX_LIFETIME_TICKS = 20L * 30L;

    private final AlleyPlugin plugin;
    private final ProfileService profileService;
    private final KitService kitService;
    private final NamespacedKey temporaryMobKey;

    private final Map<Match, Map<LuckyBlockRewardType, List<ItemStack>>> rewardPools = new ConcurrentHashMap<>();
    private final Map<Match, Map<Location, BlockState>> brokenLuckyBlocks = new ConcurrentHashMap<>();
    private final Map<Location, TemporaryLava> temporaryLava = new ConcurrentHashMap<>();
    private final Map<Match, Set<UUID>> temporaryMobs = new ConcurrentHashMap<>();

    public LuckyBlockServiceImpl(AlleyPlugin plugin, ProfileService profileService, KitService kitService) {
        this.plugin = plugin;
        this.profileService = profileService;
        this.kitService = kitService;
        this.temporaryMobKey = new NamespacedKey(plugin, "lucky_block_temporary_mob");
    }

    @Override
    public void initialize(AlleyContext context) {
        this.plugin.getServer().getPluginManager().registerEvents(this, this.plugin);
    }

    @Override
    public void shutdown(AlleyContext context) {
        Set<Match> activeMatches = new HashSet<>(this.rewardPools.keySet());
        activeMatches.addAll(this.brokenLuckyBlocks.keySet());
        activeMatches.addAll(this.temporaryMobs.keySet());
        this.temporaryLava.values().forEach(lava -> activeMatches.add(lava.match()));
        activeMatches.forEach(this::cleanupMatch);
    }

    @Override
    public boolean isLuckyBlock(Material material) {
        return LuckyBlockRewardType.fromBlock(material) != null;
    }

    @Override
    public boolean isTemporaryPenaltyMob(Entity entity) {
        return entity != null && entity.getPersistentDataContainer()
                .has(this.temporaryMobKey, PersistentDataType.BYTE);
    }

    @Override
    public void prepareMatch(Match match) {
        if (match == null || !match.getKit().isSettingEnabled(KitSettingLuckyBlock.class)) {
            return;
        }

        Kit matchKit = match.getKit();
        Map<LuckyBlockRewardType, List<ItemStack>> pools = new EnumMap<>(LuckyBlockRewardType.class);
        pools.put(LuckyBlockRewardType.GREEN, snapshotPool(matchKit.getLuckyBlockGreenKit()));
        pools.put(LuckyBlockRewardType.BLUE, snapshotPool(matchKit.getLuckyBlockBlueKit()));
        pools.put(LuckyBlockRewardType.YELLOW, snapshotPool(matchKit.getLuckyBlockYellowKit()));
        this.rewardPools.put(match, pools);
    }

    @Override
    public Optional<ItemStack> createRandomReward(Match match, Material luckyBlockMaterial) {
        LuckyBlockRewardType rewardType = LuckyBlockRewardType.fromBlock(luckyBlockMaterial);
        if (match == null || rewardType == null) {
            return Optional.empty();
        }

        Map<LuckyBlockRewardType, List<ItemStack>> pools = this.rewardPools.get(match);
        List<ItemStack> candidates = pools == null ? null : pools.get(rewardType);
        if (candidates == null || candidates.isEmpty()) {
            return Optional.empty();
        }
        return Optional.of(candidates.get(ThreadLocalRandom.current().nextInt(candidates.size())).clone());
    }

    /**
     * Each reward kit is an explicit pool, so every non-air item saved in that
     * kit may be awarded. This includes its inventory, armour, and offhand;
     * there are deliberately no material-name category heuristics.
     */
    private List<ItemStack> snapshotPool(String kitName) {
        List<ItemStack> items = new ArrayList<>();
        if (kitName == null || kitName.isBlank()) {
            return items;
        }
        Kit rewardKit = this.kitService.getKit(kitName);
        if (rewardKit == null || rewardKit.getItems() == null) {
            return items;
        }
        for (ItemStack item : rewardKit.getItems()) {
            if (item != null && !item.getType().isAir() && item.getAmount() > 0) {
                items.add(item.clone());
            }
        }
        if (rewardKit.getArmor() != null) {
            for (ItemStack item : rewardKit.getArmor()) {
                if (item != null && !item.getType().isAir() && item.getAmount() > 0) {
                    items.add(item.clone());
                }
            }
        }
        if (rewardKit.getOffhand() != null
                && !rewardKit.getOffhand().getType().isAir()
                && rewardKit.getOffhand().getAmount() > 0) {
            items.add(rewardKit.getOffhand().clone());
        }
        return items;
    }

    @Override
    public boolean shouldApplyPenalty() {
        return ThreadLocalRandom.current().nextInt(100) < PENALTY_CHANCE_PERCENT;
    }

    @Override
    public void applyRandomPenalty(Player player, Match match) {
        if (!isActiveParticipant(player, match)) {
            return;
        }

        switch (ThreadLocalRandom.current().nextInt(6)) {
            case 0 -> spawnZombies(player, match);
            case 1 -> placeTemporaryLava(player, match);
            case 2 -> applyPotionPenalty(player);
            case 3 -> player.damage(6.0D);
            case 4 -> teleportUp(player);
            case 5 -> spawnDiamondSkeletons(player, match);
            default -> throw new IllegalStateException("Unexpected LuckyBlock penalty");
        }
        player.sendMessage(CC.translate("&cLucky block failed! A penalty was applied."));
    }

    @Override
    public void trackBrokenLuckyBlock(Match match, BlockState originalState, Location location) {
        if (match == null || originalState == null || location == null || location.getWorld() == null) {
            return;
        }
        Location key = location.toBlockLocation();
        this.brokenLuckyBlocks
                .computeIfAbsent(match, ignored -> new ConcurrentHashMap<>())
                .putIfAbsent(key, originalState);
    }

    @Override
    public void cleanupMatch(Match match) {
        if (match == null) {
            return;
        }

        this.rewardPools.remove(match);

        Map<Location, BlockState> brokenBlocks = this.brokenLuckyBlocks.remove(match);
        if (brokenBlocks != null) {
            brokenBlocks.forEach((location, originalState) -> {
                if (location.getWorld() != null) {
                    location.getBlock().setBlockData(originalState.getBlockData(), false);
                }
            });
        }

        this.temporaryLava.entrySet().removeIf(entry -> {
            TemporaryLava lava = entry.getValue();
            if (lava.match() != match) {
                return false;
            }
            restoreTemporaryLava(entry.getKey(), lava);
            return true;
        });

        Set<UUID> mobs = this.temporaryMobs.remove(match);
        if (mobs != null) {
            mobs.forEach(uuid -> {
                Entity entity = this.plugin.getServer().getEntity(uuid);
                if (entity != null && entity.isValid()) {
                    entity.remove();
                }
            });
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onTemporaryLavaFlow(BlockFromToEvent event) {
        if (this.temporaryLava.containsKey(event.getBlock().getLocation().toBlockLocation())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onTemporaryMobDeath(EntityDeathEvent event) {
        if (event.getEntity().getPersistentDataContainer().has(this.temporaryMobKey, PersistentDataType.BYTE)) {
            event.getDrops().clear();
            event.setDroppedExp(0);
        }
    }


    private boolean isActiveParticipant(Player player, Match match) {
        if (player == null || !player.isOnline() || match == null || match.getState() != MatchState.RUNNING) {
            return false;
        }
        Profile profile = this.profileService.getProfile(player.getUniqueId());
        return profile != null && profile.getMatch() == match && profile.getState() == ProfileState.PLAYING;
    }

    private void spawnZombies(Player player, Match match) {
        List<UUID> spawned = new ArrayList<>();
        for (int index = 0; index < 5; index++) {
            Zombie zombie = player.getWorld().spawn(randomNearbyLocation(player), Zombie.class,
                    CreatureSpawnEvent.SpawnReason.CUSTOM);
            configureTemporaryMob(zombie, player);
            spawned.add(zombie.getUniqueId());
        }
        trackTemporaryMobs(match, spawned);
    }

    private void spawnDiamondSkeletons(Player player, Match match) {
        List<UUID> spawned = new ArrayList<>();
        for (int index = 0; index < 3; index++) {
            Skeleton skeleton = player.getWorld().spawn(randomNearbyLocation(player), Skeleton.class,
                    CreatureSpawnEvent.SpawnReason.CUSTOM);
            configureTemporaryMob(skeleton, player);

            EntityEquipment equipment = skeleton.getEquipment();
            if (equipment != null) {
                equipment.setHelmet(new ItemStack(Material.DIAMOND_HELMET));
                equipment.setChestplate(new ItemStack(Material.DIAMOND_CHESTPLATE));
                equipment.setLeggings(new ItemStack(Material.DIAMOND_LEGGINGS));
                equipment.setBoots(new ItemStack(Material.DIAMOND_BOOTS));
                equipment.setHelmetDropChance(0.0F);
                equipment.setChestplateDropChance(0.0F);
                equipment.setLeggingsDropChance(0.0F);
                equipment.setBootsDropChance(0.0F);
                equipment.setItemInMainHandDropChance(0.0F);
            }
            spawned.add(skeleton.getUniqueId());
        }
        trackTemporaryMobs(match, spawned);
    }

    private void configureTemporaryMob(LivingEntity entity, Player target) {
        entity.setCanPickupItems(false);
        entity.getPersistentDataContainer().set(this.temporaryMobKey, PersistentDataType.BYTE, (byte) 1);
        if (entity instanceof Mob mob) {
            mob.setTarget(target);
        }
    }

    private Location randomNearbyLocation(Player player) {
        return player.getLocation().clone().add(
                ThreadLocalRandom.current().nextDouble(-2.5D, 2.5D),
                0.0D,
                ThreadLocalRandom.current().nextDouble(-2.5D, 2.5D)
        );
    }

    private void trackTemporaryMobs(Match match, List<UUID> spawned) {
        if (spawned.isEmpty()) {
            return;
        }
        this.temporaryMobs.computeIfAbsent(match, ignored -> ConcurrentHashMap.newKeySet()).addAll(spawned);

        new BukkitRunnable() {
            private long elapsedTicks;

            @Override
            public void run() {
                elapsedTicks += 20L;
                if (elapsedTicks >= MOB_MAX_LIFETIME_TICKS || match.getState() != MatchState.RUNNING) {
                    spawned.forEach(uuid -> {
                        Entity entity = plugin.getServer().getEntity(uuid);
                        if (entity != null && entity.isValid()) {
                            entity.remove();
                        }
                    });
                    Set<UUID> tracked = temporaryMobs.get(match);
                    if (tracked != null) {
                        tracked.removeAll(spawned);
                        if (tracked.isEmpty()) {
                            temporaryMobs.remove(match, tracked);
                        }
                    }
                    cancel();
                }
            }
        }.runTaskTimer(this.plugin, 20L, 20L);
    }

    private void placeTemporaryLava(Player player, Match match) {
        Block block = player.getLocation().getBlock();
        if (!block.getType().isAir()) {
            return;
        }

        Location location = block.getLocation().toBlockLocation();
        BlockState originalState = block.getState();
        block.setType(Material.LAVA, false);
        BlockState placedState = block.getState();
        match.addBlockToPlacedBlocksMap(placedState, location);
        TemporaryLava lava = new TemporaryLava(match, originalState, placedState);
        this.temporaryLava.put(location, lava);

        new BukkitRunnable() {
            @Override
            public void run() {
                TemporaryLava tracked = temporaryLava.remove(location);
                if (tracked != null) {
                    restoreTemporaryLava(location, tracked);
                }
            }
        }.runTaskLater(this.plugin, LAVA_DURATION_TICKS);
    }

    private void restoreTemporaryLava(Location location, TemporaryLava lava) {
        if (location.getWorld() == null) {
            return;
        }
        Block block = location.getBlock();
        if (block.getType() == Material.LAVA) {
            lava.match().removeBlockFromPlacedBlocksMap(lava.placedState(), location);
            block.setBlockData(lava.originalState().getBlockData(), false);
        }
    }

    private void applyPotionPenalty(Player player) {
        player.addPotionEffect(new PotionEffect(PotionEffectType.SLOWNESS, 100, 0), true);
        player.addPotionEffect(new PotionEffect(PotionEffectType.BLINDNESS, 100, 0), true);
    }

    private void teleportUp(Player player) {
        Location target = player.getLocation().clone().add(0.0D, 10.0D, 0.0D);
        if (target.getY() >= target.getWorld().getMaxHeight() - 1) {
            return;
        }
        player.teleportAsync(target);
    }

    private record TemporaryLava(Match match, BlockState originalState, BlockState placedState) {
    }
}
