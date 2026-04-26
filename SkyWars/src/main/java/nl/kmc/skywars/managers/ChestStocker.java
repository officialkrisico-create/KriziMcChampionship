package nl.kmc.skywars.managers;

import nl.kmc.skywars.SkyWarsPlugin;
import nl.kmc.skywars.models.Island;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.block.Chest;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

import java.util.*;

/**
 * Scans for chests near each island spawn + the middle area, then
 * fills them with weighted random loot.
 *
 * <p>Two loot tiers:
 * <ul>
 *   <li>ISLAND — basic gear, lower-tier weapons, basic armor</li>
 *   <li>MIDDLE — better gear, enchanted weapons, golden apples,
 *       potions, ender pearls, etc.</li>
 * </ul>
 *
 * <p>Loot tables are defined in config.yml.
 */
public class ChestStocker {

    private final SkyWarsPlugin plugin;

    /** Tier of a chest — determines which loot table it draws from. */
    public enum Tier { ISLAND, MIDDLE }

    /** A single loot entry: material + amount range + chance + optional enchants. */
    public record LootEntry(Material material, int minAmount, int maxAmount,
                            double chance, Map<String, Integer> enchants,
                            String customName) {}

    /** Tracks chests we've already stocked this game (so we don't double-fill them). */
    private final Set<Long> stockedChests = new HashSet<>();
    private final Map<Tier, List<LootEntry>> lootTables = new EnumMap<>(Tier.class);

    public ChestStocker(SkyWarsPlugin plugin) {
        this.plugin = plugin;
        loadLootTables();
    }

    public void loadLootTables() {
        lootTables.put(Tier.ISLAND, new ArrayList<>());
        lootTables.put(Tier.MIDDLE, new ArrayList<>());
        var cfg = plugin.getConfig();

        loadTier(cfg, "loot.island", Tier.ISLAND);
        loadTier(cfg, "loot.middle", Tier.MIDDLE);

        plugin.getLogger().info("Loaded loot tables: island="
                + lootTables.get(Tier.ISLAND).size() + " entries, middle="
                + lootTables.get(Tier.MIDDLE).size() + " entries");
    }

    private void loadTier(org.bukkit.configuration.file.FileConfiguration cfg,
                          String path, Tier tier) {
        List<?> list = cfg.getList(path);
        if (list == null) return;
        for (Object o : list) {
            if (!(o instanceof java.util.Map<?, ?> m)) continue;
            try {
                String matName = String.valueOf(m.get("material"));
                Material mat = Material.valueOf(matName.toUpperCase());
                int minA = m.containsKey("min") ? ((Number) m.get("min")).intValue() : 1;
                int maxA = m.containsKey("max") ? ((Number) m.get("max")).intValue() : minA;
                double chance = m.containsKey("chance") ? ((Number) m.get("chance")).doubleValue() : 1.0;
                Map<String, Integer> enchants = new HashMap<>();
                if (m.get("enchants") instanceof java.util.Map<?, ?> e) {
                    for (var entry : e.entrySet()) {
                        enchants.put(String.valueOf(entry.getKey()),
                                ((Number) entry.getValue()).intValue());
                    }
                }
                String name = m.containsKey("name") ? String.valueOf(m.get("name")) : null;
                lootTables.get(tier).add(new LootEntry(mat, minA, maxA, chance, enchants, name));
            } catch (Exception e) {
                plugin.getLogger().warning("Bad loot entry in " + path + ": " + o);
            }
        }
    }

    public void resetForNewGame() {
        stockedChests.clear();
    }

    /**
     * Stock all chests in the arena: each team island uses ISLAND
     * loot, the middle uses MIDDLE loot.
     */
    public int stockAll() {
        resetForNewGame();
        int total = 0;
        var arena = plugin.getArenaManager();

        for (Island i : arena.getIslands().values()) {
            total += stockNearLocation(i.getSpawn(), i.getChestSearchRadius(), Tier.ISLAND);
        }
        if (arena.getMiddleSpawn() != null) {
            total += stockNearLocation(arena.getMiddleSpawn(), arena.getMiddleRadius(), Tier.MIDDLE);
        }

        plugin.getLogger().info("Stocked " + total + " chests for SkyWars game.");
        return total;
    }

