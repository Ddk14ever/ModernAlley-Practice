package dev.revere.alley.feature.knockback;

import dev.revere.alley.AlleyPlugin;
import dev.revere.alley.bootstrap.AlleyContext;
import dev.revere.alley.bootstrap.annotation.Service;
import dev.revere.alley.feature.kit.Kit;
import dev.revere.alley.feature.kit.KitService;
import dev.revere.alley.feature.kit.setting.types.combat.KitSettingOldHitDelay;
import dev.revere.alley.feature.kit.setting.types.combat.KitSettingOldSwordBlocking;
import dev.revere.alley.feature.knockback.data.PlayerKnockbackData;
import dev.revere.alley.feature.knockback.listener.KnockbackListener;
import dev.revere.alley.feature.knockback.listener.PotionMotionListener;
import dev.revere.alley.feature.knockback.nms.LegacyMotionBridge;
import dev.revere.alley.feature.knockback.packet.MisplaceHandler;
import dev.revere.alley.feature.knockback.hitbox.HitDetection;
import dev.revere.alley.feature.knockback.sprint.LegacySprintTracker;
import org.bukkit.Bukkit;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.util.Vector;

import java.io.File;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
/**
 * @author Ddk1 ClaudeCode
 * @project Alley
 * @since 05/07/2026
 *
 * Built-in knockback manager. Resolves kit-authoritative DEFAULT/LEGACY branches.
 */
@Service(provides = KnockbackManager.class, priority = 150)
public class KnockbackManager implements dev.revere.alley.bootstrap.lifecycle.Service {
    private static final String HIT_DELAY_FORMAT_KEY = "hit_delay_format";
    private static final String HIT_DELAY_FORMAT_NMS_WINDOW = "nms_window";

    private final Map<String, KnockbackProfile> profiles = new LinkedHashMap<>();
    private final Map<UUID, PlayerKnockbackData> playerData = new ConcurrentHashMap<>();
    private final Set<String> branchMismatchWarnings = ConcurrentHashMap.newKeySet();
    private final LegacyMotionBridge legacyMotionBridge = new LegacyMotionBridge();
    private HitDetection hitDetection;
    private MisplaceHandler misplaceHandler;
    private KnockbackListener knockbackListener;
    private PotionMotionListener potionMotionListener;
    private LegacySprintTracker legacySprintTracker;
    private File profilesDir;
    private long currentTick;

    @Override
    public void initialize(AlleyContext context) {
        profilesDir = new File(AlleyPlugin.getInstance().getDataFolder(), "knockback");
        if (!profilesDir.exists()) {
            profilesDir.mkdirs();
        }
        AlleyPlugin.getInstance().saveResource("knockback/default.yml", false);
        AlleyPlugin.getInstance().saveResource("knockback/1_8.yml", false);
        reloadProfiles();
        synchronizeHitDelayConfiguration();

        this.legacySprintTracker = new LegacySprintTracker(this);
        this.legacySprintTracker.enable();

        // Register listeners
        this.knockbackListener = new KnockbackListener(this);
        AlleyPlugin.getInstance().getServer().getPluginManager()
                .registerEvents(this.knockbackListener, AlleyPlugin.getInstance());
        this.hitDetection = new HitDetection(this);
        AlleyPlugin.getInstance().getServer().getPluginManager()
                .registerEvents(this.hitDetection, AlleyPlugin.getInstance());
        this.potionMotionListener = new PotionMotionListener(this);
        AlleyPlugin.getInstance().getServer().getPluginManager()
                .registerEvents(this.potionMotionListener, AlleyPlugin.getInstance());

        // Misplace handler (ProtocolLib)
        this.misplaceHandler = new MisplaceHandler(this);
        this.misplaceHandler.enable();

        // Tick loop for packet delay queue
        AlleyPlugin.getInstance().getServer().getScheduler().runTaskTimer(AlleyPlugin.getInstance(), () -> {
            currentTick++;
            if (hitDetection != null) hitDetection.tick();
            if (legacySprintTracker != null) legacySprintTracker.tick();
            if (misplaceHandler != null) misplaceHandler.tick();
        }, 1L, 1L);
    }

