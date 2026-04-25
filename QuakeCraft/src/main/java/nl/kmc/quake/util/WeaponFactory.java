package nl.kmc.quake.util;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import nl.kmc.quake.QuakeCraftPlugin;
import nl.kmc.quake.models.PowerupType;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.inventory.ItemFlag;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;

import java.util.List;

/**
 * Factory for building weapon items with NBT tags so we can identify
 * them in click handlers.
 *
 * <p>Tag scheme:
 *   PDC key "quake:weapon" → "RAILGUN" / "SHOTGUN" / "SNIPER" / "MACHINE_GUN" / "GRENADE"
 */
public final class WeaponFactory {

    private WeaponFactory() {}

    public static final String WEAPON_KEY = "quake_weapon";

    /** Returns the weapon ID stored in NBT, or null if not a Quake weapon. */
    public static String getWeaponId(QuakeCraftPlugin plugin, ItemStack stack) {
        if (stack == null || !stack.hasItemMeta()) return null;
        ItemMeta meta = stack.getItemMeta();
        if (meta == null) return null;
        var key = new NamespacedKey(plugin, WEAPON_KEY);
        if (!meta.getPersistentDataContainer().has(key, PersistentDataType.STRING)) return null;
        return meta.getPersistentDataContainer().get(key, PersistentDataType.STRING);
    }

    public static boolean isQuakeWeapon(QuakeCraftPlugin plugin, ItemStack stack) {
        return getWeaponId(plugin, stack) != null;
    }

    // ----------------------------------------------------------------
    // Builders
    // ----------------------------------------------------------------

    public static ItemStack buildRailgun(QuakeCraftPlugin plugin) {
        return build(plugin, Material.WOODEN_HOE, "RAILGUN",
                Component.text("Railgun", NamedTextColor.GRAY, TextDecoration.BOLD),
                List.of(
                    Component.text("Right-click om te schieten", NamedTextColor.GRAY),
                    Component.text("Cooldown: " + plugin.getConfig().getInt("game.railgun-cooldown-ms", 1500) + "ms", NamedTextColor.DARK_GRAY),
                    Component.text("Range: " + (int) plugin.getConfig().getDouble("game.railgun-max-range", 80) + " blokken", NamedTextColor.DARK_GRAY)
                ));
    }

    public static ItemStack buildPowerup(QuakeCraftPlugin plugin, PowerupType type, int uses) {
        return switch (type) {
            case SHOTGUN -> build(plugin, Material.IRON_HOE, "SHOTGUN",
                    Component.text("Shotgun [" + uses + "]", NamedTextColor.YELLOW, TextDecoration.BOLD),
                    List.of(
                        Component.text("Right-click om 5 pellets te schieten", NamedTextColor.GRAY),
                        Component.text("Uses: " + uses, NamedTextColor.GOLD)
                    ));

            case SNIPER -> build(plugin, Material.NETHERITE_HOE, "SNIPER",
                    Component.text("Sniper [" + uses + "]", NamedTextColor.AQUA, TextDecoration.BOLD),
                    List.of(
                        Component.text("Right-click voor lange-afstand schot", NamedTextColor.GRAY),
                        Component.text("Uses: " + uses, NamedTextColor.GOLD)
                    ));

            case MACHINE_GUN -> build(plugin, Material.GOLDEN_HOE, "MACHINE_GUN",
                    Component.text("Machine Gun [" + uses + "]", NamedTextColor.GOLD, TextDecoration.BOLD),
                    List.of(
                        Component.text("Right-click om snel te schieten", NamedTextColor.GRAY),
                        Component.text("Uses: " + uses, NamedTextColor.GOLD)
                    ));

            case GRENADE -> build(plugin, Material.BONE, "GRENADE",
                    Component.text("Grenade", NamedTextColor.RED, TextDecoration.BOLD),
                    List.of(
                        Component.text("Right-click om te gooien", NamedTextColor.GRAY),
                        Component.text("Ontploft na 2 seconden", NamedTextColor.DARK_GRAY)
                    ));

            case SPEED -> build(plugin, Material.SUGAR, "SPEED_BUFF",
                    Component.text("Speed II Boost", NamedTextColor.GREEN, TextDecoration.BOLD),
                    List.of(
                        Component.text("Pak op voor 15 sec speed II", NamedTextColor.GRAY)
                    ));
        };
    }

    private static ItemStack build(QuakeCraftPlugin plugin, Material mat, String weaponId,
                                   Component name, List<Component> lore) {
        ItemStack stack = new ItemStack(mat);
        ItemMeta meta = stack.getItemMeta();
        if (meta == null) return stack;

        meta.displayName(name.decoration(TextDecoration.ITALIC, false));
        meta.lore(lore.stream().map(c -> c.decoration(TextDecoration.ITALIC, false)).toList());

        // Glint via fake enchant on hoes (looks better)
        if (mat != Material.BONE && mat != Material.SUGAR) {
            meta.addEnchant(Enchantment.UNBREAKING, 1, true);
            meta.addItemFlags(ItemFlag.HIDE_ENCHANTS, ItemFlag.HIDE_ATTRIBUTES);
        }

        // Make hoes unbreakable
        meta.setUnbreakable(true);
        meta.addItemFlags(ItemFlag.HIDE_UNBREAKABLE);

        // Tag with weapon ID
        var key = new NamespacedKey(plugin, WEAPON_KEY);
        meta.getPersistentDataContainer().set(key, PersistentDataType.STRING, weaponId);

        stack.setItemMeta(meta);
        return stack;
    }
}
