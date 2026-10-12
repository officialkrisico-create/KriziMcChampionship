package nl.kmc.kmccore.util;

import nl.kmc.kmccore.KMCCore;
import nl.kmc.kmccore.scoring.StatsBook;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

/**
 * Kept for source compatibility: the post-event book now lives in {@link StatsBook} (clickable contents,
 * game-by-game results from the points ledger, awards and track records).
 */
public final class EventStatsBookBuilder {

    private EventStatsBookBuilder() {}

    public static ItemStack buildBookFor(KMCCore plugin, Player viewer, int eventNumber) {
        return StatsBook.build(plugin, viewer, eventNumber);
    }
}
