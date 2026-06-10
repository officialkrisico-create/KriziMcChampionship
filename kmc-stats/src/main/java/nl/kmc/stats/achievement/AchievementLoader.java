package nl.kmc.stats.achievement;

import nl.kmc.core.domain.*;
import org.bukkit.Material;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Loads {@link AchievementDefinition}s from YAML files.
 *
 * <p>On first run: copies bundled default files from the jar into
 * {@code plugins/<plugin>/achievements/}. On every (re)load: reads all
 * {@code *.yml} files in that directory.
 *
 * <p>File format:
 * <pre>
 * achievements:
 *   - id: first_kill
 *     name: "First Blood"
 *     description: "Get your first kill in any game"
 *     category: PVP
 *     rarity: COMMON
 *     icon: IRON_SWORD
 *     hidden: false
 *     trigger: KILL_COUNT
 *     progress_target: 1
 *     reward_type: BADGE
 *     reward_value: badge_first_blood
 * </pre>
 */
public final class AchievementLoader {

    private static final Logger LOG = Logger.getLogger(AchievementLoader.class.getName());

    private static final String[] BUNDLED_FILES = {
        "achievements/pvp.yml",
        "achievements/championship.yml",
        "achievements/progression.yml",
        "achievements/movement.yml",
        "achievements/team.yml",
        "achievements/chaos.yml",
        "achievements/secret.yml",
        "achievements/legendary.yml",
        "achievements/tnttag.yml",
        "achievements/blockparty.yml"
    };

    private final JavaPlugin plugin;

    public AchievementLoader(JavaPlugin plugin) {
        this.plugin = plugin;
    }

    /**
     * Saves bundled default files to the plugin data folder if they don't
     * already exist, then loads all {@code *.yml} from the achievements folder.
     */
    public List<AchievementDefinition> loadAll() {
        Path achievementsDir = plugin.getDataFolder().toPath().resolve("achievements");
        try {
            Files.createDirectories(achievementsDir);
        } catch (IOException e) {
            LOG.log(Level.WARNING, "Could not create achievements directory", e);
        }

        // Copy bundled defaults — saveResource(path, false) is already a no-op if the file exists
        for (String path : BUNDLED_FILES) {
            try {
                plugin.saveResource(path, false);
            } catch (Exception e) {
                LOG.warning("[KMC/Achievements] Could not save default: " + path + " — " + e.getMessage());
            }
        }

        // Load all *.yml files
        List<AchievementDefinition> all = new ArrayList<>();
        File[] files = achievementsDir.toFile().listFiles((d, n) -> n.endsWith(".yml"));
        if (files == null) return all;

        for (File f : files) {
            try {
                YamlConfiguration cfg = YamlConfiguration.loadConfiguration(f);
                List<?> list = cfg.getList("achievements", List.of());
                for (Object entry : list) {
                    if (!(entry instanceof Map<?, ?> map)) continue;
                    AchievementDefinition def = parse(map);
                    if (def != null) all.add(def);
                }
            } catch (Exception e) {
                LOG.log(Level.WARNING, "[KMC/Achievements] Failed to parse " + f.getName(), e);
            }
        }

        LOG.info("[KMC/Achievements] Loaded " + all.size() + " achievement(s) from " +
                (files.length) + " file(s).");
        return all;
    }

    @SuppressWarnings("unchecked")
    private AchievementDefinition parse(Map<?, ?> map) {
        try {
            String id   = str(map, "id");
            String name = str(map, "name");
            if (id == null || id.isBlank() || name == null || name.isBlank()) return null;

            AchievementDefinition.Builder b = AchievementDefinition.builder(id, name);

            String desc = str(map, "description");
            if (desc != null) b.description(desc);

            String cat = str(map, "category");
            if (cat != null) b.category(parseEnum(AchievementCategory.class, cat, AchievementCategory.PROGRESSION));

            String rar = str(map, "rarity");
            if (rar != null) b.rarity(parseEnum(AchievementDefinition.Rarity.class, rar, AchievementDefinition.Rarity.COMMON));

            String icon = str(map, "icon");
            if (icon != null) {
                Material mat = Material.matchMaterial(icon); // matchMaterial is case-insensitive
                if (mat != null) b.icon(mat);
            }

            Object hidden = map.get("hidden");
            if (hidden instanceof Boolean bool) b.hidden(bool);

            String trig = str(map, "trigger");
            if (trig != null) b.trigger(parseEnum(AchievementTrigger.class, trig, AchievementTrigger.MANUAL));

            Object target = map.get("progress_target");
            if (target instanceof Number n) b.progressTarget(n.intValue());

            String scopeGame = str(map, "scope_game_id");
            if (scopeGame != null) b.scopeGameId(scopeGame);

            String objType = str(map, "objective_type");
            if (objType != null) b.objectiveType(objType);

            Object objThreshold = map.get("objective_threshold");
            if (objThreshold instanceof Number n) b.objectiveThreshold(n.longValue());

            String clutchType = str(map, "clutch_type");
            if (clutchType != null) b.clutchType(clutchType);

            String rewardType = str(map, "reward_type");
            String rewardVal  = str(map, "reward_value");
            if (rewardType != null) {
                AchievementReward.Type rt = parseEnum(AchievementReward.Type.class, rewardType, AchievementReward.Type.NONE);
                b.reward(new AchievementReward(rt, rewardVal != null ? rewardVal : ""));
            }

            return b.build();
        } catch (Exception e) {
            LOG.warning("[KMC/Achievements] Skipping malformed entry: " + e.getMessage());
            return null;
        }
    }

    private static String str(Map<?, ?> m, String key) {
        Object v = m.get(key);
        return v != null ? v.toString() : null;
    }

    private static <T extends Enum<T>> T parseEnum(Class<T> cls, String value, T fallback) {
        try {
            return Enum.valueOf(cls, value.toUpperCase());
        } catch (IllegalArgumentException e) {
            LOG.warning("[KMC/Achievements] Unknown " + cls.getSimpleName() + " value: " + value + " — using " + fallback);
            return fallback;
        }
    }
}
