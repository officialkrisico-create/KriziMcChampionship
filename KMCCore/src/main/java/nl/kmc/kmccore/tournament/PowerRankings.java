package nl.kmc.kmccore.tournament;

import nl.kmc.core.domain.KMCTeam;
import nl.kmc.kmccore.KMCCore;
import nl.kmc.kmccore.util.MessageUtil;
import org.bukkit.Bukkit;
import org.bukkit.Sound;
import org.bukkit.entity.Player;

import java.util.Comparator;
import java.util.List;

/** Builds + reveals the pre-tournament "KMC POWER RANKINGS" (teams by ELO rating). */
public final class PowerRankings {

    private PowerRankings() {}

    /** Teams (all current teams) sorted by power rating, highest first. */
    public static List<KMCTeam> ranked(KMCCore plugin) {
        var store = plugin.getTournamentDataStore();
        List<KMCTeam> teams = new java.util.ArrayList<>(plugin.getTeamManager().getAllTeams());
        teams.sort(Comparator.comparingInt((KMCTeam t) -> store.getElo(t.getId())).reversed());
        return teams;
    }

    /** Cinematic-ish reveal broadcast at tournament start. */
    public static void reveal(KMCCore plugin) {
        if (!plugin.getConfig().getBoolean("power-rankings.enabled", true)) return;
        var store = plugin.getTournamentDataStore();
        List<KMCTeam> teams = ranked(plugin);
        if (teams.isEmpty()) return;

        Bukkit.broadcastMessage(MessageUtil.color("&8&m                                        "));
        Bukkit.broadcastMessage(MessageUtil.color("        &6&l⚔ KMC POWER RANKINGS ⚔"));
        Bukkit.broadcastMessage(MessageUtil.color("        &7Op basis van historische prestaties"));
        Bukkit.broadcastMessage(MessageUtil.color("&8&m                                        "));
        for (int i = 0; i < teams.size(); i++) {
            KMCTeam t = teams.get(i);
            String medal = switch (i) { case 0 -> "&6#1"; case 1 -> "&7#2"; case 2 -> "&c#3"; default -> "&8#" + (i + 1); };
            Bukkit.broadcastMessage(MessageUtil.color("  " + medal + " " + t.getColor() + t.getDisplayName()
                    + " &8— &7Rating &e" + store.ratingOf(t.getId())));
        }
        Bukkit.broadcastMessage(MessageUtil.color("&8&m                                        "));
        for (Player p : Bukkit.getOnlinePlayers())
            p.playSound(p.getLocation(), Sound.UI_TOAST_CHALLENGE_COMPLETE, 1f, 1.1f);
    }
}