    @Override
    public void shutdown(AlleyContext context) {
        if (this.hitDetection != null) {
            this.hitDetection.clear();
        }
        if (this.misplaceHandler != null) {
            this.misplaceHandler.disable();
        }
        if (this.legacySprintTracker != null) {
            this.legacySprintTracker.disable();
        }
    }

    // --- Profiles ---
    public void reloadProfiles() {
        profiles.clear();
        branchMismatchWarnings.clear();
        File[] files = profilesDir.listFiles((d, n) -> n.endsWith(".yml"));
        if (files == null) files = new File[0];
        for (File f : files) {
            String name = f.getName().replace(".yml", "");
            YamlConfiguration config = YamlConfiguration.loadConfiguration(f);
            boolean configChanged = false;
            boolean hitDelayMigrated = false;
            KnockbackBranch inferredBranch = name.equalsIgnoreCase("1_8")
                    ? KnockbackBranch.LEGACY : KnockbackBranch.DEFAULT;
            KnockbackBranch configuredBranch = KnockbackBranch.fromName(config.getString("branch"));
            if (configuredBranch == null) {
                if (config.isSet("branch")) {
                    AlleyPlugin.getInstance().getLogger().warning(
                            "[Knockback] Unknown branch in " + f.getName()
                                    + "; using " + inferredBranch + ".");
                }
                configuredBranch = inferredBranch;
                config.set("branch", configuredBranch.name());
                configChanged = true;
            }
            if (configuredBranch == KnockbackBranch.LEGACY && config.isSet("disable_downward_kb")) {
                config.set("disable_downward_kb", null);
                configChanged = true;
            }
            if (configuredBranch == KnockbackBranch.LEGACY) {
                configChanged |= setIfMissing(config, "vertical.limit", 0.4D);
                configChanged |= setIfMissing(config, "attacker.horizontal_slowdown", 0.6D);
                configChanged |= setIfMissing(config, "friction.horizontal", 2.0D);
                configChanged |= setIfMissing(config, "distance_reduction.enabled", true);
                configChanged |= setIfMissing(config, "distance_reduction.start", 3.0D);
                configChanged |= setIfMissing(config, "distance_reduction.factor", 0.025D);
                configChanged |= setIfMissing(config, "distance_reduction.maximum", 1.2D);
                configChanged |= setIfMissing(config, "distance_reduction.minimum_horizontal", 0.12D);
                configChanged |= setIfMissing(config, "arrow.horizontal", 0.4D);
                configChanged |= setIfMissing(config, "arrow.vertical", 0.4D);
                configChanged |= setIfMissing(config, "arrow.vertical_limit", 0.4D);
                configChanged |= setIfMissing(config, "arrow.punch_horizontal", 0.6D);
                configChanged |= setIfMissing(config, "arrow.punch_vertical", 0.1D);
                // Surface the anti-whiff switch in existing server configs too.
                configChanged |= setIfMissing(config, "melee.anti_whiff", true);
            }
            if (!config.isSet(HIT_DELAY_FORMAT_KEY)) {
                int legacyDelay = Math.max(0, config.getInt("hit_delay", 10));
                int nmsWindow = (int) Math.min((long) legacyDelay * 2L, Integer.MAX_VALUE);
                config.set("hit_delay", nmsWindow);
                config.set(HIT_DELAY_FORMAT_KEY, HIT_DELAY_FORMAT_NMS_WINDOW);
                configChanged = true;
                hitDelayMigrated = true;
            } else if (!HIT_DELAY_FORMAT_NMS_WINDOW.equalsIgnoreCase(
                    config.getString(HIT_DELAY_FORMAT_KEY, ""))) {
                AlleyPlugin.getInstance().getLogger().warning(
                        "[HitDelay] Unknown hit-delay format in " + f.getName()
                                + "; values were left unchanged.");
            }
            if (!config.isSet("potion.speed_compensation")) {
                boolean legacyBuiltIn = name.equalsIgnoreCase("1_7") || name.equalsIgnoreCase("1_8");
                config.set("potion.speed_compensation", legacyBuiltIn ? 0.0 : 1.0);
                configChanged = true;
            }
            if (!config.isSet("potion.horizontal_compensation")) {
                config.set("potion.horizontal_compensation", 0.0);
                configChanged = true;
            }
            // 1.8 melee uses the standing 0.6 x 1.8 box expanded by 0.1
            // (Entity#getCollisionBorderSize), so the profile's own hitbox keys
            // must stay at their vanilla values. The old migration rewrote
            // 0.7/1.8 to 0.8/2.0, which widened the box beyond 1.8 for every
            // consumer that reads these keys.
            if (configChanged) {
                try {
                    config.save(f);
                    if (hitDelayMigrated) {
                        AlleyPlugin.getInstance().getLogger().info(
                                "[HitDelay] Migrated " + f.getName()
                                        + " to the NMS hurt-window format.");
                    }
                } catch (java.io.IOException exception) {
                    AlleyPlugin.getInstance().getLogger().warning(
                            "Unable to save migrated knockback settings to " + f.getName()
                                    + ": " + exception.getMessage());
                }
            }
            profiles.put(name.toLowerCase(), new KnockbackProfile(name, config));
        }
        if (profiles.isEmpty()) {
            AlleyPlugin.getInstance().getLogger().warning("No knockback profiles found in knockback/ folder!");
        }

        // A live reload must update the NMS window immediately; otherwise the
        // next damage event is evaluated against the previous profile's half.
        for (Player player : Bukkit.getOnlinePlayers()) {
            PlayerKnockbackData data = playerData.get(player.getUniqueId());
            if (data != null && data.getProfileName() != null) {
                KnockbackProfile profile = resolveProfile(
                        data.getProfileName(), data.getBranch(), "live player " + player.getName());
                if (profile != null) data.setProfileName(profile.getName());
                applyHitDelayWindow(player);
                applyEntityInteractionRange(player, profile);
            }
        }
    }

