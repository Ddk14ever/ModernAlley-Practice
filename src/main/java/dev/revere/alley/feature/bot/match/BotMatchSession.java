package dev.revere.alley.feature.bot.match;

import dev.revere.alley.AlleyPlugin;
import dev.revere.alley.common.PlayerUtil;
import dev.revere.alley.common.PotionUtil;
import dev.revere.alley.common.text.CC;
import dev.revere.alley.core.profile.Profile;
import dev.revere.alley.core.profile.ProfileService;
import dev.revere.alley.core.profile.enums.ProfileState;
import dev.revere.alley.feature.arena.Arena;
import dev.revere.alley.feature.arena.ArenaService;
import dev.revere.alley.feature.arena.internal.types.StandAloneArena;
import dev.revere.alley.feature.bot.BotAiMode;
import dev.revere.alley.feature.bot.BotDifficultyProfile;
import dev.revere.alley.feature.bot.entity.NativeBotPlayer;
import dev.revere.alley.feature.bot.internal.BotServiceImpl;
import dev.revere.alley.feature.combat.CombatService;
import dev.revere.alley.feature.hotbar.HotbarService;
import dev.revere.alley.feature.hotbar.HotbarType;
import dev.revere.alley.feature.kit.Kit;
import dev.revere.alley.feature.kit.setting.types.combat.KitSettingOldSwordBlocking;
import dev.revere.alley.feature.kit.setting.types.mechanic.KitSettingBreakArenaBlocksImpl;
import dev.revere.alley.feature.kit.setting.types.mechanic.KitSettingBuildImpl;
import dev.revere.alley.feature.kit.setting.types.mechanic.KitSettingDisableSwimmingImpl;
import dev.revere.alley.feature.kit.setting.types.mechanic.KitSettingVoidDeathImpl;
import dev.revere.alley.feature.kit.setting.types.mode.KitSettingSpleef;
import dev.revere.alley.feature.kit.setting.types.mode.KitSettingSumo;
import dev.revere.alley.feature.knockback.KnockbackManager;
import dev.revere.alley.feature.knockback.KnockbackProfile;
import dev.revere.alley.feature.knockback.data.PlayerKnockbackData;
import dev.revere.alley.feature.knockback.listener.PotionMotionListener;
import dev.revere.alley.feature.match.Match;
import dev.revere.alley.feature.match.MatchService;
import dev.revere.alley.feature.match.MatchState;
import dev.revere.alley.feature.match.listener.MatchListener;
import dev.revere.alley.feature.match.combat.legacy.LegacyCombatService;
import dev.revere.alley.feature.match.combat.legacy.LegacyHitboxes;
import dev.revere.alley.feature.match.combat.legacy.LegacyProjectileData;
import dev.revere.alley.feature.match.internal.MatchServiceImpl;
import dev.revere.alley.feature.match.internal.types.DefaultMatch;
import dev.revere.alley.feature.match.model.GameParticipant;
import dev.revere.alley.feature.match.model.internal.MatchGamePlayer;
import dev.revere.alley.feature.match.snapshot.SnapshotService;
import dev.revere.alley.feature.queue.Queue;
import dev.revere.alley.feature.queue.QueueService;
import dev.revere.alley.feature.music.MusicService;
import dev.revere.alley.feature.party.Party;
import dev.revere.alley.feature.party.PartyService;
import dev.revere.alley.feature.spawn.SpawnService;
import dev.revere.alley.feature.visibility.VisibilityService;
import dev.revere.alley.library.assemble.AssembleService;
import io.papermc.paper.datacomponent.DataComponentTypes;
import io.papermc.paper.datacomponent.item.Consumable;
import io.papermc.paper.datacomponent.item.FoodProperties;
import io.papermc.paper.datacomponent.item.consumable.ItemUseAnimation;
import lombok.Getter;
import org.bukkit.*;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.block.BlockState;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.entity.*;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.inventory.EntityEquipment;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.potion.PotionEffect;
import org.bukkit.profile.PlayerProfile;
import org.bukkit.scheduler.BukkitTask;
import org.bukkit.util.BoundingBox;
import org.bukkit.util.RayTraceResult;
import org.bukkit.util.Vector;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

@Getter
public final class BotMatchSession {
    public static final String BOT_ENTITY_TAG = "alley_bot_entity";

    private static final int OPENING_BUFF_DELAY_TICKS = 3;
    private static final int DOWNWARD_HEAL_WINDUP_TICKS = 2;
    private static final int TURN_HEAL_WINDUP_TICKS = 8;
    private static final int HEAL_RECOVERY_TICKS = 2;
    private static final int TURN_HEAL_RECOVERY_TICKS = 20;
    private static final int HEAL_ACTION_TIMEOUT_TICKS = 20;
    private static final int GOLDEN_APPLE_USE_TICKS = 32;
    private static final int BUFF_POTION_USE_TICKS = 32;
    private static final int BLOCK_HIT_USE_TICKS = 10;
    private static final double BLOCK_HIT_TRIGGER_RATE = 0.15D;
    private static final double BLOCK_HIT_MOVEMENT_SCALE = 0.2D;
    private static final double BLOCK_HIT_MOVEMENT_STEP = 0.2D;
    /** A received Legacy KB must actually lift the Bot before it can arm a crit retry. */
    private static final double LEGACY_CRIT_MIN_UPWARD_KB = 0.08D;
    /** Do not retain a stale airborne-combat marker after the normal jump arc has ended. */
    private static final int LEGACY_CRIT_WINDOW_TICKS = 14;
    private static final int BOW_CHARGE_TICKS = 18;
    private static final int BOW_USE_DURATION_TICKS = 72_000;
    private static final int BLOCK_AIM_TICKS = 2;
    private static final int BLOCK_PLACE_INTERVAL_TICKS = 2;
    private static final int BLOCK_WEAPON_RETURN_TICKS = 2;
    private static final int ROD_WEAPON_RETURN_TICKS = 4;
    private static final int ROD_MAX_LIFETIME_TICKS = 20;
    private static final double ARROW_DRAG = 0.99D;
    private static final double ARROW_GRAVITY = 0.05D;
    private static final int GOLDEN_APPLE_REUSE_DELAY_TICKS = 60;
    private static final int BUFF_POTION_REFRESH_WINDOW_TICKS = 100;
    private static final int BUFF_POTION_CHECK_INTERVAL_TICKS = 20;
    private static final double EATING_SPEED_MULTIPLIER = 0.2D;
    private static final int DEATH_EFFECT_DELAY_TICKS = 2;
    private static final int BOT_REMOVE_DELAY_TICKS = 10;
    private final BotServiceImpl service;
    private final Player player;
    private final Kit kit;
    private final Arena arena;
    private final BotDifficultyProfile difficulty;
    private final BotAiMode aiMode;
    private final int countdownTicks;
    private final int timeLimitTicks;
    private final int returnDelayTicks;
    private final int legacySprintRestartDelayTicks;
    private final int legacyKnockbackInputDelayTicks;
    private final double legacyKnockbackInputScale;
    private final double legacyInputRecoveryStep;
    private final double legacyStrafeRecoveryStep;
    private final boolean debug;
    private final PlayerProfile botSkinProfile;
    private final Map<Location, BlockState> changedBlocks = new LinkedHashMap<>();
    private final List<Projectile> spawnedProjectiles = new java.util.ArrayList<>();

    private NativeBotPlayer nativeBot;
    private Player bot;
    private BukkitTask task;
    private BukkitTask returnTask;
    private BotGomokuGame gomokuGame;
    private DefaultMatch matchContext;
    private Location botSpawnLocation;
    private Projectile activeRodProjectile;
    private boolean running;
    private boolean ended;
    private boolean forcingBotDeath;
    private boolean openingPotionsConsumed;
    private int ticks;
    private int runningTicks;
    private int nextBowTick;
    private int nextRodTick;
    private int nextLavaTick;
    private int nextHealTick;
    private int nextDebuffTick;
    private int nextBlockTick;
    private int nextBuffPotionCheckTick;
    private int removeRodTick;
    private int rodWeaponReturnTick;
    private boolean holdingRod;
    private int buildingSlot = -1;
    private int nextBuildPlacementTick;
    private int buildWeaponReturnTick;
    private int buildTargetIndex;
    private List<Location> buildTargets = List.of();
    private int healingPotionSlot = -1;
    private int healingThrowTick;
    private int healingActionDeadlineTick;
    private int healingRecoveryTick;
    private int buffPotionSlot = -1;
    private int buffPotionFinishTick;
    private List<PotionEffect> buffPotionEffects = List.of();
    private int goldenAppleSlot = -1;
    private int goldenAppleFinishTick;
    private int nextGoldenAppleTick;
    private int foodSlot = -1;
    private int foodFinishTick;
    private int bowSlot = -1;
    private int bowReleaseTick;
    private ItemStack chargingBow;
    private int activeItemUseDuration;
    private boolean turnHealing;
    private ItemStack healingPotion;
    private Vector healingEscapeDirection;
    private double attackProgress;
    private long wTapReleaseUntilNanos;
    private boolean pendingWTap;
    private boolean blockHitActive;
    private int blockHitUntilTick;
    private double blockHitMovementScale = 1.0D;
    private boolean pausedForOpponent;
    private int attackAttemptSequence;
    private int acceptedAttackSequence;
    private float originalWalkSpeed;
    private double strafeDirection = 1.0D;
    private boolean legacyMovementInitialized;
    private double legacyForwardInput = 1.0D;
    private double legacyStrafeInput;
    private int legacyInputRecoveryTick;
    private int legacySprintResumeTick;
    private int lastLegacyKnockbackRecoveryTick = Integer.MIN_VALUE;
    private int legacyCritWindowUntilTick;
    private boolean legacyCritAttackReady;

    public BotMatchSession(BotServiceImpl service, Player player, Kit kit, Arena arena,
                           BotDifficultyProfile difficulty, FileConfiguration config,
                           PlayerProfile botSkinProfile) {
        this.service = service;
        this.player = player;
        this.kit = kit;
        this.arena = arena;
        this.difficulty = difficulty;
        this.aiMode = kit.getBotAiMode() == null ? BotAiMode.MELEE : kit.getBotAiMode();
        this.countdownTicks = Math.max(0, config.getInt("countdown-seconds", 3)) * 20;
        this.timeLimitTicks = Math.max(30, config.getInt("match-time-limit-seconds", 300)) * 20;
        this.returnDelayTicks = Math.max(0, config.getInt("return-to-lobby-delay-seconds", 3)) * 20;
        this.legacySprintRestartDelayTicks = Math.max(1, Math.min(5,
                config.getInt("legacy-movement.sprint-restart-delay-ticks", 2)));
        this.legacyKnockbackInputDelayTicks = Math.max(0, Math.min(5,
                config.getInt("legacy-movement.knockback-input-delay-ticks", 1)));
        this.legacyKnockbackInputScale = clamp(
                config.getDouble("legacy-movement.knockback-input-scale", 0.35D), 0.0D, 1.0D);
        this.legacyInputRecoveryStep = clamp(
                config.getDouble("legacy-movement.input-recovery-step", 0.5D), 0.05D, 1.0D);
        this.legacyStrafeRecoveryStep = clamp(
                config.getDouble("legacy-movement.strafe-recovery-step", 0.21D), 0.01D, 1.0D);
        this.debug = config.getBoolean("debug", false);
        this.botSkinProfile = botSkinProfile;
    }

    public boolean start() {
        if (this.arena instanceof StandAloneArena standalone
                && !standalone.getSpawnReadyFuture().isDone()) {
            standalone.getSpawnReadyFuture().whenComplete((ignored, throwable) ->
                    Bukkit.getScheduler().runTask(AlleyPlugin.getInstance(), () -> {
                        if (throwable != null || !this.player.isOnline()) {
                            if (this.player.isOnline()) {
                                if (throwable != null) {
                                    AlleyPlugin.getInstance().getLogger().log(java.util.logging.Level.SEVERE,
                                            "Could not prepare a bot arena for " + this.player.getName(), throwable);
                                }
                                this.abortStart();
                                this.player.sendMessage(CC.translate("&cThe bot match could not be started."));
                            } else {
                                if (this.arena instanceof StandAloneArena readyArena) {
                                    AlleyPlugin.getInstance().getService(ArenaService.class)
                                            .deleteTemporaryArena(readyArena);
                                }
                                this.service.complete(this);
                            }
                            return;
                        }
                        try {
                            if (!this.startInternal()) {
                                this.abortStart();
                                this.player.sendMessage(CC.translate("&cThe bot match could not be started."));
                            }
                        } catch (RuntimeException exception) {
                            AlleyPlugin.getInstance().getLogger().log(java.util.logging.Level.SEVERE,
                                    "Could not start a bot match for " + this.player.getName(), exception);
                            this.abortStart();
                            if (this.player.isOnline()) {
                                this.player.sendMessage(CC.translate("&cThe bot match could not be started."));
                            }
                        }
                    }));
            return true;
        }
        return startInternal();
    }

