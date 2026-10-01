package nl.kmc.kmccore.managers;

import nl.kmc.core.domain.KMCTeam;
import nl.kmc.kmccore.KMCCore;
import nl.kmc.kmccore.database.DatabaseManager;
import nl.kmc.kmccore.models.PlayerData;
import org.bukkit.ChatColor;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * Pins the actual scoring rules that run every tournament: the "player and
 * team are always credited together" invariant, the placement curve, and the
 * flat-vs-multiplied kill/placement distinction (the whole point of the
 * round-multiplier design — see points.yml). {@link PointsManager} is the
 * single source of truth for every point award, so a silent regression here
 * would misscore an entire live event.
 *
 * <p>Only the plugin boundary is mocked ({@link KMCCore} and its manager
 * getters); {@link PlayerData} and {@link KMCTeam} are real objects so the
 * assertions exercise the actual point math, not a mock's recorded calls.
 */
class PointsManagerTest {

    /** Mirrors the shape of the shipped points.yml, with round numbers chosen to make the curve math easy to verify by hand. */
    private static final String POINTS_YML = """
            kills:
              per-kill: 50
              apply-multiplier: false
            placement:
              first-place: 500
              last-place: 10
              max-tracked-position: 32
              apply-multiplier: true
              overrides:
                1: 500
                2: 400
                3: 325
            team-placement:
              first-place: 1000
              second-place: 600
              third-place: 300
              fourth-place: 100
              apply-multiplier: true
            bonus:
              lucky-block-bonus: 50
              double-kill: 25
              triple-kill: 75
              mega-kill: 150
            """;

    @TempDir Path tempDir;

    private KMCCore plugin;
    private PlayerDataManager playerDataManager;
    private TeamManager teamManager;
    private DatabaseManager databaseManager;
    private TournamentManager tournamentManager;
    private YamlConfiguration mainConfig;
    private PointsManager points;
    private final UUID uuid = UUID.randomUUID();

    @BeforeEach
    void setUp() throws IOException {
        Files.writeString(tempDir.resolve("points.yml"), POINTS_YML);

        plugin = mock(KMCCore.class);
        when(plugin.getDataFolder()).thenReturn(tempDir.toFile());
        when(plugin.getLogger()).thenReturn(Logger.getLogger("PointsManagerTest"));

        playerDataManager = mock(PlayerDataManager.class);
        teamManager       = mock(TeamManager.class);
        databaseManager   = mock(DatabaseManager.class);
        tournamentManager = mock(TournamentManager.class);
        when(plugin.getPlayerDataManager()).thenReturn(playerDataManager);
        when(plugin.getTeamManager()).thenReturn(teamManager);
        when(plugin.getDatabaseManager()).thenReturn(databaseManager);
        when(plugin.getTournamentManager()).thenReturn(tournamentManager);

        mainConfig = new YamlConfiguration();
        when(plugin.getConfig()).thenReturn(mainConfig);

        points = new PointsManager(plugin);
    }

    private KMCTeam newTeam(String id) {
        return new KMCTeam(id, id, ChatColor.RED, ChatColor.RED);
    }

    // ── Placement curve ────────────────────────────────────────────────────

    @Test
    void placementCurve_explicitOverridesWinOverTheFormula() {
        assertEquals(500, points.getBasePointsForPlacement(1));
        assertEquals(400, points.getBasePointsForPlacement(2));
        assertEquals(325, points.getBasePointsForPlacement(3));
    }

    @Test
    void placementCurve_smoothlyDecreasesBetweenOverridesAndTheFloor() {
        // No override for position 4: 500 - 3 * ((500-10)/31) = 452.58... -> rounds to 453.
        assertEquals(453, points.getBasePointsForPlacement(4));
        // Position exactly at max-tracked-position lands on the floor by construction.
        assertEquals(10, points.getBasePointsForPlacement(32));
    }

    @Test
    void placementCurve_beyondMaxTrackedPosition_isFlatAtTheFloor() {
        assertEquals(10, points.getBasePointsForPlacement(33));
        assertEquals(10, points.getBasePointsForPlacement(1000));
    }

    // ── awardPlayerPoints: the central "player + team together" rule ──────

    @Test
    void awardPlayerPoints_zeroOrNegative_isRefusedWithoutTouchingAnyManager() {
        assertEquals(0, points.awardPlayerPoints(uuid, 0));
        assertEquals(0, points.awardPlayerPoints(uuid, -5));
        verifyNoInteractions(playerDataManager, databaseManager);
    }

    @Test
    void awardPlayerPoints_unknownPlayer_returnsZeroAndSavesNothing() {
        when(playerDataManager.get(uuid)).thenReturn(null);
        assertEquals(0, points.awardPlayerPoints(uuid, 100));
        verifyNoInteractions(databaseManager);
    }