    private boolean setIfMissing(YamlConfiguration config, String path, Object value) {
        if (config.isSet(path)) return false;
        config.set(path, value);
        return true;
    }

    public KnockbackProfile getProfile(String name) {
        if (name == null) return profiles.get("default");
        String key = name.toLowerCase();
        // Kits may store the profile with or without the ".yml" extension; strip it so
        // both "1_8" and "1_8.yml" resolve to the same loaded profile.
        if (key.endsWith(".yml")) {
            key = key.substring(0, key.length() - 4);
        }
        return profiles.get(key);
    }

    public KnockbackProfile getDefaultProfile() {
        return profiles.get("default");
    }

    public KnockbackProfile getFallbackProfile(KnockbackBranch branch) {
        String preferredName = branch == KnockbackBranch.LEGACY ? "1_8" : "default";
        KnockbackProfile preferred = profiles.get(preferredName);
        if (preferred != null && preferred.getBranch() == branch) return preferred;
        return profiles.values().stream()
                .filter(profile -> profile.getBranch() == branch)
                .findFirst()
                .orElse(null);
    }

    public KnockbackProfile resolveProfile(String profileName, KnockbackBranch requiredBranch,
                                           String ownerDescription) {
        KnockbackBranch branch = requiredBranch == null ? KnockbackBranch.DEFAULT : requiredBranch;
        KnockbackProfile requested = getProfile(profileName);
        if (requested != null && requested.getBranch() == branch) return requested;

        KnockbackProfile fallback = getFallbackProfile(branch);
        String requestedName = profileName == null || profileName.isBlank() ? "default" : profileName;
        String warningKey = ownerDescription + '|' + requestedName + '|' + branch;
        if (branchMismatchWarnings.add(warningKey)) {
            String reason = requested == null
                    ? "missing profile '" + requestedName + "'"
                    : "profile '" + requested.getName() + "' (branch " + requested.getBranch() + ")";
            AlleyPlugin.getInstance().getLogger().warning(
                    "[Knockback] " + ownerDescription + " requires " + branch + " but references "
                            + reason + "; using "
                            + (fallback == null ? "no profile" : "'" + fallback.getName() + "'") + ".");
        }
        return fallback;
    }

