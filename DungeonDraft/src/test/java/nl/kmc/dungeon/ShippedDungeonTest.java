package nl.kmc.dungeon;

import nl.kmc.dungeon.DungeonLogic.RoomType;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;

import java.io.InputStreamReader;
import java.io.Reader;
import java.util.Random;
import java.util.HashSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/** The dungeon that ships in config.yml must be playable: a full run can always be drafted. */
class ShippedDungeonTest {

    private YamlConfiguration load() throws Exception {
        try (Reader r = new InputStreamReader(getClass().getResourceAsStream("/config.yml"))) {
            return YamlConfiguration.loadConfiguration(r);
        }
    }

    @Test
    void theShippedRoomsParseWithoutProblems() throws Exception {
        YamlConfiguration cfg = load();
        var parsed = DungeonConfig.parse(cfg);
        assertTrue(parsed.problems.isEmpty(), parsed.problems.toString());
        assertTrue(DungeonLogic.validate(parsed.rooms, cfg.getInt("dungeon.length"), cfg.getInt("dungeon.route-choices"),
                cfg.getBoolean("dungeon.boss-last")).isEmpty());
        for (RoomType t : RoomType.values())
            assertTrue(parsed.rooms.stream().anyMatch(r -> r.type() == t), "no room of type " + t);
    }

    @Test
    void everyRunOfTheShippedPoolEndsInTheBoss() throws Exception {
        YamlConfiguration cfg = load();
        var pool = DungeonConfig.parse(cfg).rooms;
        int length = cfg.getInt("dungeon.length"), choices = cfg.getInt("dungeon.route-choices");
        for (int seed = 0; seed < 200; seed++) {
            Random rnd = new Random(seed);
            Set<String> used = new HashSet<>();
            for (int n = 1; n <= length; n++) {
                var opts = DungeonLogic.draft(pool, used, n, length, choices, true, cfg.getDouble("dungeon.modifier-chance"), rnd);
                assertFalse(opts.isEmpty(), "no options at room " + n + " seed " + seed);
                var pick = opts.get(rnd.nextInt(opts.size()));
                assertTrue(used.add(pick.room().id()));
                if (n == length) assertEquals(RoomType.BOSS, pick.room().type());
                else assertNotEquals(RoomType.BOSS, pick.room().type());
            }
        }
    }

    @Test
    void roomsOfTheSameLaneDoNotOverlap() throws Exception {
        var rooms = DungeonConfig.parse(load()).rooms;
        Set<Integer> xs = new HashSet<>();
        for (var r : rooms) assertTrue(xs.add(r.at()[0] / 14), "two rooms share a cell: " + r.id());
    }

    @Test
    void aBrokenRoomIsReported() throws Exception {
        YamlConfiguration cfg = load();
        var list = new java.util.ArrayList<java.util.Map<String, Object>>();
        var bad = new java.util.LinkedHashMap<String, Object>();
        bad.put("id", "x"); bad.put("type", "COMBAT"); bad.put("at", java.util.List.of(0, 0, 0)); bad.put("entry", java.util.List.of(1, 0, 0));
        list.add(bad);
        cfg.set("rooms", list);
        var parsed = DungeonConfig.parse(cfg);
        assertFalse(parsed.problems.isEmpty());
    }
}
