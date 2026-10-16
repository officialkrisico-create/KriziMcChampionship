package nl.kmc.factory;

import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;

import java.io.InputStreamReader;
import java.io.Reader;

import static org.junit.jupiter.api.Assertions.*;

/** The kitchen that ships in config.yml must be valid: every dish can be made with the layout. */
class ShippedKitchenTest {

    private YamlConfiguration shipped() throws Exception {
        try (Reader r = new InputStreamReader(getClass().getResourceAsStream("/config.yml"), "UTF-8")) {
            return YamlConfiguration.loadConfiguration(r);
        }
    }

    @Test
    void theShippedKitchenIsValid() throws Exception {
        FactoryFrenzyManager.Model m = FactoryFrenzyManager.parse(shipped());
        assertTrue(m.problems.isEmpty(), m.problems.toString());
        assertTrue(m.dishes.size() >= 4);
        assertFalse(m.stations.isEmpty());
    }

    @Test
    void takingTheOnlyPanAwayCatchesTheBurger() throws Exception {
        YamlConfiguration cfg = shipped();
        var stations = new java.util.ArrayList<java.util.Map<?, ?>>();
        for (Object o : cfg.getList("layout.stations")) {
            if (!"pan1".equals(((java.util.Map<?, ?>) o).get("id"))) stations.add((java.util.Map<?, ?>) o);
        }
        cfg.set("layout.stations", stations);
        FactoryFrenzyManager.Model m = FactoryFrenzyManager.parse(cfg);
        assertTrue(m.problems.stream().anyMatch(s -> s.contains("burger")), m.problems.toString());
    }
}
