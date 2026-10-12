package nl.kmc.hitw.config;

import nl.kmc.hitw.wall.SpeedCurve;
import nl.kmc.hitw.wall.WallTypeDef;
import nl.kmc.hitw.wall.WallTypes;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Typed view of config.yml. Re-read at the start of every game so edits apply without a restart.
 * Missing keys fall back to the defaults bundled in the jar's config.yml (Bukkit merges those in).
 */
public final class HitwConfig {

    // game
    public int    rounds;
    public int    roundMaxSeconds;
    public int    roundCountdownSeconds;
    public int    resultsSeconds;
    public double firstWallDelaySeconds;

    // walls
    public double spawnMargin;
    public double exitMargin;
    public double spacingBlocks;
    public double minArrivalSeparationSeconds;
    public int    updateTicks;
    public double sprintSpeed;
    public double reachFactor;
    public double minReach;
    public int    gapPlayersForBase;
    public double gapExtraPerPlayer;
    public double gapMaxScale;
    public List<Double> directionsDeg = new ArrayList<>();
    /** Names of the 8 sides a wall can come from (see WallSideNames: index 0 = east, 45° steps clockwise). */
    public List<String> sideNames = new ArrayList<>();

    // collision
    public double playerRadius;
    public double tolerance;
    public double standHeight;
    public double sneakHeight;
    public double fallBelow;

    // points
    public final Map<Integer, Integer> roundPlacementPoints = new HashMap<>();
    public int roundPlacementDefault;
    public int wallsMilestone;

    public final WallTypes wallTypes = new WallTypes();
    public SpeedCurve speedCurve;
    public final List<PatternDef> patterns = new ArrayList<>();
    public final List<EventDef> events = new ArrayList<>();

    // events
    public boolean eventsEnabled;
    public double  eventsStartAfter;
    public double  eventsMinGap;
    public double  eventsMaxGap;

    public void load(FileConfiguration cfg) {
        rounds                = Math.max(1, cfg.getInt("game.rounds", 3));
        roundMaxSeconds       = Math.max(10, cfg.getInt("game.round-max-seconds", 120));
        roundCountdownSeconds = Math.max(0, cfg.getInt("game.round-countdown-seconds", 3));
        resultsSeconds        = Math.max(2, cfg.getInt("game.round-results-seconds", 8));
        firstWallDelaySeconds = Math.max(0, cfg.getDouble("game.first-wall-delay-seconds", 2.5));

        spawnMargin                 = cfg.getDouble("walls.spawn-margin", 14);
        exitMargin                  = cfg.getDouble("walls.exit-margin", 10);
        spacingBlocks               = Math.max(4, cfg.getDouble("walls.spacing-blocks", 22));
        minArrivalSeparationSeconds = Math.max(0.2, cfg.getDouble("walls.min-arrival-separation-seconds", 1.1));
        updateTicks                 = Math.max(1, Math.min(4, cfg.getInt("walls.update-ticks", 2)));
        sprintSpeed                 = cfg.getDouble("walls.sprint-speed", 5.6);
        reachFactor                 = cfg.getDouble("walls.reach-factor", 0.55);
        minReach                    = cfg.getDouble("walls.min-reach", 4.0);
        gapPlayersForBase           = Math.max(0, cfg.getInt("walls.gap.players-for-base", 6));
        gapExtraPerPlayer           = Math.max(0, cfg.getDouble("walls.gap.extra-per-player", 0.08));
        gapMaxScale                 = Math.max(1, cfg.getDouble("walls.gap.max-scale", 2.2));

        directionsDeg.clear();
        for (Object o : cfg.getList("walls.directions", List.of(0, 90, 180, 270))) {
            if (o instanceof Number n) directionsDeg.add(n.doubleValue());
        }
        if (directionsDeg.isEmpty()) directionsDeg.add(0.0);

        sideNames.clear();
        for (String s : cfg.getStringList("walls.side-names")) sideNames.add(s);
        if (sideNames.size() != 8) {
            sideNames.clear();
            sideNames.addAll(nl.kmc.hitw.wall.WallSideNames.DEFAULT);
        }

        playerRadius = cfg.getDouble("collision.player-radius", 0.3);
        tolerance    = cfg.getDouble("collision.tolerance", 0.05);
        standHeight  = cfg.getDouble("collision.stand-height", 1.8);
        sneakHeight  = cfg.getDouble("collision.sneak-height", 1.5);
        fallBelow    = cfg.getDouble("collision.fall-below", 3.0);

        roundPlacementPoints.clear();
        ConfigurationSection pts = cfg.getConfigurationSection("points.round-placement");
        roundPlacementDefault = 0;
        if (pts != null) {
            for (String key : pts.getKeys(false)) {
                if (key.equalsIgnoreCase("default")) { roundPlacementDefault = pts.getInt(key); continue; }
                try { roundPlacementPoints.put(Integer.parseInt(key), pts.getInt(key)); }
                catch (NumberFormatException ignored) { /* not a placement key */ }
            }
        }
        wallsMilestone = Math.max(1, cfg.getInt("points.walls-milestone", 25));

        loadWallTypes(cfg.getConfigurationSection("wall-types"));
        loadSpeedCurve(cfg.getMapList("speed-curve"));
        loadPatterns(cfg.getConfigurationSection("patterns"));
        loadEvents(cfg);
    }

