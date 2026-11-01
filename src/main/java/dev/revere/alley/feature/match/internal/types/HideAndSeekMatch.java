package dev.revere.alley.feature.match.internal.types;

import dev.revere.alley.common.ListenerUtil;
import dev.revere.alley.common.PlayerUtil;
import dev.revere.alley.common.text.CC;
import dev.revere.alley.core.locale.LocaleService;
import dev.revere.alley.core.locale.internal.impl.VisualsLocaleImpl;
import dev.revere.alley.core.locale.internal.impl.message.GameMessagesLocaleImpl;
import dev.revere.alley.core.profile.ProfileService;
import dev.revere.alley.feature.combat.CombatService;
import dev.revere.alley.feature.arena.Arena;
import dev.revere.alley.feature.arena.ArenaService;
import dev.revere.alley.feature.kit.Kit;
import dev.revere.alley.feature.kit.KitService;
import dev.revere.alley.feature.match.listener.MatchListener;
import dev.revere.alley.feature.match.menu.HideAndSeekRoleSelectMenu;
import dev.revere.alley.feature.match.model.GameParticipant;
import dev.revere.alley.feature.match.model.GamePlayer;
import dev.revere.alley.feature.match.model.TeamGameParticipant;
import dev.revere.alley.feature.match.model.internal.MatchGamePlayer;
import dev.revere.alley.feature.queue.Queue;
import lombok.Getter;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Sound;
import org.bukkit.boss.BarColor;
import org.bukkit.boss.BarStyle;
import org.bukkit.boss.BossBar;
import org.bukkit.entity.Player;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.scheduler.BukkitTask;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
/**
 * @author Ddk1 ClaudeCode
 * @project Alley
 * @since 27/06/2026
 */
@Getter
public class HideAndSeekMatch extends DefaultMatch {
    private final GameParticipant<MatchGamePlayer> participantA;
    private final GameParticipant<MatchGamePlayer> participantB;

    private BukkitTask seekerReleaseTask;
    private BukkitTask gameEndTask;
    private BukkitTask bossBarUpdateTask;
    private BukkitTask intermissionTask;
    private BukkitTask roleSelectTask;
    private boolean timeExpired = false;
    private boolean intermissionPhase = true;
    private boolean selectingRoles = false;

    private final int hidingTimeSeconds = 180;
    private final int gameTimeSeconds = 600;
    private final int hiderHealthHearts = 3;
    private final int intermissionSeconds = 45;
    private final int roleSelectSeconds = 60;
    private final int seekerCount;

    private final Map<UUID, Boolean> roleChoices = new LinkedHashMap<>();
    private final List<UUID> roleChoosers = new ArrayList<>();
    private long phaseStartMillis;

    private BossBar countdownBar;

    private final ArenaService arenaService = plugin.getService(ArenaService.class);
    private final Arena intermissionArena = arenaService.getArenaByName("IntermissionArena");

    private Location getIntermissionSpawn() {
        if (intermissionArena != null && intermissionArena.getPos1() != null) {
            return intermissionArena.getPos1();
        }
        return getArena().getPos1() != null ? getArena().getPos1() : getArena().getCenter();
    }

    private Location getHiderSpawn() {
        if (getArena().getPos2() != null) {
            return getArena().getPos2();
        }
        return getArena().getPos1() != null ? getArena().getPos1() : getArena().getCenter();
    }

    public HideAndSeekMatch(Queue queue, Kit kit, Arena arena, boolean ranked, GameParticipant<MatchGamePlayer> participantA, GameParticipant<MatchGamePlayer> participantB) {
        super(queue, kit, arena, ranked, participantA, participantB);

        List<MatchGamePlayer> allPlayers = new ArrayList<>();
        allPlayers.addAll(participantA.getAllPlayers());
        allPlayers.addAll(participantB.getAllPlayers());
        this.seekerCount = allPlayers.size() > 2
                ? Math.max(1, (int) Math.ceil(allPlayers.size() * 0.2))
                : 1;

        // Everyone starts on one free-for-all team so intermission combat is allowed.
        this.participantA = new TeamGameParticipant<>(allPlayers.get(0));
        for (int i = 1; i < allPlayers.size(); i++) {
            this.participantA.addPlayer(allPlayers.get(i));
        }
        this.participantB = new TeamGameParticipant<>(allPlayers.get(0));
        this.participantB.removePlayer(allPlayers.get(0));
    }

