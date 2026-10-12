package nl.kmc.kmccore.presentation;

import nl.kmc.core.domain.KMCTeam;
import nl.kmc.kmccore.KMCCore;
import org.bukkit.Bukkit;
import org.bukkit.Sound;
import org.bukkit.entity.Player;

import java.util.*;

/**
 * The tournament table after a game, revealed from last to first: every team shows how many places it
 * climbed or fell and how many points it earned, with a rising note per line, and the biggest riser and
 * faller get a title at the end. Falls back to a plain table when there is no "before" snapshot.
 */
public final class LeaderboardAnimation {

    private LeaderboardAnimation() {}

    /** Team ranks (1 = best) of the current standings. */
    public static Map<String, Integer> currentRanks(KMCCore plugin) {
        Map<String, Integer> out = new LinkedHashMap<>();
        int r = 1;
        for (KMCTeam t : plugin.getTeamManager().getTeamsSortedByPoints()) out.put(t.getId(), r++);
        return out;
    }

    public static Map<String, Integer> currentPoints(KMCCore plugin) {
        Map<String, Integer> out = new HashMap<>();
        for (KMCTeam t : plugin.getTeamManager().getTeamsSortedByPoints()) out.put(t.getId(), t.getPoints());
        return out;
    }

    /**
     * @param ranksBefore  team id → rank before the game (empty = unknown)
     * @param pointsBefore team id → points before the game
     * @param done         runs after the last line and the riser/faller titles
     */
    public static void run(KMCCore plugin, Map<String, Integer> ranksBefore, Map<String, Integer> pointsBefore,
                           Runnable done) {
        List<KMCTeam> table = new ArrayList<>(plugin.getTeamManager().getTeamsSortedByPoints());
        if (table.isEmpty()) { if (done != null) done.run(); return; }
        var lm = plugin.getLanguageManager();
        int n = table.size();

        // Header.
        for (Player p : Bukkit.getOnlinePlayers()) {
            p.sendMessage("§6§m                                        ");
            p.sendMessage(lm.tr(p, "leaderboard.header"));
        }

        // Reveal from last place upwards.
        long tick = 10;
        String riser = null, faller = null;
        int bestClimb = 0, worstFall = 0;
        for (int i = n - 1; i >= 0; i--) {
            final KMCTeam t = table.get(i);
            final int rank = i + 1;
            Integer before = ranksBefore.get(t.getId());
            int delta = before == null ? 0 : before - rank;          // positive = climbed
            int gained = t.getPoints() - pointsBefore.getOrDefault(t.getId(), t.getPoints());
            if (delta > bestClimb) { bestClimb = delta; riser = t.getId(); }
            if (delta < worstFall) { worstFall = delta; faller = t.getId(); }

            final int fDelta = delta, fGained = gained;
            final float pitch = 0.6f + 0.1f * (n - i);
            Bukkit.getScheduler().runTaskLater(plugin, () -> {
                String medal = rank == 1 ? "§6§l#1" : rank == 2 ? "§7§l#2" : rank == 3 ? "§c§l#3" : "§7#" + rank;
                String arrow = fDelta > 0 ? "§a▲" + fDelta : fDelta < 0 ? "§c▼" + (-fDelta) : "§8═";
                for (Player p : Bukkit.getOnlinePlayers()) {
                    p.sendMessage("  " + medal + " " + arrow + " " + t.getColor() + "◆ " + t.getDisplayName()
                            + " §8— §e" + t.getPoints() + (fGained > 0 ? " §a(+" + fGained + ")" : ""));
                    p.playSound(p.getLocation(), Sound.BLOCK_NOTE_BLOCK_PLING, 0.6f, Math.min(2f, pitch));
                    if (fDelta >= 2) p.playSound(p.getLocation(), Sound.ENTITY_EXPERIENCE_ORB_PICKUP, 0.5f, 1.4f);
                    if (fDelta <= -2) p.playSound(p.getLocation(), Sound.BLOCK_NOTE_BLOCK_BASS, 0.6f, 0.6f);
                }
            }, tick);
            tick += 12;
        }

        // Biggest movers.
        final String fRiser = riser, fFaller = faller;
        final int fClimb = bestClimb, fFall = -worstFall;
        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            KMCTeam r = fRiser != null ? plugin.getTeamManager().getTeam(fRiser) : null;
            KMCTeam f = fFaller != null ? plugin.getTeamManager().getTeam(fFaller) : null;
            for (Player p : Bukkit.getOnlinePlayers()) {
                if (r != null && fClimb >= 1)
                    p.sendMessage(lm.tr(p, "leaderboard.riser", r.getColor() + r.getDisplayName() + "§7", fClimb));
                if (f != null && fFall >= 1)
                    p.sendMessage(lm.tr(p, "leaderboard.faller", f.getColor() + f.getDisplayName() + "§7", fFall));
                p.sendMessage("§6§m                                        ");
                if (r != null && fClimb >= 2)
                    p.sendTitle(lm.tr(p, "leaderboard.riser-title"), r.getColor() + r.getDisplayName()
                            + " §a▲" + fClimb, 5, 45, 10);
            }
            if (done != null) done.run();
        }, tick + 6);
    }
}
