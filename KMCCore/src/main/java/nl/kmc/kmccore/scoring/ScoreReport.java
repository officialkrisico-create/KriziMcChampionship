package nl.kmc.kmccore.scoring;

import nl.kmc.core.domain.KMCTeam;
import nl.kmc.kmccore.KMCCore;
import nl.kmc.kmccore.scoring.PointsLedger.Entry;
import org.bukkit.Bukkit;
import org.bukkit.Sound;
import org.bukkit.entity.Player;

import java.util.*;

/**
 * The after-game score report: where did the points of THIS game come from?
 *
 * <ol>
 *   <li>top players of the game (individual ranking),</li>
 *   <li>per team: what it earned and from what (placement, kills, bonuses, ...),</li>
 *   <li>privately to every player: "your game" — your points by reason, your rank and your team's rank.</li>
 * </ol>
 * All text is per-viewer language ({@code score.*} keys).
 */
public final class ScoreReport {

    private ScoreReport() {}

    /** How many ticks the three parts are spaced (the overall standings follow after these). */
    public static final long PLAYERS_AT = 20L, TEAMS_AT = 120L, PERSONAL_AT = 240L;

    /** True when the ledger holds anything for this game. */
    public static boolean hasData(KMCCore plugin, int gameSeq) {
        return gameSeq > 0 && !plugin.getPointsLedger().forGame(gameSeq).isEmpty();
    }

    public static void schedule(KMCCore plugin, int gameSeq, String gameName) {
        Bukkit.getScheduler().runTaskLater(plugin, () -> topPlayers(plugin, gameSeq, gameName), PLAYERS_AT);
        Bukkit.getScheduler().runTaskLater(plugin, () -> teamBreakdown(plugin, gameSeq, gameName), TEAMS_AT);
        Bukkit.getScheduler().runTaskLater(plugin, () -> personal(plugin, gameSeq, gameName), PERSONAL_AT);
    }

    // ── 1. top players of this game ───────────────────────────────────────────

    static void topPlayers(KMCCore plugin, int seq, String gameName) {
        var byPlayer = PointsLedger.byPlayer(plugin.getPointsLedger().forGame(seq));
        if (byPlayer.isEmpty()) return;
        var lm = plugin.getLanguageManager();
        for (Player v : Bukkit.getOnlinePlayers()) {
            v.sendMessage("§6§m                                        ");
            v.sendMessage(lm.tr(v, "score.players.header", gameName));
            int i = 0;
            for (var e : byPlayer.entrySet()) {
                if (i >= 5) break;
                v.sendMessage("  " + medal(i + 1) + " §f" + nameOf(plugin, e.getKey())
                        + teamTag(plugin, e.getKey()) + " §8— §e+" + e.getValue());
                i++;
            }
            v.sendMessage("§6§m                                        ");
        }
    }

    // ── 2. per team ───────────────────────────────────────────────────────────

    static void teamBreakdown(KMCCore plugin, int seq, String gameName) {
        List<Entry> entries = plugin.getPointsLedger().forGame(seq);
        var byTeam = PointsLedger.byTeam(entries);
        if (byTeam.isEmpty()) return;
        var lm = plugin.getLanguageManager();
        double mul = 1.0;
        for (Entry e : entries) if (!"KILL".equals(e.reason()) && !"ASSIST".equals(e.reason())) { mul = e.multiplier(); break; }

        for (Player v : Bukkit.getOnlinePlayers()) {
            v.sendMessage("§6§m                                        ");
            v.sendMessage(lm.tr(v, "score.teams.header", gameName));
            if (Math.abs(mul - 1.0) > 0.001) v.sendMessage(lm.tr(v, "score.teams.multiplier", fmt(mul)));
            int rank = 1;
            for (var te : byTeam.entrySet()) {
                KMCTeam t = plugin.getTeamManager().getTeam(te.getKey());
                String label = t != null ? t.getColor() + t.getDisplayName() : te.getKey();
                // The biggest three reasons of this team, e.g. "Plaats 120 · Kills 80 · Bonus 25".
                var reasons = PointsLedger.byReason(forTeam(entries, te.getKey()));
                StringBuilder sb = new StringBuilder();
                int shown = 0;
                for (var r : reasons.entrySet()) {
                    if (shown++ >= 3) break;
                    if (sb.length() > 0) sb.append(" §8· ");
                    sb.append("§7").append(reasonLabel(lm, v, r.getKey())).append(" §f").append(r.getValue());
                }
                v.sendMessage("  " + medal(rank) + " " + label + " §e+" + te.getValue());
                if (sb.length() > 0) v.sendMessage("      " + sb);
                rank++;
                if (rank > 8) break;
            }
            v.sendMessage("§6§m                                        ");
        }
    }

