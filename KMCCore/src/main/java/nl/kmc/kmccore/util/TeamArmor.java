package nl.kmc.kmccore.util;

import nl.kmc.kmccore.KMCCore;
import nl.kmc.kmccore.models.KMCTeam;
import org.bukkit.ChatColor;
import org.bukkit.Color;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.bukkit.inventory.meta.LeatherArmorMeta;
import org.bukkit.persistence.PersistentDataType;

/**
 * Utility for equipping players with leather armor dyed in their team color.
 *
 * <p>Used by minigames where armor doesn't matter mechanically (lobby,
 * AE, parkour, etc.) so teammates can identify each other at a glance.
 *
 * <p>Three preset modes:
 * <ul>
 *   <li>{@link #applyBoots(Player)} — just boots (most parkour/lobby cases)</li>
 *   <li>{@link #applyHelmetAndBoots(Player)} — helmet + boots</li>
 *   <li>{@link #applyFullSet(Player)} — full leather set (chest, legs, helmet, boots)</li>
 * </ul>
 *
 * <p>All armor is unbreakable and tagged so the team-armor listener in
 * KMCCore can prevent players from removing or dropping it.
 *
 * <p>Convention: KMCCore stores its "team armor" tag in
 * {@code persistent_data["nl.kmc.kmccore","team_armor"] = 1}.
 */
public final class TeamArmor {

    private TeamArmor() {}

    /**
     * Apply just team-colored boots to the player. Other armor slots
     * are left untouched.
     */
    public static void applyBoots(Player p) {
        Color color = resolveColor(p);
        p.getInventory().setBoots(buildPiece(p.getServer().getPluginManager()
                .getPlugin("KMCCore") instanceof KMCCore c ? c : null,
                Material.LEATHER_BOOTS, color));
    }

    /**
     * Apply team-colored boots + helmet to the player.
     */
    public static void applyHelmetAndBoots(Player p) {
        Color color = resolveColor(p);
        KMCCore core = p.getServer().getPluginManager().getPlugin("KMCCore") instanceof KMCCore c ? c : null;
        PlayerInventory inv = p.getInventory();
        inv.setBoots(buildPiece(core,  Material.LEATHER_BOOTS,   color));
        inv.setHelmet(buildPiece(core, Material.LEATHER_HELMET,  color));
    }

    /**
     * Apply full team-colored leather armor set (helmet, chest, legs, boots).
     * Use this for The Bridge or other games where armor IS combat-relevant.
     */
    public static void applyFullSet(Player p) {
        Color color = resolveColor(p);
        KMCCore core = p.getServer().getPluginManager().getPlugin("KMCCore") instanceof KMCCore c ? c : null;
        PlayerInventory inv = p.getInventory();
        inv.setBoots(buildPiece(core,       Material.LEATHER_BOOTS,      color));
        inv.setLeggings(buildPiece(core,    Material.LEATHER_LEGGINGS,   color));
        inv.setChestplate(buildPiece(core,  Material.LEATHER_CHESTPLATE, color));
        inv.setHelmet(buildPiece(core,      Material.LEATHER_HELMET,     color));
    }

    /**
     * Strips KMC team armor (any piece tagged with team_armor) from the
     * player's armor slots. Other armor pieces are left in place.
     */
    public static void removeTeamArmor(Player p) {
        KMCCore core = p.getServer().getPluginManager().getPlugin("KMCCore") instanceof KMCCore c ? c : null;
        if (core == null) return;
        PlayerInventory inv = p.getInventory();
        if (isTeamArmor(core, inv.getBoots()))      inv.setBoots(null);
        if (isTeamArmor(core, inv.getLeggings()))   inv.setLeggings(null);
        if (isTeamArmor(core, inv.getChestplate())) inv.setChestplate(null);
        if (isTeamArmor(core, inv.getHelmet()))     inv.setHelmet(null);
    }

    /** Returns true if this item is KMC-issued team armor. */
    public static boolean isTeamArmor(KMCCore core, ItemStack item) {
        if (core == null || item == null || item.getType() == Material.AIR) return false;
        if (!(item.getItemMeta() instanceof LeatherArmorMeta meta)) return false;
        NamespacedKey key = new NamespacedKey(core, "team_armor");
        Byte b = meta.getPersistentDataContainer().get(key, PersistentDataType.BYTE);
        return b != null && b == (byte) 1;
    }

    // ----------------------------------------------------------------
    // Internals
    // ----------------------------------------------------------------

    private static Color resolveColor(Player p) {
        try {
            KMCCore core = (KMCCore) p.getServer().getPluginManager().getPlugin("KMCCore");
            if (core == null) return Color.WHITE;
            KMCTeam team = core.getApi().getTeamByPlayer(p.getUniqueId());
            if (team == null) return Color.WHITE;
            return chatColorToColor(team.getColor());
        } catch (Exception e) {
            return Color.WHITE;
        }
    }

    private static ItemStack buildPiece(KMCCore core, Material mat, Color color) {
        ItemStack item = new ItemStack(mat);
        if (item.getItemMeta() instanceof LeatherArmorMeta meta) {
            meta.setColor(color);
            meta.setUnbreakable(true);
            meta.addItemFlags(org.bukkit.inventory.ItemFlag.HIDE_UNBREAKABLE,
                              org.bukkit.inventory.ItemFlag.HIDE_ATTRIBUTES);
            if (core != null) {
                NamespacedKey key = new NamespacedKey(core, "team_armor");
                meta.getPersistentDataContainer().set(key, PersistentDataType.BYTE, (byte) 1);
            }
            item.setItemMeta(meta);
        }
        return item;
    }

    /**
     * Maps a {@link ChatColor} to a leather-armor {@link Color}.
     * Matches the standard KMC team palette (RED, BLUE, GREEN, YELLOW,
     * etc.) — falls back to white for unmapped colors.
     */
    private static Color chatColorToColor(ChatColor cc) {
        if (cc == null) return Color.WHITE;
        return switch (cc) {
            case RED          -> Color.fromRGB(0xCC0000);
            case DARK_RED     -> Color.fromRGB(0x660000);
            case BLUE         -> Color.fromRGB(0x3366FF);
            case DARK_BLUE    -> Color.fromRGB(0x000099);
            case AQUA         -> Color.fromRGB(0x33CCCC);
            case DARK_AQUA    -> Color.fromRGB(0x008B8B);
            case GREEN        -> Color.fromRGB(0x33CC33);
            case DARK_GREEN   -> Color.fromRGB(0x006600);
            case YELLOW       -> Color.fromRGB(0xFFFF33);
            case GOLD         -> Color.fromRGB(0xFF9900);
            case LIGHT_PURPLE -> Color.fromRGB(0xFF66FF);
            case DARK_PURPLE  -> Color.fromRGB(0x800080);
            case WHITE        -> Color.WHITE;
            case GRAY         -> Color.fromRGB(0xAAAAAA);
            case DARK_GRAY    -> Color.fromRGB(0x555555);
            case BLACK        -> Color.fromRGB(0x222222);
            default           -> Color.WHITE;
        };
    }
}
