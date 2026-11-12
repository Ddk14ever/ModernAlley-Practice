package dev.revere.alley.feature.match.listener;

import dev.revere.alley.AlleyPlugin;
import dev.revere.alley.common.ListenerUtil;
import dev.revere.alley.common.text.CC;
import dev.revere.alley.core.locale.LocaleService;
import dev.revere.alley.core.locale.internal.impl.message.GameMessagesLocaleImpl;
import dev.revere.alley.core.profile.Profile;
import dev.revere.alley.core.profile.ProfileService;
import dev.revere.alley.core.profile.enums.ProfileState;
import dev.revere.alley.feature.arena.ArenaType;
import dev.revere.alley.feature.arena.internal.types.StandAloneArena;
import dev.revere.alley.feature.bot.BotService;
import dev.revere.alley.feature.bot.match.BotMatchSession;
import dev.revere.alley.feature.kit.Kit;
import dev.revere.alley.feature.knockback.KnockbackManager;
import dev.revere.alley.feature.kit.setting.types.mechanic.KitSettingDenyMovementImpl;
import dev.revere.alley.feature.kit.setting.types.mechanic.KitSettingNoHungerImpl;
import dev.revere.alley.feature.kit.setting.types.mechanic.KitSettingVoidDeathImpl;
import dev.revere.alley.feature.kit.setting.types.mode.*;
import dev.revere.alley.feature.match.Match;
import dev.revere.alley.feature.match.MatchState;
import dev.revere.alley.feature.match.internal.types.RoundsMatch;
import dev.revere.alley.feature.match.model.GameParticipant;
import dev.revere.alley.feature.match.model.internal.MatchGamePlayer;
import dev.revere.alley.feature.match.utility.MatchUtility;
import dev.revere.alley.feature.match.utility.MatchResultFlight;
import dev.revere.alley.library.menu.Menu;
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.damage.DamageSource;
import org.bukkit.damage.DamageType;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.EntityPickupItemEvent;
import org.bukkit.event.entity.FoodLevelChangeEvent;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.player.*;
import org.bukkit.inventory.ItemStack;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Match listener for handling match-related events.
 * 比赛监听器，用于处理比赛相关的事件。
 * @author Remi
 * @project Alley
 * @date 5/21/2024
 */
public class MatchListener implements Listener {
    private static final Set<UUID> DEAD_PICKUP_BLOCKED = ConcurrentHashMap.newKeySet();

    public static void blockDeadPlayerPickup(Player player) {
        if (player != null) DEAD_PICKUP_BLOCKED.add(player.getUniqueId());
    }

    public static void clearDeadPlayerPickupBlock(Player player) {
        if (player != null) DEAD_PICKUP_BLOCKED.remove(player.getUniqueId());
    }

    @EventHandler
    private void onTeleport(PlayerTeleportEvent event) {
        Player player = event.getPlayer();
        if (AlleyPlugin.getInstance().getService(BotService.class).getSession((Entity) player) != null) return;
        ProfileService profileService = AlleyPlugin.getInstance().getService(ProfileService.class);
        Profile profile = profileService.getProfile(player.getUniqueId());
        if (profile.getState() == ProfileState.SPECTATING || profile.getState() == ProfileState.PLAYING) {
            if (event.getCause() == PlayerTeleportEvent.TeleportCause.ENDER_PEARL) {
                if (MatchUtility.isBeyondBounds(event.getTo(), profile)) {
                    event.setCancelled(true);
                    player.sendMessage(CC.translate("&cYou cannot leave the arena."));
                }
            }
        }
    }

