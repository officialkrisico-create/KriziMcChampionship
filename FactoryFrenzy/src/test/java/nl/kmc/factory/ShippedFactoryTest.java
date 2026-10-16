package nl.kmc.factory;

import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;

import java.io.InputStreamReader;
import java.io.Reader;

import static org.junit.jupiter.api.Assertions.*;

/** The factory that ships in config.yml must be valid: every order can be produced with the layout. */
class ShippedFactoryTest {

    @Test
    void theShippedFactoryIsValid() throws Exception {
        try (Reader r = new InputStreamReader(getClass().getResourceAsStream("/config.yml"))) {
            YamlConfiguration cfg = YamlConfiguration.loadConfiguration(r);
            FactoryFrenzyManager.Model m = FactoryFrenzyManager.parse(cfg);
            assertTrue(m.problems.isEmpty(), m.problems.toString());
            assertFalse(m.machines.isEmpty());
            assertFalse(m.stations.isEmpty());
            assertTrue(m.orders.size() >= 5);
        }
    }

    @Test
    void anImpossibleOrderIsCaught() throws Exception {
        try (Reader r = new InputStreamReader(getClass().getResourceAsStream("/config.yml"))) {
            YamlConfiguration cfg = YamlConfiguration.loadConfiguration(r);
            // remove the only station that gives redstone: components can no longer be made
            var stations = new java.util.ArrayList<java.util.Map<?, ?>>();
            for (Object o : cfg.getList("layout.stations")) {
                if (!"redstone".equals(((java.util.Map<?, ?>) o).get("id"))) stations.add((java.util.Map<?, ?>) o);
            }
            cfg.set("layout.stations", stations);
            FactoryFrenzyManager.Model m = FactoryFrenzyManager.parse(cfg);
            assertTrue(m.problems.stream().anyMatch(s -> s.contains("niet te maken")), m.problems.toString());
        }
    }
}