    private boolean startInternal() {
        this.originalWalkSpeed = player.getWalkSpeed();
        PlayerUtil.reset(player, true, true);
        Profile humanProfile = AlleyPlugin.getInstance().getService(ProfileService.class).getProfile(player.getUniqueId());
        AlleyPlugin.getInstance().getService(MusicService.class).stopMusic(player);

        String botName = difficulty.getId().equalsIgnoreCase("custom")
                ? difficulty.getDisplayName() : "Bot_" + difficulty.getId();
        if (botName.length() > 16) botName = botName.substring(0, 16);
        this.nativeBot = NativeBotPlayer.spawn(arena.getPos2(), botName, difficulty.getPing(), this.botSkinProfile);
        this.bot = nativeBot.player();
        this.bot.addScoreboardTag(BOT_ENTITY_TAG);

        Profile botProfile = new Profile(bot.getUniqueId(), botName);
        botProfile.setOnline(true);
        AlleyPlugin.getInstance().getService(ProfileService.class).getProfiles().put(bot.getUniqueId(), botProfile);
        this.service.registerBot(this, bot);

        this.botSpawnLocation = arena.getPos2().clone();
        this.bot.setGravity(false);
        this.bot.teleport(this.botSpawnLocation);

        createMatchContext(humanProfile, botProfile);
        this.matchContext.startMatch();
        this.matchContext.getRunnable().setStage((this.countdownTicks / 20) + 1);
        refreshPlayerScoreboard();

        if (this.aiMode == BotAiMode.GOMOKU) {
            player.getInventory().clear();
            player.getInventory().setArmorContents(new ItemStack[4]);
            bot.setGameMode(GameMode.ADVENTURE);
            bot.setHealth(bot.getMaxHealth());
        } else {
            setupBot();
            applyCombatSystems();
            Bukkit.getScheduler().runTaskLater(AlleyPlugin.getInstance(), () -> {
                if (!this.ended && this.nativeBot != null && this.nativeBot.isSpawned()) {
                    this.nativeBot.refreshTrackingFor(this.player);
                    syncBotEquipment();
                }
            }, 2L);
        }
        this.nativeBot.refreshTrackingFor(this.player);
        this.task = Bukkit.getScheduler().runTaskTimer(AlleyPlugin.getInstance(), this::tick, 1L, 1L);
        return true;
    }

    private void createMatchContext(Profile humanProfile, Profile botProfile) {
        Queue queue = AlleyPlugin.getInstance().getService(QueueService.class).getQueues().stream()
                .filter(candidate -> candidate.getKit().equals(this.kit))
                .filter(candidate -> !candidate.isRanked() && !candidate.isDuos())
                .findFirst()
                .orElse(null);

        GameParticipant<MatchGamePlayer> human = new GameParticipant<>(
                new MatchGamePlayer(this.player.getUniqueId(), this.player.getName()));
        GameParticipant<MatchGamePlayer> computer = new GameParticipant<>(
                new MatchGamePlayer(this.bot.getUniqueId(), botProfile.getName()));

        MatchService matchService = AlleyPlugin.getInstance().getService(MatchService.class);
        // Gomoku has a dedicated BotGomokuGame (rather than the normal two-player
        // turn engine), so it keeps a plain context; every combat bot mode is
        // selected through MatchService's setting-based factory below.
        Match createdMatch = this.aiMode == BotAiMode.GOMOKU
                ? new DefaultMatch(queue, this.kit, this.arena, false, human, computer)
                : matchService.createMatch(queue, this.kit, this.arena, false, human, computer);
        if (!(createdMatch instanceof DefaultMatch defaultMatch)) {
            throw new IllegalStateException("Bot matches require a two-participant DefaultMatch subtype, got "
                    + createdMatch.getClass().getSimpleName());
        }
        this.matchContext = defaultMatch;
        this.matchContext.setAffectStatistics(false);
        this.matchContext.setTeamMatch(false);
        matchService.addMatch(this.matchContext);

        humanProfile.setState(ProfileState.PLAYING);
        humanProfile.setMatch(this.matchContext);
        botProfile.setState(ProfileState.PLAYING);
        botProfile.setMatch(this.matchContext);
    }

    private void refreshPlayerScoreboard() {
        Bukkit.getScheduler().runTask(AlleyPlugin.getInstance(), () -> {
            if (!this.player.isOnline() || this.ended) return;
            AssembleService assemble = AlleyPlugin.getInstance().getService(AssembleService.class);
            assemble.removeBoard(this.player);
            assemble.createBoard(this.player);
        });
    }

    private void setupBot() {
        // Match#startMatch normally resolves the Bot through MatchGamePlayer's
        // Bukkit#getEntity fallback. Keep a defensive direct setup for the small
        // spawn-registration race on the first Bot match only.
        if (!this.matchContext.isPlayerInitialized(bot.getUniqueId())) {
            this.matchContext.setupPlayer(bot);
        }
        bot.getInventory().setArmorContents(cloneItems(kit.getArmor()));
        bot.setHealth(bot.getMaxHealth());
        bot.setFoodLevel(20);
        bot.setSaturation(5.0F);
        bot.setExhaustion(0.0F);
        bot.setGameMode(GameMode.SURVIVAL);
        bot.setInvulnerable(false);
        bot.setNoDamageTicks(0);
        bot.setWalkSpeed(0.2F);
        applyBotMovementSpeed();
        setBotSprinting(true);
        this.matchContext.applyColorKit(bot);
        selectCombatItem();
        syncBotEquipment();

        bot.setCollidable(true);
    }

    private void applyCombatSystems() {
        KnockbackManager knockbackManager = AlleyPlugin.getInstance().getService(KnockbackManager.class);
        knockbackManager.getPlayerData(bot).setServerControlled(true);
        knockbackManager.applyKnockback(player, kit);
        knockbackManager.applyKnockback(bot, kit);
        traceCombatProfile(knockbackManager);

        MatchService matchService = AlleyPlugin.getInstance().getService(MatchService.class);
        if (matchService instanceof MatchServiceImpl impl && impl.getLegacyCombatService() != null) {
            // Reconcile the exact kit state instead of retaining disabled legacy
            // settings left behind by a previous match or an early Bot setup pass.
            impl.getLegacyCombatService().removeAll(player);
            impl.getLegacyCombatService().removeAll(bot);
            impl.getLegacyCombatService().applyKit(player, kit);
            impl.getLegacyCombatService().applyKit(bot, kit);
        }
    }

    private void traceCombatProfile(KnockbackManager manager) {
        if (!this.debug) return;

        PlayerKnockbackData data = manager.getPlayerData(bot);
        KnockbackProfile profile = manager.getAppliedProfile(bot);
        if (profile == null) {
            trace("No KB profile resolved for kitProfile=" + kit.getKnockbackProfile());
            return;
        }

        trace("entityClass=" + bot.getClass().getName()
                + " match=" + matchContext.getClass().getSimpleName()
                + " kitProfile=" + kit.getKnockbackProfile()
                + " resolvedProfile=" + profile.getName()
                + " branch=" + profile.getBranch()
                + " horizontal=" + profile.getHorizontalGround() + "/" + profile.getHorizontalAir()
                + " vertical=" + profile.getVerticalGround() + "/" + profile.getVerticalAir()
                + " interactionRange=" + profile.getEntityInteractionRange()
                + " disableDownward=" + profile.isDisableDownwardKb()
                + " misplace=" + profile.isPacketMisplaceEnabled()
                + " packetDelay=" + profile.isPacketDelayEnabled()
                + ":" + profile.getPacketDelayTicks());
    }

    private void tick() {
        if (ended || !player.isOnline() || bot == null || nativeBot == null || !nativeBot.isSpawned()) {
            if (!ended) finish(false);
            return;
        }

        ticks++;
        if (!running) {
            nativeBot.clearMovementInput();
            nativeBot.tick();
            if (ticks <= 40 || ticks % 20 == 0) nativeBot.refreshTrackingFor(player);
            tickCountdown();
            return;
        }

        tickBotPlayerBridge();
        tickRunningBehavior();
        if (ended || !nativeBot.isSpawned()) return;

        // The input selected above must be present when ServerPlayer#doTick
        // runs. Ticking first delayed W/strafe by a frame and allowed W-tap to
        // clear the next frame's input before it was ever applied.
        nativeBot.tick();
        AlleyPlugin.getInstance().getService(KnockbackManager.class).updateMovementState(bot);
        updateLegacyKnockbackCritWindow();
        if (ticks <= 40 || ticks % 20 == 0) nativeBot.refreshTrackingFor(player);
    }

    private void tickRunningBehavior() {
        runningTicks++;
        if (runningTicks >= timeLimitTicks) {
            finish(false);
            return;
        }
        if (player.isDead()) {
            nativeBot.clearMovementInput();
            return;
        }
        applyBotMovementSettings();
        if (isBelowArena(player)) {
            finish(false);
            return;
        }
        if (isBotMovementEliminated()) {
            finish(true);
            return;
        }

        if (this.aiMode == BotAiMode.GOMOKU) {
            nativeBot.clearMovementInput();
            if (this.gomokuGame != null) this.gomokuGame.tick();
            return;
        }

        tickBotBlockHit();
        if (pauseForOpponent()) {
            if (this.blockHitActive) return;
            tickPausedOpponentActions();
            return;
        }
        if (this.blockHitActive) {
            updateNavigation();
            return;
        }

        if (this.goldenAppleSlot >= 0) {
            tickGoldenAppleUse();
            return;
        }

        if (needsPriorityHealing()) {
            interruptForPriorityHealing();
            if (tryGoldenApple()) return;
            if (this.aiMode == BotAiMode.POTPVP && tryHeal()) return;
        }

        if (this.buffPotionSlot >= 0) {
            tickBuffPotionUse();
            return;
        }
        if (!this.openingPotionsConsumed) {
            if (this.runningTicks < OPENING_BUFF_DELAY_TICKS) {
                nativeBot.clearMovementInput();
                return;
            }
            if (beginBuffPotionUse()) return;
        } else if (this.ticks >= this.nextBuffPotionCheckTick) {
            if (beginBuffPotionUse()) return;
        }
        if (this.foodSlot >= 0) {
            tickFoodUse();
            return;
        }
        if (this.aiMode == BotAiMode.POTPVP) {
            if (tryHeal()) return;
        }
        if (this.aiMode == BotAiMode.BUILDUHC && tryExtinguishFire()) return;
        if (beginFoodUse()) return;
        if (this.aiMode == BotAiMode.POTPVP) {
            if (tryDebuffPotion()) return;
        }
        if (this.aiMode == BotAiMode.BUILDUHC) {
            if (tryBuildUhcAction()) return;
        }
        updateNavigation();
        tryAttack();
    }

    private void applyBotMovementSettings() {
        if (kit.isSettingEnabled(KitSettingDisableSwimmingImpl.class)
                && (bot.isSwimming() || bot.getPose() == Pose.SWIMMING)) {
            bot.setSwimming(false);
            bot.setPose(Pose.STANDING, true);
        }
    }

    private boolean isBotMovementEliminated() {
        if ((kit.isSettingEnabled(KitSettingSumo.class)
                || kit.isSettingEnabled(KitSettingSpleef.class))
                && bot.getLocation().getBlock().getType() == Material.WATER) {
            return true;
        }
        if (arena instanceof StandAloneArena standalone
                && kit.isSettingEnabled(KitSettingVoidDeathImpl.class)
                && bot.getLocation().getY() <= standalone.getVoidLevel()) {
            return true;
        }
        return isBelowArena(bot);
    }