    @Override
    public void setupPlayer(Player player) {
        MatchGamePlayer gamePlayer = getGamePlayer(player);
        if (gamePlayer == null) return;

        gamePlayer.setDead(false);
        PlayerUtil.reset(player, true, true);

        if (this.intermissionPhase) {
            giveIntermissionSword(player);
            Location spawn = getIntermissionSpawn();
            if (spawn != null) {
                player.teleportAsync(spawn);
            }
            player.sendTitle(CC.translate("&6&lINTERMISSION"),
                    CC.translate("&7Most kills choose the roles!"), 10, 40, 10);
            player.sendMessage(CC.translate("&6&lHide & Seek &8| &7Fight in the intermission area. "
                    + "The top &e" + this.seekerCount + " &7killer"
                    + (this.seekerCount == 1 ? "" : "s")
                    + " will choose identities."));
            return;
        }

        boolean isHider = getParticipantB().containsPlayer(player.getUniqueId());
        giveLoadout(player, getRoleKit(player));

        if (isHider) {
            Location hiderSpawn = getHiderSpawn();
            if (hiderSpawn != null) {
                player.teleportAsync(hiderSpawn);
            }
            player.setMaxHealth(hiderHealthHearts * 2.0);
            player.setHealth(player.getMaxHealth());
            player.addPotionEffect(new PotionEffect(PotionEffectType.SPEED, Integer.MAX_VALUE, 1, false, false));
            player.sendTitle(CC.translate("&a&lYOU ARE A HIDER"), CC.translate("&7Hide before the seekers arrive!"), 10, 60, 10);
            player.sendMessage(CC.translate("&a&lYou are a &6&lHIDER&a&l! &7Hide now — seekers will be released in &e3 minutes&7."));
        } else {
            ListenerUtil.teleportAndClearSpawn(player, getIntermissionSpawn());
            player.addPotionEffect(new PotionEffect(PotionEffectType.STRENGTH, Integer.MAX_VALUE, 9, false, false));
            player.addPotionEffect(new PotionEffect(PotionEffectType.SPEED, Integer.MAX_VALUE, 0, false, false));
            player.sendTitle(CC.translate("&c&lYOU ARE A SEEKER"), CC.translate("&7You'll be released in 3 minutes!"), 10, 60, 10);
            player.sendMessage(CC.translate("&c&lYou are a &6&lSEEKER&c&l! &7The hiders are hiding. You will be released in &e3 minutes&7."));
        }
    }

    @Override
    public void denyPlayerMovement(List<GameParticipant<MatchGamePlayer>> participants) {
        if (this.intermissionPhase || this.selectingRoles) return;
        GameParticipant<?> hiders = this.getParticipantB();
        Location hidersSpawn = this.getArena().getPos2();
        if (hiders == null || hidersSpawn == null) return;
        for (GamePlayer gamePlayer : hiders.getPlayers()) {
            Player pp = gamePlayer.getTeamPlayer();
            if (pp != null) this.teleportBackIfMoved(pp, hidersSpawn);
        }
    }

    @Override
    public void startMatch() {
        super.startMatch();
        showIntermissionBar();
    }

    @Override
    public void handleRoundStart() {
        super.handleRoundStart();
        startIntermissionPhase();
    }

    private void showIntermissionBar() {
        this.countdownBar = Bukkit.createBossBar(
                CC.translate("&6&lINTERMISSION &8| &7Fight for role select!"),
                BarColor.YELLOW, BarStyle.SOLID);
        addAllPlayersToBar();
    }

    private void startIntermissionPhase() {
        this.intermissionPhase = true;
        this.selectingRoles = false;
        this.phaseStartMillis = System.currentTimeMillis();
        if (this.countdownBar == null) {
            showIntermissionBar();
        }

        this.bossBarUpdateTask = plugin.getServer().getScheduler().runTaskTimer(plugin, new Runnable() {
            int remaining = intermissionSeconds;

            @Override
            public void run() {
                if (!intermissionPhase || countdownBar == null) {
                    if (bossBarUpdateTask != null) bossBarUpdateTask.cancel();
                    return;
                }
                if (remaining <= 0) {
                    bossBarUpdateTask.cancel();
                    return;
                }
                if (remaining <= 10) {
                    playPlingToAll();
                    broadcastToAll(CC.translate("&e&l" + remaining + " &7second"
                            + (remaining == 1 ? "" : "s") + " until role select!"));
                }
                countdownBar.setProgress(Math.max(0, Math.min(1, (double) remaining / intermissionSeconds)));
                countdownBar.setTitle(CC.translate("&6&lINTERMISSION &8| &7Role select in &e" + formatTime(remaining)));
                remaining--;
            }
        }, 0L, 20L);

        this.intermissionTask = plugin.getServer().getScheduler().runTaskLater(plugin,
                this::beginRoleSelection, this.intermissionSeconds * 20L);
    }

