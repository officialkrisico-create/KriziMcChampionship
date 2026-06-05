package nl.kmc.kmccore.gui;

import nl.kmc.core.domain.KMCTeam;
import nl.kmc.kmccore.KMCCore;
import nl.kmc.kmccore.models.PlayerData;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Material;

import java.util.List;
import java.util.UUID;

/**
 * Live Standings GUI — team leaderboard with a podium feel, plus the top
 * players and the viewer's own rank.
 */
public final class StandingsGui extends Gui {

    public StandingsGui(KMCCore plugin, UUID viewer) {
        super("&1&lKMC Standings", 6);
        render(plugin, viewer);
    }

    private void render(KMCCore plugin, UUID viewer) {
        List<KMCTeam> teams = plugin.getTeamManager().getTeamsSortedByPoints();

        set(4, item(Material.GOLD_BLOCK, "&6&lTeam Klassement",
                "&7Ronde &e" + plugin.getTournamentManager().getCurrentRound()
                        + " &7• Multiplier &e×" + plugin.getTournamentManager().getMultiplier()));

        int slot = 9;
        for (int i = 0; i < teams.size() && slot < 45; i++) {
            KMCTeam t = teams.get(i);
            String medal = switch (i) { case 0 -> "&6🥇"; case 1 -> "&7🥈"; case 2 -> "&c🥉"; default -> "&7#" + (i + 1); };
            boolean mine = t.hasMember(viewer);
            set(slot, item(concrete(t.getColor()),
                    medal + " " + t.getColor() + "&l" + t.getDisplayName() + (mine ? " &a(jouw team)" : ""),
                    "&7Punten: &e" + t.getPoints(),
                    "&7Leden: &f" + t.getMembers().size()));
            slot++;
            if (slot % 9 == 8) slot += 1;
        }

        // Top players row at the bottom.
        List<PlayerData> top = plugin.getPlayerDataManager().getLeaderboard();
        int pslot = 47;
        set(45, item(Material.PLAYER_HEAD, "&e&lTop Spelers", "&7De beste individuele scores"));
        for (int i = 0; i < Math.min(3, top.size()); i++) {
            PlayerData pd = top.get(i);
            String medal = switch (i) { case 0 -> "&6#1"; case 1 -> "&7#2"; default -> "&c#3"; };
            set(pslot++, head(Bukkit.getOfflinePlayer(pd.getUuid()),
                    medal + " &f" + pd.getName(),
                    "&7Punten: &e" + pd.getPoints(),
                    "&7Kills: &f" + pd.getKills()));
        }

        fillEmpty();
    }

    /** Maps a team ChatColor to a coloured concrete block for the icon. */
    private static Material concrete(ChatColor c) {
        return switch (c) {
            case RED, DARK_RED       -> Material.RED_CONCRETE;
            case GOLD                -> Material.ORANGE_CONCRETE;
            case YELLOW              -> Material.YELLOW_CONCRETE;
            case GREEN, DARK_GREEN   -> Material.LIME_CONCRETE;
            case AQUA, DARK_AQUA     -> Material.CYAN_CONCRETE;
            case BLUE, DARK_BLUE     -> Material.BLUE_CONCRETE;
            case DARK_PURPLE         -> Material.PURPLE_CONCRETE;
            case LIGHT_PURPLE        -> Material.MAGENTA_CONCRETE;
            case BLACK, DARK_GRAY    -> Material.BLACK_CONCRETE;
            case GRAY                -> Material.LIGHT_GRAY_CONCRETE;
            default                  -> Material.WHITE_CONCRETE;
        };
    }
}
