package nl.kmc.kmccore.host;

import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;

import java.io.InputStreamReader;
import java.io.Reader;
import java.io.StringReader;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class HostDialoguesTest {

    private static YamlConfiguration yaml(String s) {
        return YamlConfiguration.loadConfiguration(new StringReader(s));
    }

    private static YamlConfiguration shipped() throws Exception {
        try (Reader r = new InputStreamReader(HostDialoguesTest.class.getResourceAsStream("/host.yml"), "UTF-8")) {
            return YamlConfiguration.loadConfiguration(r);
        }
    }

    private static HostDialogues.Parsed parseShipped() throws Exception {
        YamlConfiguration y = shipped();
        return HostDialogues.parse(y.getConfigurationSection("dialogues"), y.getConfigurationSection("dialogue-defaults"));
    }

    @Test
    void theShippedHostFileParsesWithoutWarnings() throws Exception {
        var parsed = parseShipped();
        assertTrue(parsed.warnings().isEmpty(), parsed.warnings().toString());
        for (String id : new String[]{"opening", "how-it-works", "team-intro", "games-lineup", "results-game",
                "standings-normal", "standings-close", "standings-new-leader", "finale-announce", "finale-third",
                "finale-second", "finale-champion", "finale-awards", "live-goal", "live-puzzle", "live-order",
                "live-room", "live-boss", "info-about", "info-how", "test-bubble", "idle-touch"}) {
            assertNotNull(parsed.dialogues().get(id), "missing dialogue " + id);
        }
    }

    @Test
    void cameraWorkIsOptInAndNeverOnLiveAnnouncements() throws Exception {
        var parsed = parseShipped();
        assertTrue(parsed.dialogues().get("finale-champion").focus());
        assertEquals("host-champion", parsed.dialogues().get("finale-champion").cutscene());
        parsed.dialogues().forEach((id, d) -> {
            if (id.startsWith("live-") || id.startsWith("info-")) {
                assertFalse(d.focus(), id + " must not move the camera");
                assertNull(d.cutscene(), id);
            }
        });
    }

    @Test
    void theOpeningOnlyPlaysAtTheStartAndInfoIsForOnePlayer() throws Exception {
        var parsed = parseShipped();
        assertTrue(parsed.dialogues().get("opening").onlyOnStart());
        assertEquals(HostDialogues.Audience.PLAYER, parsed.dialogues().get("info-about").audience());
    }

    @Test
    void everyLiveDialogueHasVariantsAndACooldown() throws Exception {
        parseShipped().dialogues().forEach((id, d) -> {
            if (id.startsWith("live-")) {
                assertFalse(d.variants().isEmpty(), id);
                assertTrue(d.cooldownSeconds() > 0, id + " needs a cooldown so it cannot repeat every second");
            }
        });
    }

    @Test
    void unknownPlaceholdersAndEmptyDialoguesBecomeWarningsNotCrashes() {
        var y = yaml("dialogues:\n"
                + "  a:\n    lines:\n      - \"Hallo {nope}\"\n"
                + "  b:\n    lines: []\n"
                + "  c: not-a-section\n"
                + "  d:\n    audience: NOBODY\n    priority: banana\n    lines: [\"ok\"]\n");
        var p = HostDialogues.parse(y.getConfigurationSection("dialogues"), null);
        assertTrue(p.warnings().stream().anyMatch(w -> w.contains("{nope}")), p.warnings().toString());
        assertTrue(p.warnings().stream().anyMatch(w -> w.contains("'b'")), p.warnings().toString());
        assertTrue(p.warnings().stream().anyMatch(w -> w.contains("'c'")), p.warnings().toString());
        assertNotNull(p.dialogues().get("d"), "an invalid audience falls back to the default");
        assertEquals(HostDialogues.Audience.ALL, p.dialogues().get("d").audience());
        assertEquals(HostDialogues.NORMAL, p.dialogues().get("d").priority());
    }

    @Test
    void textMayCarryLanguageVariantsAndOptions() {
        var y = yaml("dialogues:\n"
                + "  a:\n    priority: HIGH\n    lines:\n"
                + "      - text: {nl: \"Welkom\", en: \"Welcome\"}\n"
                + "        delay: 1.5\n        duration: 4\n        sound: ENTITY_PLAYER_LEVELUP\n"
                + "        requires: [team]\n");
        var d = HostDialogues.parse(y.getConfigurationSection("dialogues"), null).dialogues().get("a");
        var line = d.lines().get(0);
        assertEquals("Welcome", line.text("en"));
        assertEquals("Welkom", line.text("nl"));
        assertEquals("Welkom", line.text("de"), "an unknown language falls back to nl");
        assertEquals(30, line.delayTicks());
        assertEquals(80, line.holdTicks());
        assertEquals(List.of("team"), line.requires());
        assertEquals(HostDialogues.HIGH, d.priority());
    }
}
