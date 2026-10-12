package nl.kmc.kmccore.scoring;

import nl.kmc.kmccore.scoring.AwardsEngine.Award;
import nl.kmc.kmccore.scoring.EditionArchive.*;
import nl.kmc.kmccore.scoring.PointsLedger.Entry;
import nl.kmc.kmccore.scoring.PointsLedger.GameInfo;
import nl.kmc.kmccore.scoring.PointsLedger.Highlight;
import org.junit.jupiter.api.Test;

import java.io.File;
import java.nio.file.Files;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

class ScoringTest {

    private static final UUID A = UUID.fromString("00000000-0000-0000-0000-00000000000a");
    private static final UUID B = UUID.fromString("00000000-0000-0000-0000-00000000000b");
    private static final UUID C = UUID.fromString("00000000-0000-0000-0000-00000000000c");

    private static Entry e(int game, String reason, UUID p, String name, String team, int pts) {
        return new Entry(0, 1, game, "g" + game, reason, p, name, team, pts, 1.0, 0, pts, p == null);
    }

    private static List<GameInfo> games(int n) {
        List<GameInfo> l = new ArrayList<>();
        for (int i = 1; i <= n; i++) l.add(new GameInfo(i, "g" + i, 1, 0));
        return l;
    }

    // ── ledger aggregation ───────────────────────────────────────────────────

    @Test
    void ledgerAggregatesByReasonPlayerAndTeam() {
        List<Entry> es = List.of(
                e(1, "KILL", A, "A", "red", 50), e(1, "KILL", A, "A", "red", 50),
                e(1, "PLACEMENT", B, "B", "blue", 200),
                e(1, "TEAM_PLACEMENT", null, null, "red", 100));
        assertEquals(100, PointsLedger.byReason(es).get("KILL"));
        assertEquals(200, PointsLedger.byPlayer(es).get(B));
        assertEquals(200, PointsLedger.byTeam(es).get("red"));   // 100 kills + 100 team bonus
        assertEquals(B, PointsLedger.byPlayer(es).keySet().iterator().next());   // biggest first
    }

    @Test
    void truncateRollsTheBookBack() {
        PointsLedger l = new PointsLedger(null);
        l.beginGame("g1", 1);
        l.record(e(1, "KILL", A, "A", "red", 10));
        int size = l.size(), seq = l.lastGameSeq();
        l.beginGame("g2", 1);
        l.record(e(2, "KILL", A, "A", "red", 20));
        l.recordHighlight(new Highlight("clutch", A, "A", "red", 1, "g2", 2));
        l.truncate(size, 0, seq);
        assertEquals(1, l.size());
        assertEquals(1, l.lastGameSeq());
        assertEquals(1, l.games().size());
        assertTrue(l.highlights().isEmpty());
    }

    // ── awards ───────────────────────────────────────────────────────────────

    @Test
    void awardsPickTheRightWinners() {
        List<Entry> es = new ArrayList<>();
        // A scores the most overall, B has the most kills, C is a steady 100 every game.
        for (int g = 1; g <= 3; g++) {
            es.add(e(g, "PLACEMENT", A, "A", "red", g == 1 ? 400 : 50));
            es.add(e(g, "KILL", B, "B", "blue", g == 2 ? 10 : 110));
            es.add(e(g, "OBJECTIVE", C, "C", "blue", 100));
        }
        List<Highlight> hl = List.of(
                new Highlight("clutch", C, "C", "blue", 2, "g1", 1),
                new Highlight("clutch", A, "A", "red", 1, "g2", 2));
        List<Award> awards = AwardsEngine.compute(es, hl, games(3));
        Map<String, Award> by = new HashMap<>();
        awards.forEach(a -> by.put(a.id(), a));

        assertEquals(A, by.get("mvp").player());                 // 500 vs 180 vs 300
        assertEquals(B, by.get("killer").player());
        assertEquals(C, by.get("clutch").player());
        assertEquals(C, by.get("consistent").player());          // zero spread
        assertTrue(by.get("mvp").runnersUp().size() >= 1);
        assertNotNull(by.get("team_wins"));
    }

