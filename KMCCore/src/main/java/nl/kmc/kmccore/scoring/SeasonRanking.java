package nl.kmc.kmccore.scoring;

import nl.kmc.kmccore.scoring.EditionArchive.Edition;
import nl.kmc.kmccore.scoring.EditionArchive.PlayerRow;
import nl.kmc.kmccore.scoring.EditionArchive.TeamRow;

import java.util.*;

/**
 * Season / all-time ranking over the edition archives. Pure aggregation (unit-tested): the caller chooses
 * which editions count — all of them for the all-time ranking, or the last N for a season.
 */
public final class SeasonRanking {

    private SeasonRanking() {}

    public record PlayerTotal(UUID uuid, String name, int editions, int points, int kills, int wins,
                              int editionWins, int bestRank, int awards) {}

    public record TeamTotal(String key, String name, int editions, int points, int titles, int bestRank) {}

    /** All players over {@code editions}, best first (points, then edition wins). */
    public static List<PlayerTotal> players(List<Edition> editions) {
        class Acc { String name; int ed, pts, kills, wins, titles, best = Integer.MAX_VALUE, awards; }
        Map<UUID, Acc> acc = new LinkedHashMap<>();
        for (Edition e : editions) {
            Map<String, Integer> awardsByName = new HashMap<>();
            for (var a : e.awards()) if (!a.team() && a.name() != null) awardsByName.merge(a.name(), 1, Integer::sum);
            for (PlayerRow p : e.players()) {
                Acc a = acc.computeIfAbsent(p.uuid(), k -> new Acc());
                a.name = p.name();
                a.ed++;
                a.pts += p.points();
                a.kills += p.kills();
                a.wins += p.wins();
                a.best = Math.min(a.best, p.rank());
                if (e.championTeamId() != null && e.championTeamId().equals(p.teamId())) a.titles++;
                a.awards += awardsByName.getOrDefault(p.name(), 0);
            }
        }
        List<PlayerTotal> out = new ArrayList<>();
        for (var en : acc.entrySet()) {
            Acc a = en.getValue();
            out.add(new PlayerTotal(en.getKey(), a.name, a.ed, a.pts, a.kills, a.wins, a.titles,
                    a.best == Integer.MAX_VALUE ? 0 : a.best, a.awards));
        }
        out.sort(Comparator.comparingInt(PlayerTotal::points).reversed()
                .thenComparing(Comparator.comparingInt(PlayerTotal::editionWins).reversed()));
        return out;
    }

    /** Teams are matched by display name (ids and colours may change between editions). */
    public static List<TeamTotal> teams(List<Edition> editions) {
        class Acc { String name; int ed, pts, titles, best = Integer.MAX_VALUE; }
        Map<String, Acc> acc = new LinkedHashMap<>();
        for (Edition e : editions) {
            for (TeamRow t : e.teams()) {
                String key = t.name() == null ? t.id() : t.name().toLowerCase(Locale.ROOT);
                Acc a = acc.computeIfAbsent(key, k -> new Acc());
                a.name = t.name();
                a.ed++;
                a.pts += t.points();
                a.best = Math.min(a.best, t.rank());
                if (t.rank() == 1) a.titles++;
            }
        }
        List<TeamTotal> out = new ArrayList<>();
        for (var en : acc.entrySet()) {
            Acc a = en.getValue();
            out.add(new TeamTotal(en.getKey(), a.name, a.ed, a.pts, a.titles, a.best == Integer.MAX_VALUE ? 0 : a.best));
        }
        out.sort(Comparator.comparingInt(TeamTotal::titles).reversed()
                .thenComparing(Comparator.comparingInt(TeamTotal::points).reversed()));
        return out;
    }

    /** The last {@code n} editions (a "season"); all when {@code n <= 0}. */
    public static List<Edition> lastN(List<Edition> editions, int n) {
        if (n <= 0 || editions.size() <= n) return editions;
        return editions.subList(editions.size() - n, editions.size());
    }
}
