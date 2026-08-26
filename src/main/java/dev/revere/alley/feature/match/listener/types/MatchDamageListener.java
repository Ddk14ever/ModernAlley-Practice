package dev.revere.alley.feature.match.listener.types;

import dev.revere.alley.AlleyPlugin;
import dev.revere.alley.feature.combat.CombatAttribution;
import dev.revere.alley.feature.combat.CombatService;
import dev.revere.alley.feature.bot.BotService;
import dev.revere.alley.feature.bot.match.BotMatchSession;
import dev.revere.alley.feature.kit.Kit;
import dev.revere.alley.feature.kit.setting.types.mechanic.KitSettingNoDamageImpl;
import dev.revere.alley.feature.kit.setting.types.mechanic.KitSettingNoFallDamageImpl;
import dev.revere.alley.feature.kit.setting.types.mechanic.KitSettingLuckyBlock;
import dev.revere.alley.feature.kit.setting.types.mode.KitSettingBoxing;
import dev.revere.alley.feature.kit.setting.types.mode.KitSettingHideAndSeek;
import dev.revere.alley.feature.kit.setting.types.mode.KitSettingSpleef;
import dev.revere.alley.feature.kit.setting.types.mode.KitSettingSumo;
import dev.revere.alley.feature.kit.setting.types.visual.KitSettingBowShotIndicator;
import dev.revere.alley.feature.kit.setting.types.visual.KitSettingHealthBar;
import dev.revere.alley.feature.knockback.KnockbackManager;
import dev.revere.alley.feature.match.Match;
import dev.revere.alley.feature.match.MatchState;
import dev.revere.alley.feature.event.skywars.SkyWarsMatch;
import dev.revere.alley.feature.match.internal.types.HideAndSeekMatch;
import dev.revere.alley.feature.match.model.internal.MatchGamePlayer;
import dev.revere.alley.feature.match.model.GameParticipant;
import dev.revere.alley.core.profile.ProfileService;
import dev.revere.alley.core.profile.Profile;
import dev.revere.alley.core.profile.enums.ProfileState;
import dev.revere.alley.adapter.core.CoreAdapter;
import dev.revere.alley.common.reflect.ReflectionService;
import dev.revere.alley.common.reflect.internal.types.ActionBarReflectionServiceImpl;
import dev.revere.alley.common.text.CC;
import dev.revere.alley.common.text.Symbol;
import org.bukkit.entity.Arrow;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.EntityRegainHealthEvent;
import org.bukkit.event.player.PlayerToggleSprintEvent;

/**
 * Match damage listener for handling damage events during matches.
 * 比赛伤害监听器，用于处理比赛期间的伤害事件。
 * @author Emmy
 * @project Alley
 * @since 08/02/2025
 */
public class MatchDamageListener implements Listener {
    @EventHandler
    private void onEntityDamage(EntityDamageEvent event) {
        if (!(event.getEntity() instanceof Player)) return;
        Player player = (Player) event.getEntity();
        ProfileService profileService = AlleyPlugin.getInstance().getService(ProfileService.class);
        Profile profile = profileService.getProfile(player.getUniqueId());

        if (profile.getState() == ProfileState.SPECTATING) event.setCancelled(true);
        if (profile.getState() == ProfileState.PLAYING) {
            Match match = profile.getMatch();
            if (match == null) return;
            if (match instanceof SkyWarsMatch skyWarsMatch && skyWarsMatch.isProtectionActive()) {
                event.setCancelled(true);
                return;
            }
            Kit matchKit = match.getKit();

            if (matchKit.isSettingEnabled(KitSettingNoFallDamageImpl.class)
                    && event.getCause() == EntityDamageEvent.DamageCause.FALL) {
                event.setCancelled(true);
            }

            if (event.getCause() == EntityDamageEvent.DamageCause.FALL
                    && (matchKit.isSettingEnabled(KitSettingBoxing.class)
                    || matchKit.isSettingEnabled(KitSettingSumo.class)
                    || matchKit.isSettingEnabled(KitSettingSpleef.class))) {
                event.setCancelled(true);
            }

            if (match.getState() != MatchState.RUNNING) {
                event.setCancelled(true);
                return;
            }

            if (match.getGamePlayer(player).isDead()) {
                event.setCancelled(true);
                return;
            }

            // LuckyBlock intentionally keeps the arena dangerous: every
            // non-player living entity may damage active players, including
            // projectiles whose causing entity is a mob. This must bypass modes
            // that normally reduce all combat damage to zero (Boxing, Sumo,
            // Spleef and NoDamage), without opening player-versus-player damage.
            if (isLuckyBlockMobDamage(event, match)) {
                return;
            }

            if (matchKit.isSettingEnabled(KitSettingBoxing.class)
                    || matchKit.isSettingEnabled(KitSettingSumo.class)
                    || matchKit.isSettingEnabled(KitSettingSpleef.class)
                    || matchKit.isSettingEnabled(KitSettingNoDamageImpl.class)) {
                // A cancelled event must not open a fresh hurt window. This is
                // especially important for fall/no-fall damage in Boxing/Sumo:
                // the event is cancelled above and must not make the next attack
                // look like it arrived during a newly armed i-frame.
                if (event.isCancelled()) return;
                event.setDamage(0);
                player.setHealth(player.getMaxHealth());
                // The final damage is intentionally zero. The monitor handler below
                // arms the window after all listeners have accepted this event.
                player.updateInventory();
            }
        }
    }

