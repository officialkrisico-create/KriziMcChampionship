package nl.kmc.kmccore.scoring;

import nl.kmc.kmccore.scoring.PointsLedger.Entry;
import nl.kmc.kmccore.scoring.PointsLedger.GameInfo;
import nl.kmc.kmccore.scoring.PointsLedger.Highlight;

import java.util.*;

/**
 * Works out the end-of-tournament awards from the points ledger and the highlights the games reported.
 * Pure logic (no Bukkit) so it can be unit-tested.
 *
 * <p>Personal: {@code mvp}, {@code killer}, {@code winner} (most game wins), {@code consistent},
 * {@code clutch}, {@code racer}, {@code parkour}, {@code teammate}.<br>
 * Team: {@code team_wins}, {@code team_consistent}, {@code team_comeback}.
 */
public final class AwardsEngine {

    private AwardsEngine() {}

    public record Award(String id, boolean teamAward, UUID player, String name, String teamId,
                        double value, List<Standing> runnersUp) {}

    /** A runner-up (player uuid or team id) with its value. */
    public record Standing(UUID player, String name, String teamId, double value) {}

    /** At least this many games with points before "consistent" can be awarded. */
    public static final int MIN_GAMES_CONSISTENT = 3;

    public static List<Award> compute(List<Entry> entries, List<Highlight> highlights, List<GameInfo> games) {
        List<Award> out = new ArrayList<>();
        Map<UUID, String> names = new HashMap<>();
        Map<UUID, String> teamOf = new HashMap<>();
        for (Entry e : entries) {
            if (e.player() == null) continue;
            if (e.playerName() != null) names.put(e.player(), e.playerName());
            if (e.teamId() != null) teamOf.put(e.player(), e.teamId());
        }
        for (Highlight h : highlights) {
            if (h.player() == null) continue;
            if (h.playerName() != null) names.putIfAbsent(h.player(), h.playerName());
            if (h.teamId() != null) teamOf.putIfAbsent(h.player(), h.teamId());
        }

        // ── Personal ───────────────────────────────────────────────────────────
        add(out, personal("mvp", sumPoints(entries, null), names, teamOf));

        Map<UUID, Double> kills = new HashMap<>();
        for (Entry e : entries) {
            if (e.player() != null && "KILL".equals(e.reason())) kills.merge(e.player(), 1.0, Double::sum);
        }
        add(out, personal("killer", kills, names, teamOf));

        Map<UUID, Double> gameWins = new HashMap<>();
        for (GameInfo g : games) {
            UUID best = null; int bestPts = 0;
            Map<UUID, Integer> perPlayer = new HashMap<>();
            for (Entry e : entries) {
                if (e.gameSeq() == g.seq() && e.player() != null) perPlayer.merge(e.player(), e.points(), Integer::sum);
            }
            for (var p : perPlayer.entrySet()) {
                if (p.getValue() > bestPts) { bestPts = p.getValue(); best = p.getKey(); }
            }
            if (best != null) gameWins.merge(best, 1.0, Double::sum);
        }
        add(out, personal("winner", gameWins, names, teamOf));

        // Most consistent: lowest spread of per-game points (needs enough games to mean something).
        Map<UUID, List<Integer>> perGame = new HashMap<>();
        for (GameInfo g : games) {
            Map<UUID, Integer> m = new HashMap<>();
            for (Entry e : entries) {
                if (e.gameSeq() == g.seq() && e.player() != null) m.merge(e.player(), e.points(), Integer::sum);
            }
            for (var p : m.entrySet()) perGame.computeIfAbsent(p.getKey(), k -> new ArrayList<>()).add(p.getValue());
        }
        Map<UUID, Double> spread = new HashMap<>();
        for (var p : perGame.entrySet()) {
            if (p.getValue().size() >= MIN_GAMES_CONSISTENT && average(p.getValue()) > 0)
                spread.put(p.getKey(), stdDev(p.getValue()));
        }
        add(out, personalLowest("consistent", spread, names, teamOf));

        for (String cat : List.of("clutch", "racer", "parkour")) {
            Map<UUID, Double> m = new HashMap<>();
            for (Highlight h : highlights)
                if (cat.equals(h.category()) && h.player() != null) m.merge(h.player(), (double) h.value(), Double::sum);
            add(out, personal(cat, m, names, teamOf));
        }
        Map<UUID, Double> mates = new HashMap<>();
        for (Highlight h : highlights)
            if ("teammate".equals(h.category()) && h.player() != null) mates.merge(h.player(), (double) h.value(), Double::sum);
        for (Entry e : entries)
            if (e.player() != null && "ASSIST".equals(e.reason())) mates.merge(e.player(), 1.0, Double::sum);
        add(out, personal("teammate", mates, names, teamOf));

        // ── Team ───────────────────────────────────────────────────────────────
        List<Map<String, Integer>> teamPerGame = new ArrayList<>();
        for (GameInfo g : games) {
            Map<String, Integer> m = new HashMap<>();
            for (Entry e : entries) {
                if (e.gameSeq() == g.seq() && e.teamId() != null) m.merge(e.teamId(), e.points(), Integer::sum);
            }
            teamPerGame.add(m);
        }
        Map<String, Double> teamWins = new HashMap<>();
        for (Map<String, Integer> m : teamPerGame) {
            String best = null; int bestPts = 0;
            for (var t : m.entrySet()) if (t.getValue() > bestPts) { bestPts = t.getValue(); best = t.getKey(); }
            if (best != null) teamWins.merge(best, 1.0, Double::sum);
        }
        add(out, team("team_wins", teamWins, false));

        Map<String, List<Integer>> teamSeries = new HashMap<>();
        for (Map<String, Integer> m : teamPerGame)
            for (var t : m.entrySet()) teamSeries.computeIfAbsent(t.getKey(), k -> new ArrayList<>()).add(t.getValue());
        Map<String, Double> teamSpread = new HashMap<>();
        for (var t : teamSeries.entrySet())
            if (t.getValue().size() >= MIN_GAMES_CONSISTENT && average(t.getValue()) > 0)
                teamSpread.put(t.getKey(), stdDev(t.getValue()));
        add(out, team("team_consistent", teamSpread, true));

        add(out, team("team_comeback", comebackClimb(teamPerGame), false));
        return out;
    }

