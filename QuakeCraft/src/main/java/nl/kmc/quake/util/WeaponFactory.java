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

            case BAZOOKA -> build(plugin, Material.DIAMOND_HOE, "BAZOOKA",
                    Component.text("Bazooka [" + uses + "]", NamedTextColor.DARK_RED, TextDecoration.BOLD),
                    List.of(
                        Component.text("Right-click om een raket af te vuren", NamedTextColor.GRAY),
                        Component.text("Explodeert bij inslag (AoE)", NamedTextColor.DARK_GRAY),
                        Component.text("Uses: " + uses, NamedTextColor.GOLD)
                    ));

            case GRAPPLE -> build(plugin, Material.FISHING_ROD, "GRAPPLE",
                    Component.text("Grappling Hook [" + uses + "]", NamedTextColor.GREEN, TextDecoration.BOLD),
                    List.of(
                        Component.text("Right-click op een blok om jezelf erheen te trekken", NamedTextColor.GRAY),
                        Component.text("Perfect voor jump pads en hoge plekken", NamedTextColor.DARK_GRAY),
                        Component.text("Uses: " + uses, NamedTextColor.GOLD)
                    ));

            case PROXIMITY_MINE -> build(plugin, Material.TRIPWIRE_HOOK, "PROXIMITY_MINE",
                    Component.text("Proximity Mine [" + uses + "]", NamedTextColor.RED, TextDecoration.BOLD),
                    List.of(
                        Component.text("Right-click om een mijn te plaatsen", NamedTextColor.GRAY),
                        Component.text("Explodeert als een vijand dichtbij komt", NamedTextColor.DARK_GRAY),
                        Component.text("Uses: " + uses, NamedTextColor.GOLD)
                    ));

            case SMOKE_BOMB -> build(plugin, Material.GUNPOWDER, "SMOKE_BOMB",
                    Component.text("Smoke Bomb [" + uses + "]", NamedTextColor.DARK_GRAY, TextDecoration.BOLD),
                    List.of(
                        Component.text("Right-click om te gooien", NamedTextColor.GRAY),
                        Component.text("Maakt een rookwolk die zicht blokkeert", NamedTextColor.DARK_GRAY),
                        Component.text("Uses: " + uses, NamedTextColor.GOLD)
                    ));

            case IMPULSE_CANNON -> build(plugin, Material.HEAVY_CORE, "IMPULSE_CANNON",
                    Component.text("Impulse Cannon [" + uses + "]", NamedTextColor.AQUA, TextDecoration.BOLD),
                    List.of(
                        Component.text("Right-click voor een knockback-blast", NamedTextColor.GRAY),
                        Component.text("Lanceert vijanden weg — en jezelf (rocket-jump)", NamedTextColor.DARK_GRAY),
                        Component.text("Doodt niet", NamedTextColor.DARK_AQUA),
                        Component.text("Uses: " + uses, NamedTextColor.GOLD)
                    ));

            case JUMP_PAD_GRENADE -> build(plugin, Material.SLIME_BALL, "JUMP_PAD_GRENADE",
                    Component.text("Jump Pad Grenade [" + uses + "]", NamedTextColor.GREEN, TextDecoration.BOLD),
                    List.of(
                        Component.text("Gooi om een tijdelijke jump pad te maken", NamedTextColor.GRAY),
                        Component.text("Iedereen kan hem gebruiken", NamedTextColor.DARK_GRAY),
                        Component.text("Uses: " + uses, NamedTextColor.GOLD)
                    ));

            case RECON_DART -> build(plugin, Material.SPECTRAL_ARROW, "RECON_DART",
                    Component.text("Recon Dart [" + uses + "]", NamedTextColor.YELLOW, TextDecoration.BOLD),
                    List.of(
                        Component.text("Right-click om een vijand te markeren", NamedTextColor.GRAY),
                        Component.text("Doelwit gaat gloeien (zichtbaar door muren)", NamedTextColor.DARK_GRAY),
                        Component.text("Uses: " + uses, NamedTextColor.GOLD)
                    ));

            case FREEZE_GRENADE -> build(plugin, Material.PACKED_ICE, "FREEZE_GRENADE",
                    Component.text("Freeze Grenade [" + uses + "]", NamedTextColor.AQUA, TextDecoration.BOLD),
                    List.of(
                        Component.text("Gooi om vijanden te vertragen", NamedTextColor.GRAY),
                        Component.text("Minder snelheid + sprong (geen schade)", NamedTextColor.DARK_GRAY),
                        Component.text("Uses: " + uses, NamedTextColor.GOLD)
                    ));

            case FLASHBANG -> build(plugin, Material.ECHO_SHARD, "FLASHBANG",
                    Component.text("Flashbang [" + uses + "]", NamedTextColor.WHITE, TextDecoration.BOLD),
                    List.of(
                        Component.text("Gooi om vijanden te verblinden", NamedTextColor.GRAY),
                        Component.text("Wie ernaar kijkt wordt blind (geen schade)", NamedTextColor.DARK_GRAY),
                        Component.text("Uses: " + uses, NamedTextColor.GOLD)
                    ));

            case AIRSTRIKE -> build(plugin, Material.FIREWORK_ROCKET, "AIRSTRIKE",
                    Component.text("Airstrike [" + uses + "]", NamedTextColor.RED, TextDecoration.BOLD),
                    List.of(
                        Component.text("Right-click om een doelwit te markeren", NamedTextColor.GRAY),
                        Component.text("Na korte vertraging slaan raketten in (instant-kill)", NamedTextColor.DARK_GRAY),
                        Component.text("Uses: " + uses, NamedTextColor.GOLD)
                    ));

            case HOLOGRAM_DECOY -> build(plugin, Material.ARMOR_STAND, "HOLOGRAM_DECOY",
                    Component.text("Hologram Decoy [" + uses + "]", NamedTextColor.LIGHT_PURPLE, TextDecoration.BOLD),
                    List.of(
                        Component.text("Right-click om een nep-jou neer te zetten", NamedTextColor.GRAY),
                        Component.text("Vijanden verspillen schoten erop", NamedTextColor.DARK_GRAY),
                        Component.text("Uses: " + uses, NamedTextColor.GOLD)
                    ));

            case MIMIC_DEVICE -> build(plugin, Material.PLAYER_HEAD, "MIMIC_DEVICE",
                    Component.text("Mimic Device [" + uses + "]", NamedTextColor.DARK_PURPLE, TextDecoration.BOLD),
                    List.of(
                        Component.text("Right-click om je als een ander team te vermommen", NamedTextColor.GRAY),
                        Component.text("Alleen visueel — je echte team blijft hetzelfde", NamedTextColor.DARK_GRAY),
                        Component.text("Uses: " + uses, NamedTextColor.GOLD)
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
