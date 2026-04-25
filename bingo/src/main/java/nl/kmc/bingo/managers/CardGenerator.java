package nl.kmc.bingo.managers;

import nl.kmc.bingo.BingoPlugin;
import nl.kmc.bingo.models.BingoCard;
import nl.kmc.bingo.objectives.BingoObjective;
import nl.kmc.bingo.objectives.CollectObjective;
import org.bukkit.Material;

import java.util.*;

/**
 * Builds a randomized 5×5 bingo card from the {@code card.collect-pool}
 * config list.
 *
 * <p>The same RNG seed produces the same card — we use the game start
 * time as the seed so all teams in a single game get an identical card,
 * but every game gets a fresh one.
 */
public class CardGenerator {

    private final BingoPlugin plugin;

    public CardGenerator(BingoPlugin plugin) { this.plugin = plugin; }

    public BingoCard generate(long seed) {
        Random rng = new Random(seed);
        List<BingoObjective> pool = loadCollectPool();

        if (pool.size() < BingoCard.TOTAL) {
            // Pad with cobblestone:16 if pool is too small (avoids crash)
            plugin.getLogger().warning("Collect pool has only " + pool.size()
                    + " items — need " + BingoCard.TOTAL + ". Padding with COBBLESTONE.");
            while (pool.size() < BingoCard.TOTAL) {
                pool.add(new CollectObjective(Material.COBBLESTONE, 16));
            }
        }

        Collections.shuffle(pool, rng);
        return new BingoCard(pool.subList(0, BingoCard.TOTAL));
    }

    private List<BingoObjective> loadCollectPool() {
        List<BingoObjective> out = new ArrayList<>();
        for (String entry : plugin.getConfig().getStringList("card.collect-pool")) {
            String[] parts = entry.split(":");
            try {
                Material m = Material.valueOf(parts[0].toUpperCase());
                int amount = parts.length > 1 ? Integer.parseInt(parts[1]) : 1;
                out.add(new CollectObjective(m, amount));
            } catch (Exception e) {
                plugin.getLogger().warning("Bad collect entry in config: " + entry);
            }
        }
        return out;
    }
}
