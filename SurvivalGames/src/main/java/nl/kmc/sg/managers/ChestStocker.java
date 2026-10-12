package nl.kmc.sg.managers;

import nl.kmc.sg.SurvivalGamesPlugin;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.Chest;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.scheduler.BukkitTask;

import java.util.*;

/**
 * Scans the Survival Games world for chests inside the border radius
 * around the cornucopia, and stocks them with random loot from
 * config-defined tables.
 *
 * <p>Two tiers based on distance from the cornucopia:
 * <ul>
 *   <li>CORNUCOPIA — within 15 blocks of cornucopia center: best loot,
 *       enchanted weapons, golden apples, ender pearls</li>
 *   <li>OUTER — chests beyond cornucopia radius but within border:
 *       basic + intermediate gear</li>
 * </ul>
 *
 * <p>Scanning is async-chunked because a 200×200×100 region has ~4M
 * blocks. We scan in vertical columns from the center outward,
 * 16 columns per tick.
 */
public class ChestStocker {

    /** OUTER = common, CORNUCOPIA = rare, DROP = epic (supply drops), FEAST = legendary. */
    public enum Tier { CORNUCOPIA, OUTER, FEAST, DROP }

    public record LootEntry(Material material, int minAmount, int maxAmount,
                            double chance, Map<String, Integer> enchants, String customName) {}

    private final SurvivalGamesPlugin plugin;
    private final Map<Tier, List<LootEntry>> lootTables = new EnumMap<>(Tier.class);
    private final Set<Long> stockedChests = new HashSet<>();
    /** Every chest that was stocked as a cornucopia-tier chest (for the REFILL cornucopia event). */
    private final List<Location> rareChests = new ArrayList<>();
    private BukkitTask scanTask;

    public ChestStocker(SurvivalGamesPlugin plugin) {
        this.plugin = plugin;
        loadLootTables();
    }

    public void loadLootTables() {
        lootTables.put(Tier.CORNUCOPIA, new ArrayList<>());
        lootTables.put(Tier.OUTER, new ArrayList<>());
        lootTables.put(Tier.FEAST, new ArrayList<>());
        lootTables.put(Tier.DROP, new ArrayList<>());
        var cfg = plugin.getConfig();
        loadTier(cfg, "loot.cornucopia", Tier.CORNUCOPIA);
        loadTier(cfg, "loot.outer", Tier.OUTER);
        loadTier(cfg, "loot.feast", Tier.FEAST);
        loadTier(cfg, "loot.drop", Tier.DROP);
        if (lootTables.get(Tier.DROP).isEmpty()) lootTables.get(Tier.DROP).addAll(lootTables.get(Tier.FEAST));
        plugin.getLogger().info("Loaded loot tables: cornucopia="
                + lootTables.get(Tier.CORNUCOPIA).size() + ", outer="
                + lootTables.get(Tier.OUTER).size() + ", feast="
                + lootTables.get(Tier.FEAST).size());
    }