    @EventHandler
    private void onPlayerMove(PlayerMoveEvent event) {
        Player player = event.getPlayer();
        ProfileService profileService = AlleyPlugin.getInstance().getService(ProfileService.class);
        Profile profile = profileService.getProfile(player.getUniqueId());
        Match match = profile.getMatch();
        if (match == null) return;

        Kit matchKit = match.getKit();
        if (profile.getState() == ProfileState.PLAYING && profile.getMatch().getState() == MatchState.RUNNING) {
            if (matchKit.isSettingEnabled(KitSettingSumo.class) || matchKit.isSettingEnabled(KitSettingSpleef.class)) {
                if (player.getLocation().getBlock().getType() == Material.WATER || player.getLocation().getBlock().getType() == Material.WATER) {
                    eliminateMatchPlayer(player, match, EntityDamageEvent.DamageCause.CUSTOM);
                }
            }

            if (match.getArena() instanceof StandAloneArena) {
                StandAloneArena arena = (StandAloneArena) match.getArena();
                if (player.getLocation().getY() <= arena.getVoidLevel() && matchKit.isSettingEnabled(KitSettingVoidDeathImpl.class)) {
                    if (player.getGameMode() == GameMode.SPECTATOR) return;
                    if (player.getGameMode() == GameMode.CREATIVE) return;
                    if (match.getArena().getType() != ArenaType.STANDALONE) return;
                    if (profile.getState() != ProfileState.PLAYING) return;

                    if (match.getKit().isSettingEnabled(KitSettingStickFight.class)) {
                        RoundsMatch roundsMatch = (RoundsMatch) match;
                        roundsMatch.handleDeath(player, EntityDamageEvent.DamageCause.VOID);
                        return;
                    }

                    profile.getMatch().handleDeath(player, EntityDamageEvent.DamageCause.VOID);
                }
            }
        }

        if (profile.getState() == ProfileState.PLAYING) {
            if (match.getState() == MatchState.STARTING || match.getState() == MatchState.ENDING_ROUND || match.getState() == MatchState.RESTARTING_ROUND) {
                boolean humanInBotMatch = AlleyPlugin.getInstance().getService(BotService.class)
                        .getSession(player) != null;
                if (!humanInBotMatch && matchKit.isSettingEnabled(KitSettingDenyMovementImpl.class)) {
                    List<GameParticipant<MatchGamePlayer>> participants = match.getParticipants();
                    match.denyPlayerMovement(participants);
                }
            }
        }

        if (profile.getState() == ProfileState.PLAYING) {
            if (profile.getMatch() == null) {
                return;
            }

            if (MatchUtility.isBeyondBounds(event.getTo(), profile)) {
                // player.teleport(event.getFrom());
                // player.sendMessage(CC.translate("&cYou cannot leave the arena."));
            }
        }
    }

