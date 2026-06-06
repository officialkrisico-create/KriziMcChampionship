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

    // Podium slots: #1 top-centre, #2 left, #3 right (classic podium feel).
    private static final int[] PODIUM = {13, 20, 24};

    private void render(KMCCore plugin, UUID viewer) {
        List<KMCTeam> teams = plugin.getTeamManager().getTeamsSortedByPoints();

        set(4, item(Material.GOLD_BLOCK, "&6&lTeam Klassement",
                "&7Ronde &e" + plugin.getTournamentManager().getCurrentRound()
                        + " &7• Multiplier &e×" + plugin.getTournamentManager().getMultiplier()));

        // ── Top 3 on a podium ───────────────────────────────────────────────
        for (int i = 0; i < Math.min(3, teams.size()); i++) {
            set(PODIUM[i], teamItem(teams.get(i), i, viewer));
        }

        // ── #4 and below in a centred 7-wide list (rows 3-4) ────────────────
        int idx = 0;
        for (int i = 3; i < teams.size(); i++) {
            int slot = (3 + idx / 7) * 9 + (1 + idx % 7);
            if (slot >= 45) break;
            set(slot, teamItem(teams.get(i), i, viewer));
            idx++;
        }

        // ── Top players, centred along the bottom row ───────────────────────
        List<PlayerData> top = plugin.getPlayerDataManager().getLeaderboard();
        set(45, item(Material.PLAYER_HEAD, "&e&lTop Spelers", "&7De beste individuele scores"));
        int[] playerSlots = {47, 48, 49, 50, 51};
        for (int i = 0; i < Math.min(playerSlots.length, top.size()); i++) {
            PlayerData pd = top.get(i);
            String medal = switch (i) { case 0 -> "&6#1"; case 1 -> "&7#2"; case 2 -> "&c#3"; default -> "&7#" + (i + 1); };
            set(playerSlots[i], head(Bukkit.getOfflinePlayer(pd.getUuid()),
                    medal + " &f" + pd.getName(),
                    "&7Punten: &e" + pd.getPoints(),
                    "&7Kills: &f" + pd.getKills()));
        }

        fillEmpty();
    }

    private org.bukkit.inventory.ItemStack teamItem(KMCTeam t, int rank, UUID viewer) {
        String medal = switch (rank) {
            case 0 -> "&6🥇"; case 1 -> "&7🥈"; case 2 -> "&c🥉"; default -> "&7#" + (rank + 1);
        };
        boolean mine = t.hasMember(viewer);
        return item(concrete(t.getColor()),
                medal + " " + t.getColor() + "&l" + t.getDisplayName() + (mine ? " &a(jouw team)" : ""),
                "&7Punten: &e" + t.getPoints(),
                "&7Leden: &f" + t.getMembers().size());
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