    private void loadTier(org.bukkit.configuration.file.FileConfiguration cfg,
                          String path, Tier tier) {
        List<?> list = cfg.getList(path);
        if (list == null) return;
        for (Object o : list) {
            if (!(o instanceof java.util.Map<?, ?> m)) continue;
            try {
                String matName = String.valueOf(m.get("material"));
                Material mat = Material.matchMaterial(matName);
                if (mat == null) throw new IllegalArgumentException("Unknown material: " + matName);
                // No building in Survival Games: block items (except TNT, which auto-primes) stay out of the chests.
                if (mat.isBlock() && mat != Material.TNT) continue;
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

    /**
     * Scan the arena world for chests within the border radius and
     * stock them. Async chunked — calls onComplete when done.
     */
    public void stockAllAsync(Runnable onComplete) {
        cancelTasks();
        stockedChests.clear();
        rareChests.clear();
        double rareChance = Math.max(0, Math.min(1, plugin.getConfig().getDouble("loot.rare-chest-chance", 0.1)));
        Random tierRng = new Random();

        var arena = plugin.getArenaManager().getArena();
        World world = arena.getWorld();
        var cor = arena.getCornucopiaCenter();
        double radius = arena.getBorderRadius();

        if (world == null || cor == null || radius <= 0) {
            plugin.getLogger().warning("Arena not configured for stocking.");
            onComplete.run();
            return;
        }

        double cornucopiaInner = plugin.getConfig().getDouble("game.cornucopia-radius", 15);
        int cornucopiaInnerSq = (int) (cornucopiaInner * cornucopiaInner);
        int radiusSq = (int) (radius * radius);

        // Build column list (x,z pairs) inside the border circle
        int cx = cor.getBlockX();
        int cz = cor.getBlockZ();
        int radiusInt = (int) Math.ceil(radius);

        List<int[]> columns = new ArrayList<>();
        for (int dx = -radiusInt; dx <= radiusInt; dx++) {
            for (int dz = -radiusInt; dz <= radiusInt; dz++) {
                if (dx * dx + dz * dz > radiusSq) continue;
                columns.add(new int[]{cx + dx, cz + dz});
            }
        }

        plugin.getLogger().info("Scanning " + columns.size() + " columns for chests...");

        int yMin = world.getMinHeight();
        int yMax = world.getMaxHeight();
        int columnsPerTick = plugin.getConfig().getInt("game.columns-per-tick", 16);

        Iterator<int[]> iter = columns.iterator();
        int[] count = {0};

        scanTask = Bukkit.getScheduler().runTaskTimer(plugin, () -> {
            for (int i = 0; i < columnsPerTick && iter.hasNext(); i++) {
                int[] col = iter.next();
                int x = col[0], z = col[1];
                for (int y = yMin; y < yMax; y++) {
                    Block b = world.getBlockAt(x, y, z);
                    if (b.getType() != Material.CHEST && b.getType() != Material.TRAPPED_CHEST) continue;
                    long key = encodePos(x, y, z);
                    if (!stockedChests.add(key)) continue;
                    int distSq = (x - cx) * (x - cx) + (z - cz) * (z - cz);
                    Tier tier = (distSq <= cornucopiaInnerSq) ? Tier.CORNUCOPIA : Tier.OUTER;
                    // A few outer chests are "rare": they hold cornucopia-tier loot, so exploring pays off.
                    if (tier == Tier.OUTER && tierRng.nextDouble() < rareChance) tier = Tier.CORNUCOPIA;
                    if (tier == Tier.CORNUCOPIA) rareChests.add(b.getLocation());
                    fillChest((Chest) b.getState(), tier);
                    count[0]++;
                }
            }
            if (!iter.hasNext()) {
                scanTask.cancel();
                scanTask = null;
                plugin.getLogger().info("Stocked " + count[0] + " chests for SG.");
                onComplete.run();
            }
        }, 1L, 1L);
    }

    private void fillChest(Chest chest, Tier tier) {
        Inventory inv = chest.getBlockInventory();
        inv.clear();

        List<LootEntry> table = lootTables.get(tier);
        if (table == null || table.isEmpty()) return;

        Random rng = new Random();
        List<Integer> slots = new ArrayList<>();
        for (int i = 0; i < inv.getSize(); i++) slots.add(i);
        Collections.shuffle(slots, rng);
        int slotIdx = 0;

        int minItems = plugin.getConfig().getInt("loot.min-items-per-chest", 3);
        int maxItems = plugin.getConfig().getInt("loot.max-items-per-chest", 7);
        int targetItems = minItems + rng.nextInt(Math.max(1, maxItems - minItems + 1));

        List<LootEntry> candidates = new ArrayList<>();
        for (LootEntry e : table) {
            if (rng.nextDouble() < e.chance()) candidates.add(e);
        }
        Collections.shuffle(candidates, rng);

        int placed = 0;
        for (LootEntry e : candidates) {
            if (placed >= targetItems) break;
            if (slotIdx >= slots.size()) break;
            inv.setItem(slots.get(slotIdx++), buildStack(e, rng, tier));
            placed++;
        }
    }

    private static String rarityLabel(Tier tier) {
        return switch (tier) {
            case OUTER -> "§7Common";
            case CORNUCOPIA -> "§9Rare";
            case DROP -> "§5Epic";
            case FEAST -> "§6Legendary";
        };
    }

    private ItemStack buildStack(LootEntry e, Random rng, Tier tier) {
        int amount = e.minAmount() + rng.nextInt(Math.max(1, e.maxAmount() - e.minAmount() + 1));
        ItemStack stack = new ItemStack(e.material(), amount);
        nl.kmc.game.api.PotionLoot.apply(stack, rng, null);
        for (var entry : e.enchants().entrySet()) {
            try {
                var ench = org.bukkit.enchantments.Enchantment.getByName(entry.getKey().toUpperCase());
                if (ench == null) {
                    ench = org.bukkit.enchantments.Enchantment.getByKey(
                            org.bukkit.NamespacedKey.minecraft(entry.getKey().toLowerCase()));
                }
                if (ench != null) stack.addUnsafeEnchantment(ench, entry.getValue());
            } catch (Exception ex) { /* ignore */ }
        }
        if (e.customName() != null) {
            ItemMeta m = stack.getItemMeta();
            if (m != null) {
                m.displayName(net.kyori.adventure.text.Component.text(e.customName()));
                stack.setItemMeta(m);
            }
        }
        // Gear (not food/arrows) shows its rarity so players can read the loot tier at a glance.
        if (plugin.getConfig().getBoolean("loot.rarity-lore", true) && stack.getMaxStackSize() == 1) {
            ItemMeta m = stack.getItemMeta();
            if (m != null) {
                var lore = m.hasLore() && m.lore() != null ? new ArrayList<>(m.lore())
                        : new ArrayList<net.kyori.adventure.text.Component>();
                lore.add(net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer.legacySection()
                        .deserialize(rarityLabel(tier)));
                m.lore(lore);
                stack.setItemMeta(m);
            }
        }
        return stack;
    }

    /**
     * Places (or re-fills, if one's already there) a single chest of
     * guaranteed top-tier "Feast" loot directly at the given centre —
     * the one-time deathmatch reward that draws every survivor back to
     * the cornucopia. Returns false if the location's world is missing.
     */
    public boolean spawnFeastChest(Location center) {
        if (center == null || center.getWorld() == null) return false;
        Block b = center.clone().add(0, 1, 0).getBlock();
        b.setType(Material.CHEST);
        if (!(b.getState() instanceof Chest chest)) return false;
        fillChest(chest, Tier.FEAST);
        return true;
    }

    /**
     * Puts an epic supply-drop chest at {@code at} (the block itself; an air block or a replaceable one).
     * Returns the chest's location, or null when it could not be placed.
     */
    public Location spawnDropChest(Location at) {
        if (at == null || at.getWorld() == null) return null;
        Block b = at.getBlock();
        b.setType(Material.CHEST);
        if (!(b.getState() instanceof Chest chest)) return null;
        fillChest(chest, Tier.DROP);
        return b.getLocation();
    }

    /** Refills every cornucopia/rare chest found during the last scan; returns how many were refilled. */
    public int restockRareChests() {
        int n = 0;
        for (Location l : rareChests) {
            if (l.getWorld() == null) continue;
            Block b = l.getBlock();
            if (b.getType() != Material.CHEST && b.getType() != Material.TRAPPED_CHEST) continue;
            if (b.getState() instanceof Chest chest) { fillChest(chest, Tier.CORNUCOPIA); n++; }
        }
        return n;
    }

    public void cancelTasks() {
        if (scanTask != null) { scanTask.cancel(); scanTask = null; }
    }

    public int getStockedCount() { return stockedChests.size(); }

    private static long encodePos(int x, int y, int z) {
        return ((long) (x & 0xFFFFFF) << 40)
             | ((long) (z & 0xFFFFFF) << 16)
             | (y & 0xFFFF);
    }
}