    /**
     * NMS skips refreshing hurt resistance when a mode changes the final damage
     * to zero. Arm the window only after every damage listener accepted the event;
     * this prevents a later cancellation (fall protection, teams, etc.) from
     * opening a phantom hit-delay window.
     */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    private void armNoDamageModeWindow(EntityDamageEvent event) {
        if (!(event.getEntity() instanceof Player player)) return;

        Profile profile = AlleyPlugin.getInstance().getService(ProfileService.class)
                .getProfile(player.getUniqueId());
        if (profile == null || profile.getState() != ProfileState.PLAYING) return;
        Match match = profile.getMatch();
        if (match == null || match.getState() != MatchState.RUNNING) return;
        MatchGamePlayer gamePlayer = match.getGamePlayer(player);
        if (gamePlayer == null || gamePlayer.isDead()) return;

        Kit kit = match.getKit();
        if (isLuckyBlockMobDamage(event, match)) return;
        if (!(kit.isSettingEnabled(KitSettingBoxing.class)
                || kit.isSettingEnabled(KitSettingSumo.class)
                || kit.isSettingEnabled(KitSettingSpleef.class)
                || kit.isSettingEnabled(KitSettingNoDamageImpl.class))) return;

        int maximum = player.getMaximumNoDamageTicks();
        if (maximum <= 0 || player.getNoDamageTicks() > maximum / 2) return;
        player.setNoDamageTicks(maximum);
    }

    /**
     * Accumulates the amount of health a player naturally regenerates during a match
     * (RegainReason.REGEN), shown in the post-match snapshot as "Regen".
     * 统计玩家在对局中自然回血的血量（显示为快照里的 Regen）。
     */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onNaturalRegen(EntityRegainHealthEvent event) {
        if (event.getRegainReason() != EntityRegainHealthEvent.RegainReason.REGEN) return;
        if (!(event.getEntity() instanceof Player player)) return;

        Profile profile = AlleyPlugin.getInstance().getService(ProfileService.class).getProfile(player.getUniqueId());
        if (profile == null || profile.getState() != ProfileState.PLAYING) return;
        Match match = profile.getMatch();
        if (match == null) return;

        MatchGamePlayer gamePlayer = match.getGamePlayer(player);
        if (gamePlayer != null) {
            gamePlayer.getData().addRegen(event.getAmount());
        }
    }

