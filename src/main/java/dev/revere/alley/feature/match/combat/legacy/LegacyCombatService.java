package dev.revere.alley.feature.match.combat.legacy;

import dev.revere.alley.AlleyPlugin;
import dev.revere.alley.feature.kit.Kit;
import dev.revere.alley.feature.knockback.KnockbackManager;
import dev.revere.alley.feature.kit.setting.types.combat.*;
import io.papermc.paper.datacomponent.DataComponentTypes;
import io.papermc.paper.datacomponent.item.BlocksAttacks;
import org.bukkit.Bukkit;
import org.bukkit.GameRule;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Registry;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.entity.Player;
import org.bukkit.event.entity.EntityRegainHealthEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.scoreboard.Scoreboard;
import org.bukkit.scoreboard.Team;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
/**
 * @author Ddk1 ClaudeCode
 * @project Alley
 * @since 13/06/2026
 */
public class LegacyCombatService {

    private static final String LEGACY_COLLISION_TEAM = "alley_legacy_nc";
    private static final double LEGACY_ATTACK_SPEED = 1024.0D;
    private static final int LEGACY_NATURAL_REGEN_RATE = 80;
    // 1.8 sword block applies on the same tick the item is raised. The default
    // blocks_attacks component uses the shield warmup (5 ticks / 0.25s).
    private static final BlocksAttacks LEGACY_SWORD_BLOCKS_ATTACKS =
            BlocksAttacks.blocksAttacks().blockDelaySeconds(0.0F).build();

    private final Set<UUID> swordBlockKB = ConcurrentHashMap.newKeySet();
    private final Set<UUID> oldFood = ConcurrentHashMap.newKeySet();
    private final Set<UUID> oldOffhand = ConcurrentHashMap.newKeySet();
    private final Set<UUID> oldEnchants = ConcurrentHashMap.newKeySet();
    private final Set<UUID> blocking = ConcurrentHashMap.newKeySet();

    private final Map<UUID, Player> oldFoodPlayers = new ConcurrentHashMap<>();
    private final Map<UUID, Integer> naturalRegenTimers = new ConcurrentHashMap<>();
    private final Set<UUID> applyingNaturalHeal = ConcurrentHashMap.newKeySet();
    private BukkitRunnable tickTask;
    private SweepAttackHandler sweepAttackHandler;
    private AttackSoundSuppressor attackSoundSuppressor;

    // Unified hit delay — single tracker for all damage + knockback
    private final Map<UUID, Double> origAttackSpeed = new ConcurrentHashMap<>();
    private final Map<UUID, String> originalCollisionTeams = new ConcurrentHashMap<>();
    private final AlleyPlugin plugin;
    private Team legacyCollisionTeam;

    public LegacyCombatService(AlleyPlugin plugin) { this.plugin = plugin; }

    public void start() {
        tickTask = new BukkitRunnable() {
            @Override
            public void run() {
                tickOldFoodRegeneration();
            }
        };
        tickTask.runTaskTimer(plugin, 1L, 1L);
        sweepAttackHandler = new SweepAttackHandler(this);
        sweepAttackHandler.enable();
        attackSoundSuppressor = new AttackSoundSuppressor(this);
        attackSoundSuppressor.enable();
    }
    public void stop() {
        if (attackSoundSuppressor != null) { attackSoundSuppressor.disable(); attackSoundSuppressor = null; }
        if (sweepAttackHandler != null) { sweepAttackHandler.disable(); sweepAttackHandler = null; }
        if (tickTask != null) { tickTask.cancel(); tickTask = null; }
        swordBlockKB.clear(); oldFood.clear(); oldOffhand.clear(); oldEnchants.clear();
        oldFoodPlayers.clear(); naturalRegenTimers.clear(); applyingNaturalHeal.clear();
        blocking.clear(); origAttackSpeed.clear(); originalCollisionTeams.clear();
    }

    public void applyKit(Player player, Kit kit) {
        if (plugin.getService(KnockbackManager.class).usesLegacyCombat(kit)) applySwordBlockKB(player);
        if (kit.isSettingEnabled(KitSettingOldFood.class)) applyOldFood(player);
        if (kit.isSettingEnabled(KitSettingOldOffhand.class)) applyOldOffhand(player);
        if (kit.isSettingEnabled(KitSettingOldEnchantments.class)) applyOldEnchants(player);
    }

