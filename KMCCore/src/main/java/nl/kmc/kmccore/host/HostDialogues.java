package nl.kmc.kmccore.host;

import org.bukkit.configuration.ConfigurationSection;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * The dialogue definitions of {@code host.yml}: what the host says, to whom, how important it is and how
 * often it may be said. Parsing never throws on bad config: a broken entry becomes a warning and is skipped.
 */
public final class HostDialogues {

    private HostDialogues() { }

    /** Every placeholder a dialogue may use. Anything else is reported as a config warning. */
    public static final Set<String> KNOWN = Set.of(
            "player", "team", "team_color", "game", "game_description", "game_objective", "game_time",
            "team_score", "player_score", "leaderboard_position", "leaderboard_leader", "leader_points",
            "winner", "top_team_1", "top_team_2", "top_team_3", "team_count", "round", "rounds", "gap",
            "second", "tournament_name", "games_played", "games_total", "top_player", "top_player_points",
            "game_winner", "finalist_a", "finalist_b", "place", "place_team", "place_points", "host",
            "team_slogan", "team_members", "editions", "last_champion", "last_edition", "titles_leader", "titles_count",
            "award_mvp", "award_killer", "award_winner", "award_teammate", "award_comeback", "award_team_wins",
            "seats", "seats_word", "seats_cap",
            "award_name", "award_desc", "award_who", "award_value", "award_index", "award_total", "award_team",
            "mvp_name", "mvp_team", "mvp_count", "record_kills", "record_wins", "record_streak");

    public enum Audience {
        ALL, TEAM, SPECTATORS, PLAYER, ADMINS;

        public static Audience parse(String s, Audience def) {
            if (s == null) return def;
            try { return valueOf(s.trim().toUpperCase(Locale.ROOT)); }
            catch (IllegalArgumentException e) { return def; }
        }
    }

    public static final int LOW = 10, NORMAL = 50, HIGH = 80, CRITICAL = 100;

    public static int parsePriority(Object o, int def) {
        if (o instanceof Number n) return Math.max(0, Math.min(100, n.intValue()));
        if (o == null) return def;
        return switch (String.valueOf(o).trim().toUpperCase(Locale.ROOT)) {
            case "LOW" -> LOW;
            case "NORMAL" -> NORMAL;
            case "HIGH" -> HIGH;
            case "CRITICAL" -> CRITICAL;
            default -> def;
        };
    }

    /**
     * The staging of one line in a staged dialogue ({@code staged: true}): the camera shot, how the host
     * moves, a gesture, an effect, a title on every screen, and how tense the moment is (0-10, -1 = rising
     * automatically over the speech). Every field may be empty.
     */
    public record Stage(String shot, String walk, String act, String fx, String title, String subtitle, int tension) {
        public static final Stage NONE = new Stage("", "", "", "", "", "", -1);

        /** A later page of a line that was split over several pages: the same shot, nothing new happens. */
        public static Stage continuation(int tension) {
            return new Stage("~", "", "", "", "", "", tension);
        }

        public boolean isContinuation() { return "~".equals(shot); }

        public boolean isEmpty() {
            return shot.isEmpty() && walk.isEmpty() && act.isEmpty() && fx.isEmpty() && title.isEmpty()
                    && subtitle.isEmpty() && tension < 0;
        }
    }

    /** One spoken sentence. Text per language ({@code *} = any); a value of -1 means "use the default". */
    public record Line(Map<String, String> text, int delayTicks, int holdTicks, String sound, List<String> requires,
                       Stage stage) {

        public Line(Map<String, String> text, int delayTicks, int holdTicks, String sound, List<String> requires) {
            this(text, delayTicks, holdTicks, sound, requires, Stage.NONE);
        }

        public String text(String lang) {
            if (text.isEmpty()) return "";
            String t = text.get(lang);
            if (t == null) t = text.get("*");
            if (t == null) t = text.get("nl");
            if (t == null) t = text.values().iterator().next();
            return t;
        }
    }

    public record Dialogue(String id, List<Line> lines, List<Line> variants, Audience audience, int priority,
                           int cooldownSeconds, boolean skippable, boolean onlyOnStart, int maxWaitSeconds,
                           boolean once, String cutscene, boolean focus, boolean staged) {

        public Dialogue(String id, List<Line> lines, List<Line> variants, Audience audience, int priority,
                        int cooldownSeconds, boolean skippable, boolean onlyOnStart, int maxWaitSeconds,
                        boolean once, String cutscene, boolean focus) {
            this(id, lines, variants, audience, priority, cooldownSeconds, skippable, onlyOnStart, maxWaitSeconds,
                    once, cutscene, focus, false);
        }
    }

    public record Parsed(Map<String, Dialogue> dialogues, List<String> warnings) { }

    // ── Parsing ───────────────────────────────────────────────────────────────

    public static Parsed parse(ConfigurationSection dialogues, ConfigurationSection defaults) {
        Map<String, Dialogue> out = new LinkedHashMap<>();
        List<String> warnings = new ArrayList<>();
        if (dialogues == null) return new Parsed(out, warnings);
        for (String id : dialogues.getKeys(false)) {
            ConfigurationSection s = dialogues.getConfigurationSection(id);
            if (s == null) { warnings.add("dialogue '" + id + "' is not a section"); continue; }
            Dialogue d = parseOne(id, s, defaults, warnings);
            if (d != null) out.put(id, d);
        }
        return new Parsed(out, warnings);
    }

