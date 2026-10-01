package nl.kmc.mayhem.models;

import org.bukkit.Material;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;

import java.util.List;

/**
 * Pickups that spawn around a team's arena during a Mob Mayhem match —
 * same pattern as QuakeCraft's powerups, but granting a straightforward
 * combat buff instead of a weapon swap.
 */
public enum PowerupType {

    SPEED("Snelheid", Material.SUGAR, "speed", 1, 30),
    STRENGTH("Kracht", Material.BLAZE_POWDER, "strength", 0, 30),
    REGENERATION("Regeneratie", Material.GHAST_TEAR, "regeneration", 1, 15),
    RESISTANCE("Weerstand", Material.IRON_CHESTPLATE, "resistance", 0, 30),
    ABSORPTION("Absorptie", Material.GOLDEN_APPLE, "absorption", 1, 45),
    INSTANT_HEAL("Genezing", Material.GLISTERING_MELON_SLICE, "instant_health", 0, 0);

    private final String   displayName;
    private final Material icon;
    private final String   configKey;
    private final int      amplifier;
    private final int      durationSeconds;

    PowerupType(String displayName, Material icon, String configKey, int amplifier, int durationSeconds) {
        this.displayName     = displayName;
        this.icon            = icon;
        this.configKey       = configKey;
        this.amplifier       = amplifier;
        this.durationSeconds = durationSeconds;
    }

    public String   getDisplayName()     { return displayName; }
    public Material getIcon()            { return icon; }
    public String   getConfigKey()       { return configKey; }

    /** Builds the potion effect(s) to apply on pickup, looked up from the Paper registry (version-safe). */
    public List<PotionEffect> buildEffects() {
        PotionEffectType type = lookup(configKey);
        if (type == null) return List.of();
        return List.of(new PotionEffect(type, Math.max(1, durationSeconds * 20), amplifier, true, true, true));
    }

    public static PowerupType fromConfigKey(String key) {
        for (PowerupType t : values()) if (t.configKey.equalsIgnoreCase(key)) return t;
        return null;
    }

    private static PotionEffectType lookup(String key) {
        try {
            return io.papermc.paper.registry.RegistryAccess.registryAccess()
                    .getRegistry(io.papermc.paper.registry.RegistryKey.MOB_EFFECT)
                    .get(org.bukkit.NamespacedKey.minecraft(key));
        } catch (Exception e) {
            return null;
        }
    }
}
