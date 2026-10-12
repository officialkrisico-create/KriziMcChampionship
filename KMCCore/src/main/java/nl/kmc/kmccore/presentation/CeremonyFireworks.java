package nl.kmc.kmccore.presentation;

import nl.kmc.kmccore.KMCCore;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Color;
import org.bukkit.FireworkEffect;
import org.bukkit.Location;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Firework;
import org.bukkit.entity.Player;
import org.bukkit.inventory.meta.FireworkMeta;
import org.bukkit.scheduler.BukkitTask;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

/**
 * Configurable fireworks for the opening ceremony. Every {@link Moment} has its own on/off switch, spots
 * (locations an admin placed with {@code /kmcopeningsceremony}), firework shape, colours and number of salvos.
 * Moments without spots fire above the online players instead, so a fresh install still gets something.
 *
 * <p>Persisted in {@code plugins/KMCCore/ceremony-fireworks.yml}.
 */
public final class CeremonyFireworks {

    /** A point in the ceremony where fireworks can go off. */
    public enum Moment {
        OPENING      ("opening",             "Opening",         "Begin van de openingsceremonie"),
        HOW_IT_WORKS ("how-it-works",        "Zo werkt het",    "Start van de uitleg"),
        OVERVIEW     ("tournament-overview", "Rondes & punten", "Start van het ronde-overzicht"),
        TEAM_SHOWCASE("team-showcase",       "Team-showcase",   "Start van de team-showcase"),
        GAME_LINEUP  ("game-lineup",         "De games",        "Start van de game-line-up"),
        GAME_CARD    ("game-card",           "Per game-kaart",  "Bij elke game die getoond wordt"),
        FINALE       ("finale",              "Finale",          "Einde van de ceremonie, voor de eerste pauze");

        public final String id;
        public final String label;
        public final String description;

        Moment(String id, String label, String description) {
            this.id = id;
            this.label = label;
            this.description = description;
        }

        public static Moment byId(String s) {
            for (Moment m : values()) if (m.id.equalsIgnoreCase(s) || m.name().equalsIgnoreCase(s)) return m;
            return null;
        }
    }

    public static final class Setting {
        public boolean enabled;
        public final List<Location> spots = new ArrayList<>();
        public FireworkEffect.Type type = FireworkEffect.Type.BALL_LARGE;
        /** TEAM (team colours), GOLD or RAINBOW. */
        public String colors = "TEAM";
        public int salvos = 3;
        public boolean trail = true;
    }

    private static final FireworkEffect.Type[] TYPES = {
            FireworkEffect.Type.BALL, FireworkEffect.Type.BALL_LARGE, FireworkEffect.Type.STAR,
            FireworkEffect.Type.BURST, FireworkEffect.Type.CREEPER};
    private static final String[] COLOR_MODES = {"TEAM", "GOLD", "RAINBOW"};
    private static final int[] SALVO_STEPS = {1, 2, 3, 5, 8};

    private final KMCCore plugin;
    private final File file;
    private final Map<Moment, Setting> settings = new EnumMap<>(Moment.class);
    private final List<BukkitTask> running = new ArrayList<>();
    private final Random rng = new Random();
    private final List<Location> podium = new ArrayList<>();

    public CeremonyFireworks(KMCCore plugin) {
        this.plugin = plugin;
        this.file = new File(plugin.getDataFolder(), "ceremony-fireworks.yml");
        load();
    }

    // ── Settings ──────────────────────────────────────────────────────────────

    public Setting get(Moment m) { return settings.computeIfAbsent(m, CeremonyFireworks::defaults); }

    private static Setting defaults(Moment m) {
        Setting s = new Setting();
        s.enabled = m == Moment.OPENING || m == Moment.FINALE;
        return s;
    }

    public void cycleType(Moment m) {
        Setting s = get(m);
        int i = 0;
        for (int k = 0; k < TYPES.length; k++) if (TYPES[k] == s.type) i = k;
        s.type = TYPES[(i + 1) % TYPES.length];
        save();
    }

    public void cycleColors(Moment m) {
        Setting s = get(m);
        int i = 0;
        for (int k = 0; k < COLOR_MODES.length; k++) if (COLOR_MODES[k].equals(s.colors)) i = k;
        s.colors = COLOR_MODES[(i + 1) % COLOR_MODES.length];
        save();
    }

    public void cycleSalvos(Moment m) {
        Setting s = get(m);
        int i = 0;
        for (int k = 0; k < SALVO_STEPS.length; k++) if (SALVO_STEPS[k] == s.salvos) i = k;
        s.salvos = SALVO_STEPS[(i + 1) % SALVO_STEPS.length];
        save();
    }

    public void toggle(Moment m)      { Setting s = get(m); s.enabled = !s.enabled; save(); }
    public void toggleTrail(Moment m) { Setting s = get(m); s.trail = !s.trail; save(); }
    public void addSpot(Moment m, Location l) { get(m).spots.add(l.clone()); save(); }
    public void clearSpots(Moment m)  { get(m).spots.clear(); save(); }

    /** The team-showcase podium: one spot per player (yaw = the way they face; the camera sits in front). */
    public List<Location> getPodium() { return java.util.Collections.unmodifiableList(podium); }
    public void addPodiumSpot(Location l) { podium.add(l.clone()); save(); }
    public void clearPodium() { podium.clear(); save(); }

    // ── Playback ──────────────────────────────────────────────────────────────

    /** Fires the configured show for {@code m} (no-op if the moment is switched off). */
    public void play(Moment m) { play(m, false); }

