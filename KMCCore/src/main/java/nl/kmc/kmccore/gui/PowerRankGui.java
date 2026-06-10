package nl.kmc.kmccore.gui;

import nl.kmc.core.domain.KMCTeam;
import nl.kmc.kmccore.KMCCore;
import nl.kmc.kmccore.tournament.PowerRankings;
import nl.kmc.kmccore.tournament.TournamentDataStore;
import org.bukkit.ChatColor;
import org.bukkit.Material;

import java.util.List;

/** Power Rankings board — teams ranked by historical ELO rating. */
public final class PowerRankGui extends Gui {

    public PowerRankGui(KMCCore plugin) {
        super("&6&lKMC Power Rankings", 6);
        render(plugin);
    }

    private void render(KMCCore plugin) {
        TournamentDataStore store = plugin.getTournamentDataStore();
        set(4, item(Material.NETHERITE_INGOT, "&6&l⚔ Power Rankings",
                "&7Team-rating op basis van historische prestaties.",
                "&7Bijgewerkt na elke game (ELO)."));

        List<KMCTeam> teams = PowerRankings.ranked(plugin);
        int idx = 0;
        for (KMCTeam t : teams) {
            int slot = (1 + idx / 7) * 9 + (1 + idx % 7);
            if (slot >= 45) break;
            String medal = switch (idx) { case 0 -> "&6#1"; case 1 -> "&7#2"; case 2 -> "&c#3"; default -> "&8#" + (idx + 1); };
            set(slot, item(concrete(t.getColor()),
                    medal + " " + t.getColor() + "&l" + t.getDisplayName(),
                    "&7Rating: &e" + store.ratingOf(t.getId()),
                    "&7ELO: &f" + store.getElo(t.getId()),
                    "&7Punten nu: &f" + t.getPoints()));
            idx++;
        }
        fillEmpty();
    }

    private static Material concrete(ChatColor c) {
        return switch (c) {
            case RED, DARK_RED     -> Material.RED_CONCRETE;
            case GOLD              -> Material.ORANGE_CONCRETE;
            case YELLOW            -> Material.YELLOW_CONCRETE;
            case GREEN, DARK_GREEN -> Material.LIME_CONCRETE;
            case AQUA, DARK_AQUA   -> Material.CYAN_CONCRETE;
            case BLUE, DARK_BLUE   -> Material.BLUE_CONCRETE;
            case DARK_PURPLE       -> Material.PURPLE_CONCRETE;
            case LIGHT_PURPLE      -> Material.MAGENTA_CONCRETE;
            case BLACK, DARK_GRAY  -> Material.BLACK_CONCRETE;
            case GRAY              -> Material.LIGHT_GRAY_CONCRETE;
            default                -> Material.WHITE_CONCRETE;
        };
    }
}
