package nl.kmc.kmccore.platform;

import nl.kmc.core.domain.KMCTeam;
import nl.kmc.kmccore.KMCCore;
import org.bukkit.ChatColor;
import org.bukkit.DyeColor;
import org.bukkit.Material;
import org.bukkit.block.banner.Pattern;
import org.bukkit.block.banner.PatternType;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.BannerMeta;

import java.util.List;
import java.util.Locale;

/**
 * Every team's emblem as a banner: base colour from the team colour, one pattern that is derived from the
 * team id (so it is stable) and can be overridden per team in {@code emblems.<teamId>.{base,pattern,color}}.
 */
public final class TeamEmblems {

    /** Registry keys (looked up lazily: the registry only exists on a running server). */
    private static final List<String> PATTERN_KEYS = List.of(
            "cross", "stripe_downright", "circle", "rhombus", "triangle_bottom", "half_horizontal", "border",
            "flower", "skull", "globe", "straight_cross", "gradient");

    private final KMCCore plugin;

    public TeamEmblems(KMCCore plugin) { this.plugin = plugin; }

    /** Nearest banner dye for a chat colour. */
    public static DyeColor dyeFor(ChatColor c) {
        if (c == null) return DyeColor.WHITE;
        return switch (c) {
            case RED -> DyeColor.RED;
            case DARK_RED -> DyeColor.RED;
            case GOLD -> DyeColor.ORANGE;
            case YELLOW -> DyeColor.YELLOW;
            case GREEN -> DyeColor.LIME;
            case DARK_GREEN -> DyeColor.GREEN;
            case AQUA -> DyeColor.LIGHT_BLUE;
            case DARK_AQUA -> DyeColor.CYAN;
            case BLUE -> DyeColor.LIGHT_BLUE;
            case DARK_BLUE -> DyeColor.BLUE;
            case LIGHT_PURPLE -> DyeColor.PINK;
            case DARK_PURPLE -> DyeColor.PURPLE;
            case GRAY -> DyeColor.LIGHT_GRAY;
            case DARK_GRAY -> DyeColor.GRAY;
            case BLACK -> DyeColor.BLACK;
            default -> DyeColor.WHITE;
        };
    }

    /** Stable pattern key for a team id (e.g. "cross"). */
    public static String patternKeyFor(String teamId) {
        int h = teamId == null ? 0 : teamId.toLowerCase(Locale.ROOT).hashCode();
        return PATTERN_KEYS.get(Math.floorMod(h, PATTERN_KEYS.size()));
    }

    private static PatternType resolve(String key) {
        PatternType t = org.bukkit.Registry.BANNER_PATTERN.get(org.bukkit.NamespacedKey.minecraft(key));
        return t != null ? t : PatternType.BORDER;
    }

    /** White or black, whichever contrasts with the base colour. */
    private static DyeColor contrast(DyeColor base) {
        return switch (base) {
            case WHITE, YELLOW, LIGHT_GRAY, LIME, LIGHT_BLUE, PINK, ORANGE -> DyeColor.BLACK;
            default -> DyeColor.WHITE;
        };
    }

    public ItemStack banner(KMCTeam team) {
        var cfg = plugin.getConfig().getConfigurationSection("emblems." + team.getId());
        DyeColor base = dyeFor(team.getColor());
        PatternType pattern = resolve(patternKeyFor(team.getId()));
        DyeColor pcol = contrast(base);
        if (cfg != null) {
            try { if (cfg.isSet("base")) base = DyeColor.valueOf(cfg.getString("base").toUpperCase(Locale.ROOT)); }
            catch (IllegalArgumentException ignored) { }
            try { if (cfg.isSet("color")) pcol = DyeColor.valueOf(cfg.getString("color").toUpperCase(Locale.ROOT)); }
            catch (IllegalArgumentException ignored) { }
            if (cfg.isSet("pattern")) {
                var found = org.bukkit.Registry.BANNER_PATTERN.get(
                        org.bukkit.NamespacedKey.minecraft(cfg.getString("pattern").toLowerCase(Locale.ROOT)));
                if (found != null) pattern = found;
            }
        }

        ItemStack item = new ItemStack(Material.valueOf(base.name() + "_BANNER"));
        BannerMeta meta = (BannerMeta) item.getItemMeta();
        meta.addPattern(new Pattern(pcol, pattern));
        meta.addPattern(new Pattern(pcol, PatternType.BORDER));
        meta.setDisplayName(team.getColor() + "§l" + team.getDisplayName());
        meta.addItemFlags(org.bukkit.inventory.ItemFlag.HIDE_ADDITIONAL_TOOLTIP);
        item.setItemMeta(meta);
        return item;
    }
}