    /** @param force play even if the moment is disabled (the "Test" button) */
    public void play(Moment m, boolean force) {
        Setting s = get(m);
        if (!s.enabled && !force) return;
        for (int i = 0; i < Math.max(1, s.salvos); i++) {
            final int salvo = i;
            running.add(Bukkit.getScheduler().runTaskLater(plugin, () -> salvo(s, salvo), i * 12L));
        }
    }

    /** Cancels pending salvos (ceremony skipped / stopped). */
    public void cancelAll() {
        running.forEach(BukkitTask::cancel);
        running.clear();
    }

    private void salvo(Setting s, int index) {
        List<Location> points = new ArrayList<>();
        if (!s.spots.isEmpty()) {
            points.addAll(s.spots);
        } else {
            List<Player> online = new ArrayList<>(Bukkit.getOnlinePlayers());
            Collections.shuffle(online, rng);
            for (Player p : online.subList(0, Math.min(6, online.size()))) points.add(p.getLocation().add(0, 3, 0));
        }
        List<Color> palette = palette(s.colors);
        for (Location at : points) {
            if (at.getWorld() == null) continue;
            try {
                Firework fw = at.getWorld().spawn(at.clone().add(0, 0.5, 0), Firework.class);
                FireworkMeta meta = fw.getFireworkMeta();
                Color c1 = palette.get(rng.nextInt(palette.size()));
                Color c2 = palette.get(rng.nextInt(palette.size()));
                meta.addEffect(FireworkEffect.builder().with(s.type).withColor(c1).withFade(c2)
                        .trail(s.trail).flicker(index % 2 == 0).build());
                meta.setPower(1);
                fw.setFireworkMeta(meta);
                fw.detonate();
            } catch (Exception ignored) { /* a bad spot must not break the ceremony */ }
        }
    }

    private List<Color> palette(String mode) {
        List<Color> out = new ArrayList<>();
        switch (mode == null ? "TEAM" : mode) {
            case "GOLD" -> {
                out.add(Color.fromRGB(255, 215, 0));
                out.add(Color.fromRGB(255, 170, 0));
                out.add(Color.WHITE);
            }
            case "RAINBOW" -> {
                out.add(Color.RED);
                out.add(Color.ORANGE);
                out.add(Color.YELLOW);
                out.add(Color.LIME);
                out.add(Color.AQUA);
                out.add(Color.BLUE);
                out.add(Color.FUCHSIA);
            }
            default -> {
                try {
                    for (var t : plugin.getTeamManager().getTeamsInOrder()) {
                        ChatColor c = t.getColor();
                        if (c != null) out.add(TeamShowcase.toColor(c));
                    }
                } catch (Exception ignored) { }
                if (out.isEmpty()) out.add(Color.WHITE);
            }
        }
        return out;
    }

    // ── Persistence ───────────────────────────────────────────────────────────

    private void load() {
        settings.clear();
        podium.clear();
        if (!file.exists()) return;
        YamlConfiguration yml = YamlConfiguration.loadConfiguration(file);
        for (Object o : yml.getList("podium", List.of())) {
            if (!(o instanceof Map<?, ?> map)) continue;
            try {
                var w = Bukkit.getWorld(String.valueOf(map.get("world")));
                if (w == null) continue;
                podium.add(new Location(w, ((Number) map.get("x")).doubleValue(), ((Number) map.get("y")).doubleValue(),
                        ((Number) map.get("z")).doubleValue(), ((Number) map.get("yaw")).floatValue(), 0f));
            } catch (Exception ignored) { }
        }
        for (Moment m : Moment.values()) {
            ConfigurationSection sec = yml.getConfigurationSection(m.id);
            if (sec == null) continue;
            Setting s = defaults(m);
            s.enabled = sec.getBoolean("enabled", s.enabled);
            try { s.type = FireworkEffect.Type.valueOf(sec.getString("type", s.type.name())); } catch (Exception ignored) { }
            s.colors = sec.getString("colors", s.colors);
            s.salvos = Math.max(1, sec.getInt("salvos", s.salvos));
            s.trail  = sec.getBoolean("trail", s.trail);
            for (Object o : sec.getList("spots", List.of())) {
                if (!(o instanceof Map<?, ?> map)) continue;
                try {
                    var w = Bukkit.getWorld(String.valueOf(map.get("world")));
                    if (w == null) continue;
                    s.spots.add(new Location(w, ((Number) map.get("x")).doubleValue(),
                            ((Number) map.get("y")).doubleValue(), ((Number) map.get("z")).doubleValue()));
                } catch (Exception ignored) { }
            }
            settings.put(m, s);
        }
    }

    public void save() {
        YamlConfiguration yml = new YamlConfiguration();
        for (Moment m : Moment.values()) {
            Setting s = get(m);
            String p = m.id + ".";
            yml.set(p + "enabled", s.enabled);
            yml.set(p + "type", s.type.name());
            yml.set(p + "colors", s.colors);
            yml.set(p + "salvos", s.salvos);
            yml.set(p + "trail", s.trail);
            List<Map<String, Object>> spots = new ArrayList<>();
            for (Location l : s.spots) {
                if (l.getWorld() == null) continue;
                spots.add(Map.of("world", l.getWorld().getName(), "x", l.getX(), "y", l.getY(), "z", l.getZ()));
            }
            yml.set(p + "spots", spots);
        }
        List<Map<String, Object>> pod = new ArrayList<>();
        for (Location l : podium) {
            if (l.getWorld() == null) continue;
            pod.add(Map.of("world", l.getWorld().getName(), "x", l.getX(), "y", l.getY(), "z", l.getZ(), "yaw", (double) l.getYaw()));
        }
        yml.set("podium", pod);
        try {
            plugin.getDataFolder().mkdirs();
            yml.save(file);
        } catch (IOException e) {
            plugin.getLogger().warning("Could not save ceremony-fireworks.yml: " + e.getMessage());
        }
    }
}