    @Test
    void consistencyNeedsEnoughGames() {
        List<Entry> es = List.of(e(1, "OBJECTIVE", A, "A", "red", 100), e(2, "OBJECTIVE", A, "A", "red", 100));
        List<Award> awards = AwardsEngine.compute(es, List.of(), games(2));
        assertTrue(awards.stream().noneMatch(a -> a.id().equals("consistent")));
    }

    @Test
    void comebackNeedsARealClimb() {
        // Team z is last after game 1, then wins everything and ends first.
        List<Map<String, Integer>> perGame = List.of(
                Map.of("a", 100, "b", 80, "c", 60, "z", 10),
                Map.of("a", 10, "b", 10, "c", 10, "z", 300));
        var climb = AwardsEngine.comebackClimb(perGame);
        assertEquals(3.0, climb.get("z"));
        assertFalse(climb.containsKey("a"));
    }

    // ── season ranking ───────────────────────────────────────────────────────

    private static Edition edition(int n, String champTeam, PlayerRow... rows) {
        return new Edition(n, "KMC", 0, 4, champTeam, champTeam,
                List.of(new TeamRow("red", "Red", 100, "red".equals(champTeam) ? 1 : 2, List.of()),
                        new TeamRow("blue", "Blue", 90, "blue".equals(champTeam) ? 1 : 2, List.of())),
                List.of(rows), List.of(), List.of(), 0, 0);
    }

    @Test
    void seasonAggregatesPlayersAndTeams() {
        var e1 = edition(1, "red", new PlayerRow(A, "A", "red", "Red", 300, 5, 2, 1),
                new PlayerRow(B, "B", "blue", "Blue", 200, 9, 1, 2));
        var e2 = edition(2, "blue", new PlayerRow(A, "A", "red", "Red", 100, 1, 0, 2),
                new PlayerRow(B, "B", "blue", "Blue", 400, 7, 3, 1));
        var players = SeasonRanking.players(List.of(e1, e2));
        assertEquals(B, players.get(0).uuid());
        assertEquals(600, players.get(0).points());
        assertEquals(1, players.get(0).editionWins());
        assertEquals(2, players.get(1).editions());

        var teams = SeasonRanking.teams(List.of(e1, e2));
        assertEquals(1, teams.get(0).titles());
        assertEquals(2, teams.size());
        assertEquals(1, SeasonRanking.lastN(List.of(e1, e2), 1).size());
        assertEquals(2, SeasonRanking.lastN(List.of(e1, e2), 0).size());
    }

    // ── track records ────────────────────────────────────────────────────────

    @Test
    void trackRecordsKeepTheBestTimes() throws Exception {
        File f = Files.createTempFile("track-records", ".yml").toFile();
        f.deleteOnExit();
        TrackRecords tr = new TrackRecords(null, f);

        var first = tr.submit("formula_mc", "Ice Ring", 0, A, "A", 90_000);
        assertTrue(first.trackRecord() && first.personalBest());
        assertEquals(-1, first.previousRecordMs());

        var slower = tr.submit("formula_mc", "Ice Ring", 0, B, "B", 95_000);
        assertTrue(slower.personalBest());          // the first time of B is the best of B
        assertFalse(slower.trackRecord());

        var better = tr.submit("formula_mc", "Ice Ring", 0, B, "B", 88_500);
        assertTrue(better.trackRecord());
        assertEquals(95_000, better.previousBestMs());
        assertEquals(90_000, better.previousRecordMs());
        assertEquals("A", better.previousRecordHolder());

        assertEquals(88_500, tr.record("formula_mc", "Ice Ring", 0).millis());
        assertEquals(90_000, tr.best("formula_mc", "Ice Ring", 0, A));
        assertEquals(B, tr.top("formula_mc", "Ice Ring", 0, 5).get(0).player());

        tr.flush();
        TrackRecords reloaded = new TrackRecords(null, f);
        assertEquals(88_500, reloaded.record("formula_mc", "Ice Ring", 0).millis());
        assertEquals("1:28.500", TrackRecords.format(88_500));
        assertEquals("9.120", TrackRecords.format(9_120));
    }
}