    @Test
    void awardPlayerPoints_creditsPlayerAndTeamByTheSameAmount() {
        PlayerData pd = new PlayerData(uuid, "Steve");
        KMCTeam team = newTeam("red");
        when(playerDataManager.get(uuid)).thenReturn(pd);
        when(teamManager.getTeamByPlayer(uuid)).thenReturn(team);

        int awarded = points.awardPlayerPoints(uuid, 42);

        assertEquals(42, awarded);
        assertEquals(42, pd.getPoints(), "player total");
        assertEquals(42, team.getPoints(), "team total must auto-sync with the player's award");
        verify(databaseManager).savePlayer(pd);
        verify(databaseManager).saveTeam(team);
    }

    @Test
    void awardPlayerPoints_playerWithoutATeam_onlyCreditsThePlayer() {
        PlayerData pd = new PlayerData(uuid, "Steve");
        when(playerDataManager.get(uuid)).thenReturn(pd);
        when(teamManager.getTeamByPlayer(uuid)).thenReturn(null);

        points.awardPlayerPoints(uuid, 42);

        assertEquals(42, pd.getPoints());
        verify(databaseManager, never()).saveTeam(any());
    }

    // ── Kills: documented as flat, but only because points.yml overrides the code default ──

    @Test
    void awardKill_isFlat_ignoringTheRoundMultiplier() {
        mainConfig.set("tournament.multipliers.5", 3.0);
        when(tournamentManager.getCurrentRound()).thenReturn(5);
        PlayerData pd = new PlayerData(uuid, "Steve");
        when(playerDataManager.get(uuid)).thenReturn(pd);

        int awarded = points.awardKill(uuid);

        assertEquals(50, awarded, "kills.apply-multiplier: false in points.yml must keep this flat");
        assertEquals(1, pd.getKills());
        assertEquals(50, pd.getPoints());
    }

    @Test
    void killMultiplierFlag_defaultsToTrue_whenAbsentFromConfig() throws IOException {
        // Deliberately omits kills.apply-multiplier. The shipped points.yml always sets
        // it to false — this test documents that the flat-kills behaviour depends on
        // that explicit setting, not on the code's own default.
        Files.writeString(tempDir.resolve("points.yml"), """
                kills:
                  per-kill: 50
                placement:
                  first-place: 500
                  last-place: 10
                  max-tracked-position: 32
                """);
        PointsManager withoutFlag = new PointsManager(plugin);

        assertTrue(withoutFlag.killsUseMultiplier(),
                "code default is true; if the explicit 'false' is ever removed from points.yml, "
                        + "kills would silently start scaling with the round multiplier");
    }

    // ── Placement: multiplier DOES apply ───────────────────────────────────

    @Test
    void awardPlayerPlacement_scalesWithTheRoundMultiplier() {
        mainConfig.set("tournament.multipliers.6", 3.5);
        when(tournamentManager.getCurrentRound()).thenReturn(6);
        PlayerData pd = new PlayerData(uuid, "Steve");
        when(playerDataManager.get(uuid)).thenReturn(pd);

        int awarded = points.awardPlayerPlacement(uuid, 1); // override: 500 base

        assertEquals(1750, awarded); // 500 * 3.5
        assertEquals(1750, pd.getPoints());
    }

    // ── Team placement: separate bonus, never touches player totals ───────

    @Test
    void awardTeamPlacement_scalesWithMultiplierAndNeverTouchesPlayers() {
        KMCTeam team = newTeam("red");
        when(teamManager.getTeam("red")).thenReturn(team);
        mainConfig.set("tournament.multipliers.2", 1.5);
        when(tournamentManager.getCurrentRound()).thenReturn(2);

        int awarded = points.awardTeamPlacement("red", 1); // team-placement.first-place: 1000

        assertEquals(1500, awarded);
        assertEquals(1500, team.getPoints());
        verify(databaseManager).saveTeam(team);
        verifyNoInteractions(playerDataManager);
    }

    @Test
    void awardTeamPlacement_unknownTeam_returnsZero() {
        when(teamManager.getTeam("ghost")).thenReturn(null);
        assertEquals(0, points.awardTeamPlacement("ghost", 1));
    }

    @Test
    void awardTeamPlacement_placeBeyondFourth_returnsZero() {
        when(teamManager.getTeam("red")).thenReturn(newTeam("red"));
        assertEquals(0, points.awardTeamPlacement("red", 5));
    }

    // ── Bonus + multiplier lookups ──────────────────────────────────────────

    @Test
    void bonusValues_readFromPointsYml() {
        assertEquals(50, points.getLuckyBlockBonus());
        assertEquals(25, points.getDoubleKillBonus());
        assertEquals(75, points.getTripleKillBonus());
        assertEquals(150, points.getMegaKillBonus());
    }

