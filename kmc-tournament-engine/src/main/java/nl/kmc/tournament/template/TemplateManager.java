package nl.kmc.tournament.template;

import org.bukkit.configuration.file.YamlConfiguration;

import java.io.File;
import java.io.IOException;
import java.util.*;
import java.util.logging.Logger;

/**
 * Saves and loads {@link TournamentTemplate}s as YAML files under
 * {@code <dataFolder>/templates/}.
 */
public final class TemplateManager {

    private static final Logger LOG = Logger.getLogger(TemplateManager.class.getName());

    private final File                         templatesDir;
    private final Map<String, TournamentTemplate> cache = new LinkedHashMap<>();

    public TemplateManager(File dataFolder) {
        this.templatesDir = new File(dataFolder, "templates");
        if (!templatesDir.exists()) templatesDir.mkdirs();
    }

    // ── CRUD ─────────────────────────────────────────────────────────────────

    public void save(TournamentTemplate template) {
        File file = fileFor(template.getId());
        YamlConfiguration cfg = new YamlConfiguration();

        cfg.set("id",                      template.getId());
        cfg.set("display-name",            template.getDisplayName());
        cfg.set("description",             template.getDescription());
        cfg.set("total-rounds",            template.getTotalRounds());
        cfg.set("game-rotation",           template.getGameRotation());
        cfg.set("voting-enabled",          template.isVotingEnabled());
        cfg.set("voting-duration-seconds", template.getVotingDurationSeconds());

        // multipliers: 1: 1.0, 2: 2.0, ...
        Map<Integer, Double> mult = template.getRoundMultipliers();
        mult.forEach((round, m) -> cfg.set("round-multipliers." + round, m));

        try {
            cfg.save(file);
            cache.put(template.getId(), template);
            LOG.info("[KMC/Templates] Saved template: " + template.getId());
        } catch (IOException e) {
            LOG.severe("[KMC/Templates] Failed to save template " + template.getId() + ": " + e.getMessage());
        }
    }

    public Optional<TournamentTemplate> load(String id) {
        if (cache.containsKey(id)) return Optional.of(cache.get(id));
        File file = fileFor(id);
        if (!file.exists()) return Optional.empty();
        return Optional.ofNullable(parseFile(file));
    }

    public void loadAll() {
        cache.clear();
        File[] files = templatesDir.listFiles((d, name) -> name.endsWith(".yml"));
        if (files == null) return;
        for (File f : files) {
            TournamentTemplate t = parseFile(f);
            if (t != null) cache.put(t.getId(), t);
        }
        LOG.info("[KMC/Templates] Loaded " + cache.size() + " template(s).");
    }

    public boolean delete(String id) {
        cache.remove(id);
        return fileFor(id).delete();
    }

    public Collection<TournamentTemplate> getAll() {
        return Collections.unmodifiableCollection(cache.values());
    }

    // ── Import / export ───────────────────────────────────────────────────────

    /** Export to an arbitrary destination file. */
    public void export(TournamentTemplate template, File destination) throws IOException {
        YamlConfiguration cfg = YamlConfiguration.loadConfiguration(fileFor(template.getId()));
        cfg.save(destination);
    }

    /** Import from an external file; returns the imported template. */
    public Optional<TournamentTemplate> importFrom(File source) {
        if (!source.exists()) return Optional.empty();
        TournamentTemplate t = parseFile(source);
        if (t != null) save(t);
        return Optional.ofNullable(t);
    }

    // ── Internal ──────────────────────────────────────────────────────────────

    private File fileFor(String id) {
        return new File(templatesDir, id + ".yml");
    }

    private TournamentTemplate parseFile(File file) {
        YamlConfiguration cfg = YamlConfiguration.loadConfiguration(file);
        try {
            String id          = cfg.getString("id", file.getName().replace(".yml", ""));
            String displayName = cfg.getString("display-name", id);
            String description = cfg.getString("description", "");
            int    rounds      = cfg.getInt("total-rounds", 5);
            List<String> rotation = cfg.getStringList("game-rotation");
            boolean voting     = cfg.getBoolean("voting-enabled", true);
            int voteDuration   = cfg.getInt("voting-duration-seconds", 30);

            Map<Integer, Double> mult = new LinkedHashMap<>();
            var multSection = cfg.getConfigurationSection("round-multipliers");
            if (multSection != null) {
                for (String key : multSection.getKeys(false)) {
                    try { mult.put(Integer.parseInt(key), multSection.getDouble(key)); }
                    catch (NumberFormatException ignored) {}
                }
            }
            if (mult.isEmpty()) {
                for (int i = 1; i <= rounds; i++) mult.put(i, (double) i);
            }

            return TournamentTemplate.builder(id, displayName)
                    .description(description)
                    .totalRounds(rounds)
                    .gameRotation(rotation)
                    .roundMultipliers(mult)
                    .votingEnabled(voting)
                    .votingDurationSeconds(voteDuration)
                    .build();
        } catch (Exception e) {
            LOG.warning("[KMC/Templates] Failed to parse " + file.getName() + ": " + e.getMessage());
            return null;
        }
    }
}
