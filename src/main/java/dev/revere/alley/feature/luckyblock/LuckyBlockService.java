package dev.revere.alley.feature.luckyblock;

import dev.revere.alley.bootstrap.lifecycle.Service;
import dev.revere.alley.feature.match.Match;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.block.BlockState;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

import java.util.Optional;

/**
 * Owns LuckyBlock reward selection and temporary penalty cleanup.
 */
public interface LuckyBlockService extends Service {
    boolean isLuckyBlock(Material material);

    /** Returns whether this entity was spawned as a temporary LuckyBlock penalty mob. */
    boolean isTemporaryPenaltyMob(Entity entity);

    /** Snapshots the three configured reward kits when a LuckyBlock match starts. */
    void prepareMatch(Match match);

    Optional<ItemStack> createRandomReward(Match match, Material luckyBlockMaterial);

    boolean shouldApplyPenalty();

    void applyRandomPenalty(Player player, Match match);

    void trackBrokenLuckyBlock(Match match, BlockState originalState, Location location);

    void cleanupMatch(Match match);
}
