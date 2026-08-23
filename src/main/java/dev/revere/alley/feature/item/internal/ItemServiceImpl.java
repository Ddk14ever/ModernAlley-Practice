package dev.revere.alley.feature.item.internal;

import dev.revere.alley.AlleyPlugin;
import dev.revere.alley.bootstrap.AlleyContext;
import dev.revere.alley.bootstrap.annotation.Service;
import dev.revere.alley.common.constants.TexturesConstant;
import dev.revere.alley.common.item.ItemBuilder;
import dev.revere.alley.core.config.ConfigService;
import dev.revere.alley.feature.item.ItemService;
import dev.revere.alley.feature.item.CustomItemType;
import lombok.Getter;
import org.bukkit.GameMode;
import org.bukkit.Material;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.inventory.meta.Damageable;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.NamespacedKey;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeModifier;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;

import java.util.Arrays;
import java.util.List;

/**
 * @author Emmy
 * 作者 Emmy
 * @project alley-practice
 * 项目 alley-practice
 * @since 18/07/2025
 * 自 18/07/2025
 */
@Getter
@Service(provides = ItemService.class, priority = 440)
public class ItemServiceImpl implements ItemService {
    private final ConfigService configService;

    private String GOLDEN_HEAD_TEXTURE;
    private ItemStack goldenHead;
    private ItemStack specialGoldenApple;
    private ItemStack ak47;
    private ItemStack awp;
    private ItemStack grapplingHook;

    private final NamespacedKey customItemKey = new NamespacedKey(AlleyPlugin.getInstance(), "custom_item");
    private final NamespacedKey ammoKey = new NamespacedKey(AlleyPlugin.getInstance(), "weapon_ammo");

    /**
     * DI Constructor for the ItemService class.
     * ItemService 类的依赖注入构造函数。
     *
     * @param configService The configuration service to load item textures from.
     * 用于加载物品纹理的配置服务。
     */
    public ItemServiceImpl(ConfigService configService) {
        this.configService = configService;
    }

    @Override
    public void initialize(AlleyContext context) {
        this.loadGoldenHeadTextureAndConstructItem();
        this.loadCustomItems();
    }

    @Override
    public void reloadItems() {
        this.loadGoldenHeadTextureAndConstructItem();
        this.loadCustomItems();
    }

    private void loadGoldenHeadTextureAndConstructItem() {
        FileConfiguration config = this.configService.getTexturesConfig();
        if (config == null) {
            throw new IllegalStateException("Textures configuration is not loaded.");
        }

        String path = "golden-head";

        this.GOLDEN_HEAD_TEXTURE = config.getString(path + ".texture", TexturesConstant.GOLDEN_STEVE_SKIN);

        String name = config.getString(path + ".name", "&6Golden Head");
        List<String> lore = config.getStringList(path + ".lore");

        this.goldenHead = new ItemBuilder(Material.PLAYER_HEAD)
                .name(name)
                .lore(lore)
                .durability(3)
                .setSkullTexture(this.GOLDEN_HEAD_TEXTURE)
                .build();
        mark(this.goldenHead, CustomItemType.GOLDEN_HEAD, -1, -1);
    }

    private void loadCustomItems() {
        FileConfiguration config = this.configService.getCustomItemsConfig();
        if (config == null) {
            throw new IllegalStateException("Custom item configuration is not loaded.");
        }

        this.specialGoldenApple = createItem(config, "special-golden-apple",
                Material.GOLDEN_APPLE, CustomItemType.SPECIAL_GOLDEN_APPLE, -1);
        this.ak47 = createItem(config, "ak47", Material.SHEARS, CustomItemType.AK47,
                Math.max(1, config.getInt("ak47.magazine-size", 30)));
        this.awp = createItem(config, "awp", Material.DIAMOND_HOE, CustomItemType.AWP,
                Math.max(1, config.getInt("awp.magazine-size", 5)));
        this.grapplingHook = createItem(config, "grappling-hook", Material.FISHING_ROD,
                CustomItemType.GRAPPLING_HOOK, -1);
    }

    @Override public ItemStack getGoldenHead() { return this.goldenHead.clone(); }
    @Override public ItemStack getSpecialGoldenApple() { return this.specialGoldenApple.clone(); }
    @Override public ItemStack getAk47() { return this.ak47.clone(); }
    @Override public ItemStack getAwp() { return this.awp.clone(); }
    @Override public ItemStack getGrapplingHook() { return this.grapplingHook.clone(); }

