package dev.revere.alley.feature.match.listener.types;

import dev.revere.alley.AlleyPlugin;
import dev.revere.alley.common.ListenerUtil;
import dev.revere.alley.common.reflect.ReflectionService;
import dev.revere.alley.common.reflect.internal.types.BlockAnimationReflectionServiceImpl;
import dev.revere.alley.common.text.CC;
import dev.revere.alley.core.locale.LocaleService;
import dev.revere.alley.core.locale.internal.impl.message.GameMessagesLocaleImpl;
import dev.revere.alley.core.profile.Profile;
import dev.revere.alley.core.profile.ProfileService;
import dev.revere.alley.core.profile.enums.ProfileState;
import dev.revere.alley.feature.arena.internal.types.StandAloneArena;
import dev.revere.alley.feature.kit.setting.types.mechanic.KitSettingBuildImpl;
import dev.revere.alley.feature.kit.setting.types.mechanic.KitSettingBreakArenaBlocksImpl;
import dev.revere.alley.feature.kit.setting.types.mechanic.KitSettingLuckyBlock;
import dev.revere.alley.feature.kit.setting.types.mechanic.KitSettingTimedBlocksImpl;
import dev.revere.alley.feature.luckyblock.LuckyBlockService;
import dev.revere.alley.feature.kit.setting.types.mode.*;
import dev.revere.alley.feature.match.Match;
import dev.revere.alley.feature.match.MatchService;
import dev.revere.alley.feature.match.MatchState;
import dev.revere.alley.feature.match.internal.types.BedMatch;
import dev.revere.alley.feature.match.internal.types.HideAndSeekMatch;
import dev.revere.alley.feature.match.model.BaseRaiderRole;
import dev.revere.alley.feature.match.model.GameParticipant;
import dev.revere.alley.feature.match.model.internal.MatchGamePlayer;
import org.bukkit.ChatColor;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.block.BlockState;
import org.bukkit.block.Sign;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.*;
import org.bukkit.event.entity.EntityExplodeEvent;
import org.bukkit.event.entity.ProjectileHitEvent;
import org.bukkit.event.player.PlayerBedEnterEvent;
import org.bukkit.event.player.PlayerBucketEmptyEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.scheduler.BukkitRunnable;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ThreadLocalRandom;
import java.util.stream.Collectors;

/**
 * Match block listener for handling block-related events during matches.
 * 比赛方块监听器，用于处理比赛期间的方块相关事件。
 * @author Emmy
 * @project Alley
 * @since 08/02/2025
 */