    /** Supplies movement-state tracking and pending KB velocity delivery. */
    private void tickBotPlayerBridge() {
        KnockbackManager manager = AlleyPlugin.getInstance().getService(KnockbackManager.class);
        manager.updateMovementState(bot);

        PlayerKnockbackData data = manager.getPlayerData(bot);
        long pendingNativeTick = data.getPendingNativeProjectileVelocityTick();
        boolean nativeProjectilePending = pendingNativeTick != Long.MIN_VALUE
                && manager.getCurrentTick() >= pendingNativeTick
                && manager.getCurrentTick() - pendingNativeTick <= 1L;
        Vector applied = manager.deliverPendingKnockback(bot);
        if (applied != null) {
            bot.setVelocity(applied);
            handleKnockbackApplied(applied, "bot-tick");
        } else if (nativeProjectilePending) {
            Vector nativeVelocity = bot.getVelocity();
            if (nativeVelocity.getY() < 0.0D) {
                KnockbackProfile profile = manager.getAppliedProfile(bot);
                if (profile != null && profile.isDisableDownwardKb()) {
                    nativeVelocity = nativeVelocity.clone().setY(0.0D);
                    bot.setVelocity(nativeVelocity);
                }
            }
            handleKnockbackApplied(nativeVelocity, "bot-tick-native");
        }
    }

    public void handleKnockbackApplied(Vector velocity, String source) {
        if (this.ended || this.bot == null) return;

        PlayerKnockbackData data = AlleyPlugin.getInstance().getService(KnockbackManager.class)
                .getPlayerData(bot);
        if (usesLegacyBotMovement() && velocity != null
                && velocity.getX() * velocity.getX() + velocity.getZ() * velocity.getZ() > 1.0E-4D) {
            handleAcceptedBotKnockback();
        }
        if (usesLegacyBotMovement() && velocity != null
                && velocity.getY() >= LEGACY_CRIT_MIN_UPWARD_KB) {
            this.legacyCritWindowUntilTick = this.ticks + LEGACY_CRIT_WINDOW_TICKS;
            this.legacyCritAttackReady = false;
        }
        if (this.debug) {
            trace("KB applied via " + source + " profile=" + data.getProfileName()
                    + " ground=" + bot.isOnGround() + " velocity=" + formatVector(velocity));
        }
    }

    public void handleKnockbackMiss(String source, Vector nativeVelocity) {
        if (!this.debug || this.bot == null) return;

        PlayerKnockbackData data = AlleyPlugin.getInstance().getService(KnockbackManager.class)
                .getPlayerData(bot);
        trace("KB bridge miss via " + source + " profile=" + data.getProfileName()
                + " pending=" + (data.getVelocity() == null
                ? "null" : formatVector(data.getVelocity()))
                + " nativeMarker=" + data.getPendingNativeProjectileVelocityTick()
                + " native=" + formatVector(nativeVelocity));
    }

    public void handleAcceptedBotKnockback() {
        if (this.ended || this.bot == null || !usesLegacyBotMovement()) return;
        if (this.lastLegacyKnockbackRecoveryTick != Integer.MIN_VALUE
                && this.ticks - this.lastLegacyKnockbackRecoveryTick <= 1) return;

        this.lastLegacyKnockbackRecoveryTick = this.ticks;
        beginLegacyMovementRecovery(true, true);
    }

    private void tickCountdown() {
        if (this.bot != null && this.botSpawnLocation != null) {
            this.bot.setVelocity(new Vector());
            this.bot.setFallDistance(0.0F);
            if (!this.bot.getWorld().equals(this.botSpawnLocation.getWorld())
                    || this.bot.getLocation().distanceSquared(this.botSpawnLocation) > 0.01D) {
                this.bot.teleport(this.botSpawnLocation);
            }
        }
        if (this.matchContext == null || this.matchContext.getState() != MatchState.RUNNING) return;

        running = true;
        this.runningTicks = 0;
        bot.teleport(this.botSpawnLocation);
        bot.setVelocity(new Vector());
        bot.setFallDistance(0.0F);
        bot.setGravity(true);
        bot.setInvulnerable(false);
        bot.setNoDamageTicks(0);
        player.setWalkSpeed(originalWalkSpeed <= 0.0F ? 0.2F : originalWalkSpeed);
        if (this.aiMode == BotAiMode.GOMOKU) {
            this.gomokuGame = new BotGomokuGame(this);
            this.gomokuGame.start();
        } else {
            applyCombatSystems();
            nativeBot.refreshTrackingFor(player);
            syncBotEquipment();
        }
    }

    private boolean beginBuffPotionUse() {
        if (this.aiMode == BotAiMode.GOMOKU) return false;
        this.openingPotionsConsumed = true;
        this.nextBuffPotionCheckTick = this.ticks + BUFF_POTION_CHECK_INTERVAL_TICKS;

        ItemStack[] contents = bot.getInventory().getStorageContents();
        for (int slot = 0; slot < contents.length; slot++) {
            ItemStack item = contents[slot];
            if (item == null || item.getType() != Material.POTION) continue;

            List<PotionEffect> effects = PotionUtil.getPotionEffects(item);
            if (effects.isEmpty() || effects.stream().anyMatch(effect -> !isOpeningBuff(effect))) continue;
            if (!canRefreshPotionEffects(effects)) continue;

            this.buffPotionSlot = holdItem(slot);
            this.buffPotionFinishTick = this.ticks + BUFF_POTION_USE_TICKS;
            this.buffPotionEffects = List.copyOf(effects);
            setBotSprinting(false);
            setNavigationSpeed(getCombatMovementSpeed() * EATING_SPEED_MULTIPLIER);
            startUsingHeldItem(BUFF_POTION_USE_TICKS);
            bot.updateInventory();
            return true;
        }
        return false;
    }

    private void tickBuffPotionUse() {
        ItemStack heldItem = bot.getInventory().getItem(this.buffPotionSlot);
        if (heldItem == null || !isPotionItem(heldItem.getType())) {
            finishBuffPotionUse(false);
            return;
        }

        setBotSprinting(false);
        setNavigationSpeed(getCombatMovementSpeed() * EATING_SPEED_MULTIPLIER);
        if (this.ticks < this.buffPotionFinishTick) {
            keepUsingHeldItem(Math.min(BUFF_POTION_USE_TICKS,
                    this.buffPotionFinishTick - this.ticks + 1));
            return;
        }
        finishBuffPotionUse(true);
    }

    private void finishBuffPotionUse(boolean completeUse) {
        boolean completedNatively = completeUse && bot.hasActiveItem();
        if (completedNatively) bot.completeUsingActiveItem();
        stopUsingHeldItem();
        if (completeUse && !completedNatively) {
            this.buffPotionEffects.forEach(effect -> bot.addPotionEffect(effect, true));
            consumeOne(this.buffPotionSlot);
            bot.getWorld().playSound(bot.getLocation(), Sound.ENTITY_GENERIC_DRINK, 1.0F, 1.0F);
        }
        this.buffPotionSlot = -1;
        this.buffPotionFinishTick = 0;
        this.buffPotionEffects = List.of();
        this.nextBuffPotionCheckTick = this.ticks + 1;
        syncHeldItem();
        bot.updateInventory();
        selectCombatItem();
        restoreCombatNavigation();
    }

    private boolean canRefreshPotionEffects(List<PotionEffect> effects) {
        for (PotionEffect effect : effects) {
            PotionEffect active = bot.getPotionEffect(effect.getType());
            if (active == null) continue;
            if (active.getDuration() == PotionEffect.INFINITE_DURATION
                    || active.getDuration() > BUFF_POTION_REFRESH_WINDOW_TICKS) return false;
        }
        return true;
    }

    private boolean isPotionItem(Material material) {
        return material == Material.POTION
                || material == Material.SPLASH_POTION
                || material == Material.LINGERING_POTION;
    }

    private boolean isOpeningBuff(PotionEffect effect) {
        return switch (effect.getType().getKey().getKey()) {
            case "speed", "haste", "strength", "jump_boost", "regeneration", "resistance", "fire_resistance",
                    "water_breathing", "invisibility", "night_vision", "health_boost", "absorption",
                    "saturation", "luck", "slow_falling", "conduit_power", "dolphins_grace",
                    "hero_of_the_village" -> true;
            default -> false;
        };
    }

    private boolean pauseForOpponent() {
        boolean shouldPause = !difficulty.isTryhard()
                && (service.isInventoryOpen(player) || isOpponentConsuming() || isOpponentFacingAway());
        if (!shouldPause) {
            if (this.pausedForOpponent) {
                this.pausedForOpponent = false;
                selectCombatItem();
                restoreCombatNavigation();
            }
            return false;
        }

        if (!this.pausedForOpponent) {
            this.pausedForOpponent = true;
            interruptForOpponentPause();
        }
        setBotSprinting(false);
        nativeBot.clearMovementInput();
        return true;
    }

    /**
     * A non-tryhard Bot does not pursue or attack a disengaged opponent, but it
     * can still perform the same self-maintenance actions available to a player.
     */
    private void tickPausedOpponentActions() {
        if (this.goldenAppleSlot >= 0) {
            tickGoldenAppleUse();
            return;
        }
        if (this.buffPotionSlot >= 0) {
            tickBuffPotionUse();
            return;
        }
        if (this.foodSlot >= 0) {
            tickFoodUse();
            return;
        }
        if (needsPriorityHealing() && tryGoldenApple()) return;
        if (!this.openingPotionsConsumed) {
            if (beginBuffPotionUse()) return;
        } else if (this.ticks >= this.nextBuffPotionCheckTick && beginBuffPotionUse()) {
            return;
        }
        beginFoodUse();
    }

    private boolean isOpponentConsuming() {
        if (!player.hasActiveItem()) return false;
        ItemStack activeItem = player.getActiveItem();
        if (activeItem.isEmpty()) return false;

        Consumable consumable = activeItem.getData(DataComponentTypes.CONSUMABLE);
        if (consumable == null) return false;
        return consumable.animation() == ItemUseAnimation.EAT
                || consumable.animation() == ItemUseAnimation.DRINK;
    }

    private boolean isOpponentFacingAway() {
        Vector look = player.getLocation().getDirection().setY(0.0D);
        Vector towardBot = bot.getLocation().toVector()
                .subtract(player.getLocation().toVector()).setY(0.0D);
        if (look.lengthSquared() < 1.0E-8D || towardBot.lengthSquared() < 1.0E-8D) return false;
        return look.normalize().dot(towardBot.normalize()) < 0.0D;
    }

    private void interruptForOpponentPause() {
        if (this.healingPotionSlot >= 0 || this.healingRecoveryTick > 0) cancelHealingAction();
        if (this.bowSlot >= 0) finishBowCharge(false);
        if (this.buildingSlot >= 0) clearBlockPlacement();
        if (this.activeRodProjectile != null) {
            if (this.activeRodProjectile.isValid()) this.activeRodProjectile.remove();
            this.activeRodProjectile = null;
        }
        this.holdingRod = false;
        this.rodWeaponReturnTick = 0;
        this.attackProgress = 0.0D;
        if (!hasPausedOpponentConsumableAction() && !this.blockHitActive) {
            stopUsingHeldItem();
            selectCombatItem();
        }
    }

    private boolean hasPausedOpponentConsumableAction() {
        return this.goldenAppleSlot >= 0 || this.foodSlot >= 0 || this.buffPotionSlot >= 0;
    }

    private void updateNavigation() {
        Location target = getAimTarget();
        faceTarget(target);
        if (isWTapReleasing()) {
            setBotSprinting(false);
            if (usesLegacyBotMovement()) {
                this.legacyForwardInput = 0.0D;
                this.legacyStrafeInput = approach(
                        this.legacyStrafeInput, 0.0D, this.legacyStrafeRecoveryStep);
            }
            nativeBot.clearMovementInput();
            return;
        }
        if (difficulty.isStrafe() && ticks % 16 == 0) strafeDirection *= -1.0D;
        double strafe = difficulty.isStrafe()
                && bot.getLocation().distanceSquared(player.getLocation()) < 25.0D
                ? 0.42D * strafeDirection : 0.0D;

        if (usesLegacyBotMovement()) {
            updateLegacyNavigation(player.getLocation(), strafe);
            return;
        }

        setBotSprinting(true);
        nativeBot.moveToward(player.getLocation(), getCombatMovementSpeed(), difficulty.getCombatDistance(), strafe);
    }