    public KnockbackBranch getRequiredBranch(Kit kit) {
        return kit != null && kit.isSettingEnabled(KitSettingOldSwordBlocking.class)
                ? KnockbackBranch.LEGACY : KnockbackBranch.DEFAULT;
    }

    public KnockbackProfile resolveProfile(Kit kit) {
        return resolveProfile(kit == null ? null : kit.getKnockbackProfile(),
                getRequiredBranch(kit), kit == null ? "unknown kit" : "kit '" + kit.getName() + "'");
    }

    public Collection<KnockbackProfile> getProfiles() {
        return profiles.values();
    }

    public KnockbackProfile getAppliedProfile(Player player) {
        PlayerKnockbackData data = getPlayerData(player);
        if (data.getProfileName() == null) return null;
        KnockbackProfile profile = getProfile(data.getProfileName());
        if (profile != null && profile.getBranch() == data.getBranch()) return profile;
        return getFallbackProfile(data.getBranch());
    }

    public boolean isLegacyKnockback(Player player) {
        return getPlayerData(player).getBranch() == KnockbackBranch.LEGACY;
    }

    private void synchronizeHitDelayConfiguration() {
        KitService kitService = AlleyPlugin.getInstance().getService(KitService.class);
        if (kitService.getKits().isEmpty()) return;

        int synchronizedKits = 0;
        for (Kit kit : kitService.getKits()) {
            if (synchronizeKitHitDelay(kit)) {
                kitService.saveKit(kit);
                synchronizedKits++;
            }
        }

        if (synchronizedKits > 0) {
            AlleyPlugin.getInstance().getLogger().info(
                    "[HitDelay] Synchronized " + synchronizedKits
                            + " enabled kit setting(s) to their knockback profiles.");
        }
    }

    public boolean synchronizeKitHitDelay(Kit kit) {
        KitSettingOldHitDelay setting = kit.getSetting(KitSettingOldHitDelay.class);
        if (setting == null || !setting.isEnabled()) return false;

        String profileName = kit.getKnockbackProfile();
        if (profileName == null || profileName.isBlank()) profileName = "default";
        KnockbackProfile profile = resolveProfile(kit);
        if (profile == null) {
            AlleyPlugin.getInstance().getLogger().warning(
                    "[HitDelay] Enabled kit '" + kit.getName()
                            + "' references missing knockback profile '" + profileName
                            + "'; its oldHitDelay could not be synchronized.");
            return false;
        }

        int profileDelay = Math.max(0, profile.getHitDelay());
        int kitDelay = Math.max(0, setting.getValue());
        if (kitDelay == profileDelay) return false;

        AlleyPlugin.getInstance().getLogger().warning(
                "[HitDelay] Kit '" + kit.getName() + "' oldHitDelay=" + kitDelay
                        + " conflicts with profile '" + profile.getName() + "' hit_delay="
                        + profileDelay + "; the kit value was overwritten with the profile value.");
        setting.setValue(profileDelay);
        return true;
    }

    // --- Player data ---
    public PlayerKnockbackData getPlayerData(UUID uuid) {
        return playerData.computeIfAbsent(uuid, k -> new PlayerKnockbackData());
    }

    public PlayerKnockbackData getPlayerData(Player p) {
        return getPlayerData(p.getUniqueId());
    }

    /**
     * Synchronizes the movement state used to select ground/air KB values.
     * Client-backed players normally reach this through PlayerMoveEvent; server-side
     * players call it from their tick adapter because they have no movement packets.
     */
    public void updateMovementState(Player player) {
        PlayerKnockbackData data = getPlayerData(player);
        boolean onGround = player.isOnGround();
        data.setOnGround(onGround);
        if (onGround) data.setLastGroundY(player.getLocation().getY());
    }