public class MatchBlockListener implements Listener {
    private static final BlockFace[] FLUID_NEIGHBORS = {
            BlockFace.NORTH, BlockFace.SOUTH, BlockFace.EAST,
            BlockFace.WEST, BlockFace.UP, BlockFace.DOWN
    };

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBlockFromEvent(BlockFromToEvent event) {
        Match match = findBuildMatchTracking(event.getBlock().getLocation());
        if (match == null) return;

        Block destination = event.getToBlock();
        BlockState originalState = destination.getState();
        Location destinationLocation = destination.getLocation();
        match.addBlockToBrokenBlocksMap(originalState, destinationLocation);
        match.addBlockToPlacedBlocksMap(originalState, destinationLocation);
        AlleyPlugin.getInstance().getServer().getScheduler().runTask(AlleyPlugin.getInstance(), () -> {
            if (match.getState() != MatchState.RUNNING) {
                match.removeBlockFromPlacedBlocksMap(originalState, destinationLocation);
                return;
            }
            if (destination.getType() == Material.WATER || destination.getType() == Material.LAVA) {
                match.addBlockToPlacedBlocksMap(destination.getState(), destinationLocation);
            } else {
                match.removeBlockFromPlacedBlocksMap(originalState, destinationLocation);
            }
        });
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onFluidGeneratedBlock(BlockFormEvent event) {
        Material formedType = event.getNewState().getType();
        if (formedType != Material.OBSIDIAN
                && formedType != Material.COBBLESTONE
                && formedType != Material.STONE) return;

        Block formedBlock = event.getBlock();
        Match match = findBuildMatchTracking(formedBlock.getLocation());
        if (match == null) {
            for (BlockFace face : FLUID_NEIGHBORS) {
                Block neighbor = formedBlock.getRelative(face);
                if (neighbor.getType() != Material.WATER && neighbor.getType() != Material.LAVA) continue;
                match = findBuildMatchTracking(neighbor.getLocation());
                if (match != null) break;
            }
        }
        if (match == null) return;

        Match trackedMatch = match;
        BlockState originalState = formedBlock.getState();
        Location location = formedBlock.getLocation();
        AlleyPlugin.getInstance().getServer().getScheduler().runTask(AlleyPlugin.getInstance(), () -> {
            if (trackedMatch.getState() != MatchState.RUNNING) return;
            Material currentType = location.getBlock().getType();
            if (currentType != Material.OBSIDIAN
                    && currentType != Material.COBBLESTONE
                    && currentType != Material.STONE) return;

            trackedMatch.addBlockToBrokenBlocksMap(originalState, location);
            trackedMatch.addBlockToPlacedBlocksMap(location.getBlock().getState(), location);
        });
    }

    @EventHandler
    private void onBucketEmpty(PlayerBucketEmptyEvent event) {
        Player player = event.getPlayer();
        ProfileService profileService = AlleyPlugin.getInstance().getService(ProfileService.class);
        Profile profile = profileService.getProfile(player.getUniqueId());
        if (profile == null || profile.getMatch() == null) return;
        if (profile.getMatch().getState() == MatchState.STARTING) {
            event.setCancelled(true);
            return;
        }

        Match match = profile.getMatch();
        if (profile.getState() != ProfileState.PLAYING
                || match.getState() != MatchState.RUNNING
                || !match.getKit().isSettingEnabled(KitSettingBuildImpl.class)) return;

        Material liquid = event.getBucket() == Material.WATER_BUCKET ? Material.WATER
                : event.getBucket() == Material.LAVA_BUCKET ? Material.LAVA : null;
        if (liquid == null) return;

        Block destination = event.getBlock();
        BlockState originalState = destination.getState();
        Location destinationLocation = destination.getLocation();
        match.addBlockToBrokenBlocksMap(originalState, destinationLocation);
        match.addBlockToPlacedBlocksMap(originalState, destinationLocation);
        AlleyPlugin.getInstance().getServer().getScheduler().runTask(AlleyPlugin.getInstance(), () -> {
            if (match.getState() == MatchState.RUNNING && destination.getType() == liquid) {
                match.addBlockToPlacedBlocksMap(destination.getState(), destinationLocation);
            } else {
                match.removeBlockFromPlacedBlocksMap(originalState, destinationLocation);
            }
        });
    }

    @EventHandler
    private void onBlockBreak(BlockBreakEvent event) {
        Player player = event.getPlayer();
        LocaleService localeService = AlleyPlugin.getInstance().getService(LocaleService.class);
        ProfileService profileService = AlleyPlugin.getInstance().getService(ProfileService.class);

        Profile profile = profileService.getProfile(player.getUniqueId());
        if (profile == null) {
            return;
        }

        Match match = profile.getMatch();
        if (match == null) {
            return;
        }

        GameParticipant<MatchGamePlayer> participant = match.getParticipant(player);

        switch (profile.getState()) {
            case PLAYING:
                MatchState matchState = match.getState();
                if (matchState == MatchState.STARTING || matchState == MatchState.ENDING_MATCH || matchState == MatchState.RESTARTING_ROUND) {
                    event.setCancelled(true);
                    return;
                }

                Material brokenType = event.getBlock().getType();
                if (brokenType == Material.FIRE || brokenType == Material.SOUL_FIRE) {
                    event.setDropItems(false);
                    event.setExpToDrop(0);
                    return;
                }

                LuckyBlockService luckyBlockService = AlleyPlugin.getInstance().getService(LuckyBlockService.class);
                if (matchState == MatchState.RUNNING
                        && match.getKit().isSettingEnabled(KitSettingLuckyBlock.class)
                        && luckyBlockService.isLuckyBlock(brokenType)) {
                    Block luckyBlock = event.getBlock();
                    BlockState originalState = luckyBlock.getState();

                    // LuckyBlock mode explicitly allows the three native
                    // LuckyBlock powders to be broken, independently of the
                    // normal Build/BreakArenaBlocks settings. Track the
                    // original state so the arena is restored after the match.
                    match.addBlockToBrokenBlocksMap(originalState, luckyBlock.getLocation());
                    luckyBlockService.trackBrokenLuckyBlock(match, originalState, luckyBlock.getLocation());
                    event.setDropItems(false);
                    event.setExpToDrop(0);

                    // The normal match protection pipeline can cancel a
                    // BlockBreakEvent after this listener has awarded the
                    // LuckyBlock reward. Perform the world mutation ourselves
                    // and cancel the native break to guarantee the block is
                    // gone immediately, while the saved original state still
                    // restores it when the match ends.
                    event.setCancelled(true);
                    luckyBlock.setType(Material.AIR, false);

                    java.util.Optional<ItemStack> reward = luckyBlockService
                            .createRandomReward(match, brokenType);
                    // A missing/empty reward-kit must not make the map block
                    // unbreakable; it simply produces no reward or penalty.
                    if (reward.isEmpty()) {
                        return;
                    }

                    if (luckyBlockService.shouldApplyPenalty()) {
                        luckyBlockService.applyRandomPenalty(player, match);
                    } else {
                        luckyBlock.getWorld().dropItemNaturally(
                                luckyBlock.getLocation().add(0.5D, 0.5D, 0.5D), reward.get());
                    }
                    return;
                }

                boolean buildEnabled = match.getKit().isSettingEnabled(KitSettingBuildImpl.class);
                boolean breakArenaBlocks = match.getKit().isSettingEnabled(KitSettingBreakArenaBlocksImpl.class);
                if (buildEnabled || breakArenaBlocks) {
                    Block block = event.getBlock();

                    if (match.getKit().isSettingEnabled(KitSettingRaiding.class)) {
                        if (participant.getLeader().getData().getRole() == BaseRaiderRole.TRAPPER) {
                            match.addBlockToBrokenBlocksMap(block.getState(), block.getLocation());
                            return;
                        } else {
                            event.setCancelled(true);
                        }
                        return;
                    }

                    if (match.getKit().isSettingEnabled(KitSettingHideAndSeek.class)) {
                        HideAndSeekMatch matchHideAndSeek = (HideAndSeekMatch) profile.getMatch();
                        GameParticipant<MatchGamePlayer> seekers = matchHideAndSeek.getParticipantA();

                        boolean isSeeker = seekers.containsPlayer(player.getUniqueId());

                        if (matchHideAndSeek.getGameEndTask() == null && isSeeker) {
                            player.sendMessage(localeService.getString(GameMessagesLocaleImpl.MATCH_CANNOT_BREAK_BLOCKS_DURING_HIDING_PHASE));
                            event.setCancelled(true);
                            return;
                        }
                    }

                    if (match.getKit().isSettingEnabled(KitSettingBed.class)) {
                        BedMatch matchBed = (BedMatch) profile.getMatch();
                        if (matchBed == null) {
                            event.setCancelled(true);
                            return;
                        }

                        if (!ListenerUtil.isBedFightProtectedBlock(block.getType())) {
                            if (!matchBed.isNearBed(block)) {
                                event.setCancelled(true);
                                return;
                            }

                            event.setCancelled(true);
                            return;
                        }

                        StandAloneArena arena = (StandAloneArena) match.getArena();
                        if (!block.getType().name().endsWith("_BED")) {
                            return;
                        }

                        if (!arena.isEnemyBed(block, participant)) {
                            player.sendMessage(localeService.getString(GameMessagesLocaleImpl.MATCH_CANNOT_BREAK_OWN_BED));
                            event.setCancelled(true);
                            return;
                        }

                        GameParticipant<MatchGamePlayer> opponent = matchBed.getParticipantA().containsPlayer(player.getUniqueId())
                                ? matchBed.getParticipantB()
                                : matchBed.getParticipantA();

                        event.setDropItems(false);
                        event.setExpToDrop(0);
                        event.setCancelled(true);

                        if (opponent == null) {
                            return;
                        }

                        removeBed(match, block);
                        opponent.setBedBroken(true);
                        matchBed.alertBedDestruction(player, opponent);
                        return;
                    }

                    if (match.getKit().isSettingEnabled(KitSettingBridges.class)) {
                        if (block.getType().name().endsWith("_TERRACOTTA")) {
                            match.addBlockToBrokenBlocksMap(block.getState(), block.getLocation());
                            return;
                        }
                    }

                    BlockState blockState = event.getBlock().getState();

                    if (match.getPlacedBlocks().containsKey(blockState)) {
                        match.removeBlockFromPlacedBlocksMap(blockState, event.getBlock().getLocation());
                    } else if (breakArenaBlocks) {
                        // Arena blocks are restored at the end of the match, so they
                        // should not drop any items when broken.
                        event.setDropItems(false);
                        event.setExpToDrop(0);
                        match.addBlockToBrokenBlocksMap(blockState, event.getBlock().getLocation());
                    } else {
                        event.setCancelled(true);
                    }
                    return;
                }

                if (match.getKit().isSettingEnabled(KitSettingSpleef.class)) {
                    Block block = event.getBlock();
                    if (block.getType() != Material.SNOW_BLOCK) {
                        event.setCancelled(true);
                        return;
                    }

                    match.addBlockToBrokenBlocksMap(block.getState(), block.getLocation());
                    event.getBlock().setType(Material.AIR);

                    int amount = ThreadLocalRandom.current().nextInt(3, 6);
                    if (amount > 0) {
                        ItemStack snowballs = new ItemStack(Material.SNOWBALL, amount);
                        player.getInventory().addItem(snowballs);
                    }
                    return;
                }

                event.setCancelled(true);
                break;

            case SPECTATING:
                event.setCancelled(true);
                break;
        }
    }

    /**
     * Blocks destroyed by entity explosions (TNT minecarts, end crystals, creepers, …)
     * in a BreakArenaBlocks match are restored at the end of the match, so they must not
     * drop any items — the same rule as player-broken arena blocks.
     * 实体爆炸（TNT矿车、末影水晶、苦力怕等）在开启 BreakArenaBlocks 的比赛中破坏的方块，
     * 会在对局结束时恢复，因此不应掉落任何物品——与玩家手动破坏竞技场方块一致。
     */
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onEntityExplode(EntityExplodeEvent event) {
        Match match = findBreakArenaBlocksMatch(event.getLocation());
        if (match == null) return;

        event.setYield(0.0f);
        for (Block block : new ArrayList<>(event.blockList())) {
            match.addBlockToBrokenBlocksMap(block.getState(), block.getLocation());
        }
    }

    /**
     * Same as {@link #onEntityExplode} but for block-triggered explosions
     * (respawn anchors, beds, …).
     * 与 {@link #onEntityExplode} 相同，但针对方块触发的爆炸（重生锚、床等）。
     */
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onBlockExplode(BlockExplodeEvent event) {
        Match match = findBreakArenaBlocksMatch(event.getBlock().getLocation());
        if (match == null) return;

        event.setYield(0.0f);
        for (Block block : new ArrayList<>(event.blockList())) {
            match.addBlockToBrokenBlocksMap(block.getState(), block.getLocation());
        }
    }

    /**
     * Finds a running match whose kit has BreakArenaBlocks enabled and whose arena bounds
     * contain the given location.
     * 查找已开启 BreakArenaBlocks 的进行中比赛，其竞技场边界包含给定位置。
     */
    private Match findBreakArenaBlocksMatch(Location location) {
        if (location == null) return null;
        for (Match match : AlleyPlugin.getInstance().getService(MatchService.class).getMatches()) {
            if (match.getState() != MatchState.RUNNING) continue;
            if (!match.getKit().isSettingEnabled(KitSettingBreakArenaBlocksImpl.class)) continue;
            Location min = match.getArena().getMinimum();
            Location max = match.getArena().getMaximum();
            if (min == null || max == null) continue;
            if (isWithinBounds(location, min, max)) return match;
        }
        return null;
    }

    private boolean isWithinBounds(Location location, Location min, Location max) {
        double x = location.getX();
        double y = location.getY();
        double z = location.getZ();
        return x >= Math.min(min.getX(), max.getX()) && x <= Math.max(min.getX(), max.getX())
                && y >= Math.min(min.getY(), max.getY()) && y <= Math.max(min.getY(), max.getY())
                && z >= Math.min(min.getZ(), max.getZ()) && z <= Math.max(min.getZ(), max.getZ());
    }

    @EventHandler
    private void onBlockPlace(BlockPlaceEvent event) {
        Player player = event.getPlayer();
        Block placedBlock = event.getBlockPlaced();

        LocaleService localeService = AlleyPlugin.getInstance().getService(LocaleService.class);
        ProfileService profileService = AlleyPlugin.getInstance().getService(ProfileService.class);
        Profile profile = profileService.getProfile(player.getUniqueId());

        Match match = profile.getMatch();
        if (match == null) {
            return;
        }

        switch (profile.getState()) {
            case PLAYING:
                if (match.getKit().isSettingEnabled(KitSettingBed.class)
                        && placedBlock.getType().name().endsWith("_BED")) {
                    event.setCancelled(true);
                    return;
                }

                if (match.getState() == MatchState.STARTING) {
                    if (match.getKit().isSettingEnabled(KitSettingBuildImpl.class)
                            && placedBlock.getType() == Material.TNT) {
                        return;
                    }

                    event.setCancelled(true);
                }

                if (match.getState() == MatchState.ENDING_MATCH) event.setCancelled(true);
                if (match.getState() == MatchState.RESTARTING_ROUND) event.setCancelled(true);

                if (match.getArena() instanceof StandAloneArena) {
                    StandAloneArena arena = (StandAloneArena) match.getArena();
                    Location blockLocation = placedBlock.getLocation();

                    if (blockLocation.getBlockY() > arena.getHeightLimit()) {
                        player.sendMessage(localeService.getString(GameMessagesLocaleImpl.MATCH_CANNOT_PLACE_BLOCKS_ABOVE_HEIGHT_LIMIT));
                        event.setCancelled(true);
                        return;
                    }

                    //TODO: make this check configurable in settings config
                    // 待办：将此检查改为可在设置配置中配置
                    if ((arena.getTeam1Portal() != null && blockLocation.distance(arena.getTeam1Portal()) <= arena.getPortalRadius()) ||
                            (arena.getTeam2Portal() != null && blockLocation.distance(arena.getTeam2Portal()) <= arena.getPortalRadius())) {
                        player.sendMessage(localeService.getString(GameMessagesLocaleImpl.MATCH_CANNOT_BUILD_NEAR_PORTAL));
                        event.setCancelled(true);
                        return;
                    }
                }

                if (match.getKit().isSettingEnabled(KitSettingBuildImpl.class)) {
                    if (match.getKit().isSettingEnabled(KitSettingRaiding.class)) {
                        GameParticipant<MatchGamePlayer> participant = match.getParticipant(player);
                        if (participant.getLeader().getData().getRole() == BaseRaiderRole.TRAPPER) {
                            match.addBlockToPlacedBlocksMap(placedBlock.getState(), placedBlock.getLocation());
                        } else {
                            event.setCancelled(true);
                        }
                        return;
                    }

                    if (match.getKit().isSettingEnabled(KitSettingHideAndSeek.class)) {
                        HideAndSeekMatch matchHideAndSeek = (HideAndSeekMatch) profile.getMatch();
                        GameParticipant<MatchGamePlayer> seekers = matchHideAndSeek.getParticipantA();

                        boolean isSeeker = seekers.containsPlayer(player.getUniqueId());

                        if (matchHideAndSeek.getGameEndTask() == null && isSeeker) {
                            player.sendMessage(localeService.getString(GameMessagesLocaleImpl.MATCH_CANNOT_PLACE_BLOCKS_DURING_HIDING_PHASE));
                            event.setCancelled(true);
                            return;
                        }
                    }

                    match.addBlockToPlacedBlocksMap(placedBlock.getState(), event.getBlockPlaced().getLocation());

                    handleTimedBlockPlacement(event, match, profileService);
                    return;
                }

                event.setCancelled(true);
                break;
            case SPECTATING:
                event.setCancelled(true);
                break;
        }
    }

    @EventHandler
    private void onProjectileHit(ProjectileHitEvent event) {
        if (event.getEntityType() != EntityType.SNOWBALL || event.getHitBlock() == null) {
            return;
        }

        Block hitBlock = event.getHitBlock();
        if (hitBlock.getType() != Material.SNOW && hitBlock.getType() != Material.SNOW_BLOCK) {
            return;
        }

        if (!(event.getEntity().getShooter() instanceof Player)) {
            return;
        }

        Player player = (Player) event.getEntity().getShooter();
        ProfileService profileService = AlleyPlugin.getInstance().getService(ProfileService.class);
        Profile profile = profileService.getProfile(player.getUniqueId());

        if (profile.getState() != ProfileState.PLAYING) return;
        if (profile.getMatch().getState() != MatchState.RUNNING) return;
        if (!profile.getMatch().getKit().isSettingEnabled(KitSettingSpleef.class)) return;

        profile.getMatch().addBlockToBrokenBlocksMap(hitBlock.getState(), hitBlock.getLocation());
        hitBlock.setType(Material.AIR);
    }

    @EventHandler
    private void onPlayerInteract(PlayerInteractEvent event) {
        Player player = event.getPlayer();
        ProfileService profileService = AlleyPlugin.getInstance().getService(ProfileService.class);
        Profile profile = profileService.getProfile(player.getUniqueId());

        if (profile.getState() == ProfileState.SPECTATING) {
            Block block = event.getClickedBlock();
            if (block == null || block.getType() == Material.AIR) return;
            event.setCancelled(true);
            return;
        }

        // Prevent farmland trampling during matches
        if (profile.getState() == ProfileState.PLAYING
                && event.getAction() == Action.PHYSICAL
                && event.getClickedBlock() != null
                && event.getClickedBlock().getType() == Material.FARMLAND) {
            event.setCancelled(true);
        }
    }

    @EventHandler
    private void onBedEnter(PlayerBedEnterEvent event) {
        Profile profile = AlleyPlugin.getInstance().getService(ProfileService.class).getProfile(event.getPlayer().getUniqueId());
        if (profile == null || profile.getState() != ProfileState.PLAYING || profile.getMatch() == null) return;
        if (profile.getMatch().getKit().isSettingEnabled(KitSettingBed.class)) {
            event.setCancelled(true);
        }
    }

    private void removeBed(Match match, Block bedBlock) {
        BlockState bedState = bedBlock.getState();
        Block otherPart = null;
        if (bedBlock.getBlockData() instanceof org.bukkit.block.data.type.Bed bedData) {
            otherPart = bedBlock.getRelative(bedData.getPart() == org.bukkit.block.data.type.Bed.Part.HEAD
                    ? bedData.getFacing().getOppositeFace()
                    : bedData.getFacing());
        }

        match.addBlockToBrokenBlocksMap(bedState, bedBlock.getLocation());
        if (otherPart != null && otherPart.getType().name().endsWith("_BED")) {
            match.addBlockToBrokenBlocksMap(otherPart.getState(), otherPart.getLocation());
            otherPart.setType(Material.AIR, false);
        }
        bedBlock.setType(Material.AIR, false);
    }

    @EventHandler
    private void onDoorOpen(PlayerInteractEvent event) {
        Player player = event.getPlayer();
        LocaleService localeService = AlleyPlugin.getInstance().getService(LocaleService.class);
        ProfileService profileService = AlleyPlugin.getInstance().getService(ProfileService.class);
        Profile profile = profileService.getProfile(player.getUniqueId());

        if (profile.getState() != ProfileState.PLAYING) return;
        if (profile.getMatch() == null) return;
        if (profile.getMatch().getKit().isSettingEnabled(KitSettingRaiding.class)) {
            Block block = event.getClickedBlock();
            if (block == null || block.getType() == Material.AIR) return;

            Action action = event.getAction();
            if (action == Action.RIGHT_CLICK_BLOCK) {
                if (block.getType() == Material.OAK_SIGN || block.getType() == Material.OAK_WALL_SIGN) {
                    return;
                }

                GameParticipant<MatchGamePlayer> participant = profile.getMatch().getParticipant(player);
                if (participant.getLeader().getData().getRole() == BaseRaiderRole.RAIDER && ListenerUtil.isInteractiveBlock(block.getType())) {
                    if (event.getPlayer().isSneaking() && event.getItem().getType() == Material.ENDER_PEARL && event.getAction() == Action.RIGHT_CLICK_BLOCK && event.getClickedBlock().getType().name().contains("FENCE")) {
                        return;
                    }
                    event.setCancelled(true);
                    player.sendMessage(localeService.getString(GameMessagesLocaleImpl.MATCH_CANNOT_INTERACT_AS_RAIDER));
                }
            }
        } else {
            if (event.getClickedBlock() == null) {
                return;
            }

            if (ListenerUtil.isInteractiveBlock(event.getClickedBlock().getType())) {
                event.setCancelled(true);
                player.sendMessage(localeService.getString(GameMessagesLocaleImpl.MATCH_CANNOT_INTERACT_DURING_MATCH));
            }
        }
    }

    @EventHandler
    public void onSign(SignChangeEvent event) {
        String[] lines = event.getLines();

        int elevatorLineIndex = findElevatorLine(lines);
        if (elevatorLineIndex == -1 || elevatorLineIndex >= 3) {
            return;
        }

        String direction = lines[elevatorLineIndex + 1];
        if (!isValidDirection(direction)) {
            breakSignAndNotifyPlayer(event);
            return;
        }

        createElevatorSign(event, direction);
    }

    @EventHandler
    public void onSignInteract(PlayerInteractEvent event) {
        Block clickedBlock = event.getClickedBlock();
        if (clickedBlock == null || !isSignBlock(clickedBlock)) {
            return;
        }

        if (event.getAction() != Action.RIGHT_CLICK_BLOCK) {
            return;
        }

        Sign sign = (Sign) clickedBlock.getState();
        if (!isElevatorSign(sign)) {
            return;
        }

        String direction = sign.getLine(2);
        Player player = event.getPlayer();

        if (direction.equalsIgnoreCase("Up")) {
            teleportUp(player, clickedBlock.getLocation());
        } else if (direction.equalsIgnoreCase("Down")) {
            teleportDown(player, clickedBlock.getLocation());
        }
    }

    private int findElevatorLine(String[] lines) {
        for (int i = 0; i < lines.length; i++) {
            if (lines[i].equalsIgnoreCase("[Elevator]")) {
                return i;
            }
        }
        return -1;
    }

    private boolean isValidDirection(String direction) {
        return direction != null && (direction.equalsIgnoreCase("Up") || direction.equalsIgnoreCase("Down"));
    }

    private void breakSignAndNotifyPlayer(SignChangeEvent event) {
        event.getBlock().breakNaturally();
        event.getPlayer().sendMessage(CC.translate("&cInvalid direction."));
    }

    private void createElevatorSign(SignChangeEvent event, String direction) {
        event.setLine(0, "");
        event.setLine(1, ChatColor.translateAlternateColorCodes('&', "&c[Elevator]"));
        event.setLine(2, ChatColor.translateAlternateColorCodes('&', direction));
        event.setLine(3, "");
    }

    private boolean isSignBlock(Block block) {
        Material type = block.getType();
        return type == Material.OAK_WALL_SIGN || type == Material.OAK_SIGN;
    }

    private boolean isElevatorSign(Sign sign) {
        return sign.getLine(1).equalsIgnoreCase(ChatColor.RED + "[Elevator]");
    }

    private void teleportUp(Player player, Location signLocation) {
        Location searchLocation = signLocation.clone().add(0.0, 1.0, 0.0);

        while (searchLocation.getY() < 254.0) {
            if (searchLocation.getBlock().getType() != Material.AIR) {
                Location safeSpot = findSafeSpotAbove(searchLocation);
                if (safeSpot != null) {
                    teleportPlayerToLocation(player, safeSpot);
                    return;
                }
            }
            searchLocation.add(0.0, 1.0, 0.0);
        }

        LocaleService localeService = AlleyPlugin.getInstance().getService(LocaleService.class);
        player.sendMessage(localeService.getString(GameMessagesLocaleImpl.MATCH_ELEVATOR_NO_SAFE_SPOT_FOUND));
    }

    private void teleportDown(Player player, Location signLocation) {
        Location searchLocation = signLocation.clone().subtract(0.0, 1.0, 0.0);

        while (searchLocation.getY() > 2.0) {
            if (searchLocation.getBlock().getType() != Material.AIR) {
                Location safeSpot = findSafeSpotBelow(searchLocation);
                if (safeSpot != null) {
                    teleportPlayerToLocation(player, safeSpot);
                    return;
                }
            }
            searchLocation.subtract(0.0, 1.0, 0.0);
        }

        LocaleService localeService = AlleyPlugin.getInstance().getService(LocaleService.class);
        player.sendMessage(localeService.getString(GameMessagesLocaleImpl.MATCH_ELEVATOR_NO_SAFE_SPOT_FOUND));
    }

    private Location findSafeSpotAbove(Location startLocation) {
        Location checkLocation = startLocation.clone();

        while (checkLocation.getY() < 254) {
            Location aboveLocation = checkLocation.clone().add(0.0, 1.0, 0.0);

            if (checkLocation.getBlock().getType() == Material.AIR &&
                    aboveLocation.getBlock().getType() == Material.AIR) {
                return checkLocation;
            }

            checkLocation.add(0.0, 1.0, 0.0);
        }

        return null;
    }

    private Location findSafeSpotBelow(Location startLocation) {
        Location checkLocation = startLocation.clone();

        while (checkLocation.getY() > 2.0) {
            Location belowLocation = checkLocation.clone().subtract(0.0, 1.0, 0.0);

            if (checkLocation.getBlock().getType() == Material.AIR &&
                    belowLocation.getBlock().getType() == Material.AIR) {
                return checkLocation;
            }

            checkLocation.subtract(0.0, 1.0, 0.0);
        }

        return null;
    }

    private void teleportPlayerToLocation(Player player, Location targetLocation) {
        Location playerLocation = player.getLocation();
        Location teleportLocation = new Location(
                targetLocation.getWorld(),
                targetLocation.getX() + 0.5,
                targetLocation.getY(),
                targetLocation.getZ() + 0.5,
                playerLocation.getYaw(),
                playerLocation.getPitch()
        );

        player.teleport(teleportLocation);
    }

    private Match findBuildMatchTracking(Location location) {
        MatchService matchService = AlleyPlugin.getInstance().getService(MatchService.class);
        for (Match match : matchService.getMatches()) {
            if (match.getState() != MatchState.RUNNING) continue;
            if (!match.getKit().isSettingEnabled(KitSettingBuildImpl.class)) continue;
            if (match.getPlacedBlocks().containsValue(location)) return match;
        }
        return null;
    }

    /**
     * Handles the logic for blocks that disappear after a set time.
     * 处理在设定时间后消失的方块的逻辑。
     * This is triggered from the onBlockPlace event.
     * 此方法由 onBlockPlace 事件触发。
     *
     * @param event          The BlockPlaceEvent.
     *                      方块放置事件。
     * @param match          The current match.
     *                       当前比赛。
     * @param profileService The profile service instance.
     *                       玩家资料服务实例。
     */
    private void handleTimedBlockPlacement(BlockPlaceEvent event, Match match, ProfileService profileService) {
        if (!match.getKit().isSettingEnabled(KitSettingTimedBlocksImpl.class)) {
            return;
        }

        Block placedBlock = event.getBlockPlaced();
        final BlockState originalState = placedBlock.getState();
        Material type = placedBlock.getType();

        if (!type.name().endsWith("_WOOL") && !type.name().endsWith("_TERRACOTTA")) {
            return;
        }

        final Player player = event.getPlayer();
        final int DURATION_TICKS = 100;

        BlockAnimationReflectionServiceImpl animationService = AlleyPlugin.getInstance().getService(ReflectionService.class).getReflectionService(BlockAnimationReflectionServiceImpl.class);
        int animationId = placedBlock.getLocation().hashCode();

        List<Player> playersInMatch = match.getParticipants().stream()
                .flatMap(participant -> participant.getPlayers().stream())
                .map(MatchGamePlayer::getTeamPlayer)
                .filter(p -> p != null && p.isOnline())
                .collect(Collectors.toList());

        animationService.sendBreakAnimationSequence(playersInMatch, placedBlock, animationId, DURATION_TICKS);

        ItemStack itemToReturn = event.getItemInHand().clone();
        itemToReturn.setAmount(1);

        new BukkitRunnable() {
            @Override
            public void run() {
                if (!match.getPlacedBlocks().containsKey(originalState)) {
                    return;
                }

                Profile currentProfile = profileService.getProfile(player.getUniqueId());
                boolean playerIsStillPlaying = player.getGameMode() == GameMode.SURVIVAL && currentProfile != null && currentProfile.getMatch() == match && currentProfile.getState() == ProfileState.PLAYING && match.getState() == MatchState.RUNNING;

                if (placedBlock.getLocation().getBlock().getType() == type) {
                    playersInMatch.forEach(p -> animationService.sendBreakAnimation(p, placedBlock, animationId, -1));

                    placedBlock.setType(Material.AIR);
                    match.removeBlockFromPlacedBlocksMap(placedBlock.getState(), placedBlock.getLocation());

                    if (playerIsStillPlaying) {
                        for (int i = 0; i < player.getInventory().getSize(); i++) {
                            ItemStack itemInSlot = player.getInventory().getItem(i);
                            if (itemInSlot != null && itemInSlot.isSimilar(itemToReturn)) {
                                if (itemInSlot.getAmount() < itemInSlot.getMaxStackSize()) {
                                    itemInSlot.setAmount(itemInSlot.getAmount() + 1);
                                    return;
                                }
                            }
                        }
                    }
                }
            }
        }.runTaskLater(AlleyPlugin.getInstance(), DURATION_TICKS);
    }
}