    public boolean isKitApplied(Player player, Kit kit) {
        UUID uniqueId = player.getUniqueId();
        return (!plugin.getService(KnockbackManager.class).usesLegacyCombat(kit) || hasSwordBlockKB(uniqueId))
                && (!kit.isSettingEnabled(KitSettingOldFood.class) || hasOldFood(uniqueId))
                && (!kit.isSettingEnabled(KitSettingOldOffhand.class) || hasOldOffhand(uniqueId))
                && (!kit.isSettingEnabled(KitSettingOldEnchantments.class) || hasOldEnchants(uniqueId));
    }

    public void removeAll(Player player) {
        UUID u = player.getUniqueId();
        removeSwordBlockKB(player); removeOldFood(player); removeOldOffhand(player); removeOldEnchants(player);
        blocking.remove(u); origAttackSpeed.remove(u);
    }

    // ---- oldSwordBlockKB ----
    void applySwordBlockKB(Player p) {
        UUID uniqueId = p.getUniqueId();
        boolean newlyEnabled = swordBlockKB.add(uniqueId);
        if (newlyEnabled) {
            removeAttackCooldown(p);
            rememberCollisionTeam(p);
        }
        getLegacyCollisionTeam().addEntry(p.getName());
        applyBlockableToSwords(p);
    }
    void removeSwordBlockKB(Player p) {
        UUID uniqueId = p.getUniqueId();
        swordBlockKB.remove(uniqueId);
        blocking.remove(uniqueId);
        if (legacyCollisionTeam != null) legacyCollisionTeam.removeEntry(p.getName());
        restoreCollisionTeam(p);
        removeBlockableFromSwords(p);
        restoreAttackCooldown(p);
    }
    public boolean hasSwordBlockKB(UUID u) { return swordBlockKB.contains(u); }
    public void suppressPearlTeleportSound(org.bukkit.Location from, org.bukkit.Location to) {
        if (attackSoundSuppressor != null) attackSoundSuppressor.markPearlTeleport(from, to);
    }
    public boolean isBlocking(UUID u) { return blocking.contains(u); }
    public void setBlocking(UUID u, boolean v) { if (v) blocking.add(u); else blocking.remove(u); }

    private Team getLegacyCollisionTeam() {
        Scoreboard scoreboard = Bukkit.getScoreboardManager().getMainScoreboard();
        Team team = scoreboard.getTeam(LEGACY_COLLISION_TEAM);
        if (team == null) team = scoreboard.registerNewTeam(LEGACY_COLLISION_TEAM);
        team.setOption(Team.Option.COLLISION_RULE, Team.OptionStatus.NEVER);
        legacyCollisionTeam = team;
        return team;
    }

    private void rememberCollisionTeam(Player player) {
        Scoreboard scoreboard = Bukkit.getScoreboardManager().getMainScoreboard();
        for (Team team : scoreboard.getTeams()) {
            if (!team.getName().equals(LEGACY_COLLISION_TEAM) && team.hasEntry(player.getName())) {
                originalCollisionTeams.put(player.getUniqueId(), team.getName());
                return;
            }
        }
    }

    private void restoreCollisionTeam(Player player) {
        String teamName = originalCollisionTeams.remove(player.getUniqueId());
        if (teamName == null) return;
        Team team = Bukkit.getScoreboardManager().getMainScoreboard().getTeam(teamName);
        if (team != null) team.addEntry(player.getName());
    }

    // ---- blocks_attacks ----
    public void applyBlockableToSwords(Player p) {
        for (ItemStack i : p.getInventory().getContents())
            if (i != null && isSword(i.getType()))
                i.setData(DataComponentTypes.BLOCKS_ATTACKS, LEGACY_SWORD_BLOCKS_ATTACKS);
    }
    public void removeBlockableFromSwords(Player p) {
        for (ItemStack i : p.getInventory().getContents())
            if (i != null && isSword(i.getType()) && i.hasData(DataComponentTypes.BLOCKS_ATTACKS))
                i.unsetData(DataComponentTypes.BLOCKS_ATTACKS);
    }
    public void onHeldSword(Player p, ItemStack item) {
        if (!hasSwordBlockKB(p.getUniqueId())) return;
        if (item != null && isSword(item.getType()))
            item.setData(DataComponentTypes.BLOCKS_ATTACKS, LEGACY_SWORD_BLOCKS_ATTACKS);
    }