    private void updateLegacyNavigation(Location target, double desiredStrafe) {
        initializeLegacyMovement();

        if (this.ticks >= this.legacyInputRecoveryTick) {
            this.legacyForwardInput = approach(
                    this.legacyForwardInput, 1.0D, this.legacyInputRecoveryStep);
            this.legacyStrafeInput = approach(
                    this.legacyStrafeInput, desiredStrafe, this.legacyStrafeRecoveryStep);
        } else {
            this.legacyStrafeInput = approach(
                    this.legacyStrafeInput, 0.0D, this.legacyStrafeRecoveryStep);
        }

        Vector offset = target.toVector().subtract(bot.getLocation().toVector()).setY(0.0D);
        boolean movingForward = offset.lengthSquared()
                >= difficulty.getCombatDistance() * difficulty.getCombatDistance();
        boolean canSprint = movingForward
                && this.ticks >= this.legacySprintResumeTick
                && this.legacyForwardInput >= 0.8D;

        if (canSprint) {
            if (!bot.isSprinting()) rearmBotSprint();
        } else if (movingForward) {
            // This is only the server movement state. A forced attack stop does
            // not become a synthetic client STOP packet.
            bot.setSprinting(false);
        } else if (bot.isSprinting()) {
            // Moving backwards drops sprint on the 1.8 client.
            setBotSprinting(false);
            this.legacySprintResumeTick = Math.max(this.legacySprintResumeTick,
                    this.ticks + this.legacySprintRestartDelayTicks);
        }

        nativeBot.moveToward(target, this.legacyForwardInput * this.blockHitMovementScale,
                difficulty.getCombatDistance(), this.legacyStrafeInput);
    }

    private void setNavigationSpeed(double speedModifier) {
        if (nativeBot != null) nativeBot.scaleMovementInput(speedModifier);
    }

    private void restoreCombatNavigation() {
        if (nativeBot == null || !nativeBot.isSpawned()) return;
        if (usesLegacyBotMovement()) {
            if (this.pausedForOpponent || isWTapReleasing()) {
                bot.setSprinting(false);
            } else {
                beginLegacyMovementRecovery(false, false);
            }
            return;
        }
        setBotSprinting(!this.pausedForOpponent && !isWTapReleasing());
    }

    private void initializeLegacyMovement() {
        if (this.legacyMovementInitialized) return;
        this.legacyMovementInitialized = true;
        this.legacyForwardInput = this.legacyKnockbackInputScale;
        this.legacyStrafeInput = 0.0D;
        this.legacyInputRecoveryTick = this.ticks + this.legacyKnockbackInputDelayTicks;
        this.legacySprintResumeTick = this.ticks + this.legacySprintRestartDelayTicks;
        setBotSprinting(false);
    }

    private void beginLegacyMovementRecovery(boolean reduceInput, boolean clientStop) {
        if (!usesLegacyBotMovement()) return;
        initializeLegacyMovement();

        if (reduceInput) {
            this.legacyForwardInput = Math.min(
                    this.legacyForwardInput, this.legacyKnockbackInputScale);
            this.legacyStrafeInput = 0.0D;
            this.legacyInputRecoveryTick = Math.max(this.legacyInputRecoveryTick,
                    this.ticks + this.legacyKnockbackInputDelayTicks);
        }
        this.legacySprintResumeTick = Math.max(this.legacySprintResumeTick,
                this.ticks + this.legacySprintRestartDelayTicks);

        if (clientStop) {
            setBotSprinting(false);
        } else {
            bot.setSprinting(false);
        }
    }

    private boolean usesLegacyBotMovement() {
        return kit.isSettingEnabled(KitSettingOldSwordBlocking.class);
    }

    private double approach(double current, double target, double maximumStep) {
        if (current < target) return Math.min(target, current + maximumStep);
        if (current > target) return Math.max(target, current - maximumStep);
        return target;
    }

    private void setBotSprinting(boolean sprinting) {
        bot.setSprinting(sprinting);
        AlleyPlugin.getInstance().getService(KnockbackManager.class)
                .updateSyntheticLegacySprint(bot, sprinting);
    }

    private void rearmBotSprint() {
        bot.setSprinting(true);
        AlleyPlugin.getInstance().getService(KnockbackManager.class)
                .forceSyntheticLegacySprintStart(bot);
    }

    private double getCombatMovementSpeed() {
        return 1.0D;
    }

    private void applyBotMovementSpeed() {
        AttributeInstance movement = bot.getAttribute(Attribute.MOVEMENT_SPEED);
        if (movement == null) return;

        // Legacy combat keeps the same movement attribute as a normal player.
        // The Bot still supplies its regular W/strafe input while knocked back;
        // difficulty must not make that input accelerate through Legacy KB faster.
        double movementMultiplier = kit.isSettingEnabled(KitSettingOldSwordBlocking.class)
                ? 1.0D : difficulty.getMovementSpeed();
        movement.setBaseValue(0.1D * movementMultiplier);
    }

    private void tryAttack() {
        if (runningTicks < difficulty.getReactionTicks()) return;
        if (!isWithinRange(difficulty.getSwingRange()) || !hasClearLineOfSight()) return;

        boolean criticalRetry = this.legacyCritAttackReady;
        if (!criticalRetry) {
            attackProgress = Math.min(1.0, attackProgress + difficulty.getCps() / 20.0);
            if (attackProgress < 1.0) return;
            attackProgress -= 1.0;
        }

        double attackReach = rollAttackReach();
        if (!isWithinRange(attackReach)) {
            bot.swingMainHand();
            return;
        }
        if (!isTargetInView(attackReach)) {
            bot.swingMainHand();
            return;
        }
        attackAsPlayer();
        if (criticalRetry) this.legacyCritAttackReady = false;
    }

    /**
     * Runs after the Bot's native physics tick. This intentionally observes the
     * real fall distance/vertical velocity that the Legacy crit listener will
     * read on the next attack, rather than synthesizing either value.
     */
    private void updateLegacyKnockbackCritWindow() {
        if (!usesLegacyBotMovement()) return;
        if (this.ticks > this.legacyCritWindowUntilTick || bot.isOnGround()
                || bot.isInWater() || bot.isClimbing()) {
            this.legacyCritWindowUntilTick = 0;
            this.legacyCritAttackReady = false;
            return;
        }

        Vector velocity = bot.getVelocity();
        if (velocity.getY() < 0.0D && bot.getFallDistance() > 0.0F) {
            this.legacyCritAttackReady = true;
        }
    }

    private void attackAsPlayer() {
        KnockbackManager manager = AlleyPlugin.getInstance().getService(KnockbackManager.class);
        PlayerKnockbackData data = manager.getPlayerData(bot);
        int attempt = this.debug ? ++this.attackAttemptSequence : 0;
        KnockbackProfile knockbackProfile = manager.getAppliedProfile(bot);
        boolean hasKnockbackEnchant = bot.getInventory().getItemInMainHand()
                .getEnchantmentLevel(Enchantment.KNOCKBACK) > 0;
        boolean applyLegacySlowdown = manager.isLegacyKnockback(bot)
                && knockbackProfile != null
                && (manager.hasLegacySprintKnockback(bot) || hasKnockbackEnchant);
        Vector velocityBeforeAttack = applyLegacySlowdown ? bot.getVelocity().clone() : null;
        boolean useWTap = difficulty.isWTap()
                && ThreadLocalRandom.current().nextDouble() < difficulty.getWTapRate();
        this.pendingWTap = useWTap;

        data.setServerSideHit(true);
        try {
            nativeBot.attack(player);
        } finally {
            data.setServerSideHit(false);
        }
        // Entity damage events raised by ServerPlayer#attack are synchronous.
        // A marker left behind here belongs to a failed swing, not a later hit.
        this.pendingWTap = false;
        if (velocityBeforeAttack != null) {
            Vector current = bot.getVelocity();
            double slowdown = knockbackProfile.getLegacyAttackerHorizontalSlowdown();
            bot.setVelocity(new Vector(
                    velocityBeforeAttack.getX() * slowdown,
                    current.getY(),
                    velocityBeforeAttack.getZ() * slowdown));
        }
        if (shouldStartBotBlockHit(useWTap)) startBotBlockHit();
        if (!useWTap && !ended) {
            if (usesLegacyBotMovement() && applyLegacySlowdown) {
                // Keep full W input so NMS accelerates naturally from the 0.6
                // attacker slowdown, but do not restore sprint immediately.
                beginLegacyMovementRecovery(false, false);
            } else {
                rearmBotSprint();
            }
        }
        verifyAttackAttempt(attempt, data);
    }

    public void confirmBotAttack() {
        if (this.debug) this.acceptedAttackSequence = this.attackAttemptSequence;
        boolean useWTap = this.pendingWTap;
        this.pendingWTap = false;
        if (!useWTap || ended || bot == null || nativeBot == null) return;
        this.wTapReleaseUntilNanos = System.nanoTime()
                + difficulty.getWTapReactionTimeMs() * 1_000_000L;
        setBotSprinting(false);
        if (usesLegacyBotMovement()) {
            initializeLegacyMovement();
            this.legacyForwardInput = 0.0D;
            this.legacyStrafeInput = 0.0D;
            this.legacySprintResumeTick = Math.max(this.legacySprintResumeTick,
                    this.ticks + this.legacySprintRestartDelayTicks);
        }
        nativeBot.clearMovementInput();
    }

    private boolean isWTapReleasing() {
        return System.nanoTime() < this.wTapReleaseUntilNanos;
    }

    private double rollAttackReach() {
        double minimum = difficulty.getMinReach();
        double maximum = difficulty.getMaxReach();
        if (maximum - minimum < 1.0E-6D) return maximum;
        return ThreadLocalRandom.current().nextDouble(minimum, maximum);
    }

    private boolean shouldStartBotBlockHit(boolean wTapThisAttack) {
        if (!usesLegacyBotMovement() || !difficulty.isBlockHit() || this.blockHitActive) return false;

        boolean closeTrade = bot.getLocation().distanceSquared(player.getLocation()) <= 6.25D;
        return (closeTrade || wTapThisAttack)
                && ThreadLocalRandom.current().nextDouble() < BLOCK_HIT_TRIGGER_RATE;
    }

    private void startBotBlockHit() {
        LegacyCombatService legacyCombat = getLegacyCombatService();
        if (legacyCombat == null || bot == null) return;

        this.blockHitActive = true;
        this.blockHitUntilTick = this.ticks + BLOCK_HIT_USE_TICKS;
        this.blockHitMovementScale = approach(this.blockHitMovementScale,
                BLOCK_HIT_MOVEMENT_SCALE, BLOCK_HIT_MOVEMENT_STEP);
        setBotSprinting(false);
        this.legacySprintResumeTick = Math.max(this.legacySprintResumeTick,
                this.ticks + this.legacySprintRestartDelayTicks);
        legacyCombat.setBlocking(bot.getUniqueId(), true);
        try {
            bot.startUsingItem(EquipmentSlot.HAND);
        } catch (IllegalStateException ignored) {
            finishBotBlockHit();
        }
    }

    private void tickBotBlockHit() {
        if (this.blockHitActive && (!usesLegacyBotMovement() || this.ticks >= this.blockHitUntilTick)) {
            finishBotBlockHit();
        }

        double targetScale = this.blockHitActive ? BLOCK_HIT_MOVEMENT_SCALE : 1.0D;
        this.blockHitMovementScale = approach(this.blockHitMovementScale,
                targetScale, BLOCK_HIT_MOVEMENT_STEP);
    }

    private void finishBotBlockHit() {
        if (!this.blockHitActive) return;

        boolean completedReset = this.ticks >= this.blockHitUntilTick;
        this.blockHitActive = false;
        this.blockHitUntilTick = 0;
        LegacyCombatService legacyCombat = getLegacyCombatService();
        if (legacyCombat != null && bot != null) {
            legacyCombat.setBlocking(bot.getUniqueId(), false);
        }
        if (bot != null && bot.hasActiveItem()) bot.clearActiveItem();
        if (completedReset && bot != null) {
            AlleyPlugin.getInstance().getService(KnockbackManager.class)
                    .armLegacyWTapExtra(bot);
        }
    }

    private LegacyCombatService getLegacyCombatService() {
        MatchService matchService = AlleyPlugin.getInstance().getService(MatchService.class);
        return matchService instanceof MatchServiceImpl impl ? impl.getLegacyCombatService() : null;
    }

