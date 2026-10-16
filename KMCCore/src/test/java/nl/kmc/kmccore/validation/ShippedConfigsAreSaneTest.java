package nl.kmc.kmccore.validation;

import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The files that ship in the KMCCore jar must pass the config doctor themselves: no negative durations, no
 * chance above 1, no min above max. (Comparing a file with itself leaves only the value rules.)
 */
class ShippedConfigsAreSaneTest {

    private static YamlConfiguration load(String name) throws Exception {
        try (InputStream in = ShippedConfigsAreSaneTest.class.getResourceAsStream("/" + name)) {
            assertNotNull(in, name + " is not in the jar");
            return YamlConfiguration.loadConfiguration(new InputStreamReader(in, StandardCharsets.UTF_8));
        }
    }

    @Test
    void theShippedFilesHaveNoImpossibleValues() throws Exception {
        for (String name : List.of("config.yml", "host.yml", "points.yml", "ceremonies.yml")) {
            YamlConfiguration y = load(name);
            var findings = ConfigDoctor.compare("KMCCore/" + name, y, y, Set.of(), null);
            assertTrue(findings.isEmpty(), name + ": " + findings);
        }
    }
}