    private ItemStack createItem(FileConfiguration config, String path, Material fallback,
                                 CustomItemType type, int ammo) {
        Material material = Material.matchMaterial(config.getString(path + ".material", fallback.name()));
        if (material == null) material = fallback;
        String defaultName = type == CustomItemType.GRAPPLING_HOOK
                ? "&b&lGrappling Hook" : "&f" + type.name();
        ItemStack item = new ItemBuilder(material)
                .name(config.getString(path + ".name", defaultName))
                .lore(config.getStringList(path + ".lore"))
                .build();
        mark(item, type, ammo, config.getInt(path + ".custom-model-data", -1));
        if (type == CustomItemType.AK47 || type == CustomItemType.AWP) {
            applyAttackSpeed(item, config.getDouble(path + ".attack-speed", 0.833333D));
        }
        return item;
    }

    private void applyAttackSpeed(ItemStack item, double attackSpeed) {
        ItemMeta meta = item.getItemMeta();
        if (meta == null || !Double.isFinite(attackSpeed)) return;
        meta.removeAttributeModifier(Attribute.ATTACK_SPEED);
        double amount = attackSpeed - 4.0D;
        AttributeModifier modifier = new AttributeModifier(
                java.util.UUID.nameUUIDFromBytes(("alley-weapon-speed:" + item.getType().name())
                        .getBytes(java.nio.charset.StandardCharsets.UTF_8)),
                "alley_weapon_attack_speed", amount,
                AttributeModifier.Operation.ADD_NUMBER, EquipmentSlot.HAND);
        meta.addAttributeModifier(Attribute.ATTACK_SPEED, modifier);
        item.setItemMeta(meta);
    }

    private void mark(ItemStack item, CustomItemType type, int ammo, int modelData) {
        ItemMeta meta = item.getItemMeta();
        if (meta == null) return;
        PersistentDataContainer data = meta.getPersistentDataContainer();
        data.set(this.customItemKey, PersistentDataType.STRING, type.name());
        if (ammo >= 0) data.set(this.ammoKey, PersistentDataType.INTEGER, ammo);
        if (modelData >= 0) meta.setCustomModelData(modelData);
        if (type == CustomItemType.AK47 || type == CustomItemType.AWP) {
            // 1.21.4+ resolves the item model through the item_model data
            // component. This is item-specific and never changes every bow or
            // spyglass in the server.
            meta.setItemModel(new NamespacedKey(AlleyPlugin.getInstance(),
                    type == CustomItemType.AK47 ? "ak47" : "awp"));
        }
        item.setItemMeta(meta);
    }

    @Override
    public CustomItemType getCustomItemType(ItemStack item) {
        if (item == null || item.getType().isAir() || !item.hasItemMeta()) return null;
        ItemMeta itemMeta = item.getItemMeta();
        if (itemMeta == null) return null;
        String id = itemMeta.getPersistentDataContainer()
                .get(this.customItemKey, PersistentDataType.STRING);
        if (id == null) return null;
        try {
            CustomItemType type = CustomItemType.valueOf(id);
            // AK stacks from the first implementation used several temporary
            // base materials. The AWP base is now a DIAMOND_HOE (unusable, so
            // the client never enters an item-use state that would suppress
            // left-click); its model comes from the item_model component.
            if (type == CustomItemType.AK47
                    && (item.getType() == Material.BOW || item.getType() == Material.PAPER
                    || item.getType() == Material.SPYGLASS || item.getType() == Material.SHIELD)) {
                item.setType(Material.SHEARS);
            }
            if ((type == CustomItemType.AK47 || type == CustomItemType.AWP)
                    && item.getItemMeta() != null) {
                ItemMeta meta = item.getItemMeta();
                NamespacedKey modelKey = new NamespacedKey(AlleyPlugin.getInstance(),
                        type == CustomItemType.AK47 ? "ak47" : "awp");
                if (!modelKey.equals(meta.getItemModel())) {
                    meta.setItemModel(modelKey);
                    item.setItemMeta(meta);
                }
            }
            return type;
        } catch (IllegalArgumentException ignored) {
            return null;
        }
    }

    @Override
    public int getAmmo(ItemStack weapon) {
        if (weapon == null || !weapon.hasItemMeta()) return 0;
        ItemMeta weaponMeta = weapon.getItemMeta();
        if (weaponMeta == null) return 0;
        PersistentDataContainer data = weaponMeta.getPersistentDataContainer();
        Integer stored = data.get(this.ammoKey, PersistentDataType.INTEGER);
        if (stored != null) return Math.max(0, stored);

        // Migrate weapon stacks made by the first implementation.  Those
        // stacks already carried the durability bar but not the ammo PDC, so
        // deriving the round count here keeps them usable after an update.
        CustomItemType type = getCustomItemTypeWithoutMutation(weapon);
        if (type != CustomItemType.AK47 && type != CustomItemType.AWP) return 0;
        int magazine = this.configService.getCustomItemsConfig().getInt(
                type == CustomItemType.AK47 ? "ak47.magazine-size" : "awp.magazine-size",
                type == CustomItemType.AK47 ? 30 : 5);
        if (!(weapon.getItemMeta() instanceof Damageable damageable)
                || weapon.getType().getMaxDurability() <= 1 || magazine <= 0) return magazine;
        double ratio = 1.0D - damageable.getDamage()
                / (double) (weapon.getType().getMaxDurability() - 1);
        return Math.max(0, Math.min(magazine, (int) Math.round(ratio * magazine)));
    }