    public void removePlayer(UUID uuid) {
        PlayerKnockbackData data = playerData.remove(uuid);
        if (data != null) {
            // Cancel any one-tick legacy velocity delivery that still references
            // this player's old data object.
            data.setVelocity(null);
            data.clearLegacyState();
        }
        if (hitDetection != null) hitDetection.clearPlayer(uuid);
        if (misplaceHandler != null) misplaceHandler.clearPlayer(uuid);
        if (legacySprintTracker != null) legacySprintTracker.clear(uuid);
    }

    public void clearKnockback(Player player) {
        PlayerKnockbackData data = getPlayerData(player);
        data.setProfileName(null);
        data.setBranch(KnockbackBranch.DEFAULT);
        data.setConfiguredHitDelayWindow(-1);
        applyEntityInteractionRange(player, null);
        resetHitDelayState(player);
        if (misplaceHandler != null) misplaceHandler.clearPlayer(player.getUniqueId());
    }

    /** Clears the live/category state while retaining the player's assigned profile. */
    public void resetHitDelayState(Player player) {
        PlayerKnockbackData data = getPlayerData(player);
        data.clearLegacyState();
        data.setVelocity(null);
        if (legacySprintTracker != null) {
            legacySprintTracker.reset(player,
                    data.getBranch() == KnockbackBranch.LEGACY && data.getProfileName() != null);
        }
        player.setNoDamageTicks(0);
        if (hitDetection != null) hitDetection.clearPlayer(player.getUniqueId());

        if (data.getProfileName() == null) {
            // Do not let a match's hurt window leak into the lobby or next kit.
            data.setConfiguredHitDelayWindow(-1);
            player.setMaximumNoDamageTicks(KitSettingOldHitDelay.DEFAULT_DELAY);
        } else {
            applyHitDelayWindow(player);
        }
    }

    public MisplaceHandler getMisplaceHandler() { return misplaceHandler; }
    public PotionMotionListener getPotionMotionListener() { return potionMotionListener; }
    public long getCurrentTick() { return currentTick; }

    public boolean hasLegacySprintKnockback(Player player) {
        return isLegacyKnockback(player) && legacySprintTracker != null
                && legacySprintTracker.hasKnockbackEligibility(player);
    }

    public void consumeLegacySprintKnockback(Player player) {
        if (legacySprintTracker != null) {
            legacySprintTracker.consumeKnockbackEligibility(player);
        }
    }

    public LegacySprintTracker.WTapResult recordLegacyWTapHit(Player player) {
        return legacySprintTracker == null
                ? new LegacySprintTracker.WTapResult(false, false)
                : legacySprintTracker.recordAcceptedMeleeHit(player);
    }

    public boolean consumeLegacyWTapExtra(Player player) {
        return legacySprintTracker != null
                && legacySprintTracker.consumeWTapExtraEligibility(player);
    }

    public void armLegacyWTapExtra(Player player) {
        if (legacySprintTracker != null && isLegacyKnockback(player)) {
            legacySprintTracker.armWTapExtraEligibility(player);
        }
    }

    /** Records a confirmed Legacy melee hit before the MONITOR knockback phase. */
    public LegacySprintTracker.WTapResult recordLegacyMeleeHit(Player player) {
        return recordLegacyWTapHit(player);
    }

    public void updateSyntheticLegacySprint(Player player, boolean sprinting) {
        if (legacySprintTracker != null) {
            legacySprintTracker.updateSyntheticSprint(player, sprinting);
        }
    }

    public void forceSyntheticLegacySprintStart(Player player) {
        if (legacySprintTracker != null) {
            legacySprintTracker.forceSyntheticSprintStart(player);
        }
    }

    /**
     * Drops the Bot's synthetic sprint eligibility without opening a W-tap
     * window, for involuntary stops such as its own attack, knockback or
     * backing up. Only a deliberate STOP -> START may arm the W-tap extra.
     */
    public void dropSyntheticLegacySprint(Player player) {
        if (legacySprintTracker != null) {
            legacySprintTracker.dropSyntheticSprint(player);
        }
    }

