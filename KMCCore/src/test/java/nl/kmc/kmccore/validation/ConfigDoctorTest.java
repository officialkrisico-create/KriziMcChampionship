package nl.kmc.kmccore.validation;

import nl.kmc.kmccore.validation.ConfigDoctor.Finding;
import nl.kmc.kmccore.validation.ConfigDoctor.Severity;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;

import java.io.StringReader;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class ConfigDoctorTest {

    private static YamlConfiguration yaml(String s) {
        return YamlConfiguration.loadConfiguration(new StringReader(s));
    }

    private static final String DEFAULTS = String.join("\n",
            "automation:",
            "  prestart-seconds: 10",
            "  games-per-round: 2",
            "  mid-break:",
            "    enabled: true",
            "    seconds: 300",
            "tournament:",
            "  total-rounds: 8",
            "  multipliers:",
            "    1: 1.0",
            "    2: 1.5",
            "teams:",
            "  red:",
            "    display-name: Red",
            "  blue:",
            "    display-name: Blue",
            "ball:",
            "  material: WHITE_CONCRETE",
            "  bounce: 0.55",
            "points:",
            "  win: 300",
            "  chance: 0.5",
            "range:",
            "  min-seconds: 5",
            "  max-seconds: 20",
            "");

    private static List<Finding> run(String actual) {
        return ConfigDoctor.compare("config.yml", yaml(DEFAULTS), yaml(actual), Set.of(), n -> n.equals("WHITE_CONCRETE"));
    }

    @Test
    void anUnchangedFileHasNoFindings() {
        assertTrue(run(DEFAULTS).isEmpty());
    }

    @Test
    void aTypoIsReportedWithASuggestion() {
        var f = run("automation:\n  prestart-secnds: 10\n");
        assertEquals(1, f.size());
        assertEquals("automation.prestart-secnds", f.get(0).path());
        assertEquals(Severity.WARN, f.get(0).severity());
        assertTrue(f.get(0).hint().contains("prestart-seconds"), f.get(0).hint());
    }

    @Test
    void aKeyThatIsFarFromEverythingGetsNoSuggestion() {
        var f = run("automation:\n  completely-different-thing: 1\n");
        assertEquals(1, f.size());
        assertFalse(f.get(0).hint().contains("bedoelde je"));
    }

    @Test
    void freeFormSectionsAcceptNewKeys() {
        // a third team and a ninth round are not typos
        assertTrue(run("teams:\n  green:\n    display-name: Green\ntournament:\n  multipliers:\n    9: 6.0\n").isEmpty());
    }

    @Test
    void aWrongTypeIsAnError() {
        var f = run("automation:\n  prestart-seconds: \"snel\"\n  mid-break: yes please\n");
        assertTrue(f.stream().anyMatch(x -> x.path().equals("automation.prestart-seconds") && x.severity() == Severity.ERROR), f.toString());
        assertTrue(f.stream().anyMatch(x -> x.path().equals("automation.mid-break") && x.severity() == Severity.ERROR), f.toString());
    }

    @Test
    void valuesThatContradictTheirNameAreReported() {
        var f = run("automation:\n  prestart-seconds: -5\npoints:\n  chance: 1.5\n");
        assertTrue(f.stream().anyMatch(x -> x.path().equals("automation.prestart-seconds") && x.severity() == Severity.WARN), f.toString());
        assertTrue(f.stream().anyMatch(x -> x.path().equals("points.chance") && x.severity() == Severity.ERROR), f.toString());
    }

    @Test
    void minAboveMaxIsAnError() {
        var f = run("range:\n  min-seconds: 30\n  max-seconds: 20\n");
        assertTrue(f.stream().anyMatch(x -> x.path().equals("range.min-seconds") && x.severity() == Severity.ERROR), f.toString());
    }

    @Test
    void anUnknownMaterialIsAWarning() {
        var f = run("ball:\n  material: WHITE_CONCRTE\n");
        assertEquals(1, f.size());
        assertTrue(f.get(0).message().contains("materiaal"));
    }

    @Test
    void multiplierMustBePositive() {
        var f = ConfigDoctor.compare("x", yaml("golden:\n  multiplier: 2.0\n"), yaml("golden:\n  multiplier: 0\n"), Set.of(), null);
        assertEquals(1, f.size());
        assertEquals(Severity.ERROR, f.get(0).severity());
    }

    @Test
    void distanceIsLevenshtein() {
        assertEquals(0, ConfigDoctor.distance("abc", "abc"));
        assertEquals(1, ConfigDoctor.distance("abc", "abd"));
        assertEquals(3, ConfigDoctor.distance("", "abc"));
        assertEquals(2, ConfigDoctor.distance("kitten", "sitten") + 1);
    }
}