    // ---- Attack cooldown ----
    void removeAttackCooldown(Player p) {
        try {
            Attribute a = Registry.ATTRIBUTE.get(NamespacedKey.minecraft("attack_speed"));
            if (a != null) { AttributeInstance ai = p.getAttribute(a); if (ai != null) { origAttackSpeed.put(p.getUniqueId(), ai.getBaseValue()); ai.setBaseValue(LEGACY_ATTACK_SPEED); } }
        } catch (Exception ignored) {}
    }
    private void restoreAttackCooldown(Player p) {
        Double o = origAttackSpeed.remove(p.getUniqueId());
        if (o != null) try { Attribute a = Registry.ATTRIBUTE.get(NamespacedKey.minecraft("attack_speed")); if (a != null) { AttributeInstance ai = p.getAttribute(a); if (ai != null) ai.setBaseValue(o); } } catch (Exception ignored) {}
    }

    // ---- oldFood ----
    void applyOldFood(Player p) {
        UUID uniqueId = p.getUniqueId();
        boolean newlyEnabled = oldFood.add(uniqueId);
        oldFoodPlayers.put(uniqueId, p);
        if (newlyEnabled) naturalRegenTimers.put(uniqueId, 0);
    }
    void removeOldFood(Player p) {
        UUID uniqueId = p.getUniqueId();
        oldFood.remove(uniqueId);
        oldFoodPlayers.remove(uniqueId);
        naturalRegenTimers.remove(uniqueId);
        applyingNaturalHeal.remove(uniqueId);
    }
    public boolean hasOldFood(UUID u) { return oldFood.contains(u); }
    boolean isApplyingNaturalHeal(UUID uniqueId) { return applyingNaturalHeal.contains(uniqueId); }

    private void tickOldFoodRegeneration() {
        for (Map.Entry<UUID, Player> entry : oldFoodPlayers.entrySet()) {
            UUID uniqueId = entry.getKey();
            Player player = entry.getValue();
            Boolean naturalRegeneration = player.getWorld().getGameRuleValue(GameRule.NATURAL_REGENERATION);
            boolean canHeal = Boolean.TRUE.equals(naturalRegeneration)
                    && !player.isDead()
                    && player.getFoodLevel() >= 18
                    && player.getHealth() > 0.0
                    && player.getHealth() < player.getMaxHealth();
            if (!canHeal) {
                naturalRegenTimers.put(uniqueId, 0);
                continue;
            }

            int timer = naturalRegenTimers.merge(uniqueId, 1, Integer::sum);
            if (timer < LEGACY_NATURAL_REGEN_RATE) continue;
            naturalRegenTimers.put(uniqueId, 0);

            double healthBefore = player.getHealth();
            applyingNaturalHeal.add(uniqueId);
            try {
                player.heal(1.0, EntityRegainHealthEvent.RegainReason.REGEN);
            } finally {
                applyingNaturalHeal.remove(uniqueId);
            }
            if (player.getHealth() > healthBefore) {
                player.setExhaustion(Math.min(40.0F, player.getExhaustion() + 3.0F));
            }
        }
    }

    // ---- oldOffhandSounds ----
    void applyOldOffhand(Player p) {
        oldOffhand.add(p.getUniqueId());
        ItemStack offhand = p.getInventory().getItemInOffHand();
        if (offhand == null || offhand.getType().isAir()) return;

        p.getInventory().setItemInOffHand(new ItemStack(Material.AIR));
        p.getInventory().addItem(offhand).values()
                .forEach(item -> p.getWorld().dropItemNaturally(p.getLocation(), item));
    }
    void removeOldOffhand(Player p) { oldOffhand.remove(p.getUniqueId()); }
    public boolean hasOldOffhand(UUID u) { return oldOffhand.contains(u); }

    // ---- oldEnchantsArmor ----
    void applyOldEnchants(Player p) { oldEnchants.add(p.getUniqueId()); }
    void removeOldEnchants(Player p) { oldEnchants.remove(p.getUniqueId()); }
    public boolean hasOldEnchants(UUID u) { return oldEnchants.contains(u); }

    private boolean isSword(Material t) { return t.name().endsWith("_SWORD"); }
}