    /** Round points for a placement (1 = best). */
    public int pointsForPlacement(int placement) {
        return roundPlacementPoints.getOrDefault(placement, roundPlacementDefault);
    }

    // ── Sections ──────────────────────────────────────────────────────────────

    private void loadWallTypes(ConfigurationSection sec) {
        wallTypes.clear();
        if (sec == null) return;
        for (String id : sec.getKeys(false)) {
            ConfigurationSection t = sec.getConfigurationSection(id);
            if (t == null) continue;
            wallTypes.register(new WallTypeDef(id.toUpperCase(),
                    t.getString("shape", "HOLES"),
                    t.getString("display-name", id.toUpperCase().replace('_', ' ')),
                    t.getString("material", "ORANGE_CONCRETE"),
                    t.getInt("holes", 1),
                    t.getDouble("gap-width", 3.0),
                    t.getDouble("height", 5.0),
                    t.getDouble("y-min", 0.0),
                    t.getDouble("y-max", 1.0),
                    t.getDouble("thickness", 1.0),
                    t.getDouble("speed-mult", 1.0)));
        }
    }

    private void loadSpeedCurve(List<Map<?, ?>> list) {
        List<SpeedCurve.Key> keys = new ArrayList<>();
        for (Map<?, ?> m : list) {
            Object t = m.get("t"), s = m.get("speed");
            if (t instanceof Number tn && s instanceof Number sn) keys.add(new SpeedCurve.Key(tn.doubleValue(), sn.doubleValue()));
        }
        speedCurve = new SpeedCurve(keys);
    }

    private void loadPatterns(ConfigurationSection sec) {
        patterns.clear();
        if (sec == null) return;
        for (String name : sec.getKeys(false)) {
            ConfigurationSection p = sec.getConfigurationSection(name);
            if (p == null) continue;
            List<PatternDef.Step> steps = new ArrayList<>();
            for (Map<?, ?> m : p.getMapList("steps")) {
                Object type = m.get("type"), count = m.get("count");
                if (type == null) continue;
                steps.add(new PatternDef.Step(String.valueOf(type).toUpperCase(),
                        count instanceof Number n ? Math.max(1, n.intValue()) : 1));
            }
            if (steps.isEmpty()) continue;
            patterns.add(new PatternDef(name, p.getDouble("weight", 1.0),
                    p.getDouble("min-time", 0), p.getDouble("max-time", 1.0e9),
                    p.getDouble("rotation", 0), p.getDouble("speed-mult", 1.0), steps));
        }
    }

    private void loadEvents(FileConfiguration cfg) {
        events.clear();
        eventsEnabled    = cfg.getBoolean("events.enabled", true);
        eventsStartAfter = cfg.getDouble("events.start-after-seconds", 15);
        eventsMinGap     = cfg.getDouble("events.min-gap-seconds", 10);
        eventsMaxGap     = Math.max(eventsMinGap, cfg.getDouble("events.max-gap-seconds", 22));

        ConfigurationSection sec = cfg.getConfigurationSection("events.list");
        if (sec == null) return;
        for (String id : sec.getKeys(false)) {
            ConfigurationSection e = sec.getConfigurationSection(id);
            if (e == null) continue;
            Map<String, Double> weights = new HashMap<>();
            ConfigurationSection pw = e.getConfigurationSection("pattern-weights");
            if (pw != null) for (String k : pw.getKeys(false)) weights.put(k, pw.getDouble(k));
            events.add(new EventDef(id, e.getDouble("weight", 1.0), e.getDouble("duration", 8),
                    e.getString("title", id.toUpperCase().replace('_', ' ')),
                    e.getString("subtitle", ""), e.getString("sound", "ENTITY_WITHER_SPAWN"),
                    e.getDouble("speed-mult", 1.0), e.getDouble("spacing-mult", 1.0),
                    e.getDouble("gap-scale", 1.0), e.getDouble("speed-bonus", 0.0), weights));
        }
    }
}