    private void verifyAttackAttempt(int attempt, PlayerKnockbackData data) {
        if (!this.debug || attempt == 0) return;

        Bukkit.getScheduler().runTask(AlleyPlugin.getInstance(), () -> {
            if (this.ended || this.acceptedAttackSequence >= attempt) return;
            trace("Attack produced no accepted damage event: matchState=" + matchContext.getState()
                    + " profile=" + data.getProfileName()
                    + " distance=" + String.format(java.util.Locale.ROOT, "%.3f",
                    bot.getLocation().distance(player.getLocation())));
        });
    }

    private Location getAimTarget() {
        Location eye = player.getEyeLocation().clone();
        BoundingBox box = player.getBoundingBox();
        double eyeY = clamp(eye.getY(), box.getMinY(), box.getMaxY());
        double chestY = Math.min(eyeY, box.getMinY() + box.getHeight() * 0.72D);
        double aimY = chestY >= eyeY
                ? eyeY
                : ThreadLocalRandom.current().nextDouble(chestY, eyeY);
        double error = difficulty.getAimError();
        eye.add(random(error), 0.0D, random(error));
        eye.setY(clamp(aimY + random(error * 0.2D), chestY, eyeY));
        return eye;
    }

    private void faceTarget(Location target) {
        Vector direction = target.toVector().subtract(bot.getEyeLocation().toVector());
        if (direction.lengthSquared() < 1.0E-8D) return;

        Location rotation = bot.getLocation();
        rotation.setDirection(direction);
        nativeBot.face(target, (float) difficulty.getAimSpeed());
    }

    private boolean isWithinRange(double reach) {
        BoundingBox box = getCombatTargetBox();
        Vector eye = bot.getEyeLocation().toVector();
        double x = clamp(eye.getX(), box.getMinX(), box.getMaxX());
        double y = clamp(eye.getY(), box.getMinY(), box.getMaxY());
        double z = clamp(eye.getZ(), box.getMinZ(), box.getMaxZ());
        return eye.distanceSquared(new Vector(x, y, z)) <= reach * reach;
    }

    private BoundingBox getCombatTargetBox() {
        if (kit.isSettingEnabled(KitSettingOldSwordBlocking.class)) {
            return LegacyHitboxes.meleeTarget(player);
        }

        BoundingBox box = player.getBoundingBox().clone();
        KnockbackManager manager = AlleyPlugin.getInstance().getService(KnockbackManager.class);
        KnockbackProfile profile = manager.getProfile(manager.getPlayerData(bot).getProfileName());
        if (profile == null) return box;

        double width = Math.max(box.getWidthX(), box.getWidthZ());
        double horizontalExpansion = Math.max(0.0D, (profile.getHitboxLength() - width) * 0.5D);
        double verticalExpansion = Math.max(0.0D, (profile.getHitboxHeight() - box.getHeight()) * 0.5D);
        return box.expand(horizontalExpansion, verticalExpansion, horizontalExpansion);
    }

    private boolean hasClearLineOfSight() {
        if (bot.hasLineOfSight(player)) return true;

        Location start = bot.getEyeLocation();
        Vector direction = player.getEyeLocation().toVector().subtract(start.toVector());
        double distance = direction.length();
        return distance < 1.0E-6D || bot.getWorld().rayTraceBlocks(
                start, direction.normalize(), distance, FluidCollisionMode.NEVER, true) == null;
    }

    /**
     * Player#attack is a server-side convenience call and does not know which
     * entity the synthetic player's crosshair is over. Require the same
     * eye-direction entity ray that a real client would use before applying a
     * melee hit, so targets behind or outside the bot's view cannot be hit.
     */
    private boolean isTargetInView(double reach) {
        Location eye = bot.getEyeLocation();
        Vector direction = eye.getDirection();
        if (direction.lengthSquared() < 1.0E-8D) return false;

        RayTraceResult hit = getCombatTargetBox().rayTrace(
                eye.toVector(), direction.normalize(), reach);
        if (hit == null) return false;

        RayTraceResult block = bot.getWorld().rayTraceBlocks(
                eye, direction.normalize(), hit.getHitPosition().distance(eye.toVector()),
                FluidCollisionMode.NEVER, true);
        return block == null;
    }

    private double clamp(double value, double minimum, double maximum) {
        return Math.max(minimum, Math.min(maximum, value));
    }

    private boolean tryHeal() {
        if (this.healingPotionSlot >= 0) {
            tickHealingAction();
            return true;
        }
        if (this.healingRecoveryTick > 0) {
            if (this.ticks < this.healingRecoveryTick) return true;
            this.healingRecoveryTick = 0;
            selectCombatItem();
            restoreCombatNavigation();
        }
        if (difficulty.getHealHealth() <= 0.0 || bot.getHealth() > difficulty.getHealHealth()
                || ticks < nextHealTick) return false;
        ItemStack[] contents = bot.getInventory().getStorageContents();
        for (int slot = 0; slot < contents.length; slot++) {
            ItemStack item = contents[slot];
            if (!PotionUtil.isSplashHealthPotion(item)) continue;

            beginHealingAction(slot);
            return true;
        }
        return false;
    }

    private void beginHealingAction(int slot) {
        this.healingPotionSlot = holdItem(slot);
        ItemStack heldPotion = bot.getInventory().getItem(this.healingPotionSlot);
        this.healingPotion = heldPotion == null ? null : heldPotion.clone();
        if (this.healingPotion != null) this.healingPotion.setAmount(1);

        this.turnHealing = ThreadLocalRandom.current().nextBoolean();
        this.healingEscapeDirection = bot.getLocation().toVector()
                .subtract(player.getLocation().toVector()).setY(0.0D);
        if (this.healingEscapeDirection.lengthSquared() < 1.0E-6D) {
            this.healingEscapeDirection = bot.getLocation().getDirection().multiply(-1.0D).setY(0.0D);
        }
        this.healingEscapeDirection.normalize();
        this.healingThrowTick = this.ticks + (this.turnHealing
                ? TURN_HEAL_WINDUP_TICKS : DOWNWARD_HEAL_WINDUP_TICKS);
        this.healingActionDeadlineTick = this.ticks + HEAL_ACTION_TIMEOUT_TICKS;
        this.nativeBot.clearMovementInput();
        this.bot.updateInventory();
        updateHealingPose();
    }

    private void tickHealingAction() {
        if (this.ticks > this.healingActionDeadlineTick) {
            cancelHealingAction();
            return;
        }
        ItemStack heldItem = bot.getInventory().getItem(this.healingPotionSlot);
        if (this.healingPotion == null || !PotionUtil.isSplashHealthPotion(heldItem)) {
            cancelHealingAction();
            return;
        }

        updateHealingPose();
        if (this.ticks < this.healingThrowTick) return;

        Vector velocity;
        if (this.turnHealing) {
            velocity = this.healingEscapeDirection.clone().setY(-0.32D).normalize().multiply(0.85D);
        } else {
            Location landing = bot.getLocation().clone()
                    .add(this.healingEscapeDirection.clone().multiply(0.65D))
                    .add(0.0D, 0.05D, 0.0D);
            velocity = landing.toVector().subtract(bot.getEyeLocation().toVector())
                    .normalize().multiply(0.85D);
        }
        nativeBot.face(bot.getEyeLocation().clone().add(velocity.clone().multiply(5.0D)), 180.0F);

        KnockbackManager knockbackManager = AlleyPlugin.getInstance().getService(KnockbackManager.class);
        if (!knockbackManager.isKnockbackApplied(bot, kit)) {
            knockbackManager.applyKnockback(bot, kit);
        }
        PotionMotionListener potionMotionListener = knockbackManager.getPotionMotionListener();
        ThrownPotion potion;
        if (potionMotionListener != null) potionMotionListener.beginCustomLaunch(bot);
        try {
            bot.swingMainHand();
            potion = bot.launchProjectile(ThrownPotion.class, velocity);
        } catch (RuntimeException exception) {
            cancelHealingAction();
            return;
        } finally {
            if (potionMotionListener != null) potionMotionListener.endCustomLaunch(bot);
        }
        potion.setItem(this.healingPotion);
        if (potionMotionListener != null) potionMotionListener.applyCustomLaunch(potion, bot);
        this.spawnedProjectiles.add(potion);
        consumeOne(this.healingPotionSlot);
        syncHeldItem();
        bot.updateInventory();

        this.nextHealTick = this.ticks + 30;
        this.healingRecoveryTick = this.ticks
                + (this.turnHealing ? TURN_HEAL_RECOVERY_TICKS : HEAL_RECOVERY_TICKS);
        this.healingPotionSlot = -1;
        this.healingPotion = null;
        this.healingEscapeDirection = null;
    }

    private void updateHealingPose() {
        Location eye = bot.getEyeLocation();
        if (this.turnHealing) {
            nativeBot.face(eye.clone().add(this.healingEscapeDirection.clone().multiply(5.0D)), 180.0F);
            setBotSprinting(true);
            nativeBot.moveToward(bot.getLocation().clone().add(this.healingEscapeDirection),
                    getCombatMovementSpeed(), 0.0D, 0.0D);
            return;
        }

        Location lookAt = bot.getLocation().clone()
                .add(this.healingEscapeDirection.clone().multiply(0.65D))
                .add(0.0D, 0.05D, 0.0D);
        nativeBot.face(lookAt, 180.0F);
    }

    private void cancelHealingAction() {
        this.healingPotionSlot = -1;
        this.healingPotion = null;
        this.healingEscapeDirection = null;
        this.healingRecoveryTick = 0;
        selectCombatItem();
        restoreCombatNavigation();
    }

    private boolean tryBuildUhcAction() {
        if (this.buildingSlot >= 0) {
            tickBlockPlacement();
            return true;
        }
        if (this.bowSlot >= 0) {
            tickBowCharge();
            return true;
        }
        tickRodProjectile();
        if (this.holdingRod) return true;

        if (tryGoldenApple()) return true;
        double distanceSquared = bot.getLocation().distanceSquared(player.getLocation());
        if (distanceSquared <= 12.25D && this.ticks >= this.nextBlockTick
                && ThreadLocalRandom.current().nextInt(20) == 0 && placeBlockingBlocks()) return true;
        if (difficulty.isLava() && distanceSquared <= 9.0
                && this.ticks >= this.nextLavaTick && placeTemporaryLava()) return true;
        if (difficulty.isBow() && distanceSquared >= 49.0
                && this.ticks >= this.nextBowTick && beginBowCharge()) return true;
        return difficulty.isRod() && distanceSquared >= 12.25 && distanceSquared <= 100.0
                && this.ticks >= this.nextRodTick && castRod();
    }

    private boolean tryDebuffPotion() {
        double distanceSquared = bot.getLocation().distanceSquared(player.getLocation());
        if (distanceSquared > 16.0D || this.ticks < this.nextDebuffTick
                || ThreadLocalRandom.current().nextInt(12) != 0) return false;

        ItemStack[] contents = bot.getInventory().getStorageContents();
        for (int slot = 0; slot < contents.length; slot++) {
            ItemStack item = contents[slot];
            if (item == null || item.getType() != Material.SPLASH_POTION
                    || PotionUtil.isSplashHealthPotion(item)) continue;
            List<PotionEffect> effects = PotionUtil.getPotionEffects(item);
            if (effects.isEmpty() || effects.stream().noneMatch(this::isDebuff)) continue;
            if (effects.stream().anyMatch(effect -> {
                PotionEffect active = player.getPotionEffect(effect.getType());
                return active != null && active.getDuration() > 100;
            })) continue;

            int heldSlot = holdItem(slot);
            ItemStack thrownItem = bot.getInventory().getItem(heldSlot).clone();
            thrownItem.setAmount(1);
            Location target = player.getLocation().clone().add(player.getVelocity().clone().multiply(3.0D));
            Vector velocity = target.toVector().subtract(bot.getEyeLocation().toVector())
                    .normalize().multiply(0.9D);
            nativeBot.face(target, 180.0F);
            bot.swingMainHand();
            KnockbackManager knockbackManager = AlleyPlugin.getInstance().getService(KnockbackManager.class);
            PotionMotionListener potionMotionListener = knockbackManager.getPotionMotionListener();
            ThrownPotion potion;
            if (potionMotionListener != null) potionMotionListener.beginCustomLaunch(bot);
            try {
                potion = bot.launchProjectile(ThrownPotion.class, velocity);
            } finally {
                if (potionMotionListener != null) potionMotionListener.endCustomLaunch(bot);
            }
            potion.setItem(thrownItem);
            if (potionMotionListener != null) potionMotionListener.applyCustomLaunch(potion, bot);
            spawnedProjectiles.add(potion);
            consumeOne(heldSlot);
            nextDebuffTick = ticks + 60;
            selectCombatItem();
            return true;
        }
        return false;
    }