    // ── 3. "your game" ────────────────────────────────────────────────────────

    static void personal(KMCCore plugin, int seq, String gameName) {
        List<Entry> entries = plugin.getPointsLedger().forGame(seq);
        var byPlayer = PointsLedger.byPlayer(entries);
        var byTeam = PointsLedger.byTeam(entries);
        var lm = plugin.getLanguageManager();
        List<UUID> playerOrder = new ArrayList<>(byPlayer.keySet());
        List<String> teamOrder = new ArrayList<>(byTeam.keySet());

        for (Player p : Bukkit.getOnlinePlayers()) {
            UUID id = p.getUniqueId();
            List<Entry> mine = new ArrayList<>();
            for (Entry e : entries) if (id.equals(e.player())) mine.add(e);
            if (mine.isEmpty()) continue;

            int total = 0;
            for (Entry e : mine) total += e.points();
            p.sendMessage("");
            p.sendMessage(lm.tr(p, "score.you.header", gameName));
            for (var r : PointsLedger.byReason(mine).entrySet()) {
                p.sendMessage("  §7" + reasonLabel(lm, p, r.getKey()) + " §8» §a+" + r.getValue());
            }
            p.sendMessage(lm.tr(p, "score.you.total", total));
            int myRank = playerOrder.indexOf(id) + 1;
            KMCTeam team = plugin.getTeamManager().getTeamByPlayer(id);
            int teamRank = team != null ? teamOrder.indexOf(team.getId()) + 1 : 0;
            // The totals are everybody who took part, not just the ones who scored in this game.
            int playersTotal = Math.max(playerOrder.size(), playingCount(plugin));
            int teamsTotal = Math.max(teamOrder.size(), teamsInPlay(plugin));
            p.sendMessage(lm.tr(p, "score.you.rank", myRank, playersTotal,
                    teamRank > 0 ? teamRank : "-", teamsTotal));
            int comeback = 0;
            for (Entry e : mine) comeback += e.comeback();
            if (comeback > 0) p.sendMessage(lm.tr(p, "score.you.comeback", comeback));
            p.playSound(p.getLocation(), Sound.ENTITY_EXPERIENCE_ORB_PICKUP, 0.6f, 1.2f);
        }
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    /** Online players that belong to a team (the field of a tournament game). */
    private static int playingCount(KMCCore plugin) {
        int n = 0;
        for (Player p : Bukkit.getOnlinePlayers())
            if (plugin.getTeamManager().getTeamByPlayer(p.getUniqueId()) != null) n++;
        return n;
    }

    /** Teams that have at least one player online. */
    private static int teamsInPlay(KMCCore plugin) {
        java.util.Set<String> ids = new java.util.HashSet<>();
        for (Player p : Bukkit.getOnlinePlayers()) {
            KMCTeam t = plugin.getTeamManager().getTeamByPlayer(p.getUniqueId());
            if (t != null) ids.add(t.getId());
        }
        return ids.size();
    }

    /** Entries that count for a team: its members' awards plus team-only awards. */
    private static List<Entry> forTeam(List<Entry> all, String teamId) {
        List<Entry> out = new ArrayList<>();
        for (Entry e : all) if (teamId.equals(e.teamId())) out.add(e);
        return out;
    }

    public static String reasonLabel(nl.kmc.kmccore.lang.LanguageManager lm, Player viewer, String reason) {
        String key = "score.reason." + reason;
        String t = lm.tr(viewer, key);
        return t.equals(key) ? reason.toLowerCase().replace('_', ' ') : t;
    }

    private static String medal(int rank) {
        return rank == 1 ? "§6🥇" : rank == 2 ? "§7🥈" : rank == 3 ? "§c🥉" : "§7#" + rank;
    }

    private static String nameOf(KMCCore plugin, UUID id) {
        var pd = plugin.getPlayerDataManager().get(id);
        if (pd != null && pd.getName() != null) return pd.getName();
        var op = Bukkit.getOfflinePlayer(id);
        return op.getName() != null ? op.getName() : id.toString().substring(0, 8);
    }

    private static String teamTag(KMCCore plugin, UUID id) {
        KMCTeam t = plugin.getTeamManager().getTeamByPlayer(id);
        return t != null ? " §8(" + t.getColor() + t.getDisplayName() + "§8)" : "";
    }

    private static String fmt(double d) {
        return d == Math.rint(d) ? String.valueOf((int) d) : String.format(Locale.ROOT, "%.2f", d);
    }
}