    /**
     * Tracks W-tap attempts: timestamps when a player stops sprinting (releases W) during a match.
     * W-tap 尝试统计：记录玩家在对局中停止疾跑（松开 W）的时间点，用于后续判定 W-tap。
     */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onSprintToggle(PlayerToggleSprintEvent event) {
        Player player = event.getPlayer();
        Profile profile = AlleyPlugin.getInstance().getService(ProfileService.class).getProfile(player.getUniqueId());
        if (profile == null || profile.getState() != ProfileState.PLAYING) return;
        Match match = profile.getMatch();
        if (match == null) return;

        MatchGamePlayer gamePlayer = match.getGamePlayer(player);
        if (gamePlayer != null) {
            KnockbackManager knockbackManager = AlleyPlugin.getInstance()
                    .getService(KnockbackManager.class);
            if (knockbackManager.isLegacyKnockback(player)) return;
            gamePlayer.getData().onSprintToggle(event.isSprinting());
        }
    }

    @EventHandler
    private void onEntityDamageByEntity(EntityDamageByEntityEvent event) {
        if (event.isCancelled() || !(event.getEntity() instanceof Player)) {
            return;
        }

        Player damaged = (Player) event.getEntity();
        Player attacker = CombatAttribution.getAttacker(event);
        boolean isMelee = event.getDamager() instanceof Player;
        if (attacker == null) {
            return;
        }

        ProfileService profileService = AlleyPlugin.getInstance().getService(ProfileService.class);

        Profile damagedProfile = profileService.getProfile(damaged.getUniqueId());
        Profile attackerProfile = profileService.getProfile(attacker.getUniqueId());

        if (damagedProfile.getState() == ProfileState.SPECTATING || attackerProfile.getState() == ProfileState.SPECTATING) {
            event.setCancelled(true);
            return;
        }

        if (damagedProfile.getState() == ProfileState.PLAYING && attackerProfile.getState() == ProfileState.PLAYING) {
            Match match = damagedProfile.getMatch();
            if (match == null || attackerProfile.getMatch() != match) {
                event.setCancelled(true);
                return;
            }

            if (match.getState() != MatchState.RUNNING) {
                event.setCancelled(true);
                return;
            }

            if (match instanceof SkyWarsMatch skyWarsMatch && skyWarsMatch.isProtectionActive()) {
                event.setCancelled(true);
                return;
            }

            if (match.getGamePlayer(damaged).isDead() || match.getGamePlayer(attacker).isDead()) {
                event.setCancelled(true);
                return;
            }

            if (match.getGamePlayer(attacker).isDead()) {
                event.setCancelled(true);
                return;
            }

            if (!attacker.getUniqueId().equals(damaged.getUniqueId()) && match.isInSameTeam(attacker, damaged)) {
                if (match.getKit().isSettingEnabled(KitSettingHideAndSeek.class)) {
                    HideAndSeekMatch matchHideAndSeek = (HideAndSeekMatch) attackerProfile.getMatch();
                    GameParticipant<MatchGamePlayer> seekers = matchHideAndSeek.getParticipantA();

                    boolean isSeeker = seekers.containsPlayer(attacker.getUniqueId());

                    if (matchHideAndSeek.getGameEndTask() == null && isSeeker) {
                        return;
                    }
                }

                event.setCancelled(true);
                return;
            }

            // A higher hit inside the legacy hurt window only
            // contributes its damage delta. It is not a new Boxing/combo hit.
            KnockbackManager knockbackManager = AlleyPlugin.getInstance()
                    .getService(KnockbackManager.class);
            if (knockbackManager.wasInsideHurtResistanceWindow(damaged)) return;

            if (!attacker.getUniqueId().equals(damaged.getUniqueId())) {
                // Fishing rod hits are marked with a scoreboard tag — skip hit counting
                boolean isRodHit = damaged.getScoreboardTags().contains("alley_rod");
                if (isRodHit) {
                    damaged.removeScoreboardTag("alley_rod");
                }

                // Only count melee left-click attacks as hits; exclude projectiles (rod/snowball/egg/bow)
                if (isMelee && !isRodHit) {
                    var attackerData = attackerProfile.getMatch().getGamePlayer(attacker).getData();
                    attackerData.handleAttack();
                    if (knockbackManager.isLegacyKnockback(attacker)) {
                        var result = knockbackManager.recordLegacyMeleeHit(attacker);
                        attackerData.handleLegacyWTap(result.attempt(), result.success());
                    } else {
                        // Default keeps the original Bukkit sprint-event statistic.
                        attackerData.handleWTap(attacker.isSprinting());
                    }
                }
                damagedProfile.getMatch().getGamePlayer(damaged).getData().resetCombo();

                GameParticipant<MatchGamePlayer> participant = match.getParticipant(attacker);
                GameParticipant<MatchGamePlayer> opponent = match.getParticipant(damaged);

                if (participant != null && opponent != null) {
                    // Only count melee hits — projectiles (egg/snowball/bow)
                    // should not advance the boxing hit counter
                    if (isMelee && !isRodHit) {
                        participant.setTeamHits(participant.getTeamHits() + 1);
                    }

                    if (match.getKit().isSettingEnabled(KitSettingBowShotIndicator.class) && event.getDamager() instanceof Arrow) {
                        double finalHealth = damaged.getHealth() - event.getFinalDamage();
                        finalHealth = Math.max(0, finalHealth);

                        if (finalHealth > 0) {
                            attacker.sendMessage(CC.translate(AlleyPlugin.getInstance().getService(CoreAdapter.class).getCore().getPlayerColor(damaged) + damaged.getName() + " &7&l" + Symbol.ARROW_R + " &6" + String.format("%.1f", finalHealth) + " &c" + Symbol.HEART));
                        }
                    }

                    if (match.getKit().isSettingEnabled(KitSettingBoxing.class)) {
                        int lowestPlayerCount = match.getParticipants().stream()
                                .mapToInt(p -> p.getPlayers().size())
                                .filter(size -> size > 0)
                                .min()
                                .orElse(1);

                        int requiredHits = lowestPlayerCount * 100;

                        if (participant.getTeamHits() >= requiredHits) {
                            BotMatchSession botSession = AlleyPlugin.getInstance()
                                    .getService(BotService.class).getSession(attacker);
                            if (botSession == null) {
                                botSession = AlleyPlugin.getInstance()
                                        .getService(BotService.class).getSession(damaged);
                            }

                            if (botSession != null) {
                                botSession.finish(attacker.getUniqueId().equals(botSession.getPlayerId()));
                            } else {
                                opponent.getPlayers().forEach(matchGamePlayer -> {
                                    Player opponentPlayer = matchGamePlayer.getTeamPlayer();
                                    if (opponentPlayer != null) {
                                        match.handleDeath(opponentPlayer, EntityDamageEvent.DamageCause.ENTITY_ATTACK);
                                    }
                                });
                            }
                        }
                    }
                }
            }
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    private void onEntityDamageByEntityMonitor(EntityDamageByEntityEvent event) {
        if (event.getEntity() instanceof Player) {
            Player attacker = CombatAttribution.getAttacker(event);
            if (attacker == null) {
                return;
            }

            Profile profile = AlleyPlugin.getInstance().getService(ProfileService.class).getProfile(event.getEntity().getUniqueId());
            if (profile.getState() == ProfileState.SPECTATING) {
                event.setCancelled(true);
                return;
            }

            if (profile.getState() == ProfileState.PLAYING) {
                Player player = (Player) event.getEntity();

                if (AlleyPlugin.getInstance().getService(KnockbackManager.class)
                        .wasInsideHurtResistanceWindow(player)) return;

                AlleyPlugin.getInstance().getService(CombatService.class).setLastAttacker(player, attacker);

                if (profile.getMatch().getKit().isSettingEnabled(KitSettingHealthBar.class)) {
                    AlleyPlugin.getInstance().getService(ReflectionService.class).getReflectionService(ActionBarReflectionServiceImpl.class).visualizeTargetHealth(attacker, player);
                }
            }
        }
    }

    private boolean isLuckyBlockMobDamage(EntityDamageEvent event, Match match) {
        if (!(event instanceof EntityDamageByEntityEvent damageEvent)
                || !match.getKit().isSettingEnabled(KitSettingLuckyBlock.class)) {
            return false;
        }

        Entity causingEntity = damageEvent.getDamageSource().getCausingEntity();
        return isNonPlayerLivingEntity(damageEvent.getDamager())
                || isNonPlayerLivingEntity(causingEntity);
    }

    private boolean isNonPlayerLivingEntity(Entity entity) {
        return entity instanceof LivingEntity && !(entity instanceof Player);
    }
}