    private boolean isDebuff(PotionEffect effect) {
        return switch (effect.getType().getKey().getKey()) {
            case "slowness", "poison", "weakness", "blindness", "mining_fatigue", "nausea",
                    "wither", "levitation", "unluck", "darkness" -> true;
            default -> false;
        };
    }

    private boolean placeBlockingBlocks() {
        if (!canBuild()) return false;
        int blockSlot = findPlaceableBlockSlot();
        if (blockSlot < 0) return false;

        Vector towardBot = bot.getLocation().toVector().subtract(player.getLocation().toVector()).setY(0.0D);
        if (towardBot.lengthSquared() < 1.0E-6D) return false;
        BlockFace direction = cardinalFace(towardBot);
        Block base = player.getLocation().getBlock().getRelative(direction);
        List<Location> targets = new ArrayList<>(2);
        for (int height = 0; height < 2; height++) {
            Block block = base.getRelative(0, height, 0);
            if (canPlaceBlockAt(block, targets)) targets.add(block.getLocation());
        }
        if (targets.isEmpty()) return false;

        this.buildingSlot = holdItem(blockSlot);
        this.buildTargets = List.copyOf(targets);
        this.buildTargetIndex = 0;
        this.nextBuildPlacementTick = this.ticks + BLOCK_AIM_TICKS;
        this.buildWeaponReturnTick = 0;
        nextBlockTick = ticks + 50;
        nativeBot.clearMovementInput();
        faceBlock(this.buildTargets.getFirst());
        return true;
    }

    private void tickBlockPlacement() {
        nativeBot.clearMovementInput();
        if (this.buildTargetIndex < this.buildTargets.size()) {
            Location target = this.buildTargets.get(this.buildTargetIndex);
            faceBlock(target);
            if (this.ticks < this.nextBuildPlacementTick) return;

            Block block = target.getBlock();
            ItemStack heldBlock = bot.getInventory().getItem(this.buildingSlot);
            if (heldBlock == null || !heldBlock.getType().isBlock()
                    || !canPlaceBlockAt(block, this.buildTargets.subList(0, this.buildTargetIndex))) {
                finishBlockPlacement();
                return;
            }

            recordPlacedBlock(block.getState());
            block.setType(heldBlock.getType(), false);
            bot.swingMainHand();
            SoundGroup sounds = block.getBlockData().getSoundGroup();
            bot.getWorld().playSound(block.getLocation().add(0.5D, 0.5D, 0.5D),
                    sounds.getPlaceSound(), sounds.getVolume(), sounds.getPitch() * 0.8F);
            consumeOne(this.buildingSlot);
            syncHeldItem();
            bot.updateInventory();
            this.buildTargetIndex++;
            this.nextBuildPlacementTick = this.ticks + BLOCK_PLACE_INTERVAL_TICKS;
            if (this.buildTargetIndex >= this.buildTargets.size()) {
                this.buildWeaponReturnTick = this.ticks + BLOCK_WEAPON_RETURN_TICKS;
            }
            return;
        }

        if (this.ticks >= this.buildWeaponReturnTick) finishBlockPlacement();
    }

    private boolean canPlaceBlockAt(Block block, List<Location> plannedSupports) {
        if (!block.getType().isAir()) return false;
        Location eye = bot.getEyeLocation();
        Location center = block.getLocation().add(0.5D, 0.5D, 0.5D);
        Vector sightLine = center.toVector().subtract(eye.toVector());
        double distance = sightLine.length();
        if (distance > 4.5D) return false;
        RayTraceResult obstruction = bot.getWorld().rayTraceBlocks(
                eye, sightLine.normalize(), distance, FluidCollisionMode.NEVER, true);
        if (obstruction != null && obstruction.getHitBlock() != null
                && !obstruction.getHitBlock().equals(block)
                && !isAdjacentSupport(block, obstruction.getHitBlock(), plannedSupports)) return false;

        BoundingBox blockBox = new BoundingBox(block.getX(), block.getY(), block.getZ(),
                block.getX() + 1.0D, block.getY() + 1.0D, block.getZ() + 1.0D);
        if (blockBox.overlaps(player.getBoundingBox()) || blockBox.overlaps(bot.getBoundingBox())) return false;

        for (BlockFace face : new BlockFace[]{BlockFace.DOWN, BlockFace.NORTH, BlockFace.SOUTH,
                BlockFace.EAST, BlockFace.WEST, BlockFace.UP}) {
            Block support = block.getRelative(face);
            if (support.getType().isSolid() || plannedSupports.stream().anyMatch(location ->
                    location.getBlockX() == support.getX() && location.getBlockY() == support.getY()
                            && location.getBlockZ() == support.getZ())) return true;
        }
        return false;
    }

    private boolean isAdjacentSupport(Block target, Block support, List<Location> plannedSupports) {
        for (BlockFace face : new BlockFace[]{BlockFace.DOWN, BlockFace.NORTH, BlockFace.SOUTH,
                BlockFace.EAST, BlockFace.WEST, BlockFace.UP}) {
            Block adjacent = target.getRelative(face);
            if (!adjacent.equals(support)) continue;
            return support.getType().isSolid() || plannedSupports.stream().anyMatch(location ->
                    location.getBlockX() == support.getX() && location.getBlockY() == support.getY()
                            && location.getBlockZ() == support.getZ());
        }
        return false;
    }

    private BlockFace cardinalFace(Vector direction) {
        if (Math.abs(direction.getX()) > Math.abs(direction.getZ())) {
            return direction.getX() >= 0.0D ? BlockFace.EAST : BlockFace.WEST;
        }
        return direction.getZ() >= 0.0D ? BlockFace.SOUTH : BlockFace.NORTH;
    }

    private void faceBlock(Location blockLocation) {
        nativeBot.face(blockLocation.clone().add(0.5D, 0.5D, 0.5D), 180.0F);
    }

    private void finishBlockPlacement() {
        this.buildingSlot = -1;
        this.buildTargets = List.of();
        this.buildTargetIndex = 0;
        this.nextBuildPlacementTick = 0;
        this.buildWeaponReturnTick = 0;
        selectCombatItem();
        restoreCombatNavigation();
    }

    private int findPlaceableBlockSlot() {
        ItemStack[] contents = bot.getInventory().getStorageContents();
        for (int slot = 0; slot < contents.length; slot++) {
            ItemStack item = contents[slot];
            if (item == null || !item.getType().isBlock() || item.getType().hasGravity()) continue;
            if (item.getType() == Material.BEDROCK || item.getType() == Material.BARRIER) continue;
            return slot;
        }
        return -1;
    }

    private boolean needsPriorityHealing() {
        return difficulty.getHealHealth() > 0.0D && bot.getHealth() <= difficulty.getHealHealth();
    }

    private void interruptForPriorityHealing() {
        finishBotBlockHit();
        boolean restoreWeapon = this.buildingSlot >= 0 || this.holdingRod;
        if (this.foodSlot >= 0) finishFoodUse(false);
        if (this.buffPotionSlot >= 0) finishBuffPotionUse(false);
        if (this.bowSlot >= 0) finishBowCharge(false);
        if (this.buildingSlot >= 0) clearBlockPlacement();
        if (this.activeRodProjectile != null) {
            if (this.activeRodProjectile.isValid()) this.activeRodProjectile.remove();
            this.activeRodProjectile = null;
        }
        this.holdingRod = false;
        this.rodWeaponReturnTick = 0;
        if (restoreWeapon) selectCombatItem();
    }

    private void clearBlockPlacement() {
        this.buildingSlot = -1;
        this.buildTargets = List.of();
        this.buildTargetIndex = 0;
        this.nextBuildPlacementTick = 0;
        this.buildWeaponReturnTick = 0;
    }

    private boolean tryExtinguishFire() {
        if (!difficulty.isAntiFire() || !canBuild() || bot.getFireTicks() <= 0) return false;
        int waterSlot = findSlot(Material.WATER_BUCKET);
        Block feet = bot.getLocation().getBlock();
        if (waterSlot < 0 || !feet.getType().isAir()
                || !feet.getRelative(BlockFace.DOWN).getType().isSolid()) return false;

        BlockState original = feet.getState();
        recordPlacedBlock(original);
        holdItem(waterSlot);
        bot.swingMainHand();
        feet.setType(Material.WATER, false);
        bot.setFireTicks(0);
        Bukkit.getScheduler().runTaskLater(AlleyPlugin.getInstance(), () -> {
            if (feet.getType() == Material.WATER) original.update(true, false);
            if (!ended) selectCombatItem();
        }, 4L);
        return true;
    }

    private boolean beginFoodUse() {
        if (bot.getFoodLevel() >= 20) return false;

        ItemStack[] contents = bot.getInventory().getStorageContents();
        for (int slot = 0; slot < contents.length; slot++) {
            ItemStack item = contents[slot];
            if (!isOrdinaryFood(item)) continue;

            Consumable consumable = item.getData(DataComponentTypes.CONSUMABLE);
            int useTicks = Math.max(1, (int) (consumable.consumeSeconds() * 20.0F));
            this.foodSlot = holdItem(slot);
            this.foodFinishTick = this.ticks + useTicks;
            setBotSprinting(false);
            setNavigationSpeed(getCombatMovementSpeed() * EATING_SPEED_MULTIPLIER);
            startUsingHeldItem(useTicks);
            bot.updateInventory();
            return true;
        }
        return false;
    }

    private boolean isOrdinaryFood(ItemStack item) {
        if (item == null || item.isEmpty()
                || item.getType() == Material.GOLDEN_APPLE
                || item.getType() == Material.ENCHANTED_GOLDEN_APPLE) return false;

        FoodProperties food = item.getData(DataComponentTypes.FOOD);
        Consumable consumable = item.getData(DataComponentTypes.CONSUMABLE);
        return food != null && consumable != null && consumable.animation() == ItemUseAnimation.EAT;
    }

    private void tickFoodUse() {
        ItemStack heldItem = bot.getInventory().getItem(this.foodSlot);
        if (!isOrdinaryFood(heldItem) || bot.getFoodLevel() >= 20) {
            finishFoodUse(false);
            return;
        }

        setBotSprinting(false);
        setNavigationSpeed(getCombatMovementSpeed() * EATING_SPEED_MULTIPLIER);
        if (this.ticks < this.foodFinishTick) {
            keepUsingHeldItem(this.foodFinishTick - this.ticks + 1);
            return;
        }
        finishFoodUse(true);
    }

    private void finishFoodUse(boolean completeUse) {
        if (completeUse && bot.hasActiveItem()) bot.completeUsingActiveItem();
        stopUsingHeldItem();
        this.foodSlot = -1;
        this.foodFinishTick = 0;
        syncHeldItem();
        bot.updateInventory();
        selectCombatItem();
        restoreCombatNavigation();
    }

    private boolean placeTemporaryLava() {
        if (!canBuild()) return false;
        int lavaSlot = findSlot(Material.LAVA_BUCKET);
        Block target = player.getLocation().getBlock();
        if (lavaSlot < 0 || !target.getType().isAir()
                || !target.getRelative(BlockFace.DOWN).getType().isSolid()) return false;

        BlockState original = target.getState();
        recordPlacedBlock(original);
        holdItem(lavaSlot);
        bot.swingMainHand();
        target.setType(Material.LAVA, false);
        this.nextLavaTick = this.ticks + 120;
        this.nextBowTick = this.ticks + 15;
        this.nextRodTick = this.ticks + 15;
        Bukkit.getScheduler().runTaskLater(AlleyPlugin.getInstance(), () -> {
            if (!this.ended && target.getType() == Material.LAVA) original.update(true, false);
            if (!this.ended) selectCombatItem();
        }, difficulty.getLavaTicks());
        return true;
    }

    private boolean tryGoldenApple() {
        if (bot.getHealth() > difficulty.getHealHealth() || this.ticks < this.nextGoldenAppleTick) return false;
        int slot = findSlot(Material.GOLDEN_APPLE);
        if (slot < 0) return false;

        this.goldenAppleSlot = holdItem(slot);
        this.goldenAppleFinishTick = this.ticks + GOLDEN_APPLE_USE_TICKS;
        this.nextBowTick = this.ticks + 20;
        this.nextRodTick = this.ticks + 20;
        setBotSprinting(false);
        setNavigationSpeed(getCombatMovementSpeed() * EATING_SPEED_MULTIPLIER);
        startUsingHeldItem(GOLDEN_APPLE_USE_TICKS);
        bot.updateInventory();
        return true;
    }