    private void beginRoleSelection() {
        if (!this.intermissionPhase || this.selectingRoles) return;
        this.intermissionPhase = false;
        this.selectingRoles = true;
        if (this.bossBarUpdateTask != null) this.bossBarUpdateTask.cancel();

        this.roleChoosers.clear();
        this.roleChoices.clear();
        this.phaseStartMillis = System.currentTimeMillis();
        this.roleChoosers.addAll(determineRoleChoosers());

        if (this.roleChoosers.isEmpty()) {
            assignRemainingRoles();
            return;
        }

        String chooserNames = this.roleChoosers.stream()
                .map(uuid -> {
                    MatchGamePlayer gamePlayer = findGamePlayer(uuid);
                    return gamePlayer == null ? "Unknown" : gamePlayer.getUsername();
                })
                .reduce((first, second) -> first + ", " + second)
                .orElse("Nobody");
        broadcastToAll(CC.translate("&6&lHide & Seek &8| &e" + chooserNames
                + " &7earned the right to choose an identity."));

        if (this.countdownBar != null) {
            this.countdownBar.setTitle(CC.translate("&6&lCHOOSE YOUR ROLE &8| &e" + formatTime(this.roleSelectSeconds)));
            this.countdownBar.setColor(BarColor.PURPLE);
            this.countdownBar.setProgress(1.0);
        }

        this.bossBarUpdateTask = plugin.getServer().getScheduler().runTaskTimer(plugin, new Runnable() {
            int remaining = roleSelectSeconds;

            @Override
            public void run() {
                if (!selectingRoles || countdownBar == null) {
                    if (bossBarUpdateTask != null) bossBarUpdateTask.cancel();
                    return;
                }
                countdownBar.setProgress(Math.max(0, Math.min(1, (double) remaining / roleSelectSeconds)));
                countdownBar.setTitle(CC.translate("&6&lCHOOSE YOUR ROLE &8| &e" + formatTime(remaining)));
                remaining--;
            }
        }, 0L, 20L);

        for (UUID chooserId : this.roleChoosers) {
            Player chooser = Bukkit.getPlayer(chooserId);
            if (chooser != null && chooser.isOnline()) {
                new HideAndSeekRoleSelectMenu(this).openMenu(chooser);
            }
        }

        this.roleSelectTask = plugin.getServer().getScheduler().runTaskLater(plugin, () -> {
            if (this.selectingRoles) {
                assignRemainingRoles();
            }
        }, this.roleSelectSeconds * 20L);
    }

    private List<UUID> determineRoleChoosers() {
        List<MatchGamePlayer> alive = getAllMatchPlayers().stream()
                .filter(player -> !player.isDisconnected())
                .sorted(Comparator
                        .comparingInt((MatchGamePlayer player) -> player.getData().getKills())
                        .reversed()
                        .thenComparing(MatchGamePlayer::getUsername))
                .toList();
        List<UUID> choosers = new ArrayList<>();
        for (int i = 0; i < Math.min(this.seekerCount, alive.size()); i++) {
            choosers.add(alive.get(i).getUuid());
        }
        return choosers;
    }

    public boolean isFreeForAllCombat() {
        return this.intermissionPhase || this.selectingRoles;
    }

    public boolean isIntermissionPhase() {
        return this.intermissionPhase;
    }

    public int getPhaseSecondsRemaining() {
        int elapsed = this.phaseStartMillis <= 0L
                ? 0
                : (int) ((System.currentTimeMillis() - this.phaseStartMillis) / 1000L);
        if (this.intermissionPhase) {
            return Math.max(0, this.intermissionSeconds - elapsed);
        }
        if (this.selectingRoles) {
            return Math.max(0, this.roleSelectSeconds - elapsed);
        }
        if (this.gameEndTask == null) {
            return Math.max(0, this.hidingTimeSeconds - elapsed);
        }
        return Math.max(0, this.gameTimeSeconds - elapsed);
    }

