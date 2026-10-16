package nl.kmc.blockball;

import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;

import java.io.InputStreamReader;
import java.io.Reader;

import static org.junit.jupiter.api.Assertions.*;

/** The shipped keeper settings must be complete and make sense together. */
class ShippedBlockballTest {

    private static YamlConfiguration load() throws Exception {
        try (Reader r = new InputStreamReader(ShippedBlockballTest.class.getResourceAsStream("/config.yml"), "UTF-8")) {
            return YamlConfiguration.loadConfiguration(r);
        }
    }

    @Test
    void theKeeperSectionIsComplete() throws Exception {
        var k = load().getConfigurationSection("keeper");
        assertNotNull(k);
        for (String key : new String[]{"mode", "min-team-size", "zone-depth", "reach-bonus", "catch-max-speed",
                "hold-seconds", "catch-cooldown-ticks", "punch-power", "punch-lift", "throw-power", "throw-lift",
                "save-window-seconds", "save-min-speed"}) {
            assertTrue(k.contains(key), "keeper." + key + " missing");
        }
        assertEquals("AUTO", k.getString("mode"));
    }

    @Test
    void aChargedShotCannotBeCaughtButACasualKickCan() throws Exception {
        var y = load();
        double catchMax = y.getDouble("keeper.catch-max-speed");
        assertTrue(catchMax < y.getDouble("controls.shot-power"), "a charged shot must be punched, not caught");
        assertTrue(catchMax >= y.getDouble("controls.kick-power"), "a normal kick should be catchable");
        assertTrue(y.getDouble("keeper.save-min-speed") < catchMax);
        assertTrue(y.getInt("points.save") > 0);
    }

    @Test
    void theKeeperCannotHoldTheBallForever() throws Exception {
        var y = load();
        assertTrue(y.getInt("keeper.hold-seconds") >= 1 && y.getInt("keeper.hold-seconds") <= 10);
        assertTrue(y.getInt("keeper.catch-cooldown-ticks") > 0);
    }
}