    /**
     * Re-sprints after a forced stop without arming the W-tap extra. The sprint
     * knockback bonus is re-earned (like a 1.8 client re-sending
     * START_SPRINTING) but only a deliberate STOP -> START may add a W-tap.
     */
    public void restartSyntheticLegacySprint(Player player) {
        if (legacySprintTracker != null) {
            legacySprintTracker.restartSyntheticSprint(player);
        }
    }

    /**
     * Mirrors the 1.8 attacker's horizontal slowdown without using Bukkit
     * setVelocity for a real client. Bukkit marks that call for a velocity
     * packet, which would also shrink an in-flight knockback packet.
     */
    public void applyLegacyAttackerHorizontalMotion(Player player, Vector horizontalSource,
                                                     double vertical, double multiplier) {
        Vector adjusted = new Vector(
                horizontalSource.getX() * multiplier,
                vertical,
                horizontalSource.getZ() * multiplier);
        if (getPlayerData(player).isServerControlled()) {
            player.setVelocity(adjusted);
            return;
        }
        this.legacyMotionBridge.setDeltaMovement(player, adjusted);
    }

    /**
     * Consumes the same pending profile velocity used by PlayerVelocityEvent.
     * This is also the supported bridge for Player implementations without a
     * real client connection, such as the built-in Bot player.
     */
    public Vector consumePendingKnockback(Player player, Vector nativeVelocity) {
        return this.knockbackListener == null
                ? null
                : this.knockbackListener.consumePendingKnockback(player, nativeVelocity);
    }

    public Vector consumeServerControlledVelocity(Player player) {
        return getPlayerData(player).consumeServerControlledVelocity();
    }

    /**
     * Returns the knockback vector that was captured from the target's
     * PlayerVelocityEvent, without applying it. Used by the Bot feedback layer
     * to react to a hit it has already received.
     */
    public Vector consumeCapturedKnockback(Player player) {
        return getPlayerData(player).consumeCapturedKnockback();
    }

    /**
     * Delivers the profile knockback for a server-side Player implementation.
     *
     * <p>A synthetic Bot has no client, so the vector applied inside
     * PlayerVelocityEvent never reaches its entity: the event's velocity is
     * only transmitted to a real client. This method is therefore the actual
     * delivery path for the Bot and must apply the velocity exactly once.</p>
     *
     * <p>Both slots are drained on every call: the profile vector (pending hit)
     * and the vector captured from the velocity event (synthetic projectile or
     * cancelled-event paths). Whichever exists is applied, and the other is
     * cleared so a stale copy can never be applied on a later tick.</p>
     */
    public Vector deliverPendingKnockback(Player player) {
        PlayerKnockbackData data = getPlayerData(player);
        Vector captured = data.consumeCapturedKnockback();
        Vector pending = consumePendingKnockback(player, player.getVelocity());
        Vector applied = pending != null ? pending : captured;
        if (applied != null) {
            player.setVelocity(applied);
        }
        return applied;
    }

    public void applyLegacyPearlKnockback(Player attacker, Player victim, Vector impactVelocity) {
        if (this.knockbackListener != null) {
            this.knockbackListener.applyLegacyPearlKnockback(attacker, victim, impactVelocity);
        }
    }

    public void applyHitDelayWindow(Player player) {
        int window = getEffectiveNoDamageWindow(getPlayerData(player));
        if (player.getMaximumNoDamageTicks() != window) {
            player.setMaximumNoDamageTicks(window);
        }
    }

    /**
     * Returns whether the player is in NMS's upper hurt-resistance half. This
     * is the branch where a larger incoming amount can only add its damage
     * delta; it must not be treated as a fresh hit for legacy side effects.
     */
    public boolean isInsideHurtResistanceWindow(Player player) {
        int maximumNoDamageTicks = player.getMaximumNoDamageTicks();
        return player.getNoDamageTicks() > maximumNoDamageTicks / 2.0F;
    }