    @Test
    void multiplierForRound_defaultsToOne_whenNotConfigured() {
        assertEquals(1.0, points.getMultiplierForRound(99));
    }

    // ── Comeback bonus ──────────────────────────────────────────────────────

    private KMCTeam teamWithPoints(String id, int pts) {
        KMCTeam t = newTeam(id);
        t.addPoints(pts);
        return t;
    }

    @Test
    void comebackBonus_boostsTheLastPlaceTeamOnceTheGapIsBigEnough() {
        mainConfig.set("comeback-bonus.enabled", true);
        mainConfig.set("comeback-bonus.multiplier", 1.25);
        mainConfig.set("comeback-bonus.min-gap", 100);

        KMCTeam leader = teamWithPoints("red", 500);
        KMCTeam last   = teamWithPoints("blue", 50); // gap = 450, well over min-gap
        when(teamManager.getTeamsSortedByPoints()).thenReturn(List.of(leader, last));
        when(teamManager.getTeamByPlayer(uuid)).thenReturn(last);

        PlayerData pd = new PlayerData(uuid, "Underdog");
        when(playerDataManager.get(uuid)).thenReturn(pd);

        int awarded = points.awardPlayerPoints(uuid, 100);

        assertEquals(125, awarded, "100 * 1.25 multiplier");
        assertEquals(125, pd.getPoints());
        assertEquals(50 + 125, last.getPoints(), "team credited the same boosted amount");
    }

    @Test
    void comebackBonus_doesNothing_whenGapIsBelowMinGap() {
        mainConfig.set("comeback-bonus.min-gap", 100);
        KMCTeam leader = teamWithPoints("red", 120);
        KMCTeam last   = teamWithPoints("blue", 100); // gap = 20, below min-gap
        when(teamManager.getTeamsSortedByPoints()).thenReturn(List.of(leader, last));
        when(teamManager.getTeamByPlayer(uuid)).thenReturn(last);
        PlayerData pd = new PlayerData(uuid, "Steve");
        when(playerDataManager.get(uuid)).thenReturn(pd);

        assertEquals(100, points.awardPlayerPoints(uuid, 100));
    }

    @Test
    void comebackBonus_doesNothing_forATeamThatIsNotLast() {
        KMCTeam leader = teamWithPoints("red", 500);
        KMCTeam last   = teamWithPoints("blue", 50);
        when(teamManager.getTeamsSortedByPoints()).thenReturn(List.of(leader, last));
        when(teamManager.getTeamByPlayer(uuid)).thenReturn(leader); // scoring for the LEADER, not last place

        PlayerData pd = new PlayerData(uuid, "Steve");
        when(playerDataManager.get(uuid)).thenReturn(pd);

        assertEquals(100, points.awardPlayerPoints(uuid, 100));
    }

    @Test
    void comebackBonus_disabledByConfig_isIgnored() {
        mainConfig.set("comeback-bonus.enabled", false);
        KMCTeam leader = teamWithPoints("red", 500);
        KMCTeam last   = teamWithPoints("blue", 50);
        when(teamManager.getTeamsSortedByPoints()).thenReturn(List.of(leader, last));
        when(teamManager.getTeamByPlayer(uuid)).thenReturn(last);
        PlayerData pd = new PlayerData(uuid, "Steve");
        when(playerDataManager.get(uuid)).thenReturn(pd);

        assertEquals(100, points.awardPlayerPoints(uuid, 100));
    }

    // ── Golden Hour ─────────────────────────────────────────────────────────

    @Test
    void goldenHour_overridesTheRoundMultiplier() {
        mainConfig.set("tournament.multipliers.4", 2.5);
        when(tournamentManager.getCurrentRound()).thenReturn(4);

        assertEquals(2.5, points.getCurrentMultiplier(), "sanity: normal round multiplier before activating");
        points.activateGoldenHour(3.0);
        assertEquals(3.0, points.getCurrentMultiplier(), "Golden Hour overrides the round's own multiplier");

        points.deactivateGoldenHour();
        assertEquals(2.5, points.getCurrentMultiplier(), "back to normal once deactivated");
    }

    @Test
    void goldenHour_makesKillsScaleTooDespiteTheNormallyFlatRule() {
        mainConfig.set("tournament.multipliers.1", 1.0);
        when(tournamentManager.getCurrentRound()).thenReturn(1);
        PlayerData pd = new PlayerData(uuid, "Steve");
        when(playerDataManager.get(uuid)).thenReturn(pd);

        points.activateGoldenHour(2.0);
        assertTrue(points.killsUseMultiplier(), "Golden Hour is an absolute boost, kills included");

        int awarded = points.awardKill(uuid);
        assertEquals(100, awarded, "50 base kill points * 2.0 Golden Hour multiplier");
    }
}
