package nl.kmc.luckyblock.managers;

import nl.kmc.luckyblock.LuckyBlockPlugin;
import nl.kmc.luckyblock.models.LootEntry;
import org.bukkit.Material;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.entity.EntityType;
import org.bukkit.potion.PotionEffectType;

import java.util.*;

/**
 * Reads the {@code loot} block from config.yml and builds a
 * weighted list of {@link LootEntry} objects.
 *
 * <p>Picking a random entry uses a weighted-random algorithm:
 * all weights are summed, a random number in [0, total) is
 * generated, and entries are walked until the number is consumed.
 */
public class LootTableManager {

    private final LuckyBlockPlugin plugin;
    private final List<LootEntry>  entries = new ArrayList<>();
    private int                    totalWeight;
    private final Random           random  = new Random();

    public LootTableManager(LuckyBlockPlugin plugin) {
        this.plugin = plugin;
        load();
    }

    // ----------------------------------------------------------------
    // Loading
    // ----------------------------------------------------------------

    private void load() {
        entries.clear();
        totalWeight = 0;

        ConfigurationSection lootSec = plugin.getConfig().getConfigurationSection("loot");
        if (lootSec == null) {
            plugin.getLogger().warning("No loot table found in config.yml!");
            return;
        }

        for (String key : lootSec.getKeys(false)) {
            ConfigurationSection sec = lootSec.getConfigurationSection(key);
            if (sec == null) continue;

            String typeStr = sec.getString("type", "ITEM").toUpperCase();
            LootEntry.Type type;
            try { type = LootEntry.Type.valueOf(typeStr); }
            catch (IllegalArgumentException e) {
                plugin.getLogger().warning("Unknown loot type '" + typeStr + "' for entry: " + key);
                continue;
            }

            int    weight  = sec.getInt("weight", 10);
            String message = sec.getString("message", "");
            LootEntry entry = new LootEntry(key, type, weight, message);

            // Parse type-specific fields
            switch (type) {
                case ITEM -> {
                    Material mat = parseMaterial(sec.getString("item", "STONE"), key);
                    entry.setItem(mat);
                    entry.setAmount(sec.getInt("amount", 1));
                    Map<Enchantment, Integer> enchants = new HashMap<>();
                    ConfigurationSection enchSec = sec.getConfigurationSection("enchants");
                    if (enchSec != null) {
                        for (String eName : enchSec.getKeys(false)) {
                            Enchantment ench = Enchantment.getByName(eName);
                            if (ench != null) enchants.put(ench, enchSec.getInt(eName, 1));
                        }
                    }
                    entry.setEnchants(enchants);
                }
                case EFFECT -> {
                    PotionEffectType pet = PotionEffectType.getByName(
                            sec.getString("effect", "SPEED").toUpperCase());
                    if (pet == null) pet = PotionEffectType.SPEED;
                    entry.setPotionEffect(pet);
                    entry.setDurationSeconds(sec.getInt("duration-seconds", 10));
                    entry.setAmplifier(sec.getInt("amplifier", 0));
                }
                case EXPLOSION -> entry.setExplosionPower((float) sec.getDouble("power", 2.0));
                case MOB -> {
                    EntityType et;
                    try { et = EntityType.valueOf(sec.getString("mob", "ZOMBIE").toUpperCase()); }
                    catch (IllegalArgumentException e) { et = EntityType.ZOMBIE; }
                    entry.setMobType(et);
                    entry.setMobCount(sec.getInt("count", 1));
                }
                case COINS, POINTS -> entry.setRewardAmount(sec.getInt("amount", 10));
                case FULL_ARMOR -> {
                    Material m = parseMaterial(sec.getString("material", "IRON"), key);
                    entry.setArmorMaterial(m);
                }
                case TNT_RAIN -> entry.setTntCount(sec.getInt("count", 5));
                default -> { /* no extra fields */ }
            }

            entries.add(entry);
            totalWeight += weight;
        }

        plugin.getLogger().info("Loaded " + entries.size() + " loot entries (total weight: " + totalWeight + ").");
    }

    // ----------------------------------------------------------------
    // Picking
    // ----------------------------------------------------------------

    /**
     * Returns a randomly selected loot entry using weighted probability.
     * Returns {@code null} if the loot table is empty.
     */
    public LootEntry pick() {
        if (entries.isEmpty()) return null;
        int roll = random.nextInt(totalWeight);
        int cumulative = 0;
        for (LootEntry e : entries) {
            cumulative += e.getWeight();
            if (roll < cumulative) return e;
        }
        return entries.get(entries.size() - 1); // fallback
    }

    // ----------------------------------------------------------------
    // Helper
    // ----------------------------------------------------------------

    private Material parseMaterial(String name, String context) {
        try { return Material.valueOf(name.toUpperCase()); }
        catch (IllegalArgumentException e) {
            plugin.getLogger().warning("Invalid material '" + name + "' for loot entry: " + context);
            return Material.STONE;
        }
    }

    public List<LootEntry> getEntries() { return Collections.unmodifiableList(entries); }
}
