package nl.kmc.kmccore.gui;

import nl.kmc.core.domain.KMCTeam;
import nl.kmc.kmccore.KMCCore;
import nl.kmc.kmccore.tournament.TournamentDataStore;
import org.bukkit.Material;

/** Momentum board — biggest rise/fall and current hot streaks this tournament. */
public final class MomentumGui extends Gui {

    public MomentumGui(KMCCore plugin) {
        super("&b&lKMC Momentum", 5);
        render(plugin);
    }

    private void render(KMCCore plugin) {
        TournamentDataStore store = plugin.getTournamentDataStore();

        set(4, item(Material.COMPASS, "&b&lMomentum",
                "&7Hoe teams bewegen tussen games."));

        String riseTeam = store.getBiggestRiseTeam();
        set(20, item(Material.LIME_BANNER, "&a&l▲ GROOTSTE STIJGER",
                riseTeam != null ? teamName(plugin, riseTeam) + " §a+" + store.getBiggestRise() + " posities"
                                 : "&7Nog geen data"));

        String fallTeam = store.getBiggestFallTeam();
        set(22, item(Material.RED_BANNER, "&c&l▼ GROOTSTE DALER",
                fallTeam != null ? teamName(plugin, fallTeam) + " §c-" + store.getBiggestFall() + " posities"
                                 : "&7Nog geen data"));

        // Hot streaks (teams on consecutive top-3 finishes).
        set(24, item(Material.BLAZE_POWDER, "&6&l🔥 HOT STREAKS",
                hotStreakLines(plugin, store)));

        fillEmpty();
    }

    private String[] hotStreakLines(KMCCore plugin, TournamentDataStore store) {
        java.util.List<String> lines = new java.util.ArrayList<>();
        for (KMCTeam t : plugin.getTeamManager().getTeamsSortedByPoints()) {
            int streak = store.getHotStreak(t.getId());
            if (streak >= 2) lines.add(t.getColor() + t.getDisplayName() + " §7— §6" + streak + " op rij top-3");
        }
        if (lines.isEmpty()) lines.add("&7Nog geen streaks");
        return lines.toArray(new String[0]);
    }

    private String teamName(KMCCore plugin, String teamId) {
        var t = plugin.getTeamManager().getTeam(teamId);
        return t != null ? t.getColor() + t.getDisplayName() : "§f" + teamId;
    }
}