    public boolean hasChosenRole(UUID playerId) {
        return this.roleChoices.containsKey(playerId);
    }

    public int getRemainingSeekerSlots() {
        return Math.max(0, this.seekerCount - countChosen(true));
    }

    public int getRemainingHiderSlots() {
        int hiderSlots = Math.max(0, getAllMatchPlayers().size() - this.seekerCount);
        return Math.max(0, hiderSlots - countChosen(false));
    }

    public boolean selectRole(Player player, boolean seeker) {
        if (!this.selectingRoles || player == null || !this.roleChoosers.contains(player.getUniqueId())) {
            return false;
        }
        if (this.roleChoices.containsKey(player.getUniqueId())) {
            return false;
        }
        if (seeker && getRemainingSeekerSlots() <= 0) {
            player.sendMessage(CC.translate("&cThere are no seeker slots left."));
            return false;
        }
        if (!seeker && getRemainingHiderSlots() <= 0) {
            player.sendMessage(CC.translate("&cThere are no hider slots left."));
            return false;
        }

        this.roleChoices.put(player.getUniqueId(), seeker);
        player.sendMessage(CC.translate(seeker
                ? "&cYou chose to be a &6&lSEEKER&c."
                : "&aYou chose to be a &6&lHIDER&a."));
        broadcastToAll(CC.translate("&6&lHide & Seek &8| &f" + player.getName()
                + " &7chose " + (seeker ? "&cSeeker" : "&aHider") + "&7."));

        if (this.roleChoices.size() >= this.roleChoosers.size()
                || (getRemainingSeekerSlots() <= 0 && getRemainingHiderSlots() <= 0)) {
            assignRemainingRoles();
        }
        return true;
    }

    private int countChosen(boolean seeker) {
        int count = 0;
        for (Boolean choice : this.roleChoices.values()) {
            if (choice == seeker) count++;
        }
        return count;
    }

    private void assignRemainingRoles() {
        if (!this.selectingRoles && !this.intermissionPhase) return;
        this.selectingRoles = false;
        this.intermissionPhase = false;
        if (this.roleSelectTask != null) this.roleSelectTask.cancel();
        if (this.bossBarUpdateTask != null) this.bossBarUpdateTask.cancel();

        for (UUID chooserId : this.roleChoosers) {
            Player chooser = Bukkit.getPlayer(chooserId);
            if (chooser != null) chooser.closeInventory();
            this.roleChoices.putIfAbsent(chooserId, false);
        }

        List<MatchGamePlayer> unassigned = new ArrayList<>();
        for (MatchGamePlayer player : getAllMatchPlayers()) {
            if (!this.roleChoices.containsKey(player.getUuid()) && !player.isDisconnected()) {
                unassigned.add(player);
            }
        }

        int seekersNeeded = getRemainingSeekerSlots();
        java.util.Collections.shuffle(unassigned);
        for (MatchGamePlayer player : unassigned) {
            boolean seeker = seekersNeeded > 0;
            this.roleChoices.put(player.getUuid(), seeker);
            if (seeker) seekersNeeded--;
        }

        applyChosenRoles();
        beginHidingPhase();
    }

    private void applyChosenRoles() {
        List<MatchGamePlayer> seekers = new ArrayList<>();
        List<MatchGamePlayer> hiders = new ArrayList<>();
        for (MatchGamePlayer player : getAllMatchPlayers()) {
            if (Boolean.TRUE.equals(this.roleChoices.get(player.getUuid()))) {
                seekers.add(player);
            } else {
                hiders.add(player);
            }
        }
        if (seekers.isEmpty() && !hiders.isEmpty()) {
            seekers.add(hiders.remove(0));
        }
        if (hiders.isEmpty() && seekers.size() > 1) {
            hiders.add(seekers.remove(seekers.size() - 1));
        }
        if (seekers.isEmpty() || hiders.isEmpty()) return;

        rebuildTeam(this.participantA, seekers);
        rebuildTeam(this.participantB, hiders);
        updateParticipantNametags();
    }