    private void tickGoldenAppleUse() {
        ItemStack heldItem = bot.getInventory().getItem(this.goldenAppleSlot);
        if (heldItem == null || heldItem.getType() != Material.GOLDEN_APPLE) {
            finishGoldenAppleUse(false);
            return;
        }

        setBotSprinting(false);
        setNavigationSpeed(getCombatMovementSpeed() * EATING_SPEED_MULTIPLIER);
        if (this.ticks < this.goldenAppleFinishTick) {
            keepUsingHeldItem(Math.min(GOLDEN_APPLE_USE_TICKS,
                    this.goldenAppleFinishTick - this.ticks + 1));
            return;
        }

        finishGoldenAppleUse(true);
    }

    private void finishGoldenAppleUse(boolean completeUse) {
        if (completeUse && bot.hasActiveItem()) {
            bot.completeUsingActiveItem();
        }
        stopUsingHeldItem();
        if (completeUse) {
            this.nextGoldenAppleTick = this.ticks + GOLDEN_APPLE_REUSE_DELAY_TICKS;
        }
        this.goldenAppleSlot = -1;
        this.goldenAppleFinishTick = 0;
        syncHeldItem();
        bot.updateInventory();
        selectCombatItem();
        restoreCombatNavigation();
    }

    private void startUsingHeldItem(int remainingTicks) {
        bot.startUsingItem(EquipmentSlot.HAND);
        this.activeItemUseDuration = bot.hasActiveItem()
                ? Math.max(0, bot.getActiveItemRemainingTime()) : 0;
        keepUsingHeldItem(remainingTicks);
    }

    private void keepUsingHeldItem(int remainingTicks) {
        if (!bot.hasActiveItem() || this.activeItemUseDuration <= 0) return;
        bot.setActiveItemRemainingTime(Math.min(this.activeItemUseDuration, Math.max(1, remainingTicks)));
    }

    private void stopUsingHeldItem() {
        if (bot.hasActiveItem()) bot.clearActiveItem();
        this.activeItemUseDuration = 0;
    }

    private boolean beginBowCharge() {
        int bowSlot = findSlot(Material.BOW);
        if (bowSlot < 0 || findSlot(Material.ARROW) < 0) return false;

        ItemStack bow = bot.getInventory().getItem(bowSlot);
        this.bowSlot = holdItem(bowSlot);
        this.bowReleaseTick = this.ticks + BOW_CHARGE_TICKS;
        this.chargingBow = bow == null ? null : bow.clone();
        setBotSprinting(false);
        setNavigationSpeed(getCombatMovementSpeed() * EATING_SPEED_MULTIPLIER);
        startUsingHeldItem(BOW_USE_DURATION_TICKS);
        bot.updateInventory();
        return true;
    }

    private void tickBowCharge() {
        ItemStack heldItem = bot.getInventory().getItem(this.bowSlot);
        if (heldItem == null || heldItem.getType() != Material.BOW || findSlot(Material.ARROW) < 0) {
            finishBowCharge(false);
            return;
        }

        setBotSprinting(false);
        setNavigationSpeed(getCombatMovementSpeed() * EATING_SPEED_MULTIPLIER);
        Location origin = bot.getEyeLocation();
        nativeBot.face(origin.clone().add(solveArrowVelocity(origin, getBowArrowSpeed())),
                (float) difficulty.getAimSpeed());
        if (this.ticks < this.bowReleaseTick) return;
        finishBowCharge(true);
    }

    private void finishBowCharge(boolean releaseArrow) {
        stopUsingHeldItem();
        if (!releaseArrow) {
            clearBowCharge();
            return;
        }

        int arrowSlot = findSlot(Material.ARROW);
        if (arrowSlot < 0) {
            clearBowCharge();
            return;
        }
        double arrowSpeed = getBowArrowSpeed();
        Location origin = bot.getEyeLocation();
        Vector velocity = solveArrowVelocity(origin, arrowSpeed);
        nativeBot.face(origin.clone().add(velocity), 180.0F);
        Arrow arrow = bot.launchProjectile(Arrow.class, velocity);
        this.spawnedProjectiles.add(arrow);
        int punch = this.chargingBow == null ? 0 : this.chargingBow.getEnchantmentLevel(Enchantment.PUNCH);
        int powerLevel = this.chargingBow == null ? 0 : this.chargingBow.getEnchantmentLevel(Enchantment.POWER);
        arrow.setCritical(BOW_CHARGE_TICKS >= 20);
        arrow.setDamage(2.0 + powerLevel * 0.5);
        if (kit.isSettingEnabled(KitSettingOldSwordBlocking.class)) {
            LegacyProjectileData.markArrow(arrow, punch, powerLevel);
        } else {
            LegacyProjectileData.storeBowPunch(arrow, punch);
        }
        consumeOne(arrowSlot);
        bot.getWorld().playSound(bot.getLocation(), Sound.ENTITY_ARROW_SHOOT, 1.0F, 1.0F);
        this.nextBowTick = this.ticks + 35;
        this.nextRodTick = this.ticks + 15;
        clearBowCharge();
    }

    private void clearBowCharge() {
        this.bowSlot = -1;
        this.bowReleaseTick = 0;
        this.chargingBow = null;
        syncHeldItem();
        bot.updateInventory();
        selectCombatItem();
        restoreCombatNavigation();
    }

    private double getBowArrowSpeed() {
        double charge = Math.min(1.0D, BOW_CHARGE_TICKS / 20.0D);
        double power = (charge * charge + charge * 2.0D) / 3.0D;
        return Math.min(1.0D, power) * 3.0D;
    }

    private boolean castRod() {
        int rodSlot = findSlot(Material.FISHING_ROD);
        if (rodSlot < 0 || this.activeRodProjectile != null) return false;

        holdItem(rodSlot);
        nativeBot.face(player.getEyeLocation(), 180.0F);
        bot.swingMainHand();
        Vector velocity = player.getEyeLocation().toVector().subtract(bot.getEyeLocation().toVector())
                .normalize().multiply(1.5);
        try {
            this.activeRodProjectile = bot.launchProjectile(FishHook.class, velocity);
        } catch (IllegalArgumentException unsupportedHook) {
            this.activeRodProjectile = bot.launchProjectile(Snowball.class, velocity);
        }
        this.spawnedProjectiles.add(this.activeRodProjectile);
        bot.getWorld().playSound(bot.getLocation(), Sound.ENTITY_FISHING_BOBBER_THROW, 1.0F, 1.0F);
        this.holdingRod = true;
        this.rodWeaponReturnTick = this.ticks + ROD_WEAPON_RETURN_TICKS;
        this.removeRodTick = this.ticks + ROD_MAX_LIFETIME_TICKS;
        this.nextRodTick = this.ticks + 32;
        this.nextBowTick = this.ticks + 12;
        return true;
    }

    private void tickRodProjectile() {
        if (this.activeRodProjectile == null) return;

        boolean hit = !this.activeRodProjectile.isValid()
                || this.activeRodProjectile instanceof FishHook hook && hook.getHookedEntity() != null;
        if (hit) {
            finishRodHold(true);
            if (this.activeRodProjectile != null) {
                if (this.activeRodProjectile.isValid()) this.activeRodProjectile.remove();
                this.activeRodProjectile = null;
            }
            return;
        }
        if (this.ticks >= this.rodWeaponReturnTick) finishRodHold(false);
        if (this.activeRodProjectile != null && this.ticks >= this.removeRodTick) {
            if (this.activeRodProjectile.isValid()) this.activeRodProjectile.remove();
            this.activeRodProjectile = null;
        }
    }

    private void finishRodHold(boolean retract) {
        if (retract && this.activeRodProjectile != null) {
            if (this.activeRodProjectile.isValid()) this.activeRodProjectile.remove();
            this.activeRodProjectile = null;
        }
        if (!this.holdingRod) return;
        this.holdingRod = false;
        this.rodWeaponReturnTick = 0;
        selectCombatItem();
        restoreCombatNavigation();
    }

    private Vector solveArrowVelocity(Location origin, double speed) {
        Vector targetOrigin = player.getEyeLocation().toVector();
        Vector targetMotion = player.getVelocity().clone();
        Vector best = targetOrigin.clone().subtract(origin.toVector()).normalize().multiply(speed);
        double bestError = Double.MAX_VALUE;

        for (int flightTicks = 1; flightTicks <= 60; flightTicks++) {
            double dragPower = Math.pow(ARROW_DRAG, flightTicks);
            double dragSum = (1.0D - dragPower) / (1.0D - ARROW_DRAG);
            double leadTicks = Math.min(flightTicks, 12) * 0.8D;
            Vector target = targetOrigin.clone().add(targetMotion.clone().multiply(leadTicks));
            Vector displacement = target.subtract(origin.toVector());
            double gravityDrop = ARROW_GRAVITY / (1.0D - ARROW_DRAG) * (flightTicks - dragSum);
            Vector required = new Vector(displacement.getX() / dragSum,
                    (displacement.getY() + gravityDrop) / dragSum,
                    displacement.getZ() / dragSum);
            double requiredSpeed = required.length();
            double error = Math.abs(requiredSpeed - speed);
            if (required.getY() > speed * 0.85D) error += required.getY();
            if (error < bestError && requiredSpeed > 1.0E-6D) {
                bestError = error;
                best = required.multiply(speed / requiredSpeed);
            }
        }
        return best;
    }

    private int findSlot(Material material) {
        ItemStack[] contents = bot.getInventory().getStorageContents();
        for (int slot = 0; slot < contents.length; slot++) {
            if (contents[slot] != null && contents[slot].getType() == material) return slot;
        }
        return -1;
    }

    private void consumeOne(int slot) {
        ItemStack item = bot.getInventory().getItem(slot);
        if (item == null) return;
        if (item.getAmount() <= 1) bot.getInventory().setItem(slot, null);
        else item.setAmount(item.getAmount() - 1);
    }

    private int holdItem(int slot) {
        if (slot <= 8) {
            bot.getInventory().setHeldItemSlot(slot);
            syncHeldItem();
            return slot;
        }
        int hotbarSlot = 1;
        ItemStack hotbarItem = bot.getInventory().getItem(hotbarSlot);
        bot.getInventory().setItem(hotbarSlot, bot.getInventory().getItem(slot));
        bot.getInventory().setItem(slot, hotbarItem);
        bot.getInventory().setHeldItemSlot(hotbarSlot);
        syncHeldItem();
        return hotbarSlot;
    }

    private void syncBotEquipment() {
        EntityEquipment equipment = bot.getEquipment();
        if (equipment == null) return;

        ItemStack[] armor = bot.getInventory().getArmorContents();
        ItemStack boots = armor.length > 0 ? armor[0] : null;
        ItemStack leggings = armor.length > 1 ? armor[1] : null;
        ItemStack chestplate = armor.length > 2 ? armor[2] : null;
        ItemStack helmet = armor.length > 3 ? armor[3] : null;
        equipment.setBoots(boots);
        equipment.setLeggings(leggings);
        equipment.setChestplate(chestplate);
        equipment.setHelmet(helmet);
        equipment.setItemInOffHand(bot.getInventory().getItemInOffHand());

        syncHeldItem();
        bot.updateInventory();
    }

    private void syncHeldItem() {
        ItemStack heldItem = bot.getInventory().getItemInMainHand();
        EntityEquipment equipment = bot.getEquipment();
        if (equipment != null) {
            equipment.setItemInMainHand(heldItem);
        }
    }

    public boolean handleGomokuPlacement(Player player) {
        return this.gomokuGame != null && this.gomokuGame.handlePlacement(player);
    }

    public void surrenderGomoku() {
        if (this.gomokuGame != null) this.gomokuGame.surrender();
    }

    public String getGomokuPlayerColorName() {
        return this.gomokuGame == null ? "&7Waiting" : this.gomokuGame.getPlayerColorName();
    }

    public String getGomokuCurrentPlayerName() {
        return this.gomokuGame == null ? "Waiting" : this.gomokuGame.getCurrentPlayerName();
    }

    public int getGomokuRemainingTurnSeconds() {
        return this.gomokuGame == null ? 30 : this.gomokuGame.getRemainingTurnSeconds();
    }

