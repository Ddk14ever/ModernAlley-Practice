package dev.revere.alley.feature.combat;

import dev.revere.alley.AlleyPlugin;
import org.bukkit.Bukkit;
import org.bukkit.damage.DamageSource;
import org.bukkit.entity.EnderCrystal;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.entity.Projectile;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.metadata.FixedMetadataValue;
import org.bukkit.metadata.MetadataValue;

import java.util.UUID;

public final class CombatAttribution {
    public static final String END_CRYSTAL_OWNER_METADATA = "alley_end_crystal_owner";

    private CombatAttribution() {
    }

    public static void setEndCrystalOwner(EnderCrystal crystal, Player player) {
        crystal.setMetadata(END_CRYSTAL_OWNER_METADATA,
                new FixedMetadataValue(AlleyPlugin.getInstance(), player.getUniqueId().toString()));
    }

    public static Player getAttacker(EntityDamageByEntityEvent event) {
        Player attacker = getAttacker(event.getDamageSource());
        return attacker != null ? attacker : getAttacker(event.getDamager());
    }

    public static Player getAttacker(DamageSource source) {
        if (source == null) return null;
        Player attacker = getAttacker(source.getCausingEntity());
        return attacker != null ? attacker : getAttacker(source.getDirectEntity());
    }

    private static Player getAttacker(Entity entity) {
        if (entity instanceof Player) {
            return (Player) entity;
        }

        if (entity instanceof Projectile && ((Projectile) entity).getShooter() instanceof Player) {
            return (Player) ((Projectile) entity).getShooter();
        }

        if (!(entity instanceof EnderCrystal)) {
            return null;
        }

        for (MetadataValue metadata : entity.getMetadata(END_CRYSTAL_OWNER_METADATA)) {
            if (metadata.getOwningPlugin() != AlleyPlugin.getInstance()) {
                continue;
            }

            try {
                return Bukkit.getPlayer(UUID.fromString(metadata.asString()));
            } catch (IllegalArgumentException ignored) {
                return null;
            }
        }

        return null;
    }
}