    private void rebuildTeam(GameParticipant<MatchGamePlayer> team, List<MatchGamePlayer> members) {
        for (MatchGamePlayer player : new ArrayList<>(team.getAllPlayers())) {
            team.removePlayer(player);
        }
        if (team instanceof TeamGameParticipant<MatchGamePlayer> teamParticipant) {
            for (MatchGamePlayer member : members) {
                teamParticipant.addPlayer(member);
            }
        }
        team.setLeader(members.get(0));
    }

    private void beginHidingPhase() {
        getAllMatchPlayers().forEach(gamePlayer -> {
            Player player = gamePlayer.getTeamPlayer();
            if (player != null) setupPlayer(player);
        });
        startHidingCountdown();
    }

    private void startHidingCountdown() {
        int totalSeconds = this.hidingTimeSeconds;
        this.phaseStartMillis = System.currentTimeMillis();
        if (this.countdownBar != null) {
            this.countdownBar.removeAll();
        }

        // --- BossBar for hiding phase countdown ---
        countdownBar = Bukkit.createBossBar(
                CC.translate("&6&lHIDE & SEEK &8| &7Seekers released in &e" + formatTime(totalSeconds)),
                BarColor.YELLOW, BarStyle.SOLID);
        addAllPlayersToBar();

        // Use a tick counter instead of getElapsedTime() for precise timing
        bossBarUpdateTask = plugin.getServer().getScheduler().runTaskTimer(plugin, new Runnable() {
            int tick = 0;
            @Override public void run() {
                tick++;
                int remaining = totalSeconds - tick / 20;
                if (remaining <= 0) {
                    if (countdownBar != null) {
                        countdownBar.setTitle(CC.translate("&c&lSEEKERS RELEASED!"));
                        countdownBar.setColor(BarColor.RED);
                    }
                    bossBarUpdateTask.cancel();
                    return;
                }

                // Last 10s countdown
                if (remaining <= 10 && tick % 20 == 0) {
                    playPlingToAll();
                    broadcastToAll(CC.translate("&e&l" + remaining + " &7second" + (remaining == 1 ? "" : "s") + " until seekers are released!"));
                }

                if (countdownBar != null) {
                    countdownBar.setProgress(Math.max(0, Math.min(1, (double) remaining / totalSeconds)));
                    countdownBar.setTitle(CC.translate("&6&lHIDE & SEEK &8| &7Seekers released in &e" + formatTime(remaining)));
                }
            }
        }, 0L, 1L);

        long totalDelayTicks = totalSeconds * 20L;
        this.seekerReleaseTask = plugin.getServer().getScheduler().runTaskLater(plugin, () -> {
            // Cancel hiding phase timer
            if (bossBarUpdateTask != null) bossBarUpdateTask.cancel();

            LocaleService localeService = plugin.getService(LocaleService.class);
            if (localeService.getBoolean(VisualsLocaleImpl.TITLE_MATCH_SEEKERS_RELEASED_ENABLED_BOOLEAN)) {
                this.sendTitle(
                        localeService.getString(VisualsLocaleImpl.TITLE_MATCH_SEEKERS_RELEASED_HEADER),
                        localeService.getString(VisualsLocaleImpl.TITLE_MATCH_SEEKERS_RELEASED_FOOTER),
                        localeService.getInt(VisualsLocaleImpl.TITLE_MATCH_SEEKERS_RELEASED_FADE_IN),
                        localeService.getInt(VisualsLocaleImpl.TITLE_MATCH_SEEKERS_RELEASED_STAY),
                        localeService.getInt(VisualsLocaleImpl.TITLE_MATCH_SEEKERS_RELEASED_FADEOUT),
                        true);
            }
            playSound(Sound.ENTITY_ENDER_DRAGON_GROWL);

            if (countdownBar != null) {
                countdownBar.setTitle(CC.translate("&c&lSEEKING PHASE &8| &7Time left: &e" + formatTime(gameTimeSeconds)));
                countdownBar.setColor(BarColor.RED);
                countdownBar.setProgress(1.0);
            }
            this.phaseStartMillis = System.currentTimeMillis();

            getParticipantA().getPlayers().forEach(seeker -> {
                Player p = seeker.getTeamPlayer();
                if (p != null) p.teleportAsync(getArena().getPos2());
            });

            broadcastToAll(CC.translate("&c&lSEEKERS HAVE BEEN RELEASED! &7They have &e" + formatTime(gameTimeSeconds) + " &7to find all hiders."));

            // Seeking phase countdown
            BukkitTask seekingCountdown = plugin.getServer().getScheduler().runTaskTimer(plugin, new Runnable() {
                int sec = gameTimeSeconds;
                @Override public void run() {
                    sec--;
                    if (sec <= 0 || countdownBar == null) return;
                    countdownBar.setProgress(Math.max(0, (double) sec / gameTimeSeconds));
                    countdownBar.setTitle(CC.translate("&c&lSEEKING PHASE &8| &7Time left: &e" + formatTime(sec)));
                    if (sec <= 10 && sec >= 1) {
                        playPlingToAll();
                        broadcastToAll(CC.translate("&e&l" + sec + " &7second" + (sec == 1 ? "" : "s") + " remaining!"));
                    }
                }
            }, 20L, 20L);

            this.gameEndTask = plugin.getServer().getScheduler().runTaskLater(plugin, () -> {
                seekingCountdown.cancel();
                this.timeExpired = true;
                broadcastToAll(CC.translate("&c&lTIME IS UP! &7Hiders win!"));
                new ArrayList<>(getParticipantA().getPlayers()).forEach(seeker -> {
                    Player p = seeker.getTeamPlayer();
                    if (p != null && !seeker.isDead()) handleDeath(p, EntityDamageEvent.DamageCause.CUSTOM);
                });
            }, gameTimeSeconds * 20L);

        }, totalDelayTicks);
    }