    public int getGomokuPlacedStones() {
        return this.gomokuGame == null ? 0 : this.gomokuGame.getPlacedStones();
    }

    private void selectCombatItem() {
        ItemStack[] storage = bot.getInventory().getStorageContents();
        for (int slot = 0; slot < storage.length; slot++) {
            ItemStack item = storage[slot];
            if (item == null) continue;
            String name = item.getType().name();
            if (name.endsWith("_SWORD") || name.endsWith("_AXE")) {
                holdItem(slot);
                return;
            }
        }
    }

    public void finish(boolean playerWon) {
        if (playerWon && beginNaturalBotDeath()) return;
        finish(playerWon, false);
    }

    private boolean beginNaturalBotDeath() {
        if (this.forcingBotDeath || this.bot == null || this.bot.isDead() || this.bot.getHealth() <= 0.0D) {
            return false;
        }

        this.forcingBotDeath = true;
        this.running = false;
        if (this.task != null) this.task.cancel();
        if (this.nativeBot != null) this.nativeBot.stopMoving();
        this.bot.playHurtAnimation(0.0F);
        this.bot.setHealth(0.0D);
        Bukkit.getScheduler().runTaskLater(AlleyPlugin.getInstance(), () -> {
            if (!this.ended) finish(true, false);
        }, 2L);
        return true;
    }

    public void handleNaturalDeath(Player victim) {
        if (victim == null) return;
        boolean playerWon = this.bot != null && victim.getUniqueId().equals(this.bot.getUniqueId());
        finish(playerWon, true);
    }

    private void finish(boolean playerWon, boolean naturalDeath) {
        if (ended) return;
        ended = true;
        running = false;
        if (task != null) task.cancel();
        finishBotBlockHit();
        if (bot != null) stopUsingHeldItem();
        if (nativeBot != null) nativeBot.stopMoving();
        if (!naturalDeath || !playerWon) freezeBotForResults();

        if (playerWon && !naturalDeath && bot != null) {
            bot.playHurtAnimation(0.0F);
        }
        concludeMatchContext(playerWon);
        if (playerWon && !naturalDeath && bot != null) {
            Bukkit.getScheduler().runTaskLater(AlleyPlugin.getInstance(), () -> {
                if (this.nativeBot != null && this.nativeBot.isSpawned() && this.bot != null) {
                    this.bot.playEffect(EntityEffect.DEATH);
                }
            }, DEATH_EFFECT_DELAY_TICKS);
            Bukkit.getScheduler().runTaskLater(AlleyPlugin.getInstance(), this::removeBotNpc, BOT_REMOVE_DELAY_TICKS);
        } else if (playerWon) {
            Bukkit.getScheduler().runTaskLater(AlleyPlugin.getInstance(), this::removeBotNpc, BOT_REMOVE_DELAY_TICKS);
        } else {
            Bukkit.getScheduler().runTaskLater(AlleyPlugin.getInstance(), this::removeBotNpc, BOT_REMOVE_DELAY_TICKS);
        }

        returnTask = Bukkit.getScheduler().runTaskLater(
                AlleyPlugin.getInstance(), this::returnToLobby,
                Math.max(returnDelayTicks, BOT_REMOVE_DELAY_TICKS));
    }

    private void freezeBotForResults() {
        if (bot == null) return;
        if (botSpawnLocation != null && (bot.getWorld() != botSpawnLocation.getWorld() || isBelowArena(bot))) {
            bot.teleport(botSpawnLocation);
        }
        bot.setGravity(false);
        bot.setVelocity(new Vector());
        bot.setFallDistance(0.0F);
    }

    private void concludeMatchContext(boolean playerWon) {
        if (this.matchContext == null || this.bot == null) return;

        MatchGamePlayer human = this.matchContext.getGamePlayer(this.player);
        MatchGamePlayer computer = this.matchContext.getGamePlayer(this.bot);
        if (human == null || computer == null) return;

        MatchGamePlayer winner = playerWon ? human : computer;
        MatchGamePlayer loser = playerWon ? computer : human;
        loser.setDead(true);
        if (playerWon) {
            this.matchContext.announceDeath(this.bot, this.player, EntityDamageEvent.DamageCause.CUSTOM);
        } else {
            winner.getData().incrementKills();
        }

        this.matchContext.playDeathCosmetics(
                playerWon ? this.bot : this.player,
                playerWon ? this.player : this.bot);
        this.matchContext.setState(MatchState.ENDING_ROUND);
        this.matchContext.handleRoundEnd();

        int snapshotCount = this.matchContext.getSnapshots().size();
        // Only use the fallback if the native player was not visible during setup.
        if (!this.matchContext.isPlayerInitialized(this.bot.getUniqueId())) {
            this.matchContext.createSnapshot(this.bot);
        }
        SnapshotService snapshots = AlleyPlugin.getInstance().getService(SnapshotService.class);
        for (int index = snapshotCount; index < this.matchContext.getSnapshots().size(); index++) {
            snapshots.addSnapshot(this.matchContext.getSnapshots().get(index));
        }
        this.matchContext.setState(MatchState.ENDING_MATCH);
        if (this.matchContext.getRunnable() != null) this.matchContext.getRunnable().cancel();
    }

    private void returnToLobby() {
        clearCombatSystems();
        restoreBlocks();
        cleanupBot();
        if (this.matchContext != null) {
            this.matchContext.endMatch();
        } else {
            restorePlayerToLobby();
            deleteTemporaryArena();
        }
        service.complete(this);
    }

    public void abortStart() {
        if (task != null) task.cancel();
        ended = true;
        try {
            runAbortCleanup("match context", this::discardMatchContext);
            runAbortCleanup("combat state", this::clearCombatSystems);
            runAbortCleanup("bot player", this::cleanupBot);
            runAbortCleanup("changed blocks", this::restoreBlocks);
            runAbortCleanup("player lobby state", this::restorePlayerToLobby);
            runAbortCleanup("temporary arena", this::deleteTemporaryArena);
        } finally {
            service.complete(this);
        }
    }

    private void runAbortCleanup(String step, Runnable cleanup) {
        try {
            cleanup.run();
        } catch (RuntimeException exception) {
            AlleyPlugin.getInstance().getLogger().log(java.util.logging.Level.WARNING,
                    "Could not clean up bot match " + step + " for " + player.getName(), exception);
        }
    }

    public void shutdown() {
        if (task != null) task.cancel();
        if (returnTask != null) returnTask.cancel();
        ended = true;
        discardMatchContext();
        clearCombatSystems();
        cleanupBot();
        restoreBlocks();
        deleteTemporaryArena();
        Profile profile = AlleyPlugin.getInstance().getService(ProfileService.class).getProfile(player.getUniqueId());
        if (profile != null) profile.setState(ProfileState.LOBBY);
        service.complete(this);
    }

    private void discardMatchContext() {
        if (this.matchContext == null) return;
        if (this.matchContext.getRunnable() != null) this.matchContext.getRunnable().cancel();
        AlleyPlugin.getInstance().getService(MatchService.class).removeMatch(this.matchContext);
        this.matchContext = null;
    }

    private void restorePlayerToLobby() {
        if (!player.isOnline()) return;

        Profile profile = AlleyPlugin.getInstance().getService(ProfileService.class).getProfile(player.getUniqueId());
        if (profile == null) return;

        // A player who clicked Play Again before returning was already released and queued;
        // keep their queue state and the queue hotbar instead of forcing the lobby state.
        // 在返回大厅前点击"再来一局"的玩家已被释放并入队：保持其排队状态和排队hotbar。
        boolean queued = profile.getQueueProfile() != null;

        if (!queued) {
            profile.setState(ProfileState.LOBBY);
        }
        profile.setMatch(null);
        PlayerUtil.reset(player, true, true);
        player.setWalkSpeed(originalWalkSpeed <= 0.0F ? 0.2F : originalWalkSpeed);
        Party party = AlleyPlugin.getInstance().getService(PartyService.class).getParty(player);
        profile.setParty(party);
        AlleyPlugin.getInstance().getService(SpawnService.class).teleportToSpawn(player);
        MatchListener.clearDeadPlayerPickupBlock(player);
        AlleyPlugin.getInstance().getService(HotbarService.class)
                .applyHotbarItems(player, queued ? HotbarType.QUEUE
                        : (party == null ? HotbarType.LOBBY : HotbarType.PARTY));
        // No Play Again paper after a bot match — the player returns to the lobby
        // without a requeue shortcut.
        // Bot对局结束后不发放"再来一局"纸——玩家直接返回大厅。
        AlleyPlugin.getInstance().getService(VisibilityService.class).updateVisibility(player);
        AlleyPlugin.getInstance().getService(MusicService.class).startMusic(player);
    }

    private void cleanupBot() {
        if (this.gomokuGame != null) this.gomokuGame.shutdown();
        if (this.activeRodProjectile != null && this.activeRodProjectile.isValid()) {
            this.activeRodProjectile.remove();
        }
        for (Projectile projectile : this.spawnedProjectiles) {
            if (projectile != null && projectile.isValid()) projectile.remove();
        }
        this.spawnedProjectiles.clear();
        UUID botId = bot == null ? null : bot.getUniqueId();
        if (bot != null) AlleyPlugin.getInstance().getService(CombatService.class).removeLastAttacker(bot, true);
        if (botId != null) {
            AlleyPlugin.getInstance().getService(KnockbackManager.class).removePlayer(botId);
        }
        removeBotNpc();
        if (botId != null) {
            AlleyPlugin.getInstance().getService(ProfileService.class).getProfiles().remove(botId);
        }
    }

    private void removeBotNpc() {
        if (this.nativeBot == null) return;
        this.nativeBot.remove();
        this.nativeBot = null;
    }

    private void clearCombatSystems() {
        finishBotBlockHit();
        KnockbackManager manager = AlleyPlugin.getInstance().getService(KnockbackManager.class);
        manager.clearKnockback(player);
        if (bot != null) manager.clearKnockback(bot);

        MatchService matchService = AlleyPlugin.getInstance().getService(MatchService.class);
        if (matchService instanceof MatchServiceImpl impl && impl.getLegacyCombatService() != null) {
            impl.getLegacyCombatService().removeAll(player);
            if (bot != null) impl.getLegacyCombatService().removeAll(bot);
        }
    }

    public void recordPlacedBlock(BlockState originalState) {
        changedBlocks.putIfAbsent(originalState.getLocation(), originalState);
    }

    public boolean canBreak(Block block) {
        Location location = block.getLocation();
        if (changedBlocks.containsKey(location)) return true;
        if (!kit.isSettingEnabled(KitSettingBreakArenaBlocksImpl.class)) return false;
        changedBlocks.put(location, block.getState());
        return true;
    }

    public boolean canBuild() {
        return kit.isSettingEnabled(KitSettingBuildImpl.class);
    }

    private void restoreBlocks() {
        changedBlocks.values().stream().toList().reversed().forEach(state -> state.update(true, false));
        changedBlocks.clear();
    }

    private void deleteTemporaryArena() {
        if (arena instanceof StandAloneArena standAloneArena) {
            AlleyPlugin.getInstance().getService(ArenaService.class).deleteTemporaryArena(standAloneArena);
        }
    }

    private boolean isBelowArena(Player target) {
        double minimumY = arena.getMinimum() == null ? arena.getPos1().getY() - 20.0 : arena.getMinimum().getY() - 10.0;
        return target.getLocation().getY() < minimumY;
    }

    private ItemStack[] cloneItems(ItemStack[] items) {
        if (items == null) return new ItemStack[0];
        ItemStack[] clone = new ItemStack[items.length];
        for (int i = 0; i < items.length; i++) clone[i] = items[i] == null ? null : items[i].clone();
        return clone;
    }

    private double random(double amount) {
        return amount <= 0.0 ? 0.0 : ThreadLocalRandom.current().nextDouble(-amount, amount);
    }

    private void trace(String message) {
        if (!this.debug) return;
        AlleyPlugin.getInstance().getLogger().info(
                "[BotDebug:" + player.getName() + "] " + message);
    }

    private String formatVector(Vector vector) {
        return String.format(java.util.Locale.ROOT, "(%.4f, %.4f, %.4f)",
                vector.getX(), vector.getY(), vector.getZ());
    }

    public UUID getPlayerId() {
        return player.getUniqueId();
    }

    public UUID getBotId() {
        return bot == null ? null : bot.getUniqueId();
    }
}
