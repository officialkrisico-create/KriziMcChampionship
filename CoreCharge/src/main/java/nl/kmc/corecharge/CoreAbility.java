package nl.kmc.corecharge;

import org.bukkit.Material;

/** The four core abilities, in menu order. */
public enum CoreAbility {
    SPEED_BOOST("speed-boost", Material.SUGAR),
    SHIELD_PULSE("shield-pulse", Material.SHIELD),
    SCORE_SURGE("score-surge", Material.GLOWSTONE_DUST),
    FINAL_OVERLOAD("final-overload", Material.NETHER_STAR);

    public final String key;
    public final Material icon;

    CoreAbility(String key, Material icon) {
        this.key = key;
        this.icon = icon;
    }
}