    public void beginLegacyDamage(Player player) {
        PlayerKnockbackData data = getPlayerData(player);
        // Capture the pre-listener state. No-damage modes arm the live counter
        // later in the same event, which must not turn that fresh hit into a
        // false damage-supplement hit during MONITOR processing.
        data.setLegacyDamageWindowActive(
                isInsideHurtResistanceWindow(player), Bukkit.getCurrentTick());
    }

    public boolean wasInsideHurtResistanceWindow(Player player) {
        return getPlayerData(player).isLegacyDamageWindowActive(Bukkit.getCurrentTick());
    }

    private int getEffectiveNoDamageWindow(PlayerKnockbackData data) {
        if (data.getConfiguredHitDelayWindow() >= 0) {
            return data.getConfiguredHitDelayWindow();
        }

        KnockbackProfile profile = resolveProfile(
                data.getProfileName(), data.getBranch(), "active knockback state");
        return profile == null ? KitSettingOldHitDelay.DEFAULT_DELAY : Math.max(0, profile.getHitDelay());
    }

    private void applyEntityInteractionRange(Player player, KnockbackProfile profile) {
        AttributeInstance attribute = player.getAttribute(Attribute.ENTITY_INTERACTION_RANGE);
        if (attribute == null) return;

        double range = profile == null
                ? attribute.getDefaultValue()
                : Math.max(0.0D, profile.getEntityInteractionRange());
        attribute.setBaseValue(range);
    }

    /**
     * Apply a KB profile to a player (called by Match/FFA when entering a game).
     */
    public void applyKnockback(Player player, String profileName) {
        KnockbackProfile requested = getProfile(profileName);
        KnockbackBranch branch = requested == null ? KnockbackBranch.DEFAULT : requested.getBranch();
        KnockbackProfile profile = resolveProfile(profileName, branch, "player " + player.getName());
        applyKnockback(player, profile, branch, -1);
    }

    /**
     * Enabled oldHitDelay uses the profile's NMS window. Disabled always uses the vanilla
     * window of 20, independently of the selected profile.
     */
    public void applyKnockback(Player player, Kit kit) {
        boolean oldHitDelayEnabled = kit.getKitSettings().stream()
                .filter(KitSettingOldHitDelay.class::isInstance)
                .anyMatch(setting -> setting.isEnabled());
        int hitDelayWindow = oldHitDelayEnabled ? -1 : KitSettingOldHitDelay.DEFAULT_DELAY;
        KnockbackBranch branch = getRequiredBranch(kit);
        applyKnockback(player, resolveProfile(kit), branch, hitDelayWindow);
    }

    /** Returns whether the player is still using the profile selected by this kit. */
    public boolean isKnockbackApplied(Player player, Kit kit) {
        KnockbackBranch expectedBranch = getRequiredBranch(kit);
        KnockbackProfile expected = resolveProfile(kit);

        PlayerKnockbackData data = getPlayerData(player);
        KnockbackProfile applied = getProfile(data.getProfileName());
        return data.getProfileName() != null
                && data.getBranch() == expectedBranch
                && applied == expected;
    }

    private void applyKnockback(Player player, KnockbackProfile profile,
                                KnockbackBranch branch, int hitDelayWindow) {
        if (misplaceHandler != null) {
            // Never carry delayed movement packets across kit/round transitions.
            misplaceHandler.clearPlayer(player.getUniqueId());
        }
        PlayerKnockbackData data = getPlayerData(player);
        data.setProfileName(profile == null ? null : profile.getName());
        data.setBranch(branch);
        data.setConfiguredHitDelayWindow(hitDelayWindow);
        data.setOnGround(player.isOnGround());
        data.setLastGroundY(player.getLocation().getY());
        data.clearLegacyState();
        data.setVelocity(null);
        if (legacySprintTracker != null) {
            legacySprintTracker.reset(player,
                    branch == KnockbackBranch.LEGACY && profile != null);
        }
        applyEntityInteractionRange(player, profile);
        // A profile transition starts a new hurt-resistance timeline. Reset the
        // live counter before changing the window, otherwise the first hit of a
        // new round/kit can be mistaken for a delta hit.
        player.setNoDamageTicks(0);
        applyHitDelayWindow(player);
    }
}
