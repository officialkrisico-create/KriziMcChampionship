package nl.kmc.dungeon;

import nl.kmc.dungeon.DungeonLogic.RoomDef;
import nl.kmc.dungeon.DungeonLogic.RoomType;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.entity.EntityType;

import java.util.*;

/** Reads and validates the rooms from config.yml. No server is needed, so a test can run it on the shipped file. */
public final class DungeonConfig {

    private DungeonConfig() { }

    public static final class Parsed {
        public final List<RoomDef> rooms = new ArrayList<>();
        public final List<String> problems = new ArrayList<>();
    }

    public static Parsed parse(FileConfiguration cfg) {
        Parsed out = new Parsed();
        for (Object o : cfg.getList("rooms", List.of())) {
            if (!(o instanceof Map<?, ?> m)) { out.problems.add("rooms: ongeldige regel " + o); continue; }
            String id = String.valueOf(m.get("id"));
            RoomType type;
            try { type = RoomType.valueOf(String.valueOf(m.get("type")).toUpperCase(Locale.ROOT)); }
            catch (IllegalArgumentException e) { out.problems.add("kamer " + id + ": onbekend type " + m.get("type")); continue; }
            int[] at = point(m.get("at")), entry = point(m.get("entry"));
            Map<String, Object> params = new LinkedHashMap<>();
            m.forEach((k, v) -> params.put(String.valueOf(k), v));
            RoomDef def = new RoomDef(id, str(m.get("name"), id), str(m.get("description"), ""), type,
                    num(m.get("difficulty"), 1), num(m.get("reward"), 100), num(m.get("time-limit-seconds"), 120),
                    str(m.get("risk"), "?"), at, entry, params);
            out.rooms.add(def);
            checkParams(def, out.problems);
        }
        return out;
    }

    private static void checkParams(RoomDef r, List<String> p) {
        Map<String, Object> m = r.params();
        String id = r.id();
        switch (r.type()) {
            case COMBAT -> {
                if (points(m.get("spawns")).isEmpty()) p.add("kamer " + id + ": combat heeft 'spawns' nodig");
                List<Map<String, Integer>> waves = waves(m.get("waves"));
                if (waves.isEmpty()) p.add("kamer " + id + ": combat heeft 'waves' nodig");
                for (var w : waves) for (String t : w.keySet()) {
                    if (!entity(t)) p.add("kamer " + id + ": onbekende mob " + t);
                }
            }
            case PARKOUR -> {
                if (point(m.get("finish-min")) == null || point(m.get("finish-max")) == null)
                    p.add("kamer " + id + ": parkour heeft 'finish-min' en 'finish-max' nodig");
            }
            case TRAP -> {
                if (points(m.get("switches")).isEmpty()) p.add("kamer " + id + ": trap heeft 'switches' nodig (de uitgang gaat open)");
            }
            case TREASURE -> {
                if (points(m.get("chests")).isEmpty()) p.add("kamer " + id + ": treasure heeft 'chests' nodig");
            }
            case PUZZLE -> {
                if (points(m.get("pads")).size() < 3) p.add("kamer " + id + ": puzzle heeft minstens 3 'pads' nodig");
            }
            case BOSS -> {
                if (point(m.get("spawn")) == null) p.add("kamer " + id + ": boss heeft 'spawn' nodig");
                if (!entity(str(m.get("mob"), "WITHER_SKELETON"))) p.add("kamer " + id + ": onbekende boss-mob " + m.get("mob"));
            }
        }
    }

    // ── Small readers (also used by the manager) ──────────────────────────────

    public static boolean entity(String name) {
        try { EntityType.valueOf(name.toUpperCase(Locale.ROOT)); return true; }
        catch (IllegalArgumentException e) { return false; }
    }

    public static String str(Object o, String def) { return o == null ? def : String.valueOf(o); }

    public static int num(Object o, int def) { return o instanceof Number n ? n.intValue() : def; }

    public static double dbl(Object o, double def) { return o instanceof Number n ? n.doubleValue() : def; }

    public static int[] point(Object o) {
        if (o instanceof List<?> l && l.size() >= 3 && l.get(0) instanceof Number a && l.get(1) instanceof Number b
                && l.get(2) instanceof Number c)
            return new int[]{a.intValue(), b.intValue(), c.intValue()};
        return null;
    }

    public static List<int[]> points(Object o) {
        List<int[]> out = new ArrayList<>();
        if (o instanceof List<?> l) for (Object e : l) { int[] p = point(e); if (p != null) out.add(p); }
        return out;
    }

    public static List<Map<String, Integer>> waves(Object o) {
        List<Map<String, Integer>> out = new ArrayList<>();
        if (o instanceof List<?> l) {
            for (Object e : l) {
                if (!(e instanceof Map<?, ?> m)) continue;
                Map<String, Integer> w = new LinkedHashMap<>();
                m.forEach((k, v) -> w.put(String.valueOf(k).toUpperCase(Locale.ROOT), v instanceof Number n ? n.intValue() : 1));
                out.add(w);
            }
        }
        return out;
    }
}
