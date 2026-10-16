package nl.kmc.kmccore.host;

import nl.kmc.kmccore.KMCCore;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.File;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Everything the host says, from {@code host.yml}: dialogues, lore order, team slogans, per-game extras and
 * the menu. The jar's copy provides the defaults, so a dialogue added in a later version is available even
 * when the server's own host.yml predates it; a dialogue in the server's file replaces the default of the
 * same id.
 */
public final class HostContent {

    public static final String FILE = "host.yml";

    private final KMCCore plugin;
    private YamlConfiguration cfg = new YamlConfiguration();
    private final Map<String, HostDialogues.Dialogue> dialogues = new LinkedHashMap<>();
    private final List<String> warnings = new ArrayList<>();

    public HostContent(KMCCore plugin) { this.plugin = plugin; }

    /** (Re)loads host.yml. Never throws: problems become {@link #warnings()}. */
    public void load() {
        dialogues.clear();
        editionCache = null;
        warnings.clear();
        File f = new File(plugin.getDataFolder(), FILE);
        try {
            if (!f.isFile()) plugin.saveResource(FILE, false);
        } catch (Throwable t) {
            warnings.add("could not write " + FILE + ": " + t.getMessage());
        }
        YamlConfiguration defaults = null;
        try (var in = plugin.getResource(FILE)) {
            if (in != null) defaults = YamlConfiguration.loadConfiguration(new InputStreamReader(in, StandardCharsets.UTF_8));
        } catch (Throwable t) {
            warnings.add("could not read the default " + FILE + ": " + t.getMessage());
        }
        YamlConfiguration user = f.isFile() ? YamlConfiguration.loadConfiguration(f) : new YamlConfiguration();
        if (defaults != null) {
            user.setDefaults(defaults);
            var base = HostDialogues.parse(defaults.getConfigurationSection("dialogues"),
                    defaults.getConfigurationSection("dialogue-defaults"));
            dialogues.putAll(base.dialogues());
        }
        var mine = HostDialogues.parse(user.getConfigurationSection("dialogues"),
                user.getConfigurationSection("dialogue-defaults"));
        dialogues.putAll(mine.dialogues());
        warnings.addAll(mine.warnings());
        cfg = user;
    }

    private Map<String, String> editionCache;
    private long editionCacheAt;

    /** Archive-derived values, cached for a minute (reading them means reading the edition files). */
    public Map<String, String> editionValues() {
        long now = System.currentTimeMillis();
        if (editionCache == null || now - editionCacheAt > 60_000L) {
            editionCache = HostValues.editions(plugin);
            editionCacheAt = now;
        }
        return editionCache;
    }

    public void invalidateCaches() { editionCache = null; }

    public List<String> warnings() { return List.copyOf(warnings); }

    public HostDialogues.Dialogue dialogue(String id) { return dialogues.get(id); }

    public int dialogueCount() { return dialogues.size(); }

    public String slogan(String teamId) {
        if (teamId == null) return "";
        ConfigurationSection s = cfg.getConfigurationSection("team-slogans");
        return s == null ? "" : s.getString(teamId, s.getString(teamId.toLowerCase(), ""));
    }

    public List<String> loreOrder() {
        List<String> order = cfg.getStringList("lore-order");
        if (!order.isEmpty()) return order;
        List<String> out = new ArrayList<>();
        for (String id : dialogues.keySet()) if (id.startsWith("lore-") && !id.endsWith("-empty")) out.add(id.substring(5));
        return out;
    }

    public ConfigurationSection menu() { return cfg.getConfigurationSection("menu"); }

    // ── Games ─────────────────────────────────────────────────────────────────

    public String gameString(String gameId, String key) {
        String v = cfg.getString("games." + gameId + "." + key, "");
        return v == null ? "" : v;
    }

    public List<String> gameList(String gameId, String key) {
        return cfg.getStringList("games." + gameId + "." + key);
    }

    private String defaultText(String key, String fallback) {
        return cfg.getString("game-defaults." + key, fallback);
    }

    public boolean hasGameInfo(String gameId) { return cfg.isConfigurationSection("games." + gameId); }

    /**
     * Builds the introduction of a game from what the game itself registered (description, objective,
     * scoring lines) and the extras in host.yml. Only configured or registered facts end up in it.
     *
     * @param sections which parts, in order: intro, lore, goal, rules, controls, scoring, warnings, duration, closing
     */
    public HostDialogues.Dialogue gameIntro(String gameId, List<String> sections, int maxScoringLines) {
        List<HostDialogues.Line> lines = new ArrayList<>();
        for (String section : sections) {
            switch (section.toLowerCase()) {
                case "intro" -> {
                    add(lines, defaultText("intro", "Het is tijd voor {game}!"));
                    add(lines, "{game_description}");
                }
                case "lore" -> add(lines, gameString(gameId, "lore"));
                case "goal" -> add(lines, defaultText("goal", "Het doel: {game_objective}"));
                case "rules" -> gameList(gameId, "rules").forEach(s -> add(lines, s));
                case "controls" -> gameList(gameId, "controls").forEach(s -> add(lines, s));
                case "scoring" -> {
                    var reg = HostValues.registration(gameId);
                    if (reg.isPresent() && !reg.get().getScoringLines().isEmpty()) {
                        add(lines, defaultText("scoring-header", "Zo verdien je punten:"));
                        int n = 0;
                        for (String l : reg.get().getScoringLines()) {
                            if (n++ >= Math.max(1, maxScoringLines)) break;
                            add(lines, HostText.strip(l));
                        }
                    }
                }
                case "warnings" -> gameList(gameId, "warnings").forEach(s -> add(lines, s));
                case "duration" -> add(lines, defaultText("duration", "Speelduur: {game_time}."));
                case "closing" -> {
                    String c = gameString(gameId, "closing");
                    add(lines, c.isBlank() ? defaultText("closing", "") : c);
                }
                default -> { }
            }
        }
        return new HostDialogues.Dialogue("game-intro:" + gameId, lines, List.of(), HostDialogues.Audience.ALL,
                HostDialogues.HIGH, 0, true, false, 60, false, null, false);
    }

    private static void add(List<HostDialogues.Line> out, String text) {
        if (text == null || text.isBlank()) return;
        Map<String, String> t = new LinkedHashMap<>();
        t.put("*", text);
        out.add(new HostDialogues.Line(t, -1, -1, null, List.of()));
    }
}
