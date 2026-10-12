package nl.kmc.game.api;

import io.papermc.paper.registry.RegistryAccess;
import io.papermc.paper.registry.RegistryKey;
import org.bukkit.ChatColor;
import org.bukkit.Color;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.LeatherArmorMeta;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;

/**
 * "Feet only" racing look (Adventure Escape, Parkour Warrior, Elytra Endrium): every racer is made invisible,
 * but the armour layer still renders — so what other players see of you is a pair of leather boots in your
 * team colour. Keeps a crowded start line readable without a forest of player models.
 */
public final class FeetOnlyVisibility {

    private FeetOnlyVisibility() {}

    /** Makes {@code p} invisible and gives them team-coloured boots (other armour pieces are removed). */
    public static void apply(Player p, ChatColor teamColor) {
        PotionEffectType invis = effect("invisibility");
        if (invis != null) {
            p.addPotionEffect(new PotionEffect(invis, PotionEffect.INFINITE_DURATION, 0, true, false, false));
        }
        ItemStack boots = new ItemStack(Material.LEATHER_BOOTS);
        if (boots.getItemMeta() instanceof LeatherArmorMeta meta) {
            meta.setColor(toColor(teamColor));
            meta.setUnbreakable(true);
            boots.setItemMeta(meta);
        }
        var inv = p.getInventory();
        inv.setHelmet(null);
        inv.setLeggings(null);
        inv.setBoots(boots);
        // The chestplate slot is left alone on purpose (Elytra Endrium needs its elytra there).
    }

    /** Takes the invisibility away again. */
    public static void clear(Player p) {
        PotionEffectType invis = effect("invisibility");
        if (invis != null) p.removePotionEffect(invis);
    }

    private static PotionEffectType effect(String key) {
        try {
            return RegistryAccess.registryAccess().getRegistry(RegistryKey.MOB_EFFECT)
                    .get(NamespacedKey.minecraft(key));
        } catch (Exception e) {
            return null;
        }
    }

    public static Color toColor(ChatColor c) {
        if (c == null) return Color.WHITE;
        return switch (c) {
            case DARK_RED -> Color.fromRGB(170, 0, 0);
            case RED -> Color.fromRGB(255, 85, 85);
            case GOLD -> Color.fromRGB(255, 170, 0);
            case YELLOW -> Color.fromRGB(255, 255, 85);
            case DARK_GREEN -> Color.fromRGB(0, 170, 0);
            case GREEN -> Color.fromRGB(85, 255, 85);
            case AQUA -> Color.fromRGB(85, 255, 255);
            case DARK_AQUA -> Color.fromRGB(0, 170, 170);
            case DARK_BLUE -> Color.fromRGB(0, 0, 170);
            case BLUE -> Color.fromRGB(85, 85, 255);
            case LIGHT_PURPLE -> Color.fromRGB(255, 85, 255);
            case DARK_PURPLE -> Color.fromRGB(170, 0, 170);
            case GRAY -> Color.fromRGB(170, 170, 170);
            case DARK_GRAY -> Color.fromRGB(85, 85, 85);
            case BLACK -> Color.fromRGB(30, 30, 30);
            default -> Color.WHITE;
        };
    }
}
