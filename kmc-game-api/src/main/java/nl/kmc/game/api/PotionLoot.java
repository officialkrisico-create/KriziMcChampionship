package nl.kmc.game.api;

import org.bukkit.Material;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.PotionMeta;
import org.bukkit.potion.PotionType;

import java.util.List;
import java.util.Random;

/**
 * Gives loot potions a real potion type. A bare {@code POTION}/{@code SPLASH_POTION} stack has no base
 * effect and shows up as "Uncraftable Potion"; this turns it into a proper, craftable-looking one.
 */
public final class PotionLoot {

    private static final List<String> GOOD = List.of(
            "HEALING", "STRONG_HEALING", "SWIFTNESS", "STRENGTH", "REGENERATION",
            "FIRE_RESISTANCE", "LEAPING", "WATER_BREATHING");
    private static final List<String> SPLASH_EXTRA = List.of("HARMING", "POISON", "SLOWNESS", "WEAKNESS");

    private PotionLoot() {}

    public static boolean isPotion(Material m) {
        return m == Material.POTION || m == Material.SPLASH_POTION || m == Material.LINGERING_POTION;
    }

    /**
     * Sets a potion type on {@code stack} if it is a potion.
     *
     * @param configured a PotionType name from config (e.g. "HEALING"), or null/blank for a random one
     */
    public static void apply(ItemStack stack, Random rng, String configured) {
        if (stack == null || !isPotion(stack.getType())) return;
        if (!(stack.getItemMeta() instanceof PotionMeta meta)) return;

        PotionType type = null;
        if (configured != null && !configured.isBlank()) type = parse(configured);
        if (type == null) {
            boolean thrown = stack.getType() != Material.POTION;
            int pool = GOOD.size() + (thrown ? SPLASH_EXTRA.size() : 0);
            int idx = rng.nextInt(pool);
            String name = idx < GOOD.size() ? GOOD.get(idx) : SPLASH_EXTRA.get(idx - GOOD.size());
            type = parse(name);
        }
        if (type == null) type = PotionType.HEALING;
        meta.setBasePotionType(type);
        stack.setItemMeta(meta);
    }

    private static PotionType parse(String name) {
        try { return PotionType.valueOf(name.trim().toUpperCase()); }
        catch (IllegalArgumentException e) { return null; }
    }
}