    /** Find all chests within `radius` blocks of `center` and stock them. */
    private int stockNearLocation(Location center, int radius, Tier tier) {
        if (center == null) return 0;
        int count = 0;
        var world = center.getWorld();
        int cx = center.getBlockX();
        int cy = center.getBlockY();
        int cz = center.getBlockZ();

        for (int dx = -radius; dx <= radius; dx++) {
            for (int dy = -radius; dy <= radius; dy++) {
                for (int dz = -radius; dz <= radius; dz++) {
                    Block b = world.getBlockAt(cx + dx, cy + dy, cz + dz);
                    if (b.getType() != Material.CHEST && b.getType() != Material.TRAPPED_CHEST) continue;
                    long key = encodePos(b.getX(), b.getY(), b.getZ());
                    if (!stockedChests.add(key)) continue;
                    fillChest((Chest) b.getState(), tier);
                    count++;
                }
            }
        }
        return count;
    }

    private void fillChest(Chest chest, Tier tier) {
        Inventory inv = chest.getBlockInventory();
        inv.clear();

        List<LootEntry> table = lootTables.get(tier);
        if (table == null || table.isEmpty()) return;

        Random rng = new Random();
        // Each entry rolls independently against its chance.
        // Items get placed in random slots until full.
        List<Integer> slots = new ArrayList<>();
        for (int i = 0; i < inv.getSize(); i++) slots.add(i);
        Collections.shuffle(slots, rng);
        int slotIdx = 0;

        int minItems = plugin.getConfig().getInt("loot.min-items-per-chest", 3);
        int maxItems = plugin.getConfig().getInt("loot.max-items-per-chest", 7);
        int targetItems = minItems + rng.nextInt(Math.max(1, maxItems - minItems + 1));

        // Filter candidates by chance; shuffle; pick top N
        List<LootEntry> candidates = new ArrayList<>();
        for (LootEntry e : table) {
            if (rng.nextDouble() < e.chance()) candidates.add(e);
        }
        Collections.shuffle(candidates, rng);
        int placed = 0;
        for (LootEntry e : candidates) {
            if (placed >= targetItems) break;
            if (slotIdx >= slots.size()) break;
            ItemStack stack = buildStack(e, rng);
            inv.setItem(slots.get(slotIdx++), stack);
            placed++;
        }
    }

    private ItemStack buildStack(LootEntry e, Random rng) {
        int amount = e.minAmount() + rng.nextInt(Math.max(1, e.maxAmount() - e.minAmount() + 1));
        ItemStack stack = new ItemStack(e.material(), amount);
        if (!e.enchants().isEmpty()) {
            for (var entry : e.enchants().entrySet()) {
                try {
                    var ench = org.bukkit.enchantments.Enchantment.getByName(entry.getKey().toUpperCase());
                    if (ench == null) {
                        ench = org.bukkit.enchantments.Enchantment.getByKey(
                                org.bukkit.NamespacedKey.minecraft(entry.getKey().toLowerCase()));
                    }
                    if (ench != null) stack.addUnsafeEnchantment(ench, entry.getValue());
                } catch (Exception ex) { /* ignore unknown enchant */ }
            }
        }
        if (e.customName() != null) {
            ItemMeta m = stack.getItemMeta();
            if (m != null) {
                m.displayName(net.kyori.adventure.text.Component.text(e.customName()));
                stack.setItemMeta(m);
            }
        }
        return stack;
    }

    private static long encodePos(int x, int y, int z) {
        return ((long) (x & 0xFFFFFF) << 40)
             | ((long) (z & 0xFFFFFF) << 16)
             | (y & 0xFFFF);
    }

    public int getStockedCount() { return stockedChests.size(); }
}
