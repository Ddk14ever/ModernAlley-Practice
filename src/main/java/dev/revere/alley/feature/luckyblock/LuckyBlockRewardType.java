package dev.revere.alley.feature.luckyblock;

import org.bukkit.Material;

/** Maps each LuckyBlock material to its independently configured reward kit. */
public enum LuckyBlockRewardType {
    GREEN("Green", Material.GREEN_CONCRETE_POWDER),
    BLUE("Blue", Material.BLUE_CONCRETE_POWDER),
    YELLOW("Yellow", Material.YELLOW_CONCRETE_POWDER);

    private final String displayName;
    private final Material blockMaterial;

    LuckyBlockRewardType(String displayName, Material blockMaterial) {
        this.displayName = displayName;
        this.blockMaterial = blockMaterial;
    }

    public String getDisplayName() {
        return this.displayName;
    }

    public Material getBlockMaterial() {
        return this.blockMaterial;
    }

    public static LuckyBlockRewardType fromBlock(Material material) {
        for (LuckyBlockRewardType type : values()) {
            if (type.blockMaterial == material) {
                return type;
            }
        }
        return null;
    }
}