    @Override
    public void setAmmo(ItemStack weapon, int ammo) {
        if (weapon == null || !weapon.hasItemMeta()) return;
        ItemMeta meta = weapon.getItemMeta();
        if (meta == null) return;
        CustomItemType type = getCustomItemTypeWithoutMutation(weapon);
        int magazine = type == CustomItemType.AK47 || type == CustomItemType.AWP
                ? this.configService.getCustomItemsConfig().getInt(
                type == CustomItemType.AK47 ? "ak47.magazine-size" : "awp.magazine-size",
                type == CustomItemType.AK47 ? 30 : 5)
                : Integer.MAX_VALUE;
        int safeAmmo = Math.max(0, Math.min(magazine, ammo));
        Integer previousAmmo = meta.getPersistentDataContainer()
                .get(this.ammoKey, PersistentDataType.INTEGER);
        boolean changed = !Integer.valueOf(safeAmmo).equals(previousAmmo);
        if (changed) {
            meta.getPersistentDataContainer().set(this.ammoKey, PersistentDataType.INTEGER, safeAmmo);
        }
        if (type == CustomItemType.AK47 || type == CustomItemType.AWP) {
            int durability = calculateDurability(weapon.getType(), safeAmmo,
                    this.configService.getCustomItemsConfig().getInt(
                            type == CustomItemType.AK47 ? "ak47.magazine-size" : "awp.magazine-size",
                            type == CustomItemType.AK47 ? 30 : 5));
            if (meta instanceof Damageable damageable && damageable.getDamage() != durability) {
                damageable.setDamage(durability);
                changed = true;
            }
        }
        // Avoid replacing the held ItemMeta on every HUD tick: repeated no-op
        // replacements can reset client-visible state (e.g. the item model or
        // durability bar) and cause visual flicker.
        if (changed) weapon.setItemMeta(meta);
    }

    private CustomItemType getCustomItemTypeWithoutMutation(ItemStack item) {
        if (item == null || !item.hasItemMeta()) return null;
        ItemMeta meta = item.getItemMeta();
        if (meta == null) return null;
        String id = meta.getPersistentDataContainer()
                .get(this.customItemKey, PersistentDataType.STRING);
        if (id == null) return null;
        try {
            return CustomItemType.valueOf(id);
        } catch (IllegalArgumentException ignored) {
            return null;
        }
    }

    private void updateDurability(ItemMeta meta, Material material, int ammo, int magazine) {
        if (!(meta instanceof Damageable damageable)) return;
        damageable.setDamage(calculateDurability(material, ammo, magazine));
    }

    private int calculateDurability(Material material, int ammo, int magazine) {
        int maximum = material.getMaxDurability();
        if (maximum <= 0 || magazine <= 0) return 0;
        double ratio = Math.max(0.0D, Math.min(1.0D, ammo / (double) magazine));
        return (int) Math.round((maximum - 1) * (1.0D - ratio));
    }

    @Override
    public void performHeadConsume(Player player, ItemStack item) {
        Arrays.asList(
                new PotionEffect(PotionEffectType.REGENERATION, 20 * 5, 2), // Regeneration III for 5 seconds
                // 再生 III，持续 5 秒
                new PotionEffect(PotionEffectType.SPEED, 20 * 10, 0),       // Speed I for 10 seconds
                // 速度 I，持续 10 秒
                new PotionEffect(PotionEffectType.ABSORPTION, 20 * 120, 0)  // Absorption I for 2 minutes
                // 伤害吸收 I，持续 2 分钟
        ).forEach(player::addPotionEffect);

        if (player.getGameMode() == GameMode.CREATIVE) {
            return;
        }

        if (item.getAmount() > 1) {
            item.setAmount(item.getAmount() - 1);
        } else {
            player.getInventory().remove(item);
        }
    }

    @Override
    public void performSpecialGoldenAppleConsume(Player player, ItemStack item) {
        player.addPotionEffect(new PotionEffect(PotionEffectType.REGENERATION, 20 * 8, 2));
        player.addPotionEffect(new PotionEffect(PotionEffectType.ABSORPTION, 20 * 120, 0));
        if (player.getGameMode() != GameMode.CREATIVE) {
            if (item.getAmount() > 1) item.setAmount(item.getAmount() - 1);
            else player.getInventory().removeItem(item);
        }
    }
}
