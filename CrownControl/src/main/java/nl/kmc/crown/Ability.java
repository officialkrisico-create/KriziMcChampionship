package nl.kmc.crown;

import org.bukkit.Material;

/** The five crown abilities, in menu order. */
public enum Ability {
    DOUBLE_DOWN("double-down", Material.GOLD_BLOCK),
    LOCKDOWN("lockdown", Material.IRON_BARS),
    OVERDRIVE("overdrive", Material.SUGAR),
    BOUNTY("bounty", Material.SKELETON_SKULL),
    FORTIFY("fortify", Material.SHIELD);

    public final String key;
    public final Material icon;

    Ability(String key, Material icon) {
        this.key = key;
        this.icon = icon;
    }
}