    /**
     * For each team: the biggest number of places it climbed from its worst moment to the final table
     * (cumulative points after each game). Teams that never climbed are left out.
     */
    static Map<String, Double> comebackClimb(List<Map<String, Integer>> teamPerGame) {
        Map<String, Integer> cumulative = new HashMap<>();
        Map<String, Integer> worstRank = new HashMap<>();
        for (Map<String, Integer> m : teamPerGame) {
            for (var t : m.entrySet()) cumulative.merge(t.getKey(), t.getValue(), Integer::sum);
            List<String> order = new ArrayList<>(cumulative.keySet());
            order.sort((a, b) -> Integer.compare(cumulative.get(b), cumulative.get(a)));
            for (int i = 0; i < order.size(); i++) worstRank.merge(order.get(i), i + 1, Math::max);
        }
        List<String> fin = new ArrayList<>(cumulative.keySet());
        fin.sort((a, b) -> Integer.compare(cumulative.get(b), cumulative.get(a)));
        Map<String, Double> out = new HashMap<>();
        for (int i = 0; i < fin.size(); i++) {
            int climb = worstRank.getOrDefault(fin.get(i), i + 1) - (i + 1);
            if (climb >= 2) out.put(fin.get(i), (double) climb);
        }
        return out;
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private static void add(List<Award> out, Award a) { if (a != null) out.add(a); }

    private static Map<UUID, Double> sumPoints(List<Entry> entries, String reason) {
        Map<UUID, Double> m = new HashMap<>();
        for (Entry e : entries) {
            if (e.player() == null) continue;
            if (reason != null && !reason.equals(e.reason())) continue;
            m.merge(e.player(), (double) e.points(), Double::sum);
        }
        return m;
    }

    private static Award personal(String id, Map<UUID, Double> values, Map<UUID, String> names, Map<UUID, String> teamOf) {
        return pick(id, values, names, teamOf, true);
    }

    private static Award personalLowest(String id, Map<UUID, Double> values, Map<UUID, String> names, Map<UUID, String> teamOf) {
        return pick(id, values, names, teamOf, false);
    }

    private static Award pick(String id, Map<UUID, Double> values, Map<UUID, String> names,
                              Map<UUID, String> teamOf, boolean highest) {
        List<Map.Entry<UUID, Double>> l = new ArrayList<>();
        for (var e : values.entrySet()) if (highest ? e.getValue() > 0 : true) l.add(e);
        if (l.isEmpty()) return null;
        l.sort((a, b) -> highest ? Double.compare(b.getValue(), a.getValue()) : Double.compare(a.getValue(), b.getValue()));
        var w = l.get(0);
        List<Standing> runners = new ArrayList<>();
        for (int i = 1; i < Math.min(3, l.size()); i++) {
            var r = l.get(i);
            runners.add(new Standing(r.getKey(), names.get(r.getKey()), teamOf.get(r.getKey()), r.getValue()));
        }
        return new Award(id, false, w.getKey(), names.get(w.getKey()), teamOf.get(w.getKey()), w.getValue(), runners);
    }

    private static Award team(String id, Map<String, Double> values, boolean lowest) {
        List<Map.Entry<String, Double>> l = new ArrayList<>(values.entrySet());
        if (!lowest) l.removeIf(e -> e.getValue() <= 0);
        if (l.isEmpty()) return null;
        l.sort((a, b) -> lowest ? Double.compare(a.getValue(), b.getValue()) : Double.compare(b.getValue(), a.getValue()));
        var w = l.get(0);
        List<Standing> runners = new ArrayList<>();
        for (int i = 1; i < Math.min(3, l.size()); i++)
            runners.add(new Standing(null, null, l.get(i).getKey(), l.get(i).getValue()));
        return new Award(id, true, null, null, w.getKey(), w.getValue(), runners);
    }

    static double average(List<Integer> v) {
        return v.stream().mapToInt(Integer::intValue).average().orElse(0);
    }

    static double stdDev(List<Integer> v) {
        double avg = average(v), s = 0;
        for (int x : v) s += (x - avg) * (x - avg);
        return Math.sqrt(s / Math.max(1, v.size()));
    }
}