    @Override
    public void handleDeath(Player player, EntityDamageEvent.DamageCause cause) {
        if (this.deferToPrimaryThread(() -> this.handleDeath(player, cause))) return;

        if (this.intermissionPhase || this.selectingRoles) {
            MatchGamePlayer gamePlayer = getGamePlayer(player);
            if (gamePlayer != null) MatchListener.blockDeadPlayerPickup(player);
            Player killer = this.plugin.getService(CombatService.class).getLastAttacker(player);
            this.announceDeath(player, killer, cause);
            this.plugin.getServer().getScheduler().runTaskLater(this.plugin, () -> handleRespawn(player), 1L);
            return;
        }

        GameParticipant<MatchGamePlayer> participant = getParticipant(player);
        if (participant != null) MatchListener.blockDeadPlayerPickup(player);
        if (participant == getParticipantA()) {
            if (this.timeExpired) {
                super.handleDeath(player, cause);
            } else {
                if (gameEndTask != null) {
                    this.sendMessage(this.plugin.getService(LocaleService.class).getString(GameMessagesLocaleImpl.MATCH_SEEKER_RESPAWNED)
                            .replace("{player}", player.getName())
                            .replace("{name-color}", String.valueOf(this.plugin.getService(ProfileService.class).getProfile(player.getUniqueId()).getNameColor())));
                }
                this.plugin.getServer().getScheduler().runTaskLater(this.plugin, () -> handleRespawn(player), 1L);
            }
        } else if (participant == getParticipantB()) {
            super.handleDeath(player, cause);
        }
    }

    @Override
    public void handleRespawn(Player player) {
        // Match deaths are synthetic, so the victim is normally alive here and this is
        // a no-op. The guard only rescues a genuine death from an unhandled path.
        if (player.isDead()) player.spigot().respawn();
        PlayerUtil.reset(player, true, false);
        if (this.intermissionPhase || this.selectingRoles) {
            ListenerUtil.teleportAndClearSpawn(player, getIntermissionSpawn());
            giveIntermissionSword(player);
            return;
        }
        if (gameEndTask == null) ListenerUtil.teleportAndClearSpawn(player, getIntermissionSpawn());
        else ListenerUtil.teleportAndClearSpawn(player, getArena().getPos2());
        giveLoadout(player, getRoleKit(player));
        if (getParticipantA().containsPlayer(player.getUniqueId())) {
            player.addPotionEffect(new PotionEffect(PotionEffectType.STRENGTH, Integer.MAX_VALUE, 9, false, false));
            player.addPotionEffect(new PotionEffect(PotionEffectType.SPEED, Integer.MAX_VALUE, 0, false, false));
        }
    }

