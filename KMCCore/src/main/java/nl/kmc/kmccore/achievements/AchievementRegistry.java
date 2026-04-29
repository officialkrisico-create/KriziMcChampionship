package nl.kmc.kmccore.achievements;

import org.bukkit.Material;

import java.util.*;

/**
 * Static catalog of all achievements available in KMC. Loaded once at
 * plugin enable; immutable thereafter.
 *
 * <p>Adding a new achievement: add an entry in {@link #buildCatalog()},
 * then add its evaluation logic to {@link AchievementManager}'s event
 * handlers. Both pieces are needed.
 */
public final class AchievementRegistry {

    private static final Map<String, Achievement> CATALOG = buildCatalog();

    private AchievementRegistry() {}

    public static Collection<Achievement> getAll() {
        return Collections.unmodifiableCollection(CATALOG.values());
    }

    public static Achievement get(String id) {
        return CATALOG.get(id);
    }

    public static int count() { return CATALOG.size(); }

    public static List<Achievement> byRarity(Achievement.Rarity rarity) {
        List<Achievement> out = new ArrayList<>();
        for (Achievement a : CATALOG.values()) if (a.getRarity() == rarity) out.add(a);
        return out;
    }

    private static Map<String, Achievement> buildCatalog() {
        Map<String, Achievement> m = new LinkedHashMap<>();

        // ============================================================
        // COMMON (12) — easy to earn, encourage participation
        // ============================================================
        add(m, "first_blood",     "First Blood",     "Maak je eerste kill in een toernooi",
                Achievement.Rarity.COMMON, Material.IRON_SWORD, 0);
        add(m, "first_win",       "First Win",       "Win je eerste game",
                Achievement.Rarity.COMMON, Material.GOLD_INGOT, 0);
        add(m, "first_finish",    "First Finish",    "Finish een race-game (AE, PKW, of Elytra)",
                Achievement.Rarity.COMMON, Material.GOLDEN_BOOTS, 0);
        add(m, "team_player",     "Team Player",     "Sluit je aan bij een team",
                Achievement.Rarity.COMMON, Material.LEATHER_CHESTPLATE, 0);
        add(m, "participant",     "Participant",     "Speel 5 games",
                Achievement.Rarity.COMMON, Material.PAPER, 5);
        add(m, "regular",         "Regular",         "Speel 25 games",
                Achievement.Rarity.COMMON, Material.BOOK, 25);
        add(m, "century",         "Century Club",    "Verdien 100 punten in één toernooi",
                Achievement.Rarity.COMMON, Material.EMERALD, 100);
        add(m, "knockout",        "Knockout",        "Maak 3 kills in één game",
                Achievement.Rarity.COMMON, Material.NETHERITE_SWORD, 3);
        add(m, "voter",           "Democratic",      "Stem op een game in de vote-GUI",
                Achievement.Rarity.COMMON, Material.OAK_SIGN, 0);
        add(m, "ready",           "Always Ready",    "Gebruik /kmcready 10 keer",
                Achievement.Rarity.COMMON, Material.LIME_DYE, 10);
        add(m, "stylist",         "Stylist",         "Pas je preferences aan via /kmcprefs",
                Achievement.Rarity.COMMON, Material.LEATHER_HELMET, 0);
        add(m, "social",          "Social",          "Gebruik team chat (/tc) 25 keer",
                Achievement.Rarity.COMMON, Material.WRITABLE_BOOK, 25);

        // ============================================================
        // RARE (6) — meaningful effort, broadcast on unlock
        // ============================================================
        add(m, "hat_trick",       "Hat Trick",       "Win 3 games op rij",
                Achievement.Rarity.RARE, Material.PLAYER_HEAD, 3);
        add(m, "speedrunner",     "Speedrunner",     "Finish Adventure Escape in <60s",
                Achievement.Rarity.RARE, Material.FEATHER, 0);
        add(m, "untouchable",     "Untouchable",     "Win een game zonder te sterven",
                Achievement.Rarity.RARE, Material.SHIELD, 0);
        add(m, "pentakill",       "Pentakill",       "Maak 5 kills in één game",
                Achievement.Rarity.RARE, Material.DIAMOND_SWORD, 5);
        add(m, "veteran",         "Veteran",         "Speel 100 games",
                Achievement.Rarity.RARE, Material.ENCHANTED_BOOK, 100);
        add(m, "high_scorer",     "High Scorer",     "Verdien 500 punten in één toernooi",
                Achievement.Rarity.RARE, Material.DIAMOND, 500);

        // ============================================================
        // LEGENDARY (4) — exceptional, the goals of an event
        // ============================================================
        add(m, "tournament_mvp",  "Tournament MVP",  "Eindig als #1 speler van een toernooi",
                Achievement.Rarity.LEGENDARY, Material.NETHER_STAR, 0);
        add(m, "team_champion",   "Team Champion",   "Win een toernooi met je team",
                Achievement.Rarity.LEGENDARY, Material.GOLDEN_APPLE, 0);
        add(m, "decathlete",      "Decathlete",      "Win minstens één van elke 12 game-types",
                Achievement.Rarity.LEGENDARY, Material.BEACON, 12);
        add(m, "legend",          "Legend",          "Speel 500 games en win minstens 50",
                Achievement.Rarity.LEGENDARY, Material.DRAGON_EGG, 0);

        return m;
    }

    private static void add(Map<String, Achievement> m, String id, String name, String desc,
                            Achievement.Rarity rarity, Material icon, int threshold) {
        m.put(id, new Achievement(id, name, desc, rarity, icon, threshold));
    }
}
