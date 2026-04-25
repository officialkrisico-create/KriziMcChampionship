package nl.kmc.bingo.managers;

import nl.kmc.bingo.BingoPlugin;
import nl.kmc.bingo.models.BingoCard;
import nl.kmc.bingo.objectives.*;
import org.bukkit.Material;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.EntityType;

import java.util.*;

/**
 * Generates a randomized bingo card.
 *
 * <p>Reads pools from config:
 * <ul>
 *   <li>collect-pool: list of "MATERIAL:amount" entries</li>
 *   <li>craft-pool:   list of "MATERIAL"</li>
 *   <li>kill-pool:    list of "ENTITY:amount"</li>
 * </ul>
 *
 * <p>Distribution: card is filled with a configurable mix (e.g.
 * 60% collect, 25% craft, 15% kill). Same card every time the
 * RNG is seeded with the same seed — seed = current time at game start.
 */
public class CardGenerator {

    private final BingoPlugin plugin;

    public CardGenerator(BingoPlugin plugin) { this.plugin = plugin; }

    public BingoCard generate(long seed) {
        Random rng = new Random(seed);

        // Ratios from config
        double pctCollect = plugin.getConfig().getDouble("card.distribution.collect", 0.60);
        double pctCraft   = plugin.getConfig().getDouble("card.distribution.craft",   0.25);
        // Kill = remainder

        int totalSquares = BingoCard.TOTAL;
        int nCollect = (int) Math.round(totalSquares * pctCollect);
        int nCraft   = (int) Math.round(totalSquares * pctCraft);
        int nKill    = totalSquares - nCollect - nCraft;
        if (nKill < 0) { nKill = 0; nCraft = totalSquares - nCollect; }

        List<BingoObjective> pool = new ArrayList<>();
        pool.addAll(takeRandom(loadCollectPool(), nCollect, rng));
        pool.addAll(takeRandom(loadCraftPool(),   nCraft,   rng));
        pool.addAll(takeRandom(loadKillPool(),    nKill,    rng));

        // If pools were too small, top up with fallback collects
        while (pool.size() < totalSquares) {
            pool.add(new CollectObjective(Material.COBBLESTONE, 16));
        }

        Collections.shuffle(pool, rng);
        return new BingoCard(pool.subList(0, totalSquares));
    }

    // ----------------------------------------------------------------

    private List<BingoObjective> loadCollectPool() {
        List<BingoObjective> out = new ArrayList<>();
        List<String> entries = plugin.getConfig().getStringList("card.collect-pool");
        for (String entry : entries) {
            String[] parts = entry.split(":");
            try {
                Material m = Material.valueOf(parts[0].toUpperCase());
                int amount = parts.length > 1 ? Integer.parseInt(parts[1]) : 1;
                out.add(new CollectObjective(m, amount));
            } catch (Exception e) {
                plugin.getLogger().warning("Bad collect entry: " + entry);
            }
        }
        return out;
    }

    private List<BingoObjective> loadCraftPool() {
        List<BingoObjective> out = new ArrayList<>();
        for (String entry : plugin.getConfig().getStringList("card.craft-pool")) {
            try {
                out.add(new CraftObjective(Material.valueOf(entry.toUpperCase())));
            } catch (Exception e) {
                plugin.getLogger().warning("Bad craft entry: " + entry);
            }
        }
        return out;
    }

    private List<BingoObjective> loadKillPool() {
        List<BingoObjective> out = new ArrayList<>();
        for (String entry : plugin.getConfig().getStringList("card.kill-pool")) {
            String[] parts = entry.split(":");
            try {
                EntityType et = EntityType.valueOf(parts[0].toUpperCase());
                int amount = parts.length > 1 ? Integer.parseInt(parts[1]) : 1;
                out.add(new KillObjective(et, amount));
            } catch (Exception e) {
                plugin.getLogger().warning("Bad kill entry: " + entry);
            }
        }
        return out;
    }

    private <T> List<T> takeRandom(List<T> source, int n, Random rng) {
        if (source.size() <= n) return new ArrayList<>(source);
        Collections.shuffle(source, rng);
        return new ArrayList<>(source.subList(0, n));
    }
}