    @Override
    public void handleDisconnect(Player player) {
        MatchGamePlayer gamePlayer = getFromAllGamePlayers(player);
        if (gamePlayer == null || gamePlayer.isDead()) return;
        // Mark dead before super call so DefaultMatch.handleDisconnect skips respawn logic
        gamePlayer.setDead(true);
        gamePlayer.setEliminated(true);
        gamePlayer.setDisconnected(true);
        String team = this.intermissionPhase || this.selectingRoles
                ? "Player"
                : (getParticipant(player) == getParticipantA() ? "Seeker" : "Hider");
        sendMessage(CC.translate("&c&lDISCONNECT! &f" + team + " &c" + player.getName() + " &fhas disconnected."));

        // Let base class clean up (finalizePlayer, etc.)
        super.handleDisconnect(player);
        if (!this.intermissionPhase && !this.selectingRoles) {
            checkForConclusion(player, null);
        }
    }

    @Override
    public void endMatch() {
        if (this.seekerReleaseTask != null) this.seekerReleaseTask.cancel();
        if (this.gameEndTask != null) this.gameEndTask.cancel();
        if (this.bossBarUpdateTask != null) this.bossBarUpdateTask.cancel();
        if (this.intermissionTask != null) this.intermissionTask.cancel();
        if (this.roleSelectTask != null) this.roleSelectTask.cancel();
        if (this.countdownBar != null) { countdownBar.removeAll(); countdownBar = null; }
        super.endMatch();
    }

    @Override
    public boolean canEndRound() {
        if (this.intermissionPhase || this.selectingRoles) return false;
        return super.canEndRound() || this.timeExpired;
    }
    @Override public List<GameParticipant<MatchGamePlayer>> getParticipants() { return Arrays.asList(this.participantA, this.participantB); }
    @Override public GameParticipant<MatchGamePlayer> getParticipantA() { return participantA; }
    @Override public GameParticipant<MatchGamePlayer> getParticipantB() { return participantB; }

    private void giveIntermissionSword(Player player) {
        player.getInventory().clear();
        player.getInventory().setArmorContents(new ItemStack[4]);
        player.getInventory().setItemInOffHand(null);
        player.getInventory().setItem(0, new ItemStack(Material.WOODEN_SWORD));
        player.updateInventory();
    }

    private List<MatchGamePlayer> getAllMatchPlayers() {
        List<MatchGamePlayer> players = new ArrayList<>();
        players.addAll(this.participantA.getAllPlayers());
        for (MatchGamePlayer player : this.participantB.getAllPlayers()) {
            if (players.stream().noneMatch(existing -> existing.getUuid().equals(player.getUuid()))) {
                players.add(player);
            }
        }
        return players;
    }

    private MatchGamePlayer findGamePlayer(UUID uuid) {
        return getAllMatchPlayers().stream()
                .filter(player -> player.getUuid().equals(uuid))
                .findFirst()
                .orElse(null);
    }

    // --- Helpers ---
    private void addAllPlayersToBar() {
        getParticipants().forEach(p -> p.getPlayers().forEach(mp -> {
            Player pl = mp.getTeamPlayer();
            if (pl != null) countdownBar.addPlayer(pl);
        }));
    }

    private void playPlingToAll() {
        getParticipants().forEach(p -> p.getPlayers().forEach(mp -> {
            Player pl = mp.getTeamPlayer();
            if (pl != null) pl.playSound(pl.getLocation(), Sound.BLOCK_NOTE_BLOCK_PLING, 1f, 1f);
        }));
    }

    private void broadcastToAll(String msg) {
        getParticipants().forEach(p -> p.getPlayers().forEach(mp -> {
            Player pl = mp.getTeamPlayer();
            if (pl != null) pl.sendMessage(msg);
        }));
    }

    private static String formatTime(int totalSeconds) {
        int min = totalSeconds / 60;
        int sec = totalSeconds % 60;
        return min + ":" + (sec < 10 ? "0" : "") + sec;
    }

    private Kit getRoleKit(Player player) {
        boolean isSeeker = getParticipantA().containsPlayer(player.getUniqueId());
        String roleKitName = isSeeker ? getKit().getHideAndSeekSeekerKit() : getKit().getHideAndSeekHiderKit();
        if (roleKitName == null || roleKitName.isEmpty()) return getKit();

        Kit roleKit = plugin.getService(KitService.class).getKit(roleKitName);
        return roleKit != null ? roleKit : getKit();
    }
}
