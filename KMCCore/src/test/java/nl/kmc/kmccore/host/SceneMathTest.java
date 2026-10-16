package nl.kmc.kmccore.host;

import nl.kmc.kmccore.host.HostDialogues.Stage;
import nl.kmc.kmccore.host.HostQueue.SpeechLine;
import nl.kmc.kmccore.host.SceneMath.Key;
import nl.kmc.kmccore.host.SceneMath.Move;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.util.Vector;
import org.junit.jupiter.api.Test;

import java.io.InputStreamReader;
import java.io.Reader;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class SceneMathTest {

    private static SpeechLine line(int delay, int hold, Stage st) {
        return new SpeechLine("x", delay, hold, null, st);
    }

    private static Stage stage(String shot, String walk, int tension) {
        return new Stage(shot, walk, "", "", "", "", tension);
    }

    private static final Vector HOME = new Vector(100, 64, 100);
    private static final Vector DIR = new Vector(0, 0, 1);

    @Test
    void displayAndEndTicksFollowThePlaybackChain() {
        List<SpeechLine> lines = List.of(line(10, 60, Stage.NONE), line(0, 5, Stage.NONE), line(20, 40, Stage.NONE));
        assertArrayEquals(new long[]{10, 70, 100}, SceneMath.displayTicks(lines));
        // a hold below 10 ticks is raised to 10, exactly like the playback does
        assertArrayEquals(new long[]{70, 80, 140}, SceneMath.endTicks(lines));
        assertEquals(140, SceneMath.totalTicks(lines));
    }

    @Test
    void tensionRisesByItselfAndHonoursExplicitValues() {
        List<SpeechLine> lines = List.of(line(0, 40, Stage.NONE), line(0, 40, Stage.NONE),
                line(0, 40, stage("", "", 10)), line(0, 40, Stage.NONE));
        double[] t = SceneMath.tension(lines);
        assertEquals(1.0, t[2], 1e-9);
        assertTrue(t[0] < t[1], "automatic tension rises");
        assertTrue(t[0] > 0 && t[3] <= 1.0);
    }

    @Test
    void tensionIsInterpolatedBetweenLines() {
        long[] display = {0, 100};
        double[] t = {0.0, 1.0};
        assertEquals(0.5, SceneMath.tensionAt(50, display, t), 1e-9);
        assertEquals(1.0, SceneMath.tensionAt(500, display, t), 1e-9);
    }

    @Test
    void walksParse() {
        assertEquals("forward", SceneMath.parseWalk("forward:4").kind());
        assertEquals(4.0, SceneMath.parseWalk("forward:4").blocks());
        assertEquals(3.0, SceneMath.parseWalk("back").blocks());
        assertEquals("home", SceneMath.parseWalk("home").kind());
        assertEquals(2.5, SceneMath.parseWalk("left:2,5").blocks());
        assertNull(SceneMath.parseWalk("sideways:3"));
        assertNull(SceneMath.parseWalk("forward:abc"));
        assertNull(SceneMath.parseWalk(""));
    }

    @Test
    void walkTargetsAreRelativeToTheWayTheHostFaces() {
        Vector fwd = SceneMath.walkTarget(HOME, HOME, DIR, SceneMath.parseWalk("forward:4"));
        assertEquals(104, fwd.getZ(), 1e-9);
        Vector right = SceneMath.walkTarget(HOME, HOME, DIR, SceneMath.parseWalk("right:2"));
        assertEquals(98, right.getX(), 1e-9, "facing +z, the right hand is -x");
        Vector home = SceneMath.walkTarget(new Vector(0, 0, 0), HOME, DIR, SceneMath.parseWalk("home"));
        assertEquals(HOME, home);
    }

    @Test
    void walksAreChainedAndTakeAsLongAsTheDistanceNeeds() {
        List<SpeechLine> lines = List.of(line(0, 100, stage("", "forward:4", -1)), line(0, 100, stage("", "home", -1)));
        long[] display = SceneMath.displayTicks(lines);
        List<Move> moves = SceneMath.planWalks(lines, display, HOME, DIR, 0.2, null);
        assertEquals(2, moves.size());
        assertEquals(display[0], moves.get(0).t0());
        assertEquals(20, moves.get(0).t1() - moves.get(0).t0(), "4 blocks at 0.2 per tick");
        assertEquals(HOME, moves.get(1).to(), "the last walk brings the host back");
        assertTrue(moves.get(1).t0() >= moves.get(0).t1());

        // halfway through the first walk the host is halfway there
        long mid = moves.get(0).t0() + 10;
        assertEquals(102, SceneMath.hostAt(mid, moves, HOME).getZ(), 1e-6);
        assertEquals(HOME, SceneMath.hostAt(0, moves, HOME));
        assertEquals(HOME, SceneMath.hostAt(10_000, moves, HOME));
    }

    @Test
    void aWalkThatDoesNotMoveIsDropped() {
        List<SpeechLine> lines = List.of(line(0, 40, stage("", "home", -1)));
        assertTrue(SceneMath.planWalks(lines, SceneMath.displayTicks(lines), HOME, DIR, 0.2, null).isEmpty());
    }

    @Test
    void theCameraCoversTheWholeSpeechWithStrictlyRisingTimes() {
        List<SpeechLine> lines = new ArrayList<>();
        String[] shots = {"fly", "wide", "push", "closeup", "orbit", "crane", "top", "low", "hold", "push"};
        for (String s : shots) lines.add(line(10, 80, stage(s, "", -1)));
        List<Move> none = List.of();
        List<Key> keys = SceneMath.camera(lines, t -> SceneMath.hostAt(t, none, HOME), DIR);

        assertEquals(0, keys.get(0).t(), "the path starts at tick 0");
        assertEquals(SceneMath.totalTicks(lines), keys.get(keys.size() - 1).t(), "and ends with the speech");
        for (int i = 1; i < keys.size(); i++) assertTrue(keys.get(i).t() > keys.get(i - 1).t(), "key " + i);
        for (Key k : keys) {
            assertTrue(Double.isFinite(k.pos().getX() + k.pos().getY() + k.pos().getZ()));
            assertNotEquals(k.pos(), k.look(), "the camera never looks at itself");
        }
    }

    @Test
    void theCameraStaysInFrontOfTheHostAndComesCloserOnAPush() {
        List<SpeechLine> lines = List.of(line(0, 100, stage("push", "", -1)));
        List<Key> keys = SceneMath.camera(lines, t -> HOME.clone(), DIR);
        Key last = keys.get(keys.size() - 1);
        assertTrue(last.pos().getZ() > HOME.getZ(), "in front of a host that faces +z");
        assertTrue(last.pos().distance(HOME) < keys.get(0).pos().distance(HOME), "the push comes closer");
    }

    @Test
    void aLineSplitOverPagesIsOneShot() {
        List<SpeechLine> lines = List.of(line(0, 60, stage("orbit", "", -1)),
                line(0, 60, Stage.continuation(-1)), line(0, 60, Stage.continuation(-1)));
        assertEquals(2, SceneMath.groupEnd(lines, 0));
        List<Key> keys = SceneMath.camera(lines, t -> HOME.clone(), DIR);
        // an orbit has four keys plus the opening key: not repeated for every page
        assertEquals(5, keys.size());
    }

    // ── The shipped lore ────────────────────────────────────────────────────

    @Test
    void theShippedLoreIsStagedAndBuildsATimeline() throws Exception {
        YamlConfiguration y;
        try (Reader r = new InputStreamReader(SceneMathTest.class.getResourceAsStream("/host.yml"), "UTF-8")) {
            y = YamlConfiguration.loadConfiguration(r);
        }
        var parsed = HostDialogues.parse(y.getConfigurationSection("dialogues"), y.getConfigurationSection("dialogue-defaults"));
        assertTrue(parsed.warnings().isEmpty(), parsed.warnings().toString());
        HostDialogues.Dialogue lore = parsed.dialogues().get("kmc-lore");
        assertNotNull(lore, "kmc-lore is shipped");
        assertTrue(lore.staged());
        assertEquals(HostDialogues.CRITICAL, lore.priority());
        assertTrue(lore.lines().size() >= 10);

        // the host walks, jumps and the camera zooms/flies in the lore, and the tension ends at the top
        boolean walks = false, jumps = false, fly = false, zoom = false, top = false;
        for (HostDialogues.Line l : lore.lines()) {
            Stage s = l.stage();
            walks |= !s.walk().isEmpty();
            jumps |= s.act().equals("jump") || s.act().equals("hop");
            fly |= s.shot().equals("fly");
            zoom |= s.shot().equals("push") || s.shot().equals("closeup");
            top |= s.tension() == 10;
        }
        assertTrue(walks && jumps && fly && zoom && top, "walk=" + walks + " jump=" + jumps + " fly=" + fly
                + " zoom=" + zoom + " climax=" + top);

        // a plausible length: a minute or two, not seconds and not ten minutes
        List<SpeechLine> sl = new ArrayList<>();
        for (HostDialogues.Line l : lore.lines()) {
            int hold = l.holdTicks() >= 0 ? l.holdTicks() : 100;
            sl.add(new SpeechLine("x", l.delayTicks() >= 0 ? l.delayTicks() : 10, hold, null, l.stage()));
        }
        long total = SceneMath.totalTicks(sl);
        assertTrue(total > 40 * 20 && total < 180 * 20, "lore length ticks: " + total);
    }
}
