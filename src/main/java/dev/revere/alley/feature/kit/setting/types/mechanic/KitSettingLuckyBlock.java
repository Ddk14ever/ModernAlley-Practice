package dev.revere.alley.feature.kit.setting.types.mechanic;

import dev.revere.alley.feature.kit.setting.KitSetting;
import dev.revere.alley.feature.kit.setting.annotation.KitSettingData;

/**
 * Enables the LuckyBlock arena mechanic for a kit.
 */
@KitSettingData(
        name = "LuckyBlock",
        description = "Breaking lucky blocks can drop random kit items or trigger a penalty",
        enabled = false
)
public class KitSettingLuckyBlock extends KitSetting {
}