    private static Dialogue parseOne(String id, ConfigurationSection s, ConfigurationSection def, List<String> warnings) {
        List<Line> lines = parseLines(id, s.getList("lines"), warnings);
        List<Line> variants = parseLines(id, s.getList("variants"), warnings);
        if (lines.isEmpty() && variants.isEmpty()) {
            warnings.add("dialogue '" + id + "' has no lines");
            return null;
        }
        Audience audience = Audience.parse(s.getString("audience", def != null ? def.getString("audience", "ALL") : "ALL"), Audience.ALL);
        int priority = parsePriority(s.get("priority", def != null ? def.get("priority") : null), NORMAL);
        int cooldown = s.getInt("cooldown-seconds", def != null ? def.getInt("cooldown-seconds", 0) : 0);
        boolean skippable = s.getBoolean("skippable", def == null || def.getBoolean("skippable", true));
        boolean onlyStart = s.getBoolean("only-on-start", false);
        int maxWait = s.getInt("max-wait-seconds", def != null ? def.getInt("max-wait-seconds", 25) : 25);
        boolean once = s.getBoolean("once", false);
        String cutscene = s.getString("cutscene", "");
        boolean focus = s.getBoolean("focus", false);
        boolean anyStage = false;
        for (Line l : lines) if (!l.stage().isEmpty()) { anyStage = true; break; }
        boolean staged = s.getBoolean("staged", anyStage);      // a line with shot/walk/act is staged by itself
        return new Dialogue(id, lines, variants, audience, priority, Math.max(0, cooldown), skippable, onlyStart,
                Math.max(1, maxWait), once, cutscene == null || cutscene.isBlank() ? null : cutscene, focus, staged);
    }

    private static List<Line> parseLines(String id, List<?> raw, List<String> warnings) {
        List<Line> out = new ArrayList<>();
        if (raw == null) return out;
        for (Object o : raw) {
            Line l = parseLine(id, o, warnings);
            if (l != null) out.add(l);
        }
        return out;
    }

    private static Line parseLine(String id, Object o, List<String> warnings) {
        Map<String, String> text = new LinkedHashMap<>();
        int delay = -1, hold = -1;
        String sound = null;
        List<String> requires = new ArrayList<>();
        Stage stage = Stage.NONE;
        if (o instanceof String str) {
            text.put("*", str);
        } else if (o instanceof Map<?, ?> m) {
            Object t = m.get("text");
            if (t instanceof String str) text.put("*", str);
            else if (t instanceof Map<?, ?> tm) tm.forEach((k, v) -> text.put(String.valueOf(k), String.valueOf(v)));
            Object d = m.get("delay");
            if (d instanceof Number n) delay = (int) Math.round(n.doubleValue() * 20);
            Object h = m.get("duration");
            if (h instanceof Number n) hold = (int) Math.round(n.doubleValue() * 20);
            Object snd = m.get("sound");
            if (snd != null) sound = String.valueOf(snd);
            Object req = m.get("requires");
            if (req instanceof List<?> rl) for (Object r : rl) requires.add(String.valueOf(r));
            else if (req instanceof String rs) requires.add(rs);
            stage = parseStage(id, m, warnings);
        } else {
            warnings.add("dialogue '" + id + "': a line is neither text nor a section");
            return null;
        }
        text.values().removeIf(v -> v == null || v.isBlank());
        if (text.isEmpty()) {
            warnings.add("dialogue '" + id + "': an empty line was skipped");
            return null;
        }
        for (String t : text.values()) {
            for (String name : HostText.placeholders(t)) {
                if (!KNOWN.contains(name))
                    warnings.add("dialogue '" + id + "': unknown placeholder {" + name + "}");
            }
        }
        for (String r : requires) {
            if (!KNOWN.contains(r)) warnings.add("dialogue '" + id + "': 'requires' names unknown placeholder " + r);
        }
        return new Line(text, delay, hold, sound, requires, stage);
    }

    private static String str(Map<?, ?> m, String key) {
        Object v = m.get(key);
        return v == null ? "" : String.valueOf(v).trim();
    }

    private static Stage parseStage(String id, Map<?, ?> m, List<String> warnings) {
        String shot = str(m, "shot").toLowerCase(Locale.ROOT);
        String walk = str(m, "walk").toLowerCase(Locale.ROOT);
        String act = str(m, "act").toLowerCase(Locale.ROOT);
        String fx = str(m, "fx").toLowerCase(Locale.ROOT);
        String title = str(m, "title");
        String subtitle = str(m, "subtitle");
        int tension = -1;
        if (m.get("tension") instanceof Number n) tension = Math.max(0, Math.min(10, n.intValue()));
        if (!shot.isEmpty() && !SceneMath.SHOTS.contains(shot)) {
            warnings.add("dialogue '" + id + "': unknown shot '" + shot + "' (use " + String.join(", ", SceneMath.SHOTS) + ")");
            shot = "";
        }
        if (!walk.isEmpty() && SceneMath.parseWalk(walk) == null) {
            warnings.add("dialogue '" + id + "': walk '" + walk + "' is not forward/back/left/right[:blocks] or home");
            walk = "";
        }
        if (!act.isEmpty() && !SceneMath.ACTS.contains(act)) {
            warnings.add("dialogue '" + id + "': unknown act '" + act + "' (use " + String.join(", ", new java.util.TreeSet<>(SceneMath.ACTS)) + ")");
            act = "";
        }
        if (!fx.isEmpty() && !SceneMath.FX.contains(fx)) {
            warnings.add("dialogue '" + id + "': unknown fx '" + fx + "' (use " + String.join(", ", new java.util.TreeSet<>(SceneMath.FX)) + ")");
            fx = "";
        }
        return new Stage(shot, walk, act, fx, title, subtitle, tension);
    }
}
