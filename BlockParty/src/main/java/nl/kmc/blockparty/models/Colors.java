package nl.kmc.blockparty.models;

import org.bukkit.Material;

import java.util.List;
import java.util.Map;

/**
 * The 16 concrete colours used by Block Party, plus display helpers.
 * Only concrete is ever used — no wool, terracotta, or glass.
 */
public final class Colors {

    private Colors() {}

    /** All 16 concrete colours, in vanilla dye order. */
    public static final List<Material> ALL = List.of(
            Material.WHITE_CONCRETE,      Material.ORANGE_CONCRETE,
            Material.MAGENTA_CONCRETE,    Material.LIGHT_BLUE_CONCRETE,
            Material.YELLOW_CONCRETE,     Material.LIME_CONCRETE,
            Material.PINK_CONCRETE,       Material.GRAY_CONCRETE,
            Material.LIGHT_GRAY_CONCRETE, Material.CYAN_CONCRETE,
            Material.PURPLE_CONCRETE,     Material.BLUE_CONCRETE,
            Material.BROWN_CONCRETE,      Material.GREEN_CONCRETE,
            Material.RED_CONCRETE,        Material.BLACK_CONCRETE);

    private static final Map<Material, String> NAMES = Map.ofEntries(
            Map.entry(Material.WHITE_CONCRETE,      "§fWIT"),
            Map.entry(Material.ORANGE_CONCRETE,     "§6ORANJE"),
            Map.entry(Material.MAGENTA_CONCRETE,    "§dMAGENTA"),
            Map.entry(Material.LIGHT_BLUE_CONCRETE, "§bLICHTBLAUW"),
            Map.entry(Material.YELLOW_CONCRETE,     "§eGEEL"),
            Map.entry(Material.LIME_CONCRETE,       "§aLIMOEN"),
            Map.entry(Material.PINK_CONCRETE,       "§dROZE"),
            Map.entry(Material.GRAY_CONCRETE,       "§8GRIJS"),
            Map.entry(Material.LIGHT_GRAY_CONCRETE, "§7LICHTGRIJS"),
            Map.entry(Material.CYAN_CONCRETE,       "§3CYAAN"),
            Map.entry(Material.PURPLE_CONCRETE,     "§5PAARS"),
            Map.entry(Material.BLUE_CONCRETE,       "§9BLAUW"),
            Map.entry(Material.BROWN_CONCRETE,      "§6BRUIN"),
            Map.entry(Material.GREEN_CONCRETE,      "§2GROEN"),
            Map.entry(Material.RED_CONCRETE,        "§cROOD"),
            Map.entry(Material.BLACK_CONCRETE,      "§0ZWART"));

    /** Coloured display label, e.g. {@code §cROOD}. */
    public static String label(Material concrete) {
        return NAMES.getOrDefault(concrete, "§7" + concrete.name());
    }

    /** Display label with the chat-colour prefix stripped (plain text). */
    public static String plain(Material concrete) {
        String l = label(concrete);
        return l.length() > 2 ? l.substring(2) : l;
    }

    public static boolean isConcrete(Material m) {
        return m != null && m.name().endsWith("_CONCRETE");
    }
}