    @EventHandler
    private void onPlayerRespawn(PlayerRespawnEvent event) {
        Player player = event.getPlayer();
        ProfileService profileService = AlleyPlugin.getInstance().getService(ProfileService.class);
        Profile profile = profileService.getProfile(player.getUniqueId());
        if (profile != null && profile.getState() == ProfileState.PLAYING) {
            event.setRespawnLocation(player.getLocation());
        }

        if (MatchResultFlight.isPending(player)) {
            AlleyPlugin.getInstance().getServer().getScheduler().runTask(
                    AlleyPlugin.getInstance(), () -> MatchResultFlight.apply(player));
        }

        AlleyPlugin.getInstance().getServer().getScheduler().runTask(
                AlleyPlugin.getInstance(), () -> {
                    if (MatchResultFlight.isPending(player)) return;

                    Profile currentProfile = AlleyPlugin.getInstance().getService(ProfileService.class)
                            .getProfile(player.getUniqueId());
                    Match currentMatch = currentProfile == null ? null : currentProfile.getMatch();
                    MatchGamePlayer gamePlayer = currentMatch == null
                            ? null : currentMatch.getFromAllGamePlayers(player);
                    if (currentMatch != null && player.getGameMode() == GameMode.SPECTATOR) {
                        dev.revere.alley.feature.match.utility.MatchRespawnHealth.restore(player);
                    }
                    if (gamePlayer != null && (gamePlayer.isDead() || gamePlayer.isEliminated())) return;

                    clearDeadPlayerPickupBlock(player);
                });
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    private void onPartyPlayerLethalDamage(EntityDamageEvent event) {
        if (!(event.getEntity() instanceof Player player)) return;

        Profile profile = AlleyPlugin.getInstance().getService(ProfileService.class).getProfile(player.getUniqueId());
        if (profile == null || profile.getState() != ProfileState.PLAYING || profile.getMatch() == null) return;
        if (AlleyPlugin.getInstance().getService(KnockbackManager.class)
                .wasInsideHurtResistanceWindow(player)) return;
        if (event.getFinalDamage() < player.getHealth()) return;
        if (player.getInventory().getItemInMainHand().getType() == Material.TOTEM_OF_UNDYING
                || player.getInventory().getItemInOffHand().getType() == Material.TOTEM_OF_UNDYING) return;

        if (handlePartyElimination(player, profile.getMatch(), event.getCause(), event.getDamageSource())) {
            event.setCancelled(true);
        }
    }

    /**
     * Intercepts lethal match damage before the native death pipeline and converts it
     * into a synthetic death: the player stays alive the entire time, so the client
     * never shows the death screen or the world-loading (dirt) animation that a real
     * respawn triggers. Everything that used to run from PlayerDeathEvent (item drops,
     * death messages, cosmetics, weapon/grapple cleanup, snapshots, match resolution,
     * spectator/round-respawn bookkeeping) is dispatched through a synthetic
     * PlayerDeathEvent here, then the victim's health is restored.
     */
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    private void onMatchLethalDamage(EntityDamageEvent event) {
        if (!(event.getEntity() instanceof Player player)) return;

        Profile profile = AlleyPlugin.getInstance().getService(ProfileService.class)
                .getProfile(player.getUniqueId());
        if (profile == null || profile.getState() != ProfileState.PLAYING || profile.getMatch() == null) return;
        // Native NPC bots have no client, so the vanilla death pipeline is fine for
        // them; only real players need the seamless (no-respawn) transition.
        BotMatchSession botSession = AlleyPlugin.getInstance().getService(BotService.class)
                .getSession((Entity) player);
        if (botSession != null && !player.getUniqueId().equals(botSession.getPlayerId())) return;
        // Inside the legacy hurt window NMS rejects hits that do not exceed the last
        // damage; only a larger (damage-delta) hit can still kill and must be caught.
        if (AlleyPlugin.getInstance().getService(KnockbackManager.class)
                .wasInsideHurtResistanceWindow(player)
                && event.getFinalDamage() <= player.getLastDamage()) return;
        // Absorption hearts are consumed first, so a hit is only lethal past them.
        if (event.getFinalDamage() < player.getHealth() + player.getAbsorptionAmount()) return;
        if (player.getInventory().getItemInMainHand().getType() == Material.TOTEM_OF_UNDYING
                || player.getInventory().getItemInOffHand().getType() == Material.TOTEM_OF_UNDYING) return;

        Match match = profile.getMatch();
        // Party matches keep the victim alive through the existing elimination path.
        if (handlePartyElimination(player, match, event.getCause(), event.getDamageSource())) {
            event.setCancelled(true);
            return;
        }

        // Cancel the lethal hit so health never reaches zero, and leave the next hit
        // immediately acceptable for respawn-window kits (stale i-frames must not
        // swallow the first attack after teleporting back to spawn).
        event.setCancelled(true);
        player.setNoDamageTicks(0);
        dispatchSyntheticDeath(player, event.getDamageSource(), event.getCause());
    }

    /**
     * Runs the seamless death flow for scripted eliminations (sumo/spleef water,
     * wrong portal) that used to call {@code player.setHealth(0)} and therefore
     * showed the vanilla death screen and world reload.
     */
    private boolean eliminateMatchPlayer(Player player, Match match,
                                          EntityDamageEvent.DamageCause cause) {
        if (handlePartyElimination(player, match, cause)) return true;

        MatchGamePlayer gamePlayer = match.getFromAllGamePlayers(player);
        if (gamePlayer == null || gamePlayer.isDead() || gamePlayer.isEliminated()) return true;

        dispatchSyntheticDeath(player, null, cause);
        return true;
    }

    /**
     * Fires a synthetic PlayerDeathEvent so every existing death listener runs without
     * the player actually dying, then restores health and clears the dead-state entries.
     */
    private void dispatchSyntheticDeath(Player player, DamageSource damageSource,
                                        EntityDamageEvent.DamageCause cause) {
        Profile deathProfile = AlleyPlugin.getInstance().getService(ProfileService.class)
                .getProfile(player.getUniqueId());
        if (deathProfile != null && deathProfile.getMatch() != null) {
            deathProfile.getMatch().playDeathFeedback(player, damageSource);
        }
        // PlayerInventory#getContents already covers storage, armour and off-hand,
        // mirroring the item set a vanilla death event would expose.
        List<ItemStack> drops = new ArrayList<>();
        for (ItemStack stack : player.getInventory().getContents()) {
            if (stack != null && !stack.getType().isAir()) drops.add(stack.clone());
        }

        DamageSource source = damageSource != null
                ? damageSource
                : DamageSource.builder(DamageType.GENERIC).withDirectEntity(player).build();
        // Death messages resolve their cause from the victim's last damage event.
        player.setLastDamageCause(new EntityDamageEvent(player, cause, source, 0.0D));
        // The drop list is a copy: match handlers either drop it or clear it, exactly
        // like the vanilla death event would.
        PlayerDeathEvent synthetic = new PlayerDeathEvent(player, source, drops, 0, "");

        // Run every existing death listener while the inventory is still intact so the
        // post-match snapshot captures the real loadout.
        Bukkit.getPluginManager().callEvent(synthetic);

        // Simulate the vanilla death inventory wipe. The victim is still alive, so the
        // server never does this for us.
        player.getInventory().clear();
        player.getInventory().setArmorContents(new ItemStack[4]);
        player.getInventory().setItemInOffHand(new ItemStack(Material.AIR));

        if (player.isOnline()) {
            player.setHealth(player.getMaxHealth());
            player.setAbsorptionAmount(0.0D);
        }
        player.setFireTicks(0);
        player.setFallDistance(0.0F);
        player.setNoDamageTicks(0);

        // The respawn-time pickup block is normally cleared from PlayerRespawnEvent,
        // which no longer fires. Clear it once the victim is actually back in play.
        Bukkit.getScheduler().runTaskLater(AlleyPlugin.getInstance(), () -> {
            if (!player.isOnline()) return;
            Profile profile = AlleyPlugin.getInstance().getService(ProfileService.class)
                    .getProfile(player.getUniqueId());
            Match currentMatch = profile == null ? null : profile.getMatch();
            MatchGamePlayer gamePlayer = currentMatch == null
                    ? null : currentMatch.getFromAllGamePlayers(player);
            if (gamePlayer != null && gamePlayer.isDead() && !gamePlayer.isEliminated()) return;
            clearDeadPlayerPickupBlock(player);
        }, 5L);
    }

    @EventHandler
    private void onPlayerDeath(PlayerDeathEvent event) {
        Player player = event.getEntity();

        ProfileService profileService = AlleyPlugin.getInstance().getService(ProfileService.class);
        Profile profile = profileService.getProfile(player.getUniqueId());
        BotMatchSession botSession = AlleyPlugin.getInstance().getService(BotService.class)
                .getSession((Entity) player);
        boolean nativeBot = botSession != null
                && !player.getUniqueId().equals(botSession.getPlayerId());
        if (!nativeBot && ((profile != null && profile.getMatch() != null)
                || MatchResultFlight.isPending(player))) {
            blockDeadPlayerPickup(player);
        }
        if (profile == null || profile.getState() != ProfileState.PLAYING || profile.getMatch() == null) return;

        event.setDeathMessage(null);

        profile.getMatch().handleDeathItemDrop(player, event);

        EntityDamageEvent.DamageCause cause = player.getLastDamageCause() != null ? player.getLastDamageCause().getCause() : EntityDamageEvent.DamageCause.CUSTOM;
        // Use the Entity overload so a dying bot (registered under entitySessions only) resolves
        // to its session as well. Using the Player overload made bot deaths fall through to the
        // regular match death path (Match#handleDeath -> handleRoundEnd), publishing a second,
        // conflicting match result alongside the NPCDeathEvent path.
        // 使用Entity重载，让死亡的bot（仅注册在entitySessions中）也能解析到其会话。
        // 使用Player重载会使bot死亡落入常规比赛死亡路径（Match#handleDeath -> handleRoundEnd），
        // 与NPCDeathEvent路径一起发布第二次结果，导致比赛结果重复且胜败者不一致。
        if (botSession != null) {
            botSession.handleNaturalDeath(player);
            if (player.getUniqueId().equals(botSession.getPlayerId())) {
                AlleyPlugin.getInstance().getServer().getScheduler().runTaskLater(
                        AlleyPlugin.getInstance(), player.spigot()::respawn, 1L);
            }
            return;
        }

        profile.getMatch().handleDeath(player, cause);

        // Safety net only. Normal match deaths are intercepted in onMatchLethalDamage
        // (synthetic death, player stays alive), so this never runs for them. It
        // rescues the rare genuine death (e.g. an external plugin or an unhandled
        // damage path) so the player cannot be stuck on the death screen.
        AlleyPlugin.getInstance().getServer().getScheduler().runTaskLater(AlleyPlugin.getInstance(), () -> {
            if (player.isDead()) player.spigot().respawn();
        }, 1L);
    }

    @EventHandler
    public void onInventoryClick(InventoryClickEvent event) {
        Player player = (Player) event.getWhoClicked();
        if (event.getClickedInventory() == null) return;

        ProfileService profileService = AlleyPlugin.getInstance().getService(ProfileService.class);
        Profile profile = profileService.getProfile(player.getUniqueId());
        if (profile.getState() == ProfileState.SPECTATING) {
            if (!Menu.currentlyOpenedMenus.containsKey(player.getName())) {
                event.setCancelled(true);
            }
        }
    }

    @EventHandler
    private void onPlayerDropItem(PlayerDropItemEvent event) {
        Player player = event.getPlayer();
        ProfileService profileService = AlleyPlugin.getInstance().getService(ProfileService.class);
        Profile profile = profileService.getProfile(player.getUniqueId());

        if (profile.getState() == ProfileState.SPECTATING) {
            event.setCancelled(true);
            return;
        }

        if (profile.getState() == ProfileState.PLAYING) {
            if (ListenerUtil.isSword(event.getItemDrop().getItemStack().getType())) {
                event.setCancelled(true);
                player.sendMessage(AlleyPlugin.getInstance().getService(LocaleService.class).getString(GameMessagesLocaleImpl.GAME_CANNOT_DROP_SWORD));
                return;
            }
            // A dropped glass bottle is leftover from drinking; remove it on the next
            // tick instead of letting it linger on the arena floor for five seconds.
            if (event.getItemDrop().getItemStack().getType() == Material.GLASS_BOTTLE) {
                org.bukkit.entity.Item bottle = event.getItemDrop();
                Bukkit.getScheduler().runTask(AlleyPlugin.getInstance(), () -> {
                    if (bottle.isValid()) bottle.remove();
                });
                return;
            }
        }
        ListenerUtil.clearDroppedItemsOnRegularItemDrop(event.getItemDrop());
    }

    @EventHandler
    private void onPlayerPickupItem(EntityPickupItemEvent event) {
        if (!(event.getEntity() instanceof Player player)) return;

        ProfileService profileService = AlleyPlugin.getInstance().getService(ProfileService.class);
        Profile profile = profileService.getProfile(player.getUniqueId());
        if (DEAD_PICKUP_BLOCKED.contains(player.getUniqueId())) {
            event.setCancelled(true);
            return;
        }
        if (profile == null) return;
        if (profile.getState() == ProfileState.SPECTATING) {
            event.setCancelled(true);
            return;
        }

        Match match = profile.getMatch();
        MatchGamePlayer gamePlayer = match == null ? null : match.getFromAllGamePlayers(player);
        if (gamePlayer != null && (gamePlayer.isDead() || gamePlayer.isEliminated())) {
            event.setCancelled(true);
        }
    }

    @EventHandler
    private void onQuit(PlayerQuitEvent event) {
        clearDeadPlayerPickupBlock(event.getPlayer());
    }

    @EventHandler
    private void onPlayerItemConsume(PlayerItemConsumeEvent event) {
        Player player = event.getPlayer();
        ProfileService profileService = AlleyPlugin.getInstance().getService(ProfileService.class);
        Profile profile = profileService.getProfile(player.getUniqueId());
        if (profile.getState() != ProfileState.PLAYING) {
            return;
        }

        ItemStack item = event.getItem();
        if (item.getType() == Material.POTION) {
            // Vanilla inserts the empty bottle after this event. Remember how many the
            // player already had so only the newly produced bottle is removed.
            scheduleGlassBottleCleanup(player, countGlassBottles(player));
        }
    }

    /**
     * Removes the empty bottle produced by drinking, checking twice because some
     * server implementations insert it after the consume event has finished.
     * Only runs when the bottle count actually grew, so pre-existing bottles are
     * never eaten.
     */
    public static void scheduleGlassBottleCleanup(Player player, int bottlesBefore) {
        for (long delay : new long[]{1L, 2L}) {
            Bukkit.getScheduler().runTaskLater(AlleyPlugin.getInstance(), () -> {
                if (!player.isOnline()) return;
                if (countGlassBottles(player) > bottlesBefore) {
                    removeGlassBottles(player);
                }
                // Safety net for a full inventory: vanilla drops the bottle instead.
                removeDroppedGlassBottlesNear(player);
                player.updateInventory();
            }, delay);
        }
    }

    /** Counts every glass bottle the player currently carries (inventory + off-hand). */
    public static int countGlassBottles(Player player) {
        int total = 0;
        for (ItemStack stack : player.getInventory().getContents()) {
            if (stack != null && stack.getType() == Material.GLASS_BOTTLE) total += stack.getAmount();
        }
        return total;
    }

    /**
     * Removes a single glass bottle from the player's inventory without touching stacks
     * of anything else. Used as the post-potion cleanup for match players.
     */
    public static void removeGlassBottles(Player player) {
        ItemStack bottle = new ItemStack(Material.GLASS_BOTTLE, 1);
        if (player.getInventory().removeItem(bottle).isEmpty()) {
            return;
        }
        // Off-hand is not covered by the storage-inventory removal above.
        ItemStack offHand = player.getInventory().getItemInOffHand();
        if (offHand != null && offHand.getType() == Material.GLASS_BOTTLE) {
            if (offHand.getAmount() > 1) offHand.setAmount(offHand.getAmount() - 1);
            else player.getInventory().setItemInOffHand(new ItemStack(Material.AIR));
        }
    }

    /**
     * Vanilla drops the empty bottle as an item entity when the inventory is full.
     * Remove those fresh bottle drops so they do not linger on the arena floor.
     */
    public static void removeDroppedGlassBottlesNear(Player player) {
        for (Entity entity : player.getNearbyEntities(3.0D, 3.0D, 3.0D)) {
            if (entity instanceof org.bukkit.entity.Item item
                    && item.getItemStack().getType() == Material.GLASS_BOTTLE
                    && item.getTicksLived() <= 10) {
                item.remove();
            }
        }
    }

    @EventHandler
    private void onHunger(FoodLevelChangeEvent event) {
        if (event.getEntity() instanceof Player) {
            Player player = (Player) event.getEntity();
            ProfileService profileService = AlleyPlugin.getInstance().getService(ProfileService.class);
            Profile profile = profileService.getProfile(player.getUniqueId());
            if (profile.getState() != ProfileState.PLAYING) return;

            if (profile.getMatch().getKit().isSettingEnabled(KitSettingNoHungerImpl.class)) {
                event.setCancelled(true);
            }
        }
    }

    @EventHandler
    public void onPortal(PlayerPortalEvent event) {
        Player player = event.getPlayer();
        ProfileService profileService = AlleyPlugin.getInstance().getService(ProfileService.class);
        Profile profile = profileService.getProfile(player.getUniqueId());
        if (profile != null && profile.getState() == ProfileState.PLAYING) {
            if (!(profile.getMatch() instanceof RoundsMatch)) {
                return;
            }

            RoundsMatch match = (RoundsMatch) profile.getMatch();
            if (match.getKit().isSettingEnabled(KitSettingRounds.class) /*|| profile.getMatch().getKit().isSettingEnabled(KitSettingBridgesImpl.class)*/) {
                if (player.getGameMode() == GameMode.CREATIVE) return;
                if (player.getGameMode() == GameMode.SPECTATOR) return;
                if (player.getLocation().getBlock().getType() == Material.END_PORTAL || player.getLocation().getBlock().getType() == Material.END_PORTAL_FRAME) {
                    if (!(match.getArena() instanceof StandAloneArena)) {
                        return;
                    }
                    StandAloneArena arena = (StandAloneArena) match.getArena();
                    GameParticipant<MatchGamePlayer> playerTeam = match.getParticipantA().containsPlayer(player.getUniqueId())
                            ? match.getParticipantA()
                            : match.getParticipantB();

                    if (!arena.isEnemyPortal(match, player.getLocation(), playerTeam)) {
                        player.sendMessage(CC.translate("&cYou cannot enter your own portal!"));

                        if (match.getKit().isSettingEnabled(KitSettingRespawnTimer.class)) {
                            eliminateMatchPlayer(player, match, EntityDamageEvent.DamageCause.CUSTOM);
                            player.setAllowFlight(true);
                            player.setFlying(true);
                            player.setGameMode(GameMode.SPECTATOR);
                        } else {
                            Location spawnLocation = match.getParticipantA().containsPlayer(player.getUniqueId()) ? match.getArena().getPos1() : match.getArena().getPos2();
                            player.teleport(spawnLocation);
                        }
                        return;
                    }

                    if (match.getState() == MatchState.ENDING_ROUND || match.getState() == MatchState.ENDING_MATCH || match.getState() == MatchState.RESTARTING_ROUND) {
                        return;
                    }

                    GameParticipant<MatchGamePlayer> opponent = match.getParticipantA().containsPlayer(player.getUniqueId()) ? match.getParticipantB() : match.getParticipantA();
                    opponent.getPlayers().forEach(matchGamePlayer -> matchGamePlayer.setDead(true));

                    if (match.canEndRound()) {
                        match.setScorer(player.getName());
                        match.handleRoundEnd();

                        if (match.canEndMatch()) {
                            Location spawnLocation = match.getParticipantA().containsPlayer(player.getUniqueId()) ? match.getArena().getPos1() : match.getArena().getPos2();
                            player.teleport(spawnLocation);

                            match.setEndTime(System.currentTimeMillis());
                            match.setState(MatchState.ENDING_MATCH);
                            match.getRunnable().setStage(4);
                        }
                    }
                }
            }
        }
    }

    private boolean handlePartyElimination(Player player, Match match, EntityDamageEvent.DamageCause cause) {
        return handlePartyElimination(player, match, cause, null);
    }

    private boolean handlePartyElimination(Player player, Match match, EntityDamageEvent.DamageCause cause,
                                           DamageSource source) {
        if (!match.isPartyMultiplayerMatch()) return false;
        if (match.getState() != MatchState.STARTING && match.getState() != MatchState.RUNNING) return false;

        MatchGamePlayer gamePlayer = match.getFromAllGamePlayers(player);
        if (gamePlayer == null || gamePlayer.isDead() || gamePlayer.isEliminated()) return true;

        match.playDeathFeedback(player, source);
        player.setHealth(player.getMaxHealth());
        player.setVelocity(new org.bukkit.util.Vector());
        blockDeadPlayerPickup(player);
        match.handleDeath(player, cause);
        return true;
    }
}
